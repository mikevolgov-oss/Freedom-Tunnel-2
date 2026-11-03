package com.freedomtunnel.app

import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Decoder for encrypted CSQTT v2 share links.
 * This only parses a profile; it does not start a CSQTT tunnel.
 */
data class CsqttProfile(
    val host: String,
    val port: Int,
    val password: String = "",
    val hashes: List<String> = emptyList(),
    val vkAccessToken: String = ""
) {
    fun peerAddress(): String {
        val formatted = if (host.contains(':') && !host.startsWith('[')) "[$host]" else host
        return "$formatted:$port"
    }
}

object CsqttLinkParser {
    private const val PREFIX = "csqtt://"
    private const val KEY_SEED = "csqtt-chiper-key"

    fun parse(raw: String): CsqttProfile? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith(PREFIX, ignoreCase = true)) return null
        val encrypted = trimmed.substring(PREFIX.length).trim()
        val plain = decrypt(encrypted) ?: return null
        if (!plain.contains("host=") && !plain.contains("connect?")) return null

        val candidate = when {
            plain.startsWith("csqtt://connect?", true) -> plain
            plain.startsWith("connect?", true) -> "csqtt://$plain"
            plain.startsWith("csqtt://", true) -> "csqtt://connect?" + plain.substring(PREFIX.length)
            else -> "csqtt://connect?$plain"
        }
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        if (uri.rawUserInfo != null || uri.port != -1 || !uri.rawPath.isNullOrEmpty()) return null
        val params = parseQuery(uri.rawQuery ?: return null) ?: return null
        if (params["v"]?.let(::decode) != "2") return null

        val host = params["host"]?.let(::decode)?.takeIf { it.isNotBlank() } ?: return null
        if (host.any(Char::isWhitespace) || host in setOf("0.0.0.0", "127.0.0.1", "::1") ||
            host.equals("localhost", true)) return null
        val port = params["peer"]?.let(::decode)?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val password = params["password"]?.let(::decode)?.trim().orEmpty()
        if (password.any(Char::isWhitespace)) return null

        val hashes = params["hashes"]?.let(::parseHashes) ?: emptyList()
        if (params.containsKey("hashes") && hashes.isEmpty()) return null
        val token = params["token"]?.let(::decode)?.trim().orEmpty()
        if (params.containsKey("token") && (token.isEmpty() || token.length > 4096 || token.any(Char::isWhitespace))) return null
        if (hashes.isNotEmpty() && token.isNotEmpty()) return null
        return CsqttProfile(host, port, password, hashes, token)
    }

    private fun decrypt(payload: String): String? = runCatching {
        val normalized = payload.replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        val bytes = Base64.getDecoder().decode(padded)
        require(bytes.size >= 28)
        val key = MessageDigest.getInstance("SHA-256").digest(KEY_SEED.toByteArray(Charsets.UTF_8))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, bytes, 0, 12))
        String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
    }.getOrNull()

    private fun parseQuery(raw: String): Map<String, String>? {
        if (raw.isEmpty()) return null
        val sanitized = raw.replace("&amp;", "&")
        val result = linkedMapOf<String, String>()
        if (sanitized.contains('&') || sanitized.contains(';')) {
            for (part in sanitized.split(Regex("[&;]"))) {
                if (part.isBlank()) continue
                val split = part.indexOf('=')
                if (split <= 0) continue
                val key = decode(part.substring(0, split)) ?: continue
                if (result.containsKey(key)) return null
                result[key] = part.substring(split + 1)
            }
            if (result.keys.containsAll(listOf("v", "host", "peer"))) return result
        }
        val pattern = Regex("(?:^|[&;]|(?<=[a-zA-Z0-9_]))(v|host|peer|password|client|hashes|token|workers)=([^&?]*?)(?=(?:v|host|peer|password|client|hashes|token|workers)=|$)", RegexOption.IGNORE_CASE)
        val extracted = linkedMapOf<String, String>()
        for (match in pattern.findAll(sanitized)) {
            val key = match.groupValues[1].lowercase()
            if (extracted.containsKey(key)) return null
            extracted[key] = match.groupValues[2]
        }
        return extracted.takeIf { it.keys.containsAll(listOf("v", "host", "peer")) }
    }

    private fun parseHashes(raw: String): List<String> {
        val parts = raw.split('+')
        if (parts.size !in 1..6 || parts.any { it.isEmpty() }) return emptyList()
        val values = parts.map { encoded ->
            decode(encoded)?.let { it.trim().removePrefix("https://vk.com/call/join/").removePrefix("https://vk.ru/call/join/").trimEnd('/') }.orEmpty()
        }
        if (values.any { it.length < 16 || it.any(Char::isWhitespace) }) return emptyList()
        return values.distinct().takeIf { it.size == values.size }.orEmpty()
    }

    private fun decode(value: String): String? = runCatching {
        URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8.name())
    }.getOrNull()
}
