package ir.ac.ui.sfd.network

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap

data class SerializableCookie(
    val name: String,
    val value: String,
    val expiresAt: Long,
    val domain: String,
    val path: String,
    val secure: Boolean,
    val httpOnly: Boolean,
    val hostOnly: Boolean
) {
    fun toCookie(): Cookie {
        val builder = Cookie.Builder()
            .name(name)
            .value(value)
            .expiresAt(expiresAt)
            .path(path)
        if (hostOnly) builder.hostOnlyDomain(domain) else builder.domain(domain)
        if (secure) builder.secure()
        if (httpOnly) builder.httpOnly()
        return builder.build()
    }

    companion object {
        fun fromCookie(cookie: Cookie): SerializableCookie {
            return SerializableCookie(
                name = cookie.name,
                value = cookie.value,
                expiresAt = cookie.expiresAt,
                domain = cookie.domain,
                path = cookie.path,
                secure = cookie.secure,
                httpOnly = cookie.httpOnly,
                hostOnly = cookie.hostOnly
            )
        }
    }
}

class PersistentCookieJar(context: Context? = null) : CookieJar {
    private val cookieStore = ConcurrentHashMap<String, MutableList<Cookie>>()
    private val prefs: SharedPreferences? = context?.getSharedPreferences("sfd_cookies", Context.MODE_PRIVATE)

    init {
        loadFromDisk()
    }

    private fun loadFromDisk() {
        val json = prefs?.getString("cookies_json", null) ?: return
        try {
            val type = object : TypeToken<Map<String, List<SerializableCookie>>>() {}.type
            val map: Map<String, List<SerializableCookie>> = Gson().fromJson(json, type)
            val now = System.currentTimeMillis()
            for ((host, list) in map) {
                val cookies = list.map { it.toCookie() }.filter { it.expiresAt >= now }.toMutableList()
                if (cookies.isNotEmpty()) {
                    cookieStore[host] = cookies
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveToDisk() {
        val sp = prefs ?: return
        try {
            val serializableMap = mutableMapOf<String, List<SerializableCookie>>()
            for ((host, list) in cookieStore) {
                serializableMap[host] = list.map { SerializableCookie.fromCookie(it) }
            }
            sp.edit().putString("cookies_json", Gson().toJson(serializableMap)).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val host = url.host
        val currentCookies = cookieStore.getOrPut(host) { mutableListOf() }
        synchronized(currentCookies) {
            for (newCookie in cookies) {
                currentCookies.removeAll { it.name == newCookie.name }
                currentCookies.add(newCookie)
            }
        }
        saveToDisk()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val host = url.host
        val currentCookies = cookieStore[host] ?: return emptyList()
        val now = System.currentTimeMillis()
        synchronized(currentCookies) {
            currentCookies.removeAll { it.expiresAt < now }
            return currentCookies.toList()
        }
    }

    fun hasValidSession(domain: String = "self.mui.ac.ir"): Boolean {
        val now = System.currentTimeMillis()
        val list = cookieStore[domain]
        if (list != null && list.any { it.name == "ASP.NET_SessionId" && it.expiresAt >= now && it.value.isNotBlank() }) {
            return true
        }
        return cookieStore.values.any { bucket ->
            bucket.any { it.name == "ASP.NET_SessionId" && it.expiresAt >= now && it.value.isNotBlank() }
        }
    }

    fun getAllCookies(domain: String = "self.mui.ac.ir"): List<Cookie> {
        val list = mutableListOf<Cookie>()
        val direct = cookieStore[domain]
        if (direct != null) list.addAll(direct)
        cookieStore.values.forEach { bucket ->
            bucket.forEach { c ->
                if (list.none { it.name == c.name }) list.add(c)
            }
        }
        return list
    }

    fun clear() {
        cookieStore.clear()
        prefs?.edit()?.clear()?.apply()
    }
}
