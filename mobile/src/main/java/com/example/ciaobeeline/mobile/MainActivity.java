package com.example.ciaobeeline.mobile;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.PowerManager;
import android.net.Uri;
import android.provider.Settings;
import android.view.WindowManager;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import org.json.JSONArray;
import org.json.JSONObject;
import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;

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
    private MapView routeMap;

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        Configuration.getInstance().load(getApplicationContext(), getSharedPreferences("osmdroid", MODE_PRIVATE));
        Configuration.getInstance().setUserAgentValue(getPackageName());

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

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

        Button preview = new Button(this);
        preview.setText("VEDI TRAGITTO SU MAPPA");
        root.addView(preview);

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

        routeMap = new MapView(this);
        routeMap.setTileSource(TileSourceFactory.MAPNIK);
        routeMap.setMultiTouchControls(true);
        routeMap.getController().setZoom(13.0);
        routeMap.getController().setCenter(new GeoPoint(41.9028, 12.4964));
        root.addView(routeMap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                1000
        ));

        setContentView(scroll);

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

        preview.setOnClickListener(v -> showRoutePreviewOnMap());
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

    private void showRoutePreviewOnMap() {
        savePrefs();

        String key = apiKeyEdit.getText().toString().trim();
        String destText = destinationEdit.getText().toString().trim();

        if (key.length() < 8) {
            status.setText("Inserisci API key OpenRouteService.");
            return;
        }

        if (destText.length() < 3) {
            status.setText("Inserisci una destinazione.");
            return;
        }

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            status.setText("Concedi il permesso posizione per vedere il tragitto.");
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 5);
            return;
        }

        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (lm == null) {
            status.setText("GPS non disponibile.");
            return;
        }

        Location last = bestLastLocation(lm);
        if (last != null) {
            fetchAndDrawPreview(key, destText, last);
            return;
        }

        status.setText("Cerco posizione GPS per anteprima...");
        try {
            LocationListener once = new LocationListener() {
                @Override public void onLocationChanged(Location location) {
                    try { lm.removeUpdates(this); } catch (Exception ignored) {}
                    fetchAndDrawPreview(key, destText, location);
                }

                @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
                @Override public void onProviderEnabled(String provider) {}
                @Override public void onProviderDisabled(String provider) {}
            };

            try { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, once); } catch (Exception ignored) {}
            try { lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0, 0, once); } catch (Exception ignored) {}
        } catch (SecurityException e) {
            status.setText("Permesso posizione mancante.");
        }
    }

    private Location bestLastLocation(LocationManager lm) {
        try {
            Location gps = null;
            Location net = null;

            try { gps = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER); } catch (Exception ignored) {}
            try { net = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER); } catch (Exception ignored) {}

            if (gps == null) return net;
            if (net == null) return gps;
            return gps.getTime() >= net.getTime() ? gps : net;
        } catch (SecurityException e) {
            return null;
        }
    }

    private void fetchAndDrawPreview(String key, String destText, Location startLocation) {
        status.setText("Calcolo anteprima percorso...");

        new Thread(() -> {
            try {
                LatLon dest = previewGeocodeDestination(key, destText);
                PreviewRoute previewRoute = previewRequestDirections(key, startLocation, dest);

                runOnUiThread(() -> drawPreviewRoute(previewRoute, startLocation, dest));
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("Errore anteprima: " + e.getMessage()));
            }
        }).start();
    }

    private LatLon previewGeocodeDestination(String key, String destinationText) throws Exception {
        String encoded = URLEncoder.encode(destinationText, "UTF-8");
        URL url = new URL("https://api.openrouteservice.org/geocode/search?api_key=" + key + "&text=" + encoded + "&size=1");

        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod("GET");
        c.setRequestProperty("Accept", "application/json");

        InputStream is = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
        String txt = readAll(is);

        if (c.getResponseCode() >= 400) throw new RuntimeException("Geocoding: " + txt);

        JSONObject json = new JSONObject(txt);
        JSONArray features = json.getJSONArray("features");
        if (features.length() == 0) throw new RuntimeException("Destinazione non trovata.");

        JSONArray coords = features.getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates");
        return new LatLon(coords.getDouble(1), coords.getDouble(0));
    }

    private PreviewRoute previewRequestDirections(String key, Location startLocation, LatLon dest) throws Exception {
        URL url = new URL("https://api.openrouteservice.org/v2/directions/driving-car/geojson");

        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Authorization", key);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");

        String preference = "shortest".equals(routeMode) ? "shortest" : "fastest";

        String body;
        if (allowFastRoads) {
            body = "{\"coordinates\":[[" +
                    startLocation.getLongitude() + "," + startLocation.getLatitude() + "],[" +
                    dest.lon + "," + dest.lat + "]]," +
                    "\"preference\":\"" + preference + "\"}";
        } else {
            body = "{\"coordinates\":[[" +
                    startLocation.getLongitude() + "," + startLocation.getLatitude() + "],[" +
                    dest.lon + "," + dest.lat + "]]," +
                    "\"preference\":\"" + preference + "\"," +
                    "\"options\":{\"avoid_features\":[\"highways\",\"tollways\"]}}";
        }

        try (OutputStream os = c.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }

        InputStream is = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
        String txt = readAll(is);

        if (c.getResponseCode() >= 400) throw new RuntimeException(txt);

        JSONObject json = new JSONObject(txt);
        JSONObject feature = json.getJSONArray("features").getJSONObject(0);
        JSONArray coords = feature.getJSONObject("geometry").getJSONArray("coordinates");

        ArrayList<GeoPoint> points = new ArrayList<>();
        for (int i = 0; i < coords.length(); i++) {
            JSONArray p = coords.getJSONArray(i);
            points.add(new GeoPoint(p.getDouble(1), p.getDouble(0)));
        }

        JSONObject summary = feature.getJSONObject("properties").optJSONObject("summary");
        double distance = summary != null ? summary.optDouble("distance", 0) : 0;
        double duration = summary != null ? summary.optDouble("duration", 0) : 0;

        return new PreviewRoute(points, distance, duration);
    }

    private void drawPreviewRoute(PreviewRoute previewRoute, Location startLocation, LatLon dest) {
        if (previewRoute.points.isEmpty()) {
            status.setText("Nessun punto percorso trovato.");
            return;
        }

        routeMap.getOverlays().clear();

        Polyline line = new Polyline();
        line.setPoints(previewRoute.points);
        line.setWidth(8f);
        line.setColor(0xff1976d2);
        routeMap.getOverlays().add(line);

        Marker startMarker = new Marker(routeMap);
        startMarker.setPosition(new GeoPoint(startLocation.getLatitude(), startLocation.getLongitude()));
        startMarker.setTitle("Partenza");
        routeMap.getOverlays().add(startMarker);

        Marker endMarker = new Marker(routeMap);
        endMarker.setPosition(new GeoPoint(dest.lat, dest.lon));
        endMarker.setTitle("Arrivo");
        routeMap.getOverlays().add(endMarker);

        zoomMapTo(previewRoute.points);
        routeMap.invalidate();

        String modeText = "shortest".equals(routeMode) ? "più breve" : "più veloce";
        String roadText = allowFastRoads ? "con autostrade/superstrade" : "senza autostrade/superstrade";
        status.setText("Anteprima: " + modeText + " " + roadText + " - " +
                formatPreviewDistance(previewRoute.distanceMeters) + " - " +
                formatPreviewDuration(previewRoute.durationSeconds));
    }

    private void zoomMapTo(ArrayList<GeoPoint> points) {
        double north = -90;
        double south = 90;
        double east = -180;
        double west = 180;

        for (GeoPoint p : points) {
            north = Math.max(north, p.getLatitude());
            south = Math.min(south, p.getLatitude());
            east = Math.max(east, p.getLongitude());
            west = Math.min(west, p.getLongitude());
        }

        try {
            BoundingBox box = new BoundingBox(north, east, south, west);
            routeMap.zoomToBoundingBox(box, true, 80);
        } catch (Exception e) {
            GeoPoint center = points.get(points.size() / 2);
            routeMap.getController().setCenter(center);
            routeMap.getController().setZoom(14.0);
        }
    }

    private String formatPreviewDistance(double meters) {
        if (meters < 1000) return Math.round(meters) + " m";
        return String.format(java.util.Locale.US, "%.1f km", meters / 1000.0);
    }

    private String formatPreviewDuration(double seconds) {
        int minutes = Math.max(1, (int) Math.round(seconds / 60.0));
        if (minutes < 60) return minutes + " min";
        int h = minutes / 60;
        int m = minutes % 60;
        return h + " h " + m + " min";
    }

    private String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        return sb.toString();
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

    @Override
    protected void onResume() {
        super.onResume();
        if (routeMap != null) routeMap.onResume();
    }

    @Override
    protected void onPause() {
        if (routeMap != null) routeMap.onPause();
        super.onPause();
    }

    private static class LatLon {
        final double lat;
        final double lon;

        LatLon(double lat, double lon) {
            this.lat = lat;
            this.lon = lon;
        }
    }

    private static class PreviewRoute {
        final ArrayList<GeoPoint> points;
        final double distanceMeters;
        final double durationSeconds;

        PreviewRoute(ArrayList<GeoPoint> points, double distanceMeters, double durationSeconds) {
            this.points = points;
            this.distanceMeters = distanceMeters;
            this.durationSeconds = durationSeconds;
        }
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
