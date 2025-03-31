package com.example.android.cameraxextensions.viewmodel

import android.app.Application
import android.os.Environment
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.core.content.ContextCompat
import com.example.android.cameraxextensions.model.CameraUiState
import com.example.android.cameraxextensions.repository.ImageCaptureRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import java.io.File

class CameraExtensionsViewModel(
    private val application: Application,
    private val imageCaptureRepository: ImageCaptureRepository
) : ViewModel() {

    private companion object {
        const val TAG = "CameraExtensionsViewModel"
    }

    private val cameraProviderFuture = ProcessCameraProvider.getInstance(application.applicationContext)
    private lateinit var cameraProvider: ProcessCameraProvider
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null

    private val _cameraUiState: MutableStateFlow<CameraUiState> =
        MutableStateFlow(CameraUiState(lensFacing = CameraSelector.LENS_FACING_BACK))
    val cameraUiState: Flow<CameraUiState> = _cameraUiState

    init {
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
        }, ContextCompat.getMainExecutor(application.applicationContext))
    }

    fun startCapturing(lifecycleOwner: LifecycleOwner, imageCount: Int) {
        val cameraSelector = cameraLensToSelector(_cameraUiState.value.lensFacing ?: CameraSelector.LENS_FACING_BACK)

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

        camera = cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, imageCapture!!)

        camera?.cameraControl?.apply {
            // Implement focus metering
            val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)
            val point = factory.createPoint(0.5f, 0.5f) // Center focus
            val action = FocusMeteringAction.Builder(point).build()
            startFocusAndMetering(action)
        }

        camera?.cameraControl?.setLinearZoom(1.0f) // Optional zoom control
    }

    private fun cameraLensToSelector(lensFacing: Int?): CameraSelector {
        return CameraSelector.Builder()
            .requireLensFacing(lensFacing ?: CameraSelector.LENS_FACING_BACK) // Default to back if null
            .build()
    }

    fun capturePhoto() {
        val imageCapture = imageCapture ?: return

        val photoFile = File(
            application.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
            "${System.currentTimeMillis()}.jpg"
        )

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(application),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    Log.d(TAG, "Photo saved: ${photoFile.absolutePath}")
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exception.message}", exception)
                }
            }
        )
    }
}
