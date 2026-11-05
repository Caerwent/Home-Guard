package org.grise.homeguard.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.grise.homeguard.util.Constants
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import androidx.core.graphics.createBitmap
import kotlin.time.Duration.Companion.milliseconds

class CameraHelper(private val context: Context) {
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private suspend fun getCameraProvider(): ProcessCameraProvider = suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                cont.resume(future.get())
            } catch (e: Exception) {
                cont.resumeWith(Result.failure(e))
            }
        }, ContextCompat.getMainExecutor(context))
    }

    suspend fun takePhoto(
        lifecycleOwner: LifecycleOwner,
        cameraId: String,
        quality: Int,
        onPhotoCaptured: (File) -> Unit,
        onError: (Exception) -> Unit
    ) {
        try {
            val cameraProvider = getCameraProvider()
            
            withContext(Dispatchers.Main) {
                val index = cameraId.toIntOrNull() ?: 0
                val availableCameras: List<CameraInfo> = cameraProvider.availableCameraInfos
                if (index >= availableCameras.size) {
                    onError(Exception("Camera ID $cameraId not found"))
                    return@withContext
                }
                
                val cameraInfo = availableCameras[index]
                val cameraSelector = CameraSelector.Builder()
                    .addCameraFilter { it.filter { info -> info == cameraInfo } }
                    .build()

                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1280, 720),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()

                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setResolutionSelector(resolutionSelector)
                    .setJpegQuality(quality)
                    .build()

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    imageCapture
                )

                val photoFile = File(
                    context.externalCacheDir,
                    "monitoring_${cameraId}_${System.currentTimeMillis()}.jpg"
                )

                val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

                suspendCancellableCoroutine { cont ->
                    imageCapture.takePicture(
                        outputOptions,
                        cameraExecutor,
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                onPhotoCaptured(photoFile)
                                if (cont.isActive) cont.resume(Unit)
                            }

                            override fun onError(exception: ImageCaptureException) {
                                onError(exception)
                                if (cont.isActive) cont.resume(Unit)
                            }
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(Constants.TAG, "Error in takePhoto for camera $cameraId", e)
            onError(e)
        }
    }

    suspend fun takePhotoWithTimeout(
        lifecycleOwner: LifecycleOwner,
        cameraId: String,
        quality: Int,
        onPhotoCaptured: (File) -> Unit,
        onError: (Exception) -> Unit,
        timeoutMs: Long = 10000
    ) {
        try {
            withTimeoutOrNull(timeoutMs.milliseconds) {
                takePhoto(lifecycleOwner, cameraId, quality, onPhotoCaptured, onError)
            } ?: run {
                val timeoutException = Exception("Camera $cameraId capture timed out after ${timeoutMs}ms")
                Log.e(Constants.TAG, "Camera capture timeout", timeoutException)
                onError(timeoutException)
            }
        } catch (e: Exception) {
            Log.e(Constants.TAG, "Error in takePhotoWithTimeout for camera $cameraId", e)
            onError(e)
        }
    }

    fun combineImages(labeledFiles: List<Pair<File, String>>, quality: Int, failedCameras: List<String> = emptyList()): File? {
        if (labeledFiles.isEmpty()) return null

        return try {
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
                inJustDecodeBounds = true
            }
            
            labeledFiles.forEach { BitmapFactory.decodeFile(it.first.absolutePath, options) }
            
            val targetHeight = Constants.IMAGE_STITCH_TARGET_HEIGHT
            options.inSampleSize = if (options.outHeight > targetHeight) {
                Integer.highestOneBit(options.outHeight / targetHeight)
            } else 1
            options.inJustDecodeBounds = false

            val bitmapsWithLabels = labeledFiles.mapNotNull { (file, label) ->
                try { 
                    val bm = BitmapFactory.decodeFile(file.absolutePath, options) 
                    if (bm != null) Pair(bm, label) else null
                } catch (e: Exception) { 
                    Log.e(Constants.TAG, "Error decoding file ${file.name}", e)
                    null 
                }
            }

            val failedBitmaps = failedCameras.map { cameraId ->
                val placeholderWidth = 320
                val placeholderHeight = 240
                val placeholder = createBitmap(placeholderWidth, placeholderHeight, Bitmap.Config.RGB_565)
                val canvas = Canvas(placeholder)
                canvas.drawColor(android.graphics.Color.BLACK)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.RED
                    textSize = 24f
                    textAlign = Paint.Align.CENTER
                    setShadowLayer(2f, 1f, 1f, android.graphics.Color.BLACK)
                }
                canvas.drawText("Camera $cameraId", placeholderWidth / 2f, placeholderHeight / 2f - 15f, paint)
                canvas.drawText("FAILED", placeholderWidth / 2f, placeholderHeight / 2f + 15f, paint)
                Pair(placeholder, cameraId)
            }
            
            val allBitmaps = bitmapsWithLabels + failedBitmaps
            
            if (allBitmaps.isEmpty()) return null

            val totalWidth = allBitmaps.sumOf { it.first.width }
            val maxHeight = allBitmaps.maxOf { it.first.height }

            val combined = createBitmap(totalWidth, maxHeight, Bitmap.Config.RGB_565)
            val canvas = Canvas(combined)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                textSize = 24f
                textAlign = Paint.Align.RIGHT
                setShadowLayer(2f, 1f, 1f, android.graphics.Color.BLACK)
            }

            var currentX = 0f
            allBitmaps.forEach { (bitmap, label) ->
                if (!bitmap.isRecycled) {
                    canvas.drawBitmap(bitmap, currentX, 0f, paint)
                    
                    val labelX = (currentX + bitmap.width) - 10f
                    val labelY = bitmap.height - 10f
                    canvas.drawText("Camera $label", labelX, labelY, textPaint)
                    
                    currentX += bitmap.width
                    bitmap.recycle()
                }
            }

            val outputFile = File(context.externalCacheDir, "combined_${System.currentTimeMillis()}.jpg")
            outputFile.outputStream().use { 
                combined.compress(Bitmap.CompressFormat.JPEG, quality, it)
            }
            combined.recycle()
            outputFile
        } catch (e: Exception) {
            Log.e(Constants.TAG, "Error in combineImages", e)
            null
        }
    }

    fun shutdown() {
        cameraExecutor.shutdown()
    }
}
