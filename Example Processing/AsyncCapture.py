import multiprocessing
import cv2
import urllib.request
import numpy as np


class DataSegmenter(multiprocessing.Process):
    def __init__(self,cap_str, last_data):
        super().__init__()
        self.last_data = last_data
        self.last_data["buffer"]=None
        self.stream = urllib.request.urlopen(cap_str)

    def run(self):
        buffer = b''
        while True:
            data = self.stream.read(1024)
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



