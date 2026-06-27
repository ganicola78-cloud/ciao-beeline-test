package com.example.ciaobeeline.wear;

import android.app.Activity;
import android.os.Bundle;
import android.view.WindowManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

public class MainActivity extends Activity {
    private NavView navView;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (NavListenerService.ACTION_NAV_UPDATE.equals(intent.getAction())) {
                String json = intent.getStringExtra(NavListenerService.EXTRA_JSON);
                if (json != null && navView != null) {
                    navView.update(json);
                }
            }
        }
    };

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        navView = new NavView(this);
        setContentView(navView);
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(receiver, new IntentFilter(NavListenerService.ACTION_NAV_UPDATE));
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(receiver);
    }
}
