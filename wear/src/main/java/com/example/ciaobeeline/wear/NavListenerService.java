package com.example.ciaobeeline.wear;

import android.content.Intent;

import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;

import java.nio.charset.StandardCharsets;

public class NavListenerService extends WearableListenerService {
    public static final String ACTION_NAV_UPDATE = "com.example.ciaobeeline.NAV_UPDATE";
    public static final String EXTRA_JSON = "json";

    private static final String PATH = "/nav_update";

    @Override
    public void onMessageReceived(MessageEvent event) {
        if (PATH.equals(event.getPath())) {
            String msg = new String(event.getData(), StandardCharsets.UTF_8);
            broadcast(msg);
        }
    }

    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
        for (DataEvent event : dataEvents) {
            if (event.getType() == DataEvent.TYPE_CHANGED &&
                    PATH.equals(event.getDataItem().getUri().getPath())) {

                String msg = DataMapItem.fromDataItem(event.getDataItem())
                        .getDataMap()
                        .getString(EXTRA_JSON);

                if (msg != null) {
                    broadcast(msg);
                }
            }
        }
    }

    private void broadcast(String msg) {
        Intent intent = new Intent(ACTION_NAV_UPDATE);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_JSON, msg);
        sendBroadcast(intent);
    }
}
