package com.freedomtunnel.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ClipboardManager
import android.content.ClipData
import android.widget.Toast
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

data class VlessServer(
    val name: String,
    val host: String,
    val port: String,
    val uuid: String,
    val security: String,
    val network: String,
    val rawLink: String,
    val country: String
) {
    val key: String get() = "$host:$port"
}

class MainActivity : ComponentActivity() {
    private var pendingVpnConfig = ""
    private var pendingEngine = "vless"
    private var pendingCsqttProfile = ""
    private val statusState = mutableStateOf("")
    private val vpnRequestCode = 410
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            statusState.value = intent?.getStringExtra(FreedomVpnService.EXTRA_STATUS).orEmpty()
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(FreedomVpnService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
    }

    override fun onStop() {
        runCatching { unregisterReceiver(receiver) }
        super.onStop()
    }

    private fun connect(link: String) {
        try {
            pendingEngine = "vless"
            pendingVpnConfig = VlessConfig.fromLink(link)
            val permission = VpnService.prepare(this)
            if (permission != null) {
                @Suppress("DEPRECATION")
                startActivityForResult(permission, vpnRequestCode)
            } else startVpn()
        } catch (e: Throwable) {
            statusState.value = "Ошибка конфигурации: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun connectCsqtt(link: String) {
        try {
            val profile = CsqttLinkParser.parse(link) ?: error("Не удалось расшифровать ссылку CSQTT")
            require(profile.hashes.isNotEmpty() || profile.vkAccessToken.isNotBlank()) {
                "В ссылке нет VK-хешей или токена. Нужна полноценная ссылка CSQTT."
            }
            pendingEngine = "csqtt"
            pendingCsqttProfile = JSONObject()
                .put("host", profile.host)
                .put("port", profile.port)
                .put("password", profile.password)
                .put("hashes", JSONArray(profile.hashes))
                .put("vkAccessToken", profile.vkAccessToken)
                .toString()
            val permission = VpnService.prepare(this)
            if (permission != null) {
                @Suppress("DEPRECATION")
                startActivityForResult(permission, vpnRequestCode)
            } else startVpn()
        } catch (e: Throwable) {
            statusState.value = "Ошибка CSQTT: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun startVpn() {
        val intent = Intent(this, FreedomVpnService::class.java)
            .setAction(FreedomVpnService.ACTION_START)
            .putExtra(FreedomVpnService.EXTRA_ENGINE, pendingEngine)
        if (pendingEngine == "csqtt") {
            intent.putExtra(FreedomVpnService.EXTRA_CSQTT_PROFILE, pendingCsqttProfile)
        } else {
            intent.putExtra(FreedomVpnService.EXTRA_CONFIG, pendingVpnConfig)
        }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == vpnRequestCode && resultCode == Activity.RESULT_OK) startVpn()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("freedom_tunnel_config", MODE_PRIVATE)
        setContent {
            FreedomTunnelScreen(
                serviceStatus = statusState.value,
                onConnect = ::connect,
                onConnectCsqtt = ::connectCsqtt,
                onDisconnect = { stopService(Intent(this, FreedomVpnService::class.java)) },
                savedSubscription = prefs.getString("subscription", "https://raw.githubusercontent.com/CaptchaQ/vless-servers/main/working_lite.txt").orEmpty(),
                onSaveSubscription = { prefs.edit().putString("subscription", it).apply() },
                savedServers = prefs.getString("servers_cache", "").orEmpty(),
                onSaveServers = { prefs.edit().putString("servers_cache", it).apply() },
                onCopyDiagnostics = {
                    val log = runCatching { File(filesDir, "last_vpn_error.txt").readText() }.getOrDefault("Журнал пока пуст")
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Freedom Tunnel diagnostics", log))
                    Toast.makeText(this, "Журнал скопирован. Вставь его сюда.", Toast.LENGTH_LONG).show()
                }
            )
        }
    }
}

private fun decodeSubscription(raw: String): String {
    val trimmed = raw.trim().removePrefix("\uFEFF")
    if (trimmed.contains("://")) return trimmed
    val compact = trimmed.filterNot { it.isWhitespace() }
    val candidates = listOf(Base64.DEFAULT, Base64.NO_WRAP, Base64.URL_SAFE or Base64.NO_WRAP)
    for (flags in candidates) {
        try {
            val decoded = Base64.decode(compact, flags).toString(Charsets.UTF_8)
            if (decoded.contains("://")) return decoded
        } catch (_: Exception) { }
    }
    return trimmed
}

private fun countryFor(name: String, host: String): String {
    val text = name.lowercase(Locale.ROOT)
    val rules = listOf(
        listOf("🇩🇪", "germany", "deutschland", "германия", "немец", "germany", "de-"),
        listOf("🇳🇱", "netherlands", "holland", "нидерланд", "голланд", "nl-"),
        listOf("🇫🇷", "france", "франция", "fr-"),
        listOf("🇫🇮", "finland", "финлянд", "fi-"),
        listOf("🇸🇪", "sweden", "швеци", "se-"),
        listOf("🇳🇴", "norway", "норвег", "no-"),
        listOf("🇵🇱", "poland", "польш", "pl-"),
        listOf("🇨🇿", "czech", "чех", "cz-"),
        listOf("🇨🇭", "switzerland", "швейцар", "ch-"),
        listOf("🇦🇹", "austria", "австри", "at-"),
        listOf("🇬🇧", "united kingdom", "uk", "britain", "англи", "великобрит", "gb-"),
        listOf("🇺🇸", "united states", "usa", "america", "сша", "америк", "us-"),
        listOf("🇨🇦", "canada", "канада", "ca-"),
        listOf("🇯🇵", "japan", "япони", "jp-"),
        listOf("🇸🇬", "singapore", "сингапур", "sg-"),
        listOf("🇹🇷", "turkey", "турци", "tr-"),
        listOf("🇷🇺", "russia", "росси", "ru-"),
        listOf("🇪🇸", "spain", "испан", "es-"),
        listOf("🇮🇹", "italy", "итал", "it-"),
        listOf("🇧🇪", "belgium", "бельги", "be-"),
        listOf("🇱🇻", "latvia", "латви", "lv-"),
        listOf("🇪🇪", "estonia", "эстони", "ee-"),
        listOf("🇱🇹", "lithuania", "литв", "lt-"),
        listOf("🇭🇰", "hong kong", "гонконг", "hk-"),
        listOf("🇰🇷", "korea", "корея", "kr-"),
        listOf("🇮🇳", "india", "индия", "in-"),
        listOf("🇧🇷", "brazil", "бразил", "br-"),
        listOf("🇦🇪", "uae", "emirates", "оаэ", "араб", "ae-")
    )
    for (rule in rules) if (rule.drop(1).any { text.contains(it) }) return "${rule[0]} ${rule[1].replaceFirstChar { it.uppercase() }}"
    val flag = Regex("[\\uD83C][\\uDDE6-\\uDDFF]{2}").find(name)?.value
    return if (flag != null) "$flag Другая страна" else "🌐 Другие / не определена"
}

private fun parseVless(text: String): List<VlessServer> {
    val links = Regex("vless://[^\\s\\\"'<>]+", RegexOption.IGNORE_CASE)
        .findAll(text.replace("&amp;", "&"))
        .map { it.value.trimEnd(',', ';', ')', ']') }
        .distinct()
    return links.mapNotNull { link ->
        try {
            val uri = Uri.parse(link)
            val host = uri.host ?: return@mapNotNull null
            val port = uri.port
            val uuid = uri.userInfo ?: return@mapNotNull null
            val transport = (uri.getQueryParameter("type") ?: "tcp").lowercase(Locale.ROOT)
            if (host == "0.0.0.0" || host == "127.0.0.1" || uuid == "00000000-0000-0000-0000-000000000000") return@mapNotNull null
            if (transport !in setOf("tcp", "raw", "ws", "grpc", "httpupgrade")) return@mapNotNull null
            if (port !in 1..65535) return@mapNotNull null
            val label = Uri.decode(uri.fragment ?: "").ifBlank { host }
            VlessServer(
                name = label,
                host = host,
                port = port.toString(),
                uuid = uuid,
                security = uri.getQueryParameter("security") ?: "none",
                network = uri.getQueryParameter("type") ?: "tcp",
                rawLink = link,
                country = countryFor(label, host)
            )
        } catch (_: Exception) { null }
    }.distinctBy { it.uuid + "@" + it.host + ":" + it.port }.toList()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreedomTunnelScreen(
    serviceStatus: String,
    onConnect: (String) -> Unit,
    onConnectCsqtt: (String) -> Unit,
    onDisconnect: () -> Unit,
    savedSubscription: String,
    onSaveSubscription: (String) -> Unit,
    savedServers: String,
    onSaveServers: (String) -> Unit,
    onCopyDiagnostics: () -> Unit
) {
    var subscription by remember { mutableStateOf(savedSubscription) }
    var csqttLink by remember { mutableStateOf("") }
    var servers by remember { mutableStateOf(parseVless(savedServers)) }
    var selected by remember { mutableStateOf(parseVless(savedServers).firstOrNull()?.rawLink.orEmpty()) }
    var tab by remember { mutableIntStateOf(0) }
    var country by remember { mutableStateOf("Все страны") }
    var search by remember { mutableStateOf("") }
    var pingResults by remember { mutableStateOf(mapOf<String, Long>()) }
    var failedPings by remember { mutableStateOf(setOf<String>()) }
    var pinging by remember { mutableStateOf(setOf<String>()) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Добавьте ссылку на подписку и загрузите серверы") }
    val executor = remember { Executors.newFixedThreadPool(8) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val green = Color(0xFF56D6A0)
    val bg = Color(0xFF0B1220)
    val screenScrollState = rememberScrollState()

    fun pingServer(server: VlessServer) {
        if (server.key in pinging || pingResults.containsKey(server.key) || server.key in failedPings) return
        pinging = pinging + server.key
        executor.execute {
            val start = System.nanoTime()
            val success = try {
                Socket().use { it.connect(InetSocketAddress(server.host, server.port.toInt()), 2500) }
                true
            } catch (_: Exception) { false }
            val ms = (System.nanoTime() - start) / 1_000_000
            mainHandler.post {
                pinging = pinging - server.key
                if (success) pingResults = pingResults + (server.key to ms)
                else failedPings = failedPings + server.key
            }
        }
    }

    fun pingAll(list: List<VlessServer>) {
        list.forEach { pingServer(it) }
    }

    val countries = servers.map { it.country }.distinct().sorted()
    val visible = servers.filter {
        (country == "Все страны" || it.country == country) &&
            (search.isBlank() || it.name.contains(search, true) || it.host.contains(search, true))
    }.sortedWith(compareBy<VlessServer> { if (pingResults.containsKey(it.key)) 0 else if (it.key in failedPings) 2 else 1 }
        .thenBy { pingResults[it.key] ?: Long.MAX_VALUE }
        .thenBy { it.name })

    MaterialTheme(colorScheme = darkColorScheme(primary = green, background = bg, surface = Color(0xFF172338))) {
        Scaffold(
            containerColor = bg,
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF111D30)) {
                    listOf("Главная", "Страны", "Подписка").forEachIndexed { index, label ->
                        NavigationBarItem(
                            selected = tab == index,
                            onClick = { tab = index },
                            icon = { Text(listOf("⌂", "◎", "⇅")[index], fontSize = 20.sp) },
                            label = { Text(label) }
                        )
                    }
                }
            }
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 10.dp)
                    .then(if (tab == 1) Modifier else Modifier.verticalScroll(screenScrollState))
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("FREEDOM TUNNEL", color = green, fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
                        Text("VLESS • быстрый выбор сервера", color = Color.LightGray, fontSize = 12.sp)
                    }
                    Text("${servers.size} серверов", color = Color.White, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(10.dp))
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF172338)), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(10.dp)) {
                        Text("CSQTT • альтернативный движок", color = green, fontWeight = FontWeight.Bold)
                        OutlinedTextField(
                            value = csqttLink,
                            onValueChange = { csqttLink = it },
                            label = { Text("Зашифрованная ссылка csqtt://") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 1,
                            maxLines = 3
                        )
                        Button(
                            onClick = { onConnectCsqtt(csqttLink) },
                            enabled = csqttLink.trim().startsWith("csqtt://", true),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("ПОДКЛЮЧИТЬ CSQTT") }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF172338)), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(if (selected.isBlank()) "Сервер не выбран" else "Выбран: ${servers.firstOrNull { it.rawLink == selected }?.name ?: "сервер"}",
                            color = Color.White, fontWeight = FontWeight.Bold)
                        Text(serviceStatus.ifBlank { message }, color = Color(0xFFFFC66D), fontSize = 12.sp)
                        TextButton(onClick = onCopyDiagnostics, modifier = Modifier.fillMaxWidth()) { Text("СКОПИРОВАТЬ ЖУРНАЛ ДИАГНОСТИКИ") }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { if (selected.isNotBlank()) onConnect(selected) else message = "Сначала выберите сервер" },
                                enabled = selected.isNotBlank(), modifier = Modifier.weight(1f)) { Text("ПОДКЛЮЧИТЬ") }
                            OutlinedButton(onClick = onDisconnect, modifier = Modifier.weight(1f)) { Text("ОТКЛЮЧИТЬ") }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                when (tab) {
                    0 -> {
                        Text("БЫСТРЫЙ ВЫБОР", color = Color.White, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        val fastest = servers.filter { pingResults.containsKey(it.key) }.minByOrNull { pingResults[it.key] ?: Long.MAX_VALUE }
                        if (fastest != null) ServerCard(fastest, pingResults[fastest.key], fastest.key in pinging, selected == fastest.rawLink,
                            { selected = fastest.rawLink }, { pingServer(fastest) }, { onConnect(fastest.rawLink) })
                        else Text("Проверьте пинг, чтобы найти самый быстрый сервер.", color = Color.LightGray, fontSize = 13.sp)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { pingAll(servers) }, enabled = servers.isNotEmpty()) { Text("ПРОВЕРИТЬ ВСЕ") }
                            OutlinedButton(onClick = { tab = 1 }) { Text("К СТРАНАМ") }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("Последние результаты: ${pingResults.size} доступно • ${failedPings.size} без ответа", color = Color.LightGray, fontSize = 12.sp)
                    }
                    1 -> {
                        Text("СТРАНЫ", color = Color.White, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = country == "Все страны", onClick = { country = "Все страны" }, label = { Text("Все ${servers.size}") })
                            OutlinedButton(onClick = { pingAll(servers.filter { country == "Все страны" || it.country == country }) }) { Text("Пинг страны") }
                        }
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            items(countries, key = { it }) { item ->
                                val count = servers.count { it.country == item }
                                val best = servers.filter { it.country == item && pingResults.containsKey(it.key) }.minOfOrNull { pingResults[it.key]!! }
                                Card(onClick = { country = item }, colors = CardDefaults.cardColors(containerColor = if (country == item) Color(0xFF24503F) else Color(0xFF172338))) {
                                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(item, color = Color.White, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                                        Text("${best?.let { "${it} мс" } ?: "пинг не проверен"}  •  $count", color = Color.LightGray, fontSize = 12.sp)
                                    }
                                }
                            }
                            item {
                                Spacer(Modifier.height(6.dp))
                                OutlinedTextField(value = search, onValueChange = { search = it }, label = { Text("Поиск сервера") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                Text(if (country == "Все страны") "ВСЕ СЕРВЕРЫ" else country, color = green, fontWeight = FontWeight.Bold)
                            }
                            items(visible, key = { it.rawLink }) { item ->
                                ServerCard(item, pingResults[item.key], item.key in pinging, selected == item.rawLink,
                                    { selected = item.rawLink; message = "Выбран ${item.name}" },
                                    { pingServer(item) }, { selected = item.rawLink; onConnect(item.rawLink) })
                            }
                        }
                    }
                    else -> {
                        Text("ПОДПИСКА И ИМПОРТ", color = Color.White, fontWeight = FontWeight.Bold)
                        Text("Можно вставить несколько ссылок на подписки, каждую с новой строки. Поддерживается обычный текст и Base64.", color = Color.LightGray, fontSize = 12.sp)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value = subscription, onValueChange = { subscription = it }, label = { Text("URL подписки (каждая с новой строки)") },
                            modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 5)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = {
                            val urls = subscription.lines().map { it.trim() }.filter { it.startsWith("http://") || it.startsWith("https://") }
                            if (urls.isEmpty()) {
                                val parsed = parseVless(decodeSubscription(subscription))
                                servers = parsed
                                selected = parsed.firstOrNull()?.rawLink.orEmpty()
                                pingResults = emptyMap()
                                failedPings = emptySet()
                                onSaveServers(parsed.joinToString("\n") { it.rawLink })
                                message = "Импортировано и сохранено серверов: ${parsed.size}"
                                onSaveSubscription(subscription)
                            } else {
                                loading = true
                                onSaveSubscription(subscription)
                                executor.execute {
                                    val all = mutableListOf<VlessServer>()
                                    val errors = mutableListOf<String>()
                                    urls.forEach { address ->
                                        try {
                                            val conn = (URL(address).openConnection() as HttpURLConnection).apply {
                                                connectTimeout = 12000
                                                readTimeout = 18000
                                                instanceFollowRedirects = true
                                                setRequestProperty("User-Agent", "FreedomTunnel/1.0")
                                            }
                                            val body = try { conn.inputStream.bufferedReader().use { it.readText() } }
                                                finally { conn.disconnect() }
                                            all += parseVless(decodeSubscription(body))
                                        } catch (e: Exception) { errors += "${address.take(35)}: ${e.message ?: "ошибка"}" }
                                    }
                                    val result = all.distinctBy { it.key + it.uuid }
                                    mainHandler.post {
                                        servers = result
                                        pingResults = emptyMap()
                                        failedPings = emptySet()
                                        selected = result.firstOrNull()?.rawLink.orEmpty()
                                        onSaveServers(result.joinToString("\n") { it.rawLink })
                                        loading = false
                                        message = "Загружено и сохранено: ${result.size} серверов из ${urls.size} подписок" +
                                            if (errors.isNotEmpty()) " • ошибок: ${errors.size}" else ""
                                        tab = 1
                                    }
                                }
                            }
                        }, enabled = !loading, modifier = Modifier.fillMaxWidth()) { Text(if (loading) "ЗАГРУЖАЕМ..." else "ЗАГРУЗИТЬ / ОБНОВИТЬ") }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { pingAll(servers) }, enabled = servers.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("ПРОВЕРИТЬ ПИНГ ВСЕХ СЕРВЕРОВ") }
                        Text("Сейчас в списке: ${servers.size}. Серверы сохраняются до следующей загрузки подписки.", color = Color.LightGray, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerCard(
    server: VlessServer,
    ping: Long?,
    isPinging: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
    onPing: () -> Unit,
    onConnect: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = if (selected) Color(0xFF24503F) else Color(0xFF172338)),
        shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Column(Modifier.fillMaxWidth().padding(11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(server.name, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(ping?.let { "${it} мс" } ?: if (isPinging) "проверка…" else "—", color = if (ping != null) Color(0xFF56D6A0) else Color.LightGray, fontSize = 12.sp)
            }
            Text("${server.host}:${server.port} • ${server.security.uppercase()} • ${server.network.uppercase()}", color = Color.LightGray, fontSize = 11.sp)
            Spacer(Modifier.height(7.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = onPing, enabled = !isPinging, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 5.dp)) {
                    Text(if (isPinging) "ПИНГУЕМ" else "ПИНГ", fontSize = 11.sp)
                }
                OutlinedButton(onClick = onSelect, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 5.dp)) { Text(if (selected) "ВЫБРАН" else "ВЫБРАТЬ", fontSize = 11.sp) }
                Button(onClick = onConnect, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 5.dp)) { Text("СТАРТ", fontSize = 11.sp) }
            }
        }
    }
}
