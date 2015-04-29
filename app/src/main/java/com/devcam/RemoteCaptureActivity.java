package com.devcam;


import android.content.Intent;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureResult;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class RemoteCaptureActivity extends DevCamActivity {

    final String PROCESSING_SETTING = "PROCESSING_SETTING";
    final String WIDTH = "WIDTH";
    final String HEIGHT = "HEIGHT";
    final String FORMAT = "FORMAT";
    final String DESIGN_NAME = "DESIGN_NAME";
    final String CAPTURE_REQUEST = "CAPTURE_REQUEST";


    ImageReader mImageReader;
    TextView textView;

    CameraDevice mCamera;
    CameraCaptureSession mCaptureSession;
    CameraCharacteristics mCamChars;


    @Override
    protected void onAutoResultsReady(CaptureResult autoresult){

    }

    @Override
    protected List<Surface> addNonPreviewSurfaces(){
        Log.v(APP_TAG,"addNonPreviewSurfaces() called.");

        List<Surface> surfaces = new ArrayList<Surface>(); // Don't use Arrays.asList() because that produces an immutable result
        if (mImageReader!=null) {
            surfaces.add(mImageReader.getSurface());
        }
        return surfaces;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState){
        super.onCreate(savedInstanceState);
        Log.v(APP_TAG,"RemoteCaptureActivity onCreate().");

        // Override default layout and preview surface
        setContentView(R.layout.remote_layout);
        mPreviewSurfaceView = (AutoFitSurfaceView) findViewById(R.id.remoteSurfaceView);

        textView = (TextView) findViewById(R.id.remoteTextView);
    }

    @Override
    protected void onResume(){
        super.onResume();
        Log.v(APP_TAG, "RemoteCaptureActivity onResume().");
    }

    @Override
    protected void onPause(){
        super.onPause();
    }


    @Override
    protected void onNewIntent(Intent intent){
        // Associate this new Intent with the Activity, to be read upon onResume()
        this.setIntent(intent);
    }

    @Override
    protected void onCameraReady(CameraDevice camera, CameraCharacteristics camChars,boolean inadequateCameraFlag){
        mCamera = camera;
        mCamChars = camChars;

        // * * * DO SOMETHING TO CATCH INADEQUATE CAMERA * * *
    }
    @Override
    protected void onCaptureSessionReady(CameraCaptureSession session){
        Log.v(APP_TAG,"onCaptureSessionReady() called.");
        mCaptureSession = session;

        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                parseIntent();
            }
        });

    }


    void parseIntent(){
        Log.v(APP_TAG,"parseIntent() called");
        Intent intent = getIntent();

        String action = intent.getAction();

        // First, make sure there is an Action Label
        if (action==null){
            return;
        }

        Log.v(APP_TAG,"Parsing Remote Capture Intent.");
        if (action.equals(CAPTURE_REQUEST)) {

            int processingSetting = intent.getIntExtra(PROCESSING_SETTING, -1);
            if (processingSetting == -1) {
                Log.v(APP_TAG, "No Processing Setting in Intent");
                return;
            }
            Log.v(APP_TAG, "Processing Setting: " + CaptureDesign.ProcessingChoice.getChoiceByIndex(processingSetting));

            int width = intent.getIntExtra(WIDTH, -1);
            if (width == -1) {
                Log.v(APP_TAG, "No Width in Intent");
                return;
            }
            Log.v(APP_TAG, "Width: " + width);

            int height = intent.getIntExtra(HEIGHT, -1);
            if (height == -1) {
                Log.v(APP_TAG, "No Height in Intent");
                return;
            }
            Log.v(APP_TAG, "Height: " + height);

            int format = intent.getIntExtra(FORMAT, -1);
            if (format == -1) {
                Log.v(APP_TAG, "No Format in Intent");
                return;
            }
            Log.v(APP_TAG, "Format: " + CameraReport.cameraConstantStringer("android.graphics.ImageFormat", format));

            String designName = intent.getStringExtra(DESIGN_NAME);
            if (designName == null) {
                Log.v(APP_TAG, "No Design Name in Intent");
                return;
            }
            Log.v(APP_TAG, "Design Name: " + designName);

        }
        String designName = "sweep_ISO";
        int processingSetting = 1;
        int format = 256;
        int width=1600;
        int height=1200;
        {
            File designFile = new File(DESIGN_DIR,designName+".json");
            try {

                final CaptureDesign mDesign = CaptureDesign.Creator.loadDesignFromJson(designFile);
                mDesign.setProcessingSetting(CaptureDesign.ProcessingChoice.getChoiceByIndex(processingSetting));
                mDesign.registerCallback(new CaptureDesign.DesignCaptureCallback() {
                    @Override
                    void onFailed(){
                        mMainHandler.post(new Runnable(){
                            public void run(){
                                textView.setText(R.string.remote_default_text);
                                errorToast();
                            }
                        });
                    }

                    @Override
                    void onFinished(DesignResult designResult) {
                        // Here, save the metadata and the request itself, and register them with the system
                        // The onImageSaved() callback method will register the actual image files when ready.

                        File IM_SAVE_DIR = new File(CAPTURE_DIR,mDesign.getDesignName());

                        // First, save JSON file with array of metadata
                        File metadataFile = new File(IM_SAVE_DIR,mDesign.getDesignName() + "_capture_metadata"+".json");
                        CameraReport.writeCaptureResultsToFile(designResult.getCaptureResults(),designResult.getFilenames(), metadataFile);
                        addFileToMTP(metadataFile.getAbsolutePath());

                        // Now, write out a txt file with the information of the original
                        // request for the capture design, to see how it compares with results
                        File requestFile = new File(IM_SAVE_DIR,mDesign.getDesignName()+"_design_request"+".txt");
                        mDesign.writeOut(requestFile);
                        addFileToMTP(requestFile.getAbsolutePath());

                        textView.setText(R.string.remote_default_text);
                        restorePreview();
                    }

                    @Override
                    void onImageReady(Image image, CaptureResult result, String filename) {
                        File IM_SAVE_DIR = new File(CAPTURE_DIR,mDesign.getDesignName());
                        IM_SAVE_DIR.mkdir();

                        // Post the images to be saved on another thread. Null for imagesaver callback
                        // is because we don't need to know when saving is done, because communication
                        // with computer is one-way anyway. Computer will just check to see if files
                        // are there.
                        mImageSaverHandler.post(
                                new ImageSaver(image, result, mCamChars, IM_SAVE_DIR,
                                        filename, null));
                    }
                });

                // Establish output surface (ImageReader) resources and register our callback with it.
                mImageReader = ImageReader.newInstance(width,height,format,
                        imageBufferSizer(mDesign));  // defer to auxiliary function to determine size of allocation
                mImageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                    @Override
                    public void onImageAvailable(ImageReader reader) {
                        Log.v(APP_TAG, "IMAGE READY! Saving to DesignResult.");
                        Image image = reader.acquireNextImage();
                        DesignResult designResult = mDesign.getDesignResult();
                        designResult.recordImage(image);
                    }
                }, mBackgroundHandler);

                mDesign.startCapture(mCamera,mCaptureSession,mImageReader.getSurface(),
                        mPreviewSurfaceView.getHolder().getSurface(),mBackgroundHandler,mCamChars);

                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        textView.setText("* Capturing Design *");
                    }
                });

            } catch (IOException ioe){
                ioe.printStackTrace();
            } catch (NoSuchFieldException nsfe){
                nsfe.printStackTrace();
            }

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
}
