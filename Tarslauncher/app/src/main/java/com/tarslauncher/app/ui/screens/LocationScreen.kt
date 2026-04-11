package com.tarslauncher.app.ui.screens

import android.annotation.SuppressLint
import android.webkit.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.tarslauncher.app.repository.TarsLocation
import java.util.*

@Composable
fun LocationScreen(
    locationState: TarsLocation,
    onBackClick: () -> Unit,
    onStartTracking: () -> Unit,
    onStopTracking: () -> Unit
) {
    LaunchedEffect(Unit) {
        onStartTracking()
    }
    
    DisposableEffect(Unit) {
        onDispose {
            onStopTracking()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Fullscreen WebView Map
        TarsLeafletMap(
            lat = locationState.latitude,
            lng = locationState.longitude,
            modifier = Modifier.fillMaxSize()
        )

        // OS Technical Data Overlay
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(16.dp)
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                .padding(8.dp)
        ) {
            Text("TARS_TRACKING_MODE: ACTIVE", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            
            val cell = locationState.cellData
            TechnicalRow("MCC", cell?.mcc ?: "---")
            TechnicalRow("MNC", cell?.mnc ?: "---")
            TechnicalRow("LAC", cell?.lac?.toString() ?: "---")
            TechnicalRow("CID", cell?.cid?.toString() ?: "---")
            TechnicalRow("CELLS", cell?.totalVisible?.toString() ?: "0")
            TechnicalRow("WIFI", locationState.wifiCount.toString())
            TechnicalRow("SIG", "${cell?.signalStrength ?: "0"} DBM")
            TechnicalRow("TYPE", cell?.type ?: "SCANNING")
            
            Spacer(modifier = Modifier.height(16.dp))
            Text("POS_DATA:", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            TechnicalRow("LAT", String.format("%.6f", locationState.latitude))
            TechnicalRow("LNG", String.format("%.6f", locationState.longitude))
            TechnicalRow("ACC", "${locationState.accuracy}M")
        }

        // Back Button Bottom Right
        Button(
            onClick = onBackClick,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.Black.copy(alpha = 0.7f)),
            shape = RoundedCornerShape(4.dp)
        ) {
            Text("BACK", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Light, letterSpacing = 2.sp)
        }
    }
}

@Composable
fun TechnicalRow(label: String, value: String) {
    Row(modifier = Modifier.width(120.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = "$label:", color = Color.Gray, fontSize = 9.sp, fontWeight = FontWeight.ExtraLight)
        Text(text = value, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Normal)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TarsLeafletMap(lat: Double, lng: Double, modifier: Modifier = Modifier) {
    val html = remember(lat, lng) {
        """
        <!DOCTYPE html>
        <html>
        <head>
            <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
            <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
            <style>
                body { margin: 0; padding: 0; background: #000; overflow: hidden; }
                #map { height: 100vh; width: 100vw; background: #000; }
                .leaflet-container { background: #000 !important; }
                .leaflet-control-attribution { display: none !important; }
            </style>
        </head>
        <body>
            <div id="map"></div>
            <script>
                var map = L.map('map', {
                    zoomControl: false,
                    attributionControl: false,
                    dragging: true,
                    doubleClickZoom: true,
                    scrollWheelZoom: true
                }).setView([$lat, $lng], 16);
                
                L.tileLayer('https://{s}.basemaps.cartocdn.com/dark_nolabels/{z}/{x}/{y}{r}.png', {
                    maxZoom: 19
                }).addTo(map);

                // Simple white dot icon
                var whiteDot = L.divIcon({
                    className: 'custom-div-icon',
                    html: '<div style="width:8px;height:8px;background:#fff;border-radius:50%;box-shadow: 0 0 10px #fff;"></div>',
                    iconSize: [8, 8],
                    iconAnchor: [4, 4]
                });

                var marker = L.marker([$lat, $lng], { icon: whiteDot }).addTo(map);

                function updatePos(newLat, newLng) {
                    map.panTo([newLat, newLng]);
                    marker.setLatLng([newLat, newLng]);
                }
            </script>
        </body>
        </html>
        """.trimIndent()
    }

    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                webViewClient = WebViewClient()
                loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                setBackgroundColor(0x00000000)
            }
        },
        update = { webView ->
            // Use evaluateJavascript to update without reloading if possible, 
            // but since we wrap it in remember(lat, lng), it will reload as needed.
        },
        modifier = modifier
    )
}

// Fixed missing Suppress annotation for formatted string
@kotlin.OptIn(androidx.compose.ui.graphics.ExperimentalGraphicsApi::class)
private fun String.format(vararg args: Any?): String = java.lang.String.format(java.util.Locale.US, this, *args)
