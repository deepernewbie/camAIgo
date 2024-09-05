import multiprocessing
from mjpeg_streamer import Stream, MjpegServer
from http.server import BaseHTTPRequestHandler, HTTPServer
import cv2
import time
import asyncio
import websockets
import base64

class AsyncMJPEGoverHTTP(multiprocessing.Process):
    def __init__(self,url,size, quality, data_share):
        super().__init__()
        self.data_share = data_share
        self.data_share["Frame"]=(True, None)
        self.size = size
        self.url = url


    def run(self):
        stream = Stream("camaigo", size=self.size, quality=40, fps=20)
        server = MjpegServer(self.url, 8080)
        server.add_stream(stream)
        server.start()
        try:
            while True:
                last_data_read, last_data = self.data_share["Frame"]
                if last_data_read == False:
                    stream.set_frame(last_data)
                    self.data_share["Frame"] = (True, None)
        finally:
            print("Finally Called")
            server.stop()

class AsyncMJPEGWEBPoverHTTP(multiprocessing.Process):
    def __init__(self, url,size, quality, shared_dict):
        super().__init__()
        self.shared_dict = shared_dict
        self.shared_dict["Frame"]=(True, None)
        self.url = url
        self.size = size
        self.quality = quality

    def run(self):
        class MJPEGHandler(BaseHTTPRequestHandler):
            def __init__(self, request, client_address, server):
                self.size = server.size
                self.quality = server.quality
                super().__init__(request, client_address, server)

            def do_GET(self):
                if self.path == '/':
                    self.send_response(200)
                    self.send_header('Content-Type', f'multipart/x-mixed-replace; boundary=image-boundary')
                    self.end_headers()

                    while True:
                        sent, frame = self.server.shared_dict['Frame']

                        if not sent:
                            # Convert the frame to JPEG format
                            frame = cv2.resize(frame, self.size)
                            val, frame = cv2.imencode(".webp", frame, [cv2.IMWRITE_WEBP_QUALITY, self.quality])
                            frame_bytes = frame.tobytes()

                            # Prepare the MJPEG frame with boundaries
                            mjpeg_frame = (f"--image-boundary\r\n"
                                           f"Content-Type: image/webp\r\n"
                                           f"Content-Length: {len(frame_bytes)}\r\n"
                                           f"X-Resolution: {self.size[0]}x{self.size[1]}\r\n\r\n"
                                           ).encode('utf-8') + frame_bytes + b"\r\n"

                            # Send the MJPEG frame to the client
                            self.wfile.write(mjpeg_frame)

                            # Mark the frame as sent
                            self.server.shared_dict['Frame'] = (True, None)

                        time.sleep(1/20)


        server_address = (self.url, 8080)
        httpd = HTTPServer(server_address, MJPEGHandler)
        httpd.shared_dict = self.shared_dict
        httpd.boundary = "image-boundary"
        httpd.size = self.size
        httpd.quality = self.quality
        print("Starting server on port 8080")
        httpd.serve_forever()

class AsyncWEBPoverWS(multiprocessing.Process):
    def __init__(self, url, size, quality, shared_dict):
        super().__init__()
        self.shared_dict = shared_dict
        self.shared_dict["Frame"] = (True, None)
        self.url = url
        self.size = size
        self.quality = quality

    async def send_frame(self, websocket, path):
        while True:
            sent, frame = self.shared_dict['Frame']

            if not sent:
                # Convert the frame to WEBP format
                frame = cv2.resize(frame, self.size)
                val, frame = cv2.imencode(".webp", frame, [cv2.IMWRITE_WEBP_QUALITY, self.quality])
                frame_bytes = base64.b64encode(frame).decode('utf-8')

                # Mark the frame as sent
                self.shared_dict['Frame'] = (True, None)

                # Send the frame over WebSocket
                await websocket.send(frame_bytes)

            await asyncio.sleep(1/20)

    async def main(self):
        async with websockets.serve(self.send_frame, self.url, 8080):
            await asyncio.Future()  # Run forever

    def run(self):
        asyncio.run(self.main())