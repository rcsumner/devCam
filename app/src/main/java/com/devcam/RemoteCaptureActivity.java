package com.devcam;


import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class RemoteCaptureActivity extends Activity {



    final static File APP_DIR =
            new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),"devCam");
    final static File CAPTURE_DIR = new File(APP_DIR,"Captured");
    final static File DESIGN_DIR = new File(APP_DIR,"Designs");

    private File mFlagFile = new File(APP_DIR,"captureflag");

    Context mContext;

    final String PROCESSING_SETTING = "PROCESSING_SETTING";
    final String WIDTH = "WIDTH";
    final String HEIGHT = "HEIGHT";
    final String FORMAT = "FORMAT";
    final String DESIGN_NAME = "DESIGN_NAME";
    final String CAPTURE_REQUEST = "CAPTURE_REQUEST";

    private List<String> mWrittenFilenames ;
    ImageReader mImageReader;
    TextView textView;

    protected Handler mMainHandler;
    private HandlerThread mImageSaverThread;
    protected Handler mImageSaverHandler;
    protected AutoFitSurfaceView mPreviewSurfaceView;
    SurfaceHolder mPreviewSurfaceHolder;

    private int mNumImagesLeftToSave;

    CaptureDesign mDesign;
    DesignResult mDesignResult;

    boolean mWaitingToCapture = false;
    int mNumToSave;

    CameraCharacteristics mCamChars;
    StreamConfigurationMap mStreamMap;

    DevCam mDevCam;

    DevCam.DevCamListener mDevCamStateCallback = new DevCam.DevCamListener() {
        @Override
        void onAutoResultsReady(CaptureResult result) {}

        @Override
        void onCameraDeviceError(int error) {
            super.onCameraDeviceError(error);
        }

        @Override
        void onDevCamReady(boolean postProcControl) {
            super.onDevCamReady(postProcControl);

            if (mWaitingToCapture){
                Log.v(DevCam.APP_TAG,"waiting to capture flag = true and session is now ready. Starting capture.");
                mNumToSave = mDesign.getExposures().size();

                try {
                    mFlagFile.createNewFile();
                } catch (IOException ioe){
                    Log.v(DevCam.APP_TAG,"Couldn't create captureflag file.");
                    return;
                }

                mDevCam.capture(mDesign);
                mWaitingToCapture = false;

                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        textView.setText("* Capturing Design *");
                    }
                });

            } else {
                Log.v(DevCam.APP_TAG,"No 'waiting to capture' flag.");
            }

        }

        @Override
        void onCaptureFailed(int code) {
            super.onCaptureFailed(code);
        }

        @Override
        void onCaptureStarted(Long timestamp) {
            super.onCaptureStarted(timestamp);

            mDesignResult.recordCaptureTimestamp(timestamp);
        }

        @Override
        void onCaptureCompleted(CaptureResult result) {
            super.onCaptureCompleted(result);

            mDesignResult.recordCaptureResult(result);
        }

        @Override
        void onCaptureSequenceCompleted() {
            super.onCaptureSequenceCompleted();
            mMainHandler.post(new Runnable() {
                public void run() {
                    textView.setText("Saving Images.");
                }
            });

        }
    };


    @Override
    protected void onCreate(Bundle savedInstanceState){
        super.onCreate(savedInstanceState);
        Log.v(DevCam.APP_TAG, "* * * * * * * * * * * * * * * * * * * * * * * * * * * * * *");
        Log.v(DevCam.APP_TAG,"RemoteCaptureActivity onCreate().");

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

        // Override default layout and preview surface
        setContentView(R.layout.remote_layout);

        mPreviewSurfaceView = (AutoFitSurfaceView) findViewById(R.id.remoteSurfaceView);

        textView = (TextView) findViewById(R.id.remoteTextView);

        mDevCam = DevCam.getInstance(this, mDevCamStateCallback);
        mCamChars = DevCam.getCameraCharacteristics(this);
        mStreamMap = mCamChars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

        mContext = this;

        // Hide the action bar so the activity gets the full screen
        getActionBar().hide();
    }

    @Override
    protected void onResume(){
        super.onResume();
        Log.v(DevCam.APP_TAG, "RemoteCaptureActivity onResume().");
        establishActiveResources();

        parseIntent();
    }

    @Override
    protected void onPause(){
        super.onPause();
        Log.v(DevCam.APP_TAG, "RemoteCaptureActivity onPause().");
        mPreviewSurfaceView.setVisibility(View.GONE);
        freeImageSaverResources();
        mDevCam.stopCam();
    }


    @Override
    protected void onNewIntent(Intent intent){
        Log.v(DevCam.APP_TAG, "onNewIntent() called.");
        // Associate this new Intent with the Activity, to be read upon onResume()
        this.setIntent(intent);
    }







    void parseIntent(){
        Log.v(DevCam.APP_TAG,"parseIntent() called");
        Intent intent = getIntent();

        String action = intent.getAction();

        // First, make sure there is an Action Label to parse. Otherwise, quit.
        if (action==null){
            Log.v(DevCam.APP_TAG,"No Action specified in the Intent that started this Activity.");
            return;
        }

        Log.v(DevCam.APP_TAG, "Parsing Intent.");
        if (action.equals(CAPTURE_REQUEST)) {
              Log.v(DevCam.APP_TAG,"Intent was for Capture Request");
//            if (mDevCam.isReady()) {
//                Log.v(DevCam.APP_TAG,"DevCam is ready, so process capture request!");
                processCaptureRequest(intent);
//            } else {
//                Log.v(DevCam.APP_TAG,"DevCam is not ready yet, set flag and wait for callback.");
//                mWaitingToCapture = true;
//            }
        } else {
            Log.v(DevCam.APP_TAG,"Intent : " +action);
        }
    }




    void processCaptureRequest(Intent intent){
        Log.v(DevCam.APP_TAG,"processCaptureRequest() called.");

        int processingSetting = intent.getIntExtra(PROCESSING_SETTING, -1);
        if (processingSetting == -1) {
            Log.v(DevCam.APP_TAG, "No Processing Setting in Intent");
            return;
        }
        Log.v(DevCam.APP_TAG, "Processing Setting: " + CaptureDesign.ProcessingChoice.getChoiceByIndex(processingSetting));

        int width = intent.getIntExtra(WIDTH, -1);
        if (width == -1) {
            Log.v(DevCam.APP_TAG, "No Width in Intent");
            return;
        }
        Log.v(DevCam.APP_TAG, "Width: " + width);

        int height = intent.getIntExtra(HEIGHT, -1);
        if (height == -1) {
            Log.v(DevCam.APP_TAG, "No Height in Intent");
            return;
        }
        Log.v(DevCam.APP_TAG, "Height: " + height);

        int format = intent.getIntExtra(FORMAT, -1);
        if (format == -1) {
            Log.v(DevCam.APP_TAG, "No Format in Intent");
            return;
        }
        Log.v(DevCam.APP_TAG, "Format: " + CameraReport.cameraConstantStringer("android.graphics.ImageFormat", format));

        String designName = intent.getStringExtra(DESIGN_NAME);
        if (designName == null) {
            Log.v(DevCam.APP_TAG, "No Design Name in Intent");
            return;
        }
        Log.v(DevCam.APP_TAG, "Design Name: " + designName);


        File designFile = new File(DESIGN_DIR,designName+".json");
        try {

            mDesign = CaptureDesign.Creator.loadDesignFromJson(designFile);
            mDesign.setDesignName(designName);
            mDesign.setProcessingSetting(CaptureDesign.ProcessingChoice.getChoiceByIndex(processingSetting));
            mDesignResult = new DesignResult(mDesign.getExposures().size(),mOnCaptureAvailableListener);
            mWrittenFilenames = new ArrayList<String>();

            mNumImagesLeftToSave = mDesign.getExposures().size();

            Log.v(DevCam.APP_TAG,"CaptureDesign created.");

            // Establish output surface (ImageReader) resources and register our callback with it.
            mImageReader = ImageReader.newInstance(width,height,format,
                    imageBufferSizer(mDesign));  // defer to auxiliary function to determine size of allocation
            mImageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    Log.v(DevCam.APP_TAG, "IMAGE READY! Saving to DesignResult.");
                    Image image = reader.acquireNextImage();
                    mDesignResult.recordImage(image);
                }
            }, mImageSaverHandler);


            mDevCam.registerOutputSurfaces(Arrays.asList(mImageReader.getSurface()));
            mWaitingToCapture = true;
            Log.v(DevCam.APP_TAG,"Output surface created and registered with DevCam. Waiting for updated CaptureSession.");

        } catch (IOException ioe){
            ioe.printStackTrace();
        } catch (NoSuchFieldException nsfe){
            nsfe.printStackTrace();
        }

    }




    /* int imageBufferSizer()
     *
     * Function to determine the number of Images we should allocate space for in the ImageReader.
     * Right now this is a very simple function, but this method is a placeholder for more
     * complicated methods in the future, based on:
     * - the image format
     * - the device's read-out-to-disk speed
     * - available RAM of the device, max number an ImageReader can allocate for (poorly documented)
     *
     * Right now the function is fairly sloppy, though it seems that a Nexus 5 can actually use 30
     * and work successfully. Much larger numbers don't throw an error, but do crash the application
     * later on.
     */
    int imageBufferSizer(CaptureDesign design){
        return Math.min(30,design.getExposures().size())+2;
    }

    private void errorToast(){
        Toast.makeText(getBaseContext(), "Failed to Capture Design.", Toast.LENGTH_LONG).show();
    }


    /* void establishActiveResources()
     *
     * Establishes the resources that need to be set up every time the activity
     * restarts: threads and their handlers, as well as
     *
     */
    private void establishActiveResources(){
        Log.v(DevCam.APP_TAG,"establishActiveResources() called.");

        // Set up a main-thread handler to receive information from the
        // background thread-based mPreviewCCB object, which sends A3
        // information back from the continuously generated preview results in
        // order to update the "auto views", which must be done in main thread.
        mMainHandler = new Handler(this.getMainLooper());

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

    }

    /* Callback for the preview Surface's SurfaceHolder. We use this to know when
     * the Surface is ready to receive an image from the camera before we load it.
     */
    private final SurfaceHolder.Callback mHolderCallback =
            new SurfaceHolder.Callback(){

                Size mTargetSize = null;

                @Override
                public void surfaceCreated(SurfaceHolder holder){
                    Log.v(DevCam.APP_TAG,"Preview Surface Created.");
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
                                Log.v(DevCam.APP_TAG,"YUV format: " + CameraReport.cameraConstantStringer("android.graphics.ImageFormat",formats[formatInd]));
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
                                        Log.v(DevCam.APP_TAG,"Frame size: " + frameSize + ". Selected as best.");
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
                    Log.v(DevCam.APP_TAG,"Preview SurfaceHolder surfaceChanged() called.");


                    // Only try to access the camera once the preview Surface is ready
                    // to accept an image stream from it.
                    if ((width == mTargetSize.getWidth()) && (height == mTargetSize.getHeight())) {
                        Log.d(DevCam.APP_TAG, "Preview Surface set up correctly.");
                        List<Surface> previewSurface = new ArrayList<Surface>();
                        previewSurface.add(holder.getSurface());
                        mDevCam.registerPreviewSurfaces(previewSurface);
                        mDevCam.startCam();
                    }

                }

                @Override
                public void surfaceDestroyed(SurfaceHolder holder){
                    Log.v(DevCam.APP_TAG,"onSurfaceDestroyed() called.");
                    // If the surface is destroyed, we definitely don't want the camera
                    // still sending data there, so close it in case it hasn't been closed yet.
                    mDevCam.stopCam();
                }
            };



    /* void freeImageSaverResources()
     *
     * When finishing the activity, make sure the threads are quit safely.
     */
    private void freeImageSaverResources() {
        Log.v(DevCam.APP_TAG,"freeImageSaverResources() called.");

        if (mImageReader!=null) {
            mImageReader.close();
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


    DesignResult.OnCaptureAvailableListener mOnCaptureAvailableListener = new DesignResult.OnCaptureAvailableListener(){

        @Override
        public void onCaptureAvailable(Image image, CaptureResult result){

            Log.v(DevCam.APP_TAG,"Image+Metadata paired by DesignResult, now available.");

            String fileType = "";
            switch (image.getFormat()){
                case ImageFormat.JPEG:
                    fileType = ".jpg";
                    break;
                case ImageFormat.YUV_420_888:
                    fileType = ".yuv";
                    break;
                case ImageFormat.RAW_SENSOR:
                    fileType = ".dng";
                    break;
            }

            // Record the filename for later, with counter based on number already saved.
            String filename = mDesign.getDesignName() + "-" + (mWrittenFilenames.size()+1) + fileType;
            mWrittenFilenames.add(filename);

            File IM_SAVE_DIR = new File(CAPTURE_DIR,mDesign.getDesignName());
            IM_SAVE_DIR.mkdir();

            // Post the images to be saved on another thread
            mImageSaverHandler.post(new ImageSaver(image, result, mCamChars, IM_SAVE_DIR, filename, mWriteOutCallback));
        };

        @Override
        public void onAllCapturesReported(final DesignResult designResult) {
            Log.v(DevCam.APP_TAG,"All Images+Metadata have been paired by DesignResult. ");
        };
    };


    ImageSaver.WriteOutCallback mWriteOutCallback = new ImageSaver.WriteOutCallback() {

        @Override
        void onImageSaved(boolean success, String filename) {

            // Now check to see if all of the images have been saved. If so, we can restore control
            // to the user and remove the "Saving images" sign.
            mNumImagesLeftToSave--;
            Log.v(DevCam.APP_TAG, "Writeout of image: " + filename + " : " + success);
            Log.v(DevCam.APP_TAG, mNumImagesLeftToSave + " image files left to save.");

            if (mNumImagesLeftToSave ==0) {
                Log.v(DevCam.APP_TAG, "Done saving images. Restore control to app.");

                // Here, save the metadata and the request itself, and register them with the system
                File IM_SAVE_DIR = new File(CAPTURE_DIR,mDesign.getDesignName());

                // First, save JSON file with array of metadata
                File metadataFile = new File(IM_SAVE_DIR,mDesign.getDesignName() + "_capture_metadata" + ".json");
                CameraReport.writeCaptureResultsToFile(mDesignResult.getCaptureResults(),mWrittenFilenames, metadataFile);
                CameraReport.addFileToMTP(mContext, metadataFile.getAbsolutePath());

                // Now, write out a txt file with the information of the original
                // request for the capture design, to see how it compares with results
                File requestFile = new File(IM_SAVE_DIR,mDesign.getDesignName()+"_design_request"+".txt");
                mDesign.writeOut(requestFile);
                CameraReport.addFileToMTP(mContext, requestFile.getAbsolutePath());

                mFlagFile.delete();

                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        textView.setText(R.string.remote_default_text);
                    }
                });
            }

            // Register the saved Image with the file system
            File IM_SAVE_DIR = new File(CAPTURE_DIR,mDesign.getDesignName());
            File imFile = new File(IM_SAVE_DIR,filename);
            CameraReport.addFileToMTP(mContext, imFile.getAbsolutePath());


        }
    };


} // End whole class
