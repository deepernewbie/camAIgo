from AsyncCapture import *
from AsyncStream import *
import multiprocessing


laptop_ip = "192.168.12.1"
android_device_ip = "http://192.168.12.136:8080/"
Hout = 360
Wout = 640


def main():
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

    data_share = multiprocessing.Manager().dict()
    #streamer = AsyncMJEPGoverHTTP(laptop_ip,(640, 360),40,data_share)
    streamer = AsyncMJPEGWEBPoverHTTP(laptop_ip, (Wout, Hout), 40, data_share, format="jpeg") #format="webp" for WEBP and format="jpeg" for MJPEG
    #streamer = AsyncMJPEGWEBPoverWS(laptop_ip, (Wout, Hout), 40, data_share, format="webp") #format="webp" for WEBP and format="jpeg" for MJPEG
    streamer.start()

    try:
        while True:

            if shared_mem["Stop"]:

                break

            frame_alg_in = shared_mem["Frame"]
            if frame_alg_in is None:
                print("Frame not read")
                continue

            # Your Favorite Algorithm
            frame_alg_out=frame_alg_in #here some magic happens
            data_share["Frame"] = (False, frame_alg_out)

            cv2.imshow("Output", frame_alg_out)

            if cv2.waitKey(1) == ord("q"):
                break

    except KeyboardInterrupt:
        print("Interrupted by user")
    except ConnectionResetError:
        print("Server is not running, Stopping...")
    except urllib.error.URLError:
        print("Server is not running, check if you have a correct video stream")
    finally:
        shared_mem["Stop"] = True
        cap.join(timeout=5)
        if cap.is_alive():
            print("AsyncCapture did not terminate gracefully")
            cap.terminate()
        streamer.terminate()
        cv2.destroyAllWindows()

if __name__ == "__main__":
    main()
