# devCam
Parameterized camera control for development and testing


devCam is an app for parameterized image sequence capture using Android devices. devCam makes it simple to capture complicated sets of photographic exposures with user-defined values for common photographic settings. It is designed to give the user as much control as the camera allows, making use of the camera2 API (requires Lollipop, Android 5.0+) to give the user manual control over the following (if the device is capable):
Exposure time
ISO
Aperture
Focal Length
Focus Distance
devCam's main strength is its programmability- it doesn't just let you set the photographic parameters for an exposure, it lets you easily set them for an entire sequence of exposures. devCam essentially turns your Android device into an easily scripted DSLR. This versatility makes devCam a useful tool for experimentation in the fields of image processing, computer vision, and computational photography.

devCam is primarily intended as a tool for those interested in these research areas, for whom precise control over the image capture and data is paramount. Images are saved for later analysis and manipulation in more powerful engineering environments (i.e. MATLAB, python, C++). Each output image is accompanied by metadata about the image and the camera device at the time of its capture.

devCam also puts out large amounts of human-readable metadata about the camera device and each capture it completes. This metadata is extremely informative about the camera and the Android camera2 API itself; because of this, this app is also very useful for those interested in understanding or developing for this API.

For more see devcamera.org

Rob Sumner
rcsumner@ucsc.edu
