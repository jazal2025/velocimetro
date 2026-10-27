package com.example.gpscompass

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread
import kotlin.math.*

class MainActivity : ComponentActivity(), SensorEventListener, LocationListener {

    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager

    private var speedKmh by mutableStateOf(0f)
    private var heading by mutableStateOf(0f)
    private var hasFix by mutableStateOf(false)
    private var osmLimit by mutableStateOf<Int?>(null)
    private var manualLimit by mutableStateOf<Int?>(null)

    // Filtro paso bajo circular para la brújula
    private var sinA = 0f
    private var cosA = 0f
    private var gpsBearing: Float? = null
    private var compassHeading = 0f

    // Consulta del límite de velocidad
    private var querying = false
    private var lastQueryTime = 0L
    private var lastQueryLat = 0.0
    private var lastQueryLon = 0.0

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) startLocation()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        setContent {
            Dashboard(
                speedKmh = speedKmh,
                heading = heading,
                hasFix = hasFix,
                limit = manualLimit ?: osmLimit,
                isManual = manualLimit != null,
                onDelta = { d -> changeLimit(d) },
                onAuto = { manualLimit = null }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) startLocation()
        else permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        locationManager.removeUpdates(this)
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, this)
        } catch (e: Exception) {
            // Sin permiso o sin proveedor GPS
        }
    }

    private fun changeLimit(delta: Int) {
        val base = manualLimit ?: osmLimit ?: 50
        manualLimit = (base + delta).coerceIn(10, 150)
    }

    // ---- GPS ----
    override fun onLocationChanged(location: Location) {
        hasFix = true
        speedKmh = if (location.hasSpeed()) location.speed * 3.6f else 0f
        gpsBearing = if (location.hasBearing() && location.speed > 3f) location.bearing else null
        updateHeading()
        maybeQueryLimit(location)
    }

    // ---- Brújula ----
    override fun onSensorChanged(event: SensorEvent) {
        val r = FloatArray(9)
        val adj = FloatArray(9)
        val o = FloatArray(3)
        SensorManager.getRotationMatrixFromVector(r, event.values)
        SensorManager.getOrientation(r, o)
        // Si el móvil está en vertical (pitch cercano a ±90°), se reasignan los ejes
        val upright = abs(o[1]) > Math.toRadians(45.0)
        val az = if (upright) {
            SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_X, SensorManager.AXIS_Z, adj)
            SensorManager.getOrientation(adj, o)
            o[0]
        } else o[0]
        sinA += 0.1f * (sin(az) - sinA)
        cosA += 0.1f * (cos(az) - cosA)
        compassHeading = ((Math.toDegrees(atan2(sinA, cosA).toDouble()) + 360) % 360).toFloat()
        updateHeading()
    }

    private fun updateHeading() {
        heading = gpsBearing ?: compassHeading
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ---- Límite de velocidad desde OpenStreetMap (Overpass) ----
    private fun maybeQueryLimit(loc: Location) {
        if (querying) return
        val now = System.currentTimeMillis()
        if (lastQueryTime != 0L) {
            val moved = FloatArray(1)
            Location.distanceBetween(lastQueryLat, lastQueryLon, loc.latitude, loc.longitude, moved)
            if (now - lastQueryTime < 5000 || moved[0] < 30f) return
        }
        querying = true
        lastQueryTime = now
        lastQueryLat = loc.latitude
        lastQueryLon = loc.longitude
        val lat = loc.latitude
        val lon = loc.longitude
        thread {
            val result: Pair<Boolean, Int?> = try {
                fetchLimit(lat, lon)
            } catch (e: Exception) {
                false to null
            }
            runOnUiThread {
                if (result.first) osmLimit = result.second
                querying = false
            }
        }
    }

    private fun fetchLimit(lat: Double, lon: Double): Pair<Boolean, Int?> {
        val q = "[out:json][timeout:6];way(around:25,$lat,$lon)" +
            "[\"highway\"~\"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|" +
            "living_street|service|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link)\$\"];" +
            "out tags geom;"
        val url = URL("https://overpass-api.de/api/interpreter?data=" + URLEncoder.encode(q, "UTF-8"))
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "GpsBrujulaApp/1.0")
        try {
            if (conn.responseCode != 200) return false to null
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val elements = JSONObject(text).getJSONArray("elements")
            var best: JSONObject? = null
            var bestDist = Double.MAX_VALUE
            for (i in 0 until elements.length()) {
                val el = elements.getJSONObject(i)
                val geom = el.optJSONArray("geometry") ?: continue
                val d = distanceToWay(lat, lon, geom)
                if (d < bestDist) {
                    bestDist = d
                    best = el
                }
            }
            val tag = best?.optJSONObject("tags")?.optString("maxspeed") ?: ""
            return true to parseMaxspeed(tag)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseMaxspeed(s: String): Int? {
        val t = s.trim().lowercase()
        if (t.isEmpty()) return null
        Regex("^(\\d+)\\s*mph$").find(t)?.let {
            return (it.groupValues[1].toInt() * 1.609).roundToInt()
        }
        t.toIntOrNull()?.let { return it }
        return when (t) {
            "es:urban" -> 50
            "es:motorway" -> 120
            else -> null
        }
    }

    private fun distanceToWay(lat: Double, lon: Double, geom: JSONArray): Double {
        val kx = 111320.0 * cos(Math.toRadians(lat))
        val ky = 110540.0
        var best = Double.MAX_VALUE
        var px = 0.0
        var py = 0.0
        for (i in 0 until geom.length()) {
            val p = geom.getJSONObject(i)
            val x = (p.getDouble("lon") - lon) * kx
            val y = (p.getDouble("lat") - lat) * ky
            if (i > 0) best = min(best, distOriginToSegment(px, py, x, y))
            px = x
            py = y
        }
        return best
    }

    private fun distOriginToSegment(ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        return hypot(ax + t * dx, ay + t * dy)
    }
}

@Composable
fun Dashboard(
    speedKmh: Float,
    heading: Float,
    hasFix: Boolean,
    limit: Int?,
    isManual: Boolean,
    onDelta: (Int) -> Unit,
    onAuto: () -> Unit
) {
    val cardinals = listOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")
    val cardinal = cardinals[((heading + 22.5f) / 45f).toInt() % 8]
    val v = speedKmh.roundToInt()

    // Verde: dentro del límite · Naranja: hasta +8% · Rojo: más de +8%
    val bg = when {
        !hasFix || limit == null -> Color(0xFF1C1C1C)
        v <= limit -> Color(0xFF1B8E3E)
        v <= limit * 1.08f -> Color(0xFFF57C00)
        else -> Color(0xFFD32F2F)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding()
    ) {
        // 1/4: brújula
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            Compass(
                heading = heading,
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(1f)
                    .padding(4.dp)
            )
        }

        // 2/4: velocidad con fondo según límite
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(2f)
                .background(bg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = if (hasFix) v.toString() else "--",
                color = Color.White,
                fontSize = 150.sp,
                fontWeight = FontWeight.Bold
            )
            Text("km/h", color = Color.White, fontSize = 26.sp)
            if (!hasFix) Text("Buscando GPS…", color = Color(0xFFFFA000), fontSize = 16.sp)
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LimitButton("-") { onDelta(-10) }
                Text(
                    text = "LÍMITE " + (limit?.toString() ?: "--") + if (isManual) " (manual)" else "",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                LimitButton("+") { onDelta(10) }
                if (isManual) LimitButton("AUTO") { onAuto() }
            }
        }

        // 1/4: rumbo grande
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "%03.0f° %s".format(heading, cardinal),
                color = Color.White,
                fontSize = 76.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun LimitButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = Color(0x66000000)),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
    ) {
        Text(label, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun Compass(heading: Float, modifier: Modifier = Modifier) {
    val paint = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
            isFakeBoldText = true
        }
    }
    Canvas(modifier) {
        val r = size.minDimension / 2f * 0.86f
        val c = center
        paint.textSize = r * 0.32f

        // Rosa de los vientos: gira al revés que el rumbo
        rotate(-heading, c) {
            drawCircle(Color.White, r, c, style = Stroke(r * 0.025f))
            for (i in 0 until 360 step 10) {
                val len = when {
                    i % 90 == 0 -> r * 0.16f
                    i % 30 == 0 -> r * 0.11f
                    else -> r * 0.06f
                }
                rotate(i.toFloat(), c) {
                    drawLine(
                        Color.White,
                        Offset(c.x, c.y - r),
                        Offset(c.x, c.y - r + len),
                        r * 0.015f
                    )
                }
            }
            listOf("N" to 0f, "E" to 90f, "S" to 180f, "O" to 270f).forEach { (label, deg) ->
                rotate(deg, c) {
                    paint.color =
                        if (label == "N") android.graphics.Color.RED else android.graphics.Color.WHITE
                    drawContext.canvas.nativeCanvas.drawText(
                        label, c.x, c.y - r + r * 0.2f + paint.textSize * 0.8f, paint
                    )
                }
            }
        }

        // Marca fija de rumbo (arriba, apuntando al círculo)
        val tri = Path().apply {
            moveTo(c.x, c.y - r - 2f)
            lineTo(c.x - r * 0.08f, c.y - r - r * 0.14f)
            lineTo(c.x + r * 0.08f, c.y - r - r * 0.14f)
            close()
        }
        drawPath(tri, Color(0xFFFFA000))
    }
}
