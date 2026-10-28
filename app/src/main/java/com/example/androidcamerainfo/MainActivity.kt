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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.androidcamerainfo.ui.theme.AndroidCameraInfoTheme

/**
 * QA probe: dumps every camera's capabilities (resolutions, fps, hardware
 * level) so we can verify which devices support 640x480 recording and what
 * CameraX fallbacks would pick. No camera permission is required to read
 * these characteristics.
 */
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

@Composable
fun CameraReportScreen() {
    val context = LocalContext.current
    val report = buildCameraReport(context)

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = { shareReport(context, report) }, modifier = Modifier.weight(1f)) {
                    Text("Share report")
                }
                Button(onClick = { copyReport(context, report) }, modifier = Modifier.weight(1f)) {
                    Text("Copy report")
                }
            }
            SelectionContainer {
                Text(
                    text = report,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 8.dp)
                )
            }
        }
    }
}

fun buildCameraReport(context: Context): String {
    val out = StringBuilder()
    out.appendLine("=== Device ===")
    out.appendLine("${Build.MANUFACTURER} ${Build.MODEL}")
    out.appendLine("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
    out.appendLine()

    val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val ids = manager.cameraIdList
    out.appendLine("=== Cameras: ${ids.size} ===")

    for (id in ids) {
        val c = manager.getCameraCharacteristics(id)
        out.appendLine()
        out.appendLine("--- Camera $id ---")
        out.appendLine("Facing: ${facingName(c.get(CameraCharacteristics.LENS_FACING))}")
        out.appendLine("Hardware level: ${levelName(c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL))}")
        out.appendLine("Sensor orientation: ${c.get(CameraCharacteristics.SENSOR_ORIENTATION)} deg")

        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        out.appendLine("Capabilities: ${capabilityNames(caps)}")

        if (Build.VERSION.SDK_INT >= 28) {
            val physical = c.physicalCameraIds
            if (physical.isNotEmpty()) out.appendLine("Physical cameras: ${physical.joinToString()}")
        }

        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        if (map == null) {
            out.appendLine("No stream configuration map")
            continue
        }

        out.appendLine()
        out.appendLine("Recording sizes (MediaRecorder):")
        val sizes = map.getOutputSizes(MediaRecorder::class.java).orEmpty()
        for (size in sizes.sortedByDescending { it.width * it.height }) {
            val minFrameNs = map.getOutputMinFrameDuration(MediaRecorder::class.java, size)
            val maxFps = if (minFrameNs > 0) (1_000_000_000L / minFrameNs) else -1
            val fpsText = if (maxFps > 0) " up to ${maxFps}fps" else ""
            val marker = if (size.width == 640 && size.height == 480) "  <-- 640x480 SUPPORTED" else ""
            out.appendLine("  ${size.width}x${size.height}$fpsText$marker")
        }

        if (Build.VERSION.SDK_INT >= 23) {
            val highSpeed = map.highSpeedVideoSizes.orEmpty()
            if (highSpeed.isNotEmpty()) {
                out.appendLine()
                out.appendLine("High-speed video sizes:")
                for (size in highSpeed) {
                    val ranges = map.getHighSpeedVideoFpsRangesFor(size)
                    out.appendLine("  ${size.width}x${size.height}: ${ranges.joinToString()}")
                }
            }
        }

        val fpsRanges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        if (fpsRanges != null) {
            out.appendLine()
            out.appendLine("AE target FPS ranges: ${fpsRanges.joinToString { "[${it.lower},${it.upper}]" }}")
        }
    }

    out.appendLine()
    out.appendLine("=== 640x480 verdict per camera ===")
    for (id in ids) {
        val c = manager.getCameraCharacteristics(id)
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
        val sizes = map.getOutputSizes(MediaRecorder::class.java).orEmpty().toList()
        val exact = sizes.any { it.width == 640 && it.height == 480 }
        val higher = sizes.filter { it.width >= 640 && it.height >= 480 }
            .minByOrNull { it.width * it.height }
        val facing = facingName(c.get(CameraCharacteristics.LENS_FACING))
        out.appendLine(
            "Camera $id ($facing): " +
                if (exact) "640x480 YES" else "640x480 NO, closest higher = ${higher?.let { "${it.width}x${it.height}" } ?: "none"}"
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

private fun capabilityNames(caps: IntArray): String {
    if (caps.isEmpty()) return "none"
    return caps.joinToString { cap ->
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