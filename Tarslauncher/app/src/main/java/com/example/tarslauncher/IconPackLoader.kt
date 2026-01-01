package com.tarslauncher.app

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.drawable.Drawable
import org.xmlpull.v1.XmlPullParser

class IconPackLoader(private val context: Context, private val packPackageName: String) {

    private val iconMap = mutableMapOf<String, String>()
    private var loaded = false
    private lateinit var packResources: Resources

    fun load() {
        if (loaded) return
        try {
            packResources = context.packageManager.getResourcesForApplication(packPackageName)
            loadAppFilter()
            loaded = true
        } catch (e: PackageManager.NameNotFoundException) {
            // İkon paketi bulunamadı veya kaldırıldı
        }
    }

    private fun loadAppFilter() {
        try {
            val appFilterId = packResources.getIdentifier("appfilter", "xml", packPackageName)
            if (appFilterId != 0) {
                val xpp = packResources.getXml(appFilterId)
                while (xpp.eventType != XmlPullParser.END_DOCUMENT) {
                    if (xpp.eventType == XmlPullParser.START_TAG && xpp.name == "item") {
                        val component = xpp.getAttributeValue(null, "component")
                        val drawable = xpp.getAttributeValue(null, "drawable")
                        if (component != null && drawable != null) {
                            iconMap[component] = drawable
                        }
                    }
                    xpp.next()
                }
            }
        } catch (e: Exception) {
            // appfilter.xml okunamadı veya hatalı
        }
    }

    fun getIconForApp(appPackageName: String, appActivityName: String): Drawable? {
        if (!loaded) return null

        val componentString = "ComponentInfo{$appPackageName/$appActivityName}"
        val drawableName = iconMap[componentString] ?: return null

        return try {
            val resId = packResources.getIdentifier(drawableName, "drawable", packPackageName)
            if (resId != 0) {
                packResources.getDrawable(resId, null)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
} 