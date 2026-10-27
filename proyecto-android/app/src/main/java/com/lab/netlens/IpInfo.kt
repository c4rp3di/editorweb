
package com.lab.netlens

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

object IpInfo {
    private val cache = ConcurrentHashMap<String, String>()

    fun org(ip: String): String? = cache[ip]

    fun resolve(ips: Collection<String>, onDone: () -> Unit) {
        Thread {
            for (ip in ips) {
                if (cache.containsKey(ip)) continue
                try {
                    val url = URL("http://ip-api.com/json/$ip?fields=status,country,isp,as,org")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    conn.setRequestProperty("User-Agent", "NetLens/1.0")
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(text)
                    if (json.optString("status") == "success") {
                        val org = json.optString("org", "").ifEmpty { json.optString("isp", "") }
                        val asn = json.optString("as", "")
                        val country = json.optString("country", "")
                        val value = listOf(org, asn, country).filter { it.isNotEmpty() }.joinToString(" · ")
                        if (value.isNotEmpty()) {
                            cache[ip] = value
                            for (f in Store.snapshot()) {
                                if (f.dstIp == ip) {
                                    f.org = value
                                    f.stat.orgs.add(value)
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
            onDone()
        }.start()
    }
}

