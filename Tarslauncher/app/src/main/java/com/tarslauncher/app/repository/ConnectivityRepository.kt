package com.tarslauncher.app.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

class ConnectivityRepository(private val context: Context) {
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    /**
     * Reactively monitors internet connectivity using NetworkCallbacks.
     * Emits immediately when network state changes.
     */
    fun getInternetStatusFlow(): Flow<Boolean> = callbackFlow {
        // Initial check
        launch {
            send(checkRealInternet())
        }

        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                launch {
                    val isReal = checkRealInternet()
                    Log.d("Watchdog", "Network Available - Real Internet: $isReal")
                    trySend(isReal)
                }
            }

            override fun onLost(network: Network) {
                Log.d("Watchdog", "Network Lost")
                trySend(false)
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                
                launch {
                    val isReal = checkRealInternet()
                    Log.d("Watchdog", "Capabilities Change - Real Internet: $isReal (Valid: $isValidated)")
                    trySend(isReal)
                }
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager?.registerNetworkCallback(request, networkCallback)

        // Keep-alive/Periodic safeguard check (every 5 mins) just in case
        val tickerJob = launch {
            while (true) {
                delay(5 * 60 * 1000L)
                trySend(checkRealInternet())
            }
        }

        awaitClose {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
            tickerJob.cancel()
        }
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    private suspend fun checkRealInternet(): Boolean = withContext(Dispatchers.IO) {
        val network = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            connectivityManager?.activeNetwork
        } else null
        
        val capabilities = connectivityManager?.getNetworkCapabilities(network)
        if (capabilities == null || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return@withContext false
        }

        // Socket probe confirm
        return@withContext trySocket("8.8.8.8") || trySocket("1.1.1.1")
    }

    private fun trySocket(host: String): Boolean {
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(host, 53), 1500) // Lower timeout (1.5s) for responsiveness
            socket.close()
            true
        } catch (e: Exception) {
            false
        }
    }
}
