package com.offlinemesh.bridge

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

/**
 * Fully offline QR scanner: CameraX preview + ZXing decoding on-device.
 * No Play Services model download, no network.
 */
class QrScanActivity : AppCompatActivity() {
    private val analysisExec = Executors.newSingleThreadExecutor()
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true))
    }
    @Volatile private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preview = PreviewView(this)
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(TextView(this@QrScanActivity).apply {
                text = "Point the camera at your contact's Offline Mesh QR code"
                setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER
                setPadding(48, 48, 48, 96); setBackgroundColor(0x88000000.toInt())
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        }
        setContentView(root)

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val p = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analysisExec) { img -> analyze(img) }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, p, analysis)
            } catch (e: Exception) {
                setResult(Activity.RESULT_CANCELED, Intent().putExtra("error", "Camera unavailable")); finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(img: ImageProxy) {
        try {
            if (done) return
            val plane = img.planes[0]
            val buf = plane.buffer
            val data = ByteArray(buf.remaining()).also { buf.get(it) }
            val source = PlanarYUVLuminanceSource(data, plane.rowStride, img.height, 0, 0, img.width, img.height, false)
            val result = try { reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))) } catch (e: NotFoundException) { null } finally { reader.reset() }
            if (result != null && !done) {
                done = true
                runOnUiThread { setResult(Activity.RESULT_OK, Intent().putExtra("text", result.text)); finish() }
            }
        } finally {
            img.close()
        }
    }

    override fun onDestroy() {
        analysisExec.shutdown()
        super.onDestroy()
    }
}
