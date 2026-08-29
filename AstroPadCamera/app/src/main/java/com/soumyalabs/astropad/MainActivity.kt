package com.soumyalabs.astropad

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

class MainActivity : ComponentActivity() {

    private val cameraPermission =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            // Camera permission result
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (checkSelfPermission(Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }

        setContent {
            MaterialTheme {
                AstroPadCameraApp()
            }
        }
    }
}

@Composable
fun AstroPadCameraApp() {

    var iso by remember { mutableStateOf("AUTO") }
    var shutter by remember { mutableStateOf("AUTO") }
    var focus by remember { mutableStateOf("AUTO") }
    var frames by remember { mutableStateOf(20) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black
    ) {

        Box(
            modifier = Modifier.fillMaxSize()
        ) {

            // Camera preview area
            AndroidView(
                modifier = Modifier.fillMaxSize(),

                factory = { context ->
                    FrameLayout(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                }
            )

            // Top information bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {

                Text(
                    text = "ASTROPAD",
                    color = Color.White
                )

                Text(
                    text = "RAW",
                    color = Color.White
                )

                Text(
                    text = "${frames} FRAMES",
                    color = Color.White
                )
            }

            // Pro controls
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.75f))
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {

                    ProControl(
                        title = "ISO",
                        value = iso,
                        onClick = {
                            iso = when (iso) {
                                "AUTO" -> "100"
                                "100" -> "200"
                                "200" -> "400"
                                "400" -> "800"
                                "800" -> "1600"
                                "1600" -> "3200"
                                else -> "AUTO"
                            }
                        }
                    )

                    ProControl(
                        title = "SHUTTER",
                        value = shutter,
                        onClick = {
                            shutter = when (shutter) {
                                "AUTO" -> "1/30"
                                "1/30" -> "1/10"
                                "1/10" -> "1s"
                                "1s" -> "5s"
                                "5s" -> "10s"
                                "10s" -> "20s"
                                "20s" -> "30s"
                                else -> "AUTO"
                            }
                        }
                    )

                    ProControl(
                        title = "FOCUS",
                        value = focus,
                        onClick = {
                            focus = when (focus) {
                                "AUTO" -> "FAR"
                                "FAR" -> "∞"
                                else -> "AUTO"
                            }
                        }
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {

                    Button(
                        onClick = {
                            frames = when (frames) {
                                20 -> 50
                                50 -> 100
                                100 -> 200
                                else -> 20
                            }
                        }
                    ) {
                        Text("Frames: $frames")
                    }

                    Button(
                        modifier = Modifier.size(100.dp),
                        onClick = {
                            // Capture stack will be connected here
                        }
                    ) {
                        Text("CAPTURE")
                    }
                }
            }
        }
    }
}

@Composable
fun ProControl(
    title: String,
    value: String,
    onClick: () -> Unit
) {

    Button(
        onClick = onClick
    ) {

        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            Text(
                text = title
            )

            Text(
                text = value
            )
        }
    }
}
