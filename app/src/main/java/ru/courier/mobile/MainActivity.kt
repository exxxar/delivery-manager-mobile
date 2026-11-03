package ru.courier.mobile

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.media.MediaPlayer
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import org.osmdroid.views.overlay.Marker
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Navy = Color(0xFF101827)
private val Panel = Color(0xFF192638)
private val Accent = Color(0xFFE53935)
private val Blue = Color(0xFF3185E8)
private val TextMain = Color(0xFFF4F7FB)
private val Muted = Color(0xFF9BAABD)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Navy, surface = Panel)) { CourierApp(ApiClient(this)) } }
    }
}

private data class NavItem(val key: String, val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)
private val navItems = listOf(NavItem("orders", "Заказы", Icons.Default.ReceiptLong), NavItem("calculator", "Расчёт", Icons.Default.Calculate), NavItem("info", "Информация", Icons.Default.Insights), NavItem("settings", "Настройки", Icons.Default.Settings), NavItem("journal", "Журнал", Icons.Default.Terminal))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CourierApp(api: ApiClient) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var logged by remember { mutableStateOf(!api.token.isNullOrBlank()) }
    var page by remember { mutableStateOf("orders") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var user by remember { mutableStateOf(JsonObject()) }
    var dashboard by remember { mutableStateOf(JsonObject()) }
    var available by remember { mutableStateOf(listOf<JsonObject>()) }
    var active by remember { mutableStateOf(listOf<JsonObject>()) }
    var completed by remember { mutableStateOf(listOf<JsonObject>()) }
    var shops by remember { mutableStateOf(listOf<JsonObject>()) }
    var selectedShopIds by remember { mutableStateOf(setOf<Int>()) }
    var settings by remember { mutableStateOf(JsonObject()) }
    var log by remember { mutableStateOf(listOf<String>()) }
    var autoRefresh by remember { mutableStateOf(false) }
    var refreshInterval by remember { mutableIntStateOf(30) }
    var soundEnabled by remember { mutableStateOf(true) }
    var orderTab by remember { mutableStateOf("available") }
    var chatOrder by remember { mutableStateOf<JsonObject?>(null) }
    var knownAvailableIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    fun addLog(s: String) { log = (listOf("${java.time.LocalTime.now().toString().take(8)}  $s") + log).take(300) }
    suspend fun call(label: String, block: suspend () -> Unit) {
        busy = true; error = ""
        try { block(); addLog("OK: $label") } catch (e: Exception) { error = e.message ?: "Ошибка"; addLog("ОШИБКА: $label — $error") }
        finally { busy = false }
    }
    suspend fun loadAll() {
        call("Обновление данных") {
            withContext(Dispatchers.IO) {
                val d = api.dashboard().obj("data")?.obj("deliveryman") ?: JsonObject()
                val a = arrayListOf<JsonObject>(); a.addAll(api.orders("available").arrayData())
                val ac = api.orders("active").arrayData(); val co = api.orders("completed").arrayData()
                val sh = api.shops().obj("data")
                val shopArray = sh?.array("shops") ?: JsonArray()
                val selected = sh?.array("selected_ids")?.mapNotNull { it.intOrNull() }?.toSet() ?: emptySet()
                val st = api.settings().obj("data") ?: JsonObject()
                withContext(Dispatchers.Main) {
                    val freshIds = a.map { it.str("id") }.toSet()
                    if (knownAvailableIds.isNotEmpty() && soundEnabled && freshIds.any { it !in knownAvailableIds }) {
                        try { MediaPlayer.create(context, R.raw.new_order)?.apply { setOnCompletionListener { it.release() }; start() } } catch (_: Exception) {}
                    }
                    knownAvailableIds = freshIds
                    dashboard = d; available = a; active = ac; completed = co; shops = shopArray.mapNotNull { it.asObjOrNull() }; selectedShopIds = selected; settings = st
                    autoRefresh = st.bool("auto_refresh", false); refreshInterval = st.int("refresh_interval", 30); soundEnabled = st.bool("sound_enabled", true)
                }
            }
        }
    }

    LaunchedEffect(logged) {
        if (logged) {
            call("Проверка сессии") { withContext(Dispatchers.IO) { val m = api.me(); user = m.obj("data")?.obj("user") ?: JsonObject() } }
            loadAll()
        }
    }
    LaunchedEffect(logged, autoRefresh, refreshInterval) {
        while (logged && autoRefresh) { delay((refreshInterval.coerceAtLeast(30) * 1000L)); loadAll() }
    }

    if (!logged) {
        LoginScreen(api, busy, error) { login, pass, tenant, base ->
            scope.launch {
                call("Авторизация") {
                    val r = withContext(Dispatchers.IO) { api.baseUrl = base; api.login(login, pass, tenant) }; user = r.obj("data")?.obj("user") ?: JsonObject()
                    logged = true
                }
            }
        }
        return
    }

    Scaffold(containerColor = Navy, topBar = {
        TopAppBar(title = { Column { Text("COURIER", fontWeight = FontWeight.Black, letterSpacing = 2.sp); Text(user.str("name", "Курьер"), color = Muted, fontSize = 12.sp) } }, actions = {
            IconButton(onClick = { scope.launch { loadAll() } }) { Icon(Icons.Default.Refresh, "Обновить", tint = TextMain) }
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Accent)
        }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Navy, titleContentColor = TextMain))
    }, bottomBar = {
        NavigationBar(containerColor = Panel) {
            navItems.forEach { item -> NavigationBarItem(selected = page == item.key, onClick = { page = item.key }, icon = { Icon(item.icon, item.title) }, label = { Text(item.title, fontSize = 10.sp) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = Accent, selectedTextColor = TextMain, unselectedTextColor = Muted, indicatorColor = Navy)) }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).background(Navy)) {
            if (error.isNotBlank()) Text(error, color = Color(0xFFFF7777), modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp))
            when (page) {
                "orders" -> OrdersScreen(orderTab, { orderTab = it }, available, active, completed, busy,
                    onAccept = { id -> scope.launch { call("Принятие заказа #$id") { withContext(Dispatchers.IO) { api.accept(id) }; loadAll() } } },
                    onStatus = { id, s -> scope.launch { call("Статус заказа #$id") { withContext(Dispatchers.IO) { api.status(id, s) }; loadAll() } } },
                    onConfirm = { id -> scope.launch { call("Завершение заказа #$id") { withContext(Dispatchers.IO) { api.confirm(id) }; loadAll() } } },
                    onChat = { chatOrder = it }, onSaveDetails = { id, d, p -> scope.launch { call("Сохранение параметров заказа") { withContext(Dispatchers.IO) { api.updateOrder(id, d, p) }; loadAll() } } })
                "calculator" -> CalculatorScreen(api, shops, selectedShopIds, addLog = ::addLog, runCall = { label, block -> scope.launch { call(label, block) } })
                "info" -> InfoScreen(dashboard, user, available.size, active.size, completed.size, onRefresh = { scope.launch { loadAll() } })
                "settings" -> SettingsScreen(autoRefresh, refreshInterval, soundEnabled, shops, selectedShopIds,
                    onSave = { a, i, s, ids -> scope.launch { call("Сохранение настроек") { withContext(Dispatchers.IO) { api.saveSettings(a, i, s); api.saveShops(ids.toList()) }; autoRefresh = a; refreshInterval = i; soundEnabled = s; selectedShopIds = ids } } },
                    onLogout = { scope.launch { call("Выход") { withContext(Dispatchers.IO) { api.logout() }; api.token = null; logged = false } } })
                "journal" -> JournalScreen(log, { log = emptyList() })
            }
        }
    }
    if (chatOrder != null) ChatDialog(api, chatOrder!!, { chatOrder = null }, addLog = ::addLog)
}

