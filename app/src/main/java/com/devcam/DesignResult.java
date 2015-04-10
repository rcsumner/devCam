/* DesignResult class, which complements a CaptureDesign object. The DesignResult contains all of
 * the relevant information about the outputs of the capturing process of a CaptureDesign.
 * This includes, for each frame of the sequence:
 * - the timestamp of when the frame started capture. Used as a unique ID to indicate an image belonging to the sequence.
 * - the Image that was captured.
 * - the CaptureResult metadata associated with the frame
 * - the filename that this frame is to be written out to
 *
 * This information about each frame is captured as soon as it is available. In general, the
 * timestamp will come in first, since that is available as soon as the exposure starts integrating,
 * and obviously the CaptureResult and Image are not available until it is done. However, depending
 * on the device and the requested image format, the order in which these latter two pieces is
 * available is uncertain.
 *
 * To get around this, whenever a new CaptureResult or Image is available, it is stored in the
 * DesignResult. The DesignResult then checks to see if it has already registered the corresponding
 * element, based on unique timestamp ID.
 *
 * Once both elements are available and registered, the DesignResult generates a filename for the
 * frame capture and sends all three pieces back to the main activity thread for whatever action it
 * wants to take- generally writing out of the image via an ImageSaver object.
 */

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


    // - - Setters and Getter - -
	public int getDesignLength(){
		return mDesignLength;
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
    public Long getCaptureTimestamp(int i){
        return mCaptureTimestamps.get(i);
    }

	public void recordCaptureTimestamp(Long timestampID){
		mCaptureTimestamps.add(timestampID);
	}
	public boolean containsCaptureTimestamp(Long timestampID){
		return mCaptureTimestamps.contains(timestampID);
	}


    /* void recordCaptureResult(CaptureResult)
     *
     * Whenever a new CaptureResult is available from the onCaptureComplete() call, record it in the
     * DesignResult. Compare with the Images which have been generated and stored, to see if any
     * match. If so, call the function which initiates writing the frame to disk, so it can be
     * saved and the Image buffer freed ASAP. If not, record it later for when the right Image comes
     * in.
     *
     */
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


    /* void recordImage(Image)
     *
     * Whenever a new Image is available from the ImageReader, record it in the DesignResult.
     * Compare with the CaptureResults which have already been generated and stored, to see if any
     * match. If so, call the function which initiates writing the frame to disk, so it can be
     * saved and the Image buffer freed ASAP. If not, record it for later for when the right
     * CaptureResult comes in.
     */
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



    /* void checkIfComplete()
     *
     * Function called whenever an Image/CaptureResult pair has been registered, associated, and
     * passed out for writing to disk. Since a filename is created for that frame only once this
     * happens, we use the length of the filename list to indicate how many frames of the total
     * expected number have been captured/saved.
     * Once all frames have been captured and saved, invoke the CaptureDesignCallback's onFinished()
     * method to inform the main Activity class.
     */

    private void checkIfComplete(){
        if (mFilenames.size()==mDesignLength){
            Log.v(appFragment.APP_TAG,"DesignResult: Capture Sequence Complete. Saving results. ");
            mDesign.getCallback().onFinished(this);
        }
    }



    /* void sendImageForWriting(Image, CaptureResult)
     *
     * Internal function for prepping to save a frame when both the Image and its associated
     * CaptureResult have been made available to the DesignResult.
     *
     * Generates and records the file name here rather than at the ImageSaver instance so that the
     * CaptureDesign can have a record of all these names to write out in the output
     * designName_capture_metadata.json file.
     *
     * This function sends the actual Image and CaptureResult back to the main function via the
     * CaptureDesign's onImageReady(...) callback, so that main Activity can post the ImageSaver
     * on the appropriate thread and take whatever other actions necessary.
     */
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

        // Record the filename for later, with counter based on number already saved.
		String filename = mDesignName + "-" + (mFilenames.size()+1) + fileType;
		mFilenames.add(filename);

        // Send back to the main Activity.
		mDesign.getCallback().onImageReady(image,result, filename);
	}
}