package com.example.ciaobeeline.wear;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.WindowManager;

import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

public class MainActivity extends Activity {
    private static final String PATH = "/nav_update";

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

        loadLastDataLayerValue();
    }

    @Override
    protected void onPause() {
        super.onPause();

        try {
            unregisterReceiver(receiver);
        } catch (Exception ignored) {
        }
    }

    private void loadLastDataLayerValue() {
        Wearable.getDataClient(this).getDataItems()
                .addOnSuccessListener(items -> {
                    for (DataItem item : items) {
                        if (PATH.equals(item.getUri().getPath())) {
                            String json = DataMapItem.fromDataItem(item)
                                    .getDataMap()
                                    .getString(NavListenerService.EXTRA_JSON);

                            if (json != null && navView != null) {
                                navView.update(json);
                            }
                        }
                    }

                    items.release();
                });
    }
}