@Composable
private fun LoginScreen(api: ApiClient, busy: Boolean, error: String, onLogin: (String,String,String,String)->Unit) {
    var base by remember { mutableStateOf(api.baseUrl.takeUnless { it == "https://example.com" } ?: "https://your-domain.ru") }
    var login by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }; var tenant by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(Navy).padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("COURIER", color = TextMain, fontSize = 30.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
        Text("Мобильное приложение курьера", color = Muted, modifier = Modifier.padding(bottom = 24.dp))
        Field("Адрес Laravel API (домен без /api)", base, { base = it }, singleLine = true)
        Field("Телефон или email", login, { login = it }, singleLine = true)
        Field("Пароль", password, { password = it }, singleLine = true, password = true)
        Field("Tenant ID / slug (необязательно)", tenant, { tenant = it }, singleLine = true)
        if (error.isNotBlank()) Text(error, color = Color(0xFFFF7777), modifier = Modifier.padding(vertical = 8.dp))
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { onLogin(login.trim(), password, tenant.trim(), base.trim().trimEnd('/')) },
            enabled = !busy &&
                login.isNotBlank() &&
                password.isNotBlank() &&
                (base.trim().startsWith("http://", ignoreCase = true) ||
                 base.trim().startsWith("https://", ignoreCase = true)),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = TextMain,
                    strokeWidth = 2.dp
                )
            } else {
                Text("ВОЙТИ")
            }
        }
        Text("API: /api/desktop • Токен хранится в Android Keystore", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 14.dp))
    }
}

@Composable
private fun OrdersScreen(tab: String, setTab: (String)->Unit, available: List<JsonObject>, active: List<JsonObject>, completed: List<JsonObject>, busy: Boolean,
                         onAccept: (String)->Unit, onStatus: (String,Int)->Unit, onConfirm: (String)->Unit, onChat: (JsonObject)->Unit, onSaveDetails: (String,Double?,Double?)->Unit) {
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = listOf("available","active","completed").indexOf(tab).coerceAtLeast(0), containerColor = Navy, contentColor = Accent) {
            listOf("available" to "Доступные", "active" to "Активные", "completed" to "Завершённые").forEach { (key,title) -> Tab(tab == key, { setTab(key) }, text = { Text(title, fontSize = 11.sp) }) }
        }
        val data = when(tab) { "active" -> active; "completed" -> completed; else -> available }
        if (data.isEmpty()) EmptyState(if (busy) "Загрузка заказов…" else "Заказов пока нет")
        else LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(data, key = { it.str("id") }) { order -> OrderCard(order, tab, onAccept, onStatus, onConfirm, onChat, onSaveDetails) }
        }
    }
}

@Composable
private fun OrderCard(o: JsonObject, tab: String, onAccept: (String)->Unit, onStatus: (String,Int)->Unit, onConfirm: (String)->Unit, onChat: (JsonObject)->Unit, onSave: (String,Double?,Double?)->Unit) {
    var expanded by remember { mutableStateOf(false) }; var distance by remember(o) { mutableStateOf(o.num("distance_km").toString()) }; var price by remember(o) { mutableStateOf(o.num("delivery_price").toString()) }
    val id = o.str("id", "?"); val status = o.int("status", 0)
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Заказ #$id", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 18.sp); Spacer(Modifier.weight(1f)); StatusPill(status) }
            Text(o.str("tenant_name", "Заведение"), color = Blue, fontWeight = FontWeight.SemiBold)
            Text(o.str("address", "Адрес не указан"), color = TextMain)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Товары: ${o.num("summary_price")} ₽", color = Muted); Text("Доставка: ${o.num("delivery_price")} ₽", color = Muted) }
            Text("Получатель: ${o.str("receiver_name", "не указан")} • ${o.str("receiver_phone", "нет телефона")}", color = Muted, fontSize = 12.sp)
            if (expanded) {
                Text("Примечание: ${o.str("info", "—")}", color = Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (tab == "available") Button(onClick = { onAccept(id) }, modifier = Modifier.weight(1f)) { Text("Принять") }
                    if (tab == "active") {
                        OutlinedButton(onClick = { onStatus(id, 4) }, modifier = Modifier.weight(1f)) { Text("Готов к доставке") }
                        OutlinedButton(onClick = { onStatus(id, 1) }, modifier = Modifier.weight(1f)) { Text("В пути") }
                    }
                }
                if (tab == "active") {
                    OutlinedButton(onClick = { onConfirm(id) }, modifier = Modifier.fillMaxWidth()) { Text("Подтвердить доставку") }
                    OutlinedButton(onClick = { onChat(o) }, modifier = Modifier.fillMaxWidth()) { Text("Открыть чат") }
                }
                if (tab == "available") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(distance, { distance = it }, label = { Text("Км") }, modifier = Modifier.weight(1f)); OutlinedTextField(price, { price = it }, label = { Text("Доставка ₽") }, modifier = Modifier.weight(1f)) }
                    TextButton(onClick = { onSave(id, distance.toDoubleOrNull(), price.toDoubleOrNull()) }) { Text("Сохранить стоимость и расстояние") }
                }
            }
            Text(if (expanded) "Свернуть ▲" else "Подробнее ▼", color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun StatusPill(status: Int) { val label = when(status) { 1 -> "В доставке"; 4 -> "Готов"; 5 -> "Готовится"; 2 -> "Завершён"; else -> "Новый" }; Surface(color = Accent.copy(alpha=.18f), shape = RoundedCornerShape(50)) { Text(label, color = Color(0xFFFF8A80), modifier = Modifier.padding(horizontal=10.dp, vertical=5.dp), fontSize=11.sp) } }

@Composable
private fun InfoScreen(d: JsonObject, user: JsonObject, available: Int, active: Int, completed: Int, onRefresh: ()->Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Информация", color = TextMain, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(user.str("name", "Курьер"), color = Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { StatCard("Доступные", available.toString(), Modifier.weight(1f)); StatCard("Активные", active.toString(), Modifier.weight(1f)) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { StatCard("Завершённые", completed.toString(), Modifier.weight(1f)); StatCard("Заработано", "${d.num("earned")} ₽", Modifier.weight(1f)) }
        StatCard("Статус", if (d.str("status") == "online") "На линии" else "Не на линии", Modifier.fillMaxWidth())
        Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Refresh, null); Text("Обновить информацию") }
    }
}

