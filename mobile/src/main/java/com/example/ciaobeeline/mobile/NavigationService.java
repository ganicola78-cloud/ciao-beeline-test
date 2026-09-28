package com.example.ciaobeeline.mobile;

import android.Manifest;
import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;

import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

public class NavigationService extends Service {
    private static final String PATH = "/nav_update";
    private static final String PREFS = "ciao_beeline_prefs";
    private static final String PREF_API_KEY = "ors_api_key";
    private static final String PREF_DESTINATION = "destination_text";
    private static final String PREF_ROUTE_MODE = "route_mode";
    private static final String PREF_ALLOW_FAST_ROADS = "allow_fast_roads";
    public static final String ACTION_START = "com.example.ciaobeeline.START_NAV";
    public static final String ACTION_STOP = "com.example.ciaobeeline.STOP_NAV";
    private static final String CHANNEL_ID = "ciao_beeline_navigation";
    private static final int NOTIFICATION_ID = 1001;

    // V0.19: scelta più veloce/più breve + toggle autostrade/superstrade + distanza senza blocco a 999 m
    private static final double OFF_ROUTE_RECALC_METERS = 14.0;
    private static final double OFF_ROUTE_WARN_METERS = 24.0;
    private static final long RECALC_COOLDOWN_MS = 1200;
    private static final long PERIODIC_RECALC_MS = 30000;
    private static final float GPS_BEARING_MIN_SPEED_KMH = 10.0f;
    private static final long SPEED_LIMIT_REFRESH_MS = 30000;
    private static final long HEARTBEAT_MS = 1000;
    private static final long CONTEXT_ROADS_REFRESH_MS = 30000;
    private static final long CONTEXT_ROADS_MIN_REFRESH_MS = 10000;
    private static final double CONTEXT_ROADS_REFRESH_DISTANCE_M = 140.0;
    private static final double CONTEXT_ROADS_QUERY_RADIUS_M = 300.0;
    // V0.21: keep the ORS geometry dense enough to preserve roundabouts and tight bends.
    // The base zoom is intentionally a little closer than V0.20; near a roundabout
    // we zoom in further so the individual exits remain distinguishable on 240x240.
    private static final double SCREEN_PIXELS_PER_METER = 1.00;
    private static final double ROUNDABOUT_PIXELS_PER_METER = 1.28;
    private static final double SCREEN_LOOKAHEAD_METERS = 230.0;
    private static final int MAX_SCREEN_POINTS = 320;

    private LocationManager locationManager;
    private Location currentLocation;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<LatLon> route = new ArrayList<>();
    private final ArrayList<Maneuver> maneuvers = new ArrayList<>();
    private final ArrayList<ArrayList<LatLon>> contextRoads = new ArrayList<>();

    private boolean running = false;
    private boolean routeRequestInProgress = false;
    private long lastRouteMs = 0;
    private long lastSendMs = 0;
    private double offRouteMeters = 9999;
    private int lastSpeedLimit = -1;
    private long lastSpeedLimitMs = 0;
    private boolean speedLimitRequestInProgress = false;
    private boolean contextRoadRequestInProgress = false;
    private long lastContextRoadsMs = 0;
    private double lastContextRoadsLat = Double.NaN;
    private double lastContextRoadsLon = Double.NaN;
    private long navSeq = 0;
    private LatLon lastDestination = null;
    private String lastDestinationText = "";
    private String apiKey = "";
    private String destinationText = "";
    private String routeMode = "fastest";
    private boolean allowFastRoads = false;
    private PowerManager.WakeLock wakeLock;

    // Keeps navigation updates alive even when Android reduces GPS callback frequency
    // after the phone display turns off. The foreground service + partial wake lock
    // remain the primary mechanism; this is a lightweight 1 Hz safety heartbeat.
    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            if (!running) return;

            try {
                Location gps = null;
                Location network = null;

                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    gps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                    network = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                }

                Location last = bestLocation(gps, network);
                if (last != null && (currentLocation == null || last.getTime() >= currentLocation.getTime())) {
                    currentLocation = last;
                }
            } catch (Exception ignored) {
            }

            if (currentLocation != null) {
                boolean empty;
                synchronized (route) { empty = route.isEmpty(); }

                if (empty) {
                    requestRoute(false);
                } else {
                    // Important on Redmi/MIUI: when the display is off, GPS callbacks can
                    // become less frequent even though the foreground service is alive.
                    // The heartbeat therefore performs the same off-route/reroute decision
                    // as a real LocationListener callback instead of merely repainting the
                    // old route. This fixes reroutes that only appeared after unlocking.
                    updateOffRouteDistance();
                    long now = System.currentTimeMillis();
                    boolean offRoute = offRouteMeters > OFF_ROUTE_RECALC_METERS;
                    boolean cooldownPassed = now - lastRouteMs > RECALC_COOLDOWN_MS;
                    boolean periodicRefresh = now - lastRouteMs > PERIODIC_RECALC_MS;

                    if ((offRoute && cooldownPassed) || periodicRefresh) {
                        requestRoute(true);
                    } else {
                        sendNavUpdate(false);
                    }
                }
            }

            if (running) handler.postDelayed(this, HEARTBEAT_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        createNotificationChannel();

        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CiaoBeeline:NavigationWakeLock");
            wakeLock.setReferenceCounted(false);
        } catch (Exception ignored) {
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;

        if (ACTION_STOP.equals(action)) {
            stopRouting();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        startForegroundCompat("Navigazione attiva", "GPS e invio dati al Carlyle attivi");
        startRouting();

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startRouting() {
        loadPrefsForService();
        running = true;
        routeRequestInProgress = false;
        acquireWakeLock();
        handler.removeCallbacks(heartbeat);
        handler.post(heartbeat);

        synchronized (route) { route.clear(); }
        synchronized (maneuvers) { maneuvers.clear(); }

        lastDestination = null;
        lastDestinationText = "";
        lastRouteMs = 0;
        lastSendMs = 0;
        offRouteMeters = 9999;
        lastSpeedLimit = -1;
        lastSpeedLimitMs = 0;
        speedLimitRequestInProgress = false;
        contextRoadRequestInProgress = false;
        lastContextRoadsMs = 0;
        lastContextRoadsLat = Double.NaN;
        lastContextRoadsLon = Double.NaN;
        synchronized (contextRoads) { contextRoads.clear(); }

        try {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 400, 0, listener);
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 700, 0, listener);
            }

            Location lastGps = null;
            Location lastNetwork = null;

            try {
                lastGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                lastNetwork = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            } catch (SecurityException ignored) {}

            currentLocation = bestLocation(lastGps, lastNetwork);

            if (currentLocation != null) {
                updateNotification("GPS disponibile", "Calcolo rotta...");
                requestRoute(true);
            } else {
                updateNotification("Navigazione attiva", "Attendo GPS del telefono...");
            }
        } catch (Exception e) {
            updateNotification("Errore GPS", String.valueOf(e.getMessage()));
        }
    }

    private Location bestLocation(Location a, Location b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.getTime() >= b.getTime() ? a : b;
    }

    private void stopRouting() {
        running = false;
        routeRequestInProgress = false;
        handler.removeCallbacks(heartbeat);
        releaseWakeLock();
        try { locationManager.removeUpdates(listener); } catch (Exception ignored) {}
        updateNotification("Navigazione fermata", "");
        sendToWear("{\"mode\":\"STOP\",\"speed\":0,\"dist\":0,\"turn\":\"STRAIGHT\",\"limit\":" + lastSpeedLimit + ",\"line\":\"120,140;120,70\"}");
    }


    @Override
    public void onDestroy() {
        stopRouting();
        super.onDestroy();
    }

    private final LocationListener listener = loc -> {
        currentLocation = loc;
        if (!running) return;

        long now = System.currentTimeMillis();

        if (route.isEmpty()) {
            requestRoute(true);
            return;
        }

        updateOffRouteDistance();

        boolean offRoute = offRouteMeters > OFF_ROUTE_RECALC_METERS;
        boolean cooldownPassed = now - lastRouteMs > RECALC_COOLDOWN_MS;
        boolean periodicRefresh = now - lastRouteMs > PERIODIC_RECALC_MS;

        if ((offRoute && cooldownPassed) || periodicRefresh) {
            requestRoute(true);
        } else {
            sendNavUpdate(false);
        }
    };

    private void updateOffRouteDistance() {
        if (currentLocation == null) return;

        ArrayList<LatLon> copy;
        synchronized (route) {
            copy = new ArrayList<>(route);
        }

        if (copy.isEmpty()) return;

        RouteMatch match = matchRoute(copy, currentLocation.getLatitude(), currentLocation.getLongitude());
        offRouteMeters = match.offMeters;
    }

    private void requestRoute(boolean forceStatus) {
        if (currentLocation == null) return;
        if (routeRequestInProgress) return;

        final String key = apiKey.trim();
        final String destinationText = this.destinationText.trim();

        if (key.length() < 8) {
            updateNotification("Errore", "Inserisci API key OpenRouteService.");
            return;
        }

        if (destinationText.length() < 3) {
            updateNotification("Errore", "Inserisci una destinazione.");
            return;
        }

        routeRequestInProgress = true;
        lastRouteMs = System.currentTimeMillis();

        if (forceStatus) {
            updateNotification("Ricalcolo rotta", "");
            sendToWear("{\"mode\":\"REROUTE\",\"speed\":0,\"dist\":0,\"turn\":\"STRAIGHT\",\"limit\":" + lastSpeedLimit + ",\"line\":\"120,140;120,105;120,70;120,40\"}");
        }

        new Thread(() -> {
            try {
                LatLon dest;
                if (lastDestination != null && destinationText.equals(lastDestinationText)) {
                    dest = lastDestination;
                } else {
                    dest = geocodeDestination(key, destinationText);
                    lastDestination = dest;
                    lastDestinationText = destinationText;
                }

                RouteResult result = requestDirections(key, dest);

                synchronized (route) {
                    route.clear();
                    route.addAll(result.points);
                }
                synchronized (maneuvers) {
                    maneuvers.clear();
                    maneuvers.addAll(result.maneuvers);
                }

                handler.post(() -> {
                    routeRequestInProgress = false;
                    updateNotification("Rotta aggiornata", destinationText + " - " + ("shortest".equals(routeMode) ? "breve" : "veloce") + (allowFastRoads ? " + strade veloci" : " no autostrade") + " - svolte: " + result.maneuvers.size());
                    sendNavUpdate(true);
                });
            } catch (Exception e) {
                handler.post(() -> {
                    routeRequestInProgress = false;
                    updateNotification("Errore routing", String.valueOf(e.getMessage()));
                });
            }
        }).start();
    }

    private LatLon geocodeDestination(String key, String destinationText) throws Exception {
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
        if (features.length() == 0) throw new RuntimeException("Destinazione non trovata. Prova con indirizzo più preciso.");

        JSONArray coords = features.getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates");
        return new LatLon(coords.getDouble(1), coords.getDouble(0));
    }

    private RouteResult requestDirections(String key, LatLon dest) throws Exception {
        URL url = new URL("https://api.heigit.org/openrouteservice/v2/directions/driving-car/geojson");

        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Authorization", key);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");

        String preference = "shortest".equals(routeMode) ? "shortest" : "fastest";

        // V0.19:
        // - PIÙ VELOCE / PIÙ BREVE cambiano la preference ORS.
        // - AUTOSTRADE / SUPERSTRADE: SÌ = non evita highways/tollways.
        // - AUTOSTRADE / SUPERSTRADE: NO = evita highways/tollways.
        String body;
        if (allowFastRoads) {
            body = "{\"coordinates\":[[" +
                    currentLocation.getLongitude() + "," + currentLocation.getLatitude() + "],[" +
                    dest.lon + "," + dest.lat + "]]," +
                    "\"preference\":\"" + preference + "\"}";
        } else {
            body = "{\"coordinates\":[[" +
                    currentLocation.getLongitude() + "," + currentLocation.getLatitude() + "],[" +
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

        ArrayList<LatLon> newRoute = new ArrayList<>();
        for (int i = 0; i < coords.length(); i++) {
            JSONArray p = coords.getJSONArray(i);
            newRoute.add(new LatLon(p.getDouble(1), p.getDouble(0)));
        }

        ArrayList<Maneuver> newManeuvers = new ArrayList<>();
        JSONArray segments = feature.getJSONObject("properties").optJSONArray("segments");

        if (segments != null) {
            for (int s = 0; s < segments.length(); s++) {
                JSONArray steps = segments.getJSONObject(s).optJSONArray("steps");
                if (steps == null) continue;

                for (int i = 0; i < steps.length(); i++) {
                    JSONObject step = steps.getJSONObject(i);
                    JSONArray wp = step.optJSONArray("way_points");
                    if (wp == null || wp.length() < 2) continue;

                    int startIndex = wp.optInt(0, 0);
                    int endIndex = wp.optInt(1, startIndex);
                    int type = step.optInt("type", -1);
                    double distance = step.optDouble("distance", 0);
                    String instruction = step.optString("instruction", "");
                    int exitNumber = step.optInt("exit_number", -1);

                    newManeuvers.add(new Maneuver(startIndex, endIndex, type, distance, instruction, exitNumber));
                }
            }
        }

        return new RouteResult(newRoute, newManeuvers);
    }

    private void sendNavUpdate(boolean recalculated) {
        if (currentLocation == null) return;

        long now = System.currentTimeMillis();
        if (!recalculated && now - lastSendMs < 500) return;
        lastSendMs = now;

        ArrayList<LatLon> copy;
        synchronized (route) { copy = new ArrayList<>(route); }
        ArrayList<Maneuver> manCopy;
        synchronized (maneuvers) { manCopy = new ArrayList<>(maneuvers); }

        if (copy.isEmpty()) {
            sendDemo();
            return;
        }

        RouteMatch match = matchRoute(copy, currentLocation.getLatitude(), currentLocation.getLongitude());
        int nearest = match.index;
        offRouteMeters = match.offMeters;

        float speedKmh = Math.max(0, currentLocation.getSpeed() * 3.6f);
        requestSpeedLimitIfNeeded(currentLocation);

        Maneuver next = nextManeuver(manCopy, nearest);
        String turn;
        int dist;
        int roundaboutExit = -1;

        if (next != null) {
            // ORS step instructions describe the maneuver at way_points[0] (the
            // beginning of the step), not at way_points[1]. The older code measured
            // to endIndex and could keep the previous arrow visible for kilometres.
            turn = validatedTurnForManeuver(copy, next);
            dist = distanceAlongRoute(copy, nearest, next.startIndex);
            if (dist < 0) dist = (int) Math.round(next.distance);
            if ("ROUND".equals(turn)) roundaboutExit = next.exitNumber;
        } else {
            turn = inferTurn(copy, nearest);
            dist = distanceToNextBend(copy, nearest);
        }

        // Nearby OSM road context is mainly useful around junctions/ramps. Start
        // refreshing it when the next manoeuvre is within roughly 1.2 km.
        if (dist <= 1200) requestContextRoadsIfNeeded(currentLocation);

        // Build the display polyline only after the next maneuver is known.
        // This allows a closer scale around roundabouts while still sending every
        // ORS geometry point available inside the visible look-ahead window.
        String line = buildScreenLine(copy, match, currentLocation, turn, dist);
        String roads = buildScreenRoads(copy, match, currentLocation, turn, dist);

        try {
            JSONObject o = new JSONObject();
            o.put("mode", offRouteMeters > OFF_ROUTE_WARN_METERS ? "OFF_ROUTE" : "NAV");
            o.put("seq", ++navSeq);
            o.put("recalculated", recalculated);
            o.put("speed", Math.round(speedKmh));
            o.put("dist", Math.max(0, Math.min(99999, dist)));
            o.put("turn", turn);
            o.put("limit", lastSpeedLimit);
            o.put("exit", roundaboutExit);
            o.put("line", line);
            o.put("roads", roads);

            sendToWear(o.toString());
            updateNotification(offRouteMeters > OFF_ROUTE_WARN_METERS ? "Fuori rotta" : "Navigazione attiva", Math.round(speedKmh) + " km/h - " + turn + " " + Math.round(Math.max(0, Math.min(99999, dist))) + " m - off " + Math.round(offRouteMeters) + " m" + (lastSpeedLimit > 0 ? " - lim " + lastSpeedLimit : ""));
        } catch (JSONException ignored) {}
    }

    private Maneuver nextManeuver(ArrayList<Maneuver> list, int nearestRouteIndex) {
        if (list.isEmpty()) return null;

        Maneuver straightFallback = null;

        for (Maneuver m : list) {
            // The instruction belongs to the START of this ORS step. Keep it visible
            // for a couple of dense geometry vertices around the junction, then move on.
            if (m.startIndex + 2 < nearestRouteIndex) continue;

            // 10 = goal, 11 = depart.
            if (m.type == 10 || m.type == 11) continue;

            // A straight instruction is useful only if there is no actual manoeuvre
            // ahead. Prefer the first real turn/keep/roundabout/U-turn.
            if (m.type == 6) {
                if (straightFallback == null) straightFallback = m;
                continue;
            }

            return m;
        }

        return straightFallback;
    }

    private String turnFromOpenRouteType(int type, String instruction) {
        String instr = instruction == null ? "" : instruction.toLowerCase();

        // Current ORS instruction encoding:
        // 0 left, 1 right, 2 sharp left, 3 sharp right,
        // 4 slight left, 5 slight right, 6 straight,
        // 7 enter roundabout, 8 exit roundabout, 9 U-turn,
        // 10 goal, 11 depart, 12 keep left, 13 keep right.
        if (type == 7 || type == 8 || instr.contains("roundabout") || instr.contains("rotatoria")) {
            return "ROUND";
        }
        if (type == 9 || instr.contains("u-turn") || instr.contains("inversione")) return "UTURN";
        if (type == 0 || type == 2 || type == 4 || type == 12 || instr.contains("left") || instr.contains("sinistra")) return "LEFT";
        if (type == 1 || type == 3 || type == 5 || type == 13 || instr.contains("right") || instr.contains("destra")) return "RIGHT";

        return "STRAIGHT";
    }

    private String validatedTurnForManeuver(ArrayList<LatLon> pts, Maneuver m) {
        String orsTurn = turnFromOpenRouteType(m.type, m.instruction);
        if ("ROUND".equals(orsTurn) || "UTURN".equals(orsTurn) || "STRAIGHT".equals(orsTurn)) return orsTurn;
        if (pts == null || pts.size() < 5) return orsTurn;

        int j = Math.max(1, Math.min(m.startIndex, pts.size() - 2));
        int before = Math.max(0, j - 4);
        int after = Math.min(pts.size() - 1, j + 5);
        if (before == j || after == j) return orsTurn;

        double inBearing = bearing(pts.get(before).lat, pts.get(before).lon, pts.get(j).lat, pts.get(j).lon);
        double outBearing = bearing(pts.get(j).lat, pts.get(j).lon, pts.get(after).lat, pts.get(after).lon);
        double delta = angleDiff(inBearing, outBearing);

        // Only override the textual/type instruction when the route geometry itself
        // shows a clear turn in the opposite direction. Small ramp/keep angles retain
        // the authoritative ORS instruction.
        if (delta >= 28.0 && "LEFT".equals(orsTurn)) return "RIGHT";
        if (delta <= -28.0 && "RIGHT".equals(orsTurn)) return "LEFT";
        return orsTurn;
    }

    private int distanceAlongRoute(ArrayList<LatLon> pts, int from, int to) {
        if (pts.isEmpty()) return -1;
        int start = Math.max(0, Math.min(from, pts.size() - 1));
        int end = Math.max(0, Math.min(to, pts.size() - 1));
        if (end <= start) return 0;

        double acc = 0;
        for (int i = start; i < end; i++) {
            acc += distanceMeters(pts.get(i).lat, pts.get(i).lon, pts.get(i + 1).lat, pts.get(i + 1).lon);
            if (acc > 99999) return 99999;
        }
        return (int) Math.round(acc);
    }

    private void requestContextRoadsIfNeeded(Location loc) {
        if (loc == null || contextRoadRequestInProgress) return;

        long now = System.currentTimeMillis();
        boolean movedFar = Double.isNaN(lastContextRoadsLat) ||
                distanceMeters(lastContextRoadsLat, lastContextRoadsLon,
                        loc.getLatitude(), loc.getLongitude()) >= CONTEXT_ROADS_REFRESH_DISTANCE_M;
        long age = now - lastContextRoadsMs;
        if (age < CONTEXT_ROADS_MIN_REFRESH_MS) return;
        if (!movedFar && age < CONTEXT_ROADS_REFRESH_MS) return;

        contextRoadRequestInProgress = true;
        lastContextRoadsMs = now;
        final double lat = loc.getLatitude();
        final double lon = loc.getLongitude();
        // Remember the attempted centre even if Overpass is temporarily unavailable,
        // otherwise a failed request would be retried every 1 Hz heartbeat.
        lastContextRoadsLat = lat;
        lastContextRoadsLon = lon;

        new Thread(() -> {
            ArrayList<ArrayList<LatLon>> found = new ArrayList<>();
            try {
                found = requestNearbyRoadGeometry(lat, lon);
            } catch (Exception ignored) {
            }

            final ArrayList<ArrayList<LatLon>> result = found;
            handler.post(() -> {
                contextRoadRequestInProgress = false;
                if (!result.isEmpty()) {
                    synchronized (contextRoads) {
                        contextRoads.clear();
                        contextRoads.addAll(result);
                    }
                    // Push the newly available grey road context to the Carlyle without
                    // waiting for the next GPS callback.
                    if (running) sendNavUpdate(false);
                }
            });
        }).start();
    }

    private ArrayList<ArrayList<LatLon>> requestNearbyRoadGeometry(double lat, double lon) throws Exception {
        URL url = new URL("https://overpass-api.de/api/interpreter");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(6000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");

        String highwayRegex = "motorway|motorway_link|trunk|trunk_link|primary|primary_link|secondary|secondary_link|tertiary|tertiary_link|residential|service|unclassified|road";
        String query = "[out:json][timeout:5];" +
                "way(around:" + (int) CONTEXT_ROADS_QUERY_RADIUS_M + "," + lat + "," + lon + ")" +
                "[\"highway\"~\"^(" + highwayRegex + ")$\"];" +
                "out geom;";
        String body = "data=" + URLEncoder.encode(query, "UTF-8");

        try (OutputStream os = c.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }

        InputStream is = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
        String txt = readAll(is);
        if (c.getResponseCode() >= 400) return new ArrayList<>();

        JSONObject json = new JSONObject(txt);
        JSONArray elements = json.optJSONArray("elements");
        ArrayList<ArrayList<LatLon>> out = new ArrayList<>();
        if (elements == null) return out;

        for (int i = 0; i < elements.length() && out.size() < 28; i++) {
            JSONObject e = elements.optJSONObject(i);
            if (e == null) continue;
            JSONArray geometry = e.optJSONArray("geometry");
            if (geometry == null || geometry.length() < 2) continue;

            ArrayList<LatLon> way = new ArrayList<>();
            int stride = Math.max(1, geometry.length() / 90);
            for (int g = 0; g < geometry.length(); g += stride) {
                JSONObject p = geometry.optJSONObject(g);
                if (p == null) continue;
                double plat = p.optDouble("lat", Double.NaN);
                double plon = p.optDouble("lon", Double.NaN);
                if (Double.isNaN(plat) || Double.isNaN(plon)) continue;
                if (distanceMeters(lat, lon, plat, plon) <= 330.0) {
                    way.add(new LatLon(plat, plon));
                }
            }
            if (way.size() >= 2) out.add(way);
        }
        return out;
    }

    private String buildScreenRoads(ArrayList<LatLon> routePts, RouteMatch match, Location loc,
                                    String turn, int distToTurn) {
        ArrayList<ArrayList<LatLon>> ways = new ArrayList<>();
        synchronized (contextRoads) {
            for (ArrayList<LatLon> way : contextRoads) ways.add(new ArrayList<>(way));
        }
        if (ways.isEmpty() || routePts.size() < 2) return "";

        int idx = Math.max(0, Math.min(match.index, routePts.size() - 1));
        double lat0 = match.lat;
        double lon0 = match.lon;
        double lat0Rad = Math.toRadians(lat0);
        double metersPerDegLat = 111320.0;
        double metersPerDegLon = Math.max(1.0, Math.cos(lat0Rad) * 111320.0);

        int headingEnd = Math.min(idx + 4, routePts.size() - 1);
        LatLon headingPoint = routePts.get(headingEnd);
        double head = bearing(lat0, lon0, headingPoint.lat, headingPoint.lon);
        float speedKmh = Math.max(0, loc.getSpeed() * 3.6f);
        if (loc.hasBearing() && speedKmh >= GPS_BEARING_MIN_SPEED_KMH) {
            double gpsHead = loc.getBearing();
            if (Math.abs(angleDiff(head, gpsHead)) < 55.0) head = gpsHead;
        }

        double h = Math.toRadians(head);
        double sinH = Math.sin(h);
        double cosH = Math.cos(h);
        double pixelsPerMeter = ("ROUND".equals(turn) && distToTurn >= 0 && distToTurn <= 140)
                ? ROUNDABOUT_PIXELS_PER_METER : SCREEN_PIXELS_PER_METER;

        StringBuilder out = new StringBuilder(6000);
        int emittedWays = 0;
        for (ArrayList<LatLon> way : ways) {
            StringBuilder one = new StringBuilder();
            int visiblePoints = 0;
            for (LatLon p : way) {
                double east = (p.lon - lon0) * metersPerDegLon;
                double north = (p.lat - lat0) * metersPerDegLat;
                double right = east * cosH - north * sinH;
                double forward = east * sinH + north * cosH;
                double sx = 120.0 + right * pixelsPerMeter;
                double sy = 164.0 - forward * pixelsPerMeter;

                // Keep a generous margin so roads crossing the round screen remain
                // continuous, but do not waste Bluetooth payload on remote geometry.
                if (sx < -90 || sx > 330 || sy < -90 || sy > 330) continue;
                if (one.length() > 0) one.append(';');
                one.append(Math.round(sx * 10.0) / 10.0).append(',')
                        .append(Math.round(sy * 10.0) / 10.0);
                visiblePoints++;
                if (visiblePoints >= 70) break;
            }
            if (visiblePoints >= 2) {
                if (out.length() > 0) out.append('|');
                out.append(one);
                emittedWays++;
                if (emittedWays >= 22 || out.length() > 11000) break;
            }
        }
        return out.toString();
    }

    private void requestSpeedLimitIfNeeded(Location loc) {
        if (loc == null) return;

        long now = System.currentTimeMillis();

        if (speedLimitRequestInProgress) return;
        if (now - lastSpeedLimitMs < SPEED_LIMIT_REFRESH_MS) return;

        speedLimitRequestInProgress = true;
        lastSpeedLimitMs = now;

        final double lat = loc.getLatitude();
        final double lon = loc.getLongitude();

        new Thread(() -> {
            int found = -1;

            try {
                found = requestSpeedLimitFromOsm(lat, lon);
            } catch (Exception ignored) {
            }

            final int result = found;

            handler.post(() -> {
                speedLimitRequestInProgress = false;

                if (result > 0) {
                    lastSpeedLimit = result;
                }
            });
        }).start();
    }

    private int requestSpeedLimitFromOsm(double lat, double lon) throws Exception {
        URL url = new URL("https://overpass-api.de/api/interpreter");

        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");

        String query =
                "[out:json][timeout:5];" +
                "way(around:60," + lat + "," + lon + ")[\"highway\"][\"maxspeed\"];" +
                "out tags 8;";

        String body = "data=" + URLEncoder.encode(query, "UTF-8");

        try (OutputStream os = c.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }

        InputStream is = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
        String txt = readAll(is);

        if (c.getResponseCode() >= 400) {
            return -1;
        }

        JSONObject json = new JSONObject(txt);
        JSONArray elements = json.optJSONArray("elements");

        if (elements == null || elements.length() == 0) {
            return -1;
        }

        for (int i = 0; i < elements.length(); i++) {
            JSONObject tags = elements.getJSONObject(i).optJSONObject("tags");

            if (tags == null) continue;

            String raw = tags.optString("maxspeed", "");
            int parsed = parseSpeedLimit(raw);

            if (parsed > 0) {
                return parsed;
            }
        }

        return -1;
    }

    private int parseSpeedLimit(String raw) {
        if (raw == null) return -1;

        String s = raw.trim().toLowerCase();

        if (s.length() == 0) return -1;
        if (s.contains("signals")) return -1;
        if (s.contains("none")) return -1;
        if (s.contains("walk")) return -1;

        boolean mph = s.contains("mph");

        StringBuilder digits = new StringBuilder();

        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);

            if (ch >= '0' && ch <= '9') {
                digits.append(ch);
            } else if (digits.length() > 0) {
                break;
            }
        }

        if (digits.length() == 0) return -1;

        try {
            int value = Integer.parseInt(digits.toString());

            if (mph) {
                value = (int) Math.round(value * 1.60934);
            }

            if (value < 5 || value > 140) return -1;

            return value;
        } catch (Exception ignored) {
            return -1;
        }
    }

    private void sendDemo() {
        String demoJson = "{\"mode\":\"NAV\",\"speed\":36,\"dist\":300,\"turn\":\"RIGHT\",\"limit\":50,\"line\":\"120,140;120,110;145,92;145,60;105,40\"}";
        sendToWear(demoJson);
        updateNotification("Demo inviata", "Carlyle aggiornato.");
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

    private void loadPrefsForService() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        apiKey = p.getString(PREF_API_KEY, "");
        destinationText = p.getString(PREF_DESTINATION, "");
        routeMode = p.getString(PREF_ROUTE_MODE, "fastest");
        allowFastRoads = p.getBoolean(PREF_ALLOW_FAST_ROADS, false);
    }

    private void acquireWakeLock() {
        try {
            if (wakeLock != null && !wakeLock.isHeld()) {
                // Navigation can legitimately last more than three hours.
                // Release is explicit in stopRouting()/onDestroy().
                wakeLock.acquire();
            }
        } catch (Exception ignored) {
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception ignored) {
        }
    }

    private void createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Ciao Beeline Navigation",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Navigazione GPS attiva verso Carlyle");

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private void startForegroundCompat(String title, String text) {
        Notification notification = buildNotification(title, text);

        if (android.os.Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification(String title, String text) {
        try {
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.notify(NOTIFICATION_ID, buildNotification(title, text));
            }
        } catch (Exception ignored) {
        }
    }

    private Notification buildNotification(String title, String text) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                android.os.Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0
        );

        Intent stopIntent = new Intent(this, NavigationService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this,
                1,
                stopIntent,
                android.os.Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0
        );

        Notification.Builder builder;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        builder
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .setContentIntent(openPendingIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent);

        return builder.build();
    }

    private static String readAll(InputStream is) throws IOException {
        BufferedReader br = new BufferedReader(new InputStreamReader(is));
        StringBuilder sb = new StringBuilder();
        String l;
        while ((l = br.readLine()) != null) sb.append(l);
        return sb.toString();
    }

    private static RouteMatch matchRoute(ArrayList<LatLon> pts, double lat, double lon) {
        if (pts == null || pts.isEmpty()) {
            return new RouteMatch(0, lat, lon, 9999);
        }

        if (pts.size() == 1) {
            double d = distanceMeters(lat, lon, pts.get(0).lat, pts.get(0).lon);
            return new RouteMatch(0, pts.get(0).lat, pts.get(0).lon, d);
        }

        double best = 1e18;
        int bestIndex = 0;
        double bestLat = pts.get(0).lat;
        double bestLon = pts.get(0).lon;

        double latRad = Math.toRadians(lat);
        double metersPerDegLat = 111320.0;
        double metersPerDegLon = Math.cos(latRad) * 111320.0;

        for (int i = 0; i < pts.size() - 1; i++) {
            LatLon a = pts.get(i);
            LatLon b = pts.get(i + 1);

            double ax = (a.lon - lon) * metersPerDegLon;
            double ay = (a.lat - lat) * metersPerDegLat;
            double bx = (b.lon - lon) * metersPerDegLon;
            double by = (b.lat - lat) * metersPerDegLat;

            double vx = bx - ax;
            double vy = by - ay;
            double len2 = vx * vx + vy * vy;

            double t = 0;

            if (len2 > 0.001) {
                t = -(ax * vx + ay * vy) / len2;
                if (t < 0) t = 0;
                if (t > 1) t = 1;
            }

            double px = ax + vx * t;
            double py = ay + vy * t;
            double d = Math.sqrt(px * px + py * py);

            if (d < best) {
                best = d;
                bestIndex = i;
                bestLat = a.lat + (b.lat - a.lat) * t;
                bestLon = a.lon + (b.lon - a.lon) * t;
            }
        }

        return new RouteMatch(bestIndex, bestLat, bestLon, best);
    }

    private static int nearestIndex(ArrayList<LatLon> pts, double lat, double lon) {
        double best = 1e18;
        int idx = 0;
        for (int i = 0; i < pts.size(); i++) {
            double d = distanceMeters(lat, lon, pts.get(i).lat, pts.get(i).lon);
            if (d < best) {
                best = d;
                idx = i;
            }
        }
        return idx;
    }

    private static double distanceMeters(double la1, double lo1, double la2, double lo2) {
        double R = 6371000;
        double p1 = Math.toRadians(la1);
        double p2 = Math.toRadians(la2);
        double dp = Math.toRadians(la2 - la1);
        double dl = Math.toRadians(lo2 - lo1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) +
                Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static double bearing(double la1, double lo1, double la2, double lo2) {
        double y = Math.sin(Math.toRadians(lo2 - lo1)) * Math.cos(Math.toRadians(la2));
        double x = Math.cos(Math.toRadians(la1)) * Math.sin(Math.toRadians(la2)) -
                Math.sin(Math.toRadians(la1)) * Math.cos(Math.toRadians(la2)) * Math.cos(Math.toRadians(lo2 - lo1));
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    private static double angleDiff(double a, double b) {
        return (b - a + 540) % 360 - 180;
    }

    private static double routeHeading(ArrayList<LatLon> pts, int idx) {
        int a = Math.min(idx + 1, pts.size() - 1);
        int b = Math.min(idx + 6, pts.size() - 1);
        if (a == b) return 0;
        return bearing(pts.get(a).lat, pts.get(a).lon, pts.get(b).lat, pts.get(b).lon);
    }

    private static String inferTurn(ArrayList<LatLon> pts, int idx) {
        if (idx + 8 >= pts.size()) return "STRAIGHT";
        int a = Math.min(idx + 3, pts.size() - 1);
        int b = Math.min(idx + 8, pts.size() - 1);
        double b1 = bearing(pts.get(idx).lat, pts.get(idx).lon, pts.get(a).lat, pts.get(a).lon);
        double b2 = bearing(pts.get(a).lat, pts.get(a).lon, pts.get(b).lat, pts.get(b).lon);
        double d = angleDiff(b1, b2);
        if (d > 35) return "RIGHT";
        if (d < -35) return "LEFT";
        return "STRAIGHT";
    }

    private static int distanceToNextBend(ArrayList<LatLon> pts, int idx) {
        double acc = 0;

        for (int i = idx; i < pts.size() - 8; i++) {
            double b1 = bearing(pts.get(i).lat, pts.get(i).lon, pts.get(i + 3).lat, pts.get(i + 3).lon);
            double b2 = bearing(pts.get(i + 3).lat, pts.get(i + 3).lon, pts.get(i + 8).lat, pts.get(i + 8).lon);

            if (Math.abs(angleDiff(b1, b2)) > 35) {
                return (int) Math.max(20, Math.min(99999, acc));
            }

            acc += distanceMeters(pts.get(i).lat, pts.get(i).lon, pts.get(i + 1).lat, pts.get(i + 1).lon);

            if (acc > 99999) {
                return 99999;
            }
        }

        return (int) Math.min(99999, acc);
    }

    private static String buildScreenLine(ArrayList<LatLon> pts, RouteMatch match, Location loc, String turn, int distToTurn) {
        int idx = Math.max(0, Math.min(match.index, pts.size() - 1));

        if (pts.size() < 2 || idx >= pts.size() - 1) {
            return "120,164;120,90";
        }

        /*
         * V0.21 high-fidelity geometry:
         * - use every ORS geometry point in the local look-ahead window;
         * - no distance-based point thinning (V0.20 still skipped points < 1.8 m);
         * - project lat/lon to local metric east/north coordinates;
         * - rotate once into heading-up coordinates, without reshaping the path;
         * - keep sub-pixel precision in the transmitted coordinates;
         * - never clamp points to the round screen edge;
         * - zoom in slightly when approaching a roundabout so its circle/exits are
         *   readable instead of collapsing under the route stroke.
         */
        double lat0 = match.lat;
        double lon0 = match.lon;
        double lat0Rad = Math.toRadians(lat0);
        double metersPerDegLat = 111320.0;
        double metersPerDegLon = Math.max(1.0, Math.cos(lat0Rad) * 111320.0);

        // Use a short forward baseline for heading. A single next coordinate can be
        // extremely close on dense geometry and cause unnecessary orientation jitter.
        int headingEnd = Math.min(idx + 4, pts.size() - 1);
        LatLon headingPoint = pts.get(headingEnd);
        double head = bearing(lat0, lon0, headingPoint.lat, headingPoint.lon);

        float speedKmh = Math.max(0, loc.getSpeed() * 3.6f);
        if (loc.hasBearing() && speedKmh >= GPS_BEARING_MIN_SPEED_KMH) {
            double gpsHead = loc.getBearing();
            double diff = Math.abs(angleDiff(head, gpsHead));
            if (diff < 55.0) head = gpsHead;
        }

        double h = Math.toRadians(head);
        double sinH = Math.sin(h);
        double cosH = Math.cos(h);

        final double originX = 120.0;
        final double originY = 164.0;

        double pixelsPerMeter = SCREEN_PIXELS_PER_METER;
        if ("ROUND".equals(turn) && distToTurn >= 0 && distToTurn <= 140) {
            pixelsPerMeter = ROUNDABOUT_PIXELS_PER_METER;
        }

        StringBuilder sb = new StringBuilder(4096);
        sb.append("120,164");

        int added = 1;
        double walked = 0.0;
        LatLon prev = new LatLon(lat0, lon0);

        for (int i = idx + 1; i < pts.size() && added < MAX_SCREEN_POINTS; i++) {
            LatLon p = pts.get(i);

            walked += distanceMeters(prev.lat, prev.lon, p.lat, p.lon);
            prev = p;

            double east = (p.lon - lon0) * metersPerDegLon;
            double north = (p.lat - lat0) * metersPerDegLat;

            // Heading-up: right is +x, forward is -y on the display.
            double right = east * cosH - north * sinH;
            double forward = east * sinH + north * cosH;

            double sx = originX + right * pixelsPerMeter;
            double sy = originY - forward * pixelsPerMeter;

            // Keep one decimal place instead of integer-rounding every vertex. On a
            // small roundabout this preserves visibly more of the original curvature.
            appendScreenPoint(sb, sx, sy);
            added++;

            if (walked >= SCREEN_LOOKAHEAD_METERS && added > 12) break;
        }

        if (added < 2) sb.append(";120,90");
        return sb.toString();
    }

    private static void appendScreenPoint(StringBuilder sb, double x, double y) {
        double rx = Math.round(x * 10.0) / 10.0;
        double ry = Math.round(y * 10.0) / 10.0;
        sb.append(';').append(rx).append(',').append(ry);
    }


    static class RouteMatch {
        int index;
        double lat;
        double lon;
        double offMeters;

        RouteMatch(int index, double lat, double lon, double offMeters) {
            this.index = index;
            this.lat = lat;
            this.lon = lon;
            this.offMeters = offMeters;
        }
    }

    static class LatLon {
        double lat;
        double lon;
        LatLon(double a, double b) { lat = a; lon = b; }
    }

    static class Maneuver {
        int startIndex;
        int endIndex;
        int type;
        double distance;
        String instruction;
        int exitNumber;
        Maneuver(int startIndex, int endIndex, int type, double distance, String instruction, int exitNumber) {
            this.startIndex = startIndex;
            this.endIndex = endIndex;
            this.type = type;
            this.distance = distance;
            this.instruction = instruction;
            this.exitNumber = exitNumber;
        }
    }

    static class RouteResult {
        ArrayList<LatLon> points;
        ArrayList<Maneuver> maneuvers;
        RouteResult(ArrayList<LatLon> points, ArrayList<Maneuver> maneuvers) {
            this.points = points;
            this.maneuvers = maneuvers;
        }
    }
}
