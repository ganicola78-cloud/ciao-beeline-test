package com.example.ciaobeeline.mobile;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.PowerManager;
import android.net.Uri;
import android.provider.Settings;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final String PATH = "/nav_update";
    private static final String PREFS = "ciao_beeline_prefs";
    private static final String PREF_API_KEY = "ors_api_key";
    private static final String PREF_DESTINATION = "destination_text";
    private static final String PREF_ROUTE_MODE = "route_mode";
    private static final String PREF_ALLOW_FAST_ROADS = "allow_fast_roads";

    private EditText apiKeyEdit;
    private EditText destinationEdit;
    private TextView status;
    private Button fastestButton;
    private Button shortestButton;
    private Button fastRoadsButton;
    private String routeMode = "fastest";
    private boolean allowFastRoads = false;

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);

        TextView title = new TextView(this);
        title.setText("Ciao Beeline Test - Phone");
        title.setTextSize(22);
        root.addView(title);

        apiKeyEdit = new EditText(this);
        apiKeyEdit.setHint("OpenRouteService API key");
        root.addView(apiKeyEdit);

        destinationEdit = new EditText(this);
        destinationEdit.setHint("Destinazione, es. Via Roma, Cagliari");
        root.addView(destinationEdit);

        fastestButton = new Button(this);
        fastestButton.setText("TRAGITTO: PIÙ VELOCE");
        root.addView(fastestButton);

        shortestButton = new Button(this);
        shortestButton.setText("TRAGITTO: PIÙ BREVE");
        root.addView(shortestButton);

        fastRoadsButton = new Button(this);
        fastRoadsButton.setText("AUTOSTRADE / SUPERSTRADE");
        root.addView(fastRoadsButton);

        Button start = new Button(this);
        start.setText("START LIVE ROUTING");
        root.addView(start);

        Button stop = new Button(this);
        stop.setText("STOP");
        root.addView(stop);

        Button test = new Button(this);
        test.setText("INVIA DEMO AL CARLYLE");
        root.addView(test);

        Button battery = new Button(this);
        battery.setText("DISATTIVA RISPARMIO BATTERIA");
        root.addView(battery);

        status = new TextView(this);
        status.setText("Pronto. La navigazione continuerà anche a schermo spento con notifica attiva.");
        status.setTextSize(16);
        root.addView(status);

        setContentView(root);

        loadPrefs();
        updateRouteModeButtons();

        fastestButton.setOnClickListener(v -> {
            routeMode = "fastest";
            savePrefs();
            updateRouteModeButtons();
        });

        shortestButton.setOnClickListener(v -> {
            routeMode = "shortest";
            savePrefs();
            updateRouteModeButtons();
        });

        fastRoadsButton.setOnClickListener(v -> {
            allowFastRoads = !allowFastRoads;
            savePrefs();
            updateRouteModeButtons();
        });

        start.setOnClickListener(v -> startRoutingService());
        stop.setOnClickListener(v -> stopRoutingService());
        test.setOnClickListener(v -> sendDemo());
        battery.setOnClickListener(v -> openBatteryOptimizationSettings());

        requestNeededPermissions();
    }

    private void requestNeededPermissions() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 5);
            return;
        }

        if (android.os.Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 6);
        }
    }

    private void openBatteryOptimizationSettings() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);

            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
                status.setText("Conferma di non ottimizzare la batteria per Ciao Beeline.");
                return;
            }
        } catch (Exception ignored) {
        }

        try {
            Intent i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            startActivity(i);
            status.setText("Imposta Ciao Beeline su batteria senza restrizioni.");
        } catch (Exception e) {
            status.setText("Apri manualmente Impostazioni > App > Ciao Beeline > Batteria > Nessuna restrizione.");
        }
    }

    private void loadPrefs() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        apiKeyEdit.setText(p.getString(PREF_API_KEY, ""));
        destinationEdit.setText(p.getString(PREF_DESTINATION, ""));
        routeMode = p.getString(PREF_ROUTE_MODE, "fastest");
        allowFastRoads = p.getBoolean(PREF_ALLOW_FAST_ROADS, false);
    }

    private void savePrefs() {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(PREF_API_KEY, apiKeyEdit.getText().toString().trim())
                .putString(PREF_DESTINATION, destinationEdit.getText().toString().trim())
                .putString(PREF_ROUTE_MODE, routeMode)
                .putBoolean(PREF_ALLOW_FAST_ROADS, allowFastRoads)
                .apply();
    }

    private void updateRouteModeButtons() {
        String modeText;

        if ("shortest".equals(routeMode)) {
            fastestButton.setText("PIÙ VELOCE");
            shortestButton.setText("✓ PIÙ BREVE");
            modeText = "più breve";
        } else {
            routeMode = "fastest";
            fastestButton.setText("✓ PIÙ VELOCE");
            shortestButton.setText("PIÙ BREVE");
            modeText = "più veloce";
        }

        if (allowFastRoads) {
            fastRoadsButton.setText("✓ AUTOSTRADE / SUPERSTRADE: SÌ");
            status.setText("Modalità percorso: " + modeText + " con autostrade/superstrade consentite.");
        } else {
            fastRoadsButton.setText("AUTOSTRADE / SUPERSTRADE: NO");
            status.setText("Modalità percorso: " + modeText + " senza autostrade/superstrade.");
        }
    }

    private void startRoutingService() {
        savePrefs();

        if (apiKeyEdit.getText().toString().trim().length() < 8) {
            status.setText("Inserisci API key OpenRouteService.");
            return;
        }

        if (destinationEdit.getText().toString().trim().length() < 3) {
            status.setText("Inserisci una destinazione.");
            return;
        }

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            status.setText("Concedi il permesso posizione.");
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 5);
            return;
        }

        if (android.os.Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 6);
        }

        Intent i = new Intent(this, NavigationService.class);
        i.setAction(NavigationService.ACTION_START);

        if (android.os.Build.VERSION.SDK_INT >= 26) {
            startForegroundService(i);
        } else {
            startService(i);
        }

        status.setText("Navigazione avviata. Ora puoi spegnere lo schermo: deve restare la notifica Ciao Beeline.");
    }

    private void stopRoutingService() {
        Intent i = new Intent(this, NavigationService.class);
        i.setAction(NavigationService.ACTION_STOP);
        startService(i);

        status.setText("Navigazione fermata.");
    }

    private void sendDemo() {
        String demoJson = "{\"mode\":\"NAV\",\"seq\":999,\"recalculated\":false,\"speed\":36,\"dist\":300,\"turn\":\"RIGHT\",\"limit\":50,\"line\":\"120,140;120,110;145,92;145,60;105,40\"}";
        sendToWear(demoJson);
        status.setText("Demo inviata al Carlyle.");
    }

    private void sendToWear(String msg) {
        com.google.android.gms.wearable.PutDataMapRequest mapRequest =
                com.google.android.gms.wearable.PutDataMapRequest.create(PATH);
        mapRequest.getDataMap().putString("json", msg);
        mapRequest.getDataMap().putLong("ts", System.currentTimeMillis());
        com.google.android.gms.wearable.PutDataRequest request = mapRequest.asPutDataRequest().setUrgent();
        Wearable.getDataClient(this).putDataItem(request);

        Wearable.getNodeClient(this).getConnectedNodes().addOnSuccessListener(nodes -> {
            for (Node n : nodes) {
                Wearable.getMessageClient(this).sendMessage(n.getId(), PATH, msg.getBytes(StandardCharsets.UTF_8));
            }
        });
    }
                    }
