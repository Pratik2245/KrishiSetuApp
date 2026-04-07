package com.example.krishisetuapp

import android.util.Log
import org.json.JSONObject
import java.net.URL

data class WeatherSnapshot(
    val shortSummary: String,
    val temperatureC: Float,
    val humidityPercent: Float,
    val totalRainMmNext48h: Float,
    val rainExpectedSoon: Boolean,
    val peakWindSpeed: Float
)

class WeatherApiHelper {

    fun fetchForecastSummary(
        latitude: Double,
        longitude: Double,
        apiKey: String
    ): WeatherSnapshot? {
        return try {
            if (apiKey.isBlank()) {
                Log.e("WeatherAPI", "API key is empty")
                return null
            }

            val url = buildString {
                append("https://api.openweathermap.org/data/2.5/forecast")
                append("?lat=$latitude&lon=$longitude&appid=$apiKey&units=metric")
            }

            val response = URL(url).readText()
            val jsonObject = JSONObject(response)
            val forecastList = jsonObject.getJSONArray("list")
            val count = minOf(16, forecastList.length())
            if (count == 0) return null

            var tempSum = 0f
            var humiditySum = 0f
            var rainSum = 0f
            var peakWind = 0f
            var rainySlots = 0
            val summaries = linkedMapOf<String, Int>()

            for (i in 0 until count) {
                val item = forecastList.getJSONObject(i)
                val main = item.getJSONObject("main")
                val weatherMain = item.getJSONArray("weather")
                    .getJSONObject(0)
                    .optString("main", "Clear")
                val rainMm = item.optJSONObject("rain")?.optDouble("3h", 0.0)?.toFloat() ?: 0f
                val windSpeed = item.optJSONObject("wind")?.optDouble("speed", 0.0)?.toFloat() ?: 0f

                tempSum += main.optDouble("temp", 0.0).toFloat()
                humiditySum += main.optDouble("humidity", 0.0).toFloat()
                rainSum += rainMm
                peakWind = maxOf(peakWind, windSpeed)
                if (rainMm > 0.2f || weatherMain.equals("Rain", true) || weatherMain.equals("Drizzle", true) || weatherMain.equals("Thunderstorm", true)) {
                    rainySlots += 1
                }
                summaries[weatherMain] = (summaries[weatherMain] ?: 0) + 1
            }

            val summary = summaries.maxByOrNull { it.value }?.key ?: "Clear"
            WeatherSnapshot(
                shortSummary = summary,
                temperatureC = tempSum / count,
                humidityPercent = humiditySum / count,
                totalRainMmNext48h = rainSum,
                rainExpectedSoon = rainySlots >= 2,
                peakWindSpeed = peakWind
            )
        } catch (e: Exception) {
            Log.e("WeatherAPI", "Forecast fetch failed: ${e.message}", e)
            null
        }
    }

    fun willRainInNext48Hours(
        latitude: Double,
        longitude: Double,
        apiKey: String
    ): String {
        val summary = fetchForecastSummary(latitude, longitude, apiKey) ?: return "ERROR"
        return when {
            summary.rainExpectedSoon -> "RAIN"
            summary.shortSummary.equals("Clouds", true) -> "CLOUDY"
            else -> "CLEAR"
        }
    }
}