@Composable
private fun StatCard(title: String, value: String, modifier: Modifier = Modifier) { Card(modifier, colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) { Column(Modifier.padding(16.dp)) { Text(title, color = Muted, fontSize = 12.sp); Text(value, color = TextMain, fontSize = 22.sp, fontWeight = FontWeight.Bold) } } }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalculatorScreen(api: ApiClient, shops: List<JsonObject>, selected: Set<Int>, addLog: (String)->Unit, runCall: (String, suspend ()->Unit)->Unit) {
    val context = LocalContext.current
    var shopId by remember { mutableStateOf("") }; var expanded by remember { mutableStateOf(false) }
    var originLat by remember { mutableStateOf("") }; var originLng by remember { mutableStateOf("") }; var destLat by remember { mutableStateOf("") }; var destLng by remember { mutableStateOf("") }
    var originAddress by remember { mutableStateOf("") }; var destAddress by remember { mutableStateOf("") }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var mapTarget by remember { mutableStateOf("destination") }; var result by remember { mutableStateOf<JsonObject?>(null) }; var paymentUrl by remember { mutableStateOf("") }
    val availableShops = shops.filter { selected.isEmpty() || it.int("id", -1) in selected }
    LaunchedEffect(availableShops) { if (shopId.isBlank() && availableShops.isNotEmpty()) { val s = availableShops.first(); shopId = s.str("id"); val coords = s.get("shop_coords")?.toString()?.trim('"')?.split(','); if (coords?.size == 2) { originLat = coords[0].trim(); originLng = coords[1].trim() } } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Калькулятор доставки", color = TextMain, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(value = availableShops.firstOrNull { it.str("id") == shopId }?.str("name", "Выберите заведение") ?: "Заведение не выбрано", onValueChange = {}, readOnly = true, label = { Text("Заведение и тариф") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.fillMaxWidth().menuAnchor())
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) { availableShops.forEach { s -> DropdownMenuItem(text = { Text(s.str("name")) }, onClick = { shopId = s.str("id"); val c = s.get("shop_coords")?.toString()?.trim('"')?.split(','); if (c?.size == 2) { originLat = c[0].trim(); originLng = c[1].trim() }; expanded = false }) } }
        }
        Text("Точка на карте задаёт: ", color = Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(mapTarget == "origin", { mapTarget = "origin" }, label = { Text("Отправление") }); FilterChip(mapTarget == "destination", { mapTarget = "destination" }, label = { Text("Назначение") }) }
        AndroidView(factory = { ctx ->
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK); setMultiTouchControls(true); controller.setZoom(12.0); controller.setCenter(GeoPoint(55.751244, 37.618423))
                overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                        if (mapTarget == "origin") { originLat = p.latitude.toString(); originLng = p.longitude.toString() } else { destLat = p.latitude.toString(); destLng = p.longitude.toString() }
                        addLog("Карта: $mapTarget = ${p.latitude}, ${p.longitude}"); return true
                    }
                    override fun longPressHelper(p: GeoPoint) = false
                }))
            }
        }, update = { map ->
            map.overlays.removeAll { it is Marker }
            val olat = originLat.toDoubleOrNull(); val olng = originLng.toDoubleOrNull()
            val dlat = destLat.toDoubleOrNull(); val dlng = destLng.toDoubleOrNull()
            if (olat != null && olng != null) map.overlays.add(Marker(map).apply { position = GeoPoint(olat, olng); title = "Отправление"; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) })
            if (dlat != null && dlng != null) map.overlays.add(Marker(map).apply { position = GeoPoint(dlat, dlng); title = "Назначение"; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) })
            map.invalidate()
        }, modifier = Modifier.fillMaxWidth().height(280.dp))
        Text("Нажми на карту после выбора типа точки", color = Muted, fontSize = 12.sp)
        Text("Откуда", color = Blue, fontWeight = FontWeight.Bold)
        Field("Адрес отправления", originAddress, { originAddress = it })
        OutlinedButton(onClick = { if (originAddress.isNotBlank()) runCall("Поиск адреса отправления") { val p = withContext(Dispatchers.IO) { api.geocode(originAddress) }; originLat = p.first.toString(); originLng = p.second.toString(); addLog("Nominatim origin: $p") } }, modifier = Modifier.fillMaxWidth()) { Text("Найти отправление на карте") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Field("Широта", originLat, { originLat = it }, Modifier.weight(1f)); Field("Долгота", originLng, { originLng = it }, Modifier.weight(1f)) }
        Text("Куда", color = Accent, fontWeight = FontWeight.Bold)
        Field("Адрес назначения", destAddress, { destAddress = it })
        OutlinedButton(onClick = { if (destAddress.isNotBlank()) runCall("Поиск адреса назначения") { val p = withContext(Dispatchers.IO) { api.geocode(destAddress) }; destLat = p.first.toString(); destLng = p.second.toString(); addLog("Nominatim destination: $p") } }, modifier = Modifier.fillMaxWidth()) { Text("Найти назначение на карте") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Field("Широта", destLat, { destLat = it }, Modifier.weight(1f)); Field("Долгота", destLng, { destLng = it }, Modifier.weight(1f)) }
        Button(onClick = {
            val olat = originLat.toDoubleOrNull(); val olng = originLng.toDoubleOrNull(); val dlat = destLat.toDoubleOrNull(); val dlng = destLng.toDoubleOrNull()
            if (shopId.isBlank() || olat == null || olng == null || dlat == null || dlng == null) { addLog("Расчёт отменён: выбери заведение и задай координаты обеих точек") }
            else runCall("Расчёт доставки для заведения $shopId") { withContext(Dispatchers.IO) { val r = api.calculate(shopId, olat, olng, dlat, dlng, originAddress, destAddress); result = r.obj("data"); addLog("API calculate: ${r}") } }
        }, modifier = Modifier.fillMaxWidth().height(50.dp)) { Icon(Icons.Default.Calculate, null); Text("Рассчитать стоимость") }
        result?.let { r ->
            Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Результат расчёта", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("${r.num("distance_km")} км", color = Blue, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("${r.num("delivery_price")} ₽", color = Accent, fontSize = 28.sp, fontWeight = FontWeight.Black)
                val b = r.obj("breakdown") ?: JsonObject(); Text("Зона: ${b.str("zone_name", "—")}", color = TextMain); Text("База: ${b.num("base_price")} ₽ • Км: ${b.num("price_per_km")} ₽ • Расстояние: ${b.num("distance_cost")} ₽", color = Muted)
                OutlinedButton(onClick = { runCall("Создание платёжной ссылки") { withContext(Dispatchers.IO) { paymentUrl = api.paymentLink(shopId, r.num("delivery_price"), "Доставка ${r.num("distance_km")} км").obj("data")?.str("url", "") ?: ""; qrBitmap = try { MultiFormatWriter().encode(paymentUrl, BarcodeFormat.QR_CODE, 480, 480).let { matrix -> Bitmap.createBitmap(480, 480, Bitmap.Config.RGB_565).also { bmp -> for (x in 0 until 480) for (y in 0 until 480) bmp.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE) } } } catch (_: Exception) { null }; addLog("Payment URL: $paymentUrl") } } }) { Text("Создать ссылку на оплату") }
                if (paymentUrl.isNotBlank()) { Text(paymentUrl, color = Blue, fontSize = 12.sp); qrBitmap?.let { Image(it.asImageBitmap(), contentDescription = "QR оплаты", modifier = Modifier.size(190.dp).align(Alignment.CenterHorizontally)) } }
            } }
        }
    }
}

@Composable
private fun SettingsScreen(auto: Boolean, interval: Int, sound: Boolean, shops: List<JsonObject>, selected: Set<Int>, onSave: (Boolean,Int,Boolean,Set<Int>)->Unit, onLogout: ()->Unit) {
    var a by remember(auto) { mutableStateOf(auto) }; var i by remember(interval) { mutableIntStateOf(interval) }; var s by remember(sound) { mutableStateOf(sound) }; var ids by remember(selected) { mutableStateOf(selected) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Настройки", color = TextMain, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Card(colors = CardDefaults.cardColors(containerColor = Panel)) { Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Автообновление", color = TextMain, modifier = Modifier.weight(1f)); Switch(a, { a = it }) }
            Text("Интервал: $i сек.", color = Muted); Slider(i.toFloat(), { i = it.toInt() }, valueRange = 30f..300f, steps = 8)
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Звуковые оповещения", color = TextMain, modifier = Modifier.weight(1f)); Switch(s, { s = it }) }
        } }
        Text("Доступные заведения", color = TextMain, fontWeight = FontWeight.Bold)
        shops.forEach { shop -> Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(shop.int("id", -1) in ids, { checked -> ids = if (checked) ids + shop.int("id") else ids - shop.int("id") }); Text(shop.str("name"), color = TextMain) } }
        Button(onClick = { onSave(a, i, s, ids) }, modifier = Modifier.fillMaxWidth()) { Text("Сохранить настройки") }
        OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent)) { Text("Выйти из аккаунта") }
    }
}

@Composable
private fun JournalScreen(log: List<String>, clear: ()->Unit) {
    Column(Modifier.fillMaxSize().padding(14.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Text("Журнал событий", color = TextMain, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); TextButton(onClick = clear) { Text("Очистить") } }
        if (log.isEmpty()) EmptyState("Событий пока нет") else LazyColumn { items(log) { Text(it, color = if (it.contains("ОШИБКА")) Color(0xFFFF7777) else Muted, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) } }
    }
}

@Composable
private fun ChatDialog(api: ApiClient, order: JsonObject, close: ()->Unit, addLog: (String)->Unit) {
    val scope = rememberCoroutineScope(); val dialogId = order.str("dialog_id"); var messages by remember { mutableStateOf(listOf<JsonObject>()) }; var text by remember { mutableStateOf("") }; var err by remember { mutableStateOf("") }
    LaunchedEffect(dialogId) { if (dialogId.isNotBlank() && dialogId != "null") try { withContext(Dispatchers.IO) { messages = api.messages(dialogId).arrayData() } } catch (e: Exception) { err = e.message ?: "Ошибка" } }
    AlertDialog(onDismissRequest = close, containerColor = Panel, title = { Text("Чат заказа #${order.str("id")}", color = TextMain) }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
            if (err.isNotBlank()) Text(err, color = Accent)
            LazyColumn(Modifier.weight(1f, fill = false)) { items(messages) { Text("${it.str("sender_type")}: ${it.str("message")}", color = TextMain, modifier = Modifier.padding(vertical = 5.dp)) } }
            OutlinedTextField(text, { text = it }, label = { Text("Сообщение") }, modifier = Modifier.fillMaxWidth())
        }
    }, confirmButton = { TextButton(onClick = { if (text.isNotBlank()) scope.launch { try { withContext(Dispatchers.IO) { api.sendMessage(dialogId, text) }; addLog("Сообщение отправлено в диалог $dialogId"); text = ""; messages = withContext(Dispatchers.IO) { api.messages(dialogId).arrayData() } } catch (e: Exception) { err = e.message ?: "Ошибка" } } }) { Text("Отправить") } }, dismissButton = { TextButton(onClick = close) { Text("Закрыть") } })
}

@Composable
private fun Field(label: String, value: String, onChange: (String)->Unit, modifier: Modifier = Modifier.fillMaxWidth(), singleLine: Boolean = false, password: Boolean = false) {
    OutlinedTextField(value, onChange, label = { Text(label) }, modifier = modifier, singleLine = singleLine, visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextMain, unfocusedTextColor = TextMain, focusedBorderColor = Accent, unfocusedBorderColor = Muted, focusedLabelColor = Accent, unfocusedLabelColor = Muted))
}
@Composable private fun EmptyState(text: String) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(text, color = Muted) } }

private fun JsonObject?.obj(key: String): JsonObject? = try { this?.getAsJsonObject(key) } catch (_: Exception) { null }
private fun JsonObject?.array(key: String): JsonArray? = try { this?.getAsJsonArray(key) } catch (_: Exception) { null }
private fun JsonObject.str(key: String, default: String = ""): String = try { get(key)?.takeUnless { it.isJsonNull }?.asString ?: default } catch (_: Exception) { default }
private fun JsonObject.int(key: String, default: Int = 0): Int = try { get(key)?.asInt ?: default } catch (_: Exception) { default }
private fun JsonObject.num(key: String, default: Double = 0.0): Double = try { get(key)?.asDouble ?: default } catch (_: Exception) { default }
private fun JsonObject.bool(key: String, default: Boolean = false): Boolean = try { get(key)?.asBoolean ?: default } catch (_: Exception) { default }
private fun JsonElement.intOrNull(): Int? = try { asInt } catch (_: Exception) { null }
private fun JsonElement.asObjOrNull(): JsonObject? = try { asJsonObject } catch (_: Exception) { null }
private fun JsonObject.arrayData(): List<JsonObject> {
    val data = get("data") ?: return emptyList()
    if (data.isJsonArray) return data.asJsonArray.mapNotNull { it.asObjOrNull() }
    if (data.isJsonObject) {
        val obj = data.asJsonObject
        for (key in listOf("orders", "items", "data")) { val arr = obj.get(key); if (arr != null && arr.isJsonArray) return arr.asJsonArray.mapNotNull { it.asObjOrNull() } }
    }
    return emptyList()
}
