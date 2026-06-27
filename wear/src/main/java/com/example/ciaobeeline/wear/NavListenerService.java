package com.example.ciaobeeline.wear;

import android.content.Intent;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;
import java.nio.charset.StandardCharsets;

public class NavListenerService extends WearableListenerService {
    public static final String ACTION_NAV_UPDATE = "com.example.ciaobeeline.NAV_UPDATE";
    public static final String EXTRA_JSON = "json";

    @Override
    public void onMessageReceived(MessageEvent event) {
        if ("/nav_update".equals(event.getPath())) {
            String msg = new String(event.getData(), StandardCharsets.UTF_8);

            Intent intent = new Intent(ACTION_NAV_UPDATE);
            intent.setPackage(getPackageName());
            intent.putExtra(EXTRA_JSON, msg);
            sendBroadcast(intent);
        }
    }
}
