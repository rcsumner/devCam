package com.devcam;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureResult;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.Log;
import android.util.Range;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Random;


/**
 * Class representing the a sequence of Exposures to capture.
 *
 * <p>Each instance contains the list of Exposures and the processing setting to use.</p>
 */
public class CaptureDesign {

    private String mDesignName;
    private List<Exposure> mExposures = new ArrayList<Exposure>(); // List of the Exposures of the Design


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






    // - - - - - Constructors  - - - - -
    public CaptureDesign(){
        // Make a new design name, a random 4-digit number
        Random randGen = new Random();
        Integer randVal = randGen.nextInt();
        if (randVal<=0){
            randVal = randVal + Integer.MAX_VALUE;
        }
        mDesignName = randVal.toString().substring(0, 4);
    }

    // Constructor based on a previous design, but with a new name.
    public CaptureDesign(CaptureDesign design) {
        this();

        // Make sure the Exposures are "deep copied" so that if the Exposure was originally
        // variable-based and subsequently gets fixed to an explicit value, the new copied Exposure
        // copies the variable, not the fixed value.
        for (Exposure e : design.getExposures()){
            mExposures.add(new Exposure(e));
        }
        mProcessingSetting = design.getProcessingSetting();
    }



    /**
     * Replace all variable parameter values in all Exposures in this object with explicit ones.
     *
     * @param camChars Characteristics object for this camera, used for device bounds.
     * @param autoResult CaptureResult from a recent camera frame which used the AE/AF routines.
     */
    public void fillAutoValues(CameraCharacteristics camChars,CaptureResult autoResult){
        Log.v(DevCamActivity.APP_TAG, "Filling in Exposure values based on CaptureResult.");
        for (Exposure exp : mExposures){
            if (exp.hasVariableValues()){
                exp.fixValues(camChars,autoResult);
            }
        }
    }


    /**
     * Writes a text file of the information contained in this CaptureDesign.
     *
     * <p>Can be useful because the camera might not always deliver exactly what you requested, so
     * you can compare this original list of desired values with the actual ones produced.</p>
     *
     * @param file Output File to write to.
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
    public List<Exposure> getExposures(){
        return mExposures;
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
                                } else {
                                    // In the case of an empty field in a struct array (e.g. the user specified
                                    // only an exposure time for the first exposure and only an ISO for the second
                                    // exposure when constructing in MATLAB), there will be a new JSON Object here.
                                    // Other fringe cases may have other things. Just remove whatever it is, since
                                    // is definitely not right.
                                    reader.skipValue();
                                    Log.v(DevCam.APP_TAG,"Skipped an unexpected JSON field value.");
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
                                } else {
                                    reader.skipValue();
                                    Log.v(DevCam.APP_TAG,"Skipped an unexpected JSON field value.");
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
                                } else {
                                    reader.skipValue();
                                    Log.v(DevCam.APP_TAG,"Skipped an unexpected JSON field value.");
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
                                } else {
                                    reader.skipValue();
                                    Log.v(DevCam.APP_TAG,"Skipped an unexpected JSON field value.");
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
                                } else {
                                    reader.skipValue();
                                    Log.v(DevCam.APP_TAG,"Skipped an unexpected JSON field value.");
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
                    Log.e(DevCamActivity.APP_TAG,"IOException reading Design JSON file.");
                    throw ioe;
                }
            } catch (FileNotFoundException fnfe){
                Log.e(DevCamActivity.APP_TAG,"Design JSON file not found.");
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