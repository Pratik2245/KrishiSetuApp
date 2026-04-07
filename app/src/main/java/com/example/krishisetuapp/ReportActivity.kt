package com.example.krishisetuapp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private data class LatestSoilRecord(
    val predictedN: Float,
    val predictedP: Float,
    val predictedK: Float,
    val phValue: Float,
    val soilType: String,
    val moisture: Float,
    val temperature: Float,
    val cropsList: List<String>
)

class ReportActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                ReportScreen()
            }
        }
    }
}

@Composable
fun ReportScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }
    val weatherHelper = remember { WeatherApiHelper() }

    var isLoading by remember { mutableStateOf(false) }
    var weatherSummary by remember { mutableStateOf<WeatherSnapshot?>(null) }
    var weatherGuidance by remember { mutableStateOf<WeatherFarmGuidance?>(null) }
    var soilRecord by remember { mutableStateOf<LatestSoilRecord?>(null) }
    var recommendationBundle by remember { mutableStateOf<CropRecommendationBundle?>(null) }
    var statusMessage by remember { mutableStateOf("Fetch the latest local weather and field guidance before generating the report.") }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(context, "Location permission is needed for weather guidance", Toast.LENGTH_LONG).show()
        }
    }

    fun loadInsights() {
        val permissionGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!permissionGranted) {
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }

        fetchLocation(context, fusedLocationClient) { lat, lng ->
            scope.launch {
                isLoading = true
                statusMessage = "Collecting forecast and latest soil analysis..."
                val latestSoilRecord = fetchLatestSoilRecord()
                val weatherResult = withContext(Dispatchers.IO) {
                    weatherHelper.fetchForecastSummary(
                        context = context,
                        latitude = lat,
                        longitude = lng,
                        openWeatherApiKey = BuildConfig.OPEN_WEATHER_API_KEY
                    )
                }
                val weather = weatherResult.snapshot
                val guidance = weather?.let { weatherHelper.buildFarmGuidance(it) }

                soilRecord = latestSoilRecord
                weatherSummary = weather
                weatherGuidance = guidance
                recommendationBundle = CropRecommendationEngine.build(
                    predictedCrops = when {
                        !latestSoilRecord?.cropsList.isNullOrEmpty() -> latestSoilRecord?.cropsList?.mapIndexed { index, crop ->
                            crop to (1f - index * 0.12f).coerceAtLeast(0.5f)
                        }
                        guidance != null -> guidance.suitedCrops.mapIndexed { index, crop ->
                            crop to (0.92f - index * 0.12f).coerceAtLeast(0.55f)
                        }
                        else -> null
                    },
                    ph = latestSoilRecord?.phValue,
                    temperature = latestSoilRecord?.temperature,
                    moisture = latestSoilRecord?.moisture,
                    weather = weather
                )
                statusMessage = when {
                    weatherResult.issueMessage != null -> weatherResult.issueMessage
                    weather == null -> "Weather could not be loaded right now."
                    latestSoilRecord == null -> "Weather loaded, but no saved soil record was found."
                    else -> "Guidance updated using the latest field record and 48-hour forecast."
                }
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        loadInsights()
    }

    fun generatePdf() {
        val record = soilRecord
        if (record == null) {
            Toast.makeText(context, "Load soil data first so the report uses current inputs", Toast.LENGTH_LONG).show()
            return
        }

        scope.launch {
            isLoading = true
            try {
                val currentUser = FirebaseAuth.getInstance().currentUser
                if (currentUser == null) {
                    Toast.makeText(context, "User not logged in", Toast.LENGTH_LONG).show()
                    isLoading = false
                    return@launch
                }

                val userDoc = FirebaseFirestore.getInstance()
                    .collection("users")
                    .document(currentUser.uid)
                    .get()
                    .await()

                if (!userDoc.exists()) {
                    Toast.makeText(context, "User document not found", Toast.LENGTH_LONG).show()
                    isLoading = false
                    return@launch
                }

                fetchLocation(context, fusedLocationClient) { lat, lng ->
                    scope.launch {
                        val reportData = ReportData(
                            name = userDoc.getString("name") ?: "Unknown",
                            village = userDoc.getString("village") ?: "Unknown",
                            latitude = lat,
                            longitude = lng,
                            samples = List(5) { index ->
                                SampleData(
                                    sampleNo = index + 1,
                                    n = record.predictedN,
                                    p = record.predictedP,
                                    k = record.predictedK,
                                    ph = record.phValue,
                                    soilType = record.soilType,
                                    moisture = record.moisture,
                                    temperature = record.temperature,
                                    crops = record.cropsList.joinToString(", ")
                                )
                            },
                            avgN = record.predictedN,
                            avgP = record.predictedP,
                            avgK = record.predictedK,
                            avgPh = record.phValue,
                            avgMoisture = record.moisture,
                            avgTemp = record.temperature,
                            predictedCrops = record.cropsList
                        )

                        val success = PdfReportGenerator(context).generateReport(reportData)
                        Toast.makeText(
                            context,
                            if (success) "PDF generated successfully" else "PDF generation failed",
                            Toast.LENGTH_LONG
                        ).show()
                        isLoading = false
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(context, e.localizedMessage ?: "Failed to generate report", Toast.LENGTH_LONG).show()
                isLoading = false
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFFF5F1E8), Color(0xFFE7F4EA), Color(0xFFD8EAD9))
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF16361F))
                ) {
                    Column(modifier = Modifier.padding(22.dp)) {
                        Text("Field Report Center", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Weather, fertilizer timing, crop fit, and PDF export in one place.",
                            color = Color(0xFFDCECD7),
                            fontSize = 15.sp
                        )
                        Spacer(Modifier.height(18.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = { loadInsights() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8BCF7A))
                            ) {
                                Text("Refresh Guidance", color = Color(0xFF16361F))
                            }
                            Button(
                                onClick = { generatePdf() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE2C98F))
                            ) {
                                Text("Generate PDF", color = Color(0xFF3B2B12))
                            }
                        }
                    }
                }

                if (isLoading) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.72f))
                    ) {
                        Row(
                            modifier = Modifier.padding(18.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.height(26.dp).width(26.dp), strokeWidth = 3.dp)
                            Spacer(Modifier.width(14.dp))
                            Text(statusMessage, color = Color(0xFF28432F))
                        }
                    }
                } else {
                    Text(statusMessage, color = Color(0xFF35513C), fontSize = 14.sp)
                }

                WeatherSummaryCard(weatherSummary)
                WeatherGuidanceCard(weatherGuidance)
                SoilOverviewCard(soilRecord)
                RecommendationSummaryCard(recommendationBundle)
            }
        }
    }
}

