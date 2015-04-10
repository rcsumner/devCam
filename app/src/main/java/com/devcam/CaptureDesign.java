package com.devcam;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.TonemapCurve;
import android.media.Image;
import android.os.Handler;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

public class CaptureDesign {

    private String mDesignName;
    private List<Exposure> mExposures = new ArrayList<Exposure>(); // List of the Exposures of the Design

    // DesignResult object that gathers the output images and data from this CaptureDesign
    private DesignResult mDesignResult;

    // The main Activity makes a callback, registers it here so the CaptureDesign knows what to call
    DesignCaptureCallback mRegisteredCallback;


    // Label string arrays relating to the CaptureDesign possibilities for
    // these three capture options.
    final static String[] FOCUS_CHOICES = {"MANUAL", "AUTO ONCE, LOCK", "AUTO ALL"};
    final static String[] EXPOSURE_CHOICES = {"MANUAL", "AUTO ONCE, LOCK", "AUTO ALL"};
    static final Integer MANUAL = 0;
    static final Integer AUTO_FIRST = 1;
    static final Integer AUTO_ALL = 2;

    final static String[] PROCESSING_CHOICES = {"NONE", "FAST", "HIGH QUALITY"};
    static final Integer NONE = 0;
    static final Integer FAST = 1;
    static final Integer HIGH_QUALITY = 2;

    // Actual variables for the CaptureDesign's auto Intents
    private Integer mAFsetting = MANUAL;
    private Integer mAEsetting = MANUAL;
    private Integer mProcessingSetting = FAST;


    // These camera2-related things are not fundamental to the CaptureDesign concept, but are
    // necessary for the CaptureDesign to control its own capturing process.
    CameraCaptureSession mSession;
    Handler mBackgroundHandler;
    CaptureRequest.Builder mCaptureCRB;

    // Once capture has started, this keeps track of progress in the Exposure sequence
    private Iterator<Exposure> mExposureIt;

    private int mNumCaptured; // tracks the number of images actually captured, not just posted


    // State variable and possible static values for the auto-focus/exposure state machine
    Integer autoState;
    final Integer WAITING_FOR_AF = 0;
    final Integer WAITING_FOR_AE = 1;




    // - - - - - Constructors  - - - - -
    public CaptureDesign(){
        // Make a new design name, a random 4-digit number
        Random randGen = new Random();
        Integer randVal = randGen.nextInt();
        if (randVal<=0){
            randVal = randVal + Integer.MAX_VALUE;
        }
        mDesignName = randVal.toString().substring(0,4);
    }

    // Constructor based on a previous design, but with a new name.
    public CaptureDesign(CaptureDesign design){
        this();
        mExposures = design.getExposures();
        mAFsetting = design.getFocusSetting();
        mAEsetting = design.getExposureSetting();
        mProcessingSetting = design.getProcessingSetting();
    }


    /* CaptureRequest.Builder makeDesignCrb(CameraDevice)
     *
     * Creates the CaptureRequest.Builder for our capture process, assigning the relevant processing
     * modes. Leaves the control mode as AUTO for focus/exposure processes because we only want to
     * overwrite these at the right time.
     */
    private CaptureRequest.Builder makeDesignCrb (CameraDevice camera){
        try {
            CaptureRequest.Builder crb = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            crb.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

            // Make different settings based on Processing desired
            if (mProcessingSetting==NONE){
                crb.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF);
                //crb.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF); // Causes error?!
                crb.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE);
                float[] linear = {TonemapCurve.LEVEL_BLACK, TonemapCurve.LEVEL_BLACK,
                        TonemapCurve.LEVEL_WHITE, TonemapCurve.LEVEL_WHITE};
                crb.set(CaptureRequest.TONEMAP_CURVE, new TonemapCurve(linear,linear,linear));
                return crb;
            }

