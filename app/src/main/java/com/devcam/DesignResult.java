package com.devcam;

import android.graphics.ImageFormat;
import android.hardware.camera2.CaptureResult;
import android.media.Image;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public class DesignResult {

	private int mDesignLength;
	private String mDesignName;
	private List<CaptureResult> mCaptureResults = new ArrayList<CaptureResult>();
	private List<Long> mCaptureRelativeStartTimes = new ArrayList<Long>();
	private List<Long> mCaptureTimestamps = new ArrayList<Long>();
	private List<Image> mImages = new ArrayList<Image>();
	private List<String> mFilenames = new ArrayList<String>();
	private CaptureDesign mDesign;

	// - - - Constructor - - -
	public DesignResult(String name, int designLength, CaptureDesign design){
		mDesignLength = designLength;
		mDesignName = name;
		mDesign = design;
	}

	public int getDesignLength(){
		return mDesignLength;
	}
	
	public List<Long> getRelativeStartTimes(){
		return mCaptureRelativeStartTimes;
	}

	public CaptureResult getCaptureResult(int i){
		return mCaptureResults.get(i);
	}

	public List<CaptureResult> getCaptureResults(){
		return mCaptureResults;
	}

	public List<String> getFilenames(){
		return mFilenames;
	}

	public void recordCaptureTimestamp(Long timestampID){
		mCaptureTimestamps.add(timestampID);
	}

	public boolean containsCaptureTimestamp(Long timestampID){
		return mCaptureTimestamps.contains(timestampID);
	}

	public Long getCaptureTimestamp(int i){
		return mCaptureTimestamps.get(i);
	}

	public void recordRelativeStartTime(Long startTime){
		mCaptureRelativeStartTimes.add(startTime);
	}

//	public Long getRelativeStartTime(int i){
//		return mCaptureRelativeStartTimes.get(i);
//	}

	public void recordCaptureResult(CaptureResult result){
		mCaptureResults.add(result);
		Log.v(appFragment.APP_TAG, mCaptureResults.size() + " CaptureResults Recorded.");
		for (int i=0; i<mImages.size(); i++){
			Log.v(appFragment.APP_TAG,"Comparing with stored Image " + i);
			if (mImages.get(i).getTimestamp()==result.get(CaptureResult.SENSOR_TIMESTAMP)){
				sendImageForWriting(mImages.get(i),result);
				mImages.remove(i); // remove from List because we can't access this image once it is close()'d by the ImageSaver
				Log.v(appFragment.APP_TAG,mImages.toString());
				checkIfComplete();
				return;
			}
		}
		Log.v(appFragment.APP_TAG,"No existing Image found. Storing for later.");
	}
	
	private void checkIfComplete(){
		// Check to see if entire sequence has been captured
		if (mFilenames.size()==mDesignLength){
			Log.v(appFragment.APP_TAG,"DesignResult: Capture Sequence Complete. Saving results. ");
			mDesign.getCallback().onFinished(this);
		}
	}

	public void recordImage(Image image){
		for (CaptureResult result : mCaptureResults){
			if (result.get(CaptureResult.SENSOR_TIMESTAMP)==image.getTimestamp()){
				sendImageForWriting(image,result);
				checkIfComplete();
				Log.v(appFragment.APP_TAG,"Existing CaptureResult matched to Image. Writing out.");
				return;
			}
		}
		
		// If no associated CaptureResult was found, save the image for later.
		mImages.add(image);
		Log.v(appFragment.APP_TAG,"No existing CaptureResult found. Storing for later.");
	}

	private void sendImageForWriting(Image image, CaptureResult result){
		String fileType = "";
		switch (image.getFormat()){
		case ImageFormat.JPEG:
			fileType = ".jpg";
			break;
		case ImageFormat.YUV_420_888:
			fileType = ".yuv";
			break;
		case ImageFormat.RAW_SENSOR:
			fileType = ".dng";
			break;
		}

		String filename = mDesignName + "-" + (mFilenames.size()+1) + fileType;

		mFilenames.add(filename);
		mDesign.getCallback().onImageReady(image,result, filename);
	}
}