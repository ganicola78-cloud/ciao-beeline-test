package com.example.ciaobeeline.wear;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;
import java.nio.charset.StandardCharsets;

public class NavListenerService extends WearableListenerService {
    @Override public void onMessageReceived(MessageEvent event) {
        if ("/nav_update".equals(event.getPath())) {
            String msg = new String(event.getData(), StandardCharsets.UTF_8);
            MainActivity.updateFromJson(msg);
        }
    }
}
