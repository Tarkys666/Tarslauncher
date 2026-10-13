package com.tarslauncher.app

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.drawable.Drawable
import org.xmlpull.v1.XmlPullParser

import androidx.collection.LruCache
import androidx.compose.runtime.Stable
import androidx.core.content.res.ResourcesCompat
import java.util.concurrent.ConcurrentHashMap

@Stable
class IconPackLoader(private val context: Context, private val packPackageName: String) {

    private val iconMap = ConcurrentHashMap<String, String>()
    private val resIdCache = ConcurrentHashMap<String, Int>()
    private val drawableCache = LruCache<String, Drawable>(128)

    @Volatile
    private var loaded = false
    @Volatile
    private var packResources: Resources? = null

    fun load() {
        if (loaded) return
        try {
            val resources = context.packageManager.getResourcesForApplication(packPackageName)
            packResources = resources
            loadAppFilter(resources)
            loaded = true
        } catch (_: PackageManager.NameNotFoundException) {
            // İkon paketi bulunamadı veya kaldırıldı
        }
    }

    private fun loadAppFilter(resources: Resources) {
        val appFilterId = resources.getIdentifier("appfilter", "xml", packPackageName)
        if (appFilterId == 0) return

        var xpp: android.content.res.XmlResourceParser? = null
        try {
            xpp = resources.getXml(appFilterId)
            val tempMap = HashMap<String, String>(4096)
            while (xpp.eventType != XmlPullParser.END_DOCUMENT) {
                if (xpp.eventType == XmlPullParser.START_TAG && xpp.name == "item") {
                    val component = xpp.getAttributeValue(null, "component")
                    val drawable = xpp.getAttributeValue(null, "drawable")
                    if (!component.isNullOrEmpty() && !drawable.isNullOrEmpty()) {
                        tempMap[component] = drawable
                    }
                }
                xpp.next()
            }
            iconMap.putAll(tempMap)
        } catch (_: Exception) {
            // appfilter.xml okunamadı veya hatalı
        } finally {
            xpp?.close() // Prevent native asset parser leak
        }
    }

    fun getIconForApp(appPackageName: String, appActivityName: String): Drawable? {
        if (!loaded) return null
        val resources = packResources ?: return null

        val componentString = "ComponentInfo{$appPackageName/$appActivityName}"

        // 1. Check in-memory LRU cache first
        drawableCache.get(componentString)?.let { cached ->
            return cached.constantState?.newDrawable() ?: cached
        }

        val drawableName = iconMap[componentString] ?: return null

        return try {
            // 2. Cache resource identifier to eliminate linear reflection lookups
            val resId = resIdCache.getOrPut(drawableName) {
                resources.getIdentifier(drawableName, "drawable", packPackageName)
            }

            if (resId != 0) {
                val drawable = ResourcesCompat.getDrawable(resources, resId, null)
                if (drawable != null) {
                    drawableCache.put(componentString, drawable)
                }
                drawable
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
} 