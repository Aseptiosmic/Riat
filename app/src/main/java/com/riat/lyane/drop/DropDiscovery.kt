package com.riat.lyane.drop

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.riat.lyane.core.LyLog
import java.net.InetAddress
import java.util.LinkedList

/**
 * Yerel ağ keşfi: mDNS/DNS-SD üzerinden `_lyane._tcp.` hizmetini duyurur ve
 * aynı ağdaki Lyane'leri bulur. İnternet gerekmez; yalnız yerel çok noktaya
 * yayın kullanılır.
 */
class DropDiscovery(private val context: Context) {

    data class Peer(val name: String, val host: String, val port: Int, val serviceName: String)

    private val nsd by lazy { context.getSystemService(Context.NSD_SERVICE) as NsdManager }
    private val multicastLock by lazy {
        (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createMulticastLock("lyane-drop").apply { setReferenceCounted(false) }
    }

    private var registrationListener: android.net.nsd.NsdManager.RegistrationListener? = null
    private var discoveryListener: android.net.nsd.NsdManager.DiscoveryListener? = null
    private val resolveQueue = LinkedList<NsdServiceInfo>()
    private var resolving = false

    /** Sunucuyu yerel ağda duyurur. */
    fun advertise(port: Int, name: String) {
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val listener = object : android.net.nsd.NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                LyLog.i(TAG, "Drop hizmeti duyuruldu: ${serviceInfo.serviceName}")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                LyLog.e(TAG, "Duyuru başarısız: $errorCode")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        registrationListener = listener
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    fun stopAdvertise() {
        registrationListener?.let {
            runCatching { nsd.unregisterService(it) }
        }
        registrationListener = null
    }

    /** Aynı ağdaki Lyane'leri arar; her biri çözümlendiğinde callback. */
    fun discover(onPeer: (Peer) -> Unit, onError: (String) -> Unit = {}) {
        multicastLock.acquire()
        val listener = object : android.net.nsd.NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                onError("Ağ keşfi başlatılamadı ($errorCode). Aynı Wi-Fi ağındasınız mı?")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType?.startsWith(SERVICE_TYPE) != true) return
                enqueueResolve(serviceInfo, onPeer)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        discoveryListener = listener
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { onError(it.message ?: "keşif hatası") }
    }

    private fun onPeerFound(info: NsdServiceInfo, onPeer: (Peer) -> Unit) {
        val host = info.host?.hostAddress ?: return
        val name = info.serviceName ?: "Lyane"
        val port = info.port
        if (port <= 0) return
        onPeer(Peer(name = name, host = host, port = port, serviceName = name))
    }

    private fun enqueueResolve(info: NsdServiceInfo, onPeer: (Peer) -> Unit) {
        synchronized(resolveQueue) {
            if (resolveQueue.any { it.serviceName == info.serviceName }) return
            resolveQueue.add(info)
        }
        pump(onPeer)
    }

    private fun pump(onPeer: (Peer) -> Unit) {
        var polled: NsdServiceInfo? = null
        synchronized(resolveQueue) {
            if (resolving) return
            polled = resolveQueue.pollFirst() ?: return
            resolving = true
        }
        val info = polled ?: return
        runCatching {
            nsd.resolveService(info, object : android.net.nsd.NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    done()
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    onPeerFound(info, onPeer)
                    done()
                }

                private fun done() {
                    synchronized(resolveQueue) { resolving = false }
                    pump(onPeer)
                }
            })
        }.onFailure { synchronized(resolveQueue) { resolving = false } }
    }

    fun stopDiscover() {
        discoveryListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discoveryListener = null
        if (multicastLock.isHeld) multicastLock.release()
    }

    companion object {
        const val SERVICE_TYPE = "_lyane._tcp."
        private const val TAG = "DropDiscovery"
    }
}
