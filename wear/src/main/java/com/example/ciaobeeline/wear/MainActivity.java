package com.example.ciaobeeline.wear;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;

import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

public class MainActivity extends Activity {
    private static final String PATH = "/nav_update";

    private NavView navView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String lastJson = "";

    private final Runnable pollingRunnable = new Runnable() {
        @Override
        public void run() {
            loadLastDataLayerValue();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        navView = new NavView(this);
        setContentView(navView);

        // Demo locale silenziosa toccando lo schermo.
        navView.setOnClickListener(v -> {
            String demoJson = "{\"mode\":\"NAV\",\"speed\":36,\"dist\":300,\"turn\":\"RIGHT\",\"line\":\"120,200;120,165;145,140;145,95;105,60\"}";
            lastJson = demoJson;
            navView.update(demoJson);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(pollingRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(pollingRunnable);
    }

    private void loadLastDataLayerValue() {
        Wearable.getDataClient(this).getDataItems()
                .addOnSuccessListener(items -> {
                    String foundJson = null;
                    for (DataItem item : items) {
                        if (PATH.equals(item.getUri().getPath())) {
                            foundJson = DataMapItem.fromDataItem(item).getDataMap().getString("json");
                        }
                    }
                    items.release();

                    if (foundJson != null && !foundJson.equals(lastJson)) {
                        lastJson = foundJson;
                        navView.update(foundJson);
                    }
                });
    }
                        }
