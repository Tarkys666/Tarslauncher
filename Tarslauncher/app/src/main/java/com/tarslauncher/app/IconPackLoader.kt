package com.tarslauncher.app

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.drawable.Drawable
import androidx.collection.LruCache
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.res.ResourcesCompat
import com.tarslauncher.app.utils.drawableToBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

@Stable
class IconPackLoader(context: Context, private val packPackageName: String) {

    private val appContext: Context = context.applicationContext
    private val iconMap = ConcurrentHashMap<String, String>()
    private val packageFallbackMap = ConcurrentHashMap<String, String>()
    private val resIdCache = ConcurrentHashMap<String, Int>()

    @Volatile
    private var iconBackNames: List<String> = emptyList()

    @Volatile
    private var iconMaskName: String? = null

    @Volatile
    private var iconUponNames: List<String> = emptyList()

    @Volatile
    private var fallbackIconScale = 0.8f

    private val maxCacheSizeKb = (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt().coerceAtLeast(2048)
    private val imageBitmapCache = object : LruCache<String, ImageBitmap>(maxCacheSizeKb) {
        override fun sizeOf(key: String, value: ImageBitmap): Int {
            return ((value.width * value.height * 4) / 1024).coerceAtLeast(1)
        }
    }

    init {
        register(this)
    }

    fun clearCache() {
        imageBitmapCache.evictAll()
    }

    var isLoaded by mutableStateOf(false)
        private set

    @Volatile
    private var packResources: Resources? = null

    fun load() {
        if (isLoaded) return
        try {
            val resources = appContext.packageManager.getResourcesForApplication(packPackageName)
            packResources = resources
            loadAppFilter(resources)
            CoroutineScope(Dispatchers.Main).launch {
                isLoaded = true
            }
        } catch (_: PackageManager.NameNotFoundException) {
            // İkon paketi bulunamadı veya kaldırıldı
        }
    }

    private fun loadAppFilter(resources: Resources) {
        val appFilterId = resources.getIdentifier("appfilter", "xml", packPackageName)
        if (appFilterId != 0) {
            var xpp: android.content.res.XmlResourceParser? = null
            try {
                xpp = resources.getXml(appFilterId)
                parseXmlIntoMaps(xpp)
                return
            } catch (_: Exception) {
                // Fall through to assets/appfilter.xml
            } finally {
                xpp?.close()
            }
        }

        // Fallback: Many icon packs place appfilter.xml inside assets/
        var assetStream: InputStream? = null
        try {
            assetStream = resources.assets.open("appfilter.xml")
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(assetStream, "UTF-8")
            parseXmlIntoMaps(parser)
        } catch (_: Exception) {
            // appfilter.xml okunamadı
        } finally {
            runCatching { assetStream?.close() }
        }
    }

    private fun parseXmlIntoMaps(xpp: XmlPullParser) {
        val tempComponentMap = HashMap<String, String>(4096)
        val tempPackageMap = HashMap<String, String>(2048)
        while (xpp.eventType != XmlPullParser.END_DOCUMENT) {
            if (xpp.eventType == XmlPullParser.START_TAG) {
                when (xpp.name) {
                    "item" -> {
                        val component = xpp.getAttributeValue(null, "component")
                        val drawable = xpp.getAttributeValue(null, "drawable")
                        if (!component.isNullOrEmpty() && !drawable.isNullOrEmpty()) {
                            tempComponentMap[component] = drawable
                            if (component.startsWith("ComponentInfo{")) {
                                val pkg = component.removePrefix("ComponentInfo{").substringBefore("/")
                                if (pkg.isNotEmpty() && !tempPackageMap.containsKey(pkg)) {
                                    tempPackageMap[pkg] = drawable
                                }
                            }
                        }
                    }
                    "iconback" -> iconBackNames = (iconBackNames + readImageNames(xpp)).distinct()
                    "iconmask" -> readImageNames(xpp).firstOrNull()?.let { iconMaskName = it }
                    "iconupon" -> iconUponNames = (iconUponNames + readImageNames(xpp)).distinct()
                    "scale" -> {
                        xpp.getAttributeValue(null, "factor")
                            ?.toFloatOrNull()
                            ?.coerceIn(0.1f, 1f)
                            ?.let { fallbackIconScale = it }
                    }
                }
            }
            xpp.next()
        }
        iconMap.putAll(tempComponentMap)
        packageFallbackMap.putAll(tempPackageMap)
    }

    private fun readImageNames(parser: XmlPullParser): List<String> =
        (0 until parser.attributeCount)
            .mapNotNull { index ->
                val attributeName = parser.getAttributeName(index)
                if (!attributeName.startsWith("img", ignoreCase = true)) return@mapNotNull null
                val value = parser.getAttributeValue(index)
                    ?.substringAfterLast('/')
                    ?.removePrefix("@")
                    ?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                (attributeName.drop(3).toIntOrNull() ?: Int.MAX_VALUE) to value
            }
            .sortedBy { it.first }
            .map { it.second }

    private fun loadPackDrawable(drawableName: String, resources: Resources): Drawable? {
        val normalizedName = drawableName.substringAfterLast('/').removePrefix("@")
        val resId = resIdCache.getOrPut(normalizedName) {
            resources.getIdentifier(normalizedName, "drawable", packPackageName)
                .takeIf { it != 0 }
                ?: resources.getIdentifier(normalizedName, "mipmap", packPackageName)
        }
        return if (resId != 0) ResourcesCompat.getDrawable(resources, resId, null) else null
    }

    private fun hasFallbackIconTheme(): Boolean =
        iconBackNames.isNotEmpty() || iconMaskName != null || iconUponNames.isNotEmpty()

    private fun createFallbackIconBitmap(
        appPackageName: String,
        componentString: String,
        fallbackIcon: Drawable,
        resources: Resources
    ): Bitmap {
        val size = 192
        val bounds = Rect(0, 0, size, size)
        val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        val backgroundName = if (iconBackNames.isEmpty()) {
            null
        } else {
            iconBackNames[(appPackageName.hashCode() and Int.MAX_VALUE) % iconBackNames.size]
        }
        backgroundName?.let { name ->
            loadPackDrawable(name, resources)?.let { drawable ->
                drawable.bounds = bounds
                drawable.draw(canvas)
            }
        }

        val foreground = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val foregroundCanvas = Canvas(foreground)
        val iconBitmap = drawableToBitmap(
            fallbackIcon,
            "icon-pack-fallback-source:$packPackageName:$componentString"
        )
        val iconSize = (size * fallbackIconScale).toInt().coerceIn(1, size)
        val inset = (size - iconSize) / 2
        val iconBounds = Rect(inset, inset, size - inset, size - inset)
        foregroundCanvas.drawBitmap(iconBitmap, null, iconBounds, bitmapPaint)

        iconMaskName?.let { name ->
            loadPackDrawable(name, resources)?.let { mask ->
                val maskBitmap = drawableToBitmap(mask, "icon-pack-mask:$packPackageName:$name")
                val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                }
                foregroundCanvas.drawBitmap(maskBitmap, null, bounds, maskPaint)
                maskPaint.xfermode = null
            }
        }

        canvas.drawBitmap(foreground, 0f, 0f, bitmapPaint)
        foreground.recycle()

        iconUponNames.firstOrNull()?.let { name ->
            loadPackDrawable(name, resources)?.let { overlay ->
                val overlayBitmap = drawableToBitmap(overlay, "icon-pack-overlay:$packPackageName:$name")
                canvas.drawBitmap(overlayBitmap, null, bounds, bitmapPaint)
            }
        }

        return result
    }

    fun getIconForApp(appPackageName: String, appActivityName: String): Drawable? {
        if (!isLoaded) return null
        val resources = packResources ?: return null

        val componentString = "ComponentInfo{$appPackageName/$appActivityName}"

        val drawableName = iconMap[componentString]
            ?: packageFallbackMap[appPackageName]
            ?: return null

        return try {
            loadPackDrawable(drawableName, resources)
        } catch (_: Exception) {
            null
        }
    }

    fun getImageBitmapForApp(
        appPackageName: String,
        appActivityName: String,
        fallbackIcon: Drawable? = null
    ): ImageBitmap? {
        val componentString = "ComponentInfo{$appPackageName/$appActivityName}"

        // Don't cache the temporary system icon while the pack is still loading.
        if (!isLoaded) {
            val pendingIcon = fallbackIcon ?: return null
            return try {
                drawableToBitmap(
                    pendingIcon,
                    "icon-pack-pending:$packPackageName:$componentString"
                ).asImageBitmap()
            } catch (_: Exception) {
                null
            }
        }

        imageBitmapCache.get(componentString)?.let { return it }

        return try {
            val themedIcon = getIconForApp(appPackageName, appActivityName)
            val bitmap = when {
                themedIcon != null -> drawableToBitmap(
                    themedIcon,
                    "icon-pack-themed:$packPackageName:$componentString"
                )
                fallbackIcon == null -> return null
                hasFallbackIconTheme() -> createFallbackIconBitmap(
                    appPackageName,
                    componentString,
                    fallbackIcon,
                    packResources ?: return null
                )
                else -> drawableToBitmap(
                    fallbackIcon,
                    "icon-pack-default:$packPackageName:$componentString"
                )
            }.asImageBitmap()
            imageBitmapCache.put(componentString, bitmap)
            bitmap
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private val activeLoaders = java.util.Collections.newSetFromMap(
            java.util.WeakHashMap<IconPackLoader, Boolean>()
        )

        internal fun register(loader: IconPackLoader) {
            synchronized(activeLoaders) {
                activeLoaders.add(loader)
            }
        }

        fun clearAllCaches() {
            synchronized(activeLoaders) {
                activeLoaders.forEach { it.clearCache() }
            }
        }

        fun clearCache() {
            clearAllCaches()
        }
    }
}