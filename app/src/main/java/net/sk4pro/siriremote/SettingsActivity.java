package net.sk4pro.siriremote;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** TV settings screen: shows the bridge's state and sends BridgeService the same extras as the adb commands. */
public class SettingsActivity extends Activity {
    final Handler h = new Handler(Looper.getMainLooper());
    TextView status;

    final Runnable tick = new Runnable() {
        @Override public void run() {
            String mac = getSharedPreferences("bridge", MODE_PRIVATE).getString("mac", null);
            status.setText(BridgeService.state + "\n" + (mac == null ? "No remote paired" : "Remote " + mac));
            h.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(96, 64, 96, 64);
        TextView title = new TextView(this);
        title.setText("Siri Remote");
        title.setTextSize(32);
        root.addView(title);
        status = new TextView(this);
        status.setTextSize(20);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, 24, 0, 32);
        root.addView(status);
        button(root, "Pair a remote", "pair");
        button(root, "Reconnect", null);
        button(root, "Disconnect", "stop");
        button(root, "Forget remote", "forget");
        setContentView(root);
    }

    void button(LinearLayout root, String label, String extra) {
        Button bt = new Button(this);
        bt.setText(label);
        bt.setOnClickListener(v -> {
            Intent i = new Intent(this, BridgeService.class);
            if (extra != null) i.putExtra(extra, true);
            startForegroundService(i);
        });
        root.addView(bt, new LinearLayout.LayoutParams(600, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    @Override protected void onResume() { super.onResume(); h.post(tick); }
    @Override protected void onPause() { super.onPause(); h.removeCallbacks(tick); }
}
