package com.chronoit.aivora.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.net.Network
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

class AndroidNetworkMonitor(private val context: Context) {
    fun changes() = callbackFlow {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(snapshot()) }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { trySend(snapshot()) }
            override fun onLost(network: Network) { trySend(snapshot()) }
        }
        manager.registerDefaultNetworkCallback(callback)
        trySend(snapshot())
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
    fun snapshot(): NetworkSnapshot {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        val validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val wifi = runCatching { context.applicationContext.getSystemService(WifiManager::class.java)?.isWifiEnabled }.getOrNull()
        val route = capabilities?.let {
            when {
                it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkRoute.VPN
                it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkRoute.WIFI
                it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkRoute.MOBILE
                it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkRoute.ETHERNET
                else -> null
            }
        }
        return NetworkSnapshot(validated, wifi, route)
    }
}