            if (mProcessingSetting==FAST){
                crb.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST);
                crb.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST);
                crb.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_FAST);
                return crb;
            }

            if (mProcessingSetting==HIGH_QUALITY){
                crb.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY);
                crb.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY);
                crb.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_HIGH_QUALITY);
                return crb;
            }
        } catch (CameraAccessException cae){
            cae.printStackTrace();
            return null;
        }
        // This will never be reached, but compiler requires it
        return null;
    }


    /* void updateCRB()
     *
     * Update the CaptureRequest.Builder being used so that it has the values of the next Exposure
     * in the sequence. This happens whenever we are ready to move onto a new desired frame of the
     * sequence. The iterator increments and sets up the capture request builder with the correct
     * parameters.
     */
    private void updateCRB(){

        if (mExposureIt.hasNext()){
            Log.v(appFragment.APP_TAG,"Updating CaptureRequest Builder to values for Exposure " + (mNumCaptured+1));
            Exposure next = mExposureIt.next();
            mCaptureCRB.set(CaptureRequest.SENSOR_EXPOSURE_TIME, next.getExposureTime());
            mCaptureCRB.set(CaptureRequest.SENSOR_SENSITIVITY, next.getSensitivity());
            mCaptureCRB.set(CaptureRequest.LENS_APERTURE, next.getAperture());
            mCaptureCRB.set(CaptureRequest.LENS_FOCAL_LENGTH, next.getFocalLength());
            mCaptureCRB.set(CaptureRequest.LENS_FOCUS_DISTANCE, next.getFocusDistance());
        }
    }



    /* void captureSequenceBurst()
     *
     * Method for taking the list of target Exposures, turning them into CaptureRequests, and then
     * capturing them as a burst. The easiest way to capture all of the frames in the sequence, and
     * is used when focus and exposure Intents are set to MANUAL, or once the desired auto intents
     * are only AUTO ONCE, LOCK and they have already converged.
     */
    private void captureSequenceBurst(){
        Log.v(appFragment.APP_TAG,"- - - - - Capturing Exposure Sequence as a Burst.");
        List<CaptureRequest> burstRequests= new ArrayList<CaptureRequest>();

        // IF AE/EF was used once before capturing the burst, ensure that they are locked and will
        // not start a new search on any frame in the burst.
        mCaptureCRB.set(CaptureRequest.CONTROL_AE_LOCK,true);
        mCaptureCRB.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE);
        mCaptureCRB.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);

        Iterator<Exposure> localExposureIt = mExposures.iterator();
        while (localExposureIt.hasNext()){
            Exposure next = localExposureIt.next();
            // don't change *_MODE settings, just values, to avoid state resets
            mCaptureCRB.set(CaptureRequest.SENSOR_EXPOSURE_TIME, next.getExposureTime());
            mCaptureCRB.set(CaptureRequest.SENSOR_SENSITIVITY, next.getSensitivity());
            mCaptureCRB.set(CaptureRequest.LENS_APERTURE, next.getAperture());
            mCaptureCRB.set(CaptureRequest.LENS_FOCAL_LENGTH, next.getFocalLength());
            mCaptureCRB.set(CaptureRequest.LENS_FOCUS_DISTANCE, next.getFocusDistance());

            burstRequests.add(mCaptureCRB.build());
        }

        try {
            mSession.captureBurst(burstRequests,frameCCB, mBackgroundHandler);
        } catch (CameraAccessException cae) {
            cae.printStackTrace();
        }
    }



    /* void fillAutoValues(CameraCharacteristics, CaptureResult)
     *
     * This method is called before trying to access the values of the Exposures, in order to make
     * sure that there are explicit values for all parameters in all exposures, not just variable
     * parameter values.
     */
    void fillAutoValues(CameraCharacteristics camChars,CaptureResult autoResult){
        for (Exposure exp : mExposures){
            if (exp.hasVariableValues()){
                exp.fixValues(camChars,autoResult);
            }
        }
    }




    /* void startCapture(CameraDevice, CameraCaptureSession, output Surface, preview Surface,
     * Handler, CaptureResult, CameraCharacteristics)
     *
     * Begins the capture process of the entire CaptureDesign.
     * The first four arguments are necessary for this class to control the camera device and
     * properly place the outputs. The latter two are necessary only for generating explicit
     * Exposure parameter values if the Exposures in the list have variable parameter values.
     *
     * The process determines its behavior based on the requested A3 processes:
     *
     * - If no AF or AE is requested, we can just do a straight burst capture right away.
     * - If AE and/or AF are requested only FIRST, then all we have to do is let the converge with a
     * series of requests that checks the output CaptureResults to see if they are converged. Then,
     * once converged, we can do a standard burst capture.
     * - If either the AE or AF requires convergence before every exposure, we enter a process where
     * a capture Request requesting these states is submitted and then its Result is checked for
     * convergence. If achieved, it captures a single Exposure from the list and then, upon success
     * of that capture, posts a new awaiting-convergence capture request.
     *
     * Rather than using a repeating capture request for these convergence tasks, we use individual
     * capture commands which recur "manually" if the state isn't converged yet. This gives us more
     * control over the entire process, so there aren't errant frames going through the camera
     * device and changing settings at all.
     */

    public void startCapture(CameraDevice camera,
                             CameraCaptureSession session,
                             Surface outputSurface,
                             Surface previewSurface,
                             Handler backgroundHandler,
                             CaptureResult autoResult,
                             CameraCharacteristics camChars){

        // Store the two pieces related to camera control which we will need later in callbacks
        mSession = session;
        mBackgroundHandler = backgroundHandler;

        // If there are no exposures in the list to capture, just exit.
        if (mExposures.size()==0){
            Log.v(appFragment.APP_TAG,"No Exposures in list to capture!");
            return;
        }

        // Now see if the exposure parameters should be derived from the AE/AF values or not,
        // and give them explicit values if so
        fillAutoValues(camChars,autoResult);

        // Initialize before capture
        mNumCaptured = 0;
        mExposureIt = mExposures.iterator();

        // Create a new (blank) DesignResult for this design
        mDesignResult = new DesignResult(mDesignName,mExposures.size(),this);

        try {
            Log.v(appFragment.APP_TAG,"- - - - - - - - - - - - - - - - - - - - - - - - - - - - -");
            Log.v(appFragment.APP_TAG,"Starting Design Capture. Stop repeating preview images.");
            session.stopRepeating(); // Stop the preview repeating requests from clogging the works

            // Generate a CaptureRequest.Builder with the appropriate settings
            mCaptureCRB = makeDesignCrb(camera);
            mCaptureCRB.addTarget(outputSurface);
            // We want the user to be able to see the images as they are captured, as well as the
            // auto-convergence process images to the feel like the system is still active
            mCaptureCRB.addTarget(previewSurface);

            // Initially set for manual control and then, depending on the settings, re-instate auto
            mCaptureCRB.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            mCaptureCRB.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);

            // If all of manually-set parameters are to be used, just capture the burst, stat,
            // using the current exposure and focus values.
            if (mAFsetting == MANUAL && mAEsetting == MANUAL){
                captureSequenceBurst();
                return;
            }

            // Otherwise, use the callback that continues posting single captures until it hits
            // desired auto-state convergence. First, give this request the values of the first
            // Exposure in the list so that they will be set for either auto process that is NOT
            // used.
            updateCRB();


            // Now set the auto controls as desired, and the states accordingly. Note that we use
            // a state machine that goes from determining focus to determining exposure, so we
            // only change the state to WAITING_FOR_AE if the we are not waiting for AF first
            if (mAFsetting !=MANUAL){
                autoState = WAITING_FOR_AF;
                mCaptureCRB.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                mCaptureCRB.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
            }

            if (mAEsetting !=MANUAL){
                if (mAFsetting ==0) autoState = WAITING_FOR_AE;
                mCaptureCRB.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                mCaptureCRB.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            }

            // Actually post the first request to start the auto-routines
            mSession.capture(mCaptureCRB.build(), mAutoCCB, mBackgroundHandler);

        } catch (CameraAccessException cae){
            cae.printStackTrace();
        }
    }



    /* CaptureCallback that handles frames that are part of the auto-Intent convergence state
     * machine cycle.
     */
    private CameraCaptureSession.CaptureCallback mAutoCCB = new CameraCaptureSession.CaptureCallback() {

        @Override
        public void onCaptureCompleted(CameraCaptureSession session,
                                       CaptureRequest request,TotalCaptureResult result){
            Log.v(appFragment.APP_TAG,"Auto State Check-in! - - - ");

            if (WAITING_FOR_AF==autoState){
                Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
                Log.v(appFragment.APP_TAG,"- - - AF_STATE: " +CameraReport.sContextMap.get("android.control.afState").get(afState));

                // If the AF state has converged, either to in-focus or not-in-focus, advance to
                // the next state, WAITING_FOR_AE, or if we don't care about AE, just finish.
                if (CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED==afState
                        || CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED==afState){
                    Log.v(appFragment.APP_TAG,"- - - AF Converged.");

                    if (mAEsetting != AUTO_ALL){
                        Log.v(appFragment.APP_TAG,"- - - Not requiring AE convergence. Posting next Capture.");
                        finishWithAuto();
                    } else {
                        autoState = WAITING_FOR_AE;
                    }


                    // Otherwise, we are in some PASSIVE state (unless we are in INACTIVE AF state,
                    // which will eventually self-trigger to a PASSIVE state), and we want to
                    // precipitate the converged-and-locked state, so send a TRIGGER.
                    // Don't trigger AE though, that will start its process over.
                } else if(CaptureResult.CONTROL_AF_STATE_INACTIVE!=afState) {
                    try {
                        Log.v(appFragment.APP_TAG,"- - - Triggering AF Passive state to lock.");
                        mCaptureCRB.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE);
                        mCaptureCRB.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
                        mSession.capture(mCaptureCRB.build(), this, mBackgroundHandler);
                    } catch (CameraAccessException cae){
                        cae.printStackTrace();
                    }
                }
            }

            if (WAITING_FOR_AE==autoState){
                Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                Log.v(appFragment.APP_TAG,"- - - AE_STATE: " +CameraReport.sContextMap.get("android.control.aeState").get(aeState));
                if (CaptureResult.CONTROL_AE_STATE_CONVERGED==aeState ||
                        CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED==aeState) {
                    Log.v(appFragment.APP_TAG,"- - - AE converged. Posting next Capture.");
                    finishWithAuto();
                } else {
                    try {
                        // Auto process is already in progress, so we don't need/want triggers to start them again
                        Log.v(appFragment.APP_TAG,"- - - Trying again for AE convergence.");
                        mCaptureCRB.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE);
                        mCaptureCRB.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
                        mSession.capture(mCaptureCRB.build(), this, mBackgroundHandler);
                    } catch (CameraAccessException cae){
                        cae.printStackTrace();
                    }
                }
            }
        }


        /* void finishWithAuto()
         *
         * This gets called by the callback when the desired auto-processes have converged. Now that
         * the state has been met, we can lock the values from it and capture either the entire
         * burst (if this is the first time called and we only wanted AUTO ONCE, LOCK intent for
         * both processes) or the next target frame in the Exposure sequence.
         */
        private void finishWithAuto(){
            Log.v(appFragment.APP_TAG,"- - - Finished with this frame's Auto sequence. Posting CaptureNextExposure().");

            // If either Auto process was being used for only the first frame, let us now just
            // post a burst capture since the state is converged.
            if (mAEsetting != AUTO_ALL && mAFsetting != AUTO_ALL){
                captureSequenceBurst();
                return;
            }

            // Otherwise, at least one auto process will be recurring for all frames, so only
            // capture the next frame with this converged state, and then let another
            // converging process begin in the onCaptureCompleted() callback
            try {
                // Exposure settings have already been set for mCaptureCRB.
                // Ensure that a new AE/AF search will not be started, and that AE is locked
                mCaptureCRB.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE);
                mCaptureCRB.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
                mCaptureCRB.set(CaptureRequest.CONTROL_AE_LOCK,true);
                mSession.capture(mCaptureCRB.build(),frameCCB,mBackgroundHandler);

                // This approach of starting a new capture instead of just using the current result
                // is suboptimal for time, but the user has already sacrificed that by using an
                // auto- process for every capture. This could be re-engineered later to be less
                // time sloppy, but would require re-jiggering the ImageReader.Callback to know when
                // to save an image sent to it.

            } catch (CameraAccessException cae){
                cae.printStackTrace();
            }

        }
    };




    /* CaptureCallback that handles frames that are actually part of the desired image sequence,
     * not part of the auto-routine-convergence cycling.
     */
    private CameraCaptureSession.CaptureCallback frameCCB = new CameraCaptureSession.CaptureCallback() {
        // Note this callback will be running on background thread.

        @Override
        public void onCaptureStarted(CameraCaptureSession session, CaptureRequest request, long timestamp, long frameNumber){
            // When a targeted capture starts, record the identifying timestamp in the DesignResult
            // so that later steps, such as the ImageReader, can identify which images are wanted
            // and which are from the auto-convergence process.
            mDesignResult.recordCaptureTimestamp(timestamp);
        }

        @Override
        public void onCaptureCompleted(CameraCaptureSession session,
                                       CaptureRequest request, TotalCaptureResult result){
            Log.v(appFragment.APP_TAG,"Frame capture completed, result saved to DesignResult.");

            // Store the result for later matching with an Image and writing out
            mDesignResult.recordCaptureResult(result);

            // If we have just captured the last image in the sequence, we can skip the following
            // code and return here. The camera device is done for the time being, and the whole
            // capturing process will signal its end from the DesignResult object when the last
            // Image/CaptureResult pair has been recorded and written out.
            mNumCaptured++;
            if (mNumCaptured==mExposures.size()){
                Log.v(appFragment.APP_TAG,"That was the last exposure to capture!");
                return;
            }

            // The following code is only run if there are more images to capture AND some auto
            // Intent is being used. In such a case, a new capture request must be posted to the
            // session to start the auto exposure/focus process before the next frame is to be
            // captured.

            // If we are using an auto-process for all exposures, post the next one to start converging
            if (mAEsetting == AUTO_ALL || mAFsetting == AUTO_ALL) {
                Log.v(appFragment.APP_TAG,"Still using Auto-something, so posting next convergence trail.");
                // Set the parameters of the next capture according to the desired settings, to be
                // disregarded as necessary, or actually used if not overwritten
                updateCRB();

                // Since we are concerned with AF convergence first, then AE, we set the state in
                // the reverse order so that it can step "backwards" only when needed.

                if (mAEsetting ==AUTO_ALL){
                    autoState = WAITING_FOR_AE;
                    // unlock AE and trigger it again
                    mCaptureCRB.set(CaptureRequest.CONTROL_AE_LOCK,false);
                    //mCaptureCRB.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START); // i think this is unnecessary
                }

                if (mAFsetting == AUTO_ALL){
                    autoState = WAITING_FOR_AF;
                    mCaptureCRB.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
                }

                try {
                    // Post the capture request to return to the auto-state-machine capture callback
                    mSession.capture(mCaptureCRB.build(), mAutoCCB, mBackgroundHandler);
                } catch (CameraAccessException cae){
                    cae.printStackTrace();
                }
            }
        }

        @Override
        public void onCaptureFailed(CameraCaptureSession session,
                                    CaptureRequest request, CaptureFailure failure){
            Log.v(appFragment.APP_TAG,"!!! Frame capture failure! Writing out the failed CaptureRequest !!!");
            // If the capture failed, write out a JSON file with the metadata about it.
            File file = new File(appFragment.APP_DIR,"Failed_CaptureRequest_"+".json");

            CameraReport.writeCaptureRequestToFile(request, file);

             /* not implemented yet */
//            // Also increase the number of filenames in the DesignResult so that its method of
//            // knowing when all the captures have been completed does not get thrown off.
//            mDesignResult.reportFailedCapture();

            //Also let the CaptureDesign know not to wait any more for this frame.
            mNumCaptured++;
        }
    };




    /* static CaptureDesign splitTime(CaptureResult, int n)
     *
     * Static utility function for generating a CaptureDesign by taking the values present in a
     * CaptureResult and repeating them in n many Exposures, except for exposure time, each of which
     * is 1/n the CaptureResult's.
     */
    static public CaptureDesign splitTime(CaptureResult autoResult, int nExp){
        CaptureDesign output = new CaptureDesign();
        // Assign the Exposure input 1/nExp its regular exposure time
        // then use that in the construct of nExp Exposures in the list.
        for (int i = 0; i < nExp; i++){
            Exposure temp = new Exposure(autoResult);
            temp.setExposureTime(temp.getExposureTime()/nExp);
            output.addExposure(temp);
        }
        return output;
    }




    /* We use a callback which the main Activity instantiates and then registers with the
     * CaptureDesign. This is used to let the main activity know when each new image is ready so
     * it can be saved, and also to know when the entire design sequence has been captured so it can
     * regain control of appropriate things, like the capture session to reinstate previews.
     */
    static abstract class DesignCaptureCallback {
        abstract void onFinished(DesignResult designResult);
        abstract void onImageReady(Image image, CaptureResult result, String filename);
    }

    void registerCallback(DesignCaptureCallback callback){
        mRegisteredCallback = callback;
    }





    /* static CaptureDesign loadDesignFromJson(File)
     *
     * Static method to read a JSON pointed to be the input File into a CaptureDesign. The
     * CaptureDesign will have the default Intent values, as well as a list of Exposures which is
     * derived from the array-of-objects describing Exposure parameters in the JSON.
     *
     * If an object in the JSON array does not contain one of the necessary fields, an exception is
     * thrown.
     *
     * If a variable parameter string does not follow the form "x*AUTO", a different exception is
     * thrown.
     *
     * Currently, no other check on the content is performed, such as making sure the exposureTime
     * can be read as a Long.
     *
     * I know the capricious use of these specific checked exceptions is probably infuriating, but
     * it works for now.
     */
    static CaptureDesign loadDesignFromJson(File file) throws NoSuchFieldException, IOException, NoSuchMethodException{
        CaptureDesign out = new CaptureDesign();
        try {
            FileInputStream fistream = new FileInputStream(file);

            // Read the JSON file
            JsonReader reader = new JsonReader(new InputStreamReader(fistream));
            try {
                reader.beginArray();

                // While there's another object in this array of JSON Exposures
                while (reader.hasNext()) {
                    Exposure tempExposure = new Exposure();
                    reader.beginObject();

                    // These flags make sure all necessary fields for an Exposure were obtained
                    boolean hadExposureTime = false;
                    boolean hadAperture = false;
                    boolean hadSensitivity = false;
                    boolean hadFocalLength = false;
                    boolean hadFocusDistance = false;

                    // While there's another field in this JSON Exposure
                    while(reader.hasNext()){
                        /* Check to see if the field is any of the five necessary, expected ones.
                         * For each field in the object that indicates one of the parameters, parse
                         * whether the parameter contained is a number, indicating a literal
                         * parameter value, or a string, indicating a variable parameter value.
                         * If it is a string, make sure it fits the required format, as a fairly
                         * loose check.
                         */

                        String nextName = reader.nextName().toLowerCase(); //Deal with capitalization here
                        if (nextName.equals("exposuretime")){
                            JsonToken value = reader.peek();
                            if (value==JsonToken.NUMBER){
                                tempExposure.setExposureTime(reader.nextLong());
                                hadExposureTime = true;
                            } else if (value==JsonToken.STRING){
                                String next = reader.nextString();
                                if (next.matches("[0-9.]*\\*[a-zA-Z]*")) {
                                    tempExposure.recordExposureTimeVar(next);
                                    hadExposureTime = true;
                                } else {
                                    throw new NoSuchMethodException();
                                }
                            }


                        } else if (nextName.equals("aperture")){
                            JsonToken value = reader.peek();
                            if (value==JsonToken.NUMBER){
                                tempExposure.setAperture(Double.valueOf(reader.nextDouble()).floatValue());
                                hadAperture = true;
                            } else if (value==JsonToken.STRING){
                                String next = reader.nextString();
                                if (next.matches("[0-9.]*\\*[a-zA-Z]*")) {
                                    tempExposure.recordApertureVar(next);
                                    hadAperture = true;
                                } else {
                                    throw new NoSuchMethodException();
                                }
                            }

                        } else if (nextName.equals("sensitivity")){
                            JsonToken value = reader.peek();
                            if (value==JsonToken.NUMBER){
                                tempExposure.setSensitivity(reader.nextInt());
                                hadSensitivity = true;
                            } else if (value==JsonToken.STRING){
                                String next = reader.nextString();
                                if (next.matches("[0-9.]*\\*[a-zA-Z]*")) {
                                    tempExposure.recordSensitivityVar(next);
                                    hadSensitivity = true;
                                } else {
                                    throw new NoSuchMethodException();
                                }
                            }

                        } else if (nextName.equals("focallength")){
                            JsonToken value = reader.peek();
                            if (value==JsonToken.NUMBER){
                                tempExposure.setFocalLength(Double.valueOf(reader.nextDouble()).floatValue());
                                hadFocalLength = true;
                            } else if (value==JsonToken.STRING){
                                String next = reader.nextString();
                                if (next.matches("[0-9.]*\\*[a-zA-Z]*")) {
                                    tempExposure.recordFocalLengthVar(next);
                                    hadFocalLength = true;
                                } else {
                                    throw new NoSuchMethodException();
                                }
                            }

                        } else if (nextName.equals("focusdistance")){
                            JsonToken value = reader.peek();
                            if (value==JsonToken.NUMBER){
                                Double temp = reader.nextDouble();
                                tempExposure.setFocusDistance(Double.valueOf(temp).floatValue());
                                hadFocusDistance = true;
                            } else if (value==JsonToken.STRING){
                                String next = reader.nextString();
                                if (next.matches("[0-9.]*\\*[a-zA-Z]*")) {
                                    tempExposure.recordFocusDistanceVar(next);
                                    hadFocusDistance = true;
                                } else {
                                    throw new NoSuchMethodException();
                                }
                            }

                        } else {
                            reader.skipValue();
                        }
                    }
                    reader.endObject();

                    // If this JSON had a malformed object without the necessary field:
                    if (!hadExposureTime
                            || !hadAperture
                            || !hadSensitivity
                            || !hadFocusDistance
                            || !hadFocalLength){
                        throw new NoSuchFieldException();
                    }

                    // Add the properly-read Exposure to the list.
                    out.addExposure(tempExposure);
                }

                reader.endArray();
                reader.close();
            } catch (IOException ioe) {
                Log.e(appFragment.APP_TAG,"IOException reading Design JSON file.");
                throw ioe;
            }
        } catch (FileNotFoundException fnfe){
            Log.e(appFragment.APP_TAG,"Design JSON file not found.");
        }
        return out;
    }



    /* void writeOut(File)
     *
     * Write out a text file of what this CaptureDesign consisted of. Can be important because the
     * camera might not always deliver exactly what you requested, so you can compare. Also,
     * sometimes you simply forget what it was you were trying to do...
     */
    void writeOut(File file){
        try {
            FileWriter writer = new FileWriter(file);

            writer.write("Design name: " + mDesignName + "\n");
            writer.write("Capture time: "
                    + DateFormat.getDateTimeInstance().format(new Date(System.currentTimeMillis())) + "\n");
            writer.write("Focus setting: " + FOCUS_CHOICES[mAFsetting] + "\n");
            writer.write("Exposure setting: " + EXPOSURE_CHOICES[mAEsetting] + "\n");
            writer.write("Processing setting: " + PROCESSING_CHOICES[mProcessingSetting] + "\n");
            for (int i=0; i<mExposures.size(); i++){
                writer.write(mExposures.get(i).toString() + "\n");
            }
            writer.close();
        } catch (IOException ioe){
            ioe.printStackTrace();
        }
    }



    // - - - - Setters and Getters - - - -

    public String getDesignName(){
        return mDesignName;
    }
    public void setDesignName(String name){
        mDesignName = name;
    }
    public DesignCaptureCallback getCallback(){
        return mRegisteredCallback;
    }
    public List<Exposure> getExposures(){
        return mExposures;
    }
    public DesignResult getDesignResult(){
        return mDesignResult;
    }
    public Integer getProcessingSetting(){
        return mProcessingSetting;
    }
    public void setProcessingSetting(Integer I){
        mProcessingSetting = I;
    }
    public void setExposureSetting(Integer I){
        mAEsetting = I;
    }
    public void setFocusSetting(Integer I){
        mAFsetting = I;
    }
    public Integer getExposureSetting(){
        return mAEsetting;
    }
    public Integer getFocusSetting(){
        return mAFsetting;
    }
    public void addExposure(Exposure exp){
        mExposures.add(exp);
    }


}