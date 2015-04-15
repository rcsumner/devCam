/* Activity for choosing a Template to generate a CaptureDesign from.
 *
 * The user chooses a Template from a list, and then inputs the appropriate parameters.
 * These parameters typically involve two endpoints of a range of values to iterate between, and the
 * number of exposures to split this range iteration into.
 *
 * Some Templates, such as the "split exposure time" template, may not use the range fields.
 *
 * This Activity does not actually generate the CaptureDesign from the template, but simply sends
 * the selected parameters back to the main Activity, which calls the appropriate
 * CaptureDesign.Creator method.
 */

package com.devcam;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;

public class GenerateDesignFromTemplateActivity extends Activity {

    // These tags are used to indicate the nature of the data returned by this Activity to the
    // main Activity via the Intent.putExtra() method.
    public enum DataTags {TEMPLATE_ID, LOW_BOUND, HIGH_BOUND, N_EXP}

    // This enumeration is for the possible Template types, for things that need to use switch
    // statements based on the Template. Unfortunately, since a number of Android-specific things
    // such as the AdapterView onItemClick() method and Intent.putExtra() only work with ints, there
    // still needs to be a value associated with each enum, which is used as an index into an array
    // in some places.
    public enum DesignTemplate {
        SPLIT_TIME(0),
        RACK_FOCUS(1),
        BRACKET_EXPOSURE_TIME_RELATIVE(2),
        BRACKET_EXPOSURE_TIME_ABSOLUTE(3),
        BRACKET_ISO_RELATIVE(4),
        BRACKET_ISO_ABSOLUTE(5);

        private final int index;

        // Constructor for initialized value
        private DesignTemplate(int ind){this.index = ind;}

        public int getIndex(){return index;}

        // Static function for finding the Template enum by its int value
        public static DesignTemplate getTemplateByIndex(int ind){
            DesignTemplate output = null;
            for (DesignTemplate t: DesignTemplate.values()){
                if (t.getIndex()==ind){
                    output = t;
                }
            }
            return output;
        }

        @Override
        public String toString(){
            String output;
            switch (this){
                case SPLIT_TIME:
                    output = "Split Exposure Time Evenly";
                    break;
                case RACK_FOCUS:
                    output = "Rack Focus";
                    break;
                case BRACKET_EXPOSURE_TIME_ABSOLUTE:
                    output = "Bracket Exposure Time, Absolute";
                    break;
                case BRACKET_EXPOSURE_TIME_RELATIVE:
                    output = "Bracket Exposure Time around Auto-value";
                    break;
                case BRACKET_ISO_ABSOLUTE:
                    output = "Bracket ISO, absolute";
                    break;
                case BRACKET_ISO_RELATIVE:
                    output = "Bracket ISO around Auto-value";
                    break;
                default:
                    output = super.toString();
            }
            return output;
        }
    }

    // Used to make a list of the above Templates, for an ArrayAdapter for a ListView
    List<DesignTemplate> mDesignTemplateList = new ArrayList<DesignTemplate>();

    CameraCharacteristics mCamChars;    // used for device capability bound display

    // Various View related things
    ListView mTemplateListView;
    ArrayAdapter<String> mTemplateListViewAdapter;
    TextView mUnitsTextView;
    TextView mDeviceBoundTextView;
    Button mGenerateDesignButton;
    EditText mNexpEditText;
    EditText mLowEditText;
    EditText mHighEditText;
    LinearLayout mParamsLayout;

    // These variables capture the state of the user input in this Activity. These are what get
    // returned to the main Activity upon pressing the "Generate Capture Design" button.
    Float mSelectedHighBound = 0.0f;
    Float mSelectedLowBound = 0.0f;
    Integer mSelectedNexp = 0;
    int mSelectedTemplateInd = 0;


    @Override
    public void onCreate(Bundle savedInstanceState){
        super.onCreate(savedInstanceState);
        Log.v(appFragment.APP_TAG,"GenerateDesignFromTemplate Activity Created.");

        // Hide the action bar so the activity gets the full screen
        getActionBar().hide();

        // Set the correct layout for this Activity
        setContentView(R.layout.design_template_selector);

        mDeviceBoundTextView = (TextView) findViewById(R.id.deviceBoundTextView);
        mUnitsTextView = (TextView) findViewById(R.id.unitsTextView);

        // Set the Array Adapter to the list of options
        mTemplateListView = (ListView) findViewById(R.id.DesignTemplateListView);
        mTemplateListViewAdapter=new ArrayAdapter<String>(this,android.R.layout.simple_list_item_1);
        List<String> stringList= new ArrayList<String>();
        for (DesignTemplate t : DesignTemplate.values()){
            stringList.add(t.toString());
            mDesignTemplateList.add(t);
        }


        // Set up the List View of the different Template types, as well an object that responds
        // when one of them is selected
        mTemplateListViewAdapter.addAll(stringList);
        mTemplateListView.setAdapter(mTemplateListViewAdapter);
        mTemplateListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                // When an item from the list has been selected, change the units in the display
                // appropriately and record which DesignTemplate was selected.
                Log.v(appFragment.APP_TAG,"Clicked list position : " + position);
                parent.setSelection(position); //highlight which was selected

                // Now that template has been selected, let user put in params
                mParamsLayout.setAlpha(1.0f);
                mHighEditText.setEnabled(true);
                mLowEditText.setEnabled(true);
                mNexpEditText.setEnabled(true);
                mDeviceBoundTextView.setText("");

                // Also, since there could have been values put in before from a previously selected
                // Template, make sure they are all reset
                mSelectedNexp = null;
                mSelectedLowBound = null;
                mSelectedHighBound = null;
                mHighEditText.setText("");
                mLowEditText.setText("");
                mNexpEditText.setText("");

                // Turn off ability to generate template, since no params set yet
                mGenerateDesignButton.setAlpha(0.5f);
                mGenerateDesignButton.setClickable(false);

                // Now switch the rest of the views depending on the Template selected
                mSelectedTemplateInd = position;
                switch (mDesignTemplateList.get(position)) {
                    case SPLIT_TIME:
                        mUnitsTextView.setText("N/A");
                        // split time template has no need for bounds, so set them unclickable so as
                        // not to tempt the user. But still set values, so they aren't null.
                        mLowEditText.setEnabled(false);
                        mHighEditText.setEnabled(false);
                        mSelectedLowBound = 0.0f;
                        mSelectedHighBound = 0.0f;
                        break;
                    case RACK_FOCUS:
                        mUnitsTextView.setText("m");
                        // Set the device's actual bounds visible
                        if (mCamChars!=null){
                            Float minFoc = mCamChars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
                            if (minFoc>0) {
                                String bounds = "Device bounds:\n[" +
                                        CameraReport.diopterToMeters(mCamChars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE))
                                        +" "+ DecimalFormatSymbols.getInstance().getInfinity() + "]";
                                mDeviceBoundTextView.setText(bounds);
                            }
                        }
                        break;
                    case BRACKET_EXPOSURE_TIME_ABSOLUTE:
                        mUnitsTextView.setText("ns");
                        // Set the device's actual bounds visible
                        if (mCamChars!=null){
                            String bounds = "Device bounds:\n" +
                                    mCamChars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE).toString();
                            mDeviceBoundTextView.setText(bounds);
                        }
                        break;
                    case BRACKET_ISO_ABSOLUTE:
                        mUnitsTextView.setText("ISO");
                        // Set the device's actual bounds visible
                        if (mCamChars!=null){
                            String bounds = "Device bounds:\n" +
                                    mCamChars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE).toString();
                            mDeviceBoundTextView.setText(bounds);
                        }
                        break;
                    case BRACKET_EXPOSURE_TIME_RELATIVE:
                    case BRACKET_ISO_RELATIVE:
                        mUnitsTextView.setText("stops around auto");
                        break;
                    default:
                        mUnitsTextView.setText("?");
                        // We shouldn't have reached this state, but if we do, make unclickable
                        // fields again
                        mParamsLayout.setAlpha(0.5f);
                        mParamsLayout.setClickable(false);
                }
            }
        });


        // Set up the EditText for the Number of Exposures field
        mNexpEditText = (EditText) findViewById(R.id.nExpEditText);
        mNexpEditText.setRawInputType(Configuration.KEYBOARD_12KEY);
        mNexpEditText.setOnEditorActionListener(new TextView.OnEditorActionListener() {

            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    String s = v.getText().toString();
                    try {
                        mSelectedNexp = Integer.parseInt(s);
                        // Don't let the user say 0 exposures, that is just dumb :-p
                        if (mSelectedNexp == 0) {
                            throw new NumberFormatException();
                        }
                    } catch (NumberFormatException nfe) {
                        Toast.makeText(GenerateDesignFromTemplateActivity.this,
                                "Please enter valid integer number of exposures.",
                                Toast.LENGTH_SHORT).show();
                        mSelectedNexp = null;
                        mNexpEditText.setText("");
                    }
                }
                Log.v(appFragment.APP_TAG, "nExp: " + mSelectedNexp);
                checkIfReadyToGenerate();
                return false;
            }
        });
        //set unclickable until layout chosen, but first save key listener so it can be remade clickable
        mNexpEditText.setEnabled(false);


        // Set up the EditText for the Low Bound field
        mLowEditText = (EditText) findViewById(R.id.lowEditText);
        mLowEditText.setRawInputType(Configuration.KEYBOARD_12KEY);
        mLowEditText.setOnEditorActionListener(new TextView.OnEditorActionListener() {

            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId== EditorInfo.IME_ACTION_DONE){
                    String s = v.getText().toString();
                    try{
                        mSelectedLowBound = Float.parseFloat(s);
                    } catch (NumberFormatException nfe){
                        Toast.makeText(GenerateDesignFromTemplateActivity.this,
                                "Please enter valid real number for bound.",
                                Toast.LENGTH_SHORT).show();
                        mSelectedLowBound = null;
                        mLowEditText.setText("");
                    }
                }
                Log.v(appFragment.APP_TAG,"Low bound: " + mSelectedLowBound);
                checkIfReadyToGenerate();
                return false;
            }
        });
        //set unclickable until layout chosen, but first save key listener so it can be remade clickable
        mLowEditText.setEnabled(false);


        // Set up the EditText for the High Bound field
        mHighEditText = (EditText) findViewById(R.id.highEditText);
        mHighEditText.setRawInputType(Configuration.KEYBOARD_12KEY);
        mHighEditText.setOnEditorActionListener(new TextView.OnEditorActionListener() {

            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId== EditorInfo.IME_ACTION_DONE){
                    String s = v.getText().toString();
                    try{
                        mSelectedHighBound = Float.parseFloat(s);
                    } catch (NumberFormatException nfe){
                        Toast.makeText(GenerateDesignFromTemplateActivity.this,
                                "Please enter valid real number for bound.",
                                Toast.LENGTH_SHORT).show();
                        mSelectedHighBound = null;
                        mHighEditText.setText("");
                    }
                }
                Log.v(appFragment.APP_TAG,"High bound: " + mSelectedHighBound);
                checkIfReadyToGenerate();
                return false;
            }
        });
        //set unclickable until layout chosen, but first save key listener so it can be remade clickable
        mHighEditText.setEnabled(false);


        // Set up the "Generate Capture Design" button which ends this Activity and returns the
        // input data to the main Activity.
        mGenerateDesignButton = (Button) findViewById(R.id.generateDesignButton);
        mGenerateDesignButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                // Make sure bounds are in the correct order, just in case
                if (mSelectedHighBound<mSelectedLowBound){
                    Float temp = mSelectedHighBound;
                    mSelectedHighBound = mSelectedLowBound;
                    mSelectedLowBound = temp;
                }

                Intent resultIntent = new Intent();
                resultIntent.putExtra(DataTags.TEMPLATE_ID.toString(),mSelectedTemplateInd);
                resultIntent.putExtra(DataTags.LOW_BOUND.toString(),mSelectedLowBound);
                resultIntent.putExtra(DataTags.HIGH_BOUND.toString(),mSelectedHighBound);
                resultIntent.putExtra(DataTags.N_EXP.toString(),mSelectedNexp);
                setResult(Activity.RESULT_OK, resultIntent);
                finish();
            }
        });
        // Set unusable until correct data put in
        mGenerateDesignButton.setAlpha(0.5f);
        mGenerateDesignButton.setClickable(false);


        // Make the whole yellow area "darked out" until a Template is selected
        mParamsLayout = (LinearLayout) findViewById(R.id.designParamsLayout);
        mParamsLayout.setAlpha(0.5f);

        // Get the camera metadata to use in case the user wants to do a Template based on absolute
        // values, so that they know the actual device capability bounds
        CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            String[] deviceList = cm.getCameraIdList();
            for (int i = 0; i < deviceList.length; i++) {
                mCamChars = cm.getCameraCharacteristics(deviceList[i]);
                if (mCamChars.get(CameraCharacteristics.LENS_FACING)
                        == CameraMetadata.LENS_FACING_BACK) {
                    break;
                }
            }
        } catch (CameraAccessException cae){
            cae.printStackTrace();
        }
    }



    /* void checkIfReadyToGenerate()
     *
     * Check to see if a Template and all of its necessary parameters have been entered. If so,
     * allow the "Generate Capture Design" button to be pushed.
     *
     * Generally called whenever a new parameter value is input, to see if all necessary ones are
     * present now.
     */
    void checkIfReadyToGenerate(){
        if (mSelectedLowBound!=null && mSelectedHighBound!=null && mSelectedNexp != null){
            mGenerateDesignButton.setAlpha(1.0f);
            mGenerateDesignButton.setClickable(true);
        }
    }


}
