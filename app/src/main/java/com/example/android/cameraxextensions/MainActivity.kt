package com.example.android.cameraxextensions

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
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
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import okio.IOException
import java.io.File
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
        val fileName = "IMG_$timestamp.jpg"
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
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
                    Log.d("CameraX", "Photo saved to DCIM/CameraXPhotos")
                    Toast.makeText(this@MainActivity, "Photo saved!", Toast.LENGTH_SHORT).show()

                    // Send the image to the server
                    val imageUri = outputFileResults.savedUri ?: return
                    sendImageToServer(imageUri, this@MainActivity) // Pass URI instead of String

                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("CameraX", "Photo capture failed: ${exception.message}", exception)
                    Toast.makeText(this@MainActivity, "Photo capture failed", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
    private fun getRealPathFromURI(uri: Uri, context: Context): String? {
        val projection = arrayOf(MediaStore.Images.Media.DATA)
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            if (cursor.moveToFirst()) {
                return cursor.getString(columnIndex)
            }
        }

        // Fallback for Android 10+ using a temporary file
        return uri.let { safeUri ->
            val inputStream = context.contentResolver.openInputStream(safeUri) ?: return null
            val tempFile = File(context.cacheDir, "temp_image.jpg")
            tempFile.outputStream().use { outputStream -> inputStream.copyTo(outputStream) }
            tempFile.absolutePath
        }
    }


    private fun sendImageToServer(imageUri: Uri, context: Context) {
        val imagePath = getRealPathFromURI(imageUri, context) ?: return
        val file = File(imagePath)

        val requestBody = file.asRequestBody("image/jpeg".toMediaTypeOrNull())
        val multipartBody = MultipartBody.Part.createFormData("file", file.name, requestBody)

        val request = Request.Builder()
            .url("http://192.168.1.38:5000/upload")
            .post(MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", file.name, requestBody)
                .build())
            .build()

        val client = OkHttpClient()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("Upload", "Failed to upload image: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                Log.d("Upload", "Server response: ${response.code} - ${response.body?.string()}")
            }
        })
    }






    companion object {
        private const val CAMERA_PERMISSION_REQUEST = 1001
    }
}
