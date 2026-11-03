package com.freedomtunnel.app

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

object VlessConfig {
    fun fromLink(link: String): String {
        val uri = Uri.parse(link.trim())
        require(uri.scheme == "vless") { "Поддерживаются только ссылки VLESS" }
        val host = uri.host ?: error("В ссылке не указан сервер")
        val port = uri.port.takeIf { it in 1..65535 } ?: error("Неверный порт сервера")
        val uuid = uri.userInfo ?: error("В ссылке отсутствует UUID")
        val query = uri

        val outbound = JSONObject()
            .put("type", "vless")
            .put("tag", "proxy")
            .put("server", host)
            .put("server_port", port)
            .put("uuid", uuid)

        query.getQueryParameter("flow")?.takeIf { it.isNotBlank() }?.let {
            outbound.put("flow", it)
        }

        val security = query.getQueryParameter("security") ?: "none"
        if (security == "tls" || security == "reality") {
            val tls = JSONObject()
                .put("enabled", true)
                .put("server_name", query.getQueryParameter("sni")?.takeIf { it.isNotBlank() } ?: host)
                .put("insecure", query.getQueryParameter("allowInsecure") == "1")
            query.getQueryParameter("alpn")?.takeIf { it.isNotBlank() }?.let { value ->
                tls.put("alpn", JSONArray(value.split(',').map { it.trim() }.filter { it.isNotEmpty() }))
            }
            query.getQueryParameter("fp")?.takeIf { it.isNotBlank() }?.let { fingerprint ->
                tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", fingerprint))
            }
            if (security == "reality") {
                val reality = JSONObject().put("enabled", true)
                    .put("public_key", query.getQueryParameter("pbk") ?: "")
                    .put("short_id", query.getQueryParameter("sid") ?: "")
                require(reality.optString("public_key").isNotBlank()) { "В Reality-ссылке отсутствует pbk" }
                tls.put("reality", reality)
            }
            outbound.put("tls", tls)
        }

        when (val network = query.getQueryParameter("type") ?: "tcp") {
            "ws" -> {
                val headers = JSONObject()
                query.getQueryParameter("host")?.takeIf { it.isNotBlank() }?.let { headers.put("Host", it) }
                val transport = JSONObject().put("type", "ws")
                    .put("path", query.getQueryParameter("path") ?: "/")
                if (headers.length() > 0) transport.put("headers", headers)
                outbound.put("transport", transport)
            }
            "grpc" -> outbound.put(
                "transport",
                JSONObject().put("type", "grpc")
                    .put("service_name", query.getQueryParameter("serviceName") ?: query.getQueryParameter("path") ?: "")
            )
            "httpupgrade" -> {
                val transport = JSONObject().put("type", "httpupgrade")
                    .put("path", query.getQueryParameter("path") ?: "/")
                query.getQueryParameter("host")?.takeIf { it.isNotBlank() }?.let { transport.put("host", it) }
                outbound.put("transport", transport)
            }
            "tcp", "raw" -> Unit
            else -> error("Тип транспорта пока не поддерживается: $network")
        }

        val inbounds = JSONArray().put(
            JSONObject()
                .put("type", "tun")
                .put("tag", "tun-in")
                .put("address", JSONArray().put("172.19.0.1/30").put("fdfe:dcba:9876::1/126"))
                .put("mtu", 9000)
                .put("auto_route", true)
                .put("strict_route", false)
        )
        val outbounds = JSONArray()
            .put(outbound)
            .put(JSONObject().put("type", "direct").put("tag", "direct"))
            .put(JSONObject().put("type", "block").put("tag", "block"))

        return JSONObject()
            .put("log", JSONObject().put("level", "info").put("timestamp", true))
            .put("dns", JSONObject().put("servers", JSONArray().put(
                JSONObject().put("type", "udp").put("tag", "dns-remote")
                    .put("server", "1.1.1.1").put("detour", "proxy")
            )).put("final", "dns-remote"))
            .put("inbounds", inbounds)
            .put("outbounds", outbounds)
            .put("route", JSONObject().put("auto_detect_interface", true).put("final", "proxy"))
            .toString()
    }
}
