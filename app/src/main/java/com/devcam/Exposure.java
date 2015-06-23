package com.devcam;


import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureResult;
import android.util.Log;

import java.text.DecimalFormat;

/**
 * Exposure settings object class.
 *
 * <p>An Exposure object contains the set of parameters necessary and programmable for a
 * photographic exposure. These include
 * - exposure time
 * - sensitivity (ISO)
 * - aperture
 * - focal length
 * - focus distance
 * </p>
 *
 * <p>Every other property of an output image is considered an image property, not a photographic
 * one.</p>
 *
 * <p>An Exposure can have an explicit value for each of these parameters, or a "variable" one. When
 * it has one of these, the other is null. Explicit values can be set manually, or they can be
 * calculated based on the variable definitions and a CaptureResult object which contains the
 * absolute settings of a current AF/AE-using frame.</p>
 *
 * <p>
 * Before getting exposure parameter values from an Exposure object, make sure it actually has the
 * explicit values by
 *    (1) checking hasVariableValues()
 *        and if true, (2) generating explicit values from a CaptureResult using fixValues().
 * </p>
 */
public class Exposure {

    /**
     * Flag to indicate construction of an Exposure with all variable values equal to AUTO
     */
    final static String ALL_AUTO = "ALL_AUTO";

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

    /**
     * Constructor to generate an Exposure from a CaptureResult.
     *
     * <p>Pulls the photographic parameters used in that capture and places them explicitly in the
     * appropriate fields of this Exposure.</p>
     */
	public Exposure(CaptureResult cr){
        this();
		mExposureTime = cr.get(CaptureResult.SENSOR_EXPOSURE_TIME);
		mSensitivity = cr.get(CaptureResult.SENSOR_SENSITIVITY);
		mAperture = cr.get(CaptureResult.LENS_APERTURE);
		mFocalLength = cr.get(CaptureResult.LENS_FOCAL_LENGTH);
		mFocusDistance = cr.get(CaptureResult.LENS_FOCUS_DISTANCE);
	}

    /**
     * Constructor to create an Exposure from a template constant.
     *
     * @param flag Template constant for producing a standard Exposure. Currently, only
     *             {@link #ALL_AUTO} is supported.
     */
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

    /**
     *  Copy constructor to create an Exposure from another Exposure.
     *
     *  <p>This copies the values, not the references of the internal fields, so that if, for
     *  example, the original Exposure has its variable values fixed to explicit ones after
     *  copying, the same doesn't happen to the new one.</p>
     */
    public Exposure(Exposure e){
        this();

        // Copy any explicit values first
        if (e.getExposureTime() != null){
            this.setExposureTime(Long.valueOf(e.getExposureTime()));
        }
        if (e.getAperture() != null) {
            this.setAperture(Float.valueOf(e.getAperture()));
        }
        if (e.getFocalLength() != null){
            this.setFocalLength(Float.valueOf(e.getFocalLength()));
        }
        if (e.getFocusDistance() != null){
            this.setFocusDistance(Float.valueOf(e.getFocusDistance()));
        }
        if (e.getSensitivity() != null){
            this.setSensitivity(Integer.valueOf(e.getSensitivity()));
        }

        // Now copy any variable values, if they exist
        if (e.hasVariableAperture()){
            this.recordApertureVar(e.getApertureString());
        }
        if (e.hasVariableExposureTime()){
            this.recordExposureTimeVar(e.getExposureTimeString());
        }
        if (e.hasVariableFocalLength()){
            this.recordFocalLengthVar(e.getFocalLengthString());
        }
        if (e.hasVariableFocusDistance()){
            this.recordFocusDistanceVar(e.getFocusDistanceString());
        }
        if (e.hasVariableSensitivity()){
            this.recordSensitivityVar(e.getSensitivityString());
        }
    }

    // - - - - -  end constructors - - - - -



