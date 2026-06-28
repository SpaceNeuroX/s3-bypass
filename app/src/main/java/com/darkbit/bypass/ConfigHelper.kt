package com.darkbit.bypass

import org.json.JSONArray
import org.json.JSONObject

data class SocksEndpoint(
    val host: String,
    val port: Int
) {
    fun display(): String = "$host:$port"
}

object ConfigHelper {

    fun prepareForMode(
        configContent: String,
        proxyMode: Boolean,
        proxyPort: Int = XrayService.DEFAULT_PROXY_PORT
    ): Pair<String, SocksEndpoint?> {
        if (!proxyMode) {
            return configContent to null
        }

        val json = JSONObject(configContent)
        val inbounds = json.optJSONArray("inbounds") ?: JSONArray()
        val filtered = JSONArray()
        var endpoint: SocksEndpoint? = null
        var hasSocks = false

        for (i in 0 until inbounds.length()) {
            val inbound = inbounds.getJSONObject(i)
            val protocol = inbound.optString("protocol", "").lowercase()
            if (protocol == "tun") continue
            if (protocol == "socks") {
                hasSocks = true
                val host = inbound.optString("listen", "127.0.0.1").ifBlank { "127.0.0.1" }
                inbound.put("listen", host)
                inbound.put("port", proxyPort)
                endpoint = SocksEndpoint(host, proxyPort)
            }
            filtered.put(inbound)
        }

        if (!hasSocks) {
            endpoint = SocksEndpoint("127.0.0.1", proxyPort)
            filtered.put(
                JSONObject().apply {
                    put("tag", "socks-in")
                    put("listen", endpoint.host)
                    put("port", endpoint.port)
                    put("protocol", "socks")
                    put("settings", JSONObject().apply {
                        put("auth", "noauth")
                        put("udp", true)
                    })
                }
            )
        }

        json.put("inbounds", filtered)
        return json.toString() to endpoint
    }
}
