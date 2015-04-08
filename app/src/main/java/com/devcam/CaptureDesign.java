package com.devcam;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.TonemapCurve;
import android.media.Image;
import android.os.Handler;
import android.util.JsonReader;
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
    private List<Exposure> mExposures = new ArrayList<Exposure>();
    private DesignResult mDesignResult;

    private Iterator<Exposure> mExposureIt;

    CameraCaptureSession mSession;
    Handler mBackgroundHandler;
    CaptureRequest.Builder mCaptureCRB;

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




    private int mNumCaptured;

    // Flags for the entire design's auto scheme
    private Integer mAFsetting = MANUAL;
    private Integer mAEsetting = MANUAL;
    private Integer mProcessingSetting = FAST;


    // State variable for auto "background" repeating request.
    Integer autoState;
    final Integer WAITING_FOR_AF = 0;
    final Integer WAITING_FOR_AE = 1;


    // - - - - - Constructors  - - - - -
    public CaptureDesign(){
        // If the Design Name has not been set, make one randomly, a 4-digit number
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


    private void captureSequenceBurst(){
        Log.v(appFragment.APP_TAG,"- - - - - Capturing Exposure Sequence as a Burst.");
        List<CaptureRequest> burstRequests= new ArrayList<CaptureRequest>();

        // Ensure that a new AE/AF search will not be started, and that AE is locked
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


    void fillAutoValues(CaptureResult autoResult){
        for (Exposure exp : mExposures){
            if (exp.getAperture()<0){
                exp.setAperture(autoResult.get(CaptureResult.LENS_APERTURE));
            }
            if (exp.getExposureTime()<0){
                exp.setExposureTime(autoResult.get(CaptureResult.SENSOR_EXPOSURE_TIME));
            }
            if (exp.getFocalLength()<0){
                exp.setFocalLength(autoResult.get(CaptureResult.LENS_FOCAL_LENGTH));
            }
            if (exp.getFocusDistance()<0){
                exp.setFocusDistance(autoResult.get(CaptureResult.LENS_FOCUS_DISTANCE));
            }
            if (exp.getSensitivity()<0){
                exp.setSensitivity(autoResult.get(CaptureResult.SENSOR_SENSITIVITY));
            }
        }
    }



    /* void startCapture(CameraDevice,CameraCaptureSession,output Surface,preview Surface,Handler)
     *
     * Begins the capture process by passing the necessary objects for this class to communicate
     * with the camera appropriately.
     *
     * The process determines its behavior based on the requested A3 processes.:
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
     * capture commands which recur "manually" if the state isn't converged yet. This is so
     */

    public void startCapture(CameraDevice camera,
                             CameraCaptureSession session,
                             Surface outputSurface,
                             Surface previewSurface,
                             Handler backgroundHandler,
                             CaptureResult autoResult){

        mSession = session;
        mBackgroundHandler = backgroundHandler;

        // If there are no exposures in the list to capture, just exit.
        if (mExposures.size()==0){
            Log.v(appFragment.APP_TAG,"No Exposures in list to capture!");
            return;
        }

        // Now see if the exposure parameters should be derived from the AE/AF values or not
        fillAutoValues(autoResult);

        mNumCaptured = 0;
        mExposureIt = mExposures.iterator();

        // Create a new (blank) DesignResult for this design
        mDesignResult = new DesignResult(mDesignName,mExposures.size(),this);

        if (mExposureIt.hasNext()){
            try {
                Log.v(appFragment.APP_TAG,"- - - - - - - - - - - - - - - - - - - - - - - - - - - - -");
                Log.v(appFragment.APP_TAG,"Starting Design Capture. Stop repeating preview images.");
                session.stopRepeating(); // Stop the preview repeating requests from clogging the works

                mCaptureCRB = makeDesignCrb(camera);
                mCaptureCRB.addTarget(outputSurface);
                mCaptureCRB.addTarget(previewSurface);

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

                mSession.capture(mCaptureCRB.build(), mCaptureCCB, mBackgroundHandler);

            } catch (CameraAccessException cae){
                cae.printStackTrace();
            }
        }

    }



    private CameraCaptureSession.CaptureCallback mCaptureCCB = new CameraCaptureSession.CaptureCallback() {

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


        private void finishWithAuto(){
            Log.v(appFragment.APP_TAG,"- - - Finished with this frame's Auto sequence. Posting CaptureNextExposure().");

            // If either Auto process was being used for only the first frame, let us now just
            // post a burst capture since the state is converged
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








    private CameraCaptureSession.CaptureCallback frameCCB = new CameraCaptureSession.CaptureCallback() {
        // Note this callback will be running on background thread.

        @Override
        public void onCaptureStarted(CameraCaptureSession session, CaptureRequest request, long timestamp, long frameNumber){
            // For keeping track of images actually part of capture, so imagesaver can distinguish from auto images
            mDesignResult.recordCaptureTimestamp(timestamp);
        }

        @Override
        public void onCaptureCompleted(CameraCaptureSession session,
                                       CaptureRequest request, TotalCaptureResult result){
            // Store the result for later
            Log.v(appFragment.APP_TAG,"Frame capture completed, result saved to DesignResult.");
            mDesignResult.recordCaptureResult(result);
            mNumCaptured++;

            if (mNumCaptured==mExposures.size()){
                Log.v(appFragment.APP_TAG,"That was the last exposure to capture!");
                return;
            }

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
                    mSession.capture(mCaptureCRB.build(), mCaptureCCB, mBackgroundHandler);
                } catch (CameraAccessException cae){
                    cae.printStackTrace();
                }
            }
        }

        @Override
        public void onCaptureFailed(CameraCaptureSession session,
                                    CaptureRequest request, CaptureFailure failure){
            Log.v(appFragment.APP_TAG,"!!! Frame capture failure! Writing out the failed CaptureRequest !!!");
            File file = new File(appFragment.APP_DIR,"Failed_CaptureRequest_"+".json");
            CameraReport.writeCaptureRequestToFile(request, file);
            mNumCaptured++;
        }
    };





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

    public void clearExposures(){
        mExposures = null;
    }

    public void addExposures(List<Exposure> list){
        mExposures.addAll(list);
    }

    public void addExposure(Exposure exp){
        mExposures.add(exp);
    }

    // Utility function for time-split exposure set
    static public CaptureDesign splitTime(Exposure autoExposure, int nExp){
        CaptureDesign output = new CaptureDesign();
        // Assign the Exposure input 1/nExp its regular exposure time
        // then use that in the construct of nExp Exposures in the list.
        autoExposure.setExposureTime(autoExposure.getExposureTime()/nExp);
        for (int i = 0; i < nExp; i++){
            output.addExposure(new Exposure(autoExposure));
        }
        return output;
    }

    /* We use a callback which the main Activity instantiates and then registers with the
     * CaptureDesign. This is used to let the main activity know when each new image is ready so
     * it can be saved, and also to know when the entire design sequence has been captured so it can
     * regain control of appropriate things, like the capture session to reinstate previews.
     */

    void registerCallback(DesignCaptureCallback callback){
        mRegisteredCallback = callback;
    }


    static abstract class DesignCaptureCallback {
        abstract void onFinished(DesignResult designResult);
        abstract void onImageReady(Image image, CaptureResult result, String filename);
    }


    static CaptureDesign loadDesignFromJson(File file){
        CaptureDesign out = new CaptureDesign();
        try {
            FileInputStream fistream = new FileInputStream(file);

            // Read the JSON file
            JsonReader reader = new JsonReader(new InputStreamReader(fistream));
            try {
                reader.beginArray();

                while (reader.hasNext()) {
                    Exposure tempExposure = new Exposure();
                    reader.beginObject();
                    while(reader.hasNext()){
                        String nextName = reader.nextName().toLowerCase(); //Deal with capitalization here
                        if (nextName.equals("exposuretime")){
                            tempExposure.setExposureTime(reader.nextLong());
                        } else if (nextName.equals("aperture")){
                            tempExposure.setAperture(Double.valueOf(reader.nextDouble()).floatValue());
                        } else if (nextName.equals("sensitivity")){
                            tempExposure.setSensitivity(reader.nextInt());
                        } else if (nextName.equals("focallength")){
                            tempExposure.setFocalLength(Double.valueOf(reader.nextDouble()).floatValue());
                        } else if (nextName.equals("focaldistance")){
                            tempExposure.setFocusDistance(Double.valueOf(reader.nextDouble()).floatValue());
                        } else {
                            reader.skipValue();
                        }
                    }
                    reader.endObject();
                    out.addExposure(tempExposure);
                }

                reader.endArray();
                reader.close();
            } catch (IOException ioe) {
                Log.e(appFragment.APP_TAG,"IOException reading Design JSON file.");
            }

        } catch (FileNotFoundException fnfe){
            Log.e(appFragment.APP_TAG,"Design JSON file not found.");
        }

        return out;
    }


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

} // End whole class



// - - - - - - The old way of doing things. May be useful to return to some day, or reuse - - - - -



//    private CameraCaptureSession.CaptureCallback frameCCB = new CameraCaptureSession.CaptureCallback() {
//        // Note this callback will be running on background thread.
//
//        @Override
//        public void onCaptureStarted(CameraCaptureSession session, CaptureRequest request, long timestamp, long frameNumber){
//            mDesignResult.recordCaptureTimestamp(timestamp); // For keeping track of images actually part of capture, so imagesaver can distinguish from auto images
//        }
//
//        @Override
//        public void onCaptureCompleted(CameraCaptureSession session,
//                                       CaptureRequest request, TotalCaptureResult result){
//            // Store the result for later
//            Log.v(appFragment.APP_TAG,"Frame capture completed, result saved to DesignResult.");
//            mDesignResult.recordCaptureResult(result);
//            mNumCaptured++;
//        }
//
//        @Override
//        public void onCaptureFailed(CameraCaptureSession session,
//                                    CaptureRequest request, CaptureFailure failure){
//            Log.v(appFragment.APP_TAG,"!!! Frame capture failure! Writing out the failed CaptureRequest !!!");
//            File file = new File(appFragment.APP_DIR,"Failed_CaptureRequest_"+".json");
//            CameraReport.writeCaptureRequestToFile(request, file);
//            mNumCaptured++;
//        }
//    };


// private class CaptureNextExposure implements Runnable {
//
//		@Override
//		public void run(){
//			Log.v(appFragment.APP_TAG,"- - - - - Running CaptureNextExposure.");
//			if (mExposureIt.hasNext()){
//
//				Exposure next = mExposureIt.next();
//				mCaptureCRB.set(CaptureRequest.SENSOR_EXPOSURE_TIME, next.getExposureTime());
//				mCaptureCRB.set(CaptureRequest.SENSOR_SENSITIVITY, next.getSensitivity());
//				mCaptureCRB.set(CaptureRequest.LENS_APERTURE, next.getAperture());
//				mCaptureCRB.set(CaptureRequest.LENS_FOCAL_LENGTH, next.getFocalLength());
//				mCaptureCRB.set(CaptureRequest.LENS_FOCUS_DISTANCE, next.getFocusDistance());
//
//				try {
//					mSession.capture(mCaptureCRB.build(),frameCCB, mBackgroundHandler);
//					Log.d(appFragment.APP_TAG,"- - - - - Frame Capture Request sent to camera.");
//				} catch (CameraAccessException cae) {
//					cae.printStackTrace();
//				}
//			}
//
//			if (mExposureIt.hasNext()) {
//				if (AUTO_ALL==mAFsetting || mAEsetting==ALL_AUTO_AE){
//					Log.v(appFragment.APP_TAG,"- - - - - Using auto something for next frame, so simply going to wait for Auto Check-in.");
//				} else {
//					Log.v(appFragment.APP_TAG,"- - - - - Auto not being used for next frame. Directly Posting CaptureNextExposure.");
//					mBackgroundHandler.post(new CaptureNextExposure());
//				}
//			} else {
//				Log.v(appFragment.APP_TAG,"- - - - - No more Exposures in list, all done! Put preview back up.");
//				Log.v(appFragment.APP_TAG,"- - - - - - - - - - - - - - - - - - - - - - - - - - - - -");
//				mCaptureFinishedFlag = true;
//			}
//		}
//	}

//	private CameraCaptureSession.CaptureCallback autoCCB = new CameraCaptureSession.CaptureCallback() {
//
//		@Override
//		public void onCaptureCompleted(CameraCaptureSession session,
//				CaptureRequest request,TotalCaptureResult result){
//			Log.v(appFragment.APP_TAG,"Auto State Check-in! - - - ");
//
//			if (mCaptureFinishedFlag){
//				Log.v(appFragment.APP_TAG,"- - - Capture sequence is finished. Leftover Auto State Check-In.");
//				return;
//			}
//
//			if (WAITING_FOR_AF==autoState){
//				Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
//				Log.v(appFragment.APP_TAG,"- - - AF_STATE: " +CameraReport.sContextMap.get("android.control.afState").get(afState));
//				if (CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED==afState
//						|| CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED==afState){
//					Log.v(appFragment.APP_TAG,"- - - AF Locked.");
//					autoState = WAITING_FOR_AE;
//
//					if (mAEsetting>0){
//						Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
//						Log.v(appFragment.APP_TAG,"- - - AE_STATE: " +CameraReport.sContextMap.get("android.control.aeState").get(aeState));
//						if (CaptureResult.CONTROL_AE_STATE_CONVERGED==aeState ||
//								CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED==aeState) {
//							Log.v(appFragment.APP_TAG,"- - - AE also converged.");
//							finishWithAuto();
//						} else {
//							Log.v(appFragment.APP_TAG,"- - - Waiting for AE convergence.");
//						}
//					} else {
//						Log.v(appFragment.APP_TAG,"- - - Not requiring AE convergence.");
//						finishWithAuto();
//					}
//				}
//			}
//
//			if (WAITING_FOR_AE==autoState && mAEsetting>0){
//				Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
//				Log.v(appFragment.APP_TAG,"- - - AE_STATE: " +CameraReport.sContextMap.get("android.control.aeState").get(aeState));
//				if (CaptureResult.CONTROL_AE_STATE_CONVERGED==aeState ||
//						CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED==aeState) {
//					Log.v(appFragment.APP_TAG,"- - - AE converged. Posting CaptureNextExposure().");
//					finishWithAuto();
//				} else {
//					Log.v(appFragment.APP_TAG,"- - - Waiting for AE convergence.");
//				}
//			}
//		}
//
//
//        private void finishWithAuto(){
//            Log.v(appFragment.APP_TAG,"- - - Finished with this frame's Auto sequence. Posting CaptureNextExposure().");
//            mBackgroundHandler.post(new CaptureNextExposure());
//            autoState = FINISHED; // in case any other auto requests are still in pipeline
//
//            if (mAEsetting==ALL_AUTO_AE){
//                Log.v(appFragment.APP_TAG,"- - - Still using AE.");
//                autoState = WAITING_FOR_AE;
//            }
//
//            if (mAFsetting==AUTO_ALL){
//                Log.v(appFragment.APP_TAG,"- - - Still using AF.");
//                autoState = WAITING_FOR_AF;
//            }
//
//            if (mAFsetting!=AUTO_ALL && mAEsetting!=ALL_AUTO_AE){
//                Log.v(appFragment.APP_TAG,"- - - No more Auto. Turning off repeating requests.");
//                try {
//                    mSession.stopRepeating();
//                } catch (CameraAccessException cae){
//                    cae.printStackTrace();
//                }
//            }
//        }
//	};



//    public void startCapture(CameraDevice camera,
//			CameraCaptureSession session,
//			Surface outputSurface,
//			Surface previewSurface,
//			Handler backgroundHandler){
//
//		mSession = session;
//		mBackgroundHandler = backgroundHandler;
//
//		mCaptureFinishedFlag = false;
//		mNumCaptured = 0;
//
//		mExposureIt = mExposures.iterator();
//
//		// Create a new (blank) DesignResult for this design
//		mDesignResult = new DesignResult(mDesignName,mExposures.size(),this);
//
//		if (mExposureIt.hasNext()){
//			try {
//				Log.v(appFragment.APP_TAG,"- - - - - - - - - - - - - - - - - - - - - - - - - - - - -");
//				Log.v(appFragment.APP_TAG,"Starting Design Capture. Stop repeating preview images.");
//				session.stopRepeating(); // Stop the preview repeating requests
//				CaptureRequest.Builder autoCRB = makeDesignCrb(camera);
//				autoCRB.addTarget(outputSurface);
//
//				mCaptureCRB = makeDesignCrb(camera);
//				mCaptureCRB.addTarget(outputSurface);
//
//				autoCRB.addTarget(previewSurface); // sloppy controversial
//				//mCaptureCRB.addTarget(previewSurface); // sloppy controversial
//
//				mCaptureCRB.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
//				mCaptureCRB.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
//                autoCRB.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
//                autoCRB.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
//
//				if (mAFsetting>0 || mAEsetting>0) {
//					Log.v(appFragment.APP_TAG,"Some Auto is to be used. Establishing repeat capture request.");
//
//					if (mAFsetting==0){
//                        // WAITING_FOR_AF comes first IF it is used. But if it's not, set WAITING_FOR_AE
//						autoState = WAITING_FOR_AE;
//					} else {
//                        // We are going to use AF at least for the first image
//						autoState = WAITING_FOR_AF;
//						autoCRB.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
//						autoCRB.set(CaptureRequest.CONTROL_AF_TRIGGER,CaptureRequest.CONTROL_AF_TRIGGER_START);
//                        // set the capture CRB to the same mode as the auto CRB, because changing it
//                        // will reset the lens state to idle and ruin the locked focus
//                        mCaptureCRB.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
//					}
//
//					if (mAEsetting>0) {
//                        //We are going to use AE for at least the first image
//                        autoCRB.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
//						autoCRB.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
//                        // set the capture CRB to the same mode as the auto CRB, because changing it
//                        // will reset the AE state to idle and ruin the locked AE
//                        mCaptureCRB.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
//					}
//
//                    // Initiate repeating Auto captures and watch for the right states
//					mSession.setRepeatingRequest(autoCRB.build(), autoCCB, mBackgroundHandler);
//				} else {
//					Log.v(appFragment.APP_TAG,"No Auto anything. Posting first CaptureNextExposure.");
//					mBackgroundHandler.post(new CaptureNextExposure());
//				}
//
//			} catch (CameraAccessException cae){
//				cae.printStackTrace();
//			}
//		}
//
//	}