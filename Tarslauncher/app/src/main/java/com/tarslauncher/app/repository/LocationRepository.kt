package com.tarslauncher.app.repository

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.telephony.*
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

data class CellTowerData(
    val mcc: String? = null,
    val mnc: String? = null,
    val lac: Int? = null,
    val cid: Int? = null,
    val signalStrength: Int? = null,
    val type: String = "UNKNOWN",
    val totalVisible: Int = 0
)

data class TarsLocation(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val accuracy: Float = 0f,
    val cellData: CellTowerData? = null,
    val wifiCount: Int = 0
)

class LocationRepository(private val context: Context) {
    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    fun getCellTowerFlow(): Flow<CellTowerData?> = callbackFlow {
        if (telephonyManager == null || ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            trySend(null)
            awaitClose { }
            return@callbackFlow
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Api31Support.registerCallback(context, telephonyManager, { info ->
                    trySend(this@LocationRepository.extractCellData(info))
                }, { info ->
                    trySend(this@LocationRepository.extractCellData(info))
                })

                awaitClose {
                    Api31Support.unregisterCallback(telephonyManager)
                }
            } else {
                val listener = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>?) {
                        trySend(this@LocationRepository.extractCellData(cellInfo))
                    }
                }

                @Suppress("DEPRECATION")
                telephonyManager.listen(listener, PhoneStateListener.LISTEN_CELL_INFO)
                
                try {
                    @Suppress("DEPRECATION")
                    trySend(this@LocationRepository.extractCellData(telephonyManager.allCellInfo))
                } catch (e: Exception) {
                    Log.w("LocationRepository", "Initial cell info pull failed")
                }

                awaitClose {
                    @Suppress("DEPRECATION")
                    telephonyManager.listen(listener, PhoneStateListener.LISTEN_NONE)
                }
            }
        } catch (e: Exception) {
            Log.e("LocationRepository", "Error in CellTowerFlow", e)
            trySend(null)
            awaitClose { }
        }
    }

    fun getWifiCountFlow(): Flow<Int> = callbackFlow {
        if (wifiManager == null) {
            trySend(0)
            awaitClose { }
            return@callbackFlow
        }

        val wifiReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (ActivityCompat.checkSelfPermission(this@LocationRepository.context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    val results = wifiManager.scanResults
                    trySend(results.size)
                }
            }
        }

        context.registerReceiver(wifiReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))

        // Trigger first scan and start a timer for subsequent scans
        val scanJob = launch {
            while (true) {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    @Suppress("DEPRECATION")
                    wifiManager.startScan()
                }
                delay(15000) // Respect throttling (4 scans / 2 mins)
            }
        }

        // Initial result
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            trySend(wifiManager.scanResults.size)
        }

        awaitClose {
            context.unregisterReceiver(wifiReceiver)
            scanJob.cancel()
        }
    }

    fun getLocationFlow(): Flow<Location> = callbackFlow {
        if (locationManager == null) {
            awaitClose { }
            return@callbackFlow
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                trySend(location)
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val provider = determineProvider()
            if (provider != null) {
                try {
                    locationManager.requestLocationUpdates(provider, 5000L, 10f, listener)
                } catch (e: Exception) {
                    Log.e("LocationRepository", "Error requesting location updates", e)
                }
            }
        }

        awaitClose {
            try {
                locationManager.removeUpdates(listener)
            } catch (e: Exception) { }
        }
    }

    private fun determineProvider(): String? {
        val manager = locationManager ?: return null
        return when {
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER) -> LocationManager.PASSIVE_PROVIDER
            else -> null
        }
    }

    private fun extractCellData(cellInfoList: List<CellInfo>?): CellTowerData? {
        if (cellInfoList.isNullOrEmpty()) return null
        val total = cellInfoList.size
        val activeCell = cellInfoList.find { it.isRegistered } ?: return CellTowerData(totalVisible = total)
        
        return try {
            when (activeCell) {
                is CellInfoGsm -> {
                    val id = activeCell.cellIdentity
                    CellTowerData(mcc = id.mccString, mnc = id.mncString, lac = id.lac, cid = id.cid, signalStrength = activeCell.cellSignalStrength.dbm, type = "GSM", totalVisible = total)
                }
                is CellInfoLte -> {
                    val id = activeCell.cellIdentity
                    CellTowerData(mcc = id.mccString, mnc = id.mncString, lac = id.tac, cid = id.ci, signalStrength = activeCell.cellSignalStrength.dbm, type = "LTE", totalVisible = total)
                }
                is CellInfoWcdma -> {
                    val id = activeCell.cellIdentity
                    CellTowerData(mcc = id.mccString, mnc = id.mncString, lac = id.lac, cid = id.cid, signalStrength = activeCell.cellSignalStrength.dbm, type = "WCDMA", totalVisible = total)
                }
                is CellInfoNr -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val id = activeCell.cellIdentity as CellIdentityNr
                        CellTowerData(mcc = id.mccString, mnc = id.mncString, lac = id.tac, cid = id.nci.toInt(), signalStrength = activeCell.cellSignalStrength.dbm, type = "5G NR", totalVisible = total)
                    } else CellTowerData(totalVisible = total, type = "5G (Legacy)")
                }
                else -> CellTowerData(type = "OTHER", totalVisible = total)
            }
        } catch (e: Exception) {
            CellTowerData(type = "ERR", totalVisible = total)
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private object Api31Support {
        private var activeCallback: TelephonyCallback? = null

        @SuppressLint("MissingPermission")
        fun registerCallback(
            context: Context,
            telephonyManager: TelephonyManager,
            onCellInfo: (MutableList<CellInfo>) -> Unit,
            onInitial: (MutableList<CellInfo>) -> Unit
        ) {
            val callback = object : TelephonyCallback(), TelephonyCallback.CellInfoListener {
                override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) {
                    onCellInfo(cellInfo)
                }
            }
            activeCallback = callback
            val executor = ContextCompat.getMainExecutor(context)
            telephonyManager.registerTelephonyCallback(executor, callback)
            
            telephonyManager.requestCellInfoUpdate(executor, object : TelephonyManager.CellInfoCallback() {
                override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                    onInitial(cellInfo)
                }
            })
        }

        fun unregisterCallback(telephonyManager: TelephonyManager) {
            activeCallback?.let {
                telephonyManager.unregisterTelephonyCallback(it)
                activeCallback = null
            }
        }
    }
}
