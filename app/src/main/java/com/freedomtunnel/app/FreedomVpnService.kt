package com.freedomtunnel.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.util.Log
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Libbox
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
            stopTunnel()
            return START_NOT_STICKY
        }
        val config = intent?.getStringExtra(EXTRA_CONFIG)
        if (config.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        Thread {
            try {
                startTunnel(config)
            } catch (e: Exception) {
                Log.e(TAG, "Unable to start sing-box", e)
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
            debug = BuildConfig.DEBUG
            appVersion = BuildConfig.VERSION_CODE.toString()
            appMarketingVersion = BuildConfig.VERSION_NAME
        }
        Libbox.setup(setup)

        val platform = Proxy.newProxyInstance(
            PlatformInterface::class.java.classLoader,
            arrayOf(PlatformInterface::class.java)
        ) { _, method, args ->
            when (method.name) {
                "usePlatformAutoDetectInterfaceControl" -> true
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

        val server = CommandServer(handler, platform)
        commandServer = server
        server.start()
        server.startOrReloadService(
            config,
            OverrideOptions().apply { autoRoute = true }
        )
        Log.i(TAG, "sing-box service started")
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
                while (routes4.hasNext()) builder.addRoute(routes4.next().toIpPrefixCompat())
                val routes6 = options.inet6RouteAddress
                while (routes6.hasNext()) builder.addRoute(routes6.next().toIpPrefixCompat())
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

    private fun android.net.IpPrefix.toIpPrefixCompat(): String = toString()
}
