package com.freedomtunnel.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.system.OsConstants
import java.net.Inet6Address
import java.net.NetworkInterface
import java.util.Collections
import android.os.Build
import android.os.IBinder
import android.util.Log
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.NetworkInterface as LibboxNetworkInterface
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.TunOptions
import java.lang.reflect.Proxy

class FreedomVpnService : VpnService() {
    companion object {
        const val ACTION_START = "com.freedomtunnel.app.START"
        const val ACTION_STOP = "com.freedomtunnel.app.STOP"
        const val EXTRA_CONFIG = "config"
        const val ACTION_STATUS = "com.freedomtunnel.app.STATUS"
        const val EXTRA_STATUS = "status"
        private const val CHANNEL_ID = "freedom_vpn"
        private const val NOTIFICATION_ID = 41
        private const val TAG = "FreedomVpnService"
    }

    private var commandServer: CommandServer? = null
    private var tunFd: android.os.ParcelFileDescriptor? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            publishStatus("VPN остановлен")
            stopTunnel()
            return START_NOT_STICKY
        }
        val config = intent?.getStringExtra(EXTRA_CONFIG)
        if (config.isNullOrBlank()) {
            publishStatus("Ошибка: конфигурация VPN пустая")
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        publishStatus("Сервис VPN запущен, поднимаем туннель...")
        Thread {
            try {
                startTunnel(config)
            } catch (e: Throwable) {
                Log.e(TAG, "Unable to start sing-box", e)
                val detail = e.message ?: e.javaClass.simpleName
                publishStatus("Ошибка запуска VPN: $detail")
                runCatching {
                    openFileOutput("last_vpn_error.txt", MODE_PRIVATE).bufferedWriter().use {
                        it.write(Log.getStackTraceString(e))
                    }
                }
                stopTunnel()
            }
        }.start()
        return START_NOT_STICKY
    }

    private fun startTunnel(config: String) {
        val working = getExternalFilesDir(null) ?: filesDir
        working.mkdirs()
        val setup = SetupOptions().apply {
            basePath = filesDir.absolutePath
            workingPath = working.absolutePath
            tempPath = cacheDir.absolutePath
            logMaxLines = 1000
            debug = true
            fixAndroidStack = true
            commandServerListenPort = 0
            commandServerSecret = Libbox.randomHex(32).value
            appVersion = "1"
            appMarketingVersion = "0.1.0"
        }
        Libbox.setup(setup)

        val platform = Proxy.newProxyInstance(
            PlatformInterface::class.java.classLoader,
            arrayOf(PlatformInterface::class.java)
        ) { _, method, args ->
            when (method.name) {
                "usePlatformAutoDetectInterfaceControl" -> true
                "useProcFS" -> true
                "getInterfaces" -> getPlatformInterfaces()
                "systemCertificates" -> StringArray(emptyList())
                "autoDetectInterfaceControl" -> {
                    protect(args!![0] as Int)
                    null
                }
                "openTun" -> establishTun(args!![0] as TunOptions)
                "clearDNSCache", "startDefaultInterfaceMonitor",
                "closeDefaultInterfaceMonitor", "registerMyInterface",
                "closeNeighborMonitor" -> null
                "underNetworkExtension", "includeAllNetworks",
                "usePlatformShell", "usePlatformBridge",
                "usePlatformAutoRedirect" -> false
                "readWIFIState", "localDNSTransport" -> null
                "writeDebugMessage" -> {
                    Log.d(TAG, args?.firstOrNull()?.toString() ?: "")
                    null
                }
                else -> defaultValue(method.returnType)
            }
        } as PlatformInterface

        val handler = Proxy.newProxyInstance(
            CommandServerHandler::class.java.classLoader,
            arrayOf(CommandServerHandler::class.java)
        ) { _, method, args ->
            when (method.name) {
                "serviceStop" -> {
                    stopTunnel()
                    null
                }
                "serviceReload" -> null
                "writeDebugMessage" -> {
                    Log.d(TAG, args?.firstOrNull()?.toString() ?: "")
                    null
                }
                "triggerNativeCrash" -> null
                "connectSSHAgent" -> -1
                else -> defaultValue(method.returnType)
            }
        } as CommandServerHandler

        Libbox.checkConfig(config)
        val server = CommandServer(handler, platform)
        commandServer = server
        server.start()
        server.startOrReloadService(
            config,
            OverrideOptions()
        )
        Log.i(TAG, "sing-box service started")
        publishStatus("sing-box принял конфигурацию, VPN запускается")
        publishStatus("Команда sing-box принята. Проверяем подключение...")
    }

    private fun publishStatus(message: String) {
        sendBroadcast(
            Intent(ACTION_STATUS)
                .setPackage(packageName)
                .putExtra(EXTRA_STATUS, message)
        )
    }

    private fun getPlatformInterfaces(): NetworkInterfaceIterator {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val javaInterfaces = runCatching { Collections.list(NetworkInterface.getNetworkInterfaces()) }
            .getOrDefault(emptyList())
        val result = mutableListOf<LibboxNetworkInterface>()
        for (network in connectivity.allNetworks) {
            val properties = connectivity.getLinkProperties(network) ?: continue
            val capabilities = connectivity.getNetworkCapabilities(network) ?: continue
            val name = properties.interfaceName ?: continue
            val javaInterface = javaInterfaces.firstOrNull { it.name == name } ?: continue
            val item = LibboxNetworkInterface().apply {
                this.name = name
                index = javaInterface.index
                mtu = runCatching { javaInterface.mtu }.getOrDefault(1500)
                type = when {
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                    else -> Libbox.InterfaceTypeOther
                }
                dnsServer = StringArray(properties.dnsServers.mapNotNull { it.hostAddress })
                addresses = StringArray(javaInterface.interfaceAddresses.map { address ->
                    val ip = address.address
                    val host = if (ip is Inet6Address) ip.hostAddress.substringBefore('%') else ip.hostAddress
                    "$host/${address.networkPrefixLength}"
                )
                var interfaceFlags = 0
                if (runCatching { javaInterface.isUp }.getOrDefault(false)) {
                    interfaceFlags = interfaceFlags or OsConstants.IFF_UP or OsConstants.IFF_RUNNING
                }
                if (runCatching { javaInterface.isLoopback }.getOrDefault(false)) {
                    interfaceFlags = interfaceFlags or OsConstants.IFF_LOOPBACK
                }
                if (runCatching { javaInterface.supportsMulticast() }.getOrDefault(false)) {
                    interfaceFlags = interfaceFlags or OsConstants.IFF_MULTICAST
                }
                flags = interfaceFlags
                metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }
            result += item
        }
        return InterfaceArray(result.iterator())
    }

    private class InterfaceArray(private val iterator: Iterator<LibboxNetworkInterface>) : NetworkInterfaceIterator {
        override fun hasNext(): Boolean = iterator.hasNext()
        override fun next(): LibboxNetworkInterface = iterator.next()
    }

    private class StringArray(values: List<String>) : StringIterator {
        private val items = values
        private var position = 0
        override fun len(): Int = items.size
        override fun hasNext(): Boolean = position < items.size
        override fun next(): String = items[position++]
    }

    private fun establishTun(options: TunOptions): Int {
        val builder = Builder()
            .setSession("Freedom Tunnel")
            .setMtu(if (options.mtu > 0) options.mtu else 1500)

        val v4 = options.inet4Address
        while (v4.hasNext()) {
            val address = v4.next()
            builder.addAddress(address.address(), address.prefix())
        }
        val v6 = options.inet6Address
        while (v6.hasNext()) {
            val address = v6.next()
            builder.addAddress(address.address(), address.prefix())
        }
        if (options.autoRoute) {
            val dns = options.dnsServerAddress
            while (dns.hasNext()) builder.addDnsServer(dns.next())

            if (Build.VERSION.SDK_INT >= 33) {
                val routes4 = options.inet4RouteAddress
                while (routes4.hasNext()) { val route = routes4.next(); builder.addRoute(route.address(), route.prefix()) }
                val routes6 = options.inet6RouteAddress
                while (routes6.hasNext()) { val route = routes6.next(); builder.addRoute(route.address(), route.prefix()) }
            } else {
                val routes4 = options.inet4RouteRange
                while (routes4.hasNext()) {
                    val route = routes4.next()
                    builder.addRoute(route.address(), route.prefix())
                }
                val routes6 = options.inet6RouteRange
                while (routes6.hasNext()) {
                    val route = routes6.next()
                    builder.addRoute(route.address(), route.prefix())
                }
            }
        }
        val descriptor = builder.establish()
            ?: error("Android не выдал VPN-интерфейс")
        tunFd = descriptor
        return descriptor.fd
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Freedom Tunnel VPN", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun startInForeground() {
        val notification = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Freedom Tunnel")
                .setContentText("VPN запускается")
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Freedom Tunnel")
                .setContentText("VPN запускается")
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setOngoing(true)
                .build()
        }
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopTunnel() {
        runCatching { commandServer?.closeService() }
        runCatching { commandServer?.close() }
        commandServer = null
        runCatching { tunFd?.close() }
        tunFd = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Void.TYPE -> null
        else -> null
    }

}
