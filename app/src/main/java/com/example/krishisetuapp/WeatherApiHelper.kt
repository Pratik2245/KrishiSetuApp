package com.example.krishisetuapp

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.math.roundToInt

data class WeatherSnapshot(
    val shortSummary: String,
    val conditionLabel: String,
    val conditionDescription: String,
    val weatherCode: Int,
    val temperatureC: Float,
    val humidityPercent: Float,
    val totalRainMmNext48h: Float,
    val maxPrecipitationProbability: Int,
    val rainExpectedSoon: Boolean,
    val peakWindSpeedKmh: Float,
    val minTempCNext24h: Float,
    val maxTempCNext24h: Float,
    val riskLevel: String
)

data class WeatherFarmGuidance(
    val headline: String,
    val fieldWindow: String,
    val summary: String,
    val suitedCrops: List<String>,
    val cautionCrops: List<String>,
    val fieldActions: List<String>
)

data class WeatherFetchResult(
    val snapshot: WeatherSnapshot?,
    val issueMessage: String? = null,
    val fromCache: Boolean = false
)

class WeatherApiHelper {

    suspend fun fetchForecastSummary(
        context: Context,
        latitude: Double,
        longitude: Double,
        openWeatherApiKey: String = ""
    ): WeatherFetchResult = withContext(Dispatchers.IO) {
        val requests = buildProviderRequests(latitude, longitude, openWeatherApiKey)

        for ((_, url, parser) in requests) {
            val response = runCatching { fetchUrl(url, null) }.getOrNull()
            val snapshot = response?.let(parser)
            if (snapshot != null) {
                cacheSnapshot(context, snapshot)
                return@withContext WeatherFetchResult(snapshot = snapshot)
            }
        }

        for ((_, url, parser) in requests) {
            val response = requestCellularFetch(context, url)
            val snapshot = response?.let(parser)
            if (snapshot != null) {
                cacheSnapshot(context, snapshot)
                return@withContext WeatherFetchResult(snapshot = snapshot)
            }
        }

        val cached = readCachedSnapshot(context)
        if (cached != null) {
            return@withContext WeatherFetchResult(
                snapshot = cached,
                issueMessage = "Live weather could not be refreshed. Showing the last saved forecast. Keep mobile data on while connected to KrishiSetu Wi-Fi.",
                fromCache = true
            )
        }

        WeatherFetchResult(
            snapshot = null,
            issueMessage = "Live weather could not be fetched from OpenWeather or Open-Meteo. Keep mobile data on and verify internet access, then refresh again."
        )
    }

    fun buildFarmGuidance(snapshot: WeatherSnapshot): WeatherFarmGuidance {
        return when {
            isStormCode(snapshot.weatherCode) || snapshot.peakWindSpeedKmh >= 45f -> WeatherFarmGuidance(
                headline = "Storm alert for field operations",
                fieldWindow = "Unsafe spray and fertilizer window",
                summary = "Thunderstorm or very strong wind risk is high. Hold spraying, nitrogen top-dressing, and transplanting until the weather settles.",
                suitedCrops = listOf("Rice", "Sugarcane"),
                cautionCrops = listOf("Tomato", "Potato", "Wheat"),
                fieldActions = listOf(
                    "Delay pesticide spraying and foliar nutrients.",
                    "Avoid broadcasting urea before the storm passes.",
                    "Check drainage channels and support young plants."
                )
            )

            snapshot.totalRainMmNext48h >= 12f || snapshot.maxPrecipitationProbability >= 70 -> WeatherFarmGuidance(
                headline = "Wet weather window",
                fieldWindow = "Good for rain-loving crops",
                summary = "Rainfall support is strong over the next 48 hours. Water-loving crops fit better, while disease-sensitive crops need caution.",
                suitedCrops = listOf("Rice", "Sugarcane"),
                cautionCrops = listOf("Potato", "Tomato", "Wheat"),
                fieldActions = listOf(
                    "Prefer split fertilizer application instead of a heavy nitrogen dose.",
                    "Use drainage and avoid waterlogging in vegetable plots.",
                    "Rice nursery or transplant planning is favorable in this window."
                )
            )

            snapshot.temperatureC <= 24f && snapshot.totalRainMmNext48h < 5f && snapshot.maxPrecipitationProbability < 40 -> WeatherFarmGuidance(
                headline = "Cool stable window",
                fieldWindow = "Favorable for cool-season crops",
                summary = "Temperatures are relatively mild with limited rainfall risk, which suits cool-season crop activity better.",
                suitedCrops = listOf("Wheat", "Potato"),
                cautionCrops = listOf("Rice", "Sugarcane"),
                fieldActions = listOf(
                    "Sowing or management for wheat and potato is better aligned with this weather.",
                    "Maintain moderate irrigation rather than frequent watering.",
                    "Continue balanced fertilizer use if the field stays workable."
                )
            )

            snapshot.temperatureC >= 32f && snapshot.humidityPercent < 55f && snapshot.totalRainMmNext48h < 3f -> WeatherFarmGuidance(
                headline = "Hot dry window",
                fieldWindow = "Irrigation-sensitive period",
                summary = "Heat stress and low rain support are likely. Crops that tolerate warmer conditions perform better if irrigation is available.",
                suitedCrops = listOf("Maize", "Sugarcane"),
                cautionCrops = listOf("Potato", "Tomato"),
                fieldActions = listOf(
                    "Irrigate in the morning or evening and reduce midday moisture loss.",
                    "Use mulching where possible to protect root-zone moisture.",
                    "Avoid fertilizer application during the hottest part of the day."
                )
            )

            snapshot.humidityPercent >= 75f -> WeatherFarmGuidance(
                headline = "Humid cloudy window",
                fieldWindow = "Moderate growth with disease watch",
                summary = "Humidity is elevated even if rainfall is not severe. Moisture-loving crops fit better, but fungal pressure can rise.",
                suitedCrops = listOf("Rice", "Sugarcane", "Maize"),
                cautionCrops = listOf("Tomato", "Potato"),
                fieldActions = listOf(
                    "Inspect leaves for early fungal symptoms and improve field airflow.",
                    "Avoid over-irrigation while humidity remains high.",
                    "Use lighter fertilizer splits instead of a large single dose."
                )
            )

            else -> WeatherFarmGuidance(
                headline = "Warm stable window",
                fieldWindow = "Good field-working conditions",
                summary = "Weather is relatively stable for regular field activity, so sowing and maintenance work are easier to schedule.",
                suitedCrops = listOf("Maize", "Tomato"),
                cautionCrops = listOf("Rice", "Potato"),
                fieldActions = listOf(
                    "Field operations and balanced NPK use are safer in this window.",
                    "Maintain normal irrigation and monitor moisture trends.",
                    "This is a good time for routine crop care and transplant management."
                )
            )
        }
    }

    suspend fun willRainInNext48Hours(
        context: Context,
        latitude: Double,
        longitude: Double,
        openWeatherApiKey: String = ""
    ): String {
        val summary = fetchForecastSummary(context, latitude, longitude, openWeatherApiKey).snapshot ?: return "ERROR"
        return when {
            summary.rainExpectedSoon -> "RAIN"
            summary.conditionLabel.contains("Cloud", ignoreCase = true) || summary.conditionLabel.contains("Fog", ignoreCase = true) -> "CLOUDY"
            else -> "CLEAR"
        }
    }

    private fun buildProviderRequests(
        latitude: Double,
        longitude: Double,
        openWeatherApiKey: String
    ): List<Triple<String, String, (String) -> WeatherSnapshot?>> {
        val requests = mutableListOf<Triple<String, String, (String) -> WeatherSnapshot?>>()

        if (openWeatherApiKey.isNotBlank()) {
            requests += Triple(
                "OpenWeather",
                buildOpenWeatherUrl(latitude, longitude, openWeatherApiKey),
                ::parseOpenWeather
            )
        }

        requests += Triple(
            "Open-Meteo",
            buildOpenMeteoUrl(latitude, longitude),
            ::parseOpenMeteo
        )

        return requests
    }

    private fun buildOpenWeatherUrl(latitude: Double, longitude: Double, apiKey: String): String {
        return "https://api.openweathermap.org/data/2.5/forecast?lat=$latitude&lon=$longitude&appid=$apiKey&units=metric"
    }

    private fun buildOpenMeteoUrl(latitude: Double, longitude: Double): String {
        return buildString {
            append("https://api.open-meteo.com/v1/forecast")
            append("?latitude=$latitude")
            append("&longitude=$longitude")
            append("&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m")
            append("&hourly=temperature_2m,relative_humidity_2m,precipitation,precipitation_probability,weather_code,wind_speed_10m")
            append("&forecast_hours=48")
            append("&timezone=auto")
        }
    }

    private fun fetchUrl(url: String, network: Network?): String {
        val connection = ((network?.openConnection(URL(url)) ?: URL(url).openConnection()) as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "KrishiSetu/1.0")
        }

        return try {
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            stream?.bufferedReader()?.use { it.readText() }
                ?: throw IllegalStateException("Empty weather response")
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun requestCellularFetch(context: Context, url: String): String? {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        return suspendCancellableCoroutine { continuation ->
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    runCatching { fetchUrl(url, network) }
                        .onSuccess { response ->
                            runCatching { connectivityManager.unregisterNetworkCallback(this) }
                            if (continuation.isActive) continuation.resume(response)
                        }
                        .onFailure { error ->
                            Log.e("WeatherAPI", "Cellular weather fetch failed: ${error.message}", error)
                            runCatching { connectivityManager.unregisterNetworkCallback(this) }
                            if (continuation.isActive) continuation.resume(null)
                        }
                }

                override fun onUnavailable() {
                    runCatching { connectivityManager.unregisterNetworkCallback(this) }
                    if (continuation.isActive) continuation.resume(null)
                }
            }

            runCatching {
                connectivityManager.requestNetwork(request, callback)
            }.onFailure {
                if (continuation.isActive) continuation.resume(null)
            }

            continuation.invokeOnCancellation {
                runCatching { connectivityManager.unregisterNetworkCallback(callback) }
            }
        }
    }

    private fun parseOpenWeather(response: String): WeatherSnapshot? {
        return try {
            val jsonObject = JSONObject(response)
            val code = jsonObject.optString("cod")
            if (code != "200") return null

            val forecastList = jsonObject.optJSONArray("list") ?: return null
            val count = minOf(16, forecastList.length())
            if (count == 0) return null

            var totalRain = 0f
            var maxRainProbability = 0
            var peakWind = 0f
            var minTemp = Float.MAX_VALUE
            var maxTemp = -Float.MAX_VALUE
            var rainySlots = 0
            val codeCounts = linkedMapOf<Int, Int>()

            var currentTemp = 0f
            var currentHumidity = 0f
            var currentCode = 0

            for (i in 0 until count) {
                val item = forecastList.getJSONObject(i)
                val main = item.getJSONObject("main")
                val weatherMain = item.getJSONArray("weather")
                    .optJSONObject(0)
                    ?.optString("main", "Clear")
                    ?: "Clear"

                val temp = main.optDouble("temp", 0.0).toFloat()
                val humidity = main.optDouble("humidity", 0.0).toFloat()
                val rain = item.optJSONObject("rain")?.optDouble("3h", 0.0)?.toFloat() ?: 0f
                val rainProbability = (item.optDouble("pop", 0.0) * 100).roundToInt()
                val windKmh = (item.optJSONObject("wind")?.optDouble("speed", 0.0)?.toFloat() ?: 0f) * 3.6f
                val weatherCode = mapOpenWeatherConditionToCode(weatherMain)

                if (i == 0) {
                    currentTemp = temp
                    currentHumidity = humidity
                    currentCode = weatherCode
                }

                totalRain += rain
                maxRainProbability = maxOf(maxRainProbability, rainProbability)
                peakWind = maxOf(peakWind, windKmh)
                minTemp = minOf(minTemp, temp)
                maxTemp = maxOf(maxTemp, temp)

                if (rain > 0.2f || rainProbability >= 55 || isRainCode(weatherCode)) {
                    rainySlots += 1
                }
                codeCounts[weatherCode] = (codeCounts[weatherCode] ?: 0) + 1
            }

            val dominantCode = codeCounts.maxByOrNull { it.value }?.key ?: currentCode
            val displayCode = if (currentCode != 0 || dominantCode == 0) currentCode else dominantCode
            val conditionLabel = mapWeatherCode(displayCode)
            val description = buildConditionDescription(
                displayCode = displayCode,
                temperatureC = currentTemp,
                humidityPercent = currentHumidity,
                rainMm = totalRain,
                rainProbability = maxRainProbability,
                windKmh = peakWind
            )

            val riskLevel = when {
                isStormCode(displayCode) || peakWind >= 45f -> "High"
                totalRain >= 15f || maxRainProbability >= 75 -> "High"
                totalRain >= 7f || maxRainProbability >= 55 || currentHumidity >= 85f -> "Moderate"
                else -> "Low"
            }

            WeatherSnapshot(
                shortSummary = conditionLabel,
                conditionLabel = conditionLabel,
                conditionDescription = description,
                weatherCode = displayCode,
                temperatureC = currentTemp,
                humidityPercent = currentHumidity,
                totalRainMmNext48h = totalRain,
                maxPrecipitationProbability = maxRainProbability,
                rainExpectedSoon = rainySlots >= 2 || totalRain >= 6f || maxRainProbability >= 60,
                peakWindSpeedKmh = peakWind,
                minTempCNext24h = if (minTemp == Float.MAX_VALUE) currentTemp else minTemp,
                maxTempCNext24h = if (maxTemp == -Float.MAX_VALUE) currentTemp else maxTemp,
                riskLevel = riskLevel
            )
        } catch (e: Exception) {
            Log.e("WeatherAPI", "OpenWeather parse failed: ${e.message}", e)
            null
        }
    }

    private fun parseOpenMeteo(response: String): WeatherSnapshot? {
        return try {
            val jsonObject = JSONObject(response)
            val current = jsonObject.getJSONObject("current")
            val hourly = jsonObject.getJSONObject("hourly")

            val temperatures = hourly.getJSONArray("temperature_2m")
            val precipitation = hourly.getJSONArray("precipitation")
            val precipitationProbability = hourly.getJSONArray("precipitation_probability")
            val weatherCodes = hourly.getJSONArray("weather_code")
            val windSpeeds = hourly.getJSONArray("wind_speed_10m")

            val count = listOf(
                temperatures.length(),
                precipitation.length(),
                precipitationProbability.length(),
                weatherCodes.length(),
                windSpeeds.length(),
                48
            ).minOrNull() ?: 0

            if (count == 0) return null

            var totalRain = 0f
            var maxRainProbability = 0
            var peakWind = current.optDouble("wind_speed_10m", 0.0).toFloat()
            var minTemp = Float.MAX_VALUE
            var maxTemp = -Float.MAX_VALUE
            var rainyHours = 0
            val codeCounts = linkedMapOf<Int, Int>()

            for (i in 0 until count) {
                val temp = temperatures.optDouble(i, current.optDouble("temperature_2m", 0.0)).toFloat()
                val rain = precipitation.optDouble(i, 0.0).toFloat()
                val rainProbability = precipitationProbability.optInt(i, 0)
                val weatherCode = weatherCodes.optInt(i, current.optInt("weather_code", 0))
                val wind = windSpeeds.optDouble(i, peakWind.toDouble()).toFloat()

                totalRain += rain
                maxRainProbability = maxOf(maxRainProbability, rainProbability)
                peakWind = maxOf(peakWind, wind)
                minTemp = minOf(minTemp, temp)
                maxTemp = maxOf(maxTemp, temp)

                if (rain > 0.2f || rainProbability >= 55 || isRainCode(weatherCode)) {
                    rainyHours += 1
                }
                codeCounts[weatherCode] = (codeCounts[weatherCode] ?: 0) + 1
            }

            val currentCode = current.optInt("weather_code", 0)
            val dominantCode = codeCounts.maxByOrNull { it.value }?.key ?: currentCode
            val displayCode = if (currentCode != 0 || dominantCode == 0) currentCode else dominantCode
            val conditionLabel = mapWeatherCode(displayCode)
            val currentTemp = current.optDouble("temperature_2m", 0.0).toFloat()
            val currentHumidity = current.optDouble("relative_humidity_2m", 0.0).toFloat()
            val description = buildConditionDescription(
                displayCode = displayCode,
                temperatureC = currentTemp,
                humidityPercent = currentHumidity,
                rainMm = totalRain,
                rainProbability = maxRainProbability,
                windKmh = peakWind
            )

            val riskLevel = when {
                isStormCode(displayCode) || peakWind >= 45f -> "High"
                totalRain >= 15f || maxRainProbability >= 75 -> "High"
                totalRain >= 7f || maxRainProbability >= 55 || currentHumidity >= 85f -> "Moderate"
                else -> "Low"
            }

            WeatherSnapshot(
                shortSummary = conditionLabel,
                conditionLabel = conditionLabel,
                conditionDescription = description,
                weatherCode = displayCode,
                temperatureC = currentTemp,
                humidityPercent = currentHumidity,
                totalRainMmNext48h = totalRain,
                maxPrecipitationProbability = maxRainProbability,
                rainExpectedSoon = rainyHours >= 4 || totalRain >= 6f || maxRainProbability >= 60,
                peakWindSpeedKmh = peakWind,
                minTempCNext24h = if (minTemp == Float.MAX_VALUE) currentTemp else minTemp,
                maxTempCNext24h = if (maxTemp == -Float.MAX_VALUE) currentTemp else maxTemp,
                riskLevel = riskLevel
            )
        } catch (e: Exception) {
            Log.e("WeatherAPI", "Open-Meteo parse failed: ${e.message}", e)
            null
        }
    }

    private fun cacheSnapshot(context: Context, snapshot: WeatherSnapshot) {
        val payload = JSONObject().apply {
            put("shortSummary", snapshot.shortSummary)
            put("conditionLabel", snapshot.conditionLabel)
            put("conditionDescription", snapshot.conditionDescription)
            put("weatherCode", snapshot.weatherCode)
            put("temperatureC", snapshot.temperatureC.toDouble())
            put("humidityPercent", snapshot.humidityPercent.toDouble())
            put("totalRainMmNext48h", snapshot.totalRainMmNext48h.toDouble())
            put("maxPrecipitationProbability", snapshot.maxPrecipitationProbability)
            put("rainExpectedSoon", snapshot.rainExpectedSoon)
            put("peakWindSpeedKmh", snapshot.peakWindSpeedKmh.toDouble())
            put("minTempCNext24h", snapshot.minTempCNext24h.toDouble())
            put("maxTempCNext24h", snapshot.maxTempCNext24h.toDouble())
            put("riskLevel", snapshot.riskLevel)
        }

        context.getSharedPreferences("weather_cache", Context.MODE_PRIVATE)
            .edit()
            .putString("latest_weather", payload.toString())
            .apply()
    }

    private fun readCachedSnapshot(context: Context): WeatherSnapshot? {
        return try {
            val raw = context.getSharedPreferences("weather_cache", Context.MODE_PRIVATE)
                .getString("latest_weather", null) ?: return null
            val json = JSONObject(raw)
            WeatherSnapshot(
                shortSummary = json.optString("shortSummary"),
                conditionLabel = json.optString("conditionLabel"),
                conditionDescription = json.optString("conditionDescription"),
                weatherCode = json.optInt("weatherCode"),
                temperatureC = json.optDouble("temperatureC", 0.0).toFloat(),
                humidityPercent = json.optDouble("humidityPercent", 0.0).toFloat(),
                totalRainMmNext48h = json.optDouble("totalRainMmNext48h", 0.0).toFloat(),
                maxPrecipitationProbability = json.optInt("maxPrecipitationProbability"),
                rainExpectedSoon = json.optBoolean("rainExpectedSoon"),
                peakWindSpeedKmh = json.optDouble("peakWindSpeedKmh", 0.0).toFloat(),
                minTempCNext24h = json.optDouble("minTempCNext24h", 0.0).toFloat(),
                maxTempCNext24h = json.optDouble("maxTempCNext24h", 0.0).toFloat(),
                riskLevel = json.optString("riskLevel", "Unknown")
            )
        } catch (e: Exception) {
            Log.e("WeatherAPI", "Cached weather parse failed: ${e.message}", e)
            null
        }
    }

    private fun mapOpenWeatherConditionToCode(condition: String): Int {
        return when (condition.lowercase()) {
            "clear" -> 0
            "clouds" -> 3
            "mist", "fog", "haze", "smoke" -> 45
            "drizzle" -> 53
            "rain" -> 63
            "snow" -> 73
            "thunderstorm" -> 95
            else -> 1
        }
    }

    private fun mapWeatherCode(code: Int): String {
        return when (code) {
            0 -> "Clear"
            1, 2 -> "Mostly Clear"
            3 -> "Cloudy"
            45, 48 -> "Foggy"
            51, 53, 55, 56, 57 -> "Drizzle"
            61, 63, 65, 66, 67, 80, 81, 82 -> "Rain"
            71, 73, 75, 77, 85, 86 -> "Snow"
            95, 96, 99 -> "Thunderstorm"
            else -> "Mixed Weather"
        }
    }

    private fun isRainCode(code: Int): Boolean {
        return code in listOf(51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82, 95, 96, 99)
    }

    private fun isStormCode(code: Int): Boolean {
        return code in listOf(95, 96, 99)
    }

    private fun buildConditionDescription(
        displayCode: Int,
        temperatureC: Float,
        humidityPercent: Float,
        rainMm: Float,
        rainProbability: Int,
        windKmh: Float
    ): String {
        val base = when (displayCode) {
            0, 1, 2 -> "Skies are relatively open."
            3 -> "Cloud cover is persistent."
            45, 48 -> "Visibility may stay low due to fog."
            51, 53, 55, 56, 57 -> "Light to moderate drizzle is likely."
            61, 63, 65, 66, 67, 80, 81, 82 -> "Rainfall is likely over the next two days."
            71, 73, 75, 77, 85, 86 -> "Cold-weather precipitation is showing up in the forecast."
            95, 96, 99 -> "Convective storm risk is present."
            else -> "The forecast is mixed."
        }

        return "$base Around ${temperatureC.roundToInt()}°C with humidity near ${humidityPercent.roundToInt()}%, rain chance up to $rainProbability%, and wind near ${windKmh.roundToInt()} km/h. Forecast rain total is ${rainMm.roundToInt()} mm for the next 48 hours."
    }
}
