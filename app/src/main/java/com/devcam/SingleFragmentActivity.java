/* Standard practice for Android apps seems to be to refrain from using an Activity directly as the
 * main layout and to instead use it to host a single Fragment. Here we keep up this practice,
 * perhaps it will pay off in making things more adjustable in the future.
 */

package com.devcam;

import android.app.ActionBar;
import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager;
import android.os.Bundle;


public abstract class SingleFragmentActivity extends Activity {
    protected abstract Fragment createFragment();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Hide the action bar so the fragment gets the full screen
        ActionBar ab = getActionBar();
        ab.hide();
        
        setContentView(R.layout.fragment_holder);
		
        FragmentManager manager = getFragmentManager();
        Fragment fragment = manager.findFragmentById(R.id.fragment_container);

        if (fragment == null) {
            fragment = createFragment();
            manager.beginTransaction()
                .add(R.id.fragment_container, fragment)
                .commit();
        }
    }
}