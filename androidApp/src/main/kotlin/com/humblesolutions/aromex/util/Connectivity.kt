package com.humblesolutions.aromex.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Lightweight synchronous connectivity check used to gate pull-to-refresh (#37):
 * if the device has no validated internet transport we show the offline dialog
 * instead of firing a network request that would just fail.
 *
 * Requires the `ACCESS_NETWORK_STATE` permission (declared in the manifest).
 */
fun isOnline(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
