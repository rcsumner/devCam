# devCam
Parameterized camera control for development and testing


devCam is an app for parameterized image capture using Android devices. devCam makes it simple to generate and 
capture a set of photographic exposures with designated values for standard photographic settings. It is designed 
to give the user as much control as the camera allows, making use of the camera2 API (requires Lollipop, Android 5.0+) 
to give the user manual control over the following (if the device is capable):
Exposure time
ISO
Aperture
Focal Length
Focus Distance

devCam essentially turns your Android device into an easily scripted DSLR. It allows manual control over photographic
parameters when that is desired, and use of the camera's auto-focus/exposure algorithms when it is not. 

devCam is primarily intended as a tool for those interested in computational photography research, for whom generating
precise exposure sets is paramount. Images are saved for later analysis and manipulation in more powerful engineering 
environments (i.e. MATLAB, python). Each output image is accompanied by metadata about the image and the camera device 
at the time of its capture.

For more see https://users.soe.ucsc.edu/~rcsumner/devcam/

Rob Sumner
rcsumner@ucsc.edu
