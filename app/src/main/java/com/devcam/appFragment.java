package com.devcam;

import static com.devcam.GenerateDesignFromTemplateActivity.DataTags;
import static com.devcam.GenerateDesignFromTemplateActivity.DesignTemplate;
import static com.devcam.CaptureDesign.ProcessingChoice;

import android.app.Activity;
import android.app.Fragment;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class appFragment extends Fragment {

    // - - - - Class Constants - - - -
    public final static String APP_TAG = "devCam";

    // The following are identifying tags for specific intents of requests
    // sent to the SelectByLabelActivity class for a result.
    public final static int OUTPUT_FORMAT = 0;
    public final static int OUTPUT_SIZE = 1;
    public final static int PROCESSING_SETTING = 5;
    public final static int LOAD_DESIGN = 6;
    public final static int GENERATE_DESIGN = 7;



    // ID tag for inter-thread communication of A3 results from preview results
    public final static int AUTO_RESULTS = 100;

    // Get paths to (or create) the folders where the images are to be saved
    // and the design JSONs are located.
    // A little sloppy, should check that there is external storage first.
    final static File APP_DIR =
            new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),"devCam");
    final static File CAPTURE_DIR = new File(APP_DIR,"Captured");
    final static File DESIGN_DIR = new File(APP_DIR,"Designs");



    // - - - - Class Member Variables - - - -

    // GUI-related member variables
    private Button mLoadDesignButton;
    private Button mCaptureButton;
    private Button mOutputFormatButton;
    private Button mSettingsButton;
    private Button mOutputSizeButton;
    private Button mProcessingButton;
    private Button mSplitAmountButton;
    private TextView mInadequateCameraTextView;
    private LinearLayout mMainLinearLayout;
    private TextView mApertureValueView;
    private TextView mSensitivityValueView;
    private TextView mFocusValueView;
    private TextView mExposureTimeValueView;
    private TextView mMinFrameTimeValueView;
    private TextView mOutputStallValueView;
    private ListView mCaptureDesignListView;
    private EditText mDesignNameEditText;
    private TextView mCapturingDesignTextView;
    private ExposureArrayAdapter mCaptureDesignAdapter;

    private AutoFitSurfaceView mPreviewSurfaceView;
    private SurfaceHolder mPreviewSurfaceHolder;
    private ImageReader mImageReader;


    // Camera-related member variables.
    private CameraCharacteristics mCamChars;
    private StreamConfigurationMap mStreamMap;
    private String backCamId;
    private CameraDevice mCamera;
    private CameraManager cm;
    private CameraCaptureSession mCaptureSession;
    private CaptureRequest.Builder mPreviewCRB;


    // Lists of possible output formats and an index into each list are kept so that the user can
    // select such values and the results can be used and displayed
    private List<Integer> mOutputFormats;      // List of output formats, e.g. JPEG, RAW_SENSOR, etc
    private List<String> mOutputFormatLabels; // List of string values of the above, for readability
    private int mOutputFormatInd = 0;
    private Size[] mOutputSizes;
    private int mOutputSizeInd = 0;

    // Operation-related member variables
    private Handler mBackgroundHandler;
    private Handler mMainHandler;
    private HandlerThread mBackgroundThread;
    private HandlerThread mImageSaverThread;
    private Handler mImageSaverHandler;
    private Semaphore mCameraOpenCloseLock = new Semaphore(1);

    // returned from the preview repeating request, used to set realtime values in display
    private CaptureResult mAutoResult;

    // Array of names of JSON CaptureDesign files in appropriate directory
    private String[] mFileNames;

    boolean mUseDelay = false; // flag reflecting state of the delay switch
    boolean mInadequateCameraFlag = false; // flag for camera device that can't handle this app

    // This simply holds the user options for displaying parameters. They are loaded in onResume().
    ExposureArrayAdapter.DisplayOptionBundle mDisplayOptions = new ExposureArrayAdapter.DisplayOptionBundle();

    // The CaptureDesign the app is working with at the moment
    private CaptureDesign mDesign = new CaptureDesign();



    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -
    // - - - - - - - - - Important Callback / Listener objects - - - - - - - -

    /* CameraCaptureSession StateCallback, called when the session is started,
     * giving us access to the reference to the session object.
     */
    private CameraCaptureSession.StateCallback CCSSC = new CameraCaptureSession.StateCallback() {

        @Override
        public void onConfigured(CameraCaptureSession session){
            Log.v(APP_TAG,"CameraCaptureSession configured correctly.");
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
        }

        @Override
        public void onConfigureFailed(CameraCaptureSession session){
            throw new RuntimeException("CameraCaptureSession configuration fail.");
        }
    };

    /* The ImageReader is set up to be the output Surface for the actual frames to
     * be captured during CaptureDesign.startCapture(...). This is the callback
     * listener instance for it when such an image is available.
     *
     * It must parse the incoming images to see which is a true frame to save, and
     * which is from the AF/AE process (which is different from the preview
     * process).
     *
     * If it is a true frame to save, it passes it to the CaptureDesign's
     * DesignResult to be written to a file once both the image and its associated
     * CaptureResult are ready.
     *
     * Simply close the images from the AE/AF sequences to free up space.
     */
    private final ImageReader.OnImageAvailableListener mOnImageAvailableListener
            = new ImageReader.OnImageAvailableListener() {

        @Override
        public void onImageAvailable(ImageReader reader) {
            Log.v(APP_TAG,"IMAGE READY!");

            Image image = reader.acquireNextImage();

            // Parse to see if this image came from the Auto Requests or the Capture Request
            DesignResult designResult = mDesign.getDesignResult();
            // First check to see if mDesign even has a design result yet. This
            // check may fail in the case of recurring auto requests still in
            // the pipeline after a design has been fully captured and after
            // mDesign has been reassigned to a new CaptureDesign object
            if (designResult!=null){
                if (designResult.containsCaptureTimestamp(image.getTimestamp())){
                    designResult.recordImage(image);
                    Log.v(appFragment.APP_TAG," - - Image ready was a desired frame. Saving image. - -");
                } else {
                    Log.v(appFragment.APP_TAG," - - Image ready was from pre-capture auto sequence. Not saving image. - -");
                    image.close();
                }
            } else {
                Log.v(APP_TAG," - - Leftover Image. Discard. - - ");
                image.close();
            }
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
            Log.v(appFragment.APP_TAG,"camera device onClosed() called.");
        }

        @Override
        public void onDisconnected(CameraDevice camera){
            Log.v(appFragment.APP_TAG,"camera device onDisconnected() called.");
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
            Activity activity = getActivity();
            if (null != activity) {
                activity.finish();
            }
        }

        @Override
        public void onOpened(CameraDevice camera){
            Log.v(APP_TAG,"CameraDevice opened correctly.");
            mCameraOpenCloseLock.release();
            mCamera = camera;
            // Now that camera is open, and we know the preview Surface is
            // ready to receive from it, try creating a CameraCaptureSession.
            updateSession();
        }
    };



    /* Callback for the preview Surface's SurfaceHolder. We use this to know when
     * the Surface is ready to receive an image from the camera before we load it.
     */
    private final SurfaceHolder.Callback mHolderCallback =
            new SurfaceHolder.Callback(){

                Size mTargetSize = null;

                @Override
                public void surfaceCreated(SurfaceHolder holder){
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
                    int[] formats = mStreamMap.getOutputFormats();
                    long minTime = Long.MAX_VALUE; // min frame time of feasible size. Want to minimize.
                    long maxSize = 0; // area of feasible output size. Want to maximize.
                    for (int formatInd=0; formatInd<formats.length; formatInd++){
                        for (int yuvInd=0; yuvInd<yuvFormats.length; yuvInd++){
                            if (formats[formatInd]==yuvFormats[yuvInd] && null==mTargetSize){
                                // This is a valid YUV format, so find its output times
                                // and sizes
                                Log.v(APP_TAG,"YUV format: " + CameraReport.cameraConstantStringer("android.graphics.ImageFormat",formats[formatInd]));
                                Size[] sizes = mStreamMap.getOutputSizes(formats[formatInd]);
                                for (Size size : sizes){
                                    long frameTime = (mStreamMap.getOutputMinFrameDuration(formats[formatInd], size));
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
                    // Only try to access the camera once the preview Surface is ready
                    // to accept an image stream from it.
                    if ((width==mTargetSize.getWidth())&&(height==mTargetSize.getHeight())){
                        Log.d(APP_TAG,"Preview Surface set up correctly.");
                        accessCamera();
                    }
                }

                @Override
                public void surfaceDestroyed(SurfaceHolder holder){
                    // If the surface is destroyed, we definitely don't want the camera
                    // still sending data there, so close it.
                    closeCamera();

                }
            };



    /* Callback for the CaptureDesign's capture process. Whenever a frame for
     * the Design is captured, it is sent here for saving. When the entire
     * Design has been capture the DesignResult is sent here for write-out
     * as well. These things often need to be done from the main thread and with
     * resources of the whole app, not just the CaptureDesign, so they are
     * done here.
     */
    CaptureDesign.DesignCaptureCallback mDesignCaptureCallback = new CaptureDesign.DesignCaptureCallback(){
        @Override
        void onImageReady(Image image, CaptureResult result, String filename){

            File IM_SAVE_DIR = new File(appFragment.CAPTURE_DIR,mDesign.getDesignName());
            IM_SAVE_DIR.mkdir();

            // Post the images to be saved on another thread
            mImageSaverHandler.post(new ImageSaver(image, result, mCamChars, IM_SAVE_DIR, filename));
        }

        @Override
        void onFinished(final DesignResult designResult){

            // Actually save the images and metadata and request
            File IM_SAVE_DIR = new File(appFragment.CAPTURE_DIR,mDesign.getDesignName());

            // First, save JSON file with array of metadata
            File metadataFile = new File(IM_SAVE_DIR,mDesign.getDesignName() + "_capture_metadata"+".json");

            List<String> imageFileNames = designResult.getFilenames();

            CameraReport.writeCaptureResultsToFile(designResult.getCaptureResults(),imageFileNames, metadataFile);

            // Now, write out a txt file with the information of the original
            // request for the capture design, to see how it compares with results
            File requestFile = new File(IM_SAVE_DIR,mDesign.getDesignName()+"_design_request"+".txt");
            mDesign.writeOut(requestFile);

            // Now send the file names of every file just created for this
            // CaptureDesign to the system's record via the media scanner
            String[] filePathsToAdd = new String[imageFileNames.size()+2];
            int i=0;
            for(String img : imageFileNames){
                filePathsToAdd[i++]=new File(IM_SAVE_DIR,img).getAbsolutePath();
            }
            filePathsToAdd[i++]=metadataFile.getAbsolutePath();
            filePathsToAdd[i++]=requestFile.getAbsolutePath();
            addFilesToMTP(filePathsToAdd);


            // Make a new CaptureDesign based on the current
            // one, with a new name, so the current results
            // don't get overwritten if button pushed again.
            // Do this first, so views update in the next step.
            mDesign = new CaptureDesign(mDesign);

            // Remove "capturing design" sign from sight.
            // Must be done in main thread, which created the View.
            mMainHandler.post(new Runnable(){
                public void run(){
                    mCapturingDesignTextView.setVisibility(View.INVISIBLE);
                    mCaptureButton.setVisibility(View.VISIBLE);
                    // Display to the user how much time passed between the
                    // first opening and the last closing of the shutter. Counts on the image
                    // timestamp generator being at least SOMEWHAT accurate.
                    CaptureResult lastResult = designResult.getCaptureResult(designResult.getDesignLength()-1);
                    CaptureResult firstResult = designResult.getCaptureResult(0);
                    long captureTime = (lastResult.get(CaptureResult.SENSOR_TIMESTAMP)
                            + lastResult.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                            - firstResult.get(CaptureResult.SENSOR_TIMESTAMP))/1000000; //display in ms
                    Toast.makeText(getActivity(), "Capture Sequence Completed in " + captureTime + " ms.", Toast.LENGTH_SHORT).show();
                    updateDesignViews();
                }
            });


            // Restore the preview image now that the CaptureDesign
            // capture sequence is over.
            try{
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

            setButtonsClickable(true);
        }

    };




    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -
    // - - - - - - Begin Overridden/Lifecycle Activity Methods - - - - - - - -



    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup parent, Bundle savedInstanceState) {
        // Inflate the view from the layout XML
        View v = inflater.inflate(R.layout.camera_fragment, parent, false);
        return v;
    }


    @Override
    public void onViewCreated(View v, Bundle savedInstanceBundle){
        Log.v(APP_TAG,"onViewCreated() called.");

        // Create main app dir if none exists. If it couldn't be created, quit.
        if (!(APP_DIR.mkdir() || APP_DIR.isDirectory())){
            Toast.makeText(getActivity(), "Could not create application directory.", Toast.LENGTH_SHORT).show();
            getActivity().finish();
        }

        // Create folder for capture if none exists. If it can't be created, quit.
        if (!(CAPTURE_DIR.mkdir() || CAPTURE_DIR.isDirectory())){
            Toast.makeText(getActivity(), "Could not create capture directory.", Toast.LENGTH_SHORT).show();
            getActivity().finish();
        }

        // Create folder for designs if none exists. If it can't be created, quit.
        if (!(DESIGN_DIR.mkdir() || DESIGN_DIR.isDirectory())){
            Toast.makeText(getActivity(), "Could not create design directory.", Toast.LENGTH_SHORT).show();
            getActivity().finish();
        }

        // Gets camera characteristics, output stream info, camID.
        // Must be done before the OutputFormat/Size buttons are set up.
        loadCamera();

        // Gather the View resources for later manipulation.
        mApertureValueView = (TextView) v.findViewById(R.id.apertureValue);
        mSensitivityValueView = (TextView) v.findViewById(R.id.sensitivityValue);
        mExposureTimeValueView = (TextView) v.findViewById(R.id.exposureTimeValue);
        mFocusValueView = (TextView) v.findViewById(R.id.focusValue);
        mMinFrameTimeValueView = (TextView) v.findViewById(R.id.minFrameTimeValue);
        mOutputStallValueView = (TextView) v.findViewById(R.id.outputStallValue);
        mCaptureDesignListView = (ListView) v.findViewById(R.id.CaptureDesignListView);
        mInadequateCameraTextView = (TextView) v.findViewById(R.id.inadequateCameraTextView);
        mMainLinearLayout = (LinearLayout) v.findViewById(R.id.mainLinearLayout);
        mCapturingDesignTextView = (TextView) v.findViewById(R.id.capturingDesignTextView);

        // Set up the preview image AutoFitSurfaceView
        mPreviewSurfaceView = (AutoFitSurfaceView) v.findViewById(R.id.surfaceView);
        // Note we don't set the size of it here, because we wait until we know what size and
        // aspect ratio the camera is, and then fill the width set aside by the layout for it.
        // (That width being half of the total display width). Sizing is done in the
        // SurfaceHolder.Callback onCreate() method.

        // Set up our special ArrayAdapter for the List View of Exposures
        mCaptureDesignAdapter = new ExposureArrayAdapter(getActivity(),mDesign,mDisplayOptions);
        mCaptureDesignListView.setAdapter(mCaptureDesignAdapter);


        // Update text fields indicating the time constraints for these settings
        mOutputStallValueView.setText(CameraReport.nsToString(mStreamMap.getOutputStallDuration(
                mOutputFormats.get(mOutputFormatInd), mOutputSizes[mOutputSizeInd])));
        mMinFrameTimeValueView.setText(CameraReport.nsToString(mStreamMap.getOutputMinFrameDuration(
                mOutputFormats.get(mOutputFormatInd), mOutputSizes[mOutputSizeInd])));

        mDesignNameEditText = (EditText) v.findViewById(R.id.designNameEditText);
        mDesignNameEditText.setOnEditorActionListener(new TextView.OnEditorActionListener() {

            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId==EditorInfo.IME_ACTION_DONE){
                    mDesign.setDesignName(v.getText().toString());
                }
                return false;
            }
        });
        mDesignNameEditText.setText(mDesign.getDesignName());


		/* - - - Set up ALL THE BUTTONS! - - -
		 * Most of the following buttons pass an array of string labels to the
		 * SelectByLabelActivity activity class. Values returned by this are
		 * picked up in the onActivityResult() function, overridden below. 
		 * That function then takes the relevant returned array index and sets
		 * the new view and CaptureDesign values appropriately. 
		 * 
		 * The string arrays passed by these button push functions must
		 * correspond to the arrays of values the return index goes into.
		 */

        // Set up the settings button
        mSettingsButton = (Button) v.findViewById(R.id.settingsButton);
        mSettingsButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(getActivity(),SettingsActivity.class));
            }
        });


        // Set up output format button
        mOutputFormatButton = (Button) v.findViewById(R.id.Button_formatChoice);
        mOutputFormatButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(getActivity(), SelectByLabelActivity.class);
                intent.putExtra(SelectByLabelActivity.TAG_DATA_LABELS,mOutputFormatLabels.toArray(new String[mOutputFormatLabels.size()]));
                startActivityForResult(intent, OUTPUT_FORMAT);
            }
        });
        //mOutputFormat = mImageFormats.get(mOutputFormatInd);
        mOutputFormatButton.setText(CameraReport.cameraConstantStringer("android.graphics.ImageFormat", mOutputFormats.get(mOutputFormatInd)));


        // Set up output size button
        mOutputSizeButton = (Button) v.findViewById(R.id.Button_sizeChoice);
        mOutputSizeButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(getActivity(), SelectByLabelActivity.class);
                // SelectByLabelActivity takes a string array, so convert these
                // Sizes accordingly
                String[] temp = new String[mOutputSizes.length];
                for (int i = 0; i<mOutputSizes.length; i++){
                    temp[i] = mOutputSizes[i].toString();
                }
                intent.putExtra(SelectByLabelActivity.TAG_DATA_LABELS,temp);
                startActivityForResult(intent, OUTPUT_SIZE);
            }
        });
        mOutputSizeButton.setText(mOutputSizes[mOutputSizeInd].toString());


        // Set up processing button
        mProcessingButton = (Button) v.findViewById(R.id.Button_processingChoice);
        mProcessingButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(getActivity(), SelectByLabelActivity.class);
                String[] choices = new String[ProcessingChoice.values().length];
                for (int i=0; i<ProcessingChoice.values().length;i++){
                    choices[i] = ProcessingChoice.getChoiceByIndex(i).toString();
                }
                intent.putExtra(SelectByLabelActivity.TAG_DATA_LABELS,choices);
                startActivityForResult(intent, PROCESSING_SETTING);
            }
        });
        mProcessingButton.setText(mDesign.getProcessingSetting().toString());


        // Set up Load Design button
        mLoadDesignButton = (Button) v.findViewById(R.id.loadDesignButton);
        mLoadDesignButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                // Keep file names around for when selection index comes back
                mFileNames = DESIGN_DIR.list();

                if (mFileNames==null || mFileNames.length==0){
                    Toast.makeText(getActivity(),"No Design JSONs found.", Toast.LENGTH_SHORT).show();
                    return;
                }

                Intent intent = new Intent(getActivity(), SelectByLabelActivity.class);
                intent.putExtra(SelectByLabelActivity.TAG_DATA_LABELS,mFileNames);
                startActivityForResult(intent, LOAD_DESIGN);
            }
        });


        // set up Split Exposures button
        mSplitAmountButton = (Button) v.findViewById(R.id.splitAmountButton);
        mSplitAmountButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v){
                Intent intent = new Intent(getActivity(), GenerateDesignFromTemplateActivity.class);
                startActivityForResult(intent,GENERATE_DESIGN);
            }
        });


        // Set up the button that actually takes the pictures!
        mCaptureButton = (Button) v.findViewById(R.id.take_burst_button);
        mCaptureButton.setOnClickListener(new OnClickListener(){
            @Override
            public void onClick(View v){
                if (mDesign.getExposures().size()>0){
                    // Create and register a callback with the CaptureDesign, which
                    // will be called when it finishes capturing all of the frames.
                    mDesign.registerCallback(mDesignCaptureCallback);

                    // Turn off the buttons so the user doesn't accidentally mess up capture
                    setButtonsClickable(false);

                    // Now, tell the CaptureDesign to actually start the capture
                    // process and hand it the relevant pieces. Note the
                    // CaptureDesign class actually handles all of the commands to
                    // the camera.

                    // But first, check to see if we should use a delay timer or not.
                    long delay = (mUseDelay)? 5000 : 0;
                    CountDownTimer countDownTimer = new CountDownTimer(delay, 1000) {
                        public void onTick(long secondsTillFinish){
                            mCaptureButton.setText((secondsTillFinish/1000) + " s");
                        }
                        public void onFinish(){
                            mDesign.startCapture(mCamera, mCaptureSession, mImageReader.getSurface(),
                                    mPreviewSurfaceHolder.getSurface(), mBackgroundHandler,mCamChars);
                            // inform user sequence is being captured
                            mCapturingDesignTextView.setVisibility(View.VISIBLE);
                            mCaptureButton.setVisibility(View.INVISIBLE);
                            mCaptureButton.setText(R.string.captureText);
                        }
                    };
                    countDownTimer.start();
                }
            }
        });


        // Catch inadequate cameras, those which will not allow manual setting of exposure
        // settings and/or processing settings
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
            mInadequateCameraTextView.setVisibility(View.VISIBLE);
            mMainLinearLayout.setAlpha(0.8f);
            mInadequateCameraFlag = true;
            setButtonsClickable(false);
        }
    }


    // onResume() called every time activity restarts, good place for
    // re-establishing resources like threads, surfaces, camera access.
    @Override
    public void onResume(){
        super.onResume();
        Log.v(APP_TAG,"onResume() called.");

        // Load the user settings for the use of delay and the display of parameters
        SharedPreferences settings = getActivity().getSharedPreferences(APP_TAG,Context.MODE_MULTI_PROCESS);
        mUseDelay = settings.getBoolean(SettingsActivity.USE_DELAY_KEY,false);
        mDisplayOptions.showExposureTime = settings.getBoolean(SettingsActivity.SHOW_EXPOSURE_TIME,true);
        mDisplayOptions.showAperture = settings.getBoolean(SettingsActivity.SHOW_APERTURE,false);
        mDisplayOptions.showSensitivity = settings.getBoolean(SettingsActivity.SHOW_SENSITIVITY,true);
        mDisplayOptions.showFocalLength = settings.getBoolean(SettingsActivity.SHOW_FOCAL_LENGTH,false);
        mDisplayOptions.showFocusDistance = settings.getBoolean(SettingsActivity.SHOW_FOCUS_DISTANCE,true);

        // User choice of display settings could have changed, inform
        mCaptureDesignAdapter.updateDisplaySettings(mDisplayOptions);

        establishActiveResources();
    }

    // onPause() called before the activity is finished, good place for
    // freeing resources that need to be freed.
    @Override
    public void onPause(){
        Log.v(APP_TAG,"onPause() called.");
        closeCamera();
        stopBackgroundThreads();
        super.onPause();
    }


    /* void onActivityResult(...)
     *
     * This function fields results from all select-from-list activities
     * launched by various pushbuttons. Updates relevant member variables and
     * calls appropriate functions to update Views as necessary.
     *
     * All relevant calls to this come from SelectByLabelActivity's, meaning
     * that each return is a single int value which is an index into an array.
     * When the index is received, record it and make any udpates necessary.
     *
     */
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Log.v(APP_TAG,"onActivityResult() called.");

        switch(requestCode) {
            // Returned from button push for generating a new Design from template
            case (GENERATE_DESIGN) :
                if (resultCode == Activity.RESULT_OK){
                    float lowBound = data.getFloatExtra(DataTags.LOW_BOUND.toString(),0.0f);
                    float highBound = data.getFloatExtra(DataTags.HIGH_BOUND.toString(),0.0f);
                    int nExp = data.getIntExtra(DataTags.N_EXP.toString(),1);
                    int templateInd = data.getIntExtra(DataTags.TEMPLATE_ID.toString(),0);
                    Log.v(APP_TAG,"templateInd: " + templateInd +
                            " nExp: " + nExp +
                            " low bound: " + lowBound +
                            " high bound: " + highBound);

                    DesignTemplate template = DesignTemplate.getTemplateByIndex(templateInd);
                    Log.v(APP_TAG,"Design Template: " + template);

                    switch (template){
                        case BURST:
                            mDesign = CaptureDesign.Creator.burst(nExp);
                            break;
                        case SPLIT_TIME:
                            mDesign = CaptureDesign.Creator.splitExposureTime(nExp);
                            break;
                        case RACK_FOCUS:
                            Range<Float> focusRange = new Range<Float>(lowBound,highBound);
                            mDesign = CaptureDesign.Creator.focusBracketAbsolute(mCamChars,focusRange,nExp);
                            break;
                        case BRACKET_EXPOSURE_TIME_ABSOLUTE:
                            Range<Long> expTimeRange = new Range<Long>((long)lowBound,(long)highBound);
                            mDesign = CaptureDesign.Creator.exposureTimeBracketAbsolute(mCamChars,expTimeRange,nExp);
                            break;
                        case BRACKET_EXPOSURE_TIME_RELATIVE:
                            Range<Float> stopRange = new Range<Float>(lowBound,highBound);
                            mDesign = CaptureDesign.Creator.exposureTimeBracketAroundAuto(stopRange,nExp);
                            break;
                        case BRACKET_ISO_ABSOLUTE:
                            Range<Integer> isoRange = new Range<Integer>((int)lowBound,(int)highBound);
                            mDesign = CaptureDesign.Creator.isoBracketAbsolute(mCamChars,isoRange,nExp);
                            break;
                        case BRACKET_ISO_RELATIVE:
                            stopRange = new Range<Float>(lowBound,highBound);
                            mDesign = CaptureDesign.Creator.isoBracketAroundAuto(stopRange,nExp);
                            break;
                        default:
                            // do nothing
                    }
                    updateDesignViews();
                }

                break;

            // Returned from button push for selecting new output image format
            case (OUTPUT_FORMAT) :
                if (resultCode == Activity.RESULT_OK) {
                    mOutputFormatInd = data.getIntExtra(SelectByLabelActivity.TAG_SELECTED_INDEX,0);

                    // Since output format change may also change the available
                    // sizes, reset that selection index as well

                    // Future: fix this to not reset if not necessary
                    mOutputSizeInd = 0;
                    mOutputSizes = mStreamMap.getOutputSizes(mOutputFormats.get(mOutputFormatInd));
                    updateConstraintViews();
                }
                break;

            // Returned from button push for selecting new output image size
            case (OUTPUT_SIZE) :
                if (resultCode == Activity.RESULT_OK){
                    mOutputSizeInd = data.getIntExtra(SelectByLabelActivity.TAG_SELECTED_INDEX,0);
                    updateConstraintViews();
                }
                break;

            // Returned from button push for selecting new processing approach
            case (PROCESSING_SETTING) :
                if (resultCode == Activity.RESULT_OK) {
                    // Record choice in the CaptureDesign for when necessary
                    int result = data.getIntExtra(SelectByLabelActivity.TAG_SELECTED_INDEX, 0);
                    mDesign.setProcessingSetting(CaptureDesign.ProcessingChoice.getChoiceByIndex(result));
                    mProcessingButton.setText(mDesign.getProcessingSetting().toString());
                    // If High Quality processing requested, stall times not
                    // well defined, so indicate this visually.
                    updateConstraintViews();

                }
                break;

            // Returned from button push for selecting a CaptureDesign from JSON
            case (LOAD_DESIGN) :
                if (resultCode==Activity.RESULT_OK) {
                    String fileName = mFileNames[data.getIntExtra(SelectByLabelActivity.TAG_SELECTED_INDEX,0)];
                    File file = new File(DESIGN_DIR,fileName);
                    // Create a new captureDesign based on JSON file selected. I KNOW this is terrible
                    // misuse of incorrect checked exceptions. Will return to this when possible.
                    try {
                        CaptureDesign newDesign = CaptureDesign.Creator.loadDesignFromJson(file);
                        newDesign.setProcessingSetting(mDesign.getProcessingSetting());
                        mDesign = newDesign;
                    } catch (IOException ioe){
                        Toast.makeText(getActivity(),"Error reading JSON file.",Toast.LENGTH_LONG).show();
                    } catch (NoSuchFieldException nsme){
                        Toast.makeText(getActivity(),"Error in Capture Design JSON file: incorrect variable form.",Toast.LENGTH_LONG).show();
                    }

                    // CaptureDesign changed, so update View of its exposures.
                    updateDesignViews();
                }
                break;
        }
    }

    // - - - - - - - - - - End Overridden/Lifecycle Activity Methods - - - - -
    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -






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
    protected void loadCamera(){
        Log.v(APP_TAG,"loadCamera() called.");
        // Load the camera manager
        cm = (CameraManager) getActivity().getSystemService(Context.CAMERA_SERVICE);

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

            // This camera can produce only certain image formats and certain
            // output sizes for those formats. We need this info for display
            // and configuration purposes, so grab it from the stream map.
            // Right now, we are only set up to work with these formats:
            // JPEG, RAW_SENSOR ,YUV_420_888
            mStreamMap = mCamChars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            int[] temp = mStreamMap.getOutputFormats();
            mOutputFormats = new ArrayList<Integer>();
            mOutputFormatLabels = new ArrayList<String>();
            for (int i = 0; i<temp.length; i++){
                if (temp[i]==ImageFormat.JPEG
                        || temp[i]==ImageFormat.RAW_SENSOR
                        || temp[i]==ImageFormat.YUV_420_888){
                    mOutputFormats.add(temp[i]);
                    mOutputFormatLabels.add(CameraReport.cameraConstantStringer("android.graphics.ImageFormat",temp[i]));
                }
            }
            mOutputSizes = mStreamMap.getOutputSizes(mOutputFormats.get(mOutputFormatInd));
        }
        catch (CameraAccessException cae) {
            // If we couldn't load the camera, that's a bad sign. Just quit.
            Toast.makeText(getActivity(), "Error loading CameraDevice", Toast.LENGTH_SHORT).show();
            cae.printStackTrace();
            getActivity().finish();
        }
    }


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
        mMainHandler = new Handler(getActivity().getMainLooper()){
            @Override
            public void handleMessage(Message inputMessage){
                // If mPreviewCCB sent back some CaptureResults, save them
                // and update views.
                if (inputMessage.what == AUTO_RESULTS){
                    mAutoResult = (CaptureResult) inputMessage.obj;
                    updateAutoViews();
                } else {
                    super.handleMessage(inputMessage);
                }
            }
        };

        // Set up background threads so as not to block the main UI thread.
        // One for all the camera callbacks.
        if (null==mBackgroundThread){
            mBackgroundThread = new HandlerThread("CameraBackground");
            mBackgroundThread.start();
            mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
        }
        // One for the ImageSaver actions, to not block the camera callbacks.
        if (null==mImageSaverThread){
            mImageSaverThread = new HandlerThread("ImageSaver");
            mImageSaverThread.start();
            mImageSaverHandler = new Handler(mImageSaverThread.getLooper());
        }

        // Set up the SurfaceHolder of the appropriate View for being a
        // preview. Doing so initiates the loading of the camera, once the
        // SurfaceHolder.Callback is invoked.
        mPreviewSurfaceHolder = mPreviewSurfaceView.getHolder();
        mPreviewSurfaceHolder.addCallback(mHolderCallback);
    }



    /* void accessCamera()
     *
     * Protected way of trying to open the CameraDevice. Only called once
     * the preview surface is formatted to the correct size to receive preview
     * images, since those will be the first thing shown.
     */
    protected void accessCamera(){
        Log.v(APP_TAG,"accessCamera() called.");
        try {
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            cm.openCamera(backCamId, CDSC, mBackgroundHandler);
            Log.v(APP_TAG,"Open camera called successfully.");
        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
        }
    }



    /* void updateSession()
     *
     * Every time a new output image size or format is requested, we must create an
     * appropriate new ImageReader to receive these outputs. This also requires we
     * create a new CameraCaptureSession since all output Surfaces must be
     * registered at its creation.
     *
     * The session must be able to output to both the ImageReader Surface for output
     * and the SurfaceHolder surface for preview.
     */
    private void updateSession(){
        Log.v(APP_TAG,"updateSession() called.");

        // Establish output surface (ImageReader) resources and register our callback with it.
        mImageReader = ImageReader.newInstance(
                mOutputSizes[mOutputSizeInd].getWidth(), mOutputSizes[mOutputSizeInd].getHeight(),
                mOutputFormats.get(mOutputFormatInd),
                imageBufferSizer());  // defer to auxiliary function to determine size of allocation
        mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, mBackgroundHandler);

        try{
            // Try to create a capture session, with its callback being handled
            // in a background thread.
            mCamera.createCaptureSession(
                    Arrays.asList(mPreviewSurfaceHolder.getSurface(),mImageReader.getSurface()),
                    CCSSC,
                    mBackgroundHandler);
        } catch (CameraAccessException cae) {
            // If we couldn't create a capture session, we have trouble. Abort!
            cae.printStackTrace();
            getActivity().finish();
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
    int imageBufferSizer(){
        return Math.min(30,mDesign.getExposures().size())+2;
    }



    /* void closeCamera()
     *
     * Safely close the CaptureSession and the CameraDevice.
     * Called whenever the Activity is paused.
     */
    protected void closeCamera(){
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
                mPreviewSurfaceHolder = null;
            }

        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        } finally {
            mCameraOpenCloseLock.release();
        }
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


    /* void updateAutoViews()
     *
     * Extracts the A3 values from the constantly-refreshing preview
     * capture results and displays them on the display to the user.
     *
     * These values include: aperture, sensitivity (ISO),
     * exposure time, and focus distance.
     *
     * Needs to run on main UI thread to access the View objects.
     */
    private void updateAutoViews(){
        // Only do the following if the AutoResults will actually have the requested data,
        // if the camera has the necessary capabilities
        if (!mInadequateCameraFlag) {
            Float rApertureVal = mAutoResult.get(CaptureResult.LENS_APERTURE);
            if (null != rApertureVal) {
                mApertureValueView.setText("f" + rApertureVal);
            } else {
                mApertureValueView.setText(R.string.no_value);
            }

            Integer rSensitivityVal = mAutoResult.get(CaptureResult.SENSOR_SENSITIVITY);
            if (null != rSensitivityVal) {
                mSensitivityValueView.setText(rSensitivityVal.toString());
            } else {
                mSensitivityValueView.setText(R.string.no_value);
            }

            Long rExposureTimeVal = mAutoResult.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            if (null != rExposureTimeVal) {
                mExposureTimeValueView.setText(CameraReport.nsToString(rExposureTimeVal));
            } else {
                mExposureTimeValueView.setText(R.string.no_value);
            }

            Float rFocusVal = mAutoResult.get(CaptureResult.LENS_FOCUS_DISTANCE);
            if (null != rFocusVal) {
                if (mCamChars.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION)
                        == CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED) {
                    mFocusValueView.setText(CameraReport.diopterToMeters(rFocusVal) + "*");
                } else {
                    mFocusValueView.setText(CameraReport.diopterToMeters(rFocusVal));
                }
            } else {
                mFocusValueView.setText(R.string.no_value);
            }
        }
    }


    /* void updateConstraintViews()
     *
     * When a new output image format or size is selected, we must update the
     * appropriate labels on the buttons, but also the text displaying the
     * camera's time constraints for this combination.
     *
     * E.g. JPEG or RAW_SENSOR images will introduce a stall time between
     * frames to be captured due to processing/readout bottlenecks, which is
     * displayed as "Stall Duration." Also, all format/size combinations have
     * some minimum frame time, so if the user requests an exposure time less
     * than this, there will be effective stall time between frames.
     */
    public void updateConstraintViews(){
        Log.v(APP_TAG,"updateConstraintViews() called.");
        // Update text on buttons to reflect values selected
        mOutputSizeButton.setText(mOutputSizes[mOutputSizeInd].toString());
        mOutputFormatButton.setText(
                CameraReport.cameraConstantStringer("android.graphics.ImageFormat",mOutputFormats.get(mOutputFormatInd)));

        // Update text fields indicating the time constraints for these settings
        mOutputStallValueView.setText(CameraReport.nsToString(mStreamMap.getOutputStallDuration(
                mOutputFormats.get(mOutputFormatInd), mOutputSizes[mOutputSizeInd])));
        mMinFrameTimeValueView.setText(CameraReport.nsToString(mStreamMap.getOutputMinFrameDuration(
                mOutputFormats.get(mOutputFormatInd), mOutputSizes[mOutputSizeInd])));

        // If High Quality processing required, could be even longer wait times, so
        // indicated this with a "+"
        if (mDesign.getProcessingSetting()==ProcessingChoice.HIGH_QUALITY){
            mOutputStallValueView.setText(mOutputStallValueView.getText() + "+");
        }
    }


    /* void updateDesignListView()
     *
     * When the CaptureDesign has changed, update the displayed list of its
     * exposure parameters accordingly. And its name, if it changed.
     */
    public void updateDesignViews(){
        Log.v(APP_TAG,"updateDesignViews() called.");
        mCaptureDesignAdapter.registerNewCaptureDesign(mDesign);
    }


    /* void generateDeviceReport(metadata)
     *
     * Creates a new JSON file and writes the capabilities of the camera device
     * being used, as per the CameraCharacteristics metadata.
     */
    public void generateDeviceReport(CameraCharacteristics meta){
        Log.v(APP_TAG,"generateDeviceReport() called.");

        File file = new File(appFragment.APP_DIR,"cameraReport.json");
        CameraReport.writeCharacteristicsToFile(meta, file);

        addFileToMTP(file.getAbsolutePath());

        Log.v(APP_TAG,CameraReport.cameraConstantStringer("android.graphics.ImageFormat",ImageFormat.YV12));
    }



    /* void setButtonsClickable(boolean)
     *
     * Turn on/off clickability of all buttons of app GUI. Turn off so that
     * design capture sequence can complete without interruption, turn it
     * back on afterwards.
     */
    private void setButtonsClickable(boolean onoff){
        Log.v(APP_TAG,"setButtonsClickable() called.");
        mLoadDesignButton.setClickable(onoff);
        mOutputFormatButton.setClickable(onoff);
        mOutputSizeButton.setClickable(onoff);
        mProcessingButton.setClickable(onoff);
        mSplitAmountButton.setClickable(onoff);
    }



    /* void addFilesToMTP(String[])
     *
     * Adds files with full paths indicated in the input string array to be 
     * recognized by the system via the mediascanner.
     */
    public void addFilesToMTP(String[] filePathsToAdd){
        MediaScannerConnection.scanFile(getActivity(),filePathsToAdd,null,
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
    public void addFileToMTP(String file){
        addFilesToMTP(new String[]{file});
    }


}

