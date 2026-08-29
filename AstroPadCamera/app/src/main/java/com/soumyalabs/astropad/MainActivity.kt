package com.soumyalabs.astropad

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private val camera by lazy { AstroCamera(this) }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) camera.start()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AstroScreen(camera) }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) camera.start()
        else permission.launch(Manifest.permission.CAMERA)
    }
    override fun onDestroy() { camera.close(); super.onDestroy() }
}

@Composable
fun AstroScreen(camera: AstroCamera) {
    var iso by remember { mutableFloatStateOf(800f) }
    var shutter by remember { mutableFloatStateOf(5f) }
    var focus by remember { mutableFloatStateOf(0.0f) }
    var count by remember { mutableIntStateOf(30) }
    var captured by remember { mutableIntStateOf(0) }
    var raw by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf("READY • Xiaomi Pad 7") }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { SurfaceView(it).also { v ->
            v.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(h: SurfaceHolder) { camera.attachPreview(h.surface) }
                override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hgt: Int) {}
                override fun surfaceDestroyed(h: SurfaceHolder) { camera.detachPreview() }
            })
        } }, modifier = Modifier.fillMaxSize())
        Column(Modifier.fillMaxHeight().width(330.dp).align(Alignment.CenterEnd).background(Color(0xCC080808)).padding(18.dp)) {
            Text("ASTROPAD", color=Color.White, fontSize=22.sp)
            Text("ASTRO • PRO", color=Color.LightGray, fontSize=12.sp)
            Spacer(Modifier.height(14.dp))
            Text("ISO  ${iso.toInt()}", color=Color.White)
            Slider(value=iso, onValueChange={iso=it}, valueRange=50f..6400f)
            Text("SHUTTER  ${formatSec(shutter)}", color=Color.White)
            Slider(value=shutter, onValueChange={shutter=it}, valueRange=0.001f..30f)
            Text("FOCUS  ${"%.2f".format(focus)}", color=Color.White)
            Slider(value=focus, onValueChange={focus=it}, valueRange=0f..20f)
            Text("FRAMES  $count", color=Color.White)
            Slider(value=count.toFloat(), onValueChange={count=it.toInt()}, valueRange=1f..200f, steps=198)
            Row(verticalAlignment=Alignment.CenterVertically) {
                Checkbox(checked=raw, onCheckedChange={raw=it}); Text("RAW / DNG", color=Color.White)
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick={
                captured=0; message="CAPTURING…"
                camera.captureSequence(iso.toInt(), shutter, focus, count, raw) { n, done -> captured=n; if(done) message="DONE • $n frames" }
            }, modifier=Modifier.fillMaxWidth()) { Text("CAPTURE STACK") }
            Spacer(Modifier.height(10.dp))
            Text(message, color=Color.White, fontSize=12.sp)
            Text("RAW pipeline • star registration • stacking coming next", color=Color.Gray, fontSize=11.sp)
        }
        Text("ISO ${iso.toInt()}   ${formatSec(shutter)}   FOCUS ${"%.2f".format(focus)}   $captured/$count", color=Color.White, modifier=Modifier.align(Alignment.BottomStart).padding(18.dp))
    }
}
fun formatSec(v: Float) = if (v < 1f) "1/${(1f/v).toInt()}s" else "%.1fs".format(v)
