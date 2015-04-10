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
 * undefined but there is a twin value which contains a string telling how to generate it from
 * a CaptureResult. If any parameter of the Exposure is not defined explicitly, it has flag for this
 * which you can poll with hasVariableValues();
 *
 * Before getting exposure parameter values from an Exposure object, make sure it actually has the
 * explicit values by
 *    (1) checking hasVariableValues()
 *       if true, (2) generating explicit values from a CaptureResult using fixValues().
 */

package com.devcam;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureResult;

import java.util.Arrays;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Exposure {

    final static String LOWER ="LOWER";
    final static String AUTO ="AUTO";
    final static String UPPER ="UPPER";

    private boolean mHasVariables = false;
    // Variable forms for the parameters
    private String mExposureTimeVar = null;
    private String mSensitivityVar = null;
    private String mApertureVar = null;
    private String mFocalLengthVar = null;
    private String mFocusDistanceVar = null;

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
    // - - - - -  end constructors - - - - -


    /* void fixValues(CameraCharacteristics, CaptureResult)
     *
     * Method for filling in any parameter values which are described as variables with actual
     * explicit numeric values based on a CaptureResult. Typically the CaptureResult was generated
     * using the A3 algorithms and contains values "reasonable for the scene" which can be used as
     * variables and then manipulated (well, multiplied).
     *
     * Note the current form uses the CameraCharacteristics as well to allow the user to define
     * variables LOWER, AUTO, UPPER, but the documentation currently only describes the AUTO
     * function. It is unclear if the LOWER and UPPER functions are useful, since the user should
     * have knowledge of these values explicitly from their cameraReport.json.
     *
     * This function also currently assumes explicitly the variable form "x*AUTO" where x is a
     * numeric value of the correct format. It was loosely checked to fit this form (doesn't check
     * against forms like "10.43.256") in the loadDesignFromJson() function in the CaptureDesign
     * class.
     */
    void fixValues(CameraCharacteristics camChars, CaptureResult autoResult){
        // Check to see if exposure time is variable-based
        if (mExposureTimeVar != null){
            String[] parts = mExposureTimeVar.split("\\s*\\*\\s*");
            Double coeff = Double.valueOf(parts[0]);
            if (parts[1].equalsIgnoreCase(Exposure.LOWER)){
                double value = coeff * camChars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE).getLower();
                mExposureTime = (long) value;
            } else if(parts[1].equalsIgnoreCase(Exposure.AUTO)){
                double value = coeff * autoResult.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                mExposureTime = (long) value;
            } else if(parts[1].equalsIgnoreCase(Exposure.UPPER)){
                double value = coeff * camChars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE).getUpper();
                mExposureTime = (long) value;
            }
        }

        // Check to see if sensitivity/ISO is variable-based
        if (mSensitivityVar != null){
            String[] parts = mSensitivityVar.split("\\s*\\*\\s*");
            Double coeff = Double.valueOf(parts[0]);
            if (parts[1].equalsIgnoreCase(Exposure.LOWER)){
                double value = coeff * camChars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE).getLower();
                mSensitivity = (int) value;
            } else if(parts[1].equalsIgnoreCase(Exposure.AUTO)){
                double value = coeff * autoResult.get(CaptureResult.SENSOR_SENSITIVITY);
                mSensitivity = (int) value;
            } else if(parts[1].equalsIgnoreCase(Exposure.UPPER)){
                double value = coeff * camChars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE).getUpper();
                mSensitivity = (int) value;
            }
        }

        // Check to see if aperture is variable-based
        if (mApertureVar != null){
            String[] parts = mApertureVar.split("\\s*\\*\\s*");
            Double coeff = Double.valueOf(parts[0]);

            if (parts[1].equalsIgnoreCase(Exposure.LOWER)){
                float[] range = camChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES);
                double value = coeff * range[0]; // assumes apertures are ordered, increasing
                mAperture = (float) value;
            } else if(parts[1].equalsIgnoreCase(Exposure.AUTO)){
                double value = coeff * autoResult.get(CaptureResult.LENS_APERTURE);
                mAperture = (float) value;
            } else if(parts[1].equalsIgnoreCase(Exposure.UPPER)){
                float[] range = camChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES);
                double value = coeff * range[range.length]; // assumes apertures are ordered, increasing
                mAperture = (float) value;
            }
        }

        // Check to see if focal length is variable-based
        if (mFocalLengthVar != null){
            String[] parts = mFocalLengthVar.split("\\s*\\*\\s*");
            Double coeff = Double.valueOf(parts[0]);

            if (parts[1].equalsIgnoreCase(Exposure.LOWER)){
                float[] range = camChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                double value = coeff * range[0]; // assumes apertures are ordered, increasing
                mFocalLength = (float) value;
            } else if(parts[1].equalsIgnoreCase(Exposure.AUTO)){
                double value = coeff * autoResult.get(CaptureResult.LENS_FOCAL_LENGTH);
                mFocalLength = (float) value;
            } else if(parts[1].equalsIgnoreCase(Exposure.UPPER)){
                float[] range = camChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                double value = coeff * range[range.length]; // assumes apertures are ordered, increasing
                mFocalLength = (float) value;
            }
        }

        // Check to see if focus distance is variable-based
        if (mFocusDistanceVar != null){
            String[] parts = mFocusDistanceVar.split("\\s*\\*\\s*");
            Double coeff = Double.valueOf(parts[0]);

            if (parts[1].equalsIgnoreCase(Exposure.LOWER)){
                double value = coeff * camChars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
                mFocusDistance = (float) value;
            } else if(parts[1].equalsIgnoreCase(Exposure.AUTO)){
                double value = coeff * autoResult.get(CaptureResult.LENS_FOCUS_DISTANCE);
                mFocusDistance = (float) value;
            } else if(parts[1].equalsIgnoreCase(Exposure.UPPER)){
                mFocusDistance = 0.0f;
            }
        }


    }


    // String form just simply displays the parameters readably
    @Override
    public String toString(){
        // Display the variable values if they exist. Otherwise, display the literals that should exist.
        String stringform =
                ((mApertureVar!=null)? mApertureVar + ", " : "f" + mAperture + ", ") +
                ((mExposureTimeVar!=null)? mExposureTimeVar + ", " : CameraReport.nsToString(mExposureTime) + ", ") +
                ((mSensitivityVar!=null)? mSensitivityVar + ", " : "ISO " + mSensitivity + ", ") +
                ((mFocalLengthVar!=null)? mFocalLengthVar + ", " : mFocalLength + "mm, focus: ") +
                ((mFocusDistanceVar!=null)? mFocusDistanceVar : CameraReport.diopterToMeters(mFocusDistance));

        return stringform;
    }

	/* - - - - - Setters and Getters - - - - -
     * Note that the following use "set"/"get" for literal parameter values, and "record" for
     * variable (string) parameter values. Effectively, the Exposure doesn't have any literal values
     * until they have been set, either with the set() functions or fixExposure(), above.
     */

	public Long getExposureTime() {
		return mExposureTime;
	}
	public void setExposureTime(Long mExposureTime) {
		this.mExposureTime = mExposureTime;
	}
	public Integer getSensitivity() {
		return mSensitivity;
	}
	public void setSensitivity(Integer mSensitivity) {
		this.mSensitivity = mSensitivity;
	}
	public Float getAperture() {
		return mAperture;
	}
	public void setAperture(Float mAperture) {
		this.mAperture = mAperture;
	}
	public Float getFocalLength() {
		return mFocalLength;
	}
	public void setFocalLength(Float mFocalLength) {
		this.mFocalLength = mFocalLength;
	}
	public Float getFocusDistance() {
		return mFocusDistance;
	}
	public void setFocusDistance(Float mFocusDistance) {
		this.mFocusDistance = mFocusDistance;
	}

    // As soon as any parameter has a variable value, this exposure is "not legit" and will need
    // fixing before being read into a CaptureRequest. So as soon as a recordXXXvar() method is
    // called, set the flag indicating variable values to true.

    public void recordExposureTimeVar(String var){
        mExposureTimeVar = var;
        mHasVariables = true;
    }

    public void recordSensitivityVar(String var){
        mSensitivityVar = var;
        mHasVariables = true;
    }

    public void recordApertureVar(String var){
        mApertureVar = var;
        mHasVariables = true;
    }

    public void recordFocalLengthVar(String var){
        mFocalLengthVar = var;
        mHasVariables = true;
    }

    public void recordFocusDistanceVar(String var){
        mFocusDistanceVar = var;
        mHasVariables = true;
    }

    boolean hasVariableValues(){return mHasVariables;}

}