    /**
     * Fixes any variable exposure parameter values to explicit values based on a the current camera
     * readings.
     *
     * <p>After running this, this Exposure will report having no variable values.</p>
     *
     * @param camChars Characteristics object for this camera, used for device bounds.
     * @param autoResult CaptureResult from a recent camera frame which used the AE/AF routines.
     *
     */
    public void fixValues(CameraCharacteristics camChars, CaptureResult autoResult){
        Log.v(DevCam.APP_TAG,"Fixing Values in Exposure.");
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
        mHasVariables = false;
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

    /**
     * Get a pretty string representing the exposure time value of this Exposure, be it explicit or
     * variable, without units.
     *
     * @return String indicating the exposure time of this Exposure.
     */
    public String getExposureTimeString(){
        String s = (mExposureTimeVar!=null)? mExposureTimeVar.toString() : CameraReport.nsToString(mExposureTime);
        return s;
    }

    /**
     * Get a pretty string representing the aperture value of this Exposure, be it explicit or
     * variable, without units.
     *
     * @return String indicating the aperture of this Exposure.
     */
    public String getApertureString(){
        return (mApertureVar!=null)? mApertureVar.toString() : "f" + mAperture;
    }

    /**
     * Get a pretty string representing the ISO/sensitivity value of this Exposure, be it explicit
     * or variable, without units.
     *
     * @return String indicating the ISO of this Exposure.
     */
    public String getSensitivityString(){
        return (mSensitivityVar!=null)? mSensitivityVar.toString() : mSensitivity.toString();
    }

    /**
     * Get a pretty string representing the focal length value of this Exposure, be it explicit
     * or variable, without units.
     *
     * @return String indicating the focal length of this Exposure.
     */
    public String getFocalLengthString(){
        return (mFocalLengthVar!=null)? mFocalLengthVar.toString() : mFocalLength + " mm";
    }

    /**
     * Get a pretty string representing the focus distance value of this Exposure, be it explicit
     * or variable, without units.
     *
     * @return String indicating the focus distance of this Exposure.
     */
    public String getFocusDistanceString(){
        return (mFocusDistanceVar!=null)? mFocusDistanceVar.toString() : CameraReport.diopterToMeters(mFocusDistance);
    }


	/* - - - - - Setters and Getters - - - - -
     * Note that the following use "set"/"get" for literal parameter values, and "record" for
     * variable (string) parameter values. Effectively, the Exposure doesn't have any literal values
     * until they have been set, either with the set() functions or fixExposure(), above.
     */

    // Note that the literal value setters override the variable value's presence, and vice versa

    /**
     * Get the explicit value of the exposure time.
     *
     * <p>If the value has not been set yet or is variable, this returns null.</p>
     *
     * @see #hasVariableExposureTime()
     */
	public Long getExposureTime() {
		return mExposureTime;
	}

    /**
     * Get the explicit value of the ISO/sensitivity.
     *
     * <p>If the value has not been set yet or is variable, this returns null.</p>
     *
     * @see Exposure#hasVariableSensitivity()
     */
    public Integer getSensitivity() {
        return mSensitivity;
    }

    /**
     * Get the explicit value of the aperture.
     *
     * <p>If the value has not been set yet or is variable, this returns null.</p>
     *
     * @see #hasVariableAperture()
     */
    public Float getAperture() {
        return mAperture;
    }

    /**
     * Get the explicit value of the focal length.
     *
     * <p>If the value has not been set yet or is variable, this returns null.</p>
     *
     * @see Exposure#hasVariableFocalLength()
     */
    public Float getFocalLength() {
        return mFocalLength;
    }

    /**
     * Get the explicit value of the focus distance.
     *
     * <p>If the value has not been set yet or is variable, this returns null.</p>
     *
     * @see Exposure#hasVariableFocusDistance()
     */
    public Float getFocusDistance() {
        return mFocusDistance;
    }

    /**
     * Set the explicit value of the exposure time.
     *
     * <p>This overwrites any existing variable value the parameter has.</p>
     */
	public void setExposureTime(Long exposureTime) {
		mExposureTime = exposureTime;
        mExposureTimeVar = null;
        checkIfHasVariableValues();
	}

    /**
     * Set the explicit value of the sensitivity.
     *
     * <p>This overwrites any existing variable value the parameter has.</p>
     */
	public void setSensitivity(Integer sensitivity) {
		mSensitivity = sensitivity;
        mSensitivityVar = null;
        checkIfHasVariableValues();
	}

    /**
     * Set the explicit value of the aperture.
     *
     * <p>This overwrites any existing variable value the parameter has.</p>
     */
	public void setAperture(Float aperture) {
		mAperture = aperture;
        mApertureVar = null;
        checkIfHasVariableValues();
	}

    /**
     * Set the explicit value of the focal length.
     *
     * <p>This overwrites any existing variable value the parameter has.</p>
     */
	public void setFocalLength(Float focalLength) {
        mFocalLength = focalLength;
        mFocalLengthVar = null;
        checkIfHasVariableValues();
    }

    /**
     * Set the explicit value of the focus distance.
     *
     * <p>This overwrites any existing variable value the parameter has.</p>
     */
	public void setFocusDistance(Float focusDistance) {
		mFocusDistance = focusDistance;
        mFocusDistanceVar = null;
        checkIfHasVariableValues();
	}


    // As soon as any parameter has a variable value, this exposure is "not legit" and will need
    // fixing before being read into a CaptureRequest. So as soon as a recordXXXvar() method is
    // called, set the flag indicating variable values to true.

    /**
     * Set a variable value for the exposure time.
     *
     * <p>This overwrites any existing explicit value this parameter, setting it to null.</p>
     */
    public void recordExposureTimeVar(String var){
        mExposureTimeVar = new ExposureParameterVariable(var);
        mExposureTime = null;
        mHasVariables = true;
    }

    /**
     * Set a variable value for the sensitivity/ISO.
     *
     * <p>This overwrites any existing explicit value this parameter, setting it to null.</p>
     */
    public void recordSensitivityVar(String var){
        mSensitivityVar = new ExposureParameterVariable(var);
        mSensitivity = null;
        mHasVariables = true;
    }

    /**
     * Set a variable value for the aperture.
     *
     * <p>This overwrites any existing explicit value this parameter, setting it to null.</p>
     */
    public void recordApertureVar(String var){
        mApertureVar = new ExposureParameterVariable(var);
        mAperture = null;
        mHasVariables = true;
    }

    /**
     * Set a variable value for the focal length.
     *
     * <p>This overwrites any existing explicit value this parameter, setting it to null.</p>
     */
    public void recordFocalLengthVar(String var){
        mFocalLengthVar = new ExposureParameterVariable(var);
        mFocalLength = null;
        mHasVariables = true;
    }

    /**
     * Set a variable value for the focus distance.
     *
     * <p>This overwrites any existing explicit value this parameter, setting it to null.</p>
     */
    public void recordFocusDistanceVar(String var){
        mFocusDistanceVar = new ExposureParameterVariable(var);
        mFocusDistance = null;
        mHasVariables = true;
    }


    /**
     * Indicates if this Exposure has any parameter fields which are variable, and hence not
     * explicit.
     *
     * <p> Note that if this is true, trying to get explicit parameter values may return some null
     * values.</p>
     */
    public boolean hasVariableValues(){return mHasVariables;}


    // Likewise, these functions are public for simply poling if each individual param is variable

    /**
     * Indicates if this Exposure has a variable exposure time value.
     *
     * <p>Note that if true, then it does NOT have a valid explicit value for this parameter.</p>
     */
    public boolean hasVariableExposureTime(){return mExposureTimeVar!=null;}

    /**
     * Indicates if this Exposure has a variable aperture value.
     *
     * <p>Note that if true, then it does NOT have a valid explicit value for this parameter.</p>
     */
    public boolean hasVariableAperture(){return mApertureVar!=null;}

    /**
     * Indicates if this Exposure has a variable sensitivity/ISO value.
     *
     * <p>Note that if true, then it does NOT have a valid explicit value for this parameter.</p>
     */
    public boolean hasVariableSensitivity(){return mSensitivityVar!=null;}

    /**
     * Indicates if this Exposure has a variable focus distance value.
     *
     * <p>Note that if true, then it does NOT have a valid explicit value for this parameter.</p>
     */
    public boolean hasVariableFocusDistance(){return mFocusDistanceVar!=null;}

    /**
     * Indicates if this Exposure has a variable focal length value.
     *
     * <p>Note that if true, then it does NOT have a valid explicit value for this parameter.</p>
     */
    public boolean hasVariableFocalLength(){return mFocalLengthVar!=null;}



    // This is the internal function for seeing if this Exposure has variable values left, and
    // turning off mHasVariables if not. Used whenever an explicit-value setter is called.

    /**
     * Sets the internal flag for this Exposure indicating if it has any variable parameters or not.
     */
    private void checkIfHasVariableValues(){
        if (mExposureTimeVar==null
                && mSensitivityVar==null
                && mApertureVar==null
                && mFocalLengthVar==null
                && mFocusDistanceVar==null){
            mHasVariables = false;
        }
    }




    /**
     *  Class representing variable parameters for the fields of an Exposure.
     *
     *  <p>This class incorporates all the work involved in parsing strings for valid variable
     *  values, and stores the two relevant components of each instance. The user can get these
     *  multiplier and variable fields using the appropriate get() methods.</p>
     */
    static public class ExposureParameterVariable {

        private Float multiplier;
        private String variable;

        // Constructor based on parsing an input string of the right format

        /**
         * Constructor
         *
         * @param input A string to be parsed into a variable parameter. It should conform to the
         *              required format: either just a variable symbol ("a","A","AUTO", or "auto")
         *              or the form "[multiplier]*[symbol]" where the multiplier is a numeric value
         *              and the symbol is as described above (the square brackets are excluded). If
         *              an input is invalid, will just return a object that treats the input as "A".
         */
        ExposureParameterVariable(String input){
            // First, make sure input is correct. Should have already been checked, actually.
            if (!checkFeasibleInput(input)){
                Log.e(DevCam.APP_TAG,"Input string to Parameter Variable was malformed! Using Default.");
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

        /**
         * Get the numeric value of the scaling factor for this variable parameter.
         */
        public Float getMultiplier() {
            return multiplier;
        }

        /**
         * Get the variable label used to describe this variable parameter.
         *
         * <p>Note this currently is always some form of "A", "AUTO", etc. In the future this may
         * be expanded to include some variables indicating upper and lower device limits.</p>
         */
        public String getVariable() {
            return variable;
        }

        /**
         * Checks to see if a string appropriately matches a pattern than can be read into an
         * ExposureParameterVariable.
         */
        public static boolean checkFeasibleInput(String input){
            // The following expression matches any string that ends in either:
            // "a","A","auto","AUTO", "Auto" (or even "aUTO")
            // and also may have all of: a number consisting of "[some digits].[some digits]*"
            // Cannot have "[some digits].*" or "*" or ".[some digits]*"
            // Thus must have leading 0 for decimals.
            boolean truth = input.matches("([0-9]+?(\\.[0-9]+?)?\\*)?[Aa]((UTO)|(uto))?");
            return truth;
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
    }

}
