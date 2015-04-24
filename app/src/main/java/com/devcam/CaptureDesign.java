/* CaptureDesign class.
 * This contains all information about a how to capture a sequence of Exposures, including the
 * Exposures themselves, and contains the methods which actually control the camera device to
 * capture them.
 * The outputs are saved to a CaptureResult associated with the CaptureDesign.
 */

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
import android.util.Range;
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


    // This enumeration is for the possible Processing choices Unfortunately, since a number of
    // Android-specific things Intent.putExtra() only work with ints, there still needs to be a
    // value associated with each enum, which is used as an index into an array in some places.
    public enum ProcessingChoice {
        NONE(0),
        FAST(1),
        HIGH_QUALITY(2);

        private final int index;

        // Constructor for initialized value
        private ProcessingChoice(int ind) {
            this.index = ind;
        }

        public int getIndex() {
            return index;
        }

        // Static function for finding the choice enum by its int value
        public static ProcessingChoice getChoiceByIndex(int ind) {
            ProcessingChoice output = null;
            for (ProcessingChoice t : ProcessingChoice.values()) {
                if (t.getIndex() == ind) {
                    output = t;
                }
            }
            return output;
        }
    }

    // Actual variable for the CaptureDesign's processing Intent
    private ProcessingChoice mProcessingSetting = ProcessingChoice.FAST;


    // These camera2-related things are not fundamental to the CaptureDesign concept, but are
    // necessary for the CaptureDesign to control its own capturing process.
    CameraCaptureSession mSession;
    Handler mBackgroundHandler;
    CaptureRequest.Builder mCaptureCRB;
    CameraCharacteristics mCamChars;

    private int mNumCaptured; // tracks the number of images actually captured, not just posted



    // State variable and possible static values for the auto-focus/exposure state machine
    private enum AutoState {WAITING_FOR_AF, WAITING_FOR_AE}
    AutoState state;

    // Flags to indicate which auto-processes are needed.
    private boolean mNeedsAF;
    private boolean mNeedsAE;



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
            switch (mProcessingSetting) {
                case NONE :
                    crb.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF);
                    //crb.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF); // Causes error?!
                    crb.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE);
                    float[] linear = {TonemapCurve.LEVEL_BLACK, TonemapCurve.LEVEL_BLACK,
                            TonemapCurve.LEVEL_WHITE, TonemapCurve.LEVEL_WHITE};
                    crb.set(CaptureRequest.TONEMAP_CURVE, new TonemapCurve(linear,linear,linear));
                    break;
                case FAST :
                    crb.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST);
                    crb.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST);
                    crb.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_FAST);
                    break;
                case HIGH_QUALITY :
                    crb.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY);
                    crb.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY);
                    crb.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_HIGH_QUALITY);
                    break;
            }
        return crb;

        } catch (CameraAccessException cae){
            cae.printStackTrace();
            return null;
        }
    }



    /* void captureSequenceBurst()
     *
     * Method for taking the list of target Exposures, turning them into CaptureRequests, and then
     * capturing them as a burst. Once this is called, the camera is completely manually controlled
     * and the Exposures better have explicit values for all parameters.
     */
    private void captureSequenceBurst(){
        Log.v(appFragment.APP_TAG,"- - - - - Capturing Exposure Sequence as a Burst.");
        List<CaptureRequest> burstRequests= new ArrayList<CaptureRequest>();

        // Though some of them may have been originally derived from the scene, all parameter values
        // are now explicitly set. So make sure control modes are both OFF (leave AWB on)
        mCaptureCRB.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_OFF);
        mCaptureCRB.set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_OFF);

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
        Log.v(appFragment.APP_TAG,"Filling in Exposure values based on CaptureResult.");
        for (Exposure exp : mExposures){
            if (exp.hasVariableValues()){
                exp.fixValues(camChars,autoResult);
            }
        }
    }




    /* void startCapture(CameraDevice, CameraCaptureSession, output Surface, preview Surface,
     * Handler, CameraCharacteristics)
     *
     * Begins the capture process of the entire CaptureDesign.
     * The first four arguments are necessary for this class to control the camera device and
     * properly place the outputs. The last is necessary only for generating explicit Exposure
     * parameter values if the Exposures in the list have variable parameter values.
     *
     * If the values of all parameters are already explicitly set, a burst is simply captured.
     * If any of the Exposures have variables based around "Auto" then this takes a series of
     * pictures until the camera AF/AE state(s) converge(s). Then it uses this auto-process
     * CaptureResult to set explicit values in the Exposures, and then captures those as a burst.
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
                             CameraCharacteristics camChars){

        // Store the two pieces related to camera control which we will need later in callbacks
        mSession = session;
        mBackgroundHandler = backgroundHandler;
        mCamChars = camChars;

        // If there are no exposures in the list to capture, just exit.
        if (mExposures.size()==0){
            Log.v(appFragment.APP_TAG,"No Exposures in list to capture!");
            return;
        }

        // Initialize before capture
        mNumCaptured = 0;

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


            // Now loop over all Exposures in the sequence, and see if any of them require
            // 1) any Auto at all
            // 2) Auto focus
            // 3) Auto exposure
            // Keep track for later.
            int withoutVariables=0;
            mNeedsAF = false;
            mNeedsAE = false;
            for (Exposure e : mExposures){
                if (!e.hasVariableValues()){
                    withoutVariables++;
                }

                if (e.hasVariableFocusDistance()){
                    mNeedsAF = true;
                }

                if (e.hasVariableSensitivity()
                        || e.hasVariableExposureTime()
                        || e.hasVariableAperture()){
                    mNeedsAE = true;
                }
            }

            // If the Exposures don't require ANY Auto information, i.e. if all parameter values
            // were explicit, simply capture the burst.
            if (withoutVariables==mExposures.size()){
                Log.v(appFragment.APP_TAG,"No Auto needed, simply capturing burst.");
                captureSequenceBurst();
                return;
            }


            // Otherwise, we want the auto-process capture sequence to start.
            // Now set the auto controls as desired, and the states accordingly. Note that we use
            // a state machine that goes from determining focus to determining exposure, so we
            // only change the state to WAITING_FOR_AE if the we are not waiting for AF first
            if (mNeedsAF){
                state = AutoState.WAITING_FOR_AF;
                mCaptureCRB.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                mCaptureCRB.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
            }

            if (mNeedsAE){
                if (!mNeedsAF) state = AutoState.WAITING_FOR_AE;
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

            if (AutoState.WAITING_FOR_AF==state){
                Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
                Log.v(appFragment.APP_TAG,"- - - AF_STATE: " +CameraReport.sContextMap.get("android.control.afState").get(afState));

                // If the AF state has converged, either to in-focus or not-in-focus, advance to
                // the next state, WAITING_FOR_AE, or if we don't care about AE, just finish.
                if (CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED==afState
                        || CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED==afState){
                    Log.v(appFragment.APP_TAG,"- - - AF Converged.");

                    if (!mNeedsAE){
                        Log.v(appFragment.APP_TAG,"- - - Not requiring AE convergence.");
                        finishWithAuto(result);
                    } else {
                        state = AutoState.WAITING_FOR_AE;
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

            if (AutoState.WAITING_FOR_AE==state){
                Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                Log.v(appFragment.APP_TAG,"- - - AE_STATE: " +CameraReport.sContextMap.get("android.control.aeState").get(aeState));
                if (CaptureResult.CONTROL_AE_STATE_CONVERGED==aeState ||
                        CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED==aeState) {
                    Log.v(appFragment.APP_TAG,"- - - AE converged.");
                    finishWithAuto(result);
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


        /* void finishWithAuto(CaptureResult)
         *
         * This gets called by the callback when the desired auto-processes have converged. Now that
         * the state has been met, we can lock the values from it and capture the entire burst.
         */
        private void finishWithAuto(CaptureResult result){
            Log.v(appFragment.APP_TAG,"- - - Finished with the pre-capture Auto sequence.");

            // Now fill in the variable parameter values based on what we found, and capture.
            fillAutoValues(mCamChars,result);
            captureSequenceBurst();
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
            writer.write("Processing setting: " + mProcessingSetting + "\n");
            writer.write("\nExposure Time | ISO | Focus Distance | Aperture | Focal Length\n");
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
    public ProcessingChoice getProcessingSetting(){
        return mProcessingSetting;
    }
    public void setProcessingSetting(ProcessingChoice c){
        mProcessingSetting = c;
    }
    public void addExposure(Exposure exp){
        mExposures.add(exp);
    }





    /* CaptureDesign.Creator inner class. Used for generating patterned Capture Designs from a rule
     * and an existing CaptureResult and/or CameraCharacteristic.
     *
     * All members are static methods just useful for creating CaptureDesigns.
     */
    static abstract class Creator{

     /* static CaptureDesign loadDesignFromJson(File)
     *
     * Static method to read a JSON pointed to be the input File into a CaptureDesign. The
     * CaptureDesign will have the default Intent values, as well as a list of Exposures which is
     * derived from the array-of-objects describing Exposure parameters in the JSON.
     *
     * If an object in the JSON array does not contain one of the necessary fields, it is simply
     * set as "AUTO".
     *
     * If a variable parameter string does not follow the form "x*AUTO", a different exception is
     * thrown.
     *
     * Currently, no other check on the content is performed, such as making sure the exposureTime
     * can be read as a Long.
     *
     * I know the capricious use of these specific checked exceptions is probably upsetting, but
     * it works for now.
     */
        static CaptureDesign loadDesignFromJson(File file) throws IOException, NoSuchFieldException{
            CaptureDesign out = new CaptureDesign();
            try {
                FileInputStream fistream = new FileInputStream(file);

                // Read the JSON file
                JsonReader reader = new JsonReader(new InputStreamReader(fistream));
                try {
                    reader.beginArray();

                    // While there's another object in this array of JSON Exposures
                    while (reader.hasNext()) {
                        // Make a temporary Exposure which has all fields set to "AUTO". It is the
                        // duty of the JSON object's fields to overwrite these values now.
                        Exposure tempExposure = new Exposure(Exposure.ALL_AUTO);
                        reader.beginObject();

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
                                } else if (value==JsonToken.STRING){
                                    String next = reader.nextString();
                                    if (Exposure.ExposureParameterVariable.checkFeasibleInput(next)) {
                                        tempExposure.recordExposureTimeVar(next);
                                    } else {
                                        throw new NoSuchFieldException();
                                    }
                                }


                            } else if (nextName.equals("aperture")){
                                JsonToken value = reader.peek();
                                if (value==JsonToken.NUMBER){
                                    tempExposure.setAperture(Double.valueOf(reader.nextDouble()).floatValue());
                                } else if (value==JsonToken.STRING){
                                    String next = reader.nextString();
                                    if (Exposure.ExposureParameterVariable.checkFeasibleInput(next)) {
                                        tempExposure.recordApertureVar(next);
                                    } else {
                                        throw new NoSuchFieldException();
                                    }
                                }

                            } else if (nextName.equals("sensitivity") || nextName.equals("iso")){
                                JsonToken value = reader.peek();
                                if (value==JsonToken.NUMBER){
                                    tempExposure.setSensitivity(reader.nextInt());
                                } else if (value==JsonToken.STRING){
                                    String next = reader.nextString();
                                    if (Exposure.ExposureParameterVariable.checkFeasibleInput(next)) {
                                        tempExposure.recordSensitivityVar(next);
                                    } else {
                                        throw new NoSuchFieldException();
                                    }
                                }

                            } else if (nextName.equals("focallength")){
                                JsonToken value = reader.peek();
                                if (value==JsonToken.NUMBER){
                                    tempExposure.setFocalLength(Double.valueOf(reader.nextDouble()).floatValue());
                                } else if (value==JsonToken.STRING){
                                    String next = reader.nextString();
                                    if (Exposure.ExposureParameterVariable.checkFeasibleInput(next)) {
                                        tempExposure.recordFocalLengthVar(next);
                                    } else {
                                        throw new NoSuchFieldException();
                                    }
                                }

                            } else if (nextName.equals("focusdistance")){
                                JsonToken value = reader.peek();
                                if (value==JsonToken.NUMBER){
                                    Double temp = reader.nextDouble();
                                    tempExposure.setFocusDistance(Double.valueOf(temp).floatValue());
                                } else if (value==JsonToken.STRING){
                                    String next = reader.nextString();
                                    if (Exposure.ExposureParameterVariable.checkFeasibleInput(next)) {
                                        tempExposure.recordFocusDistanceVar(next);
                                    } else {
                                        throw new NoSuchFieldException();
                                    }
                                }

                            } else {
                                reader.skipValue();
                            }
                        }
                        reader.endObject();

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



        /* CaptureDesign exposureTimeBracketAroundAuto(Range<Float>, int)
         *
         * Creates a CaptureDesign that is bracketed around the Auto-Exposure result at capture time
         * by varying the exposure time.
         *
         * It brackets around the auto exposure time from the lower number of stops indicated in the
         * Range<Float> to the upper number. It splits this range into the integer number of
         * exposures indicated. Bracketing is linear in stops (exponential in actual exposure time).
         *
         * Note that the stop values do not need to be based around 0.
         * Also, the integer number is not odd, it will not include the actual Auto exposure time,
         * though it does bracket around it.
         */
        static CaptureDesign exposureTimeBracketAroundAuto(Range<Float> stopRange, int nExp){
            CaptureDesign output = new CaptureDesign();

            Float logStep = (stopRange.getUpper() - stopRange.getLower())/(nExp-1);

            for (int i=0; i<nExp; i++){
                    Exposure temp = new Exposure(Exposure.ALL_AUTO);
                    temp.recordExposureTimeVar(Math.pow(2,stopRange.getLower()+i*logStep)+"*AUTO");
                    output.addExposure(temp);
                }
            return output;
        }


        /* CaptureDesign isoBracketAroundAuto(Range<Float>, int)
         *
         * Creates a CaptureDesign that is bracketed around the Auto-Exposure result at capture time
         * by varying the ISO.
         *
         * It brackets around the auto ISO from the lower number of stops indicated in the
         * Range<Float> to the upper number. It splits this range into the integer number of
         * exposures indicated. Bracketing is linear in stops (exponential in ISO).
         *
         * Note that the stop values do not need to be based around 0.
         * Also, the integer number is not odd, it will not include the actual Auto ISO,
         * though it does bracket around it.
         */
        static CaptureDesign isoBracketAroundAuto(Range<Float> stopRange, int nExp){
            CaptureDesign output = new CaptureDesign();

            Float logStep = (stopRange.getUpper() - stopRange.getLower())/(nExp-1);

            for (int i=0; i<nExp; i++){
                Exposure temp = new Exposure(Exposure.ALL_AUTO);
                temp.recordSensitivityVar(Math.pow(2,stopRange.getLower()+i*logStep)+"*AUTO");
                output.addExposure(temp);
            }
            return output;
        }


      /* CaptureDesign exposureTimeBracketAbsolute(CameraCharacteristics, Range<Long>, int)
      *
      * Creates a CaptureDesign that is bracketed between two absolute exposure time values,
      * with the number of steps indicated by the int input.
      *
      * Bracketing is linear in exposure time.
      *
      */
        static CaptureDesign exposureTimeBracketAbsolute(CameraCharacteristics camChars,
                                                           Range<Long> timeRange,
                                                           int nExp){
            CaptureDesign output = new CaptureDesign();

            // The linear interpolation step size for each Exposure
            long tStep = (timeRange.getUpper()-timeRange.getLower())/(nExp-1);

            for (int i=0; i<nExp; i++){
                long expTime = timeRange.getLower() + tStep*i;
                // Only add this bracketed exposure if it is within the bounds of the camera's means.
                if (camChars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE).contains(expTime)) {
                    // Create an Exposure from the auto Result and only give it the new exposure time.
                    Exposure temp = new Exposure("ALL_AUTO");
                    temp.setExposureTime(expTime);
                    output.addExposure(temp);
                }
            }
            return output;
        }


     /* CaptureDesign isoBracketAbsolute(CameraCharacteristics, Range<Integer>, int)
      *
      * Creates a CaptureDesign that is bracketed between two absolute ISO values,
      * with the number of steps indicated by the int input.
      *
      * Bracketing is linear in ISO.
      *
      */
        static CaptureDesign isoBracketAbsolute(CameraCharacteristics camChars,
                                                         Range<Integer> isoRange,
                                                         int nExp){
            CaptureDesign output = new CaptureDesign();

            // The linear interpolation step size for each Exposure
            Integer tStep = (isoRange.getUpper()-isoRange.getLower())/(nExp-1);

            for (int i=0; i<nExp; i++){
                Integer iso = isoRange.getLower() + tStep*i;
                // Only add this bracketed exposure if it is within the bounds of the camera's means.
                if (camChars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE).contains(iso)) {
                    // Create an Exposure from the auto Result and only give it the new iso
                    Exposure temp = new Exposure("ALL_AUTO");
                    temp.setSensitivity(iso);
                    output.addExposure(temp);
                }
            }
            return output;
        }



      /* CaptureDesign focusBracketAbsolute(CameraCharacteristics, Range<Float>, int)
      *
      * Creates a CaptureDesign which racks between two focus distances. Note that this will use
      * the values given, even if the device is not actually calibrated to have those values be
      * meaningful in meters.
      *
      * Bracketing is linear in meters, as is the input Range.
      *
      */
        static CaptureDesign focusBracketAbsolute(CameraCharacteristics camChars,
                                                Range<Float> focusRange,
                                                int nExp){
            CaptureDesign output = new CaptureDesign();

            // The linear interpolation step size for each Exposure
            Float focusStep = (focusRange.getUpper()-focusRange.getLower())/(nExp-1);

            for (int i=0; i<nExp; i++){
                float focuspt = focusRange.getLower() + focusStep*i;
                float focusDiopters = 1/focuspt;
                // Only add this exposure if it is within the bounds of the camera's means.
                if (focusDiopters <= camChars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)) {
                    // Create an Exposure from the auto Result and only give it the new focus pt
                    Exposure temp = new Exposure("ALL_AUTO");
                    temp.setFocusDistance(focusDiopters);
                    output.addExposure(temp);
                }
            }
            return output;
        }



     /* static CaptureDesign splitExposureTime(int n)
      *
      * Static utility function for generating a CaptureDesign by taking the values present in a
      * CaptureResult and repeating them in n many Exposures, except for exposure time, each of which
      * is 1/n the CaptureResult's. If the time of each is smaller than the device's capable time,
      * each exposure will just be for the minimum amount.
      */
        static CaptureDesign splitExposureTime(int nExp){
            CaptureDesign output = new CaptureDesign();

            for (int i=0; i<nExp; i++){
                Exposure temp = new Exposure(Exposure.ALL_AUTO);
                temp.recordExposureTimeVar((1.0/nExp)+"*AUTO");
                output.addExposure(temp);
            }
            return output;
        }


     /* static CaptureDesign burst(int n)
      *
      * Create a burst sequence of auto-exposed/focused images of length n.
      */
        static CaptureDesign burst(int nExp){
            CaptureDesign output = new CaptureDesign();
            for (int i=0; i<nExp; i++){
                output.addExposure(new Exposure(Exposure.ALL_AUTO));
            }
            return output;
        }



    } // end Creator inner class

} // end whole CaptureDesign class