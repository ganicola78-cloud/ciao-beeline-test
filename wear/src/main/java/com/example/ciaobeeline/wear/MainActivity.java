package com.example.ciaobeeline.wear;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;

public class MainActivity extends Activity {
    static NavView navView;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int demoIndex = 0;

    private final String[] demos = new String[] {
            "{\"mode\":\"NAV\",\"speed\":34,\"dist\":180,\"turn\":\"RIGHT\",\"limit\":50,\"line\":\"120,140;120,120;121,103;132,91;150,83;163,67;168,43\"}",
            "{\"mode\":\"NAV\",\"speed\":28,\"dist\":70,\"turn\":\"LEFT\",\"limit\":30,\"line\":\"120,140;120,123;118,108;108,96;89,91;72,80;65,58\"}",
            "{\"mode\":\"NAV\",\"speed\":32,\"dist\":260,\"turn\":\"ROUND\",\"exit\":3,\"limit\":50,\"line\":\"120,140;120,122;124,107;138,96;153,91;165,78;165,58\"}",
            "{\"mode\":\"REROUTE\",\"speed\":20,\"dist\":420,\"turn\":\"STRAIGHT\",\"line\":\"120,140;119,121;115,105;108,90;104,73;109,53\"}",
            "{\"mode\":\"OFF_ROUTE\",\"speed\":12,\"dist\":90,\"turn\":\"LEFT\",\"line\":\"120,140;123,121;132,105;148,95;164,80;171,61\"}"
    };

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        navView = new NavView(this);
        setContentView(navView);

        // Long-press = local visual demo on the Carlyle.
        // Normal navigation from the phone still arrives on /nav_update.
        navView.setOnLongClickListener(v -> {
            navView.update(demos[demoIndex]);
            demoIndex = (demoIndex + 1) % demos.length;
            return true;
        });
    }

    public static void updateFromJson(String json) {
        if (navView != null) navView.update(json);
    }
}
