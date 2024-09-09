// MainActivity.kt
package com.covi.camAIgo

import StreamingServer
import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.Image
import android.os.Bundle
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.media.ImageReader
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.Size
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.nio.ByteBuffer
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.concurrent.ConcurrentLinkedQueue
import java.io.BufferedInputStream



class FixedSizeConcurrentQueue<T>(private val maxSize: Int) {
    private val queue = ConcurrentLinkedQueue<T>()

    fun add(item: T) {
        synchronized(queue) {
            if (queue.size >= maxSize) {
                queue.poll()  // Remove the oldest item
            }
            queue.add(item)
        }
    }

    fun poll(): T? {
        synchronized(queue) {
            return queue.poll()
        }
    }

    fun clear() {
        synchronized(queue) {
            queue.clear()
        }
    }
}

class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private lateinit var startButton: Button
    private lateinit var textureView: TextureView
    private lateinit var imageView: ImageView
    private lateinit var cameraDevice: CameraDevice
    private lateinit var captureSession: CameraCaptureSession
    private lateinit var cameraManager: CameraManager
    private lateinit var streamingServer: StreamingServer
    private lateinit var imageReader: ImageReader
    private lateinit var backgroundThread: HandlerThread
    private lateinit var backgroundHandler: Handler
    private lateinit var portInput: EditText
    private lateinit var uriDisplay: TextView
    private lateinit var networkStreamUrlInput: EditText
    private lateinit var showNetworkStreamButton: Button
    private lateinit var resolutionSpinner: Spinner
    private lateinit var availableResolutions: List<Size>
    private lateinit var selectedResolution: Size

    private lateinit var cameraSelectorSpinner: Spinner
    private var selectedCameraId: String? = null
    private var cameraIdList: List<String> = listOf()


    private var isCameraRunning = false
    private var currentImage: Image? = null
    private var brightness = 1
    private var isShowingNetworkStream = false


    private lateinit var surfaceView: SurfaceView
    private lateinit var surfaceHolder: SurfaceHolder
    private var streamJob: Job? = null
    private var webSocketClient: WebSocketClient? = null
    private val imageQueue = FixedSizeConcurrentQueue<Bitmap>(1)
    private var drawJob: Job? = null

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        startButton = findViewById(R.id.startButton)
        textureView = findViewById(R.id.textureView)
        imageView = findViewById(R.id.imageView)
        networkStreamUrlInput = findViewById(R.id.networkStreamUrlInput)
        showNetworkStreamButton = findViewById(R.id.showNetworkStreamButton)
        portInput = findViewById(R.id.portInput)
        uriDisplay = findViewById(R.id.uriDisplay)

        resolutionSpinner = findViewById(R.id.resolutionSpinner)
        cameraSelectorSpinner = findViewById(R.id.cameraSelectorSpinner)

        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager


        initializeCamareSelectorSpinner()

        selectedCameraId?.let { initializeResolutionSpinner(it) }


        updateUriDisplay(true)

        // Add TextWatcher to portInput
        portInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                updateUriDisplay()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        startButton.setOnClickListener { startCamera() }
        showNetworkStreamButton.setOnClickListener { showNetworkStream() }

        // Keep the screen on while the app is running
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)


        surfaceView = findViewById(R.id.surfaceView)
        surfaceHolder = surfaceView.holder
        surfaceHolder.addCallback(this)


        requestCanWriteSettings(this)

        brightness = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 255)
    }

    private fun initializeCamareSelectorSpinner() {
        try {
            // Get a list of camera IDs and their characteristics
            cameraIdList = cameraManager.cameraIdList.toList()

            // Set up the camera selector spinner
            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, cameraIdList)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            cameraSelectorSpinner.adapter = adapter

            cameraSelectorSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                    selectedCameraId = cameraIdList[position]
                    initializeResolutionSpinner(selectedCameraId!!)
                }

                override fun onNothingSelected(parent: AdapterView<*>) {}
            }

            cameraSelectorSpinner.setSelection(0)

        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun initializeResolutionSpinner(cameraId: String) {
        try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            availableResolutions = map?.getOutputSizes(ImageFormat.JPEG)?.toList() ?: listOf(Size(640, 360))

            // Set up the spinner adapter for resolutions
            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, availableResolutions.map { "${it.width}x${it.height}" })
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            resolutionSpinner.adapter = adapter

            resolutionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                    selectedResolution = availableResolutions[position]
                }

                override fun onNothingSelected(parent: AdapterView<*>) {}
            }

            // Set default selected resolution
            selectedResolution = Size(640, 360)
            val defaultIndex = availableResolutions.indexOf(selectedResolution)
            if (defaultIndex >= 0) {
                resolutionSpinner.setSelection(defaultIndex)
            }

        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    @SuppressLint("ServiceCast", "DefaultLocale")
    private fun getDeviceIpAddress(): String {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork
            if (network != null) {
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    val ipAddress = wifiManager.connectionInfo.ipAddress
                    if (ipAddress != 0) {
                        return String.format(
                            "%d.%d.%d.%d",
                            ipAddress and 0xff,
                            (ipAddress shr 8) and 0xff,
                            (ipAddress shr 16) and 0xff,
                            (ipAddress shr 24) and 0xff
                        )
                    }
                }
            }
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            if (networkInfo != null && networkInfo.isConnected && networkInfo.type == ConnectivityManager.TYPE_WIFI) {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val ipAddress = wifiManager.connectionInfo.ipAddress
                if (ipAddress != 0) {
                    return String.format(
                        "%d.%d.%d.%d",
                        ipAddress and 0xff,
                        (ipAddress shr 8) and 0xff,
                        (ipAddress shr 16) and 0xff,
                        (ipAddress shr 24) and 0xff
                    )
                }
            }
        }

        return "Not connected to Wi-Fi"
    }

    private fun updateUriDisplay(changeall: Boolean = false) {
        val ipAddress = getDeviceIpAddress()

        val uri = if (ipAddress != "Not connected to Wi-Fi") {
            "http://$ipAddress:"
        } else {
            ipAddress // Just display the "Not connected to Wi-Fi" message
        }
        uriDisplay.text = uri

        if (uri != "Not connected to Wi-Fi" && changeall){
            val parts = ipAddress.split(".")
            val newIpAddress = parts.dropLast(1).joinToString(".") + ".1"
            val newUri = "http://$newIpAddress:8080"
            networkStreamUrlInput.setText(newUri)
        }


    }

    private fun showMainUI() {
        isShowingNetworkStream = false
        startButton.visibility = View.VISIBLE
        portInput.visibility = View.VISIBLE
        resolutionSpinner.visibility = View.VISIBLE
        cameraSelectorSpinner.visibility = View.VISIBLE
        uriDisplay.visibility = View.VISIBLE
        networkStreamUrlInput.visibility = View.VISIBLE
        showNetworkStreamButton.visibility = View.VISIBLE
        textureView.visibility = View.VISIBLE
        imageView.visibility = View.VISIBLE
        imageView.setImageResource(R.drawable.camaigo)
        imageView.bringToFront()
        surfaceView.visibility = View.GONE

        stopDrawing()

    }


    @SuppressLint("SetJavaScriptEnabled")
    private fun showNetworkStream() {
        val streamUrl = networkStreamUrlInput.text.toString()
        if (streamUrl.isNotEmpty()) {
            isShowingNetworkStream = true
            startButton.visibility = View.GONE
            portInput.visibility = View.GONE
            resolutionSpinner.visibility = View.GONE
            cameraSelectorSpinner.visibility = View.GONE
            uriDisplay.visibility = View.GONE
            networkStreamUrlInput.visibility = View.GONE
            showNetworkStreamButton.visibility = View.GONE

            textureView.visibility = View.GONE
            imageView.visibility = View.INVISIBLE
            surfaceView.visibility = View.VISIBLE

            when {
                streamUrl.startsWith("ws://") || streamUrl.startsWith("wss://") -> startWebSocketStream(streamUrl)
                else -> startHttpStream(streamUrl)
            }

            startDrawing()

        } else {
            Toast.makeText(this, "Please enter a valid stream URL", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startDrawing() {
        drawJob = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                val bitmap = imageQueue.poll()
                if (bitmap != null) {
                    withContext(Dispatchers.Main) {
                        drawBitmapOnSurface(bitmap)
                    }
                }
                delay(1)  // Adjust the delay to control the frame rate (e.g., 16ms for ~60 FPS)
            }
        }
    }

    private fun stopDrawing() {
        drawJob?.cancel()
        drawJob = null
    }

    private fun drawBitmapOnSurface(bitmap: Bitmap) {
        surfaceHolder.lockCanvas()?.let {
            it.drawBitmap(bitmap, null, Rect(0, 0, surfaceView.width, surfaceView.height), null)
            surfaceHolder.unlockCanvasAndPost(it)
        }
    }

    private fun startHttpStream(streamUrl: String) {
        streamJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL(streamUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val inputStream = BufferedInputStream(connection.inputStream)
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var imageData = ByteArrayOutputStream()
                    var isReadingImage = false
                    var contentLength = -1

                    while (isActive) {
                        bytesRead = inputStream.read(buffer)
                        if (bytesRead == -1) break

                        var offset = 0
                        while (offset < bytesRead) {
                            if (!isReadingImage) {
                                // Look for the start of an image
                                val headerEnd = findSequence(buffer, "\r\n\r\n".toByteArray(), offset, bytesRead)
                                if (headerEnd != -1) {
                                    // Parse headers
                                    val headers = String(buffer, offset, headerEnd - offset)
                                    val lengthMatch = "Content-Length: (\\d+)".toRegex().find(headers)
                                    contentLength = lengthMatch?.groupValues?.get(1)?.toIntOrNull() ?: -1

                                    isReadingImage = true
                                    offset = headerEnd + 4 // Skip the empty line after headers
                                    imageData = ByteArrayOutputStream()
                                } else {
                                    break // Wait for more data
                                }
                            }else {
                                val remainingBytes = bytesRead - offset
                                val bytesToRead = minOf(remainingBytes, contentLength - imageData.size())
                                imageData.write(buffer, offset, bytesToRead)
                                offset += bytesToRead

                                if (imageData.size() == contentLength) {
                                    // We have a complete image
                                    val bitmap = BitmapFactory.decodeByteArray(imageData.toByteArray(), 0, imageData.size())
                                    if (bitmap != null) {
                                        imageQueue.add(bitmap)
                                    }
                                    isReadingImage = false
                                    contentLength = -1
                                }
                            }
                        }
                    }
                    inputStream.close()
                }
                connection.disconnect()
            } catch (e: Exception) {
                e.printStackTrace()
                delay(1000) // Retry after delay on failure
            }
        }
    }

    // Helper function to find a byte sequence in a ByteArray
    private fun findSequence(source: ByteArray, sequence: ByteArray, startIndex: Int, endIndex: Int): Int {
        for (i in startIndex until endIndex - sequence.size + 1) {
            var found = true
            for (j in sequence.indices) {
                if (source[i + j] != sequence[j]) {
                    found = false
                    break
                }
            }
            if (found) return i
        }
        return -1
    }

    private fun startWebSocketStream(streamUrl: String) {
        webSocketClient = object : WebSocketClient(URI(streamUrl)) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                Log.d("WebSocket", "Connection opened")
            }

            override fun onMessage(message: String?) {
                Log.d("WebSocket", "Received string message: ${message?.take(100)}")
                message?.let {
                    if (it.startsWith("UklGR")) {  // This is the base64 prefix for WebP images
                        try {
                            val imageBytes = Base64.decode(it, Base64.DEFAULT)
                            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                            if (bitmap != null) {
                                imageQueue.add(bitmap)
                            } else {
                                Log.e("WebSocket", "Failed to decode WebP image")
                            }
                        } catch (e: Exception) {
                            Log.e("WebSocket", "Error decoding image: ${e.message}")
                        }
                    } else {
                        Log.d("WebSocket", "Received non-image string message")
                    }
                }
            }

            override fun onMessage(bytes: ByteBuffer?) {
                Log.d("WebSocket", "Received binary message of size: ${bytes?.remaining()}")
                bytes?.let {
                    val imageBytes = ByteArray(it.remaining())
                    it.get(imageBytes)
                    val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                    if (bitmap != null) {
                        imageQueue.add(bitmap)
                    } else {
                        Log.e("WebSocket", "Failed to decode binary message as bitmap")
                    }
                }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                Log.d("WebSocket", "Connection closed")
            }

            override fun onError(ex: Exception?) {
                Log.e("WebSocket", "Error: ${ex?.message}")
            }
        }
        webSocketClient?.connect()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        // Surface is created, we can start drawing
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // Handle surface size changes if needed
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // Stop any ongoing streaming when the surface is destroyed
        stopStreaming()
    }

    private fun stopStreaming() {
        streamJob?.cancel()
        webSocketClient?.close()
        webSocketClient = null
    }



    private fun createCameraPreviewSession() {
        val previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        previewRequestBuilder.addTarget(imageReader.surface)
        previewRequestBuilder.set(CaptureRequest.JPEG_QUALITY, 40.toByte())

        // Set the frame duration explicitly (in nanoseconds)
        val frameDuration = (1_000_000_000 / 20).toLong()
        previewRequestBuilder.set(CaptureRequest.SENSOR_FRAME_DURATION, frameDuration)

        cameraDevice.createCaptureSession(listOf(imageReader.surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                captureSession.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {}
        }, backgroundHandler)
    }

    private fun processNewImage(reader: ImageReader) {
        currentImage?.close()

        // Acquire the latest image
        currentImage = reader.acquireLatestImage()
        currentImage?.let { image ->
            streamingServer.onFrameAvailable(image)  // Process the current image
        }
        currentImage?.close()
    }

    private fun startCamera() {

        if (isCameraRunning) {
            stopCamera()
            startButton.text = "Start Camera"
        } else {
            startBackgroundThread()

            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.CAMERA
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.CAMERA),
                    CAMERA_PERMISSION_REQUEST
                )
                return
            }

            try {
                imageReader = ImageReader.newInstance(selectedResolution.width, selectedResolution.height, ImageFormat.JPEG, 2)
                imageReader.setOnImageAvailableListener({ reader ->
                    processNewImage(reader)
                }, backgroundHandler)


                selectedCameraId?.let {
                    cameraManager.openCamera(it, object : CameraDevice.StateCallback() {
                        override fun onOpened(camera: CameraDevice) {
                            cameraDevice = camera
                            createCameraPreviewSession()
                        }

                        override fun onDisconnected(camera: CameraDevice) {
                            camera.close()
                        }

                        override fun onError(camera: CameraDevice, error: Int) {
                            camera.close()
                        }
                    }, backgroundHandler)
                }
            } catch (e: CameraAccessException) {
                e.printStackTrace()
            }

            val port = portInput.text.toString().toIntOrNull() ?: 8080
            streamingServer = StreamingServer(port)

            streamingServer.start()
            startButton.text = "Stop Camera"
        }
        isCameraRunning = !isCameraRunning
    }

    private fun stopCamera() {
        try {
            stopBackgroundThread()
            captureSession?.stopRepeating()
            captureSession?.close()
            cameraDevice?.close()

            // Close the last image if it exists
            currentImage?.close()
            currentImage = null

        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }

        streamingServer.stop()
    }


    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").apply { start() }
        backgroundHandler = Handler(backgroundThread.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread.quitSafely()
        try {
            backgroundThread.join()
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }


    override fun onPause() {
        super.onPause()
        Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, brightness)
    }

    override fun onDestroy() {
        super.onDestroy()
        //webView.destroy()
        stopStreaming()
        stopCamera()  // Ensure camera is fully stopped
        Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, brightness)
    }

    override fun onBackPressed() {
        if (isShowingNetworkStream) {
            showMainUI()
        } else {
            super.onBackPressed()
        }
    }

    companion object {
        private const val CAMERA_PERMISSION_REQUEST = 100
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 0) {
            if (isCanWriteSettings(context = this)) {
                // Permission has been granted, so change the screen brightness
                Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 255)
            } else {
                // Permission has been denied, so show an error message
                Toast.makeText(this, "WRITE_SETTINGS permission is required to change screen brightness", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun isCanWriteSettings(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.System.canWrite(context)
    }

    fun requestCanWriteSettings(activity: Activity){

        if (isCanWriteSettings(context = activity)) {
            return // Not needed
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
            intent.data = Uri.parse("package:" + activity.packageName)
            activity.startActivityForResult(intent, 0)
        }

    }
}
