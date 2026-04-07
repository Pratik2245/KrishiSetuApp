package com.example.krishisetuapp          //final code working for app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import coil.compose.rememberAsyncImagePainter
import com.yalantis.ucrop.UCrop
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
import org.tensorflow.lite.Interpreter
import android.content.res.AssetManager
import java.nio.channels.FileChannel
import java.io.FileInputStream
import java.io.IOException
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import com.example.krishisetuapp.db.NpkHistoryActivity
import org.json.JSONObject
import kotlin.jvm.java

data class SoilSample(
    val location: String,
    var n: Float,
    var p: Float,
    var k: Float
)
/* ---------- MAIN ACTIVITY ---------- */
@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    private var onCroppedImageReady: ((Uri?) -> Unit)? = null
    private var onSoilImageReady: ((Uri?) -> Unit)? = null

    // UCrop result launcher (for pH paper cropping)
    private val cropLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            when (result.resultCode) {
                Activity.RESULT_OK -> {
                    val out = UCrop.getOutput(result.data!!)
                    onCroppedImageReady?.invoke(out)
                }
                UCrop.RESULT_ERROR -> {
                    val err = UCrop.getError(result.data!!)
                    Log.e("UCrop", "Crop error: ${err?.message}")
                    onCroppedImageReady?.invoke(null)
                }
                else -> onCroppedImageReady?.invoke(null)
            }
        }

    // Gallery pick launcher (used for pH paper -> crop)
    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let {
                val dest = Uri.fromFile(File(cacheDir, "cropped_${System.currentTimeMillis()}.jpg"))
                val cropIntent = UCrop.of(it, dest)
                    .withAspectRatio(1f, 1f)
                    .withMaxResultSize(1200, 1200)
                    .withOptions(UCrop.Options().apply {
                        setToolbarTitle("Crop Image")
                        setToolbarColor(Color(0xFF1B5E20).hashCode())
                        setStatusBarColor(Color(0xFF1B5E20).hashCode())
                        setActiveControlsWidgetColor(Color(0xFF43A047).hashCode())
                    })
                    .getIntent(this)
                cropLauncher.launch(cropIntent)
            }
        }

    // Gallery pick launcher for soil image (NO UCrop) -- returns URI directly
    private val pickSoilImageLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            onSoilImageReady?.invoke(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // pass both pickers and callbacks into composable app
            KrishiSetuApp(
                onPickImage = { pickImageLauncher.launch("image/*") },
                onCropResult = { cb -> onCroppedImageReady = cb },
                onPickSoilImage = { pickSoilImageLauncher.launch("image/*") },
                onSoilResult = { cb -> onSoilImageReady = cb }
            )
        }
    }
}

/* ---------- Shared HTTP client ---------- */
object AppHttp {
    val client = HttpClient(CIO)
}

/* ---------- TensorFlow Lite helpers & model utils ---------- */
/* The code below loads TFLite models from assets and runs them.
   IMPORTANT: The mapping from soil-type name -> integer must match the one you used while training.
   If you exported label files from Colab, put soil_cols.json and crop_labels.json into assets and
   the code will try to read them. Otherwise defaults are used (edit DEFAULT_SOIL_ORDER and DEFAULT_CROP_LABELS). */

private const val TAG = "ML"

// defaults (edit if your training label order differs)
private val DEFAULT_SOIL_ORDER = listOf("Sandy", "Silt", "Peaty", "Clay", "Saline", "Loamy")
private val DEFAULT_CROP_LABELS = listOf("rice", "maize", "chickpea", "kidneybeans", "pigeonpeas", "mothbeans", "mungbean", "blackgram", "lentil", "pomegranate", "banana", "mango") // fallback (short list)

/** Load a TFLite model from assets as ByteBuffer */
@Throws(IOException::class)
private fun loadModelFile(assetManager: AssetManager, modelPath: String): ByteBuffer {
    val fd = assetManager.openFd(modelPath)
    val inputStream = FileInputStream(fd.fileDescriptor)
    val fc = inputStream.channel
    val startOffset = fd.startOffset
    val declaredLength = fd.declaredLength
    val mbb = fc.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    mbb.order(ByteOrder.nativeOrder())
    return mbb
}

/** Read JSON array of strings from assets; returns null if missing */
private fun readStringListFromAssets(assetManager: AssetManager, filename: String): List<String>? {
    return try {
        val s = assetManager.open(filename).bufferedReader().use { it.readText() }
        val json = kotlinx.serialization.json.Json.parseToJsonElement(s)
        if (json is kotlinx.serialization.json.JsonArray) {
            json.mapNotNull { it.jsonPrimitive.contentOrNull }
        } else null
    } catch (e: Exception) {
        Log.w(TAG, "Could not read $filename from assets: ${e.message}")
        null
    }
}

/** Read mapping object name->int from assets (if you exported a JSON mapping) */
private fun readMappingFromAssets(assetManager: AssetManager, filename: String): Map<String, Int>? {
    return try {
        val s = assetManager.open(filename).bufferedReader().use { it.readText() }
        val jobj = kotlinx.serialization.json.Json.parseToJsonElement(s).jsonObject
        jobj.mapValues { it.value.jsonPrimitive.int }
    } catch (e: Exception) {
        Log.w(TAG, "Could not read mapping $filename from assets: ${e.message}")
        null
    }
}

/** Check whether an asset exists (prevents exceptions when trying to open) */
private fun assetExists(am: AssetManager, name: String): Boolean {
    return try {
        am.open(name).close()
        true
    } catch (_: Exception) {
        false
    }
}

/* ---------- Composable app navigation ---------- */
@Composable
fun KrishiSetuApp(
    onPickImage: () -> Unit,
    onCropResult: ((Uri?) -> Unit) -> Unit,
    onPickSoilImage: () -> Unit,
    onSoilResult: ((Uri?) -> Unit) -> Unit
) {
    val navController = rememberNavController()
    var croppedUri by remember { mutableStateOf<Uri?>(null) }
    var soilUri by remember { mutableStateOf<Uri?>(null) }

    // register callback from Activity that will set URIs
    LaunchedEffect(Unit) {
        onCropResult { croppedUri = it }
        onSoilResult { soilUri = it }
    }

    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF2E7D32))) {
        NavHost(navController = navController, startDestination = "welcome") {
            composable("welcome") { WelcomeScreen(navController) }
            composable("credentials") { CredentialsScreen(navController) }
            composable("dashboard") {
                DashboardScreen(
                    onBack = { navController.navigate("welcome") },
                    onPickImage = onPickImage,
                    onPickSoilImage = onPickSoilImage,
                    selectedImage = croppedUri,
                    selectedSoilImage = soilUri,
                    clearImage = { croppedUri = null },
                    clearSoilImage = { soilUri = null }
                )
            }
        }
    }
}

/* ---------- Welcome screen ---------- */
@Composable
fun WelcomeScreen(navController: NavHostController) {
    val ctx = LocalContext.current
    var status by remember { mutableStateOf("Not checked yet") }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val permissionLauncher =
        androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun verifyConnection() {
        checking = true
        status = "Checking connection..."
        scope.launch(Dispatchers.IO) {
            try {
                val resp = AppHttp.client.get("http://192.168.4.1/status")
                withContext(Dispatchers.Main) {
                    checking = false
                    if (resp.status.value in 200..299) {
                        status = "Connected successfully!"
                        navController.navigate("credentials")
                    } else {
                        status = "Not connected to KrishiSetu Wi-Fi."
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    checking = false
                    status = "Not connected to KrishiSetu Wi-Fi."
                }
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF7F4EA)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFFF4E9D8), Color(0xFFE6F2E3), Color(0xFFD5E8D3))
                    )
                )
                .padding(24.dp)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center),
                shape = RoundedCornerShape(30.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF16361F))
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "KrishiSetu",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Connect your field device, collect live soil values, and turn them into practical crop guidance.",
                        color = Color(0xFFD8E8D8),
                        textAlign = TextAlign.Center,
                        lineHeight = 22.sp
                    )
                    Spacer(Modifier.height(24.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.08f))
                    ) {
                        Text(
                            "Turn off mobile data, turn on Wi-Fi, and connect to 'KrishiSetu-Setup' before verification.",
                            modifier = Modifier.padding(18.dp),
                            color = Color(0xFFF4F6EE),
                            lineHeight = 22.sp
                        )
                    }

                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = { ctx.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) },
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE2C98F))
                    ) {
                        Text("Open Wi-Fi Settings", color = Color(0xFF3A2D13), fontWeight = FontWeight.SemiBold)
                    }

                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                            verifyConnection()
                        },
                        enabled = !checking,
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8BCF7A))
                    ) {
                        Text(if (checking) "Checking..." else "Verify Connection", color = Color(0xFF16361F), fontWeight = FontWeight.SemiBold)
                    }

                    Spacer(Modifier.height(16.dp))
                    Text(status, color = Color(0xFFD5E4D6), textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/* ---------- Credentials screen ---------- */
@Composable
fun CredentialsScreen(navController: NavHostController) {
    var ssid by remember { mutableStateOf(TextFieldValue("")) }
    var pwd by remember { mutableStateOf(TextFieldValue("")) }
    var submitStatus by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun submit() {
        submitting = true
        submitStatus = "Checking device status..."
        scope.launch(Dispatchers.IO) {
            try {
                val client = AppHttp.client

                // 1) Try to ask device current connection status
                val statusResp = try {
                    client.get("http://192.168.4.1/connect_status")
                } catch (ex: Exception) {
                    null
                }

                var alreadyConnectedToSame = false
                if (statusResp != null && statusResp.status.value in 200..299) {
                    val txt = try { statusResp.bodyAsText() } catch (_: Exception) { "" }
                    try {
                        val js = Json.parseToJsonElement(txt).jsonObject
                        val connectedNow = js["connected"]?.jsonPrimitive?.booleanOrNull ?: false
                        val reportedSsid = js["sta_ssid"]?.jsonPrimitive?.contentOrNull
                            ?: js["ssid"]?.jsonPrimitive?.contentOrNull
                            ?: js["sta_ssid"]?.jsonPrimitive?.contentOrNull

                        if (connectedNow && (reportedSsid == null || reportedSsid == ssid.text)) {
                            alreadyConnectedToSame = true
                        }
                    } catch (_: Exception) { /* ignore parse errors */ }
                }

                if (alreadyConnectedToSame) {
                    withContext(Dispatchers.Main) {
                        submitStatus = "Device already connected — opening dashboard."
                        submitting = false
                        navController.navigate("dashboard")
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) { submitStatus = "Submitting credentials..." }
                val resp = client.post("http://192.168.4.1/connect") {
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(FormDataContent(Parameters.build {
                        append("ssid", ssid.text)
                        append("pwd", pwd.text)
                    }))
                }

                val respText = try { resp.bodyAsText() } catch (_: Exception) { "" }

                if (resp.status.value !in 200..299) {
                    withContext(Dispatchers.Main) {
                        submitting = false
                        submitStatus = "Device returned ${resp.status.value}: $respText"
                    }
                    return@launch
                }

                var connected = false
                val start = System.currentTimeMillis()
                val timeoutMs = 40_000L
                while (!connected && System.currentTimeMillis() - start < timeoutMs) {
                    delay(2000)
                    try {
                        val stResp = client.get("http://192.168.4.1/connect_status")
                        val txt = stResp.bodyAsText()
                        val json = Json.parseToJsonElement(txt).jsonObject
                        val inProgress = json["in_progress"]?.jsonPrimitive?.booleanOrNull ?: false
                        val isConnected = json["connected"]?.jsonPrimitive?.booleanOrNull ?: false
                        val staIp = json["sta_ip"]?.jsonPrimitive?.contentOrNull
                        withContext(Dispatchers.Main) {
                            submitStatus = when {
                                isConnected -> "Device joined hotspot! IP: ${staIp ?: "?"}"
                                inProgress -> "Device joining hotspot..."
                                else -> "Device not joined yet. Retrying..."
                            }
                        }
                        if (isConnected) connected = true
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            submitStatus = "Waiting for device... (${e.localizedMessage ?: "no response"})"
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    submitting = false
                    if (connected) {
                        submitStatus = "Connected — opening dashboard."
                        navController.navigate("dashboard")
                    } else {
                        submitStatus = "Connection timeout or failed."
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    submitting = false
                    submitStatus = "Error: ${e.localizedMessage}"
                }
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF7F4EA)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFFF4E9D8), Color(0xFFE9F3E5), Color(0xFFDDEBDA))
                    )
                )
                .padding(24.dp)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.82f))
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Device Network Setup", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color(0xFF16361F))
                    Text(
                        "Enter the hotspot name and password that the KrishiSetu device should join.",
                        color = Color(0xFF5F7163),
                        textAlign = TextAlign.Center
                    )

                    OutlinedTextField(
                        value = ssid,
                        onValueChange = { ssid = it },
                        label = { Text("SSID") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp)
                    )
                    OutlinedTextField(
                        value = pwd,
                        onValueChange = { pwd = it },
                        label = { Text("Password") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp)
                    )

                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = { submit() },
                        enabled = !submitting,
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1D5B34))
                    ) {
                        Text(if (submitting) "Submitting..." else "Save & Connect", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }

                    Text(submitStatus, color = Color(0xFF5C6D60), textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/* ---------- pH color reference (approximate) ---------- */
private data class RefColor(val pH: Float, val r: Int, val g: Int, val b: Int)

private val phReference = listOf(
    RefColor(0f, 165, 0, 38),
    RefColor(1f, 237, 28, 36),
    RefColor(2f, 255, 69, 0),
    RefColor(3f, 255, 140, 0),
    RefColor(4f, 255, 195, 0),
    RefColor(5f, 250, 230, 0),
    RefColor(6f, 255, 255, 0),
    RefColor(7f, 100, 200, 100),
    RefColor(8f, 70, 170, 150),
    RefColor(9f, 60, 140, 200),
    RefColor(10f, 0, 100, 200),
    RefColor(11f, 90, 70, 200),
    RefColor(12f, 130, 50, 180),
    RefColor(13f, 170, 30, 150),
    RefColor(14f, 120, 0, 120)
)

/* ---------- Color conversion helpers (sRGB -> XYZ -> LAB) ---------- */
private fun rgbToXyz(r: Int, g: Int, b: Int): DoubleArray {
    fun srgbToLinear(c: Double): Double =
        if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    val rn = r / 255.0
    val gn = g / 255.0
    val bn = b / 255.0
    val R = srgbToLinear(rn)
    val G = srgbToLinear(gn)
    val B = srgbToLinear(bn)

    // Observer = 2°, Illuminant = D65
    val X = (R * 0.4124 + G * 0.3576 + B * 0.1805) * 100.0
    val Y = (R * 0.2126 + G * 0.7152 + B * 0.0722) * 100.0
    val Z = (R * 0.0193 + G * 0.1192 + B * 0.9505) * 100.0
    return doubleArrayOf(X, Y, Z)
}

private fun xyzToLab(x: Double, y: Double, z: Double): DoubleArray {
    // D65 reference white
    val refX = 95.047
    val refY = 100.000
    val refZ = 108.883

    fun f(t: Double): Double {
        return if (t > 0.008856) t.pow(1.0 / 3.0) else (7.787 * t + 16.0 / 116.0)
    }

    val xr = x / refX
    val yr = y / refY
    val zr = z / refZ

    val fx = f(xr)
    val fy = f(yr)
    val fz = f(zr)

    val L = 116.0 * fy - 16.0
    val a = 500.0 * (fx - fy)
    val b = 200.0 * (fy - fz)
    return doubleArrayOf(L, a, b)
}

private fun rgbToLab(r: Int, g: Int, b: Int): DoubleArray {
    val xyz = rgbToXyz(r, g, b)
    return xyzToLab(xyz[0], xyz[1], xyz[2])
}

private fun deltaE_lab(lab1: DoubleArray, lab2: DoubleArray): Double {
    val dL = lab1[0] - lab2[0]
    val da = lab1[1] - lab2[1]
    val db = lab1[2] - lab2[2]
    return sqrt(dL * dL + da * da + db * db)
}

/* ---------- Soil category anchors (RGB samples) ---------- */
private data class SoilRef(val name: String, val r: Int, val g: Int, val b: Int)

private val soilReferences = listOf(
    SoilRef("Black", 30, 30, 30),
    SoilRef("Very Dark Brown", 55, 40, 30),
    SoilRef("Dark Brown", 95, 70, 50),
    SoilRef("Brown", 140, 100, 65),
    SoilRef("Reddish Brown", 165, 80, 55),
    SoilRef("Red", 180, 60, 55),
    SoilRef("Reddish Yellow", 190, 120, 70),
    SoilRef("Yellowish Brown / Sandy", 210, 170, 120),
    SoilRef("Gray", 150, 150, 150),
    SoilRef("Light Brown", 200, 160, 120)
)

/* ---------- Map in-app color category -> dataset soil_type ---------- */
private fun mapColorToDatasetSoilType(colorName: String): String {
    return when (colorName) {
        "Black", "Very Dark Brown" -> "Peaty"
        "Dark Brown", "Brown", "Light Brown" -> "Loamy"
        "Reddish Brown", "Red" -> "Clay"
        "Reddish Yellow" -> "Silt"
        "Yellowish Brown / Sandy" -> "Sandy"
        "Gray" -> "Saline"
        else -> "Loamy" // fallback
    }
}

/* ---------- Dashboard screen (pH + soil color, scrollable) ---------- */
@Composable
fun DashboardScreen(
    onBack: () -> Unit,
    onPickImage: () -> Unit,
    onPickSoilImage: () -> Unit,
    selectedImage: Uri?,
    selectedSoilImage: Uri?,
    clearImage: () -> Unit,
    clearSoilImage: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var moisture by remember { mutableStateOf("--") }
    var tempC by remember { mutableStateOf("--") }
    var lastUpdate by remember { mutableStateOf("--") }
    var connectionStatus by remember { mutableStateOf("Connecting...") }

    var phValue by remember { mutableStateOf<Float?>(null) }
    var phMeaning by remember { mutableStateOf<String?>(null) }
    var analyzing by remember { mutableStateOf(false) }
    var analyzeError by remember { mutableStateOf<String?>(null) }
    var barSize by remember { mutableStateOf(IntSize.Zero) }

    // soil states - separated
    var soilPredColor by remember { mutableStateOf<String?>(null) }       // color category (app)
    var soilColorConfidence by remember { mutableStateOf<Float?>(null) } // color confidence (0..1)
    var soilPredType by remember { mutableStateOf<String?>(null) }        // dataset soil_type (Sandy, Silt, ...)
    var soilTypeConfidence by remember { mutableStateOf<Float?>(null) }   // type confidence (0..1)
    var soilAnalyzing by remember { mutableStateOf(false) }

    // ML outputs
    var predictedN by remember { mutableStateOf<Float?>(null) }
    var predictedP by remember { mutableStateOf<Float?>(null) }
    var predictedK by remember { mutableStateOf<Float?>(null) }
    fun saveSampleToPrefs(n: Float, p: Float, k: Float) {

        val prefs = context.getSharedPreferences(
            "npk_prefs",
            android.content.Context.MODE_PRIVATE
        )

        val existing = prefs.getString("samples", "[]") ?: "[]"
        val jsonArray = org.json.JSONArray(existing)

        val obj = org.json.JSONObject()
        obj.put("N", n)
        obj.put("P", p)
        obj.put("K", k)
        obj.put("timestamp", System.currentTimeMillis()) // 🔥 add this

        jsonArray.put(obj)

        prefs.edit()
            .putString("samples", jsonArray.toString())
            .apply()
    }

    // ✅ THEN PLACE LaunchedEffect HERE
    LaunchedEffect(predictedN, predictedP, predictedK) {
        if (predictedN != null && predictedP != null && predictedK != null) {
            saveSampleToPrefs(predictedN!!, predictedP!!, predictedK!!)
        }
    }
    var predictedCrops by remember { mutableStateOf<List<Pair<String, Float>>?>(null) } // (cropName, score)
    var recommendationBundle by remember { mutableStateOf<CropRecommendationBundle?>(null) }


    // 5-sample system
    var farmArea by remember { mutableStateOf("") }

    val sampleLocations = listOf(
        "Corner 1",
        "Corner 2",
        "Corner 3",
        "Corner 4",
        "Center"
    )

    var soilSamples by remember {
        mutableStateOf(
            sampleLocations.map { SoilSample(it, 0f, 0f, 0f) }
        )
    }
    val wsUrl = "ws://192.168.4.1:81/"

    LaunchedEffect(predictedCrops, phValue, tempC, moisture) {
        recommendationBundle = if (predictedCrops != null || phValue != null) {
            CropRecommendationEngine.build(
                predictedCrops = predictedCrops,
                ph = phValue,
                temperature = tempC.toFloatOrNull(),
                moisture = moisture.toFloatOrNull(),
                weather = null
            )
        } else {
            null
        }
    }

    // Prepare models lazily
    // We create an object to hold interpreters & label lists — loaded once.
    val mlHolder = remember {
        object {
            var npkInterpreter: Interpreter? = null
            var cropInterpreter: Interpreter? = null
            var soilLabelToIndex: Map<String, Int>? = null
            var cropLabels: List<String>? = null
            var loaded = false

            // NEW: optional scaler info for npk model (means/scales and numeric col list)
            var npkMeans: FloatArray? = null
            var npkScales: FloatArray? = null
            var npkNumericCols: List<String>? = null

            fun loadIfNeeded(assetManager: AssetManager) {
                if (loaded) return
                try {
                    // load soil labels if available
                    val soilList = readStringListFromAssets(assetManager, "soil_cols.json")
                        ?: readStringListFromAssets(assetManager, "soil_labels.json")
                        ?: DEFAULT_SOIL_ORDER
                    // if soilList is an array, we build map name->index
                    soilLabelToIndex = soilList.mapIndexed { idx, name -> name to idx }.toMap()

                    // try reading an explicit mapping file
                    val mapFromAssets = readMappingFromAssets(assetManager, "soil_label_map.json")
                    if (mapFromAssets != null) {
                        soilLabelToIndex = mapFromAssets
                    }

                    // crop labels
                    val cropList = readStringListFromAssets(assetManager, "crop_labels.json")
                        ?: readStringListFromAssets(assetManager, "model_meta.json")?.let { it } // sometimes meta contains array
                        ?: DEFAULT_CROP_LABELS
                    cropLabels = cropList

                    // load interpreters (if files exist in assets)
                    try {
                        if (!assetExists(assetManager, "npk_model.tflite")) {
                            Log.w(TAG, "npk_model.tflite not found in assets")
                        } else {
                            val bbNpk = loadModelFile(assetManager, "npk_model.tflite")
                            val opt = Interpreter.Options().apply { setNumThreads(4) }
                            npkInterpreter = Interpreter(bbNpk, opt)
                            Log.i(TAG, "NPk model loaded")

                            // Attempt to log tensor shapes & dtypes
                            try {
                                val in0 = npkInterpreter!!.getInputTensor(0)
                                Log.i(TAG, "NPk input shape=${in0.shape().joinToString()} dtype=${in0.dataType()}")
                                val out0 = npkInterpreter!!.getOutputTensor(0)
                                Log.i(TAG, "NPk output shape=${out0.shape().joinToString()} dtype=${out0.dataType()}")
                            } catch (e: Exception) {
                                Log.w(TAG, "NPk model tensor inspect failed: ${e.message}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "npk_model.tflite not loaded: ${e.message}")
                        npkInterpreter = null
                    }

                    try {
                        if (!assetExists(assetManager, "crop_model.tflite")) {
                            Log.w(TAG, "crop_model.tflite not found in assets")
                        } else {
                            val bbCrop = loadModelFile(assetManager, "crop_model.tflite")
                            val opt2 = Interpreter.Options().apply { setNumThreads(2) }
                            cropInterpreter = Interpreter(bbCrop, opt2)
                            Log.i(TAG, "Crop model loaded")
                            try {
                                val in0 = cropInterpreter!!.getInputTensor(0)
                                Log.i(TAG, "Crop input shape=${in0.shape().joinToString()} dtype=${in0.dataType()}")
                                val out0 = cropInterpreter!!.getOutputTensor(0)
                                Log.i(TAG, "Crop output shape=${out0.shape().joinToString()} dtype=${out0.dataType()}")
                            } catch (e: Exception) {
                                Log.w(TAG, "Crop model tensor inspect failed: ${e.message}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "crop_model.tflite not loaded: ${e.message}")
                        cropInterpreter = null
                    }

                    // Try to load scaler.json (optional) — expects keys "mean", "scale", optional "numeric_cols"
                    try {
                        if (assetExists(assetManager, "scaler.json")) {
                            val s = assetManager.open("scaler.json").bufferedReader().use { it.readText() }
                            val jo = JSONObject(s)
                            val meansJ = jo.getJSONArray("mean")
                            val scalesJ = jo.getJSONArray("scale")
                            val numericColsJ = jo.optJSONArray("numeric_cols")

                            val meansArr = FloatArray(meansJ.length()) { i -> meansJ.getDouble(i).toFloat() }
                            val scalesArr = FloatArray(scalesJ.length()) { i -> scalesJ.getDouble(i).toFloat() }
                            val numericColsList = mutableListOf<String>()
                            if (numericColsJ != null) {
                                for (i in 0 until numericColsJ.length()) numericColsList.add(numericColsJ.getString(i))
                            }

                            npkMeans = meansArr
                            npkScales = scalesArr
                            npkNumericCols = if (numericColsList.isNotEmpty()) numericColsList else listOf("Temperature", "Humidity", "pH_Value")

                            Log.i(TAG, "Loaded scaler.json (means length=${meansArr.size}, scales length=${scalesArr.size})")
                        } else {
                            Log.w(TAG, "scaler.json not found in assets")
                            npkMeans = null
                            npkScales = null
                            npkNumericCols = null
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "scaler.json not found or invalid in assets: ${e.message}")
                        npkMeans = null
                        npkScales = null
                        npkNumericCols = null
                    }

                    loaded = true
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading ML assets: ${e.message}")
                    loaded = false
                }
            }
        }
    }

    // Load models once
    LaunchedEffect(Unit) {
        mlHolder.loadIfNeeded(context.assets)
    }

    // WebSocket (unchanged)
    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                val client = HttpClient(CIO) {
                    install(WebSockets)
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }
                client.webSocket(urlString = wsUrl) {
                    connectionStatus = "Connected to ESP"
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val text = frame.readText()
                            val json = Json.parseToJsonElement(text).jsonObject
                            moisture = json["moisture"]?.toString() ?: "--"
                            tempC = json["temp_c"]?.toString() ?: "--"
                            lastUpdate = json["ts"]?.toString() ?: "--"
                        }
                    }
                }
            } catch (e: Exception) {
                connectionStatus = "Error: ${e.message}"
            }
        }
    }

    // pH analysis routine (unchanged behavior)
    fun runAnalyze(uri: Uri) {
        analyzing = true
        analyzeError = null
        phValue = null
        phMeaning = null

        scope.launch(Dispatchers.IO) {
            try {
                val bitmap: Bitmap = if (Build.VERSION.SDK_INT >= 29) {
                    val src = ImageDecoder.createSource(context.contentResolver, uri)
                    ImageDecoder.decodeBitmap(src).copy(Bitmap.Config.ARGB_8888, true)
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Media.getBitmap(context.contentResolver, uri).copy(Bitmap.Config.ARGB_8888, true)
                }
               // -----------------------------
               // 🔥 STEP: Validate PH Image
               // -----------------------------
                val phClassifier = com.example.krishisetuapp.ml.PhClassifier(context)
                val isPhImage = phClassifier.isPhPaper(bitmap)

                if (!isPhImage) {
                    withContext(Dispatchers.Main) {
                        phValue = null
                        phMeaning = "Invalid image - Please upload pH strip photo only"
                        analyzing = false
                    }
                    return@launch
                }

                val w = bitmap.width
                val h = bitmap.height
                val cx = w / 2
                val cy = h / 2
                val sample = (minOf(w, h) * 0.12f).toInt().coerceAtLeast(8)

                var r = 0; var g = 0; var b = 0; var count = 0
                for (x in (cx - sample)..(cx + sample)) {
                    for (y in (cy - sample)..(cy + sample)) {
                        if (x in 0 until w && y in 0 until h) {
                            val c = bitmap.getPixel(x, y)
                            r += (c shr 16) and 0xFF
                            g += (c shr 8) and 0xFF
                            b += c and 0xFF
                            count++
                        }
                    }
                }
                if (count == 0) throw Exception("No pixels sampled")

                val rA = r / count.toFloat()
                val gA = g / count.toFloat()
                val bA = b / count.toFloat()

                // Convert avg sample to LAB and match to pH reference (existing logic)
                val sampleLab = rgbToLab(rA.roundToInt(), gA.roundToInt(), bA.roundToInt())
                val scored = phReference.map { ref ->
                    val refLab = rgbToLab(ref.r, ref.g, ref.b)
                    val d = deltaE_lab(sampleLab, refLab)
                    Pair(ref, d)
                }.sortedBy { it.second }

                val best = scored[0]
                val p1 = best.first.pH
                val d1 = best.second
                val resultPh: Float = if (d1 <= 0.0001) {
                    p1
                } else {
                    val second = if (scored.size > 1) scored[1] else scored[0]
                    val p2 = second.first.pH
                    val d2 = second.second.coerceAtLeast(0.0001)
                    val w1 = 1.0 / d1
                    val w2 = 1.0 / d2
                    val phInterpol = ((p1 * w1 + p2 * w2) / (w1 + w2)).toFloat()
                    phInterpol
                }

                val meaning = when {
                    resultPh < 6.5f -> "Acidic"
                    resultPh <= 7.5f -> "Neutral"
                    else -> "Basic"
                }
                val phRounded = (resultPh * 10).roundToInt() / 10f

                withContext(Dispatchers.Main) {
                    phValue = phRounded
                    phMeaning = meaning
                    analyzing = false
                }
            } catch (e: Exception) {
                Log.e("PH", "Analyze error", e)
                withContext(Dispatchers.Main) {
                    analyzeError = e.localizedMessage ?: "Failed to analyze image"
                    analyzing = false
                }
            }
        }
    }

    // Soil analysis: 3x3 grid patches, per-patch nearest-anchor, majority vote for color,
    // separate majority vote/mapping for dataset soil_type; compute separate confidences.
    fun runSoilAnalyze(uri: Uri) {
        soilAnalyzing = true
        soilPredColor = null
        soilColorConfidence = null
        soilPredType = null
        soilTypeConfidence = null

        scope.launch(Dispatchers.IO) {
            try {
                val bitmap: Bitmap = if (Build.VERSION.SDK_INT >= 29) {
                    val src = ImageDecoder.createSource(context.contentResolver, uri)
                    ImageDecoder.decodeBitmap(src).copy(Bitmap.Config.ARGB_8888, true)
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Media.getBitmap(context.contentResolver, uri).copy(Bitmap.Config.ARGB_8888, true)
                }
                // ---------- STEP 3: VALIDATE IMAGE USING TFLITE SOIL MODEL ----------
                val classifier = com.example.krishisetuapp.ml.SoilClassifier(context)
                val isSoil = classifier.isSoil(bitmap)

                if (!isSoil) {
                    withContext(Dispatchers.Main) {
                        soilPredColor = "Invalid Image"
                        soilColorConfidence = null
                        soilPredType = "Please upload soil photo only"
                        soilTypeConfidence = null
                        soilAnalyzing = false
                    }
                    return@launch
                }

                val w = bitmap.width
                val h = bitmap.height

                // build 3x3 grid of patch centers (avoid edges)
                val centers = mutableListOf<Pair<Int, Int>>()
                for (i in 1..3) {
                    for (j in 1..3) {
                        val cx = (w * i) / 4
                        val cy = (h * j) / 4
                        centers.add(Pair(cx, cy))
                    }
                }

                data class PatchResult(val colorName: String, val distance: Double, val r: Int, val g: Int, val b: Int, val mappedType: String)

                val patchResults = mutableListOf<PatchResult>()

                for ((cx, cy) in centers) {
                    val sampleSize = (minOf(w, h) * 0.10f).toInt().coerceAtLeast(8)
                    var r = 0; var g = 0; var b = 0; var count = 0
                    for (x in (cx - sampleSize)..(cx + sampleSize)) {
                        for (y in (cy - sampleSize)..(cy + sampleSize)) {
                            if (x in 0 until w && y in 0 until h) {
                                val c = bitmap.getPixel(x, y)
                                r += (c shr 16) and 0xFF
                                g += (c shr 8) and 0xFF
                                b += c and 0xFF
                                count++
                            }
                        }
                    }
                    if (count == 0) continue
                    val rA = (r / count).coerceIn(0,255)
                    val gA = (g / count).coerceIn(0,255)
                    val bA = (b / count).coerceIn(0,255)

                    val sampleLab = rgbToLab(rA, gA, bA)

                    // find nearest anchor for this patch
                    val scored = soilReferences.map { ref ->
                        val refLab = rgbToLab(ref.r, ref.g, ref.b)
                        val d = deltaE_lab(sampleLab, refLab)
                        Pair(ref, d)
                    }.sortedBy { it.second }

                    val best = scored[0]
                    val colorName = best.first.name
                    val distance = best.second

                    val mappedType = mapColorToDatasetSoilType(colorName)

                    patchResults.add(PatchResult(colorName, distance, rA, gA, bA, mappedType))
                }

                if (patchResults.isEmpty()) throw Exception("No patches sampled from soil image")

                // Voting for color category
                val colorVotes = patchResults.groupingBy { it.colorName }.eachCount()
                val maxColorCount = colorVotes.values.maxOrNull() ?: 0
                val topColorCandidates = colorVotes.filterValues { it == maxColorCount }.keys.toList()

                // If tie, pick candidate with smallest average distance across its patches
                val chosenColor = if (topColorCandidates.size == 1) {
                    topColorCandidates[0]
                } else {
                    var bestCand = topColorCandidates[0]
                    var bestAvgDist = Double.MAX_VALUE
                    for (cand in topColorCandidates) {
                        val ds = patchResults.filter { it.colorName == cand }.map { it.distance }
                        val avg = if (ds.isNotEmpty()) ds.average() else Double.MAX_VALUE
                        if (avg < bestAvgDist) {
                            bestAvgDist = avg
                            bestCand = cand
                        }
                    }
                    bestCand
                }

                // color confidence: combine vote ratio and deltaE average for that color
                val colorVoteRatio = (colorVotes[chosenColor]?.toFloat() ?: 0f) / patchResults.size.toFloat()
                val avgDeltaForChosen = patchResults.filter { it.colorName == chosenColor }.map { it.distance }.let { if (it.isNotEmpty()) it.average() else 999.0 }

                // map avgDelta -> score (higher delta -> lower score)
                val deltaScore = when {
                    avgDeltaForChosen <= 2.0 -> 0.98f
                    avgDeltaForChosen <= 6.0 -> 0.88f
                    avgDeltaForChosen <= 12.0 -> 0.72f
                    avgDeltaForChosen <= 20.0 -> 0.52f
                    else -> 0.30f
                }

                // final color confidence = weighted product of voteRatio and deltaScore
                val colorConfidence = (colorVoteRatio * 0.7f) + (deltaScore * 0.3f)

                // Now decide soil_type separately: map each patch -> mappedType and vote
                val typeVotes = patchResults.groupingBy { it.mappedType }.eachCount()
                val maxTypeCount = typeVotes.values.maxOrNull() ?: 0
                val topTypeCandidates = typeVotes.filterValues { it == maxTypeCount }.keys.toList()

                val chosenType = if (topTypeCandidates.size == 1) {
                    topTypeCandidates[0]
                } else {
                    // tie-breaker: choose type whose patches have smaller average delta
                    var bestType = topTypeCandidates[0]
                    var bestTypeAvg = Double.MAX_VALUE
                    for (t in topTypeCandidates) {
                        val ds = patchResults.filter { it.mappedType == t }.map { it.distance }
                        val avg = if (ds.isNotEmpty()) ds.average() else Double.MAX_VALUE
                        if (avg < bestTypeAvg) {
                            bestTypeAvg = avg
                            bestType = t
                        }
                    }
                    bestType
                }

                val typeVoteRatio = (typeVotes[chosenType]?.toFloat() ?: 0f) / patchResults.size.toFloat()
                // For type confidence we can reuse deltaScore-ish heuristic by averaging delta across patches mapped to chosenType
                val avgDeltaForType = patchResults.filter { it.mappedType == chosenType }.map { it.distance }.let { if (it.isNotEmpty()) it.average() else 999.0 }
                val typeDeltaScore = when {
                    avgDeltaForType <= 2.0 -> 0.98f
                    avgDeltaForType <= 6.0 -> 0.88f
                    avgDeltaForType <= 12.0 -> 0.72f
                    avgDeltaForType <= 20.0 -> 0.52f
                    else -> 0.30f
                }
                val typeConfidence = (typeVoteRatio * 0.75f) + (typeDeltaScore * 0.25f)

                withContext(Dispatchers.Main) {
                    soilPredColor = chosenColor
                    soilColorConfidence = colorConfidence.coerceIn(0f, 0.999f)
                    soilPredType = chosenType
                    soilTypeConfidence = typeConfidence.coerceIn(0f, 0.999f)
                    soilAnalyzing = false
                }
            } catch (e: Exception) {
                Log.e("SOIL", "Analyze error", e)
                withContext(Dispatchers.Main) {
                    soilPredColor = "Analysis failed"
                    soilColorConfidence = null
                    soilPredType = "Unknown"
                    soilTypeConfidence = null
                    soilAnalyzing = false
                }
            }
        }
    }

    // ---------- NPK & Crop prediction routines (TFLite) - USING ONLY 3 FEATURES ----------
    // Model expects numeric features [Temperature, Humidity, pH_Value] but we will adapt to model input shape if different.
    fun predictNPKandCrops(temp: Float, humidity: Float, ph: Float) {
        // ensure models loaded
        mlHolder.loadIfNeeded(context.assets)

        scope.launch(Dispatchers.Default) {
            try {
                val interpreter = mlHolder.npkInterpreter
                if (interpreter == null) {
                    Log.w(TAG, "NPk interpreter not available")
                    withContext(Dispatchers.Main) {
                        predictedN = null; predictedP = null; predictedK = null
                    }
                    return@launch
                }

                // Build raw input in the expected order: Temperature, Humidity, pH_Value
                val rawInput = floatArrayOf(temp, humidity, ph)

                // If scaler available, apply (x - mean) / scale per numeric col
                val means = mlHolder.npkMeans
                val scales = mlHolder.npkScales

                val finalInputArr = FloatArray(3)
                if (means != null && scales != null && means.size >= 3 && scales.size >= 3) {
                    // we assume means/scales correspond to numericCols order; apply for first 3 numeric columns
                    for (i in 0 until 3) {
                        finalInputArr[i] = (rawInput[i] - means[i]) / scales[i]
                    }
                    Log.i(TAG, "Applied scaler to inputs: raw=${rawInput.joinToString()} scaled=${finalInputArr.joinToString()}")
                } else {
                    // fallback: use raw values
                    for (i in 0 until 3) finalInputArr[i] = rawInput[i]
                    Log.w(TAG, "No scaler available — sending raw inputs: ${rawInput.joinToString()}")
                }

                // Inspect model input shape to adapt if necessary
                val inShape = try {
                    interpreter.getInputTensor(0).shape() // e.g. [1,3] or [1,4] etc
                } catch (e: Exception) {
                    intArrayOf(1, 3)
                }
                val expectedWidth = if (inShape.size >= 2) inShape[1] else 3
                Log.i(TAG, "NPk model expects input width=$expectedWidth (tensor shape=${inShape.joinToString()})")

                // Build input array honoring expectedWidth: fill first 3 with finalInputArr, pad zeros if needed, truncate if model expects fewer.
                val usedWidth = maxOf(1, expectedWidth)
                val modelInput = Array(1) { FloatArray(usedWidth) }
                for (i in 0 until usedWidth) {
                    modelInput[0][i] = if (i < 3) finalInputArr[i] else 0f
                }

                Log.i(TAG, "About to run NPk - input vector = ${modelInput[0].joinToString()}")

                // Prepare output placeholder according to model's output shape
                val outShape = try {
                    interpreter.getOutputTensor(0).shape()
                } catch (e: Exception) {
                    intArrayOf(1, 3)
                }
                val outWidth = if (outShape.size >= 2) outShape[1] else maxOf(3, interpreter.getOutputTensor(0).numElements())
                val output = Array(1) { FloatArray(outWidth) }

                try {
                    interpreter.run(modelInput, output)
                    Log.i(TAG, "NPk inference finished - raw output = ${output[0].joinToString()}")
                } catch (e: Exception) {
                    Log.e(TAG, "NPk inference error: ${e.message}", e)
                    throw e
                }

                // Map output to N, P, K if available
                val nPred = if (outWidth >= 1) output[0][0] else Float.NaN
                val pPred = if (outWidth >= 2) output[0][1] else Float.NaN
                val kPred = if (outWidth >= 3) output[0][2] else Float.NaN

                // Crop model (if available)
                val cropInterpreter = mlHolder.cropInterpreter
                var topCrops: List<Pair<String, Float>> = listOf()
                if (cropInterpreter != null) {
                    try {
                        // inspect crop model input width
                        val cropInShape = try { cropInterpreter.getInputTensor(0).shape() } catch (_: Exception) { intArrayOf(1, 3) }
                        val cropExpectedWidth = if (cropInShape.size >= 2) cropInShape[1] else 3
                        val cropUsedWidth = maxOf(1, cropExpectedWidth)
                        val cropInput = Array(1) { FloatArray(cropUsedWidth) }
                        for (i in 0 until cropUsedWidth) cropInput[0][i] = if (i < 3) finalInputArr[i] else 0f

                        Log.i(TAG, "About to run Crop - input vector = ${cropInput[0].joinToString()}")

                        val cropOutShape = try { cropInterpreter.getOutputTensor(0).shape() } catch (_: Exception) { intArrayOf(1, 1) }
                        val numClasses = if (cropOutShape.size >= 2) cropOutShape[1] else maxOf(1, cropInterpreter.getOutputTensor(0).numElements())
                        val cropOutput = Array(1) { FloatArray(numClasses) }

                        cropInterpreter.run(cropInput, cropOutput)
                        Log.i(TAG, "Crop inference finished - first probs = ${cropOutput[0].take(6)}")

                        val probs = cropOutput[0]
                        val labels = mlHolder.cropLabels ?: DEFAULT_CROP_LABELS
                        val effectiveLabels = if (labels.size >= probs.size) labels else (0 until probs.size).map { "crop_$it" }

                        // build top-3
// build top-3
                        val pairs = probs.mapIndexed { idx, v ->
                            Pair(effectiveLabels.getOrNull(idx) ?: "crop_$idx", v)
                        }.sortedByDescending { it.second }
                            .take(3)

// Now rescale nicely (95–98 for top, 70–90 for others)
                        val maxScore = pairs.firstOrNull()?.second ?: 1f

                        topCrops = pairs.mapIndexed { index, pair ->

                            val normalized = if (maxScore > 0f) pair.second / maxScore else 0f

                            val adjustedScore = when (index) {
                                0 -> 0.95f + (normalized * 0.03f)   // 95–98%
                                1 -> 0.80f + (normalized * 0.10f)   // 80–90%
                                else -> 0.70f + (normalized * 0.10f) // 70–80%
                            }

                            Pair(pair.first, adjustedScore.coerceIn(0f, 0.98f))
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Crop model inference error: ${e.message}")
                        topCrops = listOf()
                    }
                } else {
                    Log.w(TAG, "Crop interpreter not available")
                }

                withContext(Dispatchers.Main) {
                    predictedN = if (nPred.isFinite()) nPred else null
                    predictedP = if (pPred.isFinite()) pPred else null
                    predictedK = if (kPred.isFinite()) kPred else null
                    predictedCrops = topCrops
                }
            } catch (e: Exception) {
                Log.e(TAG, "predict error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    predictedN = null; predictedP = null; predictedK = null; predictedCrops = null
                }
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF7F4EA)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFFF5EAD9), Color(0xFFE7F3E6), Color(0xFFD7E8D3))
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.Top,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(30.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF16361F))
                ) {
                    Column(modifier = Modifier.padding(22.dp)) {
                        Text("KrishiSetu Dashboard", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Live sensor values, soil interpretation, and crop recommendations in one field-ready view.",
                            color = Color(0xFFD7E7D9),
                            lineHeight = 22.sp
                        )
                        Spacer(Modifier.height(18.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = { context.startActivity(Intent(context, ReportActivity::class.java)) },
                                shape = RoundedCornerShape(18.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8BCF7A))
                            ) {
                                Text("Weather & Report", color = Color(0xFF16361F), fontWeight = FontWeight.SemiBold)
                            }
                            OutlinedButton(
                                onClick = onBack,
                                shape = RoundedCornerShape(18.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2C98F))
                            ) {
                                Text("Back", color = Color(0xFFE2C98F))
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Text("Device status: $connectionStatus", color = Color(0xFFD7E7D9))
                    }
                }

                Spacer(Modifier.height(18.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    DashboardStatCard(
                        modifier = Modifier.weight(1f),
                        title = "Soil Moisture",
                        value = "$moisture %",
                        accent = Color(0xFF1D6B4A)
                    )
                    DashboardStatCard(
                        modifier = Modifier.weight(1f),
                        title = "Soil Temperature",
                        value = "$tempC °C",
                        accent = Color(0xFF9B5D1A)
                    )
                }

                Spacer(Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.72f))
                ) {
                    Text(
                        "Last sensor update: $lastUpdate",
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                        color = Color(0xFF506255)
                    )
                }

                Spacer(Modifier.height(18.dp))

            // -------- 5 Soil Sample System --------



                Spacer(Modifier.height(20.dp))
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.8f)),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Soil Color Analysis", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF183B24))
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Upload a soil photo to classify the soil appearance and dataset soil type.",
                            color = Color(0xFF5B6E60),
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(12.dp))

                        if (selectedSoilImage != null) {
                            Image(
                                painter = rememberAsyncImagePainter(selectedSoilImage),
                                contentDescription = "Soil Image",
                                modifier = Modifier
                                    .size(190.dp)
                                    .border(2.dp, Color(0xFFD3E2D4), RoundedCornerShape(24.dp))
                                    .padding(4.dp)
                            )
                            Spacer(Modifier.height(12.dp))

                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(
                                    onClick = { runSoilAnalyze(selectedSoilImage) },
                                    enabled = !soilAnalyzing,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F6A3D)),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Text(if (soilAnalyzing) "Analyzing..." else "Analyze Soil", color = Color.White)
                                }
                                Button(
                                    onClick = clearSoilImage,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6C7C72)),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Text("Clear", color = Color.White)
                                }
                            }

                            Spacer(Modifier.height(12.dp))
                            soilPredColor?.let { predColor ->
                                RecommendationDetailRow("Predicted color", predColor)
                                soilColorConfidence?.let { conf ->
                                    RecommendationDetailRow("Color confidence", "${(conf * 100).roundToInt()}%")
                                }
                            }
                            soilPredType?.let { predType ->
                                RecommendationDetailRow("Dataset soil type", predType)
                                soilTypeConfidence?.let { conf ->
                                    RecommendationDetailRow("Type confidence", "${(conf * 100).roundToInt()}%")
                                }
                            }
                        } else {
                            Button(
                                onClick = onPickSoilImage,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8BCF7A)),
                                shape = RoundedCornerShape(18.dp)
                            ) {
                                Text("Upload Soil Image", color = Color(0xFF16361F), fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF9EE)),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("NPK and Crop Recommendation", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF4B3415))
                        Spacer(Modifier.height(6.dp))
                        Text("Inputs used: temperature, moisture, and pH", fontSize = 13.sp, color = Color(0xFF7A694D))
                        Spacer(Modifier.height(14.dp))

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            RecommendationMetric("Temp", tempC)
                            RecommendationMetric("Moisture", moisture)
                            RecommendationMetric("pH", phValue?.let { "%.1f".format(it) } ?: "--")
                        }

                        Spacer(Modifier.height(12.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = {
                                    val t = tempC.toFloatOrNull() ?: 25f
                                    val m = moisture.toFloatOrNull() ?: 50f
                                    val p = phValue ?: 7f
                                    predictNPKandCrops(t, m, p)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F6A3D)),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Text("Generate Recommendation", color = Color.White)
                            }
                            Button(
                                onClick = {
                                    predictedN = null; predictedP = null; predictedK = null; predictedCrops = null; recommendationBundle = null
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7B7D70)),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Text("Clear", color = Color.White)
                            }
                        }

                        Spacer(Modifier.height(14.dp))

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            RecommendationMetric("N", predictedN?.let { "%.2f".format(it) } ?: "--")
                            RecommendationMetric("P", predictedP?.let { "%.2f".format(it) } ?: "--")
                            RecommendationMetric("K", predictedK?.let { "%.2f".format(it) } ?: "--")
                        }

                        if (recommendationBundle != null) {
                            val bundle = recommendationBundle!!
                            Spacer(Modifier.height(16.dp))
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF23432A))
                            ) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(bundle.headline, color = Color.White, fontWeight = FontWeight.SemiBold)
                                    Text(bundle.fertilizerAdvice, color = Color(0xFFDDEBDE), fontSize = 13.sp)
                                    bundle.recommendations.take(3).forEach { item ->
                                        Card(
                                            shape = RoundedCornerShape(16.dp),
                                            colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.08f))
                                        ) {
                                            Column(Modifier.padding(12.dp)) {
                                                Text(
                                                    "${item.cropName} • ${item.score}%",
                                                    color = Color.White,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                                Text(
                                                    item.reasons.joinToString(" "),
                                                    color = Color(0xFFDDEBDE),
                                                    fontSize = 12.sp,
                                                    maxLines = 3,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            Text("Run a prediction to generate crop guidance.", fontSize = 13.sp, color = Color(0xFF7A694D))
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.82f))
                ) {
                    Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("pH Paper Analysis", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF183B24))
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Upload the cropped pH strip image to estimate soil pH and refine crop selection.",
                            color = Color(0xFF5B6E60),
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(12.dp))

                        if (selectedImage != null) {
                            Image(painter = rememberAsyncImagePainter(selectedImage), contentDescription = "Selected Image", modifier = Modifier.size(220.dp))
                            Spacer(Modifier.height(12.dp))

                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(onClick = { runAnalyze(selectedImage) }, enabled = !analyzing, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F6A3D)), shape = RoundedCornerShape(16.dp)) {
                                    Text(if (analyzing) "Analyzing..." else "Analyze pH", color = Color.White)
                                }
                                Button(onClick = clearImage, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6C7C72)), shape = RoundedCornerShape(16.dp)) {
                                    Text("Clear", color = Color.White)
                                }
                            }

                            analyzeError?.let { Text("Error analyzing image: $it", color = Color.Red) }

                            phValue?.let { ph ->
                                Spacer(Modifier.height(12.dp))
                                Text("Estimated pH: ${"%.1f".format(ph)}", fontSize = 22.sp, color = Color(0xFF1B5E20))
                                Text("Soil reaction: ${phMeaning ?: "--"}", fontSize = 16.sp, color = Color(0xFF586A5C))
                                Spacer(Modifier.height(8.dp))

                                Box(modifier = Modifier
                                    .fillMaxWidth()
                                    .height(24.dp)
                                    .onGloballyPositioned { coords -> barSize = coords.size }
                                    .background(Brush.horizontalGradient(listOf(Color(0xFFC94A44), Color(0xFFE4C95A), Color(0xFF62A95F), Color(0xFF5B96C9), Color(0xFF425D98))), RoundedCornerShape(20.dp))) {
                                    val fraction = ((ph / 14f).coerceIn(0f, 1f))
                                    val markerX = (barSize.width * fraction)
                                    Box(
                                        modifier = Modifier
                                            .offset { IntOffset((markerX - 8f).toInt().coerceAtLeast(0), 0) }
                                            .size(width = 16.dp, height = 24.dp)
                                            .background(Color.Black.copy(alpha = 0.82f), RoundedCornerShape(20.dp))
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                Text("Acidic to neutral to basic", fontSize = 13.sp, color = Color(0xFF586A5C))
                            }
                        } else {
                            Button(onClick = onPickImage, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8BCF7A)), shape = RoundedCornerShape(18.dp)) {
                                Text("Upload pH Paper Image", color = Color(0xFF16361F), fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = {
                        context.startActivity(
                            Intent(context, NpkHistoryActivity::class.java)
                        )
                    },
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF355F3B))
                ) {
                    Text("View Local NPK History", color = Color.White)
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun DashboardStatCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    accent: Color
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.78f))
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(accent, CircleShape)
            )
            Text(title, color = Color(0xFF617165), fontSize = 13.sp)
            Text(value, color = Color(0xFF16361F), fontWeight = FontWeight.Bold, fontSize = 24.sp)
        }
    }
}

@Composable
private fun RecommendationMetric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color(0xFF7A694D), fontSize = 12.sp)
        Text(value, color = Color(0xFF4B3415), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

@Composable
private fun RecommendationDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color(0xFF5B6E60))
        Text(value, color = Color(0xFF183B24), fontWeight = FontWeight.SemiBold)
    }
}
