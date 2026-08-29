package com.soumyalabs.astropad

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Environment
import android.view.Surface
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger

class AstroCamera(private val context: Context) {
    private val thread=HandlerThread("AstroCamera").apply{start()}
    private val handler=Handler(thread.looper)
    private var manager=context.getSystemService(CameraManager::class.java)
    private var device: CameraDevice?=null
    private var session: CameraCaptureSession?=null
    private var preview: Surface?=null
    private var reader: ImageReader?=null
    private var characteristics: CameraCharacteristics?=null
    private var callback: ((Int,Boolean)->Unit)?=null
    private var total=0; private var done=AtomicInteger(0)

    @SuppressLint("MissingPermission") fun start(){
        val id=manager.cameraIdList.firstOrNull{ manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING)==CameraCharacteristics.LENS_FACING_BACK } ?: return
        characteristics=manager.getCameraCharacteristics(id)
        val size=characteristics!!.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!.getOutputSizes(ImageFormat.RAW_SENSOR).maxByOrNull{it.width*it.height}
        if(size!=null) reader=ImageReader.newInstance(size.width,size.height,ImageFormat.RAW_SENSOR,8).also{ r->r.setOnImageAvailableListener({ir->
            ir.acquireLatestImage()?.use { img ->
                val file=File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),"astro_${System.currentTimeMillis()}_${done.incrementAndGet()}.raw")
                FileOutputStream(file).use { out -> img.planes[0].buffer.let{b-> val bytes=ByteArray(b.remaining()); b.get(bytes); out.write(bytes) } }
                val n=done.get(); callback?.invoke(n,n>=total)
            }
        },handler)}
        manager.openCamera(id,object:CameraDevice.StateCallback(){override fun onOpened(c:CameraDevice){device=c; rebuild()};override fun onDisconnected(c:CameraDevice){c.close()};override fun onError(c:CameraDevice,e:Int){c.close()}},handler)
    }
    fun attachPreview(s:Surface){preview=s; rebuild()}; fun detachPreview(){preview=null}
    private fun rebuild(){val d=device ?: return; val p=preview ?: return; val r=reader?.surface ?: return; d.createCaptureSession(listOf(p,r),object:CameraCaptureSession.StateCallback(){override fun onConfigured(s:CameraCaptureSession){session=s; val req=d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW); req.addTarget(p); req.set(CaptureRequest.CONTROL_MODE,CameraMetadata.CONTROL_MODE_AUTO); s.setRepeatingRequest(req.build(),null,handler)};override fun onConfigureFailed(s:CameraCaptureSession){}},handler)}
    fun captureSequence(iso:Int,seconds:Float,focus:Float,count:Int,raw:Boolean,onProgress:(Int,Boolean)->Unit){
        val d=device ?: return; val s=session ?: return; val r=reader?.surface ?: return; callback=onProgress; total=count; done.set(0)
        repeat(count){ handler.postDelayed({
            val b=d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE); b.addTarget(r); if(preview!=null)b.addTarget(preview!!)
            b.set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_OFF); b.set(CaptureRequest.SENSOR_SENSITIVITY,iso); b.set(CaptureRequest.SENSOR_EXPOSURE_TIME,(seconds*1_000_000_000L).toLong()); b.set(CaptureRequest.LENS_FOCUS_DISTANCE,focus); b.set(CaptureRequest.CONTROL_AWB_MODE,CaptureRequest.CONTROL_AWB_MODE_OFF)
            s.capture(b.build(),null,handler)
        },it*150L)}
    }
    fun close(){session?.close();device?.close();reader?.close();thread.quitSafely()}
}
