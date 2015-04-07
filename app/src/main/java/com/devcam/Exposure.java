package com.devcam;

import android.hardware.camera2.CaptureResult;

public class Exposure {

	private Long mExposureTime;
	private Integer mSensitivity;
	private Float mAperture;
	private Float mFocalLength;
	private Float mFocusDistance;


	// - - - - - Constructors - - - - -
	public Exposure(){} // Empty constructor

	// Constructor to copy one Exposure to Another
	public Exposure(Exposure exposure){
		mExposureTime = exposure.getExposureTime();
		mSensitivity = exposure.getSensitivity();
		mAperture = exposure.getAperture();
		mFocalLength = exposure.getFocalLength();
		mFocusDistance = exposure.getFocusDistance();
	}

	// Constructor to generate an Exposure from a CaptureResult
	public Exposure(CaptureResult cr){
		mExposureTime = cr.get(CaptureResult.SENSOR_EXPOSURE_TIME);
		mSensitivity = cr.get(CaptureResult.SENSOR_SENSITIVITY);
		mAperture = cr.get(CaptureResult.LENS_APERTURE);
		mFocalLength = cr.get(CaptureResult.LENS_FOCAL_LENGTH);
		mFocusDistance = cr.get(CaptureResult.LENS_FOCUS_DISTANCE);
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


	@Override
	public String toString(){
		return "f" + mAperture + ", " + CameraReport.nsToMs(mExposureTime)
				+ ", ISO " + mSensitivity + ", " 
				+ mFocalLength + "mm, focus: " 
				+ CameraReport.diopterToMeters(mFocusDistance);
	}

}
