/* This class is an extension of ArrayAdapter, to be used to better arrange the list of Exposures
 * in the CaptureDesign sequence on the main interface. It does this by overriding the View used
 * for each list item so that it is not derived just from Exposure.toString().
 *
 * Moreover, it uses other information about the CaptureDesign, such as the Capture Intents, to
 * use colors to visually indicate the nature of each of the parameters in each of the Exposures.
 *
 * e.g. black means the value is explicit/absolute, blue means it is variable-based.
 */

package com.devcam;

import android.content.Context;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;


public class ExposureArrayAdapter extends ArrayAdapter<Exposure> {

    Context mContext;
    CaptureDesign mDesign;
    DisplayOptionBundle mOptions;

    // Constructor is non-standard for an ArrayAdapter, as the second argument is actually a
    // CaptureDesign, not a List<Exposure>. It does contain this list, however.
    public ExposureArrayAdapter(Context context, CaptureDesign design, DisplayOptionBundle options){
        super(context, R.layout.small_text_simple_list_view_1, design.getExposures());
        mContext = context;
        mDesign = design;
        mOptions = options;
    }


    /* void registerNewCaptureDesign(CaptureDesign)
     *
     * Register a new CaptureDesign, which has both the new List of Exposures to display, but also
     * knowledge of the Intents so that they can be displayed with the correct coloring.
     *
     * This function also changes items in the list kept track of by the super class, and updates
     * the views accordingly.
     */
    public void registerNewCaptureDesign(CaptureDesign design){
        mDesign = design;
        super.clear();
        super.addAll(mDesign.getExposures());
        notifyDataSetChanged();
    }

    /* void update DisplaySettings(bundle)
     *
     * Similar to the above function, simply records the user's choice of display options and
     * makes the adapter update the views.
     */
    public void updateDisplaySettings(DisplayOptionBundle bundle){
        mOptions = bundle;
        notifyDataSetChanged();
    }


    /*This is the main function to override to make sure each View in the list is displayed
     * exactly as we want.
     *
     * The actual entire View is generated here from scratch, based on the user settings of which
     * parameters to display. The R.layout.small_text_simple_list_view_1 passed to the constructor
     * above was just a placeholder dummy.
     *
     * The aperture and focal length fields are put in a second row because these two are often
     * fixed for a device and thus are usually uninformative to show, and are not shown by default.
     * By putting them in a second row, we can reduce each Exposure to a more compact space in most
     * instances.
     */
    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
       Log.v(DevCamActivity.APP_TAG, "Adapter getView() called");
        Exposure exp = mDesign.getExposures().get(position); // get the current exposure

        // Create a LinearLayout as our base View to have the adapter use for each item.
        LinearLayout exposureView = new LinearLayout(mContext);
        exposureView.setOrientation(LinearLayout.HORIZONTAL);
        exposureView.setGravity(Gravity.CENTER_VERTICAL);

        // Display the item number on the left of the Exposure info
        TextView positionTV = new TextView(mContext);//(TextView) exposureView.findViewById(R.id.positionTextView);
        positionTV.setText(String.valueOf(position+1)+"|");
        positionTV.setPadding(0,0,10,0);
        exposureView.addView(positionTV);

        // The sublayout contains the actual exposure information. It contains two rows of parameter
        // values for this Exposure.
        LinearLayout sublayout = new LinearLayout(mContext);
        sublayout.setOrientation(LinearLayout.VERTICAL);

        // Create the rows, add them later only if they have content
        LinearLayout topRow = new LinearLayout(mContext);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout bottomRow = new LinearLayout(mContext);
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout.LayoutParams spacingParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,LinearLayout.LayoutParams.WRAP_CONTENT,1);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT);


        // Only add the following parameter views if they have been requested.
        View apertureView = makeParameterLayout("Aperture:",exp.getApertureString());
        if (mOptions.showAperture){
            bottomRow.addView(apertureView,spacingParams);
        }

        View exposureTimeView = makeParameterLayout("Exposure Time:",exp.getExposureTimeString());
        if (mOptions.showExposureTime){
            topRow.addView(exposureTimeView,spacingParams);
        }

        View sensitivityView = makeParameterLayout("ISO:",exp.getSensitivityString());
        if (mOptions.showSensitivity){
            topRow.addView(sensitivityView,spacingParams);
        }

        View focusDistanceView = makeParameterLayout("Focus Distance:",exp.getFocusDistanceString());
        if (mOptions.showFocusDistance){
            topRow.addView(focusDistanceView,spacingParams);
        }

        View focalLengthView = makeParameterLayout("Focal Length:",exp.getFocalLengthString());
        if (mOptions.showFocalLength){
            bottomRow.addView(focalLengthView,spacingParams);
        }

        if (topRow.getChildCount()>0){
            sublayout.addView(topRow,rowParams);
        }

        if (bottomRow.getChildCount()>0){
            sublayout.addView(bottomRow,rowParams);
        }


        exposureView.addView(sublayout, rowParams);
        return exposureView;
    }



    /* LinearLayout makeParameterLayout(String paramName, String paramValue)
     *
     * Creates a Layout View which contains correctly centered and stylized exposure parameter
     * view from the parameter name and value.
     *
     */
    LinearLayout makeParameterLayout(String paramName, String paramValue){
        LinearLayout output = new LinearLayout(mContext);
        output.setOrientation(LinearLayout.VERTICAL);
        output.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView nameTV = new TextView(mContext);
        nameTV.setText(paramName);
        nameTV.setTypeface(nameTV.getTypeface(), 1); //set label bold

        TextView valueTV = new TextView(mContext);
        valueTV.setText(paramValue);

        LinearLayout.LayoutParams centeringParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,LinearLayout.LayoutParams.WRAP_CONTENT,1);
        centeringParams.gravity = Gravity.CENTER_HORIZONTAL;
        output.addView(nameTV,centeringParams);
        output.addView(valueTV,centeringParams);
        return output;
    }


    // class just for easy grouping of the display options, rather than passing 5 booleans back and
    // forth.
    static class DisplayOptionBundle{
        public boolean showExposureTime;
        public boolean showSensitivity;
        public boolean showAperture;
        public boolean showFocusDistance;
        public boolean showFocalLength;
    }
}
