// This is the only place Images are closed to free up buffers, except those Auto images immediately discarded.

package com.devcam;

import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.DngCreator;
import android.media.Image;

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


	public ImageSaver(Image image, CaptureResult captureResult, CameraCharacteristics camChars, File saveDir, String filename) {
		mImage = image;
		mCaptureResult = captureResult;
		mFilename = filename;
		mCamChars = camChars;
		SAVE_DIR = saveDir;
	}

	@Override
	public void run() {
		//Log.v(cameraFragment.APP_TAG,"ImageSaver running!");
		
		if (!(SAVE_DIR.mkdir() || SAVE_DIR.isDirectory())){
			return;
		}

		File file = new File(SAVE_DIR, mFilename);;
		FileOutputStream output = null;
		ByteBuffer buffer;
		byte[] bytes;

		switch (mImage.getFormat()){
		case ImageFormat.JPEG:			
			buffer = mImage.getPlanes()[0].getBuffer();
			bytes = new byte[buffer.remaining()]; // makes byte array large enough to hold image
			buffer.get(bytes); // copies image from buffer to byte array
			try {
				output = new FileOutputStream(file);
				output.write(bytes);	// write the byte array to file
			} catch (FileNotFoundException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			} finally {
				if (null != output) {
					try {
						output.close();
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			}
			break;

		case ImageFormat.RAW_SENSOR:
			DngCreator dc = new DngCreator(mCamChars,mCaptureResult);

			try {
				output = new FileOutputStream(file);
				dc.writeImage(output, mImage);
			} catch (FileNotFoundException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			} finally {
				dc = null;
				if (null != output) {
					try {
						output.close();
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			}
			break;

		case ImageFormat.YUV_420_888:
			// Put ints indicating width/height as first two values in file
			ByteBuffer prebuffer = ByteBuffer.allocate(16);
			prebuffer.putInt(mImage.getWidth())
			.putInt(mImage.getHeight())
			.putInt(mImage.getPlanes()[1].getPixelStride())
			.putInt(mImage.getPlanes()[1].getRowStride());

			try {
				output = new FileOutputStream(file);
				output.write(prebuffer.array());

                ByteBuffer yBuffer =  mImage.getPlanes()[0].getBuffer();
                ByteBuffer uBuffer =  mImage.getPlanes()[1].getBuffer();
                ByteBuffer vBuffer =  mImage.getPlanes()[2].getBuffer();

				for (int i = 0; i<3; i++){
					buffer = mImage.getPlanes()[i].getBuffer();
					bytes = new byte[buffer.remaining()]; // makes byte array large enough to hold image
					buffer.get(bytes); // copies image from buffer to byte array
					output.write(bytes);	// write the byte array to file
				}
			} catch (FileNotFoundException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			} finally {
				if (null != output) {
					try {
						output.close();
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			}
			break;
		}

		mImage.close(); // close this to free up buffer for other images
	}

}