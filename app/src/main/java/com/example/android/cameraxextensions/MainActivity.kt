package com.example.android.cameraxextensions

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.core.*
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import com.example.android.cameraxextensions.repository.ImageCaptureRepository
import com.example.android.cameraxextensions.viewmodel.CameraExtensionsViewModel
import com.example.android.cameraxextensions.viewmodel.CameraExtensionsViewModelFactory
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

@ExperimentalCamera2Interop
class MainActivity : AppCompatActivity() {
    private lateinit var viewModel: CameraExtensionsViewModel
    private var imageCount: Int = 7
    private var imageCapture: ImageCapture? = null
    private lateinit var previewView: PreviewView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        checkAndRequestPermissions()

        // Initialize ViewModel
        viewModel = ViewModelProvider(
            this,
            CameraExtensionsViewModelFactory(
                application,
                ImageCaptureRepository.create(applicationContext)
            )
        )[CameraExtensionsViewModel::class.java]

        val inputCount = findViewById<EditText>(R.id.input_count)
        val startButton = findViewById<Button>(R.id.start_capture)

        startButton.setOnClickListener {
            val inputText = inputCount.text.toString()
            imageCount = inputText.toIntOrNull() ?: 7
            startCapturingImages()
        }
    }

    private fun checkAndRequestPermissions() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
        } else {
            startCamera()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                Toast.makeText(this, "Camera permission is required", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .setTargetRotation(windowManager.defaultDisplay.rotation)
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            val builder = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .setTargetRotation(windowManager.defaultDisplay.rotation)

            val camera2Interop = Camera2Interop.Extender(builder)
            camera2Interop.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.SENSOR_SENSITIVITY, 50) // ISO 50
            camera2Interop.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.SENSOR_EXPOSURE_TIME, 30000000000L) // 30s shutter speed
            camera2Interop.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.LENS_FOCUS_DISTANCE, 0f) // Infinity focus

            imageCapture = builder.build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture!!)
            } catch (exc: Exception) {
                Log.e("CameraX", "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startCapturingImages() {
        val imageCapture = imageCapture ?: run {
            Toast.makeText(this, "Camera is not initialized", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            repeat(imageCount) {
                capturePhoto()
            }
        }
    }

    private fun capturePhoto() {
        val imageCapture = imageCapture ?: return

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "IMG_$timestamp.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/CameraXPhotos")
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    Log.d("CameraX", "Photo captured and saved to DCIM/CameraXPhotos")
                    Toast.makeText(this@MainActivity, "Photo saved to DCIM/CameraXPhotos!", Toast.LENGTH_SHORT).show()
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("CameraX", "Photo capture failed: ${exception.message}", exception)
                    Toast.makeText(this@MainActivity, "Photo capture failed", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    companion object {
        private const val CAMERA_PERMISSION_REQUEST = 1001
    }
}
