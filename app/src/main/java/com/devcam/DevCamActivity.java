package com.devcam;

import android.app.Activity;
import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.util.Log;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class DevCamActivity extends Activity {

    // - - - - Class Constants - - - -
    public final static String APP_TAG = "devCam";

    // ID tag for inter-thread communication of A3 results from preview results
    private final static int AUTO_RESULTS = 100;

    // Get paths to (or create) the folders where the images are to be saved
    // and the design JSONs are located.
    // A little sloppy, should check that there is external storage first.
    final static File APP_DIR =
            new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),"devCam");
    final static File CAPTURE_DIR = new File(APP_DIR,"Captured");
    final static File DESIGN_DIR = new File(APP_DIR,"Designs");

    protected AutoFitSurfaceView mPreviewSurfaceView;
    private SurfaceHolder mPreviewSurfaceHolder;

    private boolean mSurfaceExists = false;


    // Camera-related member variables.
    private CameraCharacteristics mCamChars;
    private String backCamId;
    private CameraDevice mCamera;
    private CameraManager cm;
    private CameraCaptureSession mCaptureSession;
    private CaptureRequest.Builder mPreviewCRB;



    // Operation-related member variables
    protected Handler mBackgroundHandler;
    protected Handler mMainHandler;
    private HandlerThread mBackgroundThread;
    private HandlerThread mImageSaverThread;
    protected Handler mImageSaverHandler;
    private Semaphore mCameraOpenCloseLock = new Semaphore(1);


    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -
    // - - - - - - - - - Important Callback / Listener objects - - - - - - - -


    /* CameraCaptureSession StateCallback, called when the session is started,
         * giving us access to the reference to the session object.
         */
    private CameraCaptureSession.StateCallback CCSSC = new CameraCaptureSession.StateCallback() {

        @Override
        public void onConfigured(CameraCaptureSession session){
            Log.v(APP_TAG, "CameraCaptureSession configured correctly.");
            mCaptureSession = session;

            // We now have access to a capture session, so let's use it to
            // start a recurring preview request
            try{
                mPreviewCRB = mCamera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                mPreviewCRB.addTarget(mPreviewSurfaceHolder.getSurface());
                mCaptureSession.setRepeatingRequest(mPreviewCRB.build(), previewCCB, mBackgroundHandler);
            } catch (CameraAccessException cae){
                cae.printStackTrace();
            }

            onCaptureSessionReady(mCaptureSession);
        }

        @Override
        public void onConfigureFailed(CameraCaptureSession session){
            throw new RuntimeException("CameraCaptureSession configuration fail.");
        }

        @Override
        public void onClosed(CameraCaptureSession session){
            Log.v(APP_TAG,"Capture Session onClosed() called.");
        }
    };

    /* CaptureCallback for the preview window. This should be going as a
     * repeating request whenever we are not actively capturing a Design.
     *
     * This runs on a background thread and whenever a preview frame is
     * available, complete with AE/AF results, send the CaptureResult back to
     * the main thread so that the auto-views on the preview window can be
     * updated. This allows the user to know what the auto-algorithms suggest
     * for the current scene, if they care.
     */
    private CameraCaptureSession.CaptureCallback previewCCB =
            new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession session,
                                               CaptureRequest request, TotalCaptureResult result){
//                    Log.v(APP_TAG,"Preview Image ready!");
                    // Send the auto values back to the main thread for display
                    Message msg =  mMainHandler.obtainMessage(AUTO_RESULTS,result);
                    msg.sendToTarget();
                }
            };


    /* Callback for when the CameraDevice is accessed. We need the CameraDevice
     * object passed to us here in order to open a CameraCaptureSession and to
     * create CaptureRequest.Builders, so save it as a member variable when we get
     * it.
     */
    private CameraDevice.StateCallback CDSC = new CameraDevice.StateCallback() {

        @Override
        public void onClosed(CameraDevice camera){
            Log.v(APP_TAG,"camera device onClosed() called.");
        }

        @Override
        public void onDisconnected(CameraDevice camera){
            Log.v(APP_TAG,"camera device onDisconnected() called.");
            // If camera disconnected, free resources.
            mCameraOpenCloseLock.release();
            camera.close();
            mCamera = null;
        }

        @Override
        public void onError(CameraDevice camera, int error){
            Log.v(APP_TAG,"onError() when loading camera device!");
            // If camera error, free resources.
            mCameraOpenCloseLock.release();
            camera.close();
            mCamera = null;
            finish();
        }

        @Override
        public void onOpened(CameraDevice camera){
            Log.v(APP_TAG,"CameraDevice opened correctly.");
            mCameraOpenCloseLock.release();
            mCamera = camera;


            // Catch inadequate cameras, those which will not allow manual setting of exposure
            // settings and/or processing settings
            boolean inadequateCameraFlag = false;
            int[] capabilities = mCamChars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
            int prereqs = 0;
            for (int i = 0; i<capabilities.length; i++){
                if (capabilities[i]==CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR){
                    prereqs++;
                }
                if (capabilities[i]==CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING){
                    prereqs++;
                }
            }
            if (prereqs!=2){
                inadequateCameraFlag = true;
            }

            // Now that camera is open, and we know the preview Surface is
            // ready to receive from it, try creating a CameraCaptureSession.
            onCameraReady(mCamera,mCamChars,inadequateCameraFlag);
            updateCaptureSession();
        }
    };


    protected void updateCaptureSession(){
        Log.v(APP_TAG,"updateCaptureSession() called.");

        List<Surface> surfaces = addNonPreviewSurfaces();
        surfaces.add(mPreviewSurfaceHolder.getSurface());
        try{
            // Try to create a capture session, with its callback being handled
            // in a background thread.
            if (mCamera==null){
                Log.v(APP_TAG,"CameraDevice not assigned yet! Accessing now.");
                accessCamera();
            } else {
                Log.v(APP_TAG,"Requesting creation of Capture Session.");
                for (int i=0; i<surfaces.size(); i++){
                    Log.v(APP_TAG,"Target Surface " + i + " format : " + surfaces.get(i));
                }
                mCamera.createCaptureSession(surfaces, CCSSC, mBackgroundHandler);
            }
        } catch (CameraAccessException cae) {
            // If we couldn't create a capture session, we have trouble. Abort!
            cae.printStackTrace();
            finish();
        }
    }

    // OVERRIDE ME
    protected List<Surface> addNonPreviewSurfaces(){
        return new ArrayList<Surface>();
    }


    /* Callback for the preview Surface's SurfaceHolder. We use this to know when
     * the Surface is ready to receive an image from the camera before we load it.
     */
    private final SurfaceHolder.Callback mHolderCallback =
            new SurfaceHolder.Callback(){

                Size mTargetSize = null;

                @Override
                public void surfaceCreated(SurfaceHolder holder){
                    Log.v(APP_TAG,"Preview Surface Created.");

                    mSurfaceExists = true;
                    // Preview Surface has been created, but may not be the desired
                    // size (or even one that is a feasible output for the camera's
                    // output stream). So set it to be feasible and wait.

                    // Figure out what size is fastest and feasible for this camera.
                    // We want to make sure it has the smallest possible minFrameTime
                    // so that if someone want to capture an sequence specifically
                    // counting on that minFrameTime, the preview output doesn't mess
                    // it up.

                    // Assuming the camera sends some YUV format to the Surface for
                    // preview, and assuming that all YUV formats for a given camera
                    // will have the same possible output sizes, find a YUV format the
                    // camera puts out and find the largest size it puts out in the
                    // minimum time.
                    Integer [] yuvFormats = {ImageFormat.YUV_420_888};
//                            ImageFormat.YV12,ImageFormat.YUY2,
//                            ImageFormat.NV21,ImageFormat.NV16};
                    StreamConfigurationMap streamMap = mCamChars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    int[] formats = streamMap.getOutputFormats();
                    long minTime = Long.MAX_VALUE; // min frame time of feasible size. Want to minimize.
                    long maxSize = 0; // area of feasible output size. Want to maximize.
                    for (int formatInd=0; formatInd<formats.length; formatInd++){
                        for (int yuvInd=0; yuvInd<yuvFormats.length; yuvInd++){
                            if (formats[formatInd]==yuvFormats[yuvInd] && null==mTargetSize){
                                // This is a valid YUV format, so find its output times
                                // and sizes
                                Log.v(APP_TAG,"YUV format: " + CameraReport.cameraConstantStringer("android.graphics.ImageFormat",formats[formatInd]));
                                Size[] sizes = streamMap.getOutputSizes(formats[formatInd]);
                                for (Size size : sizes){
                                    long frameTime = (streamMap.getOutputMinFrameDuration(formats[formatInd], size));
                                    if (size.getHeight()*4 != size.getWidth()*3){
                                        //Log.v(APP_TAG,"Incorrect aspect ratio. Skipping.");
                                        continue;
                                    }

                                    long frameSize = size.getHeight()*size.getWidth();
                                    //Log.v(APP_TAG,"Size " + size + " has minFrameTime " + frameTime);
                                    if (frameTime < minTime){
                                        minTime = frameTime;
                                        maxSize = 0;
                                    }
                                    if (( frameSize >= maxSize) ){
                                        Log.v(APP_TAG,"Frame size: " + frameSize + ". Selected as best.");
                                        maxSize = frameSize;
                                        mTargetSize = size;
                                    }
                                }
                            }
                        }
                    }
                    holder.setFixedSize(mTargetSize.getWidth(), mTargetSize.getHeight());
                    // Wait until now to set the size of the SurfaceView because we didn't know the
                    // right aspect ratio yet
                    mPreviewSurfaceView.setAspectRatio(mTargetSize.getWidth(),mTargetSize.getHeight());
                }

                @Override
                public void surfaceChanged(SurfaceHolder holder, int format, int width, int height){
                    Log.v(APP_TAG,"Preview SurfaceHolder surfaceChanged() called.");


                        // Only try to access the camera once the preview Surface is ready
                        // to accept an image stream from it.
                        if ((width == mTargetSize.getWidth()) && (height == mTargetSize.getHeight())) {
                            Log.d(APP_TAG, "Preview Surface set up correctly.");
                            accessCamera();
                        }

                }

                @Override
                public void surfaceDestroyed(SurfaceHolder holder){
                    Log.v(APP_TAG,"onSurfaceDestroyed() called.");
                    mSurfaceExists = false;
                    // If the surface is destroyed, we definitely don't want the camera
                    // still sending data there, so close it in case it hasn't been closed yet.
                    closeCamera();

                }
            };


    /* void loadCamera()
     *
     * Identifies and gets information about the camera device we will be using.
     * This information includes its photographic capabilities (mCamChars) and
     * its output capabilities (mStreamMap and its current working derivatives,
     * mOutputFormats and mOutputSizes).
     *
     * Also saves the CameraManager and camera ID for actually accessing it
     * later.
     * This only needs to be run once, not every time the activity is unpaused.
     */
    private void loadCamera(){
        Log.v(APP_TAG,"loadCamera() called.");
        // Load the camera manager
        cm = (CameraManager) this.getSystemService(Context.CAMERA_SERVICE);

        try {
            // ASSUMING there is one backward facing camera, and that it is the
            // device we want to use, find the ID for it and its capabilities.
            String[] deviceList = cm.getCameraIdList();
            for (int i=0; i<deviceList.length; i++){
                backCamId = deviceList[i];
                mCamChars = cm.getCameraCharacteristics(backCamId);
                if (mCamChars.get(CameraCharacteristics.LENS_FACING)
                        == CameraMetadata.LENS_FACING_BACK){
                    break;
                }
            }

            // Generate a JSON with the camera device's capabilities.
            generateDeviceReport(mCamChars);

        }
        catch (CameraAccessException cae) {
            // If we couldn't load the camera, that's a bad sign. Just quit.
            Log.v(APP_TAG, "Error loading CameraDevice");
            cae.printStackTrace();
            finish();
        }
    }

    protected final void restorePreview(){
        try{
            // Stop any repeating requests
            mCaptureSession.stopRepeating();
            // Explicitly unlock AE and set AF back to idle, in case last capture design locked them.
            mPreviewCRB.set(CaptureRequest.CONTROL_AE_LOCK,false);
            mPreviewCRB.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
            mCaptureSession.capture(mPreviewCRB.build(), previewCCB, mBackgroundHandler);

            // Now let the repeating normal (non-AF-canceling) preview request run.
            mPreviewCRB.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
            mCaptureSession.setRepeatingRequest(mPreviewCRB.build(), previewCCB, mBackgroundHandler);
        } catch (CameraAccessException cae){
            cae.printStackTrace();
        }
    }

    // OVERRIDE ME
    protected void onAutoResultsReady(CaptureResult result){};

    // OVERRIDE ME
    protected void onCaptureSessionReady(CameraCaptureSession session){};

    // OVERRIDE ME
    protected void onCameraReady(CameraDevice camera, CameraCharacteristics camChars, boolean inadequateCameraFlag){};


    /* void establishActiveResources()
     *
     * Establishes the resources that need to be set up every time the activity
     * restarts: threads and their handlers, as well as
     *
     */
    private void establishActiveResources(){
        Log.v(APP_TAG,"establishActiveResources() called.");

        // Set up a main-thread handler to receive information from the
        // background thread-based mPreviewCCB object, which sends A3
        // information back from the continuously generated preview results in
        // order to update the "auto views", which must be done in main thread.
        mMainHandler = new Handler(this.getMainLooper()){
            @Override
            public void handleMessage(Message inputMessage){
                // If mPreviewCCB sent back some CaptureResults, save them
                // and update views.
                if (inputMessage.what == AUTO_RESULTS){
                    onAutoResultsReady((CaptureResult) inputMessage.obj);
                } else {
                    super.handleMessage(inputMessage);
                }
            }
        };

        // Set up background threads so as not to block the main UI thread.
        // One for all the camera callbacks.
        if (null==mBackgroundThread){
            mBackgroundThread = new HandlerThread("devCam CameraBackground");
            mBackgroundThread.start();
            mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
        }
        // One for the ImageSaver actions, to not block the camera callbacks.
        if (null==mImageSaverThread){
            mImageSaverThread = new HandlerThread("devCam ImageSaver");
            mImageSaverThread.start();
            mImageSaverHandler = new Handler(mImageSaverThread.getLooper());
        }

        // Set up the SurfaceHolder of the appropriate View for being a
        // preview. Doing so initiates the loading of the camera, once the
        // SurfaceHolder.Callback is invoked.
        mPreviewSurfaceHolder = mPreviewSurfaceView.getHolder();
        mPreviewSurfaceHolder.addCallback(mHolderCallback);

        mPreviewSurfaceView.setVisibility(View.VISIBLE);


//        // If the surface already exists, the holder callback's onSurfaceChanged() will never be
//        // called and thus accessCamera() will not either. To keep that chain moving, do so here
//        // instead.
//        if (mSurfaceExists) {
//            Log.v(APP_TAG,"Surface = " +mPreviewSurfaceHolder.getSurface());
//            Log.v(APP_TAG,"Surface already exists, so trying to access camera directly.");
//            accessCamera();
//        }
    }



    /* void accessCamera()
     *
     * Protected way of trying to open the CameraDevice. Only called once
     * the preview surface is formatted to the correct size to receive preview
     * images, since those will be the first thing shown.
     */
    private void accessCamera(){
        Log.v(APP_TAG,"accessCamera() called.");
        if (mCamera!=null){
            Log.v(APP_TAG,"Camera already aquired. Ignoring call.");
            return;
        }
        try {
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            cm.openCamera(backCamId, CDSC, mBackgroundHandler);
            Log.v(APP_TAG,"Trying to open camera...");
        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
        }
    }


    /* void generateDeviceReport(metadata)
    *
    * Creates a new JSON file and writes the capabilities of the camera device
    * being used, as per the CameraCharacteristics metadata.
    */
    private void generateDeviceReport(CameraCharacteristics meta){
        Log.v(APP_TAG,"generateDeviceReport() called.");

        File file = new File(APP_DIR,"cameraReport.json");
        CameraReport.writeCharacteristicsToFile(meta, file);

        addFileToMTP(file.getAbsolutePath());
    }

    /* void addFilesToMTP(String[])
     *
     * Adds files with full paths indicated in the input string array to be
     * recognized by the system via the mediascanner.
     */
    void addFilesToMTP(String[] filePathsToAdd){
        MediaScannerConnection.scanFile(this, filePathsToAdd, null,
                new MediaScannerConnection.OnScanCompletedListener() {
                    public void onScanCompleted(String path, Uri uri) {
                        Log.i("ExternalStorage", "Scanned " + path + ":");
                        Log.i("ExternalStorage", "-> uri=" + uri);
                    }
                });
    }

    /* void addFileToMTP(String)
     *
     * Adds a file with full path indicated by input string to be recognized
     * by the system via the mediascanner.
     */
    void addFileToMTP(String file){
        addFilesToMTP(new String[]{file});
    }


    /* void stopBackgroundThreads()
     *
     * When finishing the activity, make sure the threads are quit safely.
     */
    private void stopBackgroundThreads() {
        Log.v(APP_TAG,"stopBackgroundThreads() called.");
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        mImageSaverThread.quitSafely();
        try {
            mImageSaverThread.join();
            mImageSaverThread = null;
            mImageSaverHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }





    /* void closeCamera()
     *
     * Safely close the CaptureSession and the CameraDevice.
     * Called whenever the Activity is paused.
     */
    private void closeCamera(){
        Log.v(APP_TAG,"closeCamera() called.");
        try {
            mCameraOpenCloseLock.acquire();
            if (null != mCaptureSession){
                mCaptureSession.close();
                mCaptureSession = null;
            }
            if (null != mCamera){
                mCamera.close();
                mCamera = null;
                Log.v(APP_TAG,"mCamera set to null.");
            }
            if (null != mPreviewSurfaceHolder){
                mPreviewSurfaceHolder.removeCallback(mHolderCallback);
//                mPreviewSurfaceHolder.getSurface().release();///////
                mPreviewSurfaceHolder = null;
                Log.v(APP_TAG,"SurfaceHolder set to null.");
            }

        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        } finally {
            mCameraOpenCloseLock.release();
        }
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.v(APP_TAG,"* * * * * * * * * * * * * * * * * * * * * * * * * * * * * *");
        Log.v(APP_TAG, "DevCamActivity onCreate() called.");

        // Hide the action bar so the activity gets the full screen
        getActionBar().hide();

        // Create main app dir if none exists. If it couldn't be created, quit.
        if (!(APP_DIR.mkdir() || APP_DIR.isDirectory())){
            Toast.makeText(this, "Could not create application directory.", Toast.LENGTH_SHORT).show();
            finish();
        }

        // Create folder for capture if none exists. If it can't be created, quit.
        if (!(CAPTURE_DIR.mkdir() || CAPTURE_DIR.isDirectory())){
            Toast.makeText(this, "Could not create capture directory.", Toast.LENGTH_SHORT).show();
            finish();
        }

        // Create folder for designs if none exists. If it can't be created, quit.
        if (!(DESIGN_DIR.mkdir() || DESIGN_DIR.isDirectory())){
            Toast.makeText(this, "Could not create design directory.", Toast.LENGTH_SHORT).show();
            finish();
        }

        // Gets camera characteristics, output stream info, camID.
        // Must be done before the OutputFormat/Size buttons are set up.
        loadCamera();


        // As base action, set the autofitSurfaceView in a Layout as the view for the Activity
        FrameLayout layout = new FrameLayout(this);
        ViewGroup.LayoutParams frameParams = new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        layout.setLayoutParams(frameParams);

        mPreviewSurfaceView = new AutoFitSurfaceView(this);
        FrameLayout.LayoutParams viewParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        viewParams.gravity = Gravity.CENTER_HORIZONTAL;
        layout.addView(mPreviewSurfaceView,viewParams);

        setContentView(layout);
    }

    // onPause() called before the activity is finished, good place for
    // freeing resources that need to be freed.
    @Override
    protected void onPause(){
        Log.v(APP_TAG,"onPause() called.");
        mPreviewSurfaceView.setVisibility(View.GONE);
        closeCamera();
        stopBackgroundThreads();
        super.onPause();
    }

    // onResume() called every time activity restarts, good place for
    // re-establishing resources like threads, surfaces, camera access.
    @Override
    protected void onResume(){
        super.onResume();
        Log.v(APP_TAG,"onResume() called.");
        establishActiveResources();
    }

}  // end whole class
