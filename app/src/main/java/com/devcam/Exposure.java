/* Exposure objects represent a single photographic exposure, based on the set of parameters that
 * define one in the eyes of devCam. These include:
 * - exposure time
 * - sensitivity (ISO)
 * - aperture
 * - focal length
 * - focus distance
 * Every other property of an output frame is considered an image property, not a photographic one.
 *
 * Exposure objects can have "variable" parameter values, which means the actual parameter value is
 * undefined but there is a twin value which contains an object telling how to generate it from
 * a CaptureResult. If any parameter of the Exposure is not defined explicitly, it has flag for this
 * which you can poll with hasVariableValues();
 *
 * Before getting exposure parameter values from an Exposure object, make sure it actually has the
 * explicit values by
 *    (1) checking hasVariableValues()
 *       if true, (2) generating explicit values from a CaptureResult using fixValues().
 *
 * Exposure parameters are exclusively either explicit or variable.
 *
 * Contains the ExposureParameterVariable inner class, which simplifies the reading and storing of
 * variable forms.
 */

package com.devcam;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureResult;
import android.util.Log;

import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Exposure {

    // used simply as a flag to indicate construction of an Exposure with all variable values
    // based on Auto
    final static String ALL_AUTO = "ALL_AUTO";

    // used to parse variable parameter values
    final static String LOWER ="LOWER";
    final static String AUTO ="AUTO";
    final static String UPPER ="UPPER";

    // Array of

    private boolean mHasVariables = false;
    // Variable forms for the parameters
    private ExposureParameterVariable mExposureTimeVar = null;
    private ExposureParameterVariable mSensitivityVar = null;
    private ExposureParameterVariable mApertureVar = null;
    private ExposureParameterVariable mFocalLengthVar = null;
    private ExposureParameterVariable mFocusDistanceVar = null;

    // Explicit, numeric values of the parameters
	private Long mExposureTime;
	private Integer mSensitivity;
	private Float mAperture;
	private Float mFocalLength;
	private Float mFocusDistance;


	// - - - - - Constructors - - - - -
	public Exposure(){} // Empty constructor

	// Constructor to generate an Exposure from a CaptureResult
	public Exposure(CaptureResult cr){
		mExposureTime = cr.get(CaptureResult.SENSOR_EXPOSURE_TIME);
		mSensitivity = cr.get(CaptureResult.SENSOR_SENSITIVITY);
		mAperture = cr.get(CaptureResult.LENS_APERTURE);
		mFocalLength = cr.get(CaptureResult.LENS_FOCAL_LENGTH);
		mFocusDistance = cr.get(CaptureResult.LENS_FOCUS_DISTANCE);
	}

    public Exposure(String flag){
        this();
        if (flag.equals(Exposure.ALL_AUTO)) {
            mHasVariables = true;
            mExposureTimeVar = new ExposureParameterVariable("AUTO");
            mSensitivityVar = new ExposureParameterVariable("AUTO");
            mApertureVar = new ExposureParameterVariable("AUTO");
            mFocalLengthVar = new ExposureParameterVariable("AUTO");
            mFocusDistanceVar = new ExposureParameterVariable("AUTO");
        }
    }
    // - - - - -  end constructors - - - - -


    /* void fixValues(CameraCharacteristics, CaptureResult)
     *
     * Method for filling in any parameter values which are described as variables with actual
     * explicit numeric values based on a CaptureResult. Typically the CaptureResult was generated
     * using the A3 algorithms and contains values "reasonable for the scene" which can be used as
     * variables and then manipulated (well, multiplied).
     *
     * This function also currently assumes explicitly the variable form "x*AUTO" where x is a
     * numeric value of the correct format. It was loosely checked to fit this form (doesn't check
     * against forms like "10.43.256") in the loadDesignFromJson() function in the CaptureDesign
     * class.
     */
    void fixValues(CameraCharacteristics camChars, CaptureResult autoResult){
        // Check to see if exposure time is variable-based
        if (mExposureTimeVar != null){
            float coeff = mExposureTimeVar.getMultiplier();
                double value = coeff * autoResult.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                mExposureTime = (long) value;
        }

        // Check to see if sensitivity/ISO is variable-based
        if (mSensitivityVar != null){
            float coeff = mSensitivityVar.getMultiplier();
                double value = coeff * autoResult.get(CaptureResult.SENSOR_SENSITIVITY);
                mSensitivity = (int) value;
        }

        // Check to see if aperture is variable-based
        if (mApertureVar != null){
            float coeff = mApertureVar.getMultiplier();
                double value = coeff * autoResult.get(CaptureResult.LENS_APERTURE);
                mAperture = (float) value;
        }

        // Check to see if focal length is variable-based
        if (mFocalLengthVar != null){
            float coeff = mFocalLengthVar.getMultiplier();
            double value = coeff * autoResult.get(CaptureResult.LENS_FOCAL_LENGTH);
                mFocalLength = (float) value;
        }

        // Check to see if focus distance is variable-based
        if (mFocusDistanceVar != null){
            float coeff = mFocusDistanceVar.getMultiplier();
                double value = coeff * autoResult.get(CaptureResult.LENS_FOCUS_DISTANCE);
                mFocusDistance = (float) value;
        }
    }


    // String form just simply displays the parameters prettily
    @Override
    public String toString(){
        // Display the variable values if they exist. Otherwise, display the literals that should exist.
        String stringform =
                ((mExposureTimeVar!=null)? mExposureTimeVar + ", " : CameraReport.nsToString(mExposureTime) + ", ") +
                ((mSensitivityVar!=null)? mSensitivityVar + ", " : "ISO " + mSensitivity + ", ") +
                ((mFocusDistanceVar!=null)? mFocusDistanceVar + ", " : CameraReport.diopterToMeters(mFocusDistance)+ ", ") +
                ((mApertureVar!=null)? mApertureVar + ", " : "f" + mAperture + ", ") +
                ((mFocalLengthVar!=null)? mFocalLengthVar + ", " : mFocalLength + "mm");

        return stringform;
    }

    // The following functions are used to get a string representation of the fields, without having
    // to know if the fields are explicit or variable.
    String getExposureTimeString(){
        return (mExposureTimeVar!=null)? mExposureTimeVar.toString() : CameraReport.nsToString(mExposureTime);
    }

    String getApertureString(){
        return (mApertureVar!=null)? mApertureVar.toString() : "f" + mAperture;
    }

    String getSensitivityString(){
        return (mSensitivityVar!=null)? mSensitivityVar.toString() : mSensitivity.toString();
    }

    String getFocalLengthString(){
        return (mFocalLengthVar!=null)? mFocalLengthVar.toString() : mFocalLength + " mm";
    }

    String getFocusDistanceString(){
        return (mFocusDistanceVar!=null)? mFocusDistanceVar.toString() : CameraReport.diopterToMeters(mFocusDistance);
    }

	/* - - - - - Setters and Getters - - - - -
     * Note that the following use "set"/"get" for literal parameter values, and "record" for
     * variable (string) parameter values. Effectively, the Exposure doesn't have any literal values
     * until they have been set, either with the set() functions or fixExposure(), above.
     */

    // Note that the literal value setters override the variable value's presence, and vice versa
	public Long getExposureTime() {
		return mExposureTime;
	}
	public void setExposureTime(Long exposureTime) {
		mExposureTime = exposureTime;
        mExposureTimeVar = null;
        checkIfHasVariableValues();
	}

	public Integer getSensitivity() {
		return mSensitivity;
	}
	public void setSensitivity(Integer sensitivity) {
		mSensitivity = sensitivity;
        mSensitivityVar = null;
        checkIfHasVariableValues();
	}

	public Float getAperture() {
		return mAperture;
	}
	public void setAperture(Float aperture) {
		mAperture = aperture;
        mApertureVar = null;
        checkIfHasVariableValues();
	}

	public Float getFocalLength() {
		return mFocalLength;
	}
	public void setFocalLength(Float focalLength) {
		mFocalLength = focalLength;
        mFocalLengthVar = null;
        checkIfHasVariableValues();
	}

	public Float getFocusDistance() {
		return mFocusDistance;
	}
	public void setFocusDistance(Float focusDistance) {
		mFocusDistance = focusDistance;
        mFocusDistanceVar = null;
        checkIfHasVariableValues();
	}

    // As soon as any parameter has a variable value, this exposure is "not legit" and will need
    // fixing before being read into a CaptureRequest. So as soon as a recordXXXvar() method is
    // called, set the flag indicating variable values to true.

    public void recordExposureTimeVar(String var){
        mExposureTimeVar = new ExposureParameterVariable(var);
        mExposureTime = null;
        mHasVariables = true;
    }

    public void recordSensitivityVar(String var){
        mSensitivityVar = new ExposureParameterVariable(var);
        mSensitivity = null;
        mHasVariables = true;
    }

    public void recordApertureVar(String var){
        mApertureVar = new ExposureParameterVariable(var);
        mAperture = null;
        mHasVariables = true;
    }

    public void recordFocalLengthVar(String var){
        mFocalLengthVar = new ExposureParameterVariable(var);
        mFocalLength = null;
        mHasVariables = true;
    }

    public void recordFocusDistanceVar(String var){
        mFocusDistanceVar = new ExposureParameterVariable(var);
        mFocusDistance = null;
        mHasVariables = true;
    }

    // This is the public function for seeing if this Exposure has variable values
    public boolean hasVariableValues(){return mHasVariables;}

    // Likewise, these functions are public for simply poling if each individual param is variable
    public boolean hasVariableExposureTime(){return mExposureTimeVar!=null;}
    public boolean hasVariableAperture(){return mApertureVar!=null;}
    public boolean hasVariableSensitivity(){return mSensitivityVar!=null;}
    public boolean hasVariableFocusDistance(){return mFocusDistanceVar!=null;}
    public boolean hasVariableFocalLength(){return mFocalLengthVar!=null;}

    // This is the internal function for seeing if this Exposure has variable values left, and
    // turning off mHasVariables if not. Used whenever an explicit-value setter is called.
    private void checkIfHasVariableValues(){
        if (mExposureTimeVar==null
                && mSensitivityVar==null
                && mApertureVar==null
                && mFocalLengthVar==null
                && mFocusDistanceVar==null){
            mHasVariables = false;
        }
    }




    /* ExposureParameterValue inner class, generally used only in Exposures.
     */
    static public class ExposureParameterVariable {

        private Float multiplier;
        private String variable;

        // Constructor based on parsing an input string of the right format
        ExposureParameterVariable(String input){
            // First, make sure input is correct. Should have already been checked, actually.
            if (!checkFeasibleInput(input)){
                Log.e(DevCamActivity.APP_TAG,"Input string to Parameter Variable was malformed! Using Default.");
                multiplier = 1.0f;
                variable = "Auto";
                return;
            }

            // So, assuming we have a correctly formatted input, it must be either "[num]*[string]"
            // or simply "[string]" with the correct formatting. So if there is a coeff and asterisk
            // we should split on it, otherwise keep the whole thing.
            if (input.contains("*")){
                String[] parts = input.split("\\s*\\*\\s*");
                multiplier = Float.valueOf(parts[0]);
                variable = parts[1];
            } else {
                multiplier = 1.0f;
                variable = input;
            }

        }

        public Float getMultiplier() {
            return multiplier;
        }

        public String getVariable() {
            return variable;
        }

        // Override the toString function to make sure output is pretty.
        @Override
        public String toString() {
            // If coeff==1, just return the variable
            if (Math.abs(multiplier - 1) < 0.000001) {
                return variable;
            } else {
                DecimalFormat df = new DecimalFormat("@@@");
                return df.format(multiplier) + "*" + variable;
            }
        }

        /* static boolean checkFeasibleInput(String)
         *
         * Checks to see if a string appropriately matches a pattern than can be read into an
         * ExposureParameterVariable
         */
        static boolean checkFeasibleInput(String input){
            // The following expression matches any string that ends in either:
            // "a","A","auto","AUTO", "Auto" (or even "aUTO")
            // and also may have all of: a number consisting of "[some digits].[some digits]*"
            // Cannot have "[some digits].*" or "*" or ".[some digits]*"
            // Thus must have leading 0 for decimals.
            boolean truth = input.matches("([0-9]+?(\\.[0-9]+?)?\\*)?[Aa]((UTO)|(uto))?");
            return truth;
        }
    }

}
