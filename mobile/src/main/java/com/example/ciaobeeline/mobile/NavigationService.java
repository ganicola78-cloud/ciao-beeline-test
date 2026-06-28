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
    public static final String ACTION_START = "com.example.ciaobeeline.START_NAV";
    public static final String ACTION_STOP = "com.example.ciaobeeline.STOP_NAV";
    private static final String CHANNEL_ID = "ciao_beeline_navigation";
    private static final int NOTIFICATION_ID = 1001;

    // V0.8: svolte vere ORS + OSM speed limit + linea agganciata alla freccia
    private static final double OFF_ROUTE_RECALC_METERS = 22.0;
    private static final double OFF_ROUTE_WARN_METERS = 35.0;
    private static final long RECALC_COOLDOWN_MS = 3000;
    private static final long PERIODIC_RECALC_MS = 60000;
    private static final float GPS_BEARING_MIN_SPEED_KMH = 14.0f;
    private static final long SPEED_LIMIT_REFRESH_MS = 30000;

    private LocationManager locationManager;
    private Location currentLocation;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<LatLon> route = new ArrayList<>();
    private final ArrayList<Maneuver> maneuvers = new ArrayList<>();

    private boolean running = false;
    private boolean routeRequestInProgress = false;
    private long lastRouteMs = 0;
    private long lastSendMs = 0;
    private double offRouteMeters = 9999;
    private int lastSpeedLimit = -1;
    private long lastSpeedLimitMs = 0;
    private boolean speedLimitRequestInProgress = false;
    private long navSeq = 0;
    private LatLon lastDestination = null;
    private String lastDestinationText = "";
    private String apiKey = "";
    private String destinationText = "";
    private PowerManager.WakeLock wakeLock;

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

        try {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 700, 1, listener);
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000, 1, listener);
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
        synchronized (route) { copy = new ArrayList<>(route); }
        if (copy.isEmpty()) return;
        int nearest = nearestIndex(copy, currentLocation.getLatitude(), currentLocation.getLongitude());
        offRouteMeters = distanceMeters(copy.get(nearest).lat, copy.get(nearest).lon,
                currentLocation.getLatitude(), currentLocation.getLongitude());
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
                    updateNotification("Rotta aggiornata", destinationText + " - svolte: " + result.maneuvers.size());
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
        URL url = new URL("https://api.openrouteservice.org/geocode/search?api_key=" + key + "&text=" + encoded + "&size=1");

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
        URL url = new URL("https://api.openrouteservice.org/v2/directions/driving-car/geojson");

        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Authorization", key);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");

        String body = "{\"coordinates\":[[" +
                currentLocation.getLongitude() + "," + currentLocation.getLatitude() + "],[" +
                dest.lon + "," + dest.lat + "]]," +
                "\"preference\":\"shortest\"," +
                "\"options\":{\"avoid_features\":[\"highways\",\"tollways\"]}}";

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

                    newManeuvers.add(new Maneuver(startIndex, endIndex, type, distance, instruction));
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

        int nearest = nearestIndex(copy, currentLocation.getLatitude(), currentLocation.getLongitude());
        offRouteMeters = distanceMeters(copy.get(nearest).lat, copy.get(nearest).lon,
                currentLocation.getLatitude(), currentLocation.getLongitude());

        float speedKmh = Math.max(0, currentLocation.getSpeed() * 3.6f);
        requestSpeedLimitIfNeeded(currentLocation);
        String line = buildScreenLine(copy, nearest, currentLocation);

        Maneuver next = nextManeuver(manCopy, nearest);
        String turn;
        int dist;

        if (next != null) {
            turn = turnFromOpenRouteType(next.type);
            dist = distanceAlongRoute(copy, nearest, next.endIndex);
            if (dist < 0) dist = (int) Math.round(next.distance);
        } else {
            turn = inferTurn(copy, nearest);
            dist = distanceToNextBend(copy, nearest);
        }

        try {
            JSONObject o = new JSONObject();
            o.put("mode", offRouteMeters > OFF_ROUTE_WARN_METERS ? "OFF_ROUTE" : "NAV");
            o.put("seq", ++navSeq);
            o.put("recalculated", recalculated);
            o.put("speed", Math.round(speedKmh));
            o.put("dist", Math.max(0, Math.min(999, dist)));
            o.put("turn", turn);
            o.put("limit", lastSpeedLimit);
            o.put("line", line);

            sendToWear(o.toString());
            updateNotification("Navigazione attiva", Math.round(speedKmh) + " km/h - " + turn + " " + Math.round(Math.max(0, Math.min(999, dist))) + " m - off " + Math.round(offRouteMeters) + " m" + (lastSpeedLimit > 0 ? " - lim " + lastSpeedLimit : ""));
        } catch (JSONException ignored) {}
    }

    private Maneuver nextManeuver(ArrayList<Maneuver> list, int nearestRouteIndex) {
        if (list.isEmpty()) return null;
        for (Maneuver m : list) {
            // type 11 è spesso la partenza: non mostrarlo come prossima svolta.
            if (m.endIndex > nearestRouteIndex + 1 && m.type != 11) return m;
        }
        return null;
    }

    private String turnFromOpenRouteType(int type) {
        // ORS: 0 left, 1 right, 2 sharp left, 3 sharp right,
        // 4 slight left, 5 slight right, 6 straight,
        // 7 enter roundabout, 8 exit roundabout, 10 destination, 11 depart.
        if (type == 0 || type == 2 || type == 4) return "LEFT";
        if (type == 1 || type == 3 || type == 5) return "RIGHT";
        if (type == 7 || type == 8) return "ROUND";
        return "STRAIGHT";
    }

    private int distanceAlongRoute(ArrayList<LatLon> pts, int from, int to) {
        if (pts.isEmpty()) return -1;
        int start = Math.max(0, Math.min(from, pts.size() - 1));
        int end = Math.max(0, Math.min(to, pts.size() - 1));
        if (end <= start) return 0;

        double acc = 0;
        for (int i = start; i < end; i++) {
            acc += distanceMeters(pts.get(i).lat, pts.get(i).lon, pts.get(i + 1).lat, pts.get(i + 1).lon);
            if (acc > 999) return 999;
        }
        return (int) Math.round(acc);
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
    }

    private void acquireWakeLock() {
        try {
            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire(3 * 60 * 60 * 1000L);
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
            if (Math.abs(angleDiff(b1, b2)) > 35) return (int) Math.max(20, acc);
            acc += distanceMeters(pts.get(i).lat, pts.get(i).lon, pts.get(i + 1).lat, pts.get(i + 1).lon);
            if (acc > 999) break;
        }
        return (int) Math.min(999, acc);
    }

    private static String buildScreenLine(ArrayList<LatLon> pts, int idx, Location loc) {
        if (idx >= pts.size()) return "120,140;120,70";

        float speedKmh = Math.max(0, loc.getSpeed() * 3.6f);
        double head;

        if (loc.hasBearing() && speedKmh >= GPS_BEARING_MIN_SPEED_KMH) {
            head = loc.getBearing();
        } else {
            head = routeHeading(pts, idx);
        }

        StringBuilder sb = new StringBuilder();

        // V0.8: la linea parte sempre esattamente dalla freccia sul Carlyle.
        // Usiamo il punto più vicino della rotta come "origine agganciata",
        // così il GPS leggermente laterale non fa sembrare la freccia fuori strada.
        LatLon origin = pts.get(Math.max(0, Math.min(idx, pts.size() - 1)));
        double lat0 = origin.lat;
        double lon0 = origin.lon;

        int added = 0;

        sb.append("120,140");
        added++;

        for (int i = Math.max(idx + 1, 0); i < pts.size() && added < 24; i++) {
            LatLon p = pts.get(i);
            double dist = distanceMeters(lat0, lon0, p.lat, p.lon);

            if (dist < 5) continue;
            if (dist > 280 && added > 5) break;

            double br = bearing(lat0, lon0, p.lat, p.lon);
            double rel = Math.toRadians(angleDiff(head, br));
            double x = Math.sin(rel) * dist;
            double y = Math.cos(rel) * dist;

            int sx = (int) Math.round(120 + x * 0.58);
            int sy = (int) Math.round(140 - y * 0.58);

            sx = Math.max(18, Math.min(222, sx));
            sy = Math.max(18, Math.min(148, sy));

            sb.append(';').append(sx).append(',').append(sy);
            added++;
        }

        if (added < 2) {
            sb.append(";120,70");
        }

        return sb.toString();
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
        Maneuver(int startIndex, int endIndex, int type, double distance, String instruction) {
            this.startIndex = startIndex;
            this.endIndex = endIndex;
            this.type = type;
            this.distance = distance;
            this.instruction = instruction;
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
