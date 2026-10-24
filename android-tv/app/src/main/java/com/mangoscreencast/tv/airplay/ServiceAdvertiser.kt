package com.mangoscreencast.tv.airplay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/** Decodes a wire-format DNS TXT record: length-prefixed "key=value" strings. */
object TxtRecord {
    fun parse(bytes: ByteArray): List<Pair<String, String>> {
        val entries = mutableListOf<Pair<String, String>>()
        var i = 0
        while (i < bytes.size) {
            val length = bytes[i].toInt() and 0xFF
            i++
            if (length == 0) continue
            if (i + length > bytes.size) break
            val entry = String(bytes, i, length, Charsets.UTF_8)
            i += length
            val eq = entry.indexOf('=')
            if (eq > 0) {
                entries += entry.substring(0, eq) to entry.substring(eq + 1)
            } else if (eq < 0 && entry.isNotEmpty()) {
                entries += entry to ""
            }
        }
        return entries
    }
}

/**
 * Publishes the AirPlay services (`_airplay._tcp`, `_raop._tcp`) with the
 * platform's mDNS responder, which is what makes the TV show up in the
 * Screen Mirroring menu on Macs, iPhones and iPads.
 */
class ServiceAdvertiser(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val registrations = mutableMapOf<String, NsdManager.RegistrationListener>()

    /** Called with a short reason when the platform refuses to advertise. */
    var onFailure: ((String) -> Unit)? = null

    /** Called with the service type and the name the platform settled on. */
    var onRegistered: ((String, String) -> Unit)? = null

    @Synchronized
    fun register(type: String, name: String, port: Int, txt: ByteArray): Int {
        unregister(type)
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = type
            this.port = port
        }
        for ((key, value) in TxtRecord.parse(txt)) {
            try {
                info.setAttribute(key, value)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "TXT entry $key rejected: ${e.message}")
            }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "Advertising ${info.serviceName} ($type) on port $port")
                onRegistered?.invoke(type, info.serviceName)
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Could not advertise $type: error $errorCode")
                synchronized(this@ServiceAdvertiser) { registrations.remove(type) }
                onFailure?.invoke("network discovery error $errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {}

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        return try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            registrations[type] = listener
            0
        } catch (e: RuntimeException) {
            Log.e(TAG, "registerService failed for $type", e)
            -1
        }
    }

    @Synchronized
    fun unregister(type: String) {
        val listener = registrations.remove(type) ?: return
        try {
            nsd.unregisterService(listener)
        } catch (e: RuntimeException) {
            Log.w(TAG, "unregisterService failed for $type", e)
        }
    }

    @Synchronized
    fun unregisterAll() {
        registrations.keys.toList().forEach(::unregister)
    }

    private companion object {
        const val TAG = "MangoAdvertiser"
    }
}
