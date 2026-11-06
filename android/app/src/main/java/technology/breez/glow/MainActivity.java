package technology.breez.glow;

import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        lockPortraitOnPhones(getResources().getConfiguration());
        super.onCreate(savedInstanceState);
    }

    // configChanges keeps the activity alive across a fold or unfold, so
    // the screen can change size class without onCreate running again.
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        lockPortraitOnPhones(newConfig);
    }

    // Portrait on phones only. The lock lives here, not in the manifest:
    // Android 16 ignores an orientation lock on large screens for apps
    // targeting API 36, and Play flags a manifest lock for that reason.
    // 600dp is the platform's own large-screen line. Smallest width does
    // not change on rotation, so the result cannot flip back and forth.
    //
    // ponytail: reads the window, not the display. In split screen on
    // Android 15 and older, a tablet half narrower than 600dp stays
    // portrait, as the whole app did before. Switch to the display's
    // maximum window metrics if that matters.
    private void lockPortraitOnPhones(Configuration config) {
        int orientation = config.smallestScreenWidthDp < 600
            ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        setRequestedOrientation(orientation);
    }
}
