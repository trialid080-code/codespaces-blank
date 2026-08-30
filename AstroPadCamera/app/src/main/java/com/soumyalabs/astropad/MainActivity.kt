package com.soumyalabs.astropad

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Bundle
import android.view.Surface
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface as ComposeSurface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.util.concurrent.Executors

class MainActivity : ComponentActivity(), AstroCamera.Listener {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var cameraController: AstroCamera
    private var textureView: TextureView? = null
    private var previewSurface: Surface? = null

    private var status by mutableStateOf("Waiting for camera permission…")
    private var isoLabel by mutableStateOf("AUTO")
    private var shutterLabel by mutableStateOf("AUTO")
    private var focusLabel by mutableStateOf("AUTO")
    private var frames by mutableStateOf(1)

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            status = "Camera permission granted"
            textureView?.let { if (it.isAvailable) startPreview(it) }
        } else {
            status = "Camera permission denied"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraController = AstroCamera(this, executor, this)
        setContent {
            MaterialTheme { AstroPadCameraApp() }
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermission.launch(Manifest.permission.CAMERA)
        } else {
            status = "Camera permission granted"
        }
    }

    private fun startPreview(view: TextureView) {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val surfaceTexture = view.surfaceTexture ?: return
        surfaceTexture.setDefaultBufferSize(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1))
        previewSurface?.release()
        previewSurface = Surface(surfaceTexture)
        cameraController.attachPreview(previewSurface!!)
        cameraController.start()
    }

    private fun onPreviewDestroyed(surface: Surface) {
        cameraController.detachPreview(surface)
        if (previewSurface === surface) previewSurface = null
        surface.release()
    }

    override fun onDestroy() {
        cameraController.close()
        executor.shutdown()
        super.onDestroy()
    }

    override fun onStatus(message: String) {
        runOnUiThread { status = message }
    }

    override fun onCaptureSaved(uri: Uri) {
        runOnUiThread { status = "Saved: $uri" }
    }

    override fun onCaptureError(message: String) {
        runOnUiThread { status = "ERROR: $message" }
    }

    @androidx.compose.runtime.Composable
    private fun AstroPadCameraApp() {
        ComposeSurface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        TextureView(context).also { view ->
                            textureView = view
                            view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                                    startPreview(view)
                                }
                                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                                    surface.setDefaultBufferSize(width.coerceAtLeast(1), height.coerceAtLeast(1))
                                }
                                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                                    previewSurface?.let(::onPreviewDestroyed)
                                    return true
                                }
                                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                            }
                        }
                    },
                    update = { textureView = it }
                )

                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.65f)).padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("ASTROPAD", color = Color.White)
                        Text("JPEG", color = Color.White)
                        Text("${frames} FRAME${if (frames == 1) "" else "S"}", color = Color.White)
                    }

                    Text(
                        text = status,
                        color = if (status.startsWith("ERROR:")) Color.Red else Color.White,
                        modifier = Modifier.padding(12.dp)
                    )

                    Column(
                        modifier = Modifier.align(Alignment.CenterHorizontally).fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.Bottom,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.75f)).padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            ProControl("ISO", isoLabel) {
                                val next = when (isoLabel) { "AUTO" -> 100; "100" -> 200; "200" -> 400; "400" -> 800; "800" -> 1600; "1600" -> 3200; else -> null }
                                isoLabel = next?.toString() ?: "AUTO"
                                cameraController.setIso(next)
                            }
                            ProControl("SHUTTER", shutterLabel) {
                                val next = when (shutterLabel) { "AUTO" -> 1.0 / 30.0; "1/30" -> 0.1; "1/10" -> 1.0; "1s" -> 5.0; "5s" -> 10.0; "10s" -> 20.0; "20s" -> 30.0; else -> null }
                                shutterLabel = when (next) { null -> "AUTO"; 1.0 / 30.0 -> "1/30"; 0.1 -> "1/10"; 1.0 -> "1s"; 5.0 -> "5s"; 10.0 -> "10s"; 20.0 -> "20s"; else -> "30s" }
                                cameraController.setExposureSeconds(next)
                            }
                            ProControl("FOCUS", focusLabel) {
                                val next = when (focusLabel) { "AUTO" -> 0f; "0.0" -> null; else -> null }
                                focusLabel = if (next == null) "AUTO" else "∞"
                                cameraController.setFocusDistance(next)
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.75f)).padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(onClick = { frames = if (frames == 1) 5 else if (frames == 5) 10 else 1 }) { Text("Frames: $frames") }
                            Button(modifier = Modifier.size(100.dp), onClick = { cameraController.capture() }) { Text("CAPTURE") }
                        }
                    }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun ProControl(title: String, value: String, onClick: () -> Unit) {
        Button(onClick = onClick) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title)
                Text(value)
            }
        }
    }
}