@Composable
private fun WeatherSummaryCard(weather: WeatherSnapshot?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.82f))
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("48-Hour Weather Outlook", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF183B24))
            if (weather == null) {
                Text("No weather snapshot loaded yet.", color = Color(0xFF5D6F62))
            } else {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    WeatherMetric("Condition", weather.conditionLabel)
                    WeatherMetric("Temp", "${weather.temperatureC.roundToInt()}°C")
                    WeatherMetric("Humidity", "${weather.humidityPercent.roundToInt()}%")
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    WeatherMetric("Rain", "${weather.totalRainMmNext48h.roundToInt()} mm")
                    WeatherMetric("Rain Chance", "${weather.maxPrecipitationProbability}%")
                    WeatherMetric("Wind", "${weather.peakWindSpeedKmh.roundToInt()} km/h")
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    WeatherMetric("24h Range", "${weather.minTempCNext24h.roundToInt()}-${weather.maxTempCNext24h.roundToInt()}°C")
                    WeatherMetric("Risk", weather.riskLevel)
                    WeatherMetric("Window", if (weather.rainExpectedSoon) "Wet" else "Stable")
                }
                Text(weather.conditionDescription, color = Color(0xFF506357), fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun WeatherGuidanceCard(guidance: WeatherFarmGuidance?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF23432A))
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Weather-Based Crop Guidance", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            if (guidance == null) {
                Text("Refresh the weather to generate field-specific crop guidance.", color = Color(0xFFD7E7D9))
            } else {
                Text(guidance.headline, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                Text("${guidance.fieldWindow} • ${guidance.summary}", color = Color(0xFFD7E7D9), fontSize = 13.sp)
                GuidanceLine("Best fit crops", guidance.suitedCrops.joinToString(", "))
                GuidanceLine("Use caution with", guidance.cautionCrops.joinToString(", "))
                guidance.fieldActions.forEachIndexed { index, item ->
                    Text("${index + 1}. $item", color = Color(0xFFF3F7F1), fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun GuidanceLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = Color(0xFFB6CBB8), fontSize = 12.sp)
        Text(value, color = Color.White, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun WeatherMetric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(label, color = Color(0xFF6A7C70), fontSize = 12.sp)
        Text(value, color = Color(0xFF183B24), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

@Composable
private fun SoilOverviewCard(record: LatestSoilRecord?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFEEF6ED))
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Latest Soil Snapshot", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF16361F))
            if (record == null) {
                Text("No soil record found yet.", color = Color(0xFF5C6D60))
            } else {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    WeatherMetric("pH", String.format("%.1f", record.phValue))
                    WeatherMetric("Moisture", "${record.moisture.roundToInt()}%")
                    WeatherMetric("Temp", "${record.temperature.roundToInt()}°C")
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    WeatherMetric("N", String.format("%.1f", record.predictedN))
                    WeatherMetric("P", String.format("%.1f", record.predictedP))
                    WeatherMetric("K", String.format("%.1f", record.predictedK))
                }
                Text("Soil type: ${record.soilType}", color = Color(0xFF36523D))
            }
        }
    }
}

@Composable
private fun RecommendationSummaryCard(bundle: CropRecommendationBundle?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF16361F))
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Crop and Fertilizer Guidance", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            if (bundle == null) {
                Text("Refresh guidance to generate crop recommendations.", color = Color(0xFFD7E7D9))
            } else {
                Text(bundle.headline, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                Text(bundle.weatherSummary, color = Color(0xFFD7E7D9))
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.12f))
                ) {
                    Text(
                        bundle.fertilizerAdvice,
                        modifier = Modifier.padding(14.dp),
                        color = Color(0xFFF5F2E8)
                    )
                }
                bundle.recommendations.take(3).forEach { item ->
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.1f))
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${item.cropName} • ${item.score}%", color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text("${item.summary} • ML confidence ${item.confidence}%", color = Color(0xFFD7E7D9), fontSize = 13.sp)
                            Text(item.reasons.joinToString(" "), color = Color(0xFFF3F7F1), fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

private suspend fun fetchLatestSoilRecord(): LatestSoilRecord? {
    return try {
        val soilSnapshot = FirebaseFirestore.getInstance()
            .collection("soil_records")
            .orderBy("temperature")
            .limitToLast(1)
            .get()
            .await()

        if (soilSnapshot.isEmpty) return null

        val soilDoc = soilSnapshot.documents[0]
        LatestSoilRecord(
            predictedN = soilDoc.getDouble("predicted_N")?.toFloat() ?: 0f,
            predictedP = soilDoc.getDouble("predicted_P")?.toFloat() ?: 0f,
            predictedK = soilDoc.getDouble("predicted_K")?.toFloat() ?: 0f,
            phValue = soilDoc.getDouble("ph")?.toFloat() ?: 0f,
            soilType = soilDoc.getString("soil_type") ?: "Unknown",
            moisture = soilDoc.getDouble("moisture")?.toFloat() ?: 0f,
            temperature = soilDoc.getDouble("temperature")?.toFloat() ?: 0f,
            cropsList = (soilDoc.get("recommended_crops") as? List<*>)?.map { it.toString() } ?: emptyList()
        )
    } catch (_: Exception) {
        null
    }
}

@Suppress("MissingPermission")
private fun fetchLocation(
    context: Context,
    fusedLocationClient: FusedLocationProviderClient,
    onLocationReady: (Double, Double) -> Unit
) {
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
        context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        return
    }

    fusedLocationClient.lastLocation
        .addOnSuccessListener { location ->
            if (location != null) {
                onLocationReady(location.latitude, location.longitude)
            } else {
                Toast.makeText(context, "Location unavailable", Toast.LENGTH_LONG).show()
            }
        }
}
