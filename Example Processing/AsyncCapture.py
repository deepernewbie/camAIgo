import urllib.request
import multiprocessing
import subprocess
import time
import cv2
import urllib.request

import numpy as np
import json

class AsyncCapturePipe(multiprocessing.Process):
    def __init__(self, cap_str,shrd_mem):
        super().__init__()
        self.cap_str = cap_str
        self.ffmpeg_cmd = [
            'ffmpeg',
            '-vcodec', 'mjpeg',
            '-fflags', 'flush_packets',
             '-f','mjpeg',
            '-r', '50',
             '-timeout', '3000000',  # Set a timeout in microseconds (3 seconds)
            '-i', '%s'%self.cap_str,  # Input stream URL
            '-f', 'image2pipe',  # Output format: pipe
            '-vcodec', 'rawvideo',  # Output codec: raw video
            '-pix_fmt', 'bgr24',  # Pixel format: BGR (compatible with OpenCV)
            '-an',  # No audio
            '-'
        ]

        self.ffmpeg_process = subprocess.Popen(self.ffmpeg_cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.shrd_mem = shrd_mem
        self.shrd_mem["Frame"] =None

    def get_stream_resolution(self):
        # Run ffprobe to get stream information
        ffprobe_cmd = [
            'ffprobe',
            '-v', 'error',
            '-select_streams', 'v:0',
            '-show_entries', 'stream=width,height',
            '-of', 'json',
            self.cap_str
        ]

        result = subprocess.run(ffprobe_cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)

        # Parse the JSON output
        if result.returncode == 0:
            stream_info = json.loads(result.stdout)
            width = stream_info['streams'][0]['width']
            height = stream_info['streams'][0]['height']
            return width, height
        else:
            print("Error running ffprobe:", result.stderr)
            return None

    def run(self):

        print("Capture Started")
        width, height = self.get_stream_resolution()
        frame_size = width * height * 3  # 3 bytes per pixel (BGR)

        while not self.shrd_mem.get("Stop", False):
            self.shrd_mem["Last Reset"] = time.perf_counter()

            raw_frame = self.ffmpeg_process.stdout.read(frame_size)
            if len(raw_frame) != frame_size:
                print("Error Reading Frame")
                self.ffmpeg_process = subprocess.Popen(self.ffmpeg_cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                print("Recovered")
                continue
            frame = np.frombuffer(raw_frame, np.uint8).reshape((height, width, 3))
            self.shrd_mem["Frame"] = frame
        self.ffmpeg_process.stdout.close()
        self.ffmpeg_process.stderr.close()


class AsyncCaptureCV(multiprocessing.Process):
    def __init__(self,cap_str,shrd_mem):
        super().__init__()
        self.cap = cv2.VideoCapture(cap_str)
        self.cap.set(cv2.CAP_PROP_FOURCC, cv2.VideoWriter_fourcc(*'MJPG'))
        self.shrd_mem = shrd_mem
        self.shrd_mem["Frame"] =None

    def run(self):
        while not self.shrd_mem["Stop"]:
            ret, self.shrd_mem["Frame"] = self.cap.read()
        self.cap.release()


class DataSegmenter(multiprocessing.Process):
    def __init__(self,cap_str, last_data):
        super().__init__()
        self.last_data = last_data
        self.last_data["buffer"]=None
        self.stream = urllib.request.urlopen(cap_str)

    def run(self):
        buffer = b''
        while True:
            try:
                data = self.stream.read(1024)
            except ConnectionResetError:
                raise ConnectionResetError
            buffer += data
            while True:
                last_boundary = buffer.rfind(b'--image-boundary')
                first_boundary = buffer[:last_boundary].rfind(b'--image-boundary')
                if first_boundary != -1 and last_boundary != -1 and first_boundary != last_boundary:
                    #self.data_list.append(buffer[first_boundary:last_boundary])
                    self.last_data["buffer"]=buffer[first_boundary:last_boundary]
                    buffer = buffer[last_boundary:]
                else:
                    break

class AsyncCaptureURL(multiprocessing.Process):
    def __init__(self, cap_str, shrd_mem):
        super().__init__()
        self.shrd_mem = shrd_mem
        self.last_data = multiprocessing.Manager().dict()
        self.data_segmenter = DataSegmenter(cap_str,self.last_data)
        self.data_segmenter.start()
        self.shrd_mem["Frame"] = None
        self.shrd_mem["Error"] = None

    def run(self):
        while not self.shrd_mem["Stop"]:
            buffer=self.last_data["buffer"]
            if buffer:
                start = buffer.find(b'\xff\xd8')
                end = buffer.rfind(b'\xff\xd9')
                jpg = buffer[start:end + 2]
                try:
                    frame = cv2.imdecode(np.frombuffer(jpg, dtype=np.uint8), cv2.IMREAD_COLOR)
                    self.shrd_mem["Frame"] = frame
                except cv2.error:
                    pass
        self.data_segmenter.terminate()

