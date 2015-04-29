/* Runnable class for saving an captured frame in the appropriate devCam-allowed format.
 *
 * Pass images from the ImageReader here via the constructor, and it is this class' responsibility
 * to close those images to free the buffers so future images can be saved.
 *
 * Saves JPEG format images as .jpg
 *       RAW_SENSOR format images as .dng (using the DngCreator class)
 *       YUV_420_888 format images as our own .yuv class.
 */

package com.devcam;

import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.DngCreator;
import android.media.Image;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

class ImageSaver implements Runnable {

	private final Image mImage;
	private final CaptureResult mCaptureResult;
	private final String mFilename;
	private final CameraCharacteristics mCamChars;
	private final File SAVE_DIR;
    private final WriteOutCallback mRegisteredCallback;


    /* Constructor for this "action" class.
     * Information required for saving a general frame from devCam:
     * - the Image
     * - the CaptureResult associated with the frame, necessary for DNG creation
     * - the CameraCharacteristics of the devie that captured the frame, necessary for DNG creation
     * - the directory name to save the file in
     * - the name of the file, *including extension*
     */

	public ImageSaver(Image image, CaptureResult captureResult, CameraCharacteristics camChars, File saveDir, String filename, WriteOutCallback callback) {
		mImage = image;
		mCaptureResult = captureResult;
		mFilename = filename;
		mCamChars = camChars;
		SAVE_DIR = saveDir;
        mRegisteredCallback = callback;
	}

	@Override
	public void run() {
		Log.v(DevCamActivity.APP_TAG, "ImageSaver running!");

        // Make sure we have a directory to save the image to.
		if (!(SAVE_DIR.mkdir() || SAVE_DIR.isDirectory())){
            mImage.close(); // make sure buffer is freed
			return;
		}

        // Assemble variables to put things
		File file = new File(SAVE_DIR, mFilename);
		FileOutputStream output = null;
		ByteBuffer buffer;
		byte[] bytes;
        boolean success = false;

		switch (mImage.getFormat()){

        // Saving JPEG is fairly straightforward, just get the one plane of compressed data.
        case ImageFormat.JPEG:
			buffer = mImage.getPlanes()[0].getBuffer();
			bytes = new byte[buffer.remaining()]; // makes byte array large enough to hold image
			buffer.get(bytes); // copies image from buffer to byte array
			try {
				output = new FileOutputStream(file);
				output.write(bytes);	// write the byte array to file
                success = true;
			} catch (FileNotFoundException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			} finally {
                Log.v(DevCamActivity.APP_TAG,"Closing image to free buffer.");
                mImage.close(); // close this to free up buffer for other images
                if (null != output) {
                    try {
                        output.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                if (mRegisteredCallback!=null) {
                    mRegisteredCallback.onImageSaved(success, mFilename); //let the main Activity know we're done
                }
            }
			break;

        // Saving RAW_SENSOR just uses the built-in DngCreator, which is nice
		case ImageFormat.RAW_SENSOR:
			DngCreator dc = new DngCreator(mCamChars,mCaptureResult);

			try {
				output = new FileOutputStream(file);
				dc.writeImage(output, mImage);
                success = true;
			} catch (FileNotFoundException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			} finally {
                Log.v(DevCamActivity.APP_TAG,"Closing image to free buffer.");
                mImage.close(); // close this to free up buffer for other images
                if (null != output) {
                    try {
                        output.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                if (mRegisteredCallback!=null) {
                    mRegisteredCallback.onImageSaved(success, mFilename); //let the main Activity know we're done
                }
            }
			break;

        // YUV_420_888 images are saved in a format of our own devising. First write out the
        // information necessary to reconstruct the image, all as ints: width, height, U-,V-plane
        // pixel strides, and U-,V-plane row strides. (Y-plane will have pixel-stride 1 always.)
        // Then directly place the three planes of byte data, uncompressed.
        //
        // Note the YUV_420_888 format does not guarantee the last pixel makes it in these planes,
        // so some cases are necessary at the decoding end, based on the number of bytes present.
        // An alternative would be to also encode, prior to each plane of bytes, how many bytes are
        // in the following plane. Perhaps in the future.
		case ImageFormat.YUV_420_888:
			// "prebuffer" simply contains the meta information about the following planes.
			ByteBuffer prebuffer = ByteBuffer.allocate(16);
			prebuffer.putInt(mImage.getWidth())
			.putInt(mImage.getHeight())
			.putInt(mImage.getPlanes()[1].getPixelStride())
			.putInt(mImage.getPlanes()[1].getRowStride());

			try {
				output = new FileOutputStream(file);
				output.write(prebuffer.array()); // write meta information to file
                // Now write the actual planes.
				for (int i = 0; i<3; i++){
					buffer = mImage.getPlanes()[i].getBuffer();
					bytes = new byte[buffer.remaining()]; // makes byte array large enough to hold image
					buffer.get(bytes); // copies image from buffer to byte array
					output.write(bytes);	// write the byte array to file
				}
                success = true;
			} catch (FileNotFoundException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			} finally {
                Log.v(DevCamActivity.APP_TAG,"Closing image to free buffer.");
                mImage.close(); // close this to free up buffer for other images
				if (null != output) {
					try {
						output.close();
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
                if (mRegisteredCallback!=null) {
                    mRegisteredCallback.onImageSaved(success, mFilename); //let the main Activity know we're done
                }
			}
			break;
		}

	}



    /* We use a callback class to indicate in the main thread when the ImageSaver has finished
     * writing out the file. This is useful for formats that take a long time to write
     * (e.g. RAW_SENSOR, YUV_420_888) after capture, so the app doesn't quit until after the images
     * are saved.
     */
    static abstract class WriteOutCallback{
        abstract void onImageSaved(boolean success,String filename);
    }


}