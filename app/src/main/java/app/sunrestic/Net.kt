package app.sunrestic

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService

/** Network facts the app needs: is the phone on Wi-Fi (also through a VPN), and which apps are VPNs. */
object Net {
    /**
     * True on Wi-Fi or Ethernet. A VPN such as WireGuard becomes the "active" network and can hide what is underneath
     * it, so every connected network is looked at, not only the active one.
     */
    fun onWifi(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        fun wifi(c: NetworkCapabilities?) =
            c != null && (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        if (wifi(cm.getNetworkCapabilities(cm.activeNetwork))) return true
        return cm.allNetworks.any { n ->
            val c = cm.getNetworkCapabilities(n)
            wifi(c) && c!!.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }

    /** True while some VPN is connected. */
    fun vpnActive(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        return cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    }

    /** Packages that provide a VPN (WireGuard, OpenVPN, Tailscale…). They carry the backup's own connection. */
    fun vpnPackages(ctx: Context): Set<String> = runCatching {
        ctx.packageManager.queryIntentServices(Intent(VpnService.SERVICE_INTERFACE), 0)
            .mapNotNull { it.serviceInfo?.packageName }.toSet()
    }.getOrDefault(emptySet())
}
