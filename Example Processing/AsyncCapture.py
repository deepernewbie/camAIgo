import multiprocessing
import cv2
import urllib.request
import numpy as np


class AsyncCaptureURL(multiprocessing.Process):
    def __init__(self,cap_str,shrd_mem):
        super().__init__()
        self.cap_str = cap_str
        self.shrd_mem = shrd_mem
        try:
            self.stream = urllib.request.urlopen(self.cap_str)
        except urllib.error.URLError:
            raise ConnectionRefusedError
        self.shrd_mem["Frame"] =None
        self.shrd_mem["Error"] = None


    def run(self):
        total_bytes = b''
        while not self.shrd_mem["Stop"]:
            try:
                new_bytes = self.stream.read(1024)
                total_bytes += new_bytes
            except ConnectionResetError:
                raise ConnectionResetError

            b = total_bytes.find(b'\xff\xd9')  # JPEG end
            if not b == -1:
                a = total_bytes.find(b'\xff\xd8')  # JPEG start
                jpg = total_bytes[a:b + 2]  # actual image
                total_bytes = total_bytes[b + 2:]  # other information
                try:
                    frame = cv2.imdecode(np.fromstring(jpg, dtype=np.uint8), cv2.IMREAD_COLOR)
                    self.shrd_mem["Frame"] = frame
                except cv2.error:
                    pass



