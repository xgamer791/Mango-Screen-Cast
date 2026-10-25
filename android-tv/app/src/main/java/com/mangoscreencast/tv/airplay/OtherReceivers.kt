package com.mangoscreencast.tv.airplay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.net.InetAddress
import java.net.NetworkInterface

/** An AirPlay receiver that lives on this very TV, next to this app. */
data class OtherReceiver(val name: String, val port: Int, val model: String?)

/**
 * Watches the network for AirPlay receivers that resolve to this TV. Hisense,
 * Sony and other Android TVs ship Apple's own AirPlay under the TV's name,
 * so Screen Mirroring lists two names for one TV, and the TV's own mirrors
 * at 1080p. Knowing it's there lets the home screen say which name to pick.
 */
class OtherReceivers(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    /** Called off the main thread when a receiver on this TV appears or goes. */
    var onChanged: ((OtherReceiver?) -> Unit)? = null

    /** Names this app advertises under: the settled one and the one asked for. */
    @Volatile
    var ownNames: Set<String> = emptySet()

    private var discovery: NsdManager.DiscoveryListener? = null
    private val found = linkedMapOf<String, OtherReceiver>()
    // NsdManager resolves one service at a time, so the rest wait their turn.
    private val waiting = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    @Synchronized
    fun start() {
        if (discovery != null) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "discovery failed to start: error $errorCode")
                synchronized(this@OtherReceivers) { discovery = null }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

            override fun onServiceFound(info: NsdServiceInfo) {
                if (isOurs(info.serviceName, ownNames)) return
                synchronized(this@OtherReceivers) {
                    waiting.addLast(info)
                    resolveNext()
                }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                val gone = synchronized(this@OtherReceivers) { found.remove(info.serviceName) != null }
                if (gone) onChanged?.invoke(current())
            }
        }
        discovery = listener
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: RuntimeException) {
            Log.w(TAG, "discoverServices failed", e)
            discovery = null
        }
    }

    @Synchronized
    fun stop() {
        val listener = discovery ?: return
        discovery = null
        try {
            nsd.stopServiceDiscovery(listener)
        } catch (e: RuntimeException) {
            Log.w(TAG, "stopServiceDiscovery failed", e)
        }
        waiting.clear()
        resolving = false
        found.clear()
    }

    /** The receiver on this TV, if one is known. */
    fun current(): OtherReceiver? = synchronized(this) { found.values.firstOrNull() }

    private fun resolveNext() {
        if (resolving) return
        val next = waiting.removeFirstOrNull() ?: return
        resolving = true
        try {
            @Suppress("DEPRECATION")
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    synchronized(this@OtherReceivers) {
                        resolving = false
                        resolveNext()
                    }
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    val onThisTv = isThisTv(info.host, localAddresses())
                    val changed = synchronized(this@OtherReceivers) {
                        resolving = false
                        resolveNext()
                        if (onThisTv && discovery != null) {
                            val model = info.attributes?.get("model")?.let { String(it, Charsets.UTF_8) }
                            found[info.serviceName] = OtherReceiver(info.serviceName, info.port, model)
                            true
                        } else false
                    }
                    if (changed) onChanged?.invoke(current())
                }
            })
        } catch (e: RuntimeException) {
            Log.w(TAG, "resolveService failed", e)
            resolving = false
        }
    }

    companion object {
        private const val TAG = "MangoReceivers"
        const val SERVICE_TYPE = "_airplay._tcp"

        /**
         * Our own service: the name asked for, or the one the platform settled
         * on, which can be the asked-for name with " (2)" when a stale copy of
         * ours still lingers in the responder.
         */
        fun isOurs(name: String, ownNames: Set<String>): Boolean =
            ownNames.any { own -> name == own || (name.startsWith("$own (") && name.endsWith(")")) }

        /** A receiver whose address is one of this TV's own (or loopback) runs on this TV. */
        fun isThisTv(host: InetAddress?, local: Collection<InetAddress>): Boolean {
            if (host == null) return false
            if (host.isLoopbackAddress || host.isAnyLocalAddress) return true
            return local.any { it.address.contentEquals(host.address) }
        }

        fun localAddresses(): List<InetAddress> = try {
            NetworkInterface.getNetworkInterfaces()?.toList()
                ?.flatMap { it.inetAddresses.toList() }
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }
}
