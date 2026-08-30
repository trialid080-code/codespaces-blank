package com.soumyalabs.astropad

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import kotlin.math.max
import kotlin.math.min

class AstroCamera(
    private val context: Context,
    private val executor: Executor,
    private val listener: Listener
) {
    interface Listener {
        fun onStatus(message: String)
        fun onCaptureSaved(uri: Uri)
        fun onCaptureError(message: String)
    }

    private val manager = context.getSystemService(CameraManager::class.java)
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var imageReader: ImageReader? = null
    private var characteristics: CameraCharacteristics? = null
    private var cameraId: String? = null
    private var pendingCapture = false
    private var iso: Int? = null
    private var exposureNs: Long? = null
    private var focusDistance: Float? = null

    fun attachPreview(surface: Surface) {
        previewSurface = surface
        if (camera != null) createSession()
    }

    fun detachPreview(surface: Surface? = null) {
        if (surface == null || previewSurface === surface) previewSurface = null
        session?.close()
        session = null
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (camera != null) return
        try {
            val id = manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: "0"
            cameraId = id
            val chars = manager.getCameraCharacteristics(id)
            characteristics = chars
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: throw IllegalStateException("Camera $id has no stream configuration map")
            val jpegSize = chooseJpegSize(map.getOutputSizes(ImageFormat.JPEG))
            imageReader = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ reader ->
                    val image = reader.acquireNextImage() ?: return@setOnImageAvailableListener
                    image.use {
                        try {
                            val buffer: ByteBuffer = it.planes[0].buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            saveJpeg(bytes)
                        } catch (t: Throwable) {
                            listener.onCaptureError("Saving JPEG failed: ${t.message ?: t.javaClass.simpleName}")
                        }
                    }
                }, null)
            }
            listener.onStatus("Opening back camera $id (${jpegSize.width}×${jpegSize.height})…")
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    camera = device
                    listener.onStatus("Camera $id opened")
                    createSession()
                }

                override fun onDisconnected(device: CameraDevice) {
                    listener.onStatus("Camera disconnected")
                    device.close()
                    if (camera === device) camera = null
                }

                override fun onError(device: CameraDevice, error: Int) {
                    listener.onCaptureError("Camera error $error")
                    device.close()
                    if (camera === device) camera = null
                }
            }, null)
        } catch (t: Throwable) {
            listener.onCaptureError("Camera start failed: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun chooseJpegSize(sizes: Array<android.util.Size>): android.util.Size {
        val preferred = sizes.firstOrNull { it.width == 4208 && it.height == 3120 }
        return preferred ?: sizes.maxByOrNull { it.width.toLong() * it.height } ?: throw IllegalStateException("No JPEG output size")
    }

    private fun createSession() {
        val device = camera ?: return
        val preview = previewSurface ?: return
        val readerSurface = imageReader?.surface ?: return
        session?.close()
        try {
            device.createCaptureSession(
                listOf(preview, readerSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(newSession: CameraCaptureSession) {
                        if (camera !== device) {
                            newSession.close()
                            return
                        }
                        session = newSession
                        try {
                            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(preview)
                                applyManualControls(this)
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                set(CaptureRequest.CONTROL_AE_MODE, if (iso != null || exposureNs != null) CaptureRequest.CONTROL_AE_MODE_OFF else CaptureRequest.CONTROL_AE_MODE_ON)
                            }
                            newSession.setRepeatingRequest(request.build(), null, null)
                            listener.onStatus("Preview running")
                        } catch (t: Throwable) {
                            listener.onCaptureError("Preview request failed: ${t.message ?: t.javaClass.simpleName}")
                        }
                    }

                    override fun onConfigureFailed(failedSession: CameraCaptureSession) {
                        listener.onCaptureError("Camera session configuration failed")
                    }
                }, null
            )
        } catch (t: Throwable) {
            listener.onCaptureError("Creating camera session failed: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    fun setIso(value: Int?) {
        val range = characteristics?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        iso = value?.takeIf { range != null && it in range }
        if (value != null && iso == null) listener.onStatus("ISO $value is not supported")
        restartPreview()
    }

    fun setExposureSeconds(seconds: Double?) {
        val range = characteristics?.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val requested = seconds?.let { (it * 1_000_000_000.0).toLong() }
        exposureNs = requested?.takeIf { range != null && it in range }
        if (requested != null && exposureNs == null) listener.onStatus("Shutter ${seconds}s is not supported")
        restartPreview()
    }

    fun setFocusDistance(value: Float?) {
        val range = characteristics?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        focusDistance = value?.takeIf { range != null && range > 0f && it in 0f..range }
        if (value != null && focusDistance == null) listener.onStatus("Manual focus is not supported")
        restartPreview()
    }

    private fun restartPreview() {
        val s = session ?: return
        val p = previewSurface ?: return
        val d = camera ?: return
        try {
            val request = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(p)
                applyManualControls(this)
                set(CaptureRequest.CONTROL_AF_MODE, if (focusDistance != null) CaptureRequest.CONTROL_AF_MODE_OFF else CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, if (iso != null || exposureNs != null) CaptureRequest.CONTROL_AE_MODE_OFF else CaptureRequest.CONTROL_AE_MODE_ON)
            }
            s.setRepeatingRequest(request.build(), null, null)
        } catch (t: Throwable) {
            listener.onCaptureError("Updating controls failed: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun applyManualControls(builder: CaptureRequest.Builder) {
        iso?.let { builder.set(CaptureRequest.SENSOR_SENSITIVITY, it) }
        exposureNs?.let { builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, it) }
        focusDistance?.let { builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, it) }
        if (focusDistance != null) builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
    }

    fun capture() {
        val d = camera ?: run { listener.onCaptureError("Camera is not open"); return }
        val s = session ?: run { listener.onCaptureError("Camera preview is not ready"); return }
        val reader = imageReader ?: run { listener.onCaptureError("JPEG reader is not ready"); return }
        if (pendingCapture) return
        pendingCapture = true
        try {
            val request = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                applyManualControls(this)
                set(CaptureRequest.CONTROL_AF_MODE, if (focusDistance != null) CaptureRequest.CONTROL_AF_MODE_OFF else CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, if (iso != null || exposureNs != null) CaptureRequest.CONTROL_AE_MODE_OFF else CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.JPEG_ORIENTATION, 0)
            }
            s.capture(request.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CameraCaptureSession.CaptureFailure) {
                    pendingCapture = false
                    listener.onCaptureError("JPEG capture failed: ${failure.reason}")
                }
            }, null)
            listener.onStatus("Capturing JPEG…")
        } catch (t: Throwable) {
            pendingCapture = false
            listener.onCaptureError("Capture request failed: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun saveJpeg(bytes: ByteArray) {
        val name = "Astro_${System.currentTimeMillis()}.jpg"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/AstroPadCamera")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore insert returned null")
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IllegalStateException("Could not open image output")
            if (Build.VERSION.SDK_INT >= 29) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            }
            pendingCapture = false
            executor.execute { listener.onCaptureSaved(uri) }
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            pendingCapture = false
            throw t
        }
    }

    fun close() {
        pendingCapture = false
        session?.close()
        session = null
        camera?.close()
        camera = null
        imageReader?.close()
        imageReader = null
        previewSurface = null
    }
}
