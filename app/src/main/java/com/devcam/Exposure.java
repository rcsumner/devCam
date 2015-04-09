/* Exposure objects represent a single photographic exposure, based on the set of parameters that
 * define one in the eyes of devCam. These include:
 * - exposure time
 * - sensitivity (ISO)
 * - aperture
 * - focal length
 * - focus distance
 *
 * Every other property of an output frame is considered an image property, not a photographic one.
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
    final static String UPPER ="HIGHER";

    private boolean mHasVariables = false;
    private String mExposureTimeVar = null;
    private String mSensitivityVar = null;
    private String mApertureVar = null;
    private String mFocalLengthVar = null;
    private String mFocusDistanceVar = null;
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
        String stringform =
                ((mApertureVar!=null)? mApertureVar + ", " : "f" + mAperture + ", ") +
                ((mExposureTimeVar!=null)? mExposureTimeVar + ", " : CameraReport.nsToString(mExposureTime) + ", ") +
                ((mSensitivityVar!=null)? mSensitivityVar + ", " : "ISO " + mSensitivity + ", ") +
                ((mFocalLengthVar!=null)? mFocalLengthVar + ", " : mFocalLength + "mm, focus: ") +
                ((mFocusDistanceVar!=null)? mFocusDistanceVar : CameraReport.diopterToMeters(mFocusDistance));

        return stringform;
    }

	// - - - - - Setters and Getters - - - - -
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
