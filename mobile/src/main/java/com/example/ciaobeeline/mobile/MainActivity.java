package com.example.ciaobeeline.mobile;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Point;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.BitmapDrawable;
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
import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase;
import org.osmdroid.tileprovider.tilesource.TileSourcePolicy;
import org.osmdroid.tileprovider.tilesource.XYTileSource;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;

public class MainActivity extends Activity {
    private static final String PATH = "/nav_update";
    private static final String PREFS = "ciao_beeline_prefs";
    private static final String PREF_API_KEY = "ors_api_key";
    private static final String PREF_DESTINATION = "destination_text";
    private static final String PREF_ROUTE_MODE = "route_mode";
    private static final String PREF_ALLOW_FAST_ROADS = "allow_fast_roads";
    private static final String PREF_FAVORITES = "favorite_destinations_v1";
    private static final String PREF_SAVED_POINTS = "saved_map_points_v1";
    private static final String PREF_SAVED_ROUTES = "saved_routes_v1";
    private static final String PREF_NAV_SOURCE = "nav_source_v1";
    private static final String PREF_NAV_TARGET_LAT = "nav_target_lat_v1";
    private static final String PREF_NAV_TARGET_LON = "nav_target_lon_v1";
    private static final String PREF_NAV_TARGET_LABEL = "nav_target_label_v1";
    private static final String PREF_NAV_ROUTE_POINTS = "nav_route_points_v1";

    // Tile source OSM con User-Agent esplicito: evita il profilo MAPNIK di osmdroid
    // che forza il User-Agent normalizzato package/versione.
    private static final OnlineTileSourceBase CIAO_OSM = new XYTileSource(
            "CiaoBeelineOSM",
            0,
            19,
            256,
            ".png",
            new String[]{"https://tile.openstreetmap.org/"},
            "© OpenStreetMap contributors",
            new TileSourcePolicy(
                    2,
                    TileSourcePolicy.FLAG_NO_BULK
                            | TileSourcePolicy.FLAG_NO_PREVENTIVE
                            | TileSourcePolicy.FLAG_USER_AGENT_MEANINGFUL
            )
    );

    private EditText apiKeyEdit;
    private TextView apiKeySavedLabel;
    private Button apiKeyButton;
    private EditText destinationEdit;
    private TextView status;
    private Button fastestButton;
    private Button shortestButton;
    private Button fastRoadsButton;
    private String routeMode = "fastest";
    private boolean allowFastRoads = false;
    private MapView routeMap;
    private MapEventsOverlay mapEventsOverlay;
    private Marker currentLocationMarker;
    private final ArrayList<GeoPoint> displayedRoutePoints = new ArrayList<>();
    private static final double PHONE_ROUTE_SNAP_METERS = 20.0;
    private LocationManager mapLocationManager;
    private Location lastMapLocation;
    private Location lastMapCourseLocation;
    private long lastMapGpsFixMs = 0;
    private float lastMapHeading = Float.NaN;
    private boolean mapCenteredOnGpsOnce = false;

    private final LocationListener mapLocationListener = location -> {
        if (!acceptMapLocation(location)) return;
        if (LocationManager.GPS_PROVIDER.equals(location.getProvider())) {
            lastMapGpsFixMs = System.currentTimeMillis();
        }
        lastMapLocation = location;
        updateCurrentLocationMarker(location);
    };
    private LinearLayout favoritesContainer;
    private LinearLayout savedPointsContainer;
    private LinearLayout savedRoutesContainer;
    private Button saveMapPointButton;
    private Button saveRouteButton;
    private int lastSelectedPointIndex = -1;
    private final ArrayList<GeoPoint> selectedRoutePoints = new ArrayList<>();
    private final ArrayList<Favorite> favorites = new ArrayList<>();
    private final ArrayList<SavedPoint> savedPoints = new ArrayList<>();
    private final ArrayList<SavedRoute> savedRoutes = new ArrayList<>();

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);

        Configuration.getInstance().load(getApplicationContext(), getSharedPreferences("osmdroid", MODE_PRIVATE));
        Configuration.getInstance().setUserAgentValue("CiaoBeeline/1.0 (Android; com.example.ciaobeeline.mobile)");

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

        LinearLayout apiKeyRow = new LinearLayout(this);
        apiKeyRow.setOrientation(LinearLayout.HORIZONTAL);

        apiKeyEdit = new EditText(this);
        apiKeyEdit.setHint("OpenRouteService API key");
        apiKeyEdit.setSingleLine(true);
        apiKeyEdit.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        apiKeyEdit.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
        apiKeyRow.addView(apiKeyEdit, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        apiKeyButton = new Button(this);
        apiKeyButton.setText("SALVA API");
        apiKeyRow.addView(apiKeyButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(apiKeyRow);

        apiKeySavedLabel = new TextView(this);
        apiKeySavedLabel.setText("✓ Chiave OpenRouteService salvata");
        apiKeySavedLabel.setTextSize(14);
        apiKeySavedLabel.setVisibility(android.view.View.GONE);
        root.addView(apiKeySavedLabel);

        LinearLayout destinationRow = new LinearLayout(this);
        destinationRow.setOrientation(LinearLayout.HORIZONTAL);

        destinationEdit = new EditText(this);
        destinationEdit.setHint("Destinazione, es. Via Roma, Cagliari");
        destinationRow.addView(destinationEdit, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button saveFavoriteButton = new Button(this);
        saveFavoriteButton.setText("★ SALVA");
        destinationRow.addView(saveFavoriteButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(destinationRow);

        TextView favoritesTitle = new TextView(this);
        favoritesTitle.setText("Preferiti");
        favoritesTitle.setTextSize(16);
        favoritesTitle.setPadding(0, 10, 0, 4);
        root.addView(favoritesTitle);

        favoritesContainer = new LinearLayout(this);
        favoritesContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(favoritesContainer);

        TextView savedPointsTitle = new TextView(this);
        savedPointsTitle.setText("Punti salvati");
        savedPointsTitle.setTextSize(16);
        savedPointsTitle.setPadding(0, 10, 0, 4);
        root.addView(savedPointsTitle);

        savedPointsContainer = new LinearLayout(this);
        savedPointsContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(savedPointsContainer);

        TextView savedRoutesTitle = new TextView(this);
        savedRoutesTitle.setText("Tragitti salvati");
        savedRoutesTitle.setTextSize(16);
        savedRoutesTitle.setPadding(0, 10, 0, 4);
        root.addView(savedRoutesTitle);

        savedRoutesContainer = new LinearLayout(this);
        savedRoutesContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(savedRoutesContainer);

        fastestButton = new Button(this);
        fastestButton.setText("TRAGITTO: PIÙ VELOCE");
        root.addView(fastestButton);

        shortestButton = new Button(this);
        shortestButton.setText("TRAGITTO: PIÙ BREVE");
        root.addView(shortestButton);

        fastRoadsButton = new Button(this);
        fastRoadsButton.setText("AUTOSTRADE / SUPERSTRADE");
        root.addView(fastRoadsButton);

        saveMapPointButton = new Button(this);
        saveMapPointButton.setText("SALVA ULTIMO PUNTO MAPPA");
        root.addView(saveMapPointButton);

        saveRouteButton = new Button(this);
        saveRouteButton.setText("SALVA TRAGITTO");
        root.addView(saveRouteButton);

        Button preview = new Button(this);
        preview.setText("VEDI TRAGITTO SU MAPPA");
        root.addView(preview);

        Button start = new Button(this);
        start.setText("START LIVE ROUTING");
        root.addView(start);

        Button reroute = new Button(this);
        reroute.setText("RICALCOLA ROTTA");
        root.addView(reroute);

        Button clearRoute = new Button(this);
        clearRoute.setText("CANCELLA PERCORSO");
        root.addView(clearRoute);

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
        routeMap.setTileSource(CIAO_OSM);
        mapLocationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        routeMap.setMultiTouchControls(true);

        // Quando il dito è sulla mappa, impedisce allo ScrollView
        // di intercettare trascinamento e pinch-to-zoom.
        routeMap.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                case android.view.MotionEvent.ACTION_POINTER_DOWN:
                case android.view.MotionEvent.ACTION_MOVE:
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    break;

                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    v.getParent().requestDisallowInterceptTouchEvent(false);
                    break;
            }
            return false;
        });

        // Pressione lunga sulla mappa:
        // 1° punto = partenza, ultimo = arrivo, quelli in mezzo = waypoint.
        mapEventsOverlay = new MapEventsOverlay(new MapEventsReceiver() {
            @Override
            public boolean singleTapConfirmedHelper(GeoPoint p) {
                return false;
            }

            @Override
            public boolean longPressHelper(GeoPoint p) {
                // Se la pressione lunga è sopra (o molto vicina a) un punto già scelto,
                // lo elimina. Altrimenti aggiunge un nuovo punto.
                int existingIndex = findSelectedRoutePointNear(p);
                if (existingIndex >= 0) {
                    removeSelectedRoutePoint(existingIndex);
                } else {
                    addSelectedRoutePoint(p);
                    if (selectedRoutePoints.size() == 1) {
                        showSelectedMapPointActions(0);
                    }
                }
                return true;
            }
        });
        routeMap.getOverlays().add(mapEventsOverlay);

        routeMap.getController().setZoom(13.0);
        routeMap.getController().setCenter(new GeoPoint(41.9028, 12.4964));
        root.addView(routeMap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                1000
        ));

        setContentView(scroll);

        loadPrefs();
        loadFavorites();
        loadSavedPoints();
        loadSavedRoutes();
        refreshFavoritesUi();
        refreshSavedPointsUi();
        refreshSavedRoutesUi();
        updateRouteModeButtons();
        updateApiKeyUi();

        apiKeyButton.setOnClickListener(v -> handleApiKeyButton());
        saveFavoriteButton.setOnClickListener(v -> showSaveFavoriteDialog());
        saveMapPointButton.setOnClickListener(v -> showSaveMapPointDialog());
        saveRouteButton.setOnClickListener(v -> showSaveRouteDialog());

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
        reroute.setOnClickListener(v -> requestManualReroute());
        clearRoute.setOnClickListener(v -> clearCurrentRoute());
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

        startMapLocationUpdates();

        if (android.os.Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 6);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 5 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startMapLocationUpdates();
        }
    }

    private void startMapLocationUpdates() {
        if (routeMap == null) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        if (mapLocationManager == null) mapLocationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (mapLocationManager == null) return;

        try { mapLocationManager.removeUpdates(mapLocationListener); } catch (Exception ignored) {}

        Location last = bestLastLocation(mapLocationManager);
        if (last != null) {
            lastMapLocation = last;
            updateCurrentLocationMarker(last);
        }

        try { mapLocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, mapLocationListener); } catch (Exception ignored) {}
        try { mapLocationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1500, 0, mapLocationListener); } catch (Exception ignored) {}
    }

    private void stopMapLocationUpdates() {
        if (mapLocationManager == null) return;
        try { mapLocationManager.removeUpdates(mapLocationListener); } catch (Exception ignored) {}
    }

    private boolean acceptMapLocation(Location location) {
        if (location == null) return false;
        long now = System.currentTimeMillis();
        if (LocationManager.NETWORK_PROVIDER.equals(location.getProvider())
                && lastMapGpsFixMs > 0 && now - lastMapGpsFixMs < 3000) {
            return false;
        }
        if (lastMapLocation != null && location.getTime() > 0 && lastMapLocation.getTime() > 0
                && location.getTime() + 1000 < lastMapLocation.getTime()) {
            return false;
        }
        return true;
    }

    private float resolveTravelHeading(Location location) {
        float candidate = Float.NaN;
        float speedKmh = location.hasSpeed() ? Math.max(0f, location.getSpeed() * 3.6f) : 0f;

        // At normal riding/driving speed Android's GPS bearing is the best source.
        if (location.hasBearing() && speedKmh >= 5f
                && (!location.hasAccuracy() || location.getAccuracy() <= 35f)) {
            candidate = location.getBearing();
        } else if (lastMapCourseLocation != null) {
            double moved = distanceMetersLocal(
                    lastMapCourseLocation.getLatitude(), lastMapCourseLocation.getLongitude(),
                    location.getLatitude(), location.getLongitude());
            long dt = location.getTime() - lastMapCourseLocation.getTime();
            if (moved >= 3.0 && dt > 0 && dt <= 6000) {
                candidate = (float) bearingLocal(
                        lastMapCourseLocation.getLatitude(), lastMapCourseLocation.getLongitude(),
                        location.getLatitude(), location.getLongitude());
            }
        }

        if (LocationManager.GPS_PROVIDER.equals(location.getProvider())) {
            if (lastMapCourseLocation == null || distanceMetersLocal(
                    lastMapCourseLocation.getLatitude(), lastMapCourseLocation.getLongitude(),
                    location.getLatitude(), location.getLongitude()) >= 2.0) {
                lastMapCourseLocation = new Location(location);
            }
        }

        if (Float.isNaN(candidate)) return lastMapHeading;
        if (Float.isNaN(lastMapHeading)) {
            lastMapHeading = normalizeHeading(candidate);
            return lastMapHeading;
        }

        // Light circular smoothing removes one-fix GPS spikes without creating the
        // large lag that a heavy animation would cause at a junction.
        float diff = shortestAngle(lastMapHeading, candidate);
        lastMapHeading = normalizeHeading(lastMapHeading + diff * 0.72f);
        return lastMapHeading;
    }

    private static float normalizeHeading(float value) {
        float r = value % 360f;
        return r < 0f ? r + 360f : r;
    }

    private static float shortestAngle(float from, float to) {
        float d = (to - from + 540f) % 360f - 180f;
        return d;
    }

    private static double bearingLocal(double la1, double lo1, double la2, double lo2) {
        double y = Math.sin(Math.toRadians(lo2 - lo1)) * Math.cos(Math.toRadians(la2));
        double x = Math.cos(Math.toRadians(la1)) * Math.sin(Math.toRadians(la2))
                - Math.sin(Math.toRadians(la1)) * Math.cos(Math.toRadians(la2))
                * Math.cos(Math.toRadians(lo2 - lo1));
        return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0;
    }

    private BitmapDrawable createGpsArrowIcon() {
        float density = getResources().getDisplayMetrics().density;
        int size = Math.max(48, Math.round(48f * density));
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        Path arrow = new Path();
        arrow.moveTo(size * 0.50f, size * 0.07f);
        arrow.lineTo(size * 0.83f, size * 0.86f);
        arrow.lineTo(size * 0.50f, size * 0.70f);
        arrow.lineTo(size * 0.17f, size * 0.86f);
        arrow.close();

        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(Color.rgb(211, 47, 47));
        canvas.drawPath(arrow, fill);

        Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(Math.max(2f, density * 2f));
        outline.setColor(Color.WHITE);
        canvas.drawPath(arrow, outline);

        return new BitmapDrawable(getResources(), bitmap);
    }

    private void updateCurrentLocationMarker(Location location) {
        if (routeMap == null || location == null) return;

        if (currentLocationMarker == null) {
            currentLocationMarker = new Marker(routeMap);
            currentLocationMarker.setIcon(createGpsArrowIcon());
            currentLocationMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
            currentLocationMarker.setTitle("Posizione GPS attuale");
        }

        GeoPoint rawPosition = new GeoPoint(location.getLatitude(), location.getLongitude());
        GeoPoint position = snapPhonePositionToDisplayedRoute(rawPosition);
        currentLocationMarker.setPosition(position);
        float heading = resolveTravelHeading(location);
        if (!Float.isNaN(heading)) {
            // osmdroid internally draws a Marker with -mBearing on a north-up map,
            // therefore the value passed to setRotation must be negated so a 90°
            // eastbound course actually points the arrow to the right/east.
            currentLocationMarker.setRotation(-heading);
        }
        addCurrentLocationMarkerOverlay();

        if (!mapCenteredOnGpsOnce) {
            routeMap.getController().setCenter(position);
            routeMap.getController().setZoom(15.0);
            mapCenteredOnGpsOnce = true;
        }
        routeMap.invalidate();
    }

    private GeoPoint snapPhonePositionToDisplayedRoute(GeoPoint raw) {
        if (raw == null || displayedRoutePoints.size() < 2) return raw;

        double lat = raw.getLatitude();
        double lon = raw.getLongitude();
        double latRad = Math.toRadians(lat);
        double metersPerDegLat = 111320.0;
        double metersPerDegLon = Math.max(1.0, Math.cos(latRad) * 111320.0);

        double best = Double.MAX_VALUE;
        double bestLat = lat;
        double bestLon = lon;

        for (int i = 0; i < displayedRoutePoints.size() - 1; i++) {
            GeoPoint a = displayedRoutePoints.get(i);
            GeoPoint b = displayedRoutePoints.get(i + 1);

            double ax = (a.getLongitude() - lon) * metersPerDegLon;
            double ay = (a.getLatitude() - lat) * metersPerDegLat;
            double bx = (b.getLongitude() - lon) * metersPerDegLon;
            double by = (b.getLatitude() - lat) * metersPerDegLat;

            double vx = bx - ax;
            double vy = by - ay;
            double len2 = vx * vx + vy * vy;
            double t = 0.0;
            if (len2 > 0.001) {
                t = -(ax * vx + ay * vy) / len2;
                if (t < 0.0) t = 0.0;
                if (t > 1.0) t = 1.0;
            }

            double px = ax + vx * t;
            double py = ay + vy * t;
            double d = Math.sqrt(px * px + py * py);
            if (d < best) {
                best = d;
                bestLat = a.getLatitude() + (b.getLatitude() - a.getLatitude()) * t;
                bestLon = a.getLongitude() + (b.getLongitude() - a.getLongitude()) * t;
            }
        }

        // Only normal GPS/map disagreement is visually corrected. Beyond 20 m we show
        // the real GPS position so an actual deviation remains visible on the phone.
        if (best <= PHONE_ROUTE_SNAP_METERS) return new GeoPoint(bestLat, bestLon);
        return raw;
    }

    private void addCurrentLocationMarkerOverlay() {
        if (routeMap == null || currentLocationMarker == null) return;
        routeMap.getOverlays().remove(currentLocationMarker);
        // Keep MapEventsOverlay last so long-press continues to work everywhere.
        if (mapEventsOverlay != null) routeMap.getOverlays().remove(mapEventsOverlay);
        routeMap.getOverlays().add(currentLocationMarker);
        if (mapEventsOverlay != null) routeMap.getOverlays().add(mapEventsOverlay);
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

    private String currentApiKey() {
        String typed = apiKeyEdit == null ? "" : apiKeyEdit.getText().toString().trim();
        if (typed.length() >= 8) return typed;
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_API_KEY, "").trim();
    }

    private void updateApiKeyUi() {
        if (apiKeyEdit == null || apiKeySavedLabel == null || apiKeyButton == null) return;
        boolean saved = currentApiKey().length() >= 8;
        if (saved) {
            apiKeyEdit.setVisibility(android.view.View.GONE);
            apiKeySavedLabel.setVisibility(android.view.View.VISIBLE);
            apiKeyButton.setText("CAMBIA API");
        } else {
            apiKeyEdit.setVisibility(android.view.View.VISIBLE);
            apiKeySavedLabel.setVisibility(android.view.View.GONE);
            apiKeyButton.setText("SALVA API");
        }
    }

    private void handleApiKeyButton() {
        if (apiKeyEdit.getVisibility() == android.view.View.GONE) {
            apiKeyEdit.setVisibility(android.view.View.VISIBLE);
            apiKeySavedLabel.setVisibility(android.view.View.GONE);
            apiKeyButton.setText("SALVA API");
            apiKeyEdit.requestFocus();
            apiKeyEdit.setSelection(apiKeyEdit.getText().length());
            return;
        }

        String key = apiKeyEdit.getText().toString().trim();
        if (key.length() < 8) {
            status.setText("Inserisci una chiave API OpenRouteService valida.");
            return;
        }

        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(PREF_API_KEY, key)
                .apply();
        status.setText("Chiave API salvata.");
        updateApiKeyUi();
    }

    private void savePrefs() {
        SharedPreferences.Editor e = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        String key = apiKeyEdit.getText().toString().trim();
        if (key.length() >= 8) e.putString(PREF_API_KEY, key);
        e.putString(PREF_DESTINATION, destinationEdit.getText().toString().trim())
                .putString(PREF_ROUTE_MODE, routeMode)
                .putBoolean(PREF_ALLOW_FAST_ROADS, allowFastRoads)
                .apply();
        if (key.length() >= 8) updateApiKeyUi();
    }

    private void loadFavorites() {
        favorites.clear();
        String raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_FAVORITES, "[]");
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                String name = o.optString("name", "").trim();
                String address = o.optString("address", "").trim();
                if (!name.isEmpty() && !address.isEmpty()) favorites.add(new Favorite(name, address));
            }
        } catch (Exception ignored) {
        }
    }

    private void persistFavorites() {
        JSONArray a = new JSONArray();
        try {
            for (Favorite f : favorites) {
                JSONObject o = new JSONObject();
                o.put("name", f.name);
                o.put("address", f.address);
                a.put(o);
            }
        } catch (Exception ignored) {
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_FAVORITES, a.toString()).apply();
    }

    private void refreshFavoritesUi() {
        if (favoritesContainer == null) return;
        favoritesContainer.removeAllViews();

        if (favorites.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Nessuna destinazione salvata");
            empty.setTextSize(13);
            favoritesContainer.addView(empty);
            return;
        }

        for (int i = 0; i < favorites.size(); i++) {
            final int index = i;
            Favorite f = favorites.get(i);
            Button b = new Button(this);
            b.setAllCaps(false);
            b.setText("★ " + f.name);
            b.setOnClickListener(v -> {
                Favorite selected = favorites.get(index);
                clearSelectedRoutePoints(false);
                destinationEdit.setText(selected.address);
                savePrefs();
                status.setText("Destinazione selezionata: " + selected.name);
            });
            b.setOnLongClickListener(v -> {
                showFavoriteActions(index);
                return true;
            });
            favoritesContainer.addView(b);
        }
    }

    private void showSaveFavoriteDialog() {
        String address = destinationEdit.getText().toString().trim();
        if (address.length() < 3) {
            status.setText("Inserisci prima una destinazione da salvare.");
            return;
        }

        EditText nameEdit = new EditText(this);
        nameEdit.setHint("Nome, es. Campo di atletica");

        new AlertDialog.Builder(this)
                .setTitle("Salva destinazione")
                .setMessage(address)
                .setView(nameEdit)
                .setNegativeButton("Annulla", null)
                .setPositiveButton("Salva", (dialog, which) -> {
                    String name = nameEdit.getText().toString().trim();
                    if (name.isEmpty()) name = address;

                    int existing = -1;
                    for (int i = 0; i < favorites.size(); i++) {
                        if (favorites.get(i).address.equalsIgnoreCase(address)) {
                            existing = i;
                            break;
                        }
                    }

                    if (existing >= 0) {
                        favorites.get(existing).name = name;
                    } else {
                        favorites.add(new Favorite(name, address));
                    }
                    persistFavorites();
                    refreshFavoritesUi();
                    status.setText("Preferito salvato: " + name);
                })
                .show();
    }

    private void showFavoriteActions(int index) {
        if (index < 0 || index >= favorites.size()) return;
        Favorite f = favorites.get(index);
        new AlertDialog.Builder(this)
                .setTitle(f.name)
                .setItems(new String[]{"Rinomina", "Elimina"}, (dialog, which) -> {
                    if (which == 0) showRenameFavoriteDialog(index);
                    else deleteFavorite(index);
                })
                .setNegativeButton("Annulla", null)
                .show();
    }

    private void showRenameFavoriteDialog(int index) {
        if (index < 0 || index >= favorites.size()) return;
        Favorite f = favorites.get(index);
        EditText nameEdit = new EditText(this);
        nameEdit.setText(f.name);
        nameEdit.setSelection(nameEdit.getText().length());

        new AlertDialog.Builder(this)
                .setTitle("Rinomina preferito")
                .setView(nameEdit)
                .setNegativeButton("Annulla", null)
                .setPositiveButton("Salva", (dialog, which) -> {
                    String name = nameEdit.getText().toString().trim();
                    if (name.isEmpty()) return;
                    favorites.get(index).name = name;
                    persistFavorites();
                    refreshFavoritesUi();
                    status.setText("Preferito rinominato: " + name);
                })
                .show();
    }

    private void deleteFavorite(int index) {
        if (index < 0 || index >= favorites.size()) return;
        String name = favorites.get(index).name;
        favorites.remove(index);
        persistFavorites();
        refreshFavoritesUi();
        status.setText("Preferito eliminato: " + name);
    }


    private void loadSavedPoints() {
        savedPoints.clear();
        String raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_SAVED_POINTS, "[]");
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                String name = o.optString("name", "").trim();
                double lat = o.optDouble("lat", Double.NaN);
                double lon = o.optDouble("lon", Double.NaN);
                if (!name.isEmpty() && !Double.isNaN(lat) && !Double.isNaN(lon)) {
                    savedPoints.add(new SavedPoint(name, lat, lon));
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void persistSavedPoints() {
        JSONArray a = new JSONArray();
        try {
            for (SavedPoint p : savedPoints) {
                JSONObject o = new JSONObject();
                o.put("name", p.name);
                o.put("lat", p.lat);
                o.put("lon", p.lon);
                a.put(o);
            }
        } catch (Exception ignored) {
        }
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(PREF_SAVED_POINTS, a.toString())
                .apply();
    }

    private void refreshSavedPointsUi() {
        if (savedPointsContainer == null) return;
        savedPointsContainer.removeAllViews();

        if (savedPoints.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Nessun punto salvato");
            empty.setTextSize(13);
            savedPointsContainer.addView(empty);
            return;
        }

        for (int i = 0; i < savedPoints.size(); i++) {
            final int index = i;
            SavedPoint p = savedPoints.get(i);
            Button b = new Button(this);
            b.setAllCaps(false);
            b.setText("● " + p.name);
            b.setOnClickListener(v -> showSavedPointActions(index));
            b.setOnLongClickListener(v -> {
                showSavedPointRenameDelete(index);
                return true;
            });
            savedPointsContainer.addView(b);
        }
    }

    private void showSaveMapPointDialog() {
        if (selectedRoutePoints.isEmpty()) {
            status.setText("Seleziona prima un punto sulla mappa con una pressione lunga.");
            return;
        }

        int index = lastSelectedPointIndex;
        if (index < 0 || index >= selectedRoutePoints.size()) index = selectedRoutePoints.size() - 1;
        final GeoPoint point = selectedRoutePoints.get(index);

        EditText nameEdit = new EditText(this);
        nameEdit.setHint("Nome, es. Campo di atletica");

        new AlertDialog.Builder(this)
                .setTitle("Salva punto sulla mappa")
                .setMessage(String.format(java.util.Locale.US, "%.6f, %.6f", point.getLatitude(), point.getLongitude()))
                .setView(nameEdit)
                .setNegativeButton("Annulla", null)
                .setPositiveButton("Salva", (dialog, which) -> {
                    String name = nameEdit.getText().toString().trim();
                    if (name.isEmpty()) name = "Punto " + (savedPoints.size() + 1);

                    int existing = findSavedPointNear(point.getLatitude(), point.getLongitude(), 5.0);
                    if (existing >= 0) {
                        savedPoints.get(existing).name = name;
                        savedPoints.get(existing).lat = point.getLatitude();
                        savedPoints.get(existing).lon = point.getLongitude();
                    } else {
                        savedPoints.add(new SavedPoint(name, point.getLatitude(), point.getLongitude()));
                    }

                    persistSavedPoints();
                    refreshSavedPointsUi();
                    status.setText("Punto salvato: " + name);
                })
                .show();
    }


    private void showSelectedMapPointActions(int index) {
        if (index < 0 || index >= selectedRoutePoints.size()) return;

        lastSelectedPointIndex = index;
        GeoPoint p = selectedRoutePoints.get(index);
        String coords = String.format(
                java.util.Locale.US,
                "%.6f, %.6f",
                p.getLatitude(),
                p.getLongitude()
        );

        new AlertDialog.Builder(this)
                .setTitle("Punto selezionato")
                .setMessage(coords)
                .setItems(new String[]{
                                "Naviga qui dalla posizione attuale",
                                "Salva punto",
                                "Usa per creare un tragitto",
                                "Rimuovi punto"
                        },
                        (dialog, which) -> {
                            if (which == 0) {
                                startRoutingServiceToMapPoint(index);
                            } else if (which == 1) {
                                showSaveMapPointDialog();
                            } else if (which == 2) {
                                status.setText("Punto mantenuto per il tragitto. Seleziona altri punti sulla mappa.");
                            } else {
                                removeSelectedRoutePoint(index);
                            }
                        })
                .setNegativeButton("Chiudi", null)
                .show();
    }

    private void startRoutingServiceToMapPoint(int index) {
        if (index < 0 || index >= selectedRoutePoints.size()) return;
        if (!validateNavigationPrerequisites(false)) return;

        GeoPoint p = selectedRoutePoints.get(index);
        savePrefs();

        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(PREF_NAV_SOURCE, "point")
                .putString(PREF_NAV_TARGET_LAT, Double.toString(p.getLatitude()))
                .putString(PREF_NAV_TARGET_LON, Double.toString(p.getLongitude()))
                .putString(PREF_NAV_TARGET_LABEL, "Punto selezionato")
                .putString(PREF_NAV_ROUTE_POINTS, "[]")
                .apply();

        launchNavigationService();
        status.setText("Navigazione avviata dalla posizione GPS attuale al punto selezionato.");
    }

    private int findSavedPointNear(double lat, double lon, double maxMeters) {
        for (int i = 0; i < savedPoints.size(); i++) {
            SavedPoint p = savedPoints.get(i);
            if (distanceMetersLocal(lat, lon, p.lat, p.lon) <= maxMeters) return i;
        }
        return -1;
    }

    private void showSavedPointActions(int index) {
        if (index < 0 || index >= savedPoints.size()) return;
        SavedPoint p = savedPoints.get(index);
        new AlertDialog.Builder(this)
                .setTitle(p.name)
                .setItems(new String[]{"Naviga qui", "Aggiungi come waypoint", "Usa come partenza", "Rinomina", "Elimina"},
                        (dialog, which) -> {
                            if (which == 0) {
                                startRoutingServiceToSavedPoint(index);
                            } else if (which == 1) {
                                addSavedPointAsWaypoint(index);
                            } else if (which == 2) {
                                useSavedPointAsStart(index);
                            } else if (which == 3) {
                                showRenameSavedPointDialog(index);
                            } else {
                                deleteSavedPoint(index);
                            }
                        })
                .setNegativeButton("Annulla", null)
                .show();
    }

    private void showSavedPointRenameDelete(int index) {
        if (index < 0 || index >= savedPoints.size()) return;
        SavedPoint p = savedPoints.get(index);
        new AlertDialog.Builder(this)
                .setTitle(p.name)
                .setItems(new String[]{"Rinomina", "Elimina"}, (dialog, which) -> {
                    if (which == 0) showRenameSavedPointDialog(index);
                    else deleteSavedPoint(index);
                })
                .setNegativeButton("Annulla", null)
                .show();
    }

    private void showRenameSavedPointDialog(int index) {
        if (index < 0 || index >= savedPoints.size()) return;
        SavedPoint p = savedPoints.get(index);
        EditText nameEdit = new EditText(this);
        nameEdit.setText(p.name);
        nameEdit.setSelection(nameEdit.getText().length());

        new AlertDialog.Builder(this)
                .setTitle("Rinomina punto")
                .setView(nameEdit)
                .setNegativeButton("Annulla", null)
                .setPositiveButton("Salva", (dialog, which) -> {
                    String name = nameEdit.getText().toString().trim();
                    if (name.isEmpty()) return;
                    savedPoints.get(index).name = name;
                    persistSavedPoints();
                    refreshSavedPointsUi();
                    status.setText("Punto rinominato: " + name);
                })
                .show();
    }

    private void deleteSavedPoint(int index) {
        if (index < 0 || index >= savedPoints.size()) return;
        String name = savedPoints.get(index).name;
        savedPoints.remove(index);
        persistSavedPoints();
        refreshSavedPointsUi();
        status.setText("Punto eliminato: " + name);
    }

    private void addSavedPointAsWaypoint(int index) {
        if (index < 0 || index >= savedPoints.size()) return;
        SavedPoint p = savedPoints.get(index);
        GeoPoint gp = new GeoPoint(p.lat, p.lon);

        if (selectedRoutePoints.size() < 2) {
            selectedRoutePoints.add(gp);
            lastSelectedPointIndex = selectedRoutePoints.size() - 1;
        } else {
            int insertAt = selectedRoutePoints.size() - 1;
            selectedRoutePoints.add(insertAt, gp);
            lastSelectedPointIndex = insertAt;
        }

        redrawSelectedRoutePoints();
        zoomMapTo(selectedRoutePoints);
        status.setText(p.name + " aggiunto al tragitto.");
    }

    private void useSavedPointAsStart(int index) {
        if (index < 0 || index >= savedPoints.size()) return;
        SavedPoint p = savedPoints.get(index);
        GeoPoint gp = new GeoPoint(p.lat, p.lon);
        if (selectedRoutePoints.isEmpty()) selectedRoutePoints.add(gp);
        else selectedRoutePoints.set(0, gp);
        lastSelectedPointIndex = 0;
        redrawSelectedRoutePoints();
        zoomMapTo(selectedRoutePoints);
        status.setText("Partenza impostata: " + p.name);
    }

    private void startRoutingServiceToSavedPoint(int index) {
        if (index < 0 || index >= savedPoints.size()) return;
        SavedPoint p = savedPoints.get(index);

        if (!validateNavigationPrerequisites(false)) return;

        clearSelectedRoutePoints(false);
        destinationEdit.setText(p.name);
        savePrefs();

        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(PREF_NAV_SOURCE, "point")
                .putString(PREF_NAV_TARGET_LAT, Double.toString(p.lat))
                .putString(PREF_NAV_TARGET_LON, Double.toString(p.lon))
                .putString(PREF_NAV_TARGET_LABEL, p.name)
                .putString(PREF_NAV_ROUTE_POINTS, "[]")
                .apply();

        launchNavigationService();
        status.setText("Navigazione verso " + p.name + " dalla posizione attuale.");
    }

    private void loadSavedRoutes() {
        savedRoutes.clear();
        String raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_SAVED_ROUTES, "[]");
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                String name = o.optString("name", "").trim();
                JSONArray pts = o.optJSONArray("points");
                if (name.isEmpty() || pts == null || pts.length() < 2) continue;

                ArrayList<GeoPoint> routePoints = new ArrayList<>();
                for (int j = 0; j < pts.length(); j++) {
                    JSONObject po = pts.optJSONObject(j);
                    if (po == null) continue;
                    double lat = po.optDouble("lat", Double.NaN);
                    double lon = po.optDouble("lon", Double.NaN);
                    if (!Double.isNaN(lat) && !Double.isNaN(lon)) routePoints.add(new GeoPoint(lat, lon));
                }
                if (routePoints.size() >= 2) savedRoutes.add(new SavedRoute(name, routePoints));
            }
        } catch (Exception ignored) {
        }
    }

    private void persistSavedRoutes() {
        JSONArray a = new JSONArray();
        try {
            for (SavedRoute r : savedRoutes) {
                JSONObject o = new JSONObject();
                o.put("name", r.name);
                JSONArray pts = new JSONArray();
                for (GeoPoint p : r.points) {
                    JSONObject po = new JSONObject();
                    po.put("lat", p.getLatitude());
                    po.put("lon", p.getLongitude());
                    pts.put(po);
                }
                o.put("points", pts);
                a.put(o);
            }
        } catch (Exception ignored) {
        }
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(PREF_SAVED_ROUTES, a.toString())
                .apply();
    }

    private void refreshSavedRoutesUi() {
        if (savedRoutesContainer == null) return;
        savedRoutesContainer.removeAllViews();

        if (savedRoutes.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Nessun tragitto salvato");
            empty.setTextSize(13);
            savedRoutesContainer.addView(empty);
            return;
        }

        for (int i = 0; i < savedRoutes.size(); i++) {
            final int index = i;
            SavedRoute r = savedRoutes.get(i);
            Button b = new Button(this);
            b.setAllCaps(false);
            int waypointCount = Math.max(0, r.points.size() - 2);
            b.setText("↝ " + r.name + " (" + waypointCount + " waypoint)");
            b.setOnClickListener(v -> loadSavedRoute(index, true));
            b.setOnLongClickListener(v -> {
                showSavedRouteActions(index);
                return true;
            });
            savedRoutesContainer.addView(b);
        }
    }

    private void showSaveRouteDialog() {
        if (selectedRoutePoints.size() < 2) {
            status.setText("Seleziona almeno partenza e arrivo prima di salvare il tragitto.");
            return;
        }

        EditText nameEdit = new EditText(this);
        nameEdit.setHint("Nome, es. Giro collinare");

        new AlertDialog.Builder(this)
                .setTitle("Salva tragitto")
                .setMessage("Salvo partenza, arrivo e tutti i waypoint nell'ordine attuale.")
                .setView(nameEdit)
                .setNegativeButton("Annulla", null)
                .setPositiveButton("Salva", (dialog, which) -> {
                    String name = nameEdit.getText().toString().trim();
                    if (name.isEmpty()) name = "Tragitto " + (savedRoutes.size() + 1);

                    ArrayList<GeoPoint> copy = copyGeoPoints(selectedRoutePoints);
                    int existing = -1;
                    for (int i = 0; i < savedRoutes.size(); i++) {
                        if (savedRoutes.get(i).name.equalsIgnoreCase(name)) {
                            existing = i;
                            break;
                        }
                    }
                    if (existing >= 0) savedRoutes.set(existing, new SavedRoute(name, copy));
                    else savedRoutes.add(new SavedRoute(name, copy));

                    persistSavedRoutes();
                    refreshSavedRoutesUi();
                    status.setText("Tragitto salvato: " + name);
                })
                .show();
    }

    private void loadSavedRoute(int index, boolean calculatePreview) {
        if (index < 0 || index >= savedRoutes.size()) return;
        SavedRoute r = savedRoutes.get(index);
        selectedRoutePoints.clear();
        selectedRoutePoints.addAll(copyGeoPoints(r.points));
        lastSelectedPointIndex = selectedRoutePoints.size() - 1;
        redrawSelectedRoutePoints();
        zoomMapTo(selectedRoutePoints);
        status.setText("Tragitto caricato: " + r.name);

        if (calculatePreview) {
            String key = currentApiKey();
            if (key.length() >= 8) fetchAndDrawPreviewFromSelectedPoints(key);
        }
    }

    private void showSavedRouteActions(int index) {
        if (index < 0 || index >= savedRoutes.size()) return;
        SavedRoute r = savedRoutes.get(index);
        new AlertDialog.Builder(this)
                .setTitle(r.name)
                .setItems(new String[]{"Carica", "Avvia tragitto", "Rinomina", "Elimina"}, (dialog, which) -> {
                    if (which == 0) {
                        loadSavedRoute(index, true);
                    } else if (which == 1) {
                        loadSavedRoute(index, false);
                        startRoutingService();
                    } else if (which == 2) {
                        showRenameSavedRouteDialog(index);
                    } else {
                        deleteSavedRoute(index);
                    }
                })
                .setNegativeButton("Annulla", null)
                .show();
    }

    private void showRenameSavedRouteDialog(int index) {
        if (index < 0 || index >= savedRoutes.size()) return;
        SavedRoute r = savedRoutes.get(index);
        EditText nameEdit = new EditText(this);
        nameEdit.setText(r.name);
        nameEdit.setSelection(nameEdit.getText().length());

        new AlertDialog.Builder(this)
                .setTitle("Rinomina tragitto")
                .setView(nameEdit)
                .setNegativeButton("Annulla", null)
                .setPositiveButton("Salva", (dialog, which) -> {
                    String name = nameEdit.getText().toString().trim();
                    if (name.isEmpty()) return;
                    savedRoutes.get(index).name = name;
                    persistSavedRoutes();
                    refreshSavedRoutesUi();
                    status.setText("Tragitto rinominato: " + name);
                })
                .show();
    }

    private void deleteSavedRoute(int index) {
        if (index < 0 || index >= savedRoutes.size()) return;
        String name = savedRoutes.get(index).name;
        savedRoutes.remove(index);
        persistSavedRoutes();
        refreshSavedRoutesUi();
        status.setText("Tragitto eliminato: " + name);
    }

    private ArrayList<GeoPoint> copyGeoPoints(ArrayList<GeoPoint> source) {
        ArrayList<GeoPoint> copy = new ArrayList<>();
        for (GeoPoint p : source) copy.add(new GeoPoint(p.getLatitude(), p.getLongitude()));
        return copy;
    }

    private String serializeGeoPoints(ArrayList<GeoPoint> points) {
        JSONArray a = new JSONArray();
        try {
            for (GeoPoint p : points) {
                JSONObject o = new JSONObject();
                o.put("lat", p.getLatitude());
                o.put("lon", p.getLongitude());
                a.put(o);
            }
        } catch (Exception ignored) {
        }
        return a.toString();
    }

    private void clearSelectedRoutePoints(boolean updateStatus) {
        selectedRoutePoints.clear();
        lastSelectedPointIndex = -1;
        redrawSelectedRoutePoints();
        if (updateStatus) status.setText("Punti del tragitto cancellati.");
    }

    private boolean validateNavigationPrerequisites(boolean requireTextDestination) {
        savePrefs();

        if (currentApiKey().length() < 8) {
            status.setText("Inserisci API key OpenRouteService.");
            return false;
        }

        if (requireTextDestination && destinationEdit.getText().toString().trim().length() < 3) {
            status.setText("Inserisci una destinazione.");
            return false;
        }

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            status.setText("Concedi il permesso posizione.");
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 5);
            return false;
        }

        if (android.os.Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 6);
        }
        return true;
    }

    private void launchNavigationService() {
        Intent i = new Intent(this, NavigationService.class);
        i.setAction(NavigationService.ACTION_START);

        if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
    }

    private static double distanceMetersLocal(double la1, double lo1, double la2, double lo2) {
        double r = 6371000.0;
        double p1 = Math.toRadians(la1);
        double p2 = Math.toRadians(la2);
        double dp = Math.toRadians(la2 - la1);
        double dl = Math.toRadians(lo2 - lo1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) +
                Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return r * 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
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

        String key = currentApiKey();

        if (key.length() < 8) {
            status.setText("Inserisci API key OpenRouteService.");
            return;
        }

        // Se è stato scelto un solo punto sulla mappa, trattalo come destinazione:
        // la partenza è la posizione GPS attuale.
        if (selectedRoutePoints.size() == 1) {
            showPreviewFromCurrentLocationToMapPoint(key, selectedRoutePoints.get(0));
            return;
        }

        // Con 2 o più punti: primo = partenza, ultimo = arrivo, intermedi = waypoint.
        if (selectedRoutePoints.size() >= 2) {
            fetchAndDrawPreviewFromSelectedPoints(key);
            return;
        }

        // Nessun punto selezionato: mantiene il funzionamento precedente
        // GPS attuale + destinazione scritta nel campo.
        String destText = destinationEdit.getText().toString().trim();

        if (destText.length() < 3) {
            status.setText("Inserisci una destinazione oppure seleziona almeno 2 punti sulla mappa con una pressione lunga.");
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


    private void showPreviewFromCurrentLocationToMapPoint(String key, GeoPoint point) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            status.setText("Concedi il permesso posizione per navigare verso il punto.");
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
            fetchAndDrawPreviewToMapPoint(key, point, last);
            return;
        }

        status.setText("Cerco posizione GPS...");
        try {
            LocationListener once = new LocationListener() {
                @Override public void onLocationChanged(Location location) {
                    try { lm.removeUpdates(this); } catch (Exception ignored) {}
                    fetchAndDrawPreviewToMapPoint(key, point, location);
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

    private void fetchAndDrawPreviewToMapPoint(String key, GeoPoint point, Location startLocation) {
        status.setText("Calcolo percorso dalla posizione attuale al punto selezionato...");

        final LatLon dest = new LatLon(point.getLatitude(), point.getLongitude());

        new Thread(() -> {
            try {
                PreviewRoute previewRoute = previewRequestDirections(key, startLocation, dest);
                runOnUiThread(() -> drawPreviewRoute(previewRoute, startLocation, dest));
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("Errore anteprima: " + e.getMessage()));
            }
        }).start();
    }

    private void addSelectedRoutePoint(GeoPoint p) {
        GeoPoint point = new GeoPoint(p.getLatitude(), p.getLongitude());
        selectedRoutePoints.add(point);
        lastSelectedPointIndex = selectedRoutePoints.size() - 1;

        redrawSelectedRoutePoints();

        int count = selectedRoutePoints.size();
        if (count == 1) {
            status.setText("Punto selezionato: puoi navigare qui dalla posizione attuale, salvarlo oppure usarlo per creare un tragitto.");
        } else if (count == 2) {
            status.setText("Partenza e arrivo selezionati. Puoi aggiungere altri punti tenendo premuto, poi premi VEDI TRAGITTO SU MAPPA.");
        } else {
            int waypointCount = count - 2;
            status.setText(count + " punti selezionati: " + waypointCount +
                    (waypointCount == 1 ? " waypoint. " : " waypoint. ") +
                    "Premi VEDI TRAGITTO SU MAPPA per calcolare il percorso.");
        }
    }

    private int findSelectedRoutePointNear(GeoPoint pressedPoint) {
        if (routeMap == null || selectedRoutePoints.isEmpty()) return -1;

        Point pressedPx = routeMap.getProjection().toPixels(pressedPoint, null);
        float density = getResources().getDisplayMetrics().density;
        double hitRadiusPx = 42.0 * density;
        double bestDistance = hitRadiusPx;
        int bestIndex = -1;

        for (int i = 0; i < selectedRoutePoints.size(); i++) {
            Point pointPx = routeMap.getProjection().toPixels(selectedRoutePoints.get(i), null);
            double dx = pointPx.x - pressedPx.x;
            double dy = pointPx.y - pressedPx.y;
            double distance = Math.sqrt(dx * dx + dy * dy);

            if (distance <= bestDistance) {
                bestDistance = distance;
                bestIndex = i;
            }
        }

        return bestIndex;
    }

    private void removeSelectedRoutePoint(int index) {
        if (index < 0 || index >= selectedRoutePoints.size()) return;

        int oldCount = selectedRoutePoints.size();
        String removedType;
        if (index == 0) {
            removedType = "Partenza";
        } else if (index == oldCount - 1) {
            removedType = "Arrivo";
        } else {
            removedType = "Waypoint " + (index + 1);
        }

        selectedRoutePoints.remove(index);
        if (selectedRoutePoints.isEmpty()) {
            lastSelectedPointIndex = -1;
        } else {
            lastSelectedPointIndex = Math.min(index, selectedRoutePoints.size() - 1);
        }
        redrawSelectedRoutePoints();

        int count = selectedRoutePoints.size();
        if (count == 0) {
            status.setText(removedType + " eliminato. Nessun punto selezionato.");
        } else if (count == 1) {
            status.setText(removedType + " eliminato. Rimane solo la partenza: seleziona almeno un altro punto.");
        } else {
            int waypointCount = Math.max(0, count - 2);
            status.setText(removedType + " eliminato. " + count + " punti selezionati: " +
                    waypointCount + (waypointCount == 1 ? " waypoint." : " waypoint.") +
                    " Premi VEDI TRAGITTO SU MAPPA per ricalcolare.");
        }
    }

    private void redrawSelectedRoutePoints() {
        displayedRoutePoints.clear();
        routeMap.getOverlays().clear();

        // Prima i marker, poi l'overlay degli eventi.
        // In osmdroid gli overlay aggiunti per ultimi ricevono per primi i touch:
        // così la pressione lunga arriva a MapEventsOverlay anche se il dito è sul marker.
        addSelectedPointMarkers();
        addCurrentLocationMarkerOverlay();

        if (mapEventsOverlay != null) {
            routeMap.getOverlays().remove(mapEventsOverlay);
            routeMap.getOverlays().add(mapEventsOverlay);
        }

        routeMap.invalidate();
    }

    private void addSelectedPointMarkers() {
        for (int i = 0; i < selectedRoutePoints.size(); i++) {
            Marker marker = new Marker(routeMap);
            marker.setPosition(selectedRoutePoints.get(i));
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);

            if (selectedRoutePoints.size() == 1) {
                marker.setTitle("Destinazione selezionata");
            } else if (i == 0) {
                marker.setTitle("1 - Partenza");
            } else if (i == selectedRoutePoints.size() - 1) {
                marker.setTitle((i + 1) + " - Arrivo");
            } else {
                marker.setTitle((i + 1) + " - Waypoint");
            }

            final int markerIndex = i;
            marker.setOnMarkerClickListener((clickedMarker, mapView) -> {
                showSelectedMapPointActions(markerIndex);
                return true;
            });

            routeMap.getOverlays().add(marker);
        }
    }

    private void fetchAndDrawPreviewFromSelectedPoints(String key) {
        status.setText("Calcolo percorso attraverso i punti selezionati...");

        final ArrayList<GeoPoint> pointsCopy = new ArrayList<>();
        for (GeoPoint p : selectedRoutePoints) {
            pointsCopy.add(new GeoPoint(p.getLatitude(), p.getLongitude()));
        }

        new Thread(() -> {
            try {
                PreviewRoute previewRoute = previewRequestDirections(key, pointsCopy);
                runOnUiThread(() -> drawSelectedPreviewRoute(previewRoute, pointsCopy));
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("Errore anteprima: " + e.getMessage()));
            }
        }).start();
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
        URL url = new URL("https://api.heigit.org/pelias/v1/search?api_key=" + key + "&text=" + encoded + "&size=1");

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
        URL url = new URL("https://api.heigit.org/openrouteservice/v2/directions/driving-car/geojson");

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

    private PreviewRoute previewRequestDirections(String key, ArrayList<GeoPoint> selectedPoints) throws Exception {
        URL url = new URL("https://api.heigit.org/openrouteservice/v2/directions/driving-car/geojson");

        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Authorization", key);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");

        String preference = "shortest".equals(routeMode) ? "shortest" : "fastest";

        StringBuilder body = new StringBuilder();
        body.append("{\"coordinates\":[");

        for (int i = 0; i < selectedPoints.size(); i++) {
            if (i > 0) body.append(",");
            GeoPoint p = selectedPoints.get(i);
            body.append("[")
                    .append(p.getLongitude())
                    .append(",")
                    .append(p.getLatitude())
                    .append("]");
        }

        body.append("],\"preference\":\"")
                .append(preference)
                .append("\"");

        if (!allowFastRoads) {
            body.append(",\"options\":{\"avoid_features\":[\"highways\",\"tollways\"]}");
        }

        body.append("}");

        try (OutputStream os = c.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
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

    private void drawSelectedPreviewRoute(PreviewRoute previewRoute, ArrayList<GeoPoint> selectedPoints) {
        if (previewRoute.points.isEmpty()) {
            status.setText("Nessun punto percorso trovato.");
            return;
        }

        displayedRoutePoints.clear();
        displayedRoutePoints.addAll(previewRoute.points);
        routeMap.getOverlays().clear();

        Polyline line = new Polyline();
        line.setPoints(previewRoute.points);
        line.setWidth(8f);
        line.setColor(0xff1976d2);
        routeMap.getOverlays().add(line);

        // Ridisegna partenza, waypoint e arrivo sopra la linea.
        addSelectedPointMarkers();
        addCurrentLocationMarkerOverlay();

        // Deve stare per ultimo: così intercetta la pressione lunga anche sopra i marker.
        if (mapEventsOverlay != null) {
            routeMap.getOverlays().remove(mapEventsOverlay);
            routeMap.getOverlays().add(mapEventsOverlay);
        }

        zoomMapTo(previewRoute.points);
        routeMap.invalidate();

        String modeText = "shortest".equals(routeMode) ? "più breve" : "più veloce";
        String roadText = allowFastRoads ? "con autostrade/superstrade" : "senza autostrade/superstrade";
        int waypointCount = Math.max(0, selectedPoints.size() - 2);

        status.setText("Anteprima: " + modeText + " " + roadText + " - " +
                waypointCount + (waypointCount == 1 ? " waypoint - " : " waypoint - ") +
                formatPreviewDistance(previewRoute.distanceMeters) + " - " +
                formatPreviewDuration(previewRoute.durationSeconds));
    }

    private void drawPreviewRoute(PreviewRoute previewRoute, Location startLocation, LatLon dest) {
        if (previewRoute.points.isEmpty()) {
            status.setText("Nessun punto percorso trovato.");
            return;
        }

        displayedRoutePoints.clear();
        displayedRoutePoints.addAll(previewRoute.points);
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
        addCurrentLocationMarkerOverlay();

        // Deve stare per ultimo per ricevere il long-press prima dei marker.
        if (mapEventsOverlay != null) {
            routeMap.getOverlays().remove(mapEventsOverlay);
            routeMap.getOverlays().add(mapEventsOverlay);
        }

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
        boolean hasSingleMapTarget = selectedRoutePoints.size() == 1;
        boolean hasRoutePoints = selectedRoutePoints.size() >= 2;
        if (!validateNavigationPrerequisites(!hasSingleMapTarget && !hasRoutePoints)) return;

        SharedPreferences.Editor e = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        if (hasRoutePoints) {
            e.putString(PREF_NAV_SOURCE, "route")
                    .putString(PREF_NAV_ROUTE_POINTS, serializeGeoPoints(selectedRoutePoints))
                    .putString(PREF_NAV_TARGET_LABEL, "Tragitto con waypoint")
                    .putString(PREF_NAV_TARGET_LAT, "")
                    .putString(PREF_NAV_TARGET_LON, "");
        } else if (hasSingleMapTarget) {
            GeoPoint p = selectedRoutePoints.get(0);
            e.putString(PREF_NAV_SOURCE, "point")
                    .putString(PREF_NAV_ROUTE_POINTS, "[]")
                    .putString(PREF_NAV_TARGET_LABEL, "Punto selezionato")
                    .putString(PREF_NAV_TARGET_LAT, Double.toString(p.getLatitude()))
                    .putString(PREF_NAV_TARGET_LON, Double.toString(p.getLongitude()));
        } else {
            e.putString(PREF_NAV_SOURCE, "text")
                    .putString(PREF_NAV_ROUTE_POINTS, "[]")
                    .putString(PREF_NAV_TARGET_LABEL, "")
                    .putString(PREF_NAV_TARGET_LAT, "")
                    .putString(PREF_NAV_TARGET_LON, "");
        }
        e.apply();

        launchNavigationService();

        if (hasRoutePoints) {
            int waypointCount = Math.max(0, selectedRoutePoints.size() - 2);
            status.setText("Navigazione avviata sul tragitto selezionato con " + waypointCount +
                    (waypointCount == 1 ? " waypoint." : " waypoint."));
        } else if (hasSingleMapTarget) {
            status.setText("Navigazione avviata dalla posizione GPS attuale al punto selezionato.");
        } else {
            status.setText("Navigazione avviata. Ora puoi spegnere lo schermo: deve restare la notifica Ciao Beeline.");
        }
    }

    private void requestManualReroute() {
        if (currentApiKey().length() < 8) {
            status.setText("Inserisci API key OpenRouteService.");
            return;
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            status.setText("Concedi il permesso posizione.");
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 5);
            return;
        }

        Intent i = new Intent(this, NavigationService.class);
        i.setAction(NavigationService.ACTION_REROUTE);
        if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
        status.setText("Ricalcolo manuale richiesto.");
    }

    private void clearCurrentRoute() {
        Intent i = new Intent(this, NavigationService.class);
        i.setAction(NavigationService.ACTION_CLEAR_ROUTE);
        try { startService(i); } catch (Exception ignored) {}

        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(PREF_NAV_SOURCE, "text")
                .putString(PREF_NAV_ROUTE_POINTS, "[]")
                .putString(PREF_NAV_TARGET_LABEL, "")
                .putString(PREF_NAV_TARGET_LAT, "")
                .putString(PREF_NAV_TARGET_LON, "")
                .apply();

        selectedRoutePoints.clear();
        displayedRoutePoints.clear();
        lastSelectedPointIndex = -1;
        if (routeMap != null) {
            routeMap.getOverlays().clear();
            addCurrentLocationMarkerOverlay();
            if (mapEventsOverlay != null) {
                routeMap.getOverlays().remove(mapEventsOverlay);
                routeMap.getOverlays().add(mapEventsOverlay);
            }
            routeMap.invalidate();
        }
        status.setText("Percorso cancellato. Preferiti, punti e tragitti salvati restano disponibili.");
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
        startMapLocationUpdates();
    }

    @Override
    protected void onPause() {
        stopMapLocationUpdates();
        if (routeMap != null) routeMap.onPause();
        super.onPause();
    }

    private static class Favorite {
        String name;
        final String address;

        Favorite(String name, String address) {
            this.name = name;
            this.address = address;
        }
    }

    private static class SavedPoint {
        String name;
        double lat;
        double lon;

        SavedPoint(String name, double lat, double lon) {
            this.name = name;
            this.lat = lat;
            this.lon = lon;
        }
    }

    private static class SavedRoute {
        String name;
        final ArrayList<GeoPoint> points;

        SavedRoute(String name, ArrayList<GeoPoint> points) {
            this.name = name;
            this.points = points;
        }
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
