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

import android.hardware.camera2.CaptureResult;
import android.media.Image;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public class DesignResult {

	private int mDesignLength;
	private List<CaptureResult> mCaptureResults = new CopyOnWriteArrayList<CaptureResult>();
//	private Map<Long,CaptureResult> mCaptureResults = new HashMap<Long,CaptureResult>();
	private List<Long> mCaptureTimestamps = new ArrayList<Long>();
	private List<Image> mImages = new CopyOnWriteArrayList<Image>();
//	private Map<Long,Image> mImages = new HashMap<Long,Image>();
	private OnCaptureAvailableListener mRegisteredListener;
    private int mNumAssociated = 0;

	// - - - Constructor - - -
	public DesignResult(int designLength, OnCaptureAvailableListener listener){
		mDesignLength = designLength;
		mRegisteredListener = listener;
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
		//Log.v(DevCamActivity.APP_TAG, mCaptureResults.size() + " CaptureResults Recorded.");

		for (int i=0; i<mImages.size(); i++){
			Log.v(DevCamActivity.APP_TAG,"Comparing with stored Image " + i);
			if (mImages.get(i).getTimestamp()==result.get(CaptureResult.SENSOR_TIMESTAMP)){
                // Send back to the main Activity.
                if (null!=mRegisteredListener) {
                    mRegisteredListener.onCaptureAvailable(mImages.get(i), result);
                }
				mImages.remove(i); // remove from List because we can't access this image once it is close()'d by the ImageSaver
				//Log.v(DevCamActivity.APP_TAG,mImages.toString());

                mNumAssociated++;
				checkIfComplete();
				return;
			}
		}
		//Log.v(DevCamActivity.APP_TAG,"No existing Image found. Storing for later.");
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

                // Send back to the main Activity.
                if (null!=mRegisteredListener) {
                    mRegisteredListener.onCaptureAvailable(image, result);
                }

                mNumAssociated++;
				checkIfComplete();
				//Log.v(DevCamActivity.APP_TAG,"Existing CaptureResult matched to Image. Writing out.");
				return;
			}
		}

        // If there was no CaptureResult associated with this image yet, save it until one is.
        mImages.add(image);
		//Log.v(DevCamActivity.APP_TAG,"No existing CaptureResult found. Storing for later.");
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
        if (mNumAssociated==mDesignLength){
            //Log.v(DevCamActivity.APP_TAG, "DesignResult: Capture Sequence Complete. Saving results. ");
            if (null!=mRegisteredListener) {
                mRegisteredListener.onAllCapturesReported(this);
            }
        }
    }



	static public abstract class OnCaptureAvailableListener{
		public void onCaptureAvailable(Image image, CaptureResult result){};
        public void onAllCapturesReported(DesignResult designResult){};
	}


} // end whole class