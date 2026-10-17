package com.tarslauncher.app

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
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
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

@Stable
class IconPackLoader(private val context: Context, private val packPackageName: String) {

    private val iconMap = ConcurrentHashMap<String, String>()
    private val packageFallbackMap = ConcurrentHashMap<String, String>()
    private val resIdCache = ConcurrentHashMap<String, Int>()

    private val maxCacheSizeKb = (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt().coerceAtLeast(2048)
    private val imageBitmapCache = object : LruCache<String, ImageBitmap>(maxCacheSizeKb) {
        override fun sizeOf(key: String, value: ImageBitmap): Int {
            return ((value.width * value.height * 4) / 1024).coerceAtLeast(1)
        }
    }

    var isLoaded by mutableStateOf(false)
        private set

    @Volatile
    private var packResources: Resources? = null

    fun load() {
        if (isLoaded) return
        try {
            val resources = context.packageManager.getResourcesForApplication(packPackageName)
            packResources = resources
            loadAppFilter(resources)
            isLoaded = true
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
            if (xpp.eventType == XmlPullParser.START_TAG && xpp.name == "item") {
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
            xpp.next()
        }
        iconMap.putAll(tempComponentMap)
        packageFallbackMap.putAll(tempPackageMap)
    }

    fun getIconForApp(appPackageName: String, appActivityName: String): Drawable? {
        if (!isLoaded) return null
        val resources = packResources ?: return null

        val componentString = "ComponentInfo{$appPackageName/$appActivityName}"

        val drawableName = iconMap[componentString]
            ?: packageFallbackMap[appPackageName]
            ?: return null

        return try {
            // Cache resource identifier to eliminate linear reflection lookups
            val resId = resIdCache.getOrPut(drawableName) {
                resources.getIdentifier(drawableName, "drawable", packPackageName)
            }

            if (resId != 0) {
                ResourcesCompat.getDrawable(resources, resId, null)
            } else {
                null
            }
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

        // 1. Check in-memory ImageBitmap LRU cache first
        imageBitmapCache.get(componentString)?.let { return it }

        // 2. Resolve themed drawable or fallback
        val drawable = getIconForApp(appPackageName, appActivityName) ?: fallbackIcon ?: return null

        return try {
            val bitmap = drawableToBitmap(drawable).asImageBitmap()
            imageBitmapCache.put(componentString, bitmap)
            bitmap
        } catch (_: Exception) {
            null
        }
    }
}