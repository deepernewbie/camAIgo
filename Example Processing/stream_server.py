import cv2
from mjpeg_streamer import MjpegServer, Stream
from AsyncCapture import AsyncCaptureURL
import multiprocessing
import time

def main():
    android_device_ip = "http://192.168.12.84:8080/"
    shared_mem = multiprocessing.Manager().dict()
    shared_mem["Stop"] = False

    try:
        cap = AsyncCaptureURL(android_device_ip, shared_mem)
        cap.start()
    except ConnectionRefusedError:
        print("Server is not running, Stopping...")
        return

    while shared_mem["Frame"] is None:
        continue

    stream = Stream("camaigo", size=(640, 360), quality=40, fps=20)
    server = MjpegServer("192.168.12.1", 8080)  #this is the local processing devices ip
    server.add_stream(stream)
    server.start()

    try:
        while True:

            if shared_mem["Stop"]:
                break

            frame = shared_mem["Frame"]
            if frame is None:
                print("Frame not read")
                continue

            stream.set_frame(frame)
            cv2.imshow(stream.name, frame)

            if cv2.waitKey(1) == ord("q"):
                break

    except KeyboardInterrupt:
        print("Interrupted by user")
    except ConnectionResetError:
        print("Server is not running, Stopping...")
    finally:
        shared_mem["Stop"] = True
        cap.join(timeout=5)
        if cap.is_alive():
            print("AsyncCapture did not terminate gracefully")
            cap.terminate()
        server.stop()
        cv2.destroyAllWindows()

if __name__ == "__main__":
    main()
