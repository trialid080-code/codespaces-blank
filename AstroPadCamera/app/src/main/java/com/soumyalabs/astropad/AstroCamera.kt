package com.soumyalabs.astropad

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.view.Surface
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.roundToLong

class AstroCamera(
    private val context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onStatus(message: String)
        fun onCaptureError(message: String)
        fun onPhotoSaved(uri: Uri)
    }

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val cameraId = "0"
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var pendingCapture = false
    private var iso: Int? = null
    private var exposureNs: Long? = null
    private var focusDistance: Float? = null
    private var characteristics: CameraCharacteristics? = null

    private val thread = HandlerThread("AstroCamera").apply { start() }
    private val handler = Handler(thread.looper)

    fun setPreview(texture: SurfaceTexture, width: Int, height: Int) {
        handler.post {
            if (previewSurface != null && camera != null) {
                closeSessionOnly()
                previewSurface?.release()
                previewSurface = null
            }
            try {
                characteristics = manager.getCameraCharacteristics(cameraId)
                val size = choosePreviewSize(width, height)
                texture.setDefaultBufferSize(size.width, size.height)
                previewSurface = Surface(texture)
                openCamera()
            } catch (e: Exception) {
                listener.onCaptureError("Preview setup failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun choosePreviewSize(viewWidth: Int, viewHeight: Int): android.util.Size {
        val chars = characteristics ?: manager.getCameraCharacteristics(cameraId).also { characteristics = it }
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw IllegalStateException("Camera stream configuration is unavailable")
        val sizes = map.getOutputSizes(SurfaceTexture::class.java).orEmpty()
        if (sizes.isEmpty()) throw IllegalStateException("Camera has no SurfaceTexture preview sizes")
        val targetRatio = viewWidth.toFloat() / viewHeight.coerceAtLeast(1).toFloat()
        return sizes.minByOrNull { size ->
            val ratio = size.width.toFloat() / size.height.toFloat()
            abs(ratio - targetRatio) * 10000f + abs(size.width - viewWidth) + abs(size.height - viewHeight)
        } ?: sizes[0]
    }

    private fun openCamera() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            listener.onCaptureError("Camera permission is not granted")
            return
        }
        try {
            characteristics = manager.getCameraCharacteristics(cameraId)
            val map = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: throw IllegalStateException("Camera stream configuration is unavailable")
            val jpeg = map.getOutputSizes(android.graphics.ImageFormat.JPEG).orEmpty()
                .maxByOrNull { it.width.toLong() * it.height }
                ?: throw IllegalStateException("Camera has no JPEG output size")

            imageReader?.close()
            imageReader = ImageReader.newInstance(jpeg.width, jpeg.height, android.graphics.ImageFormat.JPEG, 2).also {
                it.setOnImageAvailableListener({ reader ->
                    reader.acquireLatestImage()?.use { image -> saveJpeg(image) }
                }, handler)
            }

            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    camera = device
                    createSession()
                }
                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    if (camera === device) camera = null
                    listener.onCaptureError("Camera disconnected")
                }
                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    if (camera === device) camera = null
                    listener.onCaptureError("Camera error: $error")
                }
            }, handler)
        } catch (e: Exception) {
            listener.onCaptureError("Unable to open camera: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun createSession() {
        val device = camera ?: return
        val preview = previewSurface ?: run { listener.onCaptureError("Preview surface is unavailable"); return }
        val reader = imageReader ?: run { listener.onCaptureError("JPEG reader is unavailable"); return }
        try {
            device.createCaptureSession(listOf(preview, reader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (camera !== device) {
                        s.close()
                        return
                    }
                    session = s
                    try {
                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(preview)
                            applyManualControls(this)
                            set(CaptureRequest.CONTROL_AF_MODE, if (focusDistance != null) CameraMetadata.CONTROL_AF_MODE_OFF else CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            set(CaptureRequest.CONTROL_AE_MODE, if (manualExposureActive()) CameraMetadata.CONTROL_AE_MODE_OFF else CameraMetadata.CONTROL_AE_MODE_ON)
                        }
                        s.setRepeatingRequest(request.build(), null, handler)
                        listener.onStatus("Camera ready")
                    } catch (e: Exception) {
                        listener.onCaptureError("Preview request failed: ${e.message ?: e.javaClass.simpleName}")
                    }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (session === s) session = null
                    listener.onCaptureError("Camera session configuration failed")
                }
                override fun onClosed(s: CameraCaptureSession) {
                    if (session === s) session = null
                }
            }, handler)
        } catch (e: Exception) {
            listener.onCaptureError("Unable to create camera session: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun setIso(value: Int?) {
        iso = value?.let { requested ->
            characteristics?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { range ->
                requested.coerceIn(range.lower, range.upper)
            }
        }
        restartPreview()
    }

    fun setShutterSeconds(value: Double?) {
        exposureNs = value?.let { seconds ->
            characteristics?.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { range ->
                (seconds * 1_000_000_000.0).roundToLong().coerceIn(range.lower, range.upper)
            }
        }
        restartPreview()
    }

    fun setFocusDistance(value: Float?) {
        val max = characteristics?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        focusDistance = value?.takeIf { max > 0f }?.coerceIn(0f, max)
        restartPreview()
    }

    private fun manualExposureActive() = iso != null || exposureNs != null

    private fun applyManualControls(builder: CaptureRequest.Builder) {
        characteristics?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { range ->
            iso?.let { builder.set(CaptureRequest.SENSOR_SENSITIVITY, it.coerceIn(range.lower, range.upper)) }
        }
        characteristics?.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { range ->
            exposureNs?.let { builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, it.coerceIn(range.lower, range.upper)) }
        }
        characteristics?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)?.let { max ->
            if (max > 0f) focusDistance?.let { builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, it.coerceIn(0f, max)) }
        }
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
    }

    private fun restartPreview() {
        handler.post {
            val d = camera ?: return@post
            val s = session ?: return@post
            val preview = previewSurface ?: return@post
            try {
                val request = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(preview)
                    applyManualControls(this)
                    set(CaptureRequest.CONTROL_AF_MODE, if (focusDistance != null) CameraMetadata.CONTROL_AF_MODE_OFF else CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    set(CaptureRequest.CONTROL_AE_MODE, if (manualExposureActive()) CameraMetadata.CONTROL_AE_MODE_OFF else CameraMetadata.CONTROL_AE_MODE_ON)
                }
                s.setRepeatingRequest(request.build(), null, handler)
            } catch (e: Exception) {
                listener.onCaptureError("Unable to apply camera controls: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun capture() {
        handler.post {
            val d = camera ?: run { listener.onCaptureError("Camera is not open"); return@post }
            val s = session ?: run { listener.onCaptureError("Camera preview is not ready"); return@post }
            val reader = imageReader ?: run { listener.onCaptureError("JPEG reader is not ready"); return@post }
            if (pendingCapture) return@post
            pendingCapture = true
            try {
                val request = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface)
                    applyManualControls(this)
                    set(CaptureRequest.CONTROL_AF_MODE, if (focusDistance != null) CameraMetadata.CONTROL_AF_MODE_OFF else CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    set(CaptureRequest.CONTROL_AE_MODE, if (manualExposureActive()) CameraMetadata.CONTROL_AE_MODE_OFF else CameraMetadata.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.JPEG_ORIENTATION, 0)
                }
                s.capture(request.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                        listener.onStatus("Saving JPEG…")
                    }
                    override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CameraCaptureSession.CaptureFailure) {
                        pendingCapture = false
                        listener.onCaptureError("JPEG capture failed (reason ${failure.reason})")
                    }
                }, handler)
                listener.onStatus("Capturing JPEG…")
            } catch (e: Exception) {
                pendingCapture = false
                listener.onCaptureError("Capture request failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun saveJpeg(image: Image) {
        try {
            val buffer: ByteBuffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "Astro_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AstroPadCamera")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore insert returned null")
            try {
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: throw IllegalStateException("Unable to open output stream")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                }
                pendingCapture = false
                listener.onPhotoSaved(uri)
                listener.onStatus("Photo saved")
            } catch (e: Exception) {
                context.contentResolver.delete(uri, null, null)
                throw e
            }
        } catch (e: Exception) {
            pendingCapture = false
            listener.onCaptureError("Failed to save JPEG: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun close() {
        handler.post {
            closeSessionOnly()
            camera?.close()
            camera = null
            imageReader?.close()
            imageReader = null
            previewSurface?.release()
            previewSurface = null
            thread.quitSafely()
        }
    }

    private fun closeSessionOnly() {
        session?.close()
        session = null
    }
}
