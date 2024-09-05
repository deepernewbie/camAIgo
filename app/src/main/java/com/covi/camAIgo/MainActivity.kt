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
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView

class MainActivity : AppCompatActivity() {

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
    private lateinit var webView: WebView
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

        webView = findViewById(R.id.webView)
        webView.settings.javaScriptEnabled = true
        webView.webViewClient = WebViewClient()


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
            val newUri = "http://$newIpAddress:8080/camaigo"
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
        webView.visibility = View.GONE
        webView.loadUrl("about:blank")  // Clear the WebView content

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
            webView.visibility = View.VISIBLE

            webView.settings.apply {
                javaScriptEnabled = true  // Enable JavaScript
                domStorageEnabled = true  // Enable DOM storage
                cacheMode = WebSettings.LOAD_NO_CACHE  // Don't cache data
                mediaPlaybackRequiresUserGesture = false  // Allow autoplay
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW  // Allow mixed content
                useWideViewPort = true
                loadWithOverviewMode = true
                builtInZoomControls = false
                displayZoomControls = false
                setSupportZoom(false)
                // Enable WebView debugging (only for development/testing)
                WebView.setWebContentsDebuggingEnabled(false)
            }

            // Enable hardware acceleration for the WebView
            webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            webView.scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY


            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    // Inject JavaScript to handle video playback
                    webView.evaluateJavascript("""
                    var video = document.querySelector('img');
                    if (video) {
                        video.style.objectFit = 'fill';
                        video.style.width = '100%';
                        video.style.height = '100%';
                    }
                """.trimIndent(), null)
                }
            }


            val isWebSocket = streamUrl.startsWith("ws://") || streamUrl.startsWith("wss://")

            val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
                <style>
                    body, html { margin: 0; padding: 0; height: 100%; width: 100%; overflow: hidden; background-color: #000; }
                    img { position: absolute; top: 0; left: 0; width: 100%; height: 100%; object-fit: contain; }
                </style>
            </head>
            <body>
                <img id="streamImage" src="${if (!isWebSocket) streamUrl else ""}" alt="Stream" />
                ${if (isWebSocket) """
                <script>
                    const ws = new WebSocket('$streamUrl');
                    const img = document.getElementById('streamImage');
        
                    ws.onmessage = function(event) {
                        img.src = 'data:image/webp;base64,' + event.data;
                    };
        
                    ws.onerror = function(error) {
                        console.error('WebSocket Error:', error);
                    };
        
                    ws.onclose = function() {
                        console.log('WebSocket connection closed');
                    };
                </script>
                """ else ""}
            </body>
            </html>
            """.trimIndent()



            webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        } else {
            Toast.makeText(this, "Please enter a valid stream URL", Toast.LENGTH_SHORT).show()
        }
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
        webView.destroy()
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
