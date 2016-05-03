package com.devcam;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
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
import java.util.List;


public class MainDevCamActivity extends Activity{
    public final static String APP_TAG = "devCam";

    private DevCam mDevCam;

    protected Handler mMainHandler;
    private HandlerThread mImageSaverThread;
    protected Handler mImageSaverHandler;
    protected AutoFitSurfaceView mPreviewSurfaceView;
    private SurfaceHolder mPreviewSurfaceHolder;

    // Get paths to (or create) the folders where the images are to be saved
    // and the design JSONs are located.
    // A little sloppy, should check that there is external storage first.
    final static File APP_DIR =
            new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),"devCam");
    final static File CAPTURE_DIR = new File(APP_DIR,"Captured");
    final static File DESIGN_DIR = new File(APP_DIR,"Designs");

    // The following are identifying tags for specific intents of requests
    // sent to the SelectByLabelActivity class for a result.
    public final static int OUTPUT_FORMAT = 0;
    public final static int OUTPUT_SIZE = 1;
    public final static int PROCESSING_SETTING = 5;
    public final static int LOAD_DESIGN = 6;
    public final static int GENERATE_DESIGN = 7;

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


    protected ImageReader mImageReader;
    private int mOutputFormatInd = 0;
    private Size[] mOutputSizes;
    private int mOutputSizeInd = 0;

    // returned from the preview repeating request, used to set realtime values in display
    protected CaptureResult mAutoResult;

    CameraCharacteristics mCamChars;
    StreamConfigurationMap mStreamMap;

    List<Integer> mOutputFormats;
    List<String> mOutputFormatLabels;

    Context mContext;

    // Array of names of JSON CaptureDesign files in appropriate directory
    private String[] mFileNames;

    private boolean mInadequateCameraFlag;

    boolean mUseDelay = false; // flag reflecting state of the delay switch

    // This simply holds the user options for displaying parameters. They are loaded in onResume().
    ExposureArrayAdapter.DisplayOptionBundle mDisplayOptions = new ExposureArrayAdapter.DisplayOptionBundle();

    // The CaptureDesign the app is working with at the moment
    private CaptureDesign mDesign = new CaptureDesign();
    private DesignResult mDesignResult;
    private List<String> mWrittenFilenames;
    private CaptureDesign mNextDesign = new CaptureDesign();

    // Keep track of how many image files have been written out, which may happen much later
    // than the event of them being saved.
    private int mNumImagesLeftToSave;



    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -
    // - - - - - - - - - Important Callback / Listener objects - - - - - - - -

    /* The ImageReader is set up to be the output Surface for the actual frames to
         * be captured during CaptureDesign.startCapture(...). This is the callback
         * listener instance for it when such an image is available.
         *
         * When an Image is ready, it passes it to the CaptureDesign's
         * DesignResult to be written to a file once both the image and its associated
         * CaptureResult are ready.
         */


    private final ImageReader.OnImageAvailableListener mOnImageAvailableListener
            = new ImageReader.OnImageAvailableListener() {

        @Override
        public void onImageAvailable(ImageReader reader) {
            Image image = reader.acquireNextImage();
            Log.v(APP_TAG, "IMAGE READY! Timestamp: " + image.getTimestamp()/1000);
            if (mDesignResult!=null) {
                mDesignResult.recordImage(image);
            } else {
                Log.v(APP_TAG,"ERROR: no DesignResult to access!!");
            }

        }
    };



    ImageSaver.WriteOutCallback mWriteOutCallback = new ImageSaver.WriteOutCallback() {

        @Override
        void onImageSaved(boolean success, String filename) {

            // Now check to see if all of the images have been saved. If so, we can restore control
            // to the user and remove the "Saving images" sign.
            mNumImagesLeftToSave--;
            Log.v(APP_TAG,"Writeout of image: " + filename + " : " + success);
            Log.v(APP_TAG, mNumImagesLeftToSave + " image files left to save.");

            if (mNumImagesLeftToSave ==0) {
                Log.v(APP_TAG, "Done saving images. Restore control to app.");

                // Remove "saving images" sign from sight.
                // Must be done in main thread, which created the View.
                mMainHandler.post(new Runnable() {
                    public void run() {
                        mCapturingDesignTextView.setVisibility(View.INVISIBLE);
                        mCaptureButton.setVisibility(View.VISIBLE);
                    }
                });
                setButtonsClickable(true);
            }

            // Register the saved Image with the file system
            File IM_SAVE_DIR = new File(CAPTURE_DIR,mDesign.getDesignName());
            File imFile = new File(IM_SAVE_DIR,filename);
            CameraReport.addFileToMTP(mContext, imFile.getAbsolutePath());


        }
    };



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
        public void onAllCapturesReported(final DesignResult designResult){
            Log.v(DevCam.APP_TAG,"All Images+Metadata have been paired by DesignResult. ");

            // Here, save the metadata and the request itself, and register them with the system
            // The onImageSaved() callback method will register the actual image files when ready.

            File IM_SAVE_DIR = new File(CAPTURE_DIR,mDesign.getDesignName());

            // First, save JSON file with array of metadata
            File metadataFile = new File(IM_SAVE_DIR,mDesign.getDesignName() + "_capture_metadata"+".json");
            CameraReport.writeCaptureResultsToFile(mDesignResult.getCaptureResults(),mWrittenFilenames, metadataFile);
            CameraReport.addFileToMTP(mContext, metadataFile.getAbsolutePath());

            // Now, write out a txt file with the information of the original
            // request for the capture design, to see how it compares with results
            File requestFile = new File(IM_SAVE_DIR,mDesign.getDesignName()+"_design_request"+".txt");
            mDesign.writeOut(requestFile);
            CameraReport.addFileToMTP(mContext, requestFile.getAbsolutePath());

            // Replace old design now that it is done
            mDesign = mNextDesign;
            mDesignResult = null;
        };
    };



    private DevCam.DevCamListener mDevCamCallback = new DevCam.DevCamListener() {
        @Override
        void onAutoResultsReady(CaptureResult result) {
            mAutoResult = result;
            updateAutoViews();
        }

        @Override
        void onCameraDeviceError(int error) {
            super.onCameraDeviceError(error);
            if (error == DevCam.INADEQUATE_CAMERA) {
                Log.v(APP_TAG,"Camera is not adequate for devCam.");
                mInadequateCameraFlag = true;
                mInadequateCameraTextView.setVisibility(View.VISIBLE);
                mMainLinearLayout.setAlpha(0.8f);
                setButtonsClickable(false);
            }
        }


        @Override
        void onCaptureFailed(int code) {
            super.onCaptureFailed(code);

            // Inform user of failure
            mMainHandler.post(new Runnable() {
                public void run() {
                    Toast.makeText(mContext, "Failed to Capture Design.", Toast.LENGTH_SHORT).show();
                }
            });

            // Restore the interface accessibility.
            setButtonsClickable(true);

        }

        void onCaptureStarted(Long timestamp){
            super.onCaptureStarted(timestamp);
            mDesignResult.recordCaptureTimestamp(timestamp);
        };
        void onCaptureCompleted(CaptureResult result){
            super.onCaptureCompleted(result);
            mDesignResult.recordCaptureResult(result);
        };
        void onCaptureSequenceCompleted(){
            super.onCaptureSequenceCompleted();


            // Remove "capturing design" sign from sight.
            // Must be done in main thread, which created the View.
            mMainHandler.post(new Runnable(){
                public void run(){
                    mCapturingDesignTextView.setText("Saving Images.");

                    // Display to the user how much time passed between the
                    // first opening and the last closing of the shutter. Counts on the image
                    // timestamp generator being at least SOMEWHAT accurate.
                    if (mDesignResult != null) {
                        CaptureResult lastResult = mDesignResult.getCaptureResult(mDesignResult.getDesignLength() - 1);
                        CaptureResult firstResult = mDesignResult.getCaptureResult(0);
                        long captureTime = (lastResult.get(CaptureResult.SENSOR_TIMESTAMP)
                                + lastResult.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                                - firstResult.get(CaptureResult.SENSOR_TIMESTAMP));
                        Toast.makeText(mContext, "Capture Sequence Completed in " + CameraReport.nsToString(captureTime), Toast.LENGTH_SHORT).show();
                        updateDesignViews();
                    } else {
                        Toast.makeText(mContext, "Capture Sequence Completed quicky.", Toast.LENGTH_SHORT).show();
                        updateDesignViews();
                    }
                }
            });
        };

    };




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
        mSettingsButton.setClickable(onoff);
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
        if (mDesign.getProcessingSetting()== CaptureDesign.ProcessingChoice.HIGH_QUALITY){
            mOutputStallValueView.setText(mOutputStallValueView.getText() + "+");
        }
    }


    /* void updateDesignViews()
         *
         * When the CaptureDesign has changed, update the displayed list of its
         * exposure parameters accordingly. And its name, if it changed.
         */
    public void updateDesignViews(){
        Log.v(APP_TAG, "updateDesignViews() called.");
        mCaptureDesignAdapter.registerNewCaptureDesign(mDesign);
        mDesignNameEditText.setText(mDesign.getDesignName());
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





    //- - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -
    //- - - - - - - - - - - - - - - - BEGIN LIFECYCLE METHODS - - - - - - - - - - - - - - - - - - -

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.v(APP_TAG,"* * * * * * * * * * * * * * * * * * * * * * * * * * * * * *");
        Log.v(APP_TAG, "DevCamActivity onCreate() called.");
        Toast.makeText(this,"devCam directory: " + APP_DIR, Toast.LENGTH_LONG).show();
        Log.v(APP_TAG,"devCam directory: " + APP_DIR);


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

        // Set the correct layout for this Activity
        setContentView(R.layout.camera_fragment);

        // Hide the action bar so the activity gets the full screen
        getActionBar().hide();

        mContext = this;


        // Gather the View resources for later manipulation.
        mApertureValueView = (TextView) findViewById(R.id.apertureValue);
        mSensitivityValueView = (TextView) findViewById(R.id.sensitivityValue);
        mExposureTimeValueView = (TextView) findViewById(R.id.exposureTimeValue);
        mFocusValueView = (TextView) findViewById(R.id.focusValue);
        mMinFrameTimeValueView = (TextView) findViewById(R.id.minFrameTimeValue);
        mOutputStallValueView = (TextView) findViewById(R.id.outputStallValue);
        mCaptureDesignListView = (ListView) findViewById(R.id.CaptureDesignListView);
        mInadequateCameraTextView = (TextView) findViewById(R.id.inadequateCameraTextView);
        mMainLinearLayout = (LinearLayout) findViewById(R.id.mainLinearLayout);
        mCapturingDesignTextView = (TextView) findViewById(R.id.capturingDesignTextView);


        // Set up the preview image AutoFitSurfaceView
        mPreviewSurfaceView = (AutoFitSurfaceView) findViewById(R.id.surfaceView);
        // Note we don't set the size of it here, because we wait until we know what size and
        // aspect ratio the camera is, and then fill the width set aside by the layout for it.
        // (That width being half of the total display width). Sizing is done in the
        // SurfaceHolder.Callback onCreate() method.

        mCamChars = DevCam.getCameraCharacteristics(this);

        mStreamMap = mCamChars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        // This camera can produce only certain image formats and certain
        // output sizes for those formats. We need this info for display
        // and configuration purposes, so grab it from the stream map.
        // Right now, we are only set up to work with these formats:
        // JPEG, RAW_SENSOR ,YUV_420_888
        StreamConfigurationMap streamMap = mCamChars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        int[] temp = streamMap.getOutputFormats();
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

        // Since labels and such depend on knowing things about the camera, we can only just now
        // access/initialize them.
        setupButtonsAndViews();


        mDevCam = DevCam.getInstance(this,mDevCamCallback);

    }



    @Override
    public void onResume(){
        super.onResume();
        establishActiveResources();
        Log.v(APP_TAG, "MainActivity onResume().");

        // Load the user settings for the use of delay and the display of parameters
        SharedPreferences settings = this.getSharedPreferences(APP_TAG,Context.MODE_MULTI_PROCESS);
        mUseDelay = settings.getBoolean(SettingsActivity.USE_DELAY_KEY,false);
        mDisplayOptions.showExposureTime = settings.getBoolean(SettingsActivity.SHOW_EXPOSURE_TIME,true);
        mDisplayOptions.showAperture = settings.getBoolean(SettingsActivity.SHOW_APERTURE,false);
        mDisplayOptions.showSensitivity = settings.getBoolean(SettingsActivity.SHOW_SENSITIVITY,true);
        mDisplayOptions.showFocalLength = settings.getBoolean(SettingsActivity.SHOW_FOCAL_LENGTH,false);
        mDisplayOptions.showFocusDistance = settings.getBoolean(SettingsActivity.SHOW_FOCUS_DISTANCE,true);

        // User choice of display settings could have changed, inform
        if (mCaptureDesignAdapter!=null){
            Log.v(APP_TAG,"Updating Display Settings in custom ListViewAdapter");
            mCaptureDesignAdapter.updateDisplaySettings(mDisplayOptions);
        }



    }


    @Override
    public void onPause(){
        mDevCam.stopCam();
        mPreviewSurfaceView.setVisibility(View.GONE);
        freeImageSaverResources();
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
     * Note that onResume() is called after this function, so this function doesn't need to do
     * things like update the CameraCaptureSession, since onResume() will do that.
     *
     */
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Log.v(APP_TAG, "onActivityResult() called.");

        switch(requestCode) {
            // Returned from button push for generating a new Design from template
            case (GENERATE_DESIGN) :
                if (resultCode == Activity.RESULT_OK){
                    float lowBound = data.getFloatExtra(GenerateDesignFromTemplateActivity.DataTags.LOW_BOUND.toString(),0.0f);
                    float highBound = data.getFloatExtra(GenerateDesignFromTemplateActivity.DataTags.HIGH_BOUND.toString(),0.0f);
                    int nExp = data.getIntExtra(GenerateDesignFromTemplateActivity.DataTags.N_EXP.toString(),1);
                    int templateInd = data.getIntExtra(GenerateDesignFromTemplateActivity.DataTags.TEMPLATE_ID.toString(),0);
                    Log.v(APP_TAG,"templateInd: " + templateInd +
                            " nExp: " + nExp +
                            " low bound: " + lowBound +
                            " high bound: " + highBound);

                    GenerateDesignFromTemplateActivity.DesignTemplate template = GenerateDesignFromTemplateActivity.DesignTemplate.getTemplateByIndex(templateInd);
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
                        Toast.makeText(mContext,"Error reading JSON file.",Toast.LENGTH_LONG).show();
                    } catch (NoSuchFieldException nsme){
                        Toast.makeText(mContext,"Error in Capture Design JSON file: incorrect variable form.",Toast.LENGTH_LONG).show();
                    }

                    // CaptureDesign changed, so update View of its exposures.
                    updateDesignViews();
                }
                break;
        }
    }





    void setupButtonsAndViews(){

        Log.v(APP_TAG,"setupButtonsAndViews() called.");

        // Update text fields indicating the time constraints for these settings
        mOutputStallValueView.setText(CameraReport.nsToString(mStreamMap.getOutputStallDuration(
                mOutputFormats.get(mOutputFormatInd), mOutputSizes[mOutputSizeInd])));
        mMinFrameTimeValueView.setText(CameraReport.nsToString(mStreamMap.getOutputMinFrameDuration(
                mOutputFormats.get(mOutputFormatInd), mOutputSizes[mOutputSizeInd])));
        // Set up our special ArrayAdapter for the List View of Exposures
        mCaptureDesignAdapter = new ExposureArrayAdapter(mContext, mDesign, mDisplayOptions);
        mCaptureDesignListView.setAdapter(mCaptureDesignAdapter);


        mDesignNameEditText = (EditText) findViewById(R.id.designNameEditText);
        mDesignNameEditText.setOnEditorActionListener(new TextView.OnEditorActionListener() {

            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
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
        mSettingsButton = (Button) findViewById(R.id.settingsButton);
        mSettingsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(mContext, SettingsActivity.class));
            }
        });


        // Set up output format button
        mOutputFormatButton = (Button) findViewById(R.id.Button_formatChoice);
        mOutputFormatButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, SelectByLabelActivity.class);
                intent.putExtra(SelectByLabelActivity.TAG_DATA_LABELS, mOutputFormatLabels.toArray(new String[mOutputFormatLabels.size()]));
                startActivityForResult(intent, OUTPUT_FORMAT);
            }
        });
        //mOutputFormat = mImageFormats.get(mOutputFormatInd);
        mOutputFormatButton.setText(CameraReport.cameraConstantStringer("android.graphics.ImageFormat", mOutputFormats.get(mOutputFormatInd)));


        // Set up output size button
        mOutputSizeButton = (Button) findViewById(R.id.Button_sizeChoice);
        mOutputSizeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, SelectByLabelActivity.class);
                // SelectByLabelActivity takes a string array, so convert these
                // Sizes accordingly
                String[] temp = new String[mOutputSizes.length];
                for (int i = 0; i < mOutputSizes.length; i++) {
                    temp[i] = mOutputSizes[i].toString();
                }
                intent.putExtra(SelectByLabelActivity.TAG_DATA_LABELS, temp);
                startActivityForResult(intent, OUTPUT_SIZE);
            }
        });
        mOutputSizeButton.setText(mOutputSizes[mOutputSizeInd].toString());


        // Set up processing button
        mProcessingButton = (Button) findViewById(R.id.Button_processingChoice);
        mProcessingButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, SelectByLabelActivity.class);
                String[] choices = new String[CaptureDesign.ProcessingChoice.values().length];
                for (int i = 0; i < CaptureDesign.ProcessingChoice.values().length; i++) {
                    choices[i] = CaptureDesign.ProcessingChoice.getChoiceByIndex(i).toString();
                }
                intent.putExtra(SelectByLabelActivity.TAG_DATA_LABELS, choices);
                startActivityForResult(intent, PROCESSING_SETTING);
            }
        });
        mProcessingButton.setText(mDesign.getProcessingSetting().toString());


        // Set up Load Design button
        mLoadDesignButton = (Button) findViewById(R.id.loadDesignButton);
        mLoadDesignButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Keep file names around for when selection index comes back
                mFileNames = DESIGN_DIR.list();

                if (mFileNames == null || mFileNames.length == 0) {
                    Toast.makeText(mContext, "No Design JSONs found.", Toast.LENGTH_SHORT).show();
                    return;
                }

                Intent intent = new Intent(mContext, SelectByLabelActivity.class);
                intent.putExtra(SelectByLabelActivity.TAG_DATA_LABELS, mFileNames);
                startActivityForResult(intent, LOAD_DESIGN);
            }
        });


        // set up Split Exposures button
        mSplitAmountButton = (Button) findViewById(R.id.splitAmountButton);
        mSplitAmountButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, GenerateDesignFromTemplateActivity.class);
                startActivityForResult(intent, GENERATE_DESIGN);
            }
        });


        // Set up the button that actually takes the pictures!
        mCaptureButton = (Button) findViewById(R.id.take_burst_button);
        mCaptureButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mDesign.getExposures().size() > 0) {
                    if (mDevCam.isReady()) {
                        // Turn off the buttons so the user doesn't accidentally mess up capture
                        setButtonsClickable(false);

                        // Now, tell the CaptureDesign to actually start the capture
                        // process and hand it the relevant pieces. Note the
                        // CaptureDesign class actually handles all of the commands to
                        // the camera.

                        mDesignResult = new DesignResult(mDesign.getExposures().size(),mOnCaptureAvailableListener);
                        Log.v(APP_TAG,"1111mDesignResult allocated.1111");
                        mWrittenFilenames = new ArrayList<String>();

                        // But first, check to see if we should use a delay timer or not.
                        long delay = (mUseDelay) ? 5000 : 0;
                        CountDownTimer countDownTimer = new CountDownTimer(delay, 1000) {
                            public void onTick(long secondsTillFinish) {
                                mCaptureButton.setText((secondsTillFinish / 1000) + " s");
                            }

                            public void onFinish() {

                                mNumImagesLeftToSave = mDesign.getExposures().size();


                                // Make a new CaptureDesign based on the current one, with a new name, so the current
                                // results don't get overwritten if button pushed again.
                                // Note: Do this first, so when the Variable parameters get fixed during capture, they don't
                                // get copied that way.
                                mNextDesign = new CaptureDesign(mDesign);

                                mDevCam.capture(mDesign);


                                // inform user sequence is being captured
                                mCapturingDesignTextView.setText("Capturing");
                                mCapturingDesignTextView.setVisibility(View.VISIBLE);

                                mCaptureButton.setVisibility(View.INVISIBLE);
                                mCaptureButton.setText(R.string.captureText);

                            }
                        };
                        countDownTimer.start();
                    } else {
                        Toast.makeText(mContext, "devCam not ready to capture yet.", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        });


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
        mMainHandler = new Handler(this.getMainLooper());

        // One for the ImageSaver actions, to not block the camera callbacks.
        if (null==mImageSaverThread){
            mImageSaverThread = new HandlerThread("devCam ImageSaver");
            mImageSaverThread.start();
            mImageSaverHandler = new Handler(mImageSaverThread.getLooper());
        }


        // Establish output surface (ImageReader) resources and register our callback with it.
        mImageReader = ImageReader.newInstance(
                mOutputSizes[mOutputSizeInd].getWidth(), mOutputSizes[mOutputSizeInd].getHeight(),
                mOutputFormats.get(mOutputFormatInd),
                imageBufferSizer());  // defer to auxiliary function to determine size of allocation
        mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, mImageSaverHandler);

        List<Surface> surfaces = new ArrayList<Surface>();
        surfaces.add(mImageReader.getSurface());

        mDevCam.registerOutputSurfaces(surfaces);



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
                    Log.v(APP_TAG,"Preview Surface Created.");
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
                        List<Surface> previewSurface = new ArrayList<Surface>();
                        previewSurface.add(holder.getSurface());
                        mDevCam.registerPreviewSurfaces(previewSurface);
                        mDevCam.startCam();
                    }

                }

                @Override
                public void surfaceDestroyed(SurfaceHolder holder){
                    Log.v(APP_TAG,"onSurfaceDestroyed() called.");
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
        Log.v(APP_TAG,"freeImageSaverResources() called.");

        mImageReader.close();

        mImageSaverThread.quitSafely();
        try {
            mImageSaverThread.join();
            mImageSaverThread = null;
            mImageSaverHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }






} // end whole class
