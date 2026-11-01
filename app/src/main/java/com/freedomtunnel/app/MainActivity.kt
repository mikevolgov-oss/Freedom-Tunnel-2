
package com.freedomtunnel.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
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
    var message by remember { mutableStateOf("Заполните параметры сервера") }

    val background = Color(0xFF0B1220)
    val green = Color(0xFF56D6A0)

    MaterialTheme(colorScheme = darkColorScheme(primary = green)) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(28.dp))

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

                Spacer(modifier = Modifier.height(28.dp))

                Text(
                    "Настройка подключения",
                    color = Color.White,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(20.dp))

                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it },
                    label = { Text("Адрес сервера") },
                    placeholder = { Text("example.com или IP") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    label = { Text("Порт") },
                    placeholder = { Text("443") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = uuid,
                    onValueChange = { uuid = it },
                    label = { Text("UUID") },
                    placeholder = {
                        Text("xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(20.dp))

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
                        .height(54.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        "СОХРАНИТЬ НАСТРОЙКИ",
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                Button(
                    onClick = {
                        message = "VPN-движок ещё не подключён"
                    },
                    enabled = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("ПОДКЛЮЧИТЬСЯ")
                }

                Spacer(modifier = Modifier.height(18.dp))

                Text(
                    message,
                    color = Color(0xFFFFC66D),
                    fontSize = 14.sp
                )

                Spacer(modifier = Modifier.weight(1f))

                Text(
                    "FREEDOM TUNNEL • версия 0.2",
                    color = Color(0xFF718096),
                    fontSize = 11.sp
                )

                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}
