package com.devcam;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.KeyEvent;
import android.view.Surface;
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


public class MainActivity extends DevCamActivity {

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

    CameraDevice mCamera;
    CameraCaptureSession mCaptureSession;
    CameraCharacteristics mCamChars;
    StreamConfigurationMap mStreamMap;

    List<Integer> mOutputFormats;
    List<String> mOutputFormatLabels;

    Context mContext;

    private boolean buttonsSetup = false;

    // Array of names of JSON CaptureDesign files in appropriate directory
    private String[] mFileNames;

    private boolean mInadequateCameraFlag;

    boolean mUseDelay = false; // flag reflecting state of the delay switch

    // This simply holds the user options for displaying parameters. They are loaded in onResume().
    ExposureArrayAdapter.DisplayOptionBundle mDisplayOptions = new ExposureArrayAdapter.DisplayOptionBundle();

    // The CaptureDesign the app is working with at the moment
    private CaptureDesign mDesign = new CaptureDesign();

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
            Log.v(APP_TAG, "IMAGE READY! Saving to DesignResult.");
            Image image = reader.acquireNextImage();
            DesignResult designResult = mDesign.getDesignResult();
            designResult.recordImage(image);

        }
    };



    ImageSaver.WriteOutCallback mWriteOutCallback = new ImageSaver.WriteOutCallback() {

        @Override
        void onImageSaved(boolean success, String filename) {

            // Register the saved Image with the file system
            File IM_SAVE_DIR = new File(CAPTURE_DIR,mDesign.getDesignName());
            File imFile = new File(IM_SAVE_DIR,filename);
            addFileToMTP(imFile.getAbsolutePath());

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
        void onFailed(){
            // Inform user of failure
            mMainHandler.post(new Runnable(){
                public void run(){
                    Toast.makeText(mContext, "Failed to Capture Design.", Toast.LENGTH_SHORT).show();
                }
            });

            // Restore the preview image and the interface accessibility.
            restorePreview();
            setButtonsClickable(true);
        }


        @Override
        void onImageReady(Image image, CaptureResult result, String filename){

            File IM_SAVE_DIR = new File(CAPTURE_DIR,mDesign.getDesignName());
            IM_SAVE_DIR.mkdir();

            // Post the images to be saved on another thread
            mImageSaverHandler.post(new ImageSaver(image, result, mCamChars, IM_SAVE_DIR, filename, mWriteOutCallback));
        }

        // This is called when the images are done being captured, NOT when they are done being
        // written to disk.
        @Override
        void onFinished(final DesignResult designResult){

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

            // Make a new CaptureDesign based on the current one, with a new name, so the current
            // results don't get overwritten if button pushed again.
            // Do this first, so views update in the next step.
            mDesign = new CaptureDesign(mDesign);


            // Remove "capturing design" sign from sight.
            // Must be done in main thread, which created the View.
            mMainHandler.post(new Runnable(){
                public void run(){
                    mCapturingDesignTextView.setText("Saving Images.");

                    // Display to the user how much time passed between the
                    // first opening and the last closing of the shutter. Counts on the image
                    // timestamp generator being at least SOMEWHAT accurate.
                    CaptureResult lastResult = designResult.getCaptureResult(designResult.getDesignLength()-1);
                    CaptureResult firstResult = designResult.getCaptureResult(0);
                    long captureTime = (lastResult.get(CaptureResult.SENSOR_TIMESTAMP)
                            + lastResult.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                            - firstResult.get(CaptureResult.SENSOR_TIMESTAMP));
                    Toast.makeText(mContext, "Capture Sequence Completed in " + CameraReport.nsToString(captureTime), Toast.LENGTH_SHORT).show();
                    updateDesignViews();
                }
            });


            // Restore the preview image now that the CaptureDesign
            // capture sequence is over.
            restorePreview();

        }

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
        Log.v(APP_TAG,"updateDesignViews() called.");
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


    @Override
    protected List<Surface> addNonPreviewSurfaces(){
        Log.v(APP_TAG,"addNonPreviewSurfaces() called.");

        // Establish output surface (ImageReader) resources and register our callback with it.
        mImageReader = ImageReader.newInstance(
                mOutputSizes[mOutputSizeInd].getWidth(), mOutputSizes[mOutputSizeInd].getHeight(),
                mOutputFormats.get(mOutputFormatInd),
                imageBufferSizer());  // defer to auxiliary function to determine size of allocation
        mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, mBackgroundHandler);

        List<Surface> surfaces = new ArrayList<Surface>();
        surfaces.add(mImageReader.getSurface());
        return surfaces;
    }


    @Override
    protected void onAutoResultsReady(CaptureResult autoresult){
        mAutoResult = autoresult;
        updateAutoViews();
    }


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.v(APP_TAG, "MainActivity onCreate().");

        // Set the correct layout for this Activity
        setContentView(R.layout.camera_fragment);

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
    }

    @Override
    public void onResume(){
        super.onResume();
        Log.v(APP_TAG,"MainActivity onResume().");

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
        Log.v(APP_TAG,"onActivityResult() called.");

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

    @Override
    protected void onCameraReady(CameraDevice camera, CameraCharacteristics camChars, boolean inadequateCameraFlag){
        if (inadequateCameraFlag){
            mInadequateCameraFlag = true;
            mInadequateCameraTextView.setVisibility(View.VISIBLE);
            mMainLinearLayout.setAlpha(0.8f);
            setButtonsClickable(false);
        }

        mCamera = camera;
        mCamChars = camChars;

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
        // access/initialize them. But only do this on Activity creation, not every time the camera
        // gets re-setup (i.e. every time it is resumed).
        if (!buttonsSetup) {
            buttonsSetup = true;
            setupButtonsAndViews();
        }
    }

    @Override
    protected void onCaptureSessionReady(CameraCaptureSession session){
        mCaptureSession = session;
    }




    void setupButtonsAndViews(){

        Log.v(APP_TAG,"setupButtonsAndViews() called.");
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {

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
                            long delay = (mUseDelay) ? 5000 : 0;
                            CountDownTimer countDownTimer = new CountDownTimer(delay, 1000) {
                                public void onTick(long secondsTillFinish) {
                                    mCaptureButton.setText((secondsTillFinish / 1000) + " s");
                                }

                                public void onFinish() {

                                    mNumImagesLeftToSave = mDesign.getExposures().size();

                                    mDesign.startCapture(mCamera, mCaptureSession, mImageReader.getSurface(),
                                            mPreviewSurfaceView.getHolder().getSurface(), mBackgroundHandler, mCamChars);
                                    // inform user sequence is being captured
                                    mCapturingDesignTextView.setText("Capturing");
                                    mCapturingDesignTextView.setVisibility(View.VISIBLE);

                                    mCaptureButton.setVisibility(View.INVISIBLE);
                                    mCaptureButton.setText(R.string.captureText);
                                }
                            };
                            countDownTimer.start();
                        }
                    }
                });

            }
        });
    }

} // end whole class
