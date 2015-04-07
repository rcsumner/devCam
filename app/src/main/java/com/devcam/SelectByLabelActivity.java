/* Activity class for selecting an element of an array based on labels of the
 * elements. This activity is passed a string array via an Intent from the main
 * activity, and displays a list of these strings. The user selects an element
 * from the list and the activity quits and returns the index of the selected
 * element to the main activity. 
 */

package com.devcam;

import android.app.Activity;
import android.app.ListActivity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListView;

public class SelectByLabelActivity extends ListActivity {

	public final static String TAG_SELECTED_INDEX = "SELECTED_INDEX";
	public final static String TAG_DATA_LABELS = "TAG_DATA_LABELS";
	//public final static String TAG_DESCRIPTIONS = "DESCRIPTION_TAG"; // for the future

	@Override
	public void onCreate(Bundle savedInstanceState){
		super.onCreate(savedInstanceState);
		
        // Hide the action bar so the fragment gets the full screen
        getActionBar().hide();

		// Get the input values from the source activity
		String[] labels = getIntent().getStringArrayExtra(TAG_DATA_LABELS);
		//String[] descriptions = getIntent().getStringArrayExtra(TAG_DESCRIPTIONS); //for the future
		ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
				android.R.layout.simple_list_item_1, labels);
		setListAdapter(adapter);
	}

	@Override
	public void onListItemClick(ListView l, View v, int position, long id) { 
		Intent resultIntent = new Intent();
		resultIntent.putExtra(SelectByLabelActivity.TAG_SELECTED_INDEX, position);
		setResult(Activity.RESULT_OK, resultIntent);
		finish();
	}
}