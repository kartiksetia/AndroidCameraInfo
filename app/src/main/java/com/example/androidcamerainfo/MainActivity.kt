package com.example.androidcamerainfo

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.util.Size
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.androidcamerainfo.ui.theme.AndroidCameraInfoTheme

/**
 * QA probe: dumps every camera's capabilities (resolutions, fps, hardware
 * level) so we can verify which devices support 640x480 recording and what
 * CameraX fallbacks would pick. No camera permission is required to read
 * these characteristics.
 */
private val VerdictYes = Color(0xFF2E7D32)
private val VerdictNo = Color(0xFFC62828)
private val ChipBlue = Color(0xFF1565C0)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AndroidCameraInfoTheme {
                CameraReportScreen()
            }
        }
    }
}

data class SizeEntry(val size: Size, val maxFps: Long, val isTarget: Boolean)
data class CameraInfo(
    val id: String,
    val facing: String,
    val hardwareLevel: String,
    val sensorOrientation: Int?,
    val capabilities: List<String>,
    val physicalIds: List<String>,
    val recordingSizes: List<SizeEntry>,
    val highSpeed: List<Pair<Size, String>>,
    val fpsRanges: List<String>,
    val supportsTarget: Boolean,
    val closestHigher: Size?
)
data class DeviceReport(
    val device: String,
    val android: String,
    val cameras: List<CameraInfo>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraReportScreen() {
    val context = LocalContext.current
    val report = buildDeviceReport(context)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Camera Info", fontWeight = FontWeight.Bold) },
                actions = {
                    OutlinedButton(onClick = { shareReport(context, report.toText()) }) { Text("Share") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { copyReport(context, report.toText()) }) { Text("Copy") }
                    Spacer(Modifier.width(12.dp))
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { DeviceCard(report) }
            items(report.cameras) { camera -> CameraCard(camera) }
        }
    }
}

@Composable
private fun DeviceCard(report: DeviceReport) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text("Device", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(report.device, style = MaterialTheme.typography.bodyLarge)
            Text(report.android, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Text("${report.cameras.size} camera(s) found", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun CameraCard(camera: CameraInfo) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Camera ${camera.id} · ${camera.facing}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "${camera.hardwareLevel} · sensor ${camera.sensorOrientation ?: "?"}°",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                VerdictBadge(camera)
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            if (camera.capabilities.isNotEmpty()) {
                SectionLabel("Capabilities")
                ChipRow(camera.capabilities, chipColor = MaterialTheme.colorScheme.secondaryContainer)
                Spacer(Modifier.height(10.dp))
            }
            if (camera.physicalIds.isNotEmpty()) {
                InfoLine("Physical cameras", camera.physicalIds.joinToString())
                Spacer(Modifier.height(10.dp))
            }

            SectionLabel("Recording resolutions (max fps)")
            ChipRow(
                camera.recordingSizes.map { entry ->
                    "${entry.size.width}×${entry.size.height}" +
                        if (entry.maxFps > 0) " @${entry.maxFps}fps" else ""
                },
                highlightIndices = camera.recordingSizes.mapIndexedNotNull { i, e -> if (e.isTarget) i else null }.toSet(),
                chipColor = MaterialTheme.colorScheme.surface,
                highlightColor = VerdictYes.copy(alpha = 0.15f),
                highlightTextColor = VerdictYes
            )

            if (camera.highSpeed.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                SectionLabel("High-speed video")
                camera.highSpeed.forEach { (size, ranges) ->
                    Text(
                        "  ${size.width}×${size.height}: $ranges",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            if (camera.fpsRanges.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                InfoLine("AE FPS ranges", camera.fpsRanges.joinToString())
            }
        }
    }
}

@Composable
private fun VerdictBadge(camera: CameraInfo) {
    val supported = camera.supportsTarget
    val bg = if (supported) VerdictYes.copy(alpha = 0.15f) else VerdictNo.copy(alpha = 0.15f)
    val fg = if (supported) VerdictYes else VerdictNo
    Column(horizontalAlignment = Alignment.End) {
        Surface(color = bg, shape = RoundedCornerShape(8.dp)) {
            Text(
                if (supported) "640×480 ✓" else "640×480 ✗",
                color = fg,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
        if (!supported) {
            Text(
                "closest higher: ${camera.closestHigher?.let { "${it.width}×${it.height}" } ?: "none"}",
                style = MaterialTheme.typography.labelSmall,
                color = fg,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row {
        Text(
            "$label: ",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(
    labels: List<String>,
    chipColor: Color,
    highlightIndices: Set<Int> = emptySet(),
    highlightColor: Color = Color.Transparent,
    highlightTextColor: Color = Color.Unspecified
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        labels.forEachIndexed { index, label ->
            val highlighted = highlightIndices.contains(index)
            val bg = if (highlighted) highlightColor else chipColor
            val textColor = if (highlighted) highlightTextColor else Color.Unspecified
            Box(
                modifier = Modifier
                    .background(bg, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = textColor,
                    fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip
                )
            }
        }
    }
}

fun buildDeviceReport(context: Context): DeviceReport {
    val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val cameras = manager.cameraIdList.map { id ->
        val c = manager.getCameraCharacteristics(id)
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

        val recordingSizes = map?.getOutputSizes(MediaRecorder::class.java).orEmpty()
            .map { size ->
                val minFrameNs = map?.getOutputMinFrameDuration(MediaRecorder::class.java, size) ?: 0L
                val maxFps = if (minFrameNs > 0) 1_000_000_000L / minFrameNs else -1L
                SizeEntry(size, maxFps, size.width == 640 && size.height == 480)
            }
            .sortedByDescending { it.size.width * it.size.height }

        val highSpeed = if (Build.VERSION.SDK_INT >= 23 && map != null) {
            map.highSpeedVideoSizes.orEmpty().map { size ->
                size to map.getHighSpeedVideoFpsRangesFor(size).joinToString { "[${it.lower},${it.upper}]" }
            }
        } else {
            emptyList()
        }

        val sizes = recordingSizes.map { it.size }
        val supportsTarget = sizes.any { it.width == 640 && it.height == 480 }
        val closestHigher = sizes.filter { it.width >= 640 && it.height >= 480 }
            .minByOrNull { it.width * it.height }

        CameraInfo(
            id = id,
            facing = facingName(c.get(CameraCharacteristics.LENS_FACING)),
            hardwareLevel = levelName(c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)),
            sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION),
            capabilities = capabilityList(c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()),
            physicalIds = if (Build.VERSION.SDK_INT >= 28) c.physicalCameraIds.toList() else emptyList(),
            recordingSizes = recordingSizes,
            highSpeed = highSpeed,
            fpsRanges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.map { "[${it.lower},${it.upper}]" }.orEmpty(),
            supportsTarget = supportsTarget,
            closestHigher = closestHigher
        )
    }
    return DeviceReport(
        device = "${Build.MANUFACTURER} ${Build.MODEL}",
        android = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
        cameras = cameras
    )
}

fun DeviceReport.toText(): String {
    val out = StringBuilder()
    out.appendLine("=== Device ===")
    out.appendLine(device)
    out.appendLine(android)
    out.appendLine()
    out.appendLine("=== Cameras: ${cameras.size} ===")
    for (camera in cameras) {
        out.appendLine()
        out.appendLine("--- Camera ${camera.id} ---")
        out.appendLine("Facing: ${camera.facing}")
        out.appendLine("Hardware level: ${camera.hardwareLevel}")
        out.appendLine("Sensor orientation: ${camera.sensorOrientation} deg")
        out.appendLine("Capabilities: ${camera.capabilities.joinToString().ifEmpty { "none" }}")
        if (camera.physicalIds.isNotEmpty()) out.appendLine("Physical cameras: ${camera.physicalIds.joinToString()}")
        out.appendLine()
        out.appendLine("Recording sizes (MediaRecorder):")
        camera.recordingSizes.forEach { entry ->
            val fps = if (entry.maxFps > 0) " up to ${entry.maxFps}fps" else ""
            val marker = if (entry.isTarget) "  <-- 640x480 SUPPORTED" else ""
            out.appendLine("  ${entry.size.width}x${entry.size.height}$fps$marker")
        }
        if (camera.highSpeed.isNotEmpty()) {
            out.appendLine()
            out.appendLine("High-speed video sizes:")
            camera.highSpeed.forEach { (size, ranges) -> out.appendLine("  ${size.width}x${size.height}: $ranges") }
        }
        if (camera.fpsRanges.isNotEmpty()) {
            out.appendLine()
            out.appendLine("AE target FPS ranges: ${camera.fpsRanges.joinToString()}")
        }
    }
    out.appendLine()
    out.appendLine("=== 640x480 verdict per camera ===")
    cameras.forEach { camera ->
        out.appendLine(
            "Camera ${camera.id} (${camera.facing}): " +
                if (camera.supportsTarget) "640x480 YES"
                else "640x480 NO, closest higher = ${camera.closestHigher?.let { "${it.width}x${it.height}" } ?: "none"}"
        )
    }
    return out.toString()
}

private fun facingName(value: Int?): String = when (value) {
    CameraMetadata.LENS_FACING_FRONT -> "FRONT"
    CameraMetadata.LENS_FACING_BACK -> "BACK"
    CameraMetadata.LENS_FACING_EXTERNAL -> "EXTERNAL"
    else -> "unknown($value)"
}

private fun levelName(value: Int?): String = when (value) {
    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
    else -> "unknown($value)"
}

private fun capabilityList(caps: IntArray): List<String> {
    return caps.map { cap ->
        when (cap) {
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE -> "BACKWARD_COMPATIBLE"
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> "MANUAL_SENSOR"
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING -> "MANUAL_POST_PROCESSING"
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW -> "RAW"
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING -> "PRIVATE_REPROCESSING"
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS -> "READ_SENSOR_SETTINGS"
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE -> "BURST_CAPTURE"
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING -> "YUV_REPROCESSING"
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT -> "DEPTH_OUTPUT"
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO -> "CONSTRAINED_HIGH_SPEED_VIDEO"
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING -> "MOTION_TRACKING"
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> "LOGICAL_MULTI_CAMERA"
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME -> "MONOCHROME"
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_SECURE_IMAGE_DATA -> "SECURE_IMAGE_DATA"
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_SYSTEM_CAMERA -> "SYSTEM_CAMERA"
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_OFFLINE_PROCESSING -> "OFFLINE_PROCESSING"
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR -> "ULTRA_HIGH_RESOLUTION_SENSOR"
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_REMOSAIC_REPROCESSING -> "REMOSAIC_REPROCESSING"
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT -> "DYNAMIC_RANGE_TEN_BIT"
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_STREAM_USE_CASE -> "STREAM_USE_CASE"
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_COLOR_SPACE_PROFILES -> "COLOR_SPACE_PROFILES"
            else -> "cap($cap)"
        }
    }
}

private fun shareReport(context: Context, report: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Camera report: ${Build.MANUFACTURER} ${Build.MODEL} SDK ${Build.VERSION.SDK_INT}")
        putExtra(Intent.EXTRA_TEXT, report)
    }
    context.startActivity(Intent.createChooser(intent, "Share camera report"))
}

private fun copyReport(context: Context, report: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("camera report", report))
    Toast.makeText(context, "Report copied", Toast.LENGTH_SHORT).show()
}