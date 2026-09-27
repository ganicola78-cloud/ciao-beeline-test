package com.example.ciaobeeline.wear;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;

public class MainActivity extends Activity {
    static NavView navView;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int demoIndex = 0;
    private boolean receiverRegistered = false;

    private final String[] demos = new String[] {
            "{\"mode\":\"NAV\",\"speed\":34,\"dist\":180,\"turn\":\"RIGHT\",\"limit\":50,\"line\":\"120,140;120,120;121,103;132,91;150,83;163,67;168,43\"}",
            "{\"mode\":\"NAV\",\"speed\":28,\"dist\":70,\"turn\":\"LEFT\",\"limit\":30,\"line\":\"120,140;120,123;118,108;108,96;89,91;72,80;65,58\"}",
            "{\"mode\":\"NAV\",\"speed\":32,\"dist\":260,\"turn\":\"ROUND\",\"exit\":3,\"limit\":50,\"line\":\"120,140;120,122;124,107;138,96;153,91;165,78;165,58\"}",
            "{\"mode\":\"REROUTE\",\"speed\":20,\"dist\":420,\"turn\":\"STRAIGHT\",\"line\":\"120,140;119,121;115,105;108,90;104,73;109,53\"}",
            "{\"mode\":\"OFF_ROUTE\",\"speed\":12,\"dist\":90,\"turn\":\"LEFT\",\"line\":\"120,140;123,121;132,105;148,95;164,80;171,61\"}"
    };

    private final BroadcastReceiver navReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            if (!NavListenerService.ACTION_NAV_UPDATE.equals(intent.getAction())) return;

            String json = intent.getStringExtra(NavListenerService.EXTRA_JSON);
            if (json != null && navView != null) {
                navView.update(json);
            }
        }
    };

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        navView = new NavView(this);
        setContentView(navView);

        registerNavReceiver();

        // Pressione lunga = demo grafica locale sul Carlyle.
        // La navigazione reale dal telefono arriva tramite /nav_update.
        navView.setOnLongClickListener(v -> {
            navView.update(demos[demoIndex]);
            demoIndex = (demoIndex + 1) % demos.length;
            return true;
        });
    }

    private void registerNavReceiver() {
        if (receiverRegistered) return;

        IntentFilter filter = new IntentFilter(NavListenerService.ACTION_NAV_UPDATE);

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(navReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(navReceiver, filter);
        }

        receiverRegistered = true;
    }

    @Override
    protected void onDestroy() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(navReceiver);
            } catch (Exception ignored) {
            }
            receiverRegistered = false;
        }

        navView = null;
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    public static void updateFromJson(String json) {
        if (navView != null && json != null) {
            navView.update(json);
        }
    }
}
