package com.freedomtunnel.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.LocalSocket
import android.net.LocalSocketAddress
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
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class FreedomVpnService : VpnService() {
    companion object {
        const val ACTION_START = "com.freedomtunnel.app.START"
        const val ACTION_STOP = "com.freedomtunnel.app.STOP"
        const val EXTRA_CONFIG = "config"
        const val EXTRA_ENGINE = "engine"
        const val EXTRA_CSQTT_PROFILE = "csqtt_profile"
        const val ACTION_STATUS = "com.freedomtunnel.app.STATUS"
        const val EXTRA_STATUS = "status"
        private const val CHANNEL_ID = "freedom_vpn"
        private const val NOTIFICATION_ID = 41
        private const val TAG = "FreedomVpnService"
    }

    private var commandServer: CommandServer? = null
    private var tunFd: android.os.ParcelFileDescriptor? = null
    @Volatile private var csqttProcess: Process? = null

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
        val engine = intent?.getStringExtra(EXTRA_ENGINE) ?: "vless"
        if (engine == "csqtt") {
            val profile = intent?.getStringExtra(EXTRA_CSQTT_PROFILE)
            if (profile.isNullOrBlank()) {
                publishStatus("Ошибка: профиль CSQTT пустой")
                stopSelf()
                return START_NOT_STICKY
            }
            startInForeground()
            publishStatus("CSQTT: запускаем нативный клиент...")
            Thread {
                try { startCsqtt(profile) } catch (e: Throwable) {
                    Log.e(TAG, "Unable to start CSQTT", e)
                    publishStatus("Ошибка CSQTT: ${e.message ?: e.javaClass.simpleName}")
                    writeDiagnostic(Log.getStackTraceString(e))
                    stopTunnel()
                }
            }.start()
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
                writeDiagnostic(Log.getStackTraceString(e))
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
                "usePlatformAutoDetectInterfaceControl" -> false
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
                    // sing-box calls this when its internal service stops. Preserve
                    // the event before Android tears the VPN service down.
                    Log.e(TAG, "sing-box requested serviceStop")
                    writeDiagnostic("sing-box requested serviceStop")
                    publishStatus("sing-box остановил туннель. Событие записано в журнал.")
                    runCatching { tunFd?.close() }
                    tunFd = null
                    android.os.Handler(mainLooper).post {
                        runCatching { commandServer?.closeService() }
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                    null
                }
                "serviceReload" -> null
                "getSystemProxyStatus" -> {
                    // This callback is part of libbox 1.14.x. Returning null here can
                    // make the native command server fail while initializing its service.
                    io.nekohasekai.libbox.SystemProxyStatus().apply {
                        available = false
                        enabled = false
                    }
                }
                "setSystemProxyEnabled" -> null
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


    /**
     * Starts the pinned upstream CSQTT ARM64 executable and passes Android's TUN
     * descriptor over the abstract Unix socket using SCM_RIGHTS, as required by
     * the upstream Rust client protocol.
     */
    private fun startCsqtt(profileJson: String) {
        check(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }) { "CSQTT пока собран только для ARM64" }
        val profile = JSONObject(profileJson)
        val host = profile.getString("host")
        val port = profile.getInt("port")
        val password = profile.optString("password", "")
        val hashes = profile.optJSONArray("hashes")
        val hashList = (0 until (hashes?.length() ?: 0)).mapNotNull { hashes?.optString(it) }
        val token = profile.optString("vkAccessToken", "")
        require(hashList.isNotEmpty() || token.isNotBlank()) {
            "В ссылке CSQTT нет VK-хешей или токена. Такой профиль нельзя запустить."
        }
        val binary = File(applicationInfo.nativeLibraryDir, "libclient.so")
        check(binary.isFile && binary.canExecute()) { "Нативный клиент CSQTT не найден: ${binary.absolutePath}" }

        val command = mutableListOf(
            binary.absolutePath, "-peer", if (host.contains(":")) "[$host]:$port" else "$host:$port",
            "-n", "10", "-listen", "127.0.0.1:0", "-tun-uds", "csqtt_tun_uds",
            "-vk-hash-mode", if (hashList.isEmpty()) "auto_js" else "manual",
            "-fingerprint", "firefox", "-client-ids", "8202606,6287487",
            "-obfs", "unified", "-turn-transport", "udp", "-srtp-mode", "auto",
            "-vk-auth-mode", "vkcalls", "-captcha-mode", "auto"
        )
        if (hashList.isNotEmpty()) { command += "-vk"; command += hashList.joinToString(",") }
        val builder = ProcessBuilder(command).apply {
            directory(filesDir)
            redirectErrorStream(true)
            environment()["LD_LIBRARY_PATH"] = applicationInfo.nativeLibraryDir
            environment()["CSQTT_EVENTS"] = "1"
            environment()["RAYON_NUM_THREADS"] = "2"
            if (password.isNotEmpty()) environment()["CSQTT_PASSWORD"] = password
        }
        val process = builder.start()
        csqttProcess = process
        if (hashList.isEmpty() && token.isNotBlank()) {
            val encoded = android.util.Base64.encodeToString(
                JSONObject().put("token", token).toString().toByteArray(Charsets.UTF_8),
                android.util.Base64.NO_WRAP
            )
            process.outputStream.write("VK_JS_BOOTSTRAP:$encoded\n".toByteArray(Charsets.UTF_8))
            process.outputStream.flush()
        }
        // The CSQTT server assigns the virtual client address and DNS at runtime.
        // The original CSQTT Android client waits for TUNCONF before creating Android's
        // VPN interface. A made-up static address here can pass the FD handshake while
        // leaving the tunnel unusable (VPN icon appears, but no traffic is routed).
        val tunConfigReady = CountDownLatch(1)
        val tunConfig = AtomicReference<Pair<String, List<String>>?>(null)
        val nativeOutputTail = java.util.concurrent.ConcurrentLinkedDeque<String>()
        Thread {
            runCatching {
                process.inputStream.bufferedReader().forEachLine { line ->
                    Log.i(TAG, "CSQTT: $line")
                    if (line.isNotBlank()) {
                        writeDiagnostic("CSQTT: ${line.take(500)}")
                        nativeOutputTail.addLast(line.take(500))
                        while (nativeOutputTail.size > 40) nativeOutputTail.pollFirst()
                    }
                    if (line.startsWith("TUNCONF:")) {
                        val fields = line.removePrefix("TUNCONF:").split(":", limit = 5)
                        val clientIp = fields.getOrNull(0)?.trim().orEmpty()
                        val dnsServers = fields.getOrNull(1).orEmpty()
                            .split(",", ";", " ")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                        val validIp = runCatching {
                            java.net.InetAddress.getByName(clientIp) is java.net.Inet4Address &&
                                clientIp.matches(Regex("""[0-9]{1,3}(\\.[0-9]{1,3}){3}"""))
                        }.getOrDefault(false)
                        val validDns = dnsServers.filter { address ->
                            runCatching {
                                java.net.InetAddress.getByName(address) is java.net.Inet4Address &&
                                    address.matches(Regex("""[0-9]{1,3}(\\.[0-9]{1,3}){3}"""))
                            }.getOrDefault(false)
                        }
                        if (validIp && validDns.isNotEmpty()) {
                            tunConfig.set(clientIp to validDns)
                            tunConfigReady.countDown()
                        } else {
                            writeDiagnostic("CSQTT: некорректный TUNCONF от сервера")
                        }
                    }
                }
            }.onFailure {
                writeDiagnostic("CSQTT: ошибка чтения вывода: ${it.message}")
                tunConfigReady.countDown()
            }
        }.apply { isDaemon = true; start() }

        if (!tunConfigReady.await(30, TimeUnit.SECONDS)) {
            val tail = nativeOutputTail.joinToString(" | ")
            process.destroy()
            error("CSQTT не прислал TUNCONF за 30 секунд. Вывод клиента: ${tail.ifBlank { "пусто" }}")
        }
        val (clientIp, dnsServers) = tunConfig.get()
            ?: run {
                val tail = nativeOutputTail.joinToString(" | ")
                process.destroy()
                error("CSQTT завершился без корректной настройки TUN. Вывод: ${tail.ifBlank { "пусто" }}")
            }

        val vpnBuilder = Builder()
            .setSession("Freedom Tunnel CSQTT")
            .setMtu(1500)
            .addAddress(clientIp, 32)
            // Match the upstream CSQTT client: IPv4 default route and server-provided DNS.
            .addRoute("0.0.0.0", 0)
            // The native CSQTT process runs under this app UID. Excluding our
            // package prevents its peer/relay sockets from looping back into TUN.
            .addDisallowedApplication(packageName)
        dnsServers.forEach { dns ->
            vpnBuilder.addDnsServer(dns)
            runCatching { vpnBuilder.addRoute(dns, 32) }
        }
        val descriptor = vpnBuilder.establish()
            ?: error("Android не выдал TUN-интерфейс для CSQTT")
        tunFd = descriptor

        var delivered = false
        var lastError: Throwable? = null
        for (attempt in 1..60) {
            if (!process.isAlive) error("Клиент CSQTT завершился с кодом ${process.exitValue()}")
            val socket = LocalSocket()
            try {
                socket.connect(LocalSocketAddress("csqtt_tun_uds", LocalSocketAddress.Namespace.ABSTRACT))
                socket.setFileDescriptorsForSend(arrayOf(descriptor.fileDescriptor))
                socket.outputStream.write(1)
                socket.outputStream.flush()
                socket.soTimeout = 3000
                check(socket.inputStream.read() == 1) { "Rust-клиент не подтвердил получение TUN" }
                delivered = true
            } catch (e: Throwable) {
                lastError = e
            } finally {
                runCatching { socket.close() }
            }
            if (delivered) break
            if (attempt < 60) Thread.sleep(500)
        }
        check(delivered) { "Не удалось передать TUN клиенту CSQTT: ${lastError?.message ?: "тайм-аут"}" }
        publishStatus("CSQTT: TUN передан нативному клиенту, соединение устанавливается")
        Thread {
            val code = runCatching { process.waitFor() }.getOrDefault(-1)
            if (csqttProcess === process) {
                publishStatus("CSQTT завершился (код $code)")
                stopTunnel()
            }
        }.apply { isDaemon = true; start() }
    }

    private fun writeDiagnostic(message: String) {
        runCatching {
            synchronized(this) {
                openFileOutput("last_vpn_error.txt", MODE_PRIVATE or MODE_APPEND).bufferedWriter().use {
                    it.appendLine("${System.currentTimeMillis()} $message")
                }
            }
        }
    }

    private fun publishStatus(message: String) {
        Log.i(TAG, message)
        writeDiagnostic(message)
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
                })
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
        runCatching { csqttProcess?.destroy() }
        csqttProcess = null
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
