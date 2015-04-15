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
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import java.util.List;


public class ExposureArrayAdapter extends ArrayAdapter<Exposure> {

    Context mContext;
    CaptureDesign mDesign;

    // Constructor is non-standard for an ArrayAdapter, as the second argument is actually a
    // CaptureDesign, not a List<Exposure>. It does contain this list, however.
    public ExposureArrayAdapter(Context context, CaptureDesign design){
        super(context, R.layout.exposure_view_layout, design.getExposures());
        mContext = context;
        mDesign = design;
    }


    /* void registerNewCaptureDesign(CaptureDesign)
     *
     * Register a new CaptureDesign, which has both the new List of Exposures to display, but also
     * knowledge of the Intents so that they can be displayed with the correct coloring.
     *
     * This function also changes items in the list kept track of by the super class, but does not
     * tell the super class to update its View. That is done in the main Activity.
     */
    public void registerNewCaptureDesign(CaptureDesign design){
        mDesign = design;
        super.clear();
        super.addAll(mDesign.getExposures());
    }



    /*This is the main function to override to make sure each View in the list is displayed
     * exactly as we want.
     * If a value is explicit, color it black.
     * If a value is variable, color it blue.
     */
    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        List<Exposure> list = mDesign.getExposures(); // get the current list.

        // Inflate and get the Layout View we have designed.
        LayoutInflater inflater = (LayoutInflater) mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View exposureView = inflater.inflate(R.layout.exposure_view_layout, parent, false);

        // Display the item number on the left of the list item
        TextView positionTV = (TextView) exposureView.findViewById(R.id.positionTextView);
        positionTV.setText(String.valueOf(position+1)+"|");

        // Now set the Exposure parameter values accordingly
        TextView apertureTV = (TextView) exposureView.findViewById(R.id.EapertureTV);
        apertureTV.setText(list.get(position).getApertureString());
        if (list.get(position).hasVariableAperture()){
            apertureTV.setTextColor(Color.BLUE);
        }

        TextView exposureTimeTV = (TextView) exposureView.findViewById(R.id.EexpTimeTV);
        exposureTimeTV.setText(list.get(position).getExposureTimeString());
        if (list.get(position).hasVariableExposureTime()){
            exposureTimeTV.setTextColor(Color.BLUE);
        }

        TextView focalLengthTV = (TextView) exposureView.findViewById(R.id.EfocalLengthTV);
        focalLengthTV.setText(list.get(position).getFocalLengthString());
        if (list.get(position).hasVariableFocalLength()){
            focalLengthTV.setTextColor(Color.BLUE);
        }

        TextView focusDistanceTV = (TextView) exposureView.findViewById(R.id.EfocusDistanceTV);
        focusDistanceTV.setText(list.get(position).getFocusDistanceString());
        if (list.get(position).hasVariableFocusDistance()){
            focusDistanceTV.setTextColor(Color.BLUE);
        }

        TextView sensitivityTV = (TextView) exposureView.findViewById(R.id.EsensitivityTV);
        sensitivityTV.setText(list.get(position).getSensitivityString());
        if (list.get(position).hasVariableSensitivity()){
            sensitivityTV.setTextColor(Color.BLUE);
        }

        return exposureView;
    }
}
