import android.media.Image
import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class StreamingServer(private val port: Int = 8080) {
    private var serverSocket: ServerSocket? = null
    private var isRunning = AtomicBoolean(false)
    private val clients = CopyOnWriteArrayList<ClientHandler>()
    private val executor = Executors.newFixedThreadPool(20)
    private var frameWidth = 0
    private var frameHeight = 0

    fun start() {
        executor.submit {
            try {
                ServerSocket(port).use { server ->
                    serverSocket = server
                    isRunning.set(true)
                    Log.i("StreamingServer", "Server started on port $port")
                    while (isRunning.get()) {
                        try {
                            val socket = server.accept()
                            Log.i("StreamingServer", "New client connected: ${socket.inetAddress}")
                            val clientHandler = ClientHandler(socket)
                            clients.add(clientHandler)
                            executor.submit(clientHandler)
                        } catch (e: IOException) {
                            Log.e("StreamingServer", "Error accepting client: ${e.message}")
                        }
                    }
                }
            } catch (e: IOException) {
                Log.e("StreamingServer", "Error starting server: ${e.message}")
            }
        }
    }

    inner class ClientHandler(val socket: Socket) : Runnable {
        private val clientRunning = AtomicBoolean(true)

        override fun run() {
            try {
                socket.use { client ->
                    client.keepAlive = true
                    client.soTimeout = 3000
                    val output = client.getOutputStream()
                    sendMJPEGHeader(output)

                    while (isRunning.get() && clientRunning.get() && !client.isClosed) {
                        Thread.sleep(50)
                    }
                }
            } catch (e: SocketException) {
                Log.e("StreamingServer", "Socket error for ${socket.inetAddress}: ${e.message}")
            } catch (e: IOException) {
                Log.e("StreamingServer", "Error handling client ${socket.inetAddress}: ${e.message}")
            } finally {
                Log.i("StreamingServer", "Client disconnected: ${socket.inetAddress}")
                clients.remove(this)
                clientRunning.set(false)
            }
        }

        fun sendFrame(jpegBytes: ByteArray) {

            executor.submit {
                try {
                    val output = socket.getOutputStream()
                    sendMJPEGFrame(output, jpegBytes)
                } catch (e: IOException) {
                    Log.e(
                        "StreamingServer",
                        "Error sending frame to ${socket.inetAddress}: ${e.message}"
                    )
                    clientRunning.set(false)
                }
            }
        }
    }

    private fun sendMJPEGHeader(output: OutputStream) {
        val header = "HTTP/1.0 200 OK\r\n" +
                "Server: StreamingServer\r\n" +
                "Connection: keep-alive\r\n" +
                "Max-Age: 0\r\n" +
                "Expires: 0\r\n" +
                "Cache-Control: no-cache, private\r\n" +
                "Pragma: no-cache\r\n" +
                "Content-Type: multipart/x-mixed-replace; boundary=image-boundary\r\n\r\n"
        output.write(header.toByteArray())
        output.flush()
    }

    fun onFrameAvailable(image: Image) {
        frameWidth = image.width
        frameHeight = image.height
        val jpegBytes = imageToJpegBytes(image)
        clients.forEach { client ->
            executor.submit { client.sendFrame(jpegBytes) }
        }
    }


    private fun imageToJpegBytes(image: Image): ByteArray {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return bytes
    }

    private fun sendMJPEGFrame(output: OutputStream, jpegBytes: ByteArray) {
        val time_id  =System.currentTimeMillis()
        val frameHeader = "--image-boundary\r\n" +
                "Content-Type: image/jpeg\r\n" +
                "Content-Length: ${jpegBytes.size}\r\n" +
                "X-Timestamp: ${time_id}\r\n" +
                "X-Resolution: ${frameWidth}x${frameHeight}\r\n\r\n"
        output.write(frameHeader.toByteArray())
        output.write(jpegBytes)
        output.write("X-Timestamp_end: ${time_id}\r\n".toByteArray())
        //output.write("\r\n".toByteArray())
        output.flush()
    }

    fun stop() {
        isRunning.set(false)
        clients.forEach { it.socket.close() }
        clients.clear()
        serverSocket?.close()
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)
        Log.i("StreamingServer", "Server stopped")
    }
}