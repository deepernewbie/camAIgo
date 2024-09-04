![camAIgo](https://github.com/deepernewbie/camAIgo/blob/6f1e1d65ec7ee66eb14d137f934160699d3da4b5/Screenshots/camAIgoSCR.png)


# camAIgo ready, set, GO

A new open-source Android application designed to supercharge your mobile computer vision processing workflows!

## Why?

Assume that you have a cool idea to try out in a mobile device, or maybe your mobile device is too weak to handle the hardcore processing and/or maybe it is too weak to handle multiprocessing. Or you need centralized computing and just need to use the mobile devices camera and display. Or more fun you have an android AR glasses. In any case if you would like to offload your hardcore computing to a local machine on your premises, you need camAIgo

 ## How?

The idea of camAIgo is simple, we are going to use the device's camera and display.  Do the real processing in 
any language or platform on your local premises cloud. Capture, stream, process, and display—all seamlessly integrated.


```text
+----------------+                     +----------------+
| Mobile Device  |                     | Processing     |
| +----------+   |                     |                |
| | Camera   |------MJPEG over HTTP -->| Your Favorite  |
| +----------+   |                     | Language       |
| +----------+   |                     |                |
| | Display  |<-----MJPEG over HTTP----| Your Favorite  |
| +----------+   |                     | Platform       |
|    camAIgo     |                     |                |
+----------------+                     +----------------+
```
 ## Basic Usage and Installation

 1) Set a mobile AP in your processing device
 
 Ubuntu Example:

    sudo add-apt-repository ppa:lakinduakash/lwh
    sudo apt update
    sudo apt install linux-wifi-hotspot

   [linux wifi hotspot](https://github.com/lakinduakash/linux-wifi-hotspot)

2) Install camAIgo.apk in 

 [camAIgo](https://github.com/deepernewbie/camAIgo/blob/main/app/release/camAIgo.apk)

3) Connect the mobile device to the created wifi AP

4) Run camAIgo

5) Press "Start Camera"

6) Run your favorite algorithm on your favorite platform

Dummy Python Example (Receives the device camera stream and streams it back):

    pip install mjpeg-streamer
    python ./Example Processing/server_streamer

7) Press "Show Network Stream"
