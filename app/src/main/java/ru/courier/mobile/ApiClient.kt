package ru.courier.mobile

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import java.net.URLEncoder

class ApiClient(private val context: Context) {
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val prefs by lazy {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(context, "courier_secure", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }
    var baseUrl: String
        get() = context.getSharedPreferences("courier", Context.MODE_PRIVATE).getString("base_url", "https://example.com") ?: "https://example.com"
        set(value) { context.getSharedPreferences("courier", Context.MODE_PRIVATE).edit().putString("base_url", value.trim().trimEnd('/')).apply() }
    var token: String?
        get() = prefs.getString("token", null)
        set(value) { prefs.edit().putString("token", value).apply() }

    fun request(method: String, path: String, body: JsonObject? = null, query: String = ""): JsonObject {
        val url = baseUrl.trimEnd('/') + "/api/desktop" + path + query
        val builder = Request.Builder().url(url).header("Accept", "application/json").header("User-Agent", "CourierMobile/1.0")
        token?.let { builder.header("Authorization", "Bearer $it") }
        when (method.uppercase()) {
            "GET" -> builder.get()
            "POST" -> builder.post((body ?: JsonObject()).toString().toRequestBody(jsonType))
            "PUT" -> builder.put((body ?: JsonObject()).toString().toRequestBody(jsonType))
            "PATCH" -> builder.patch((body ?: JsonObject()).toString().toRequestBody(jsonType))
            else -> throw ApiException("HTTP method not supported: $method")
        }
        val response = try { http.newCall(builder.build()).execute() } catch (e: IOException) { throw ApiException("Нет связи с сервером: ${e.message}") }
        val raw = response.body?.string().orEmpty()
        val payload = try { JsonParser.parseString(raw).asJsonObject } catch (_: Exception) { JsonObject() }
        if (!response.isSuccessful || payload.get("success")?.asBoolean == false) {
            val message = payload.get("message")?.asString ?: payload.get("error")?.asString ?: "HTTP ${response.code}: $raw"
            if (response.code == 401) token = null
            throw ApiException("HTTP ${response.code}: $message")
        }
        return payload
    }

    fun login(login: String, password: String, tenant: String): JsonObject {
        val b = JsonObject().apply { addProperty("login", login); addProperty("password", password); if (tenant.isNotBlank()) addProperty("tenant", tenant) }
        val result = request("POST", "/auth/login", b)
        token = result.getAsJsonObject("data")?.get("token")?.asString ?: throw ApiException("Сервер не вернул токен")
        return result
    }
    fun me() = request("GET", "/auth/me")
    fun logout() = request("POST", "/auth/logout")
    fun dashboard() = request("GET", "/dashboard")
    fun orders(kind: String): JsonObject {
        val today = LocalDate.now().toString()
        return request("GET", "/orders/$kind", query = "?date_from=$today&date_to=$today")
    }
    fun accept(id: String) = request("POST", "/orders/$id/accept")
    fun status(id: String, status: Int) = request("PATCH", "/orders/$id/status", JsonObject().apply { addProperty("status", status) })
    fun confirm(id: String) = request("POST", "/orders/$id/confirm-delivery")
    fun updateOrder(id: String, distance: Double?, price: Double?) = request("PUT", "/orders/$id/details", JsonObject().apply { distance?.let { addProperty("distance_km", it) }; price?.let { addProperty("delivery_price", it) } })
    fun shops() = request("GET", "/shops")
    fun saveShops(ids: List<Int>) = request("PUT", "/shops", JsonObject().apply { add("shop_ids", JsonArray().also { a -> ids.forEach { a.add(it) } }) })
    fun settings() = request("GET", "/settings")
    fun saveSettings(auto: Boolean, interval: Int, sound: Boolean) = request("PUT", "/settings", JsonObject().apply { addProperty("auto_refresh", auto); addProperty("refresh_interval", interval); addProperty("sound_enabled", sound) })
    fun calculate(shopId: String, olat: Double, olng: Double, dlat: Double, dlng: Double, oa: String, da: String) = request("POST", "/delivery-calculator/shops/$shopId/calculate", JsonObject().apply {
        addProperty("origin_lat", olat); addProperty("origin_lng", olng); addProperty("destination_lat", dlat); addProperty("destination_lng", dlng)
        addProperty("origin_address", oa); addProperty("destination_address", da)
    })
    fun paymentLink(shopId: String, amount: Double, description: String) = request("POST", "/shops/$shopId/payment-link", JsonObject().apply { addProperty("amount", amount); addProperty("description", description) })
    fun location(lat: Double, lng: Double) = request("POST", "/location", JsonObject().apply { addProperty("latitude", lat); addProperty("longitude", lng) })

    fun geocode(address: String): Pair<Double, Double> {
        val encoded = URLEncoder.encode(address, "UTF-8")
        val req = Request.Builder().url("https://nominatim.openstreetmap.org/search?q=$encoded&format=json&limit=1")
            .header("User-Agent", "CourierMobile/1.0 (delivery app)").header("Accept", "application/json").get().build()
        val response = try { http.newCall(req).execute() } catch (e: IOException) { throw ApiException("Ошибка поиска адреса: ${e.message}") }
        val arr = try { JsonParser.parseString(response.body?.string().orEmpty()).asJsonArray } catch (_: Exception) { JsonArray() }
        if (!response.isSuccessful || arr.size() == 0) throw ApiException("Адрес не найден. Уточни запрос или выбери точку на карте.")
        val item = arr[0].asJsonObject
        return item.get("lat").asString.toDouble() to item.get("lon").asString.toDouble()
    }

    fun messages(dialogId: String) = request("GET", "/dialogs/$dialogId/messages")
    fun sendMessage(dialogId: String, message: String) = request("POST", "/dialogs/$dialogId/messages", JsonObject().apply { addProperty("message", message) })
}

class ApiException(message: String) : Exception(message)
