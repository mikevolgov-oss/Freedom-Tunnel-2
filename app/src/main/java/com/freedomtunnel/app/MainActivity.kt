
package com.freedomtunnel.app

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

data class VlessServer(
    val name: String,
    val host: String,
    val port: String,
    val uuid: String,
    val security: String,
    val network: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("freedom_tunnel_config", MODE_PRIVATE)

        setContent {
            FreedomTunnelScreen(
                savedServer = prefs.getString("server", "") ?: "",
                savedPort = prefs.getString("port", "443") ?: "443",
                savedUuid = prefs.getString("uuid", "") ?: "",
                onSave = { server, port, uuid ->
                    prefs.edit()
                        .putString("server", server)
                        .putString("port", port)
                        .putString("uuid", uuid)
                        .apply()
                }
            )
        }
    }
}

@Composable
fun FreedomTunnelScreen(
    savedServer: String,
    savedPort: String,
    savedUuid: String,
    onSave: (String, String, String) -> Unit
) {
    var server by remember { mutableStateOf(savedServer) }
    var port by remember { mutableStateOf(savedPort) }
    var uuid by remember { mutableStateOf(savedUuid) }

    var subscription by remember {
        mutableStateOf("https://radarhub.top/by-protocol/vless.txt")
    }

    var servers by remember { mutableStateOf(listOf<VlessServer>()) }
    var message by remember { mutableStateOf("Загрузите список серверов") }
    var loading by remember { mutableStateOf(false) }

    val handler = remember { Handler(Looper.getMainLooper()) }
    val executor = remember { Executors.newSingleThreadExecutor() }

    val green = Color(0xFF56D6A0)
    val background = Color(0xFF0B1220)

    MaterialTheme(colorScheme = darkColorScheme(primary = green)) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    "FREEDOM",
                    color = Color.White,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 3.sp
                )

                Text(
                    "TUNNEL",
                    color = green,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 7.sp
                )

                Spacer(modifier = Modifier.height(25.dp))

                Text(
                    "Подписка VLESS",
                    color = Color.White,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = subscription,
                    onValueChange = { subscription = it },
                    label = { Text("Ссылка на подписку") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        if (subscription.isBlank()) {
                            message = "Введите ссылку на подписку"
                        } else {
                            loading = true
                            message = "Загружаем серверы..."

                            executor.execute {
                                try {
                                    val connection =
                                        URL(subscription).openConnection()
                                            as HttpURLConnection

                                    connection.connectTimeout = 15000
                                    connection.readTimeout = 20000
                                    connection.requestMethod = "GET"
                                    connection.setRequestProperty(
                                        "User-Agent",
                                        "FreedomTunnel/0.3"
                                    )

                                    val response = connection.inputStream
                                        .bufferedReader()
                                        .use { it.readText() }

                                    connection.disconnect()

                                    var content = response.trim()

                                    if (!content.contains("vless://")) {
                                        val decoded = try {
                                            String(
                                                Base64.decode(
                                                    content,
                                                    Base64.DEFAULT
                                                )
                                            )
                                        } catch (e: Exception) {
                                            ""
                                        }

                                        if (decoded.contains("vless://")) {
                                            content = decoded
                                        }
                                    }

                                    val found = content.lines().mapNotNull { line ->
                                        try {
                                            val start = line.indexOf("vless://")
                                            if (start < 0) return@mapNotNull null

                                            val link = line.substring(start).trim()
                                            val uri = Uri.parse(link)

                                            val host = uri.host
                                                ?: return@mapNotNull null
                                            val portValue = uri.port
                                            val id = uri.userInfo
                                                ?: return@mapNotNull null

                                            if (portValue == null ||
                                                portValue !in 1..65535
                                            ) {
                                                return@mapNotNull null
                                            }

                                            VlessServer(
                                                name = Uri.decode(
                                                    uri.fragment ?: host
                                                ),
                                                host = host,
                                                port = portValue.toString(),
                                                uuid = id,
                                                security = uri.getQueryParameter(
                                                    "security"
                                                ) ?: "none",
                                                network = uri.getQueryParameter(
                                                    "type"
                                                ) ?: "tcp"
                                            )
                                        } catch (e: Exception) {
                                            null
                                        }
                                    }.distinctBy {
                                        it.uuid + it.host + it.port
                                    }

                                    handler.post {
                                        servers = found
                                        loading = false
                                        message = if (found.isEmpty()) {
                                            "Серверы VLESS не найдены"
                                        } else {
                                            "Найдено серверов: ${found.size}"
                                        }
                                    }
                                } catch (e: Exception) {
                                    handler.post {
                                        loading = false
                                        message =
                                            "Ошибка загрузки: ${e.message ?: "проверьте интернет"}"
                                    }
                                }
                            }
                        }
                    },
                    enabled = !loading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        if (loading) "ЗАГРУЗКА..." else "ЗАГРУЗИТЬ СЕРВЕРЫ",
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    message,
                    color = Color(0xFFFFC66D),
                    fontSize = 14.sp
                )

                if (servers.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(20.dp))

                    Text(
                        "НАЙДЕННЫЕ СЕРВЕРЫ",
                        color = green,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )

                    servers.forEachIndexed { index, item ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 5.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xFF172338)
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp)
                            ) {
                                Text(
                                    item.name.ifBlank { "Сервер ${index + 1}" },
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )

                                Text(
                                    "${item.host}:${item.port}",
                                    color = Color.LightGray,
                                    fontSize = 13.sp
                                )

                                Text(
                                    "Защита: ${item.security} • Сеть: ${item.network}",
                                    color = Color.LightGray,
                                    fontSize = 12.sp
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                Button(
                                    onClick = {
                                        server = item.host
                                        port = item.port
                                        uuid = item.uuid
                                        message = "Сервер выбран: ${item.name}"
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("ВЫБРАТЬ ЭТОТ СЕРВЕР")
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    "ПАРАМЕТРЫ СЕРВЕРА",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it },
                    label = { Text("Адрес сервера") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = port,
                    onValueChange = {
                        port = it.filter(Char::isDigit).take(5)
                    },
                    label = { Text("Порт") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = uuid,
                    onValueChange = { uuid = it },
                    label = { Text("UUID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        val validPort = port.toIntOrNull()
                        val validUuid = Regex(
                            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
                        ).matches(uuid.trim())

                        when {
                            server.isBlank() ->
                                message = "Введите адрес сервера"

                            validPort == null || validPort !in 1..65535 ->
                                message = "Проверьте номер порта"

                            !validUuid ->
                                message = "Проверьте UUID"

                            else -> {
                                onSave(server.trim(), port, uuid.trim())
                                message = "Настройки сохранены"
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("СОХРАНИТЬ НАСТРОЙКИ")
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        message = "VPN-движок ещё не подключён"
                    },
                    enabled =true ,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("ПОДКЛЮЧИТЬСЯ")
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    "FREEDOM TUNNEL • версия 0.3",
                    color = Color(0xFF718096),
                    fontSize = 11.sp
                )

                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

