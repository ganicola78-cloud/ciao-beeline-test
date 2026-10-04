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
    private static final String PREF_NAV_SOURCE = "nav_source_v1";
    private static final String PREF_NAV_TARGET_LAT = "nav_target_lat_v1";
    private static final String PREF_NAV_TARGET_LON = "nav_target_lon_v1";
    private static final String PREF_NAV_TARGET_LABEL = "nav_target_label_v1";
    private static final String PREF_NAV_ROUTE_POINTS = "nav_route_points_v1";
    private static final String PREF_LIVE_ROUTE_GEOMETRY = "live_route_geometry_v1";
    private static final String PREF_LIVE_ROUTE_REV = "live_route_rev_v1";
    public static final String ACTION_START = "com.example.ciaobeeline.START_NAV";
    public static final String ACTION_STOP = "com.example.ciaobeeline.STOP_NAV";
    public static final String ACTION_REROUTE = "com.example.ciaobeeline.REROUTE_NAV";
    public static final String ACTION_CLEAR_ROUTE = "com.example.ciaobeeline.CLEAR_ROUTE";
    private static final String CHANNEL_ID = "ciao_beeline_navigation";
    private static final int NOTIFICATION_ID = 1001;

    // V0.29: keep V0.28 GPS/map tolerance unchanged, but recognise a real wrong turn
    // faster by combining distance from the route, movement direction and distance trend.
    private static final double OFF_ROUTE_RESET_METERS = 25.0;
    private static final double OFF_ROUTE_CONFIRM_METERS = 35.0;
    private static final long OFF_ROUTE_CONFIRM_MS = 2500;
    private static final long AUTO_REROUTE_COOLDOWN_MS = 10000;

    // Fast reroute: used only when GPS bearing is reliable. It does NOT reduce the
    // visual/map-matching tolerance; it only shortens the time needed to confirm a real deviation.
    private static final double FAST_DEVIATION_MIN_METERS = 25.0;
    private static final long FAST_DEVIATION_CONFIRM_MS = 1200;
    private static final double FAST_HEADING_DIFF_DEG = 45.0;
    private static final double FAST_DISTANCE_GROWTH_METERS = 2.0;

    // V0.32: generic wrong-road detector independent of ORS maneuver type. This catches
    // the common case where the rider misses a junction but the maneuver list/bearing
    // metadata is incomplete. It does not alter the 20-25 m visual snap tolerance.
    private static final double NEAR_WRONG_ROAD_MIN_METERS = 9.0;
    private static final double NEAR_WRONG_ROAD_HEADING_DIFF_DEG = 55.0;
    private static final long NEAR_WRONG_ROAD_CONFIRM_MS = 900;
    private static final int NEAR_WRONG_ROAD_MIN_SAMPLES = 2;

    // Missed-turn detector: when we are essentially at the manoeuvre, moving in a
    // direction incompatible with the route and already drifting away, confirm sooner.
    private static final double MISSED_TURN_MIN_METERS = 7.0;
    private static final double MISSED_TURN_ROUTE_DISTANCE_METERS = 30.0;
    private static final double MISSED_TURN_HEADING_DIFF_DEG = 45.0;
    private static final long MISSED_TURN_CONFIRM_MS = 600;
    private static final double MISSED_TURN_MOVING_AWAY_METERS = 4.0;
    private static final int MISSED_TURN_MIN_SAMPLES = 2;

    // A very clear deviation gets an even shorter confirmation, still requiring a
    // reliable moving bearing so that one poor stationary GPS fix cannot consume quota.
    private static final double HARD_DEVIATION_METERS = 50.0;
    private static final long HARD_DEVIATION_CONFIRM_MS = 600;
    private static final double FAST_GPS_MAX_ACCURACY_METERS = 30.0;
    private static final float GPS_BEARING_MIN_SPEED_KMH = 10.0f;
    private static final long SPEED_LIMIT_REFRESH_MS = 30000;
    private static final long HEARTBEAT_MS = 1000;
    // V0.30: the heartbeat is only a fallback. When real GPS callbacks are arriving,
    // avoid doing the same route matching / Wear transfer twice.
    private static final long HEARTBEAT_FALLBACK_AFTER_MS = 900;
    // Prefer real GPS fixes over NETWORK_PROVIDER fixes, which often have no useful speed
    // and were able to overwrite the current GPS speed in previous versions.
    private static final long FRESH_GPS_PRIORITY_MS = 2500;
    private static final long NOTIFICATION_REFRESH_MS = 2000;
    private static final long CONTEXT_ROADS_REFRESH_MS = 35000;
    private static final long CONTEXT_ROADS_MIN_REFRESH_MS = 15000;
    private static final double CONTEXT_ROADS_REFRESH_DISTANCE_M = 160.0;
    private static final double CONTEXT_ROADS_QUERY_RADIUS_M = 380.0;

    // V0.35: show every useful side-road near the visible route as a short stub.
    // V0.34 was too selective (exact junction + angle filters), so genuine side roads
    // could disappear.  The active route stays thick; these references stay short/thin.
    private static final double CONTEXT_BRANCH_JOIN_METERS = 24.0;
    private static final double CONTEXT_BRANCH_STUB_METERS = 28.0;
    private static final double CONTEXT_BRANCH_MIN_AXIS_ANGLE_DEG = 3.0;
    private static final double CONTEXT_BRANCH_MAX_AHEAD_METERS = 205.0;
    private static final double CONTEXT_BRANCH_ALLOW_BEHIND_METERS = 12.0;
    private static final int CONTEXT_BRANCH_MAX_VISIBLE = 40;
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
    private long lastLocationCallbackMs = 0;
    private long lastGpsFixAcceptedMs = 0;
    private long lastNotificationRefreshMs = 0;
    private double offRouteMeters = 9999;
    private long offRouteSinceMs = 0;
    private boolean offRouteConfirmed = false;
    private long lastAutoRerouteMs = 0;
    private long fastDeviationSinceMs = 0;
    private long missedTurnSinceMs = 0;
    private long hardDeviationSinceMs = 0;
    private long nearWrongRoadSinceMs = 0;
    private int nearWrongRoadSamples = 0;
    private int armedManeuverStartIndex = -1;
    private double armedManeuverMinDistance = Double.POSITIVE_INFINITY;
    private int missedTurnMismatchSamples = 0;
    private boolean manualReroutePending = false;
    private double previousOffRouteMeters = Double.NaN;
    private long previousDeviationLocationTime = 0;
    // V0.32: keep route matching tied to actual forward progress. Matching against the
    // entire polyline can latch onto a nearby future/return segment and make a real
    // deviation look like 0-10 m, preventing reroute completely.
    private int lastTrackingRouteIndex = 0;
    // Some phones do not expose Location.getBearing() reliably on every fix. Derive a
    // movement bearing from consecutive accepted fixes as a fallback for wrong-turn detection.
    private Location lastMovementFix = null;
    private double lastMovementBearing = Double.NaN;
    private long lastMovementBearingMs = 0;
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
    private String navSource = "text";
    private LatLon directTarget = null;
    private String directTargetLabel = "";
    private final ArrayList<LatLon> plannedRoutePoints = new ArrayList<>();
    private int plannedWaypointStartIndex = 0;
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

                Location last;
                // Prefer a GPS fix when it is only slightly older than the network fix: GPS
                // normally carries the useful speed/bearing data needed by the Carlyle.
                if (gps != null && network != null && gps.getTime() + FRESH_GPS_PRIORITY_MS >= network.getTime()) {
                    last = gps;
                } else {
                    last = bestLocation(gps, network);
                }
                if (last != null && (currentLocation == null || last.getTime() >= currentLocation.getTime())) {
                    currentLocation = last;
                }
            } catch (Exception ignored) {
            }

            // V0.30: do not duplicate the expensive navigation pipeline when a real
            // location callback has just arrived. The heartbeat remains active as the
            // screen-off safety net if Android slows/stops callbacks.
            long heartbeatNow = System.currentTimeMillis();
            boolean recentLocationCallback =
                    lastLocationCallbackMs > 0 && heartbeatNow - lastLocationCallbackMs < HEARTBEAT_FALLBACK_AFTER_MS;

            if (currentLocation != null && !recentLocationCallback) {
                updatePlannedWaypointProgress();
                boolean empty;
                synchronized (route) { empty = route.isEmpty(); }

                if (empty) {
                    // Initial route calculation.
                    requestRoute(false);
                } else {
                    updateOffRouteStateAndMaybeReroute();
                    // Keep speed/distance/line flowing even while ORS is calculating a
                    // replacement route. The old route remains valid until the new one
                    // is atomically installed.
                    sendNavUpdate(false);
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

        if (ACTION_CLEAR_ROUTE.equals(action)) {
            clearActiveRoute();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_STOP.equals(action)) {
            stopRouting();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_REROUTE.equals(action)) {
            startForegroundCompat("Navigazione attiva", "GPS e invio dati al Carlyle attivi");
            if (!running) {
                // If Android had recreated the service, restore the active navigation first.
                startRouting();
            } else {
                // Route options can be changed on the phone while the foreground service is
                // already running. Reload them before a manual reroute so, for example,
                // AUTOSTRADE / SUPERSTRADE: SÌ takes effect immediately.
                reloadRoutingOptions();
                updatePlannedWaypointProgress();
                if (routeRequestInProgress) {
                    // Do not silently ignore the button. Queue one fresh calculation with
                    // the newest options/current GPS position as soon as the in-flight one ends.
                    manualReroutePending = true;
                    updateNotification("Ricalcolo richiesto", "Attendo il calcolo già in corso...");
                } else {
                    requestRoute(true);
                }
            }
            return START_STICKY;
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
        manualReroutePending = false;
        plannedWaypointStartIndex = 0;
        acquireWakeLock();
        handler.removeCallbacks(heartbeat);
        handler.post(heartbeat);

        synchronized (route) { route.clear(); }
        synchronized (maneuvers) { maneuvers.clear(); }

        lastDestination = null;
        lastDestinationText = "";
        lastRouteMs = 0;
        lastSendMs = 0;
        lastLocationCallbackMs = 0;
        lastGpsFixAcceptedMs = 0;
        lastNotificationRefreshMs = 0;
        offRouteMeters = 9999;
        offRouteSinceMs = 0;
        offRouteConfirmed = false;
        lastAutoRerouteMs = 0;
        resetFastDeviationTracking();
        lastTrackingRouteIndex = 0;
        lastMovementFix = null;
        lastMovementBearing = Double.NaN;
        lastMovementBearingMs = 0;
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
                requestRoute(false);
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

    private void clearActiveRoute() {
        running = false;
        routeRequestInProgress = false;
        handler.removeCallbacks(heartbeat);
        releaseWakeLock();
        try { locationManager.removeUpdates(listener); } catch (Exception ignored) {}

        synchronized (route) { route.clear(); }
        synchronized (maneuvers) { maneuvers.clear(); }
        synchronized (contextRoads) { contextRoads.clear(); }
        publishLiveRoute(new ArrayList<>());
        offRouteMeters = 0;
        offRouteSinceMs = 0;
        offRouteConfirmed = false;
        lastAutoRerouteMs = 0;
        resetFastDeviationTracking();
        lastTrackingRouteIndex = 0;
        lastMovementFix = null;
        lastMovementBearing = Double.NaN;
        lastMovementBearingMs = 0;
        lastSendMs = 0;

        updateNotification("Percorso cancellato", "In attesa di una nuova navigazione");
        sendToWear("{\"mode\":\"WAIT\",\"speed\":0,\"dist\":0,\"turn\":\"STRAIGHT\",\"limit\":" + lastSpeedLimit + ",\"line\":\"120,164;120,120\"}");
    }


    @Override
    public void onDestroy() {
        // ACTION_CLEAR_ROUTE already sent WAIT to the Carlyle. Do not overwrite it
        // with STOP while the service is shutting down.
        if (running) {
            stopRouting();
        } else {
            handler.removeCallbacks(heartbeat);
            releaseWakeLock();
            try { locationManager.removeUpdates(listener); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    private final LocationListener listener = loc -> {
        if (!acceptLiveLocation(loc)) return;

        updateMovementBearing(loc);
        currentLocation = loc;
        long now = System.currentTimeMillis();
        lastLocationCallbackMs = now;
        if (LocationManager.GPS_PROVIDER.equals(loc.getProvider())) {
            lastGpsFixAcceptedMs = now;
        }

        updatePlannedWaypointProgress();
        if (!running) return;

        boolean empty;
        synchronized (route) { empty = route.isEmpty(); }
        if (empty) {
            requestRoute(false);
            return;
        }

        updateOffRouteStateAndMaybeReroute();
        // V0.30: never freeze the Carlyle while a reroute HTTP request is in flight.
        sendNavUpdate(false);
    };

    private boolean acceptLiveLocation(Location loc) {
        if (loc == null) return false;
        long now = System.currentTimeMillis();
        String provider = loc.getProvider();

        // NETWORK_PROVIDER frequently reports speed=0 / no bearing. If a real GPS fix
        // was accepted recently, keep using it instead of replacing it with the coarser fix.
        if (LocationManager.NETWORK_PROVIDER.equals(provider) &&
                lastGpsFixAcceptedMs > 0 && now - lastGpsFixAcceptedMs < FRESH_GPS_PRIORITY_MS) {
            return false;
        }

        // Reject clearly older fixes arriving out of order.
        if (currentLocation != null && loc.getTime() > 0 && currentLocation.getTime() > 0 &&
                loc.getTime() + 1000 < currentLocation.getTime()) {
            return false;
        }
        return true;
    }

    private void updateOffRouteStateAndMaybeReroute() {
        if (currentLocation == null) return;

        ArrayList<LatLon> copy;
        synchronized (route) {
            copy = new ArrayList<>(route);
        }
        if (copy.isEmpty()) return;

        RouteMatch match = matchRouteTracked(copy, currentLocation.getLatitude(), currentLocation.getLongitude());
        offRouteMeters = match.offMeters;

        long now = System.currentTimeMillis();
        long locationTime = currentLocation.getTime();
        boolean freshLocationSample = locationTime > 0 && locationTime != previousDeviationLocationTime;
        double previousOff = previousOffRouteMeters;

        if (freshLocationSample) {
            previousOffRouteMeters = offRouteMeters;
            previousDeviationLocationTime = locationTime;
        }

        // Evaluate the missed-turn logic BEFORE the normal 25 m reset. This lets us
        // arm a junction while still correctly snapped to the route and, after passing
        // the junction in the wrong direction, react at ~7-10 m instead of waiting
        // until the GPS is 25-35 m away. Generic deviation rules inside this method
        // still keep their own >=25 m thresholds, so ordinary GPS jitter is unchanged.
        if (evaluateFastDeviationAndMaybeReroute(copy, match, now, freshLocationSample, previousOff)) {
            return;
        }

        // Back close to the route: preserve the V0.28 visual/map-matching tolerance.
        // Do NOT wipe the armed junction here: it must survive the instant in which
        // we cross a turn and then move a few metres away on the wrong road.
        if (offRouteMeters <= OFF_ROUTE_RESET_METERS) {
            offRouteSinceMs = 0;
            offRouteConfirmed = false;
            fastDeviationSinceMs = 0;
            hardDeviationSinceMs = 0;
            return;
        }

        // 25-35 m remains the normal tolerance band. No timer is started here unless
        // the fast detector has multiple independent clues that a turn was really missed.
        if (offRouteMeters < OFF_ROUTE_CONFIRM_METERS) {
            if (!offRouteConfirmed) offRouteSinceMs = 0;
            return;
        }

        if (offRouteSinceMs == 0) offRouteSinceMs = now;
        if (now - offRouteSinceMs < OFF_ROUTE_CONFIRM_MS) return;

        offRouteConfirmed = true;
        startAutomaticReroute(now);
    }

    private boolean evaluateFastDeviationAndMaybeReroute(ArrayList<LatLon> copy, RouteMatch match,
                                                          long now, boolean freshLocationSample,
                                                          double previousOff) {
        if (!freshLocationSample) return false;

        float speedKmh = currentLocation.hasSpeed() ? Math.max(0, currentLocation.getSpeed() * 3.6f) : 0;
        boolean accuracyOk = !currentLocation.hasAccuracy() || currentLocation.getAccuracy() <= FAST_GPS_MAX_ACCURACY_METERS;
        double travelBearing = effectiveTravelBearing();
        boolean bearingOk = !Double.isNaN(travelBearing) && accuracyOk &&
                (speedKmh >= 5.0f || (lastMovementBearingMs > 0 && now - lastMovementBearingMs <= 4000));

        if (!bearingOk) {
            fastDeviationSinceMs = 0;
            missedTurnSinceMs = 0;
            hardDeviationSinceMs = 0;
            nearWrongRoadSinceMs = 0;
            nearWrongRoadSamples = 0;
            return false;
        }

        double expectedBearing = routeHeadingAhead(copy, match, 35.0);
        if (Double.isNaN(expectedBearing)) {
            fastDeviationSinceMs = 0;
            missedTurnSinceMs = 0;
            hardDeviationSinceMs = 0;
            nearWrongRoadSinceMs = 0;
            nearWrongRoadSamples = 0;
            return false;
        }

        double headingDiff = Math.abs(angleDiff(travelBearing, expectedBearing));
        boolean growing = !Double.isNaN(previousOff) &&
                offRouteMeters >= previousOff + FAST_DISTANCE_GROWTH_METERS;

        // 0) Early wrong-road detection. Require a meaningful heading mismatch plus at
        // least a small separation from the tracked route and two fresh moving samples.
        // This is what makes a missed turn react quickly without lowering map snap tolerance.
        boolean nearWrongRoad = offRouteMeters >= NEAR_WRONG_ROAD_MIN_METERS &&
                headingDiff >= NEAR_WRONG_ROAD_HEADING_DIFF_DEG &&
                (!Double.isNaN(previousOff) && offRouteMeters >= previousOff - 0.5);
        if (nearWrongRoad) {
            if (nearWrongRoadSinceMs == 0) nearWrongRoadSinceMs = now;
            nearWrongRoadSamples++;
            if (nearWrongRoadSamples >= NEAR_WRONG_ROAD_MIN_SAMPLES &&
                    now - nearWrongRoadSinceMs >= NEAR_WRONG_ROAD_CONFIRM_MS) {
                offRouteConfirmed = true;
                return startAutomaticReroute(now);
            }
        } else {
            nearWrongRoadSinceMs = 0;
            nearWrongRoadSamples = 0;
        }

        // 1) Very clear deviation: >=50 m and direction incompatible with the route.
        // Confirm for one second rather than firing on one isolated fix.
        if (offRouteMeters >= HARD_DEVIATION_METERS && headingDiff >= FAST_HEADING_DIFF_DEG) {
            if (hardDeviationSinceMs == 0) hardDeviationSinceMs = now;
            if (now - hardDeviationSinceMs >= HARD_DEVIATION_CONFIRM_MS) {
                offRouteConfirmed = true;
                return startAutomaticReroute(now);
            }
        } else {
            hardDeviationSinceMs = 0;
        }

        // 2) Generic fast deviation: >=25 m, clearly wrong heading and distance growing.
        // Once armed, a nearly-flat sample is tolerated, but a real movement back toward
        // the route cancels the timer. This is quicker without becoming sensitive to jitter.
        boolean notShrinking = !Double.isNaN(previousOff) && offRouteMeters >= previousOff - 1.0;
        boolean fastCondition = offRouteMeters >= FAST_DEVIATION_MIN_METERS &&
                headingDiff >= FAST_HEADING_DIFF_DEG &&
                (growing || (fastDeviationSinceMs != 0 && notShrinking));

        if (fastCondition) {
            if (fastDeviationSinceMs == 0) fastDeviationSinceMs = now;
            if (now - fastDeviationSinceMs >= FAST_DEVIATION_CONFIRM_MS) {
                offRouteConfirmed = true;
                return startAutomaticReroute(now);
            }
        } else {
            fastDeviationSinceMs = 0;
        }

        // 3) Missed-turn detector. This is intentionally different from ordinary
        // off-route detection: while approaching a real junction we "arm" that manoeuvre.
        // If, after reaching it, the GPS is moving away from the junction on a heading
        // incompatible with the outgoing route, reroute quickly even though the raw
        // GPS may still be only a few metres from the old polyline. This is the common
        // "I should have turned but continued straight" case.
        Maneuver upcoming = nextRealManeuver(match.index);
        if (upcoming != null && upcoming.type != 7 && upcoming.type != 8 && upcoming.type != 9) {
            int turnIndex = Math.max(0, Math.min(upcoming.startIndex, copy.size() - 1));
            LatLon turnPoint = copy.get(turnIndex);
            double rawDistanceToTurn = distanceMeters(
                    currentLocation.getLatitude(), currentLocation.getLongitude(),
                    turnPoint.lat, turnPoint.lon);
            int routeDistanceToTurn = distanceAlongRoute(copy, match.index, turnIndex);

            boolean nearTurn = (routeDistanceToTurn >= 0 && routeDistanceToTurn <= MISSED_TURN_ROUTE_DISTANCE_METERS)
                    || rawDistanceToTurn <= MISSED_TURN_ROUTE_DISTANCE_METERS;

            if (nearTurn) {
                if (armedManeuverStartIndex != turnIndex) {
                    armedManeuverStartIndex = turnIndex;
                    armedManeuverMinDistance = rawDistanceToTurn;
                    missedTurnSinceMs = 0;
                    missedTurnMismatchSamples = 0;
                } else {
                    armedManeuverMinDistance = Math.min(armedManeuverMinDistance, rawDistanceToTurn);
                }
            }

            if (armedManeuverStartIndex == turnIndex) {
                double outgoingBearing = routeHeadingAfterIndex(copy, turnIndex, 28.0);
                double outgoingDiff = Double.isNaN(outgoingBearing) ? 0.0 :
                        Math.abs(angleDiff(travelBearing, outgoingBearing));
                boolean movingAwayFromTurn = rawDistanceToTurn >=
                        armedManeuverMinDistance + MISSED_TURN_MOVING_AWAY_METERS;
                boolean missedTurnCondition = movingAwayFromTurn
                        && offRouteMeters >= MISSED_TURN_MIN_METERS
                        && outgoingDiff >= MISSED_TURN_HEADING_DIFF_DEG;

                if (missedTurnCondition) {
                    if (missedTurnSinceMs == 0) missedTurnSinceMs = now;
                    missedTurnMismatchSamples++;
                    if (missedTurnMismatchSamples >= MISSED_TURN_MIN_SAMPLES
                            && now - missedTurnSinceMs >= MISSED_TURN_CONFIRM_MS) {
                        offRouteConfirmed = true;
                        return startAutomaticReroute(now);
                    }
                } else {
                    missedTurnSinceMs = 0;
                    missedTurnMismatchSamples = 0;
                }

                // Once we are well beyond the junction without a mismatch, disarm it.
                if (rawDistanceToTurn > 70.0 && !missedTurnCondition) {
                    armedManeuverStartIndex = -1;
                    armedManeuverMinDistance = Double.POSITIVE_INFINITY;
                }
            }
        } else {
            missedTurnSinceMs = 0;
            missedTurnMismatchSamples = 0;
        }

        return false;
    }

    private Maneuver nextRealManeuver(int nearestRouteIndex) {
        ArrayList<Maneuver> manCopy;
        synchronized (maneuvers) {
            manCopy = new ArrayList<>(maneuvers);
        }

        for (Maneuver m : manCopy) {
            if (m.startIndex + 2 < nearestRouteIndex) continue;
            if (m.type == 10 || m.type == 11 || m.type == 6) continue;
            return m;
        }
        return null;
    }

    private int distanceToNextRealManeuver(ArrayList<LatLon> copy, int nearestRouteIndex) {
        Maneuver m = nextRealManeuver(nearestRouteIndex);
        return m == null ? -1 : distanceAlongRoute(copy, nearestRouteIndex, m.startIndex);
    }

    private boolean startAutomaticReroute(long now) {
        if (routeRequestInProgress) return false;
        if (now - lastAutoRerouteMs < AUTO_REROUTE_COOLDOWN_MS) return false;

        lastAutoRerouteMs = now;
        // Pick up route-mode/highway changes made on the phone while navigation is live.
        reloadRoutingOptions();
        requestRoute(true);
        return true;
    }

    private void resetFastDeviationTracking() {
        fastDeviationSinceMs = 0;
        missedTurnSinceMs = 0;
        hardDeviationSinceMs = 0;
        nearWrongRoadSinceMs = 0;
        nearWrongRoadSamples = 0;
        armedManeuverStartIndex = -1;
        armedManeuverMinDistance = Double.POSITIVE_INFINITY;
        missedTurnMismatchSamples = 0;
        previousOffRouteMeters = Double.NaN;
        previousDeviationLocationTime = 0;
    }

    private void requestRoute(boolean forceStatus) {
        if (currentLocation == null) return;
        if (routeRequestInProgress) return;

        final String key = apiKey.trim();
        final String destinationText = this.destinationText.trim();
        final String source = navSource == null ? "text" : navSource;

        if (key.length() < 8) {
            updateNotification("Errore", "Inserisci API key OpenRouteService.");
            return;
        }

        if ("text".equals(source) && destinationText.length() < 3) {
            updateNotification("Errore", "Inserisci una destinazione.");
            return;
        }
        if ("point".equals(source) && directTarget == null) {
            updateNotification("Errore", "Punto salvato non valido.");
            return;
        }
        if ("route".equals(source) && plannedRoutePoints.size() < 2) {
            updateNotification("Errore", "Tragitto salvato non valido.");
            return;
        }

        routeRequestInProgress = true;
        lastRouteMs = System.currentTimeMillis();

        if (forceStatus) {
            updateNotification("Ricalcolo rotta", navigationLabel());
            // V0.29 sent a synthetic REROUTE packet with speed=0 and then stopped all
            // live packets until ORS replied. That made speed disappear and made the
            // Carlyle look frozen. Keep the old route visible and continue live data.
            boolean hasRoute;
            synchronized (route) { hasRoute = !route.isEmpty(); }
            if (hasRoute) sendNavUpdate(true);
        }

        new Thread(() -> {
            try {
                RouteResult result;

                if ("route".equals(source)) {
                    result = requestDirectionsViaPlannedRoute(key);
                } else {
                    LatLon dest;
                    if ("point".equals(source)) {
                        dest = directTarget;
                    } else if (lastDestination != null && destinationText.equals(lastDestinationText)) {
                        dest = lastDestination;
                    } else {
                        dest = geocodeDestination(key, destinationText);
                        lastDestination = dest;
                        lastDestinationText = destinationText;
                    }
                    result = requestDirections(key, dest);
                }

                synchronized (route) {
                    route.clear();
                    route.addAll(result.points);
                }

                // V0.33: publish the exact route returned by ORS so the phone map can
                // replace its old preview geometry immediately after a reroute.
                publishLiveRoute(result.points);

                synchronized (maneuvers) {
                    maneuvers.clear();
                    maneuvers.addAll(result.maneuvers);
                }

                handler.post(() -> {
                    routeRequestInProgress = false;
                    boolean rerouteAgain = manualReroutePending;
                    manualReroutePending = false;
                    offRouteSinceMs = 0;
                    offRouteConfirmed = false;
                    offRouteMeters = 0;
                    resetFastDeviationTracking();
                    lastTrackingRouteIndex = 0;
                    updateNotification("Rotta aggiornata", navigationLabel() + " - " +
                            ("shortest".equals(routeMode) ? "breve" : "veloce") +
                            (allowFastRoads ? " + strade veloci" : " no autostrade") +
                            " - svolte: " + result.maneuvers.size());
                    sendNavUpdate(true);
                    if (rerouteAgain) {
                        reloadRoutingOptions();
                        handler.postDelayed(() -> requestRoute(true), 80);
                    }
                });
            } catch (Exception e) {
                handler.post(() -> {
                    routeRequestInProgress = false;
                    boolean rerouteAgain = manualReroutePending;
                    manualReroutePending = false;
                    updateNotification("Errore routing", String.valueOf(e.getMessage()));
                    if (rerouteAgain) {
                        reloadRoutingOptions();
                        handler.postDelayed(() -> requestRoute(true), 120);
                    }
                });
            }
        }).start();
    }


    private void publishLiveRoute(ArrayList<LatLon> points) {
        StringBuilder sb = new StringBuilder();
        if (points != null) {
            for (int i = 0; i < points.size(); i++) {
                LatLon p = points.get(i);
                if (i > 0) sb.append(';');
                // Keep the full ORS geometry; Double.toString preserves enough
                // precision while remaining compact for SharedPreferences.
                sb.append(Double.toString(p.lat))
                        .append(',')
                        .append(Double.toString(p.lon));
            }
        }

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        long previousRevision = prefs.getLong(PREF_LIVE_ROUTE_REV, 0L);
        long revision = Math.max(System.currentTimeMillis(), previousRevision + 1L);
        prefs.edit()
                .putString(PREF_LIVE_ROUTE_GEOMETRY, sb.toString())
                .putLong(PREF_LIVE_ROUTE_REV, revision)
                .apply();
    }

    private String navigationLabel() {
        if ("point".equals(navSource)) {
            return directTargetLabel == null || directTargetLabel.trim().isEmpty() ? "Punto salvato" : directTargetLabel;
        }
        if ("route".equals(navSource)) return "Tragitto con waypoint";
        return destinationText == null ? "Destinazione" : destinationText;
    }

    private void updatePlannedWaypointProgress() {
        if (!"route".equals(navSource) || currentLocation == null || plannedRoutePoints.size() < 2) return;

        while (plannedWaypointStartIndex < plannedRoutePoints.size() - 1) {
            LatLon next = plannedRoutePoints.get(plannedWaypointStartIndex);
            double d = distanceMeters(currentLocation.getLatitude(), currentLocation.getLongitude(), next.lat, next.lon);
            if (d <= 90.0) plannedWaypointStartIndex++;
            else break;
        }
    }

    private LatLon geocodeDestination(String key, String destinationText) throws Exception {
        String encoded = URLEncoder.encode(destinationText, "UTF-8");
        URL url = new URL("https://api.heigit.org/pelias/v1/search?api_key=" + key + "&text=" + encoded + "&size=1");

        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(7000);
        c.setUseCaches(false);
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
        ArrayList<LatLon> coordinates = new ArrayList<>();
        coordinates.add(new LatLon(currentLocation.getLatitude(), currentLocation.getLongitude()));
        coordinates.add(dest);
        return requestDirectionsForCoordinates(key, coordinates);
    }

    private RouteResult requestDirectionsViaPlannedRoute(String key) throws Exception {
        ArrayList<LatLon> coordinates = new ArrayList<>();
        coordinates.add(new LatLon(currentLocation.getLatitude(), currentLocation.getLongitude()));

        int start = Math.max(0, Math.min(plannedWaypointStartIndex, plannedRoutePoints.size() - 1));
        for (int i = start; i < plannedRoutePoints.size(); i++) {
            LatLon p = plannedRoutePoints.get(i);
            if (coordinates.size() == 1 && i < plannedRoutePoints.size() - 1) {
                LatLon current = coordinates.get(0);
                if (distanceMeters(current.lat, current.lon, p.lat, p.lon) < 15.0) continue;
            }
            coordinates.add(new LatLon(p.lat, p.lon));
        }

        if (coordinates.size() < 2) {
            LatLon last = plannedRoutePoints.get(plannedRoutePoints.size() - 1);
            coordinates.add(new LatLon(last.lat, last.lon));
        }

        return requestDirectionsForCoordinates(key, coordinates);
    }

    private RouteResult requestDirectionsForCoordinates(String key, ArrayList<LatLon> coordinates) throws Exception {
        URL url = new URL("https://api.heigit.org/openrouteservice/v2/directions/driving-car/geojson");

        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(7000);
        c.setUseCaches(false);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Authorization", key);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");

        String preference = "shortest".equals(routeMode) ? "shortest" : "fastest";

        StringBuilder body = new StringBuilder();
        body.append("{\"coordinates\":[");
        for (int i = 0; i < coordinates.size(); i++) {
            if (i > 0) body.append(',');
            LatLon p = coordinates.get(i);
            body.append('[').append(p.lon).append(',').append(p.lat).append(']');
        }
        body.append("],\"preference\":\"").append(preference).append("\"");
        if (!allowFastRoads) {
            body.append(",\"options\":{\"avoid_features\":[\"highways\",\"tollways\"]}");
        }
        body.append('}');

        try (OutputStream os = c.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
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

        RouteMatch match = matchRouteTracked(copy, currentLocation.getLatitude(), currentLocation.getLongitude());
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

        // V0.35: keep nearby road context available throughout navigation.  The request
        // is independently rate-limited and does not consume OpenRouteService quota.
        requestContextRoadsIfNeeded(currentLocation);

        // Build the display polyline only after the next maneuver is known.
        // This allows a closer scale around roundabouts while still sending every
        // ORS geometry point available inside the visible look-ahead window.
        double displayBearing = effectiveTravelBearing();
        String line = buildScreenLine(copy, match, currentLocation, turn, dist, displayBearing);
        String roads = buildScreenRoads(copy, match, currentLocation, turn, dist, displayBearing);

        try {
            JSONObject o = new JSONObject();
            String liveMode = routeRequestInProgress ? "REROUTE" :
                    (offRouteConfirmed ? "OFF_ROUTE" : "NAV");
            o.put("mode", liveMode);
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

            // NotificationManager updates are much heavier than drawing a Wear packet.
            // Throttle only the phone notification; navigation packets remain live.
            if (recalculated || now - lastNotificationRefreshMs >= NOTIFICATION_REFRESH_MS) {
                lastNotificationRefreshMs = now;
                String title = routeRequestInProgress ? "Ricalcolo rotta" :
                        (offRouteConfirmed ? "Fuori rotta" : "Navigazione attiva");
                updateNotification(title, Math.round(speedKmh) + " km/h - " + turn + " " +
                        Math.round(Math.max(0, Math.min(99999, dist))) + " m - off " +
                        Math.round(offRouteMeters) + " m" +
                        (lastSpeedLimit > 0 ? " - lim " + lastSpeedLimit : ""));
            }
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
        // V0.35: Overpass can occasionally rate-limit a single public endpoint.  Try one
        // fallback mirror only on an actual request error; a successful empty response is
        // treated as a valid rural-area result.
        String[] endpoints = new String[]{
                "https://overpass-api.de/api/interpreter",
                "https://overpass.kumi.systems/api/interpreter"
        };
        Exception last = null;
        for (String endpoint : endpoints) {
            try {
                return requestNearbyRoadGeometryFromEndpoint(endpoint, lat, lon);
            } catch (Exception e) {
                last = e;
            }
        }
        if (last != null) throw last;
        return new ArrayList<>();
    }

    private ArrayList<ArrayList<LatLon>> requestNearbyRoadGeometryFromEndpoint(
            String endpoint, double lat, double lon) throws Exception {
        URL url = new URL(endpoint);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(6500);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "CiaoBeeline/0.36 (Wear OS navigation)");

        String highwayRegex = "motorway|motorway_link|trunk|trunk_link|primary|primary_link|secondary|secondary_link|tertiary|tertiary_link|residential|living_street|service|unclassified|road|track";
        String query = "[out:json][timeout:5];" +
                "way(around:" + (int) CONTEXT_ROADS_QUERY_RADIUS_M + "," + lat + "," + lon + ")" +
                "[\"highway\"~\"^(" + highwayRegex + ")$\"];" +
                "out geom;";
        String body = "data=" + URLEncoder.encode(query, "UTF-8");

        try (OutputStream os = c.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }

        int code = c.getResponseCode();
        InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String txt = readAll(is);
        if (code >= 400) throw new IOException("Overpass HTTP " + code);

        JSONObject json = new JSONObject(txt);
        JSONArray elements = json.optJSONArray("elements");
        ArrayList<ArrayList<LatLon>> out = new ArrayList<>();
        if (elements == null) return out;

        // Keep substantially more ways than V0.34. Overpass order is not spatially
        // meaningful, so a low cap could omit the actual side roads beside the rider.
        for (int i = 0; i < elements.length() && out.size() < 72; i++) {
            JSONObject e = elements.optJSONObject(i);
            if (e == null) continue;
            JSONArray geometry = e.optJSONArray("geometry");
            if (geometry == null || geometry.length() < 2) continue;

            ArrayList<LatLon> way = new ArrayList<>();
            int stride = Math.max(1, geometry.length() / 120);
            for (int g = 0; g < geometry.length(); g += stride) {
                JSONObject p = geometry.optJSONObject(g);
                if (p == null) continue;
                double plat = p.optDouble("lat", Double.NaN);
                double plon = p.optDouble("lon", Double.NaN);
                if (Double.isNaN(plat) || Double.isNaN(plon)) continue;
                if (distanceMeters(lat, lon, plat, plon) <= CONTEXT_ROADS_QUERY_RADIUS_M + 45.0) {
                    way.add(new LatLon(plat, plon));
                }
            }
            if (way.size() >= 2) out.add(way);
        }
        return out;
    }

    private String buildScreenRoads(ArrayList<LatLon> routePts, RouteMatch match, Location loc,
                                    String turn, int distToTurn, double displayBearing) {
        // V0.36: draw the nearby OSM road NETWORK as many short stubs instead of trying
        // to prove that each way intersects the ORS polyline at exactly one junction.
        // Different OSM/ORS samplings made that old test too fragile, which is why the
        // Carlyle could receive an empty "roads" field even in a normal town street.
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
        if (!Double.isNaN(displayBearing) && speedKmh >= 5.0f) {
            head = displayBearing;
        } else if (loc.hasBearing() && speedKmh >= GPS_BEARING_MIN_SPEED_KMH) {
            head = loc.getBearing();
        }

        double h = Math.toRadians(head);
        double sinH = Math.sin(h);
        double cosH = Math.cos(h);
        double pixelsPerMeter = ("ROUND".equals(turn) && distToTurn >= 0 && distToTurn <= 140)
                ? ROUNDABOUT_PIXELS_PER_METER : SCREEN_PIXELS_PER_METER;

        // Only the part of the active route that can be seen on the Carlyle is needed
        // to suppress duplicate segments belonging to the route itself.
        ArrayList<LatLon> routeWindow = new ArrayList<>();
        routeWindow.add(new LatLon(lat0, lon0));
        double routeWalked = 0.0;
        LatLon prevRoute = routeWindow.get(0);
        for (int i = idx + 1; i < routePts.size(); i++) {
            LatLon rp = routePts.get(i);
            routeWalked += distanceMeters(prevRoute.lat, prevRoute.lon, rp.lat, rp.lon);
            routeWindow.add(rp);
            prevRoute = rp;
            if (routeWalked >= CONTEXT_BRANCH_MAX_AHEAD_METERS + 55.0 || routeWindow.size() >= 220) break;
        }

        ArrayList<ScreenRoadStub> candidates = new ArrayList<>();

        for (ArrayList<LatLon> way : ways) {
            if (way == null || way.size() < 2) continue;

            for (int wi = 0; wi < way.size() - 1; wi++) {
                LatLon a = way.get(wi);
                LatLon b = way.get(wi + 1);
                double segLen = distanceMeters(a.lat, a.lon, b.lat, b.lon);
                if (segLen < 2.0) continue;

                double midLat = (a.lat + b.lat) * 0.5;
                double midLon = (a.lon + b.lon) * 0.5;
                double east = (midLon - lon0) * metersPerDegLon;
                double north = (midLat - lat0) * metersPerDegLat;
                double right = east * cosH - north * sinH;
                double forward = east * sinH + north * cosH;

                // Keep every real road segment in the useful navigation field.  A few
                // metres behind are retained so a side road remains visible while the
                // rider is actually crossing the junction.
                if (forward < -18.0 || forward > CONTEXT_BRANCH_MAX_AHEAD_METERS) continue;
                if (Math.abs(right) > 112.0) continue;

                double sx = 120.0 + right * pixelsPerMeter;
                double sy = 164.0 - forward * pixelsPerMeter;
                if (sx < 4.0 || sx > 236.0 || sy < 4.0 || sy > 204.0) continue;

                double segBearing = bearing(a.lat, a.lon, b.lat, b.lon);

                // Suppress only segments that are unmistakably the active route itself.
                // Crossing roads, forks and slip roads stay visible even when their
                // centre lies very close to the route line.
                RouteMatch rm = matchRoute(routeWindow, midLat, midLon);
                if (rm.offMeters <= 7.5) {
                    double routeAxis = localAxisBearing(routeWindow, rm.index);
                    if (axisAngleDiff(segBearing, routeAxis) < 10.0) continue;
                }

                ArrayList<LatLon> stub = shortSegmentAroundMidpoint(a, b,
                        CONTEXT_BRANCH_STUB_METERS);
                if (stub.size() < 2) continue;

                // Prioritise the nearest intersections, but do not require a junction
                // match. This is what makes the reference lines reliably appear.
                double priority = Math.max(0.0, forward) + Math.abs(right) * 0.18;
                candidates.add(new ScreenRoadStub(stub, sx, sy, segBearing, priority));
                if (candidates.size() >= 220) break;
            }
            if (candidates.size() >= 220) break;
        }

        java.util.Collections.sort(candidates, (a, b) -> Double.compare(a.priority, b.priority));

        StringBuilder out = new StringBuilder(6200);
        ArrayList<double[]> emitted = new ArrayList<>();
        int emittedWays = 0;

        for (ScreenRoadStub candidate : candidates) {
            boolean duplicate = false;
            for (double[] sig : emitted) {
                double dx = candidate.screenX - sig[0];
                double dy = candidate.screenY - sig[1];
                double ad = axisAngleDiff(candidate.axisBearing, sig[2]);
                if (dx * dx + dy * dy < 6.0 * 6.0 && ad < 8.0) {
                    duplicate = true;
                    break;
                }
            }
            if (duplicate) continue;

            StringBuilder one = new StringBuilder(80);
            for (LatLon p : candidate.stub) {
                double east = (p.lon - lon0) * metersPerDegLon;
                double north = (p.lat - lat0) * metersPerDegLat;
                double right = east * cosH - north * sinH;
                double forward = east * sinH + north * cosH;
                double sx = 120.0 + right * pixelsPerMeter;
                double sy = 164.0 - forward * pixelsPerMeter;

                if (one.length() > 0) one.append(';');
                one.append(Math.round(sx * 10.0) / 10.0).append(',')
                        .append(Math.round(sy * 10.0) / 10.0);
            }

            if (one.length() > 0) {
                if (out.length() > 0) out.append('|');
                out.append(one);
                emitted.add(new double[]{candidate.screenX, candidate.screenY, candidate.axisBearing});
                emittedWays++;
                if (emittedWays >= CONTEXT_BRANCH_MAX_VISIBLE) break;
            }
        }

        return out.toString();
    }

    private static ArrayList<LatLon> shortSegmentAroundMidpoint(LatLon a, LatLon b,
                                                                 double totalMeters) {
        ArrayList<LatLon> out = new ArrayList<>();
        double len = distanceMeters(a.lat, a.lon, b.lat, b.lon);
        if (len <= 0.01) return out;
        double halfFraction = Math.min(0.5, (totalMeters * 0.5) / len);
        double midLat = (a.lat + b.lat) * 0.5;
        double midLon = (a.lon + b.lon) * 0.5;
        double dLat = b.lat - a.lat;
        double dLon = b.lon - a.lon;
        out.add(new LatLon(midLat - dLat * halfFraction, midLon - dLon * halfFraction));
        out.add(new LatLon(midLat + dLat * halfFraction, midLon + dLon * halfFraction));
        return out;
    }

    private static class ScreenRoadStub {
        final ArrayList<LatLon> stub;
        final double screenX;
        final double screenY;
        final double axisBearing;
        final double priority;

        ScreenRoadStub(ArrayList<LatLon> stub, double screenX, double screenY,
                       double axisBearing, double priority) {
            this.stub = stub;
            this.screenX = screenX;
            this.screenY = screenY;
            this.axisBearing = axisBearing;
            this.priority = priority;
        }
    }

    private static double localAxisBearing(ArrayList<LatLon> pts, int index) {
        if (pts == null || pts.size() < 2) return 0.0;
        int i = Math.max(0, Math.min(index, pts.size() - 1));
        int before = Math.max(0, i - 1);
        int after = Math.min(pts.size() - 1, i + 1);
        if (before == after) return 0.0;
        return bearing(pts.get(before).lat, pts.get(before).lon,
                pts.get(after).lat, pts.get(after).lon);
    }

    private static double axisAngleDiff(double a, double b) {
        double d = Math.abs(angleDiff(a, b));
        if (d > 90.0) d = 180.0 - d;
        return Math.abs(d);
    }

    private static ArrayList<LatLon> extractRoadStub(ArrayList<LatLon> way, int junctionIndex,
                                                     double maxMetersEachSide) {
        ArrayList<LatLon> before = new ArrayList<>();
        ArrayList<LatLon> after = new ArrayList<>();
        int j = Math.max(0, Math.min(junctionIndex, way.size() - 1));
        LatLon junction = way.get(j);
        before.add(junction);

        double walked = 0.0;
        LatLon from = junction;
        for (int i = j - 1; i >= 0; i--) {
            LatLon to = way.get(i);
            double seg = distanceMeters(from.lat, from.lon, to.lat, to.lon);
            if (seg <= 0.01) {
                from = to;
                continue;
            }
            if (walked + seg <= maxMetersEachSide) {
                before.add(to);
                walked += seg;
                from = to;
            } else {
                double remain = maxMetersEachSide - walked;
                if (remain > 0.5) before.add(interpolateLatLon(from, to, remain / seg));
                break;
            }
        }

        // Reverse the backward half so the polyline flows naturally into the junction.
        ArrayList<LatLon> out = new ArrayList<>();
        for (int i = before.size() - 1; i >= 0; i--) out.add(before.get(i));

        walked = 0.0;
        from = junction;
        for (int i = j + 1; i < way.size(); i++) {
            LatLon to = way.get(i);
            double seg = distanceMeters(from.lat, from.lon, to.lat, to.lon);
            if (seg <= 0.01) {
                from = to;
                continue;
            }
            if (walked + seg <= maxMetersEachSide) {
                after.add(to);
                walked += seg;
                from = to;
            } else {
                double remain = maxMetersEachSide - walked;
                if (remain > 0.5) after.add(interpolateLatLon(from, to, remain / seg));
                break;
            }
        }
        out.addAll(after);
        return out;
    }

    private static LatLon interpolateLatLon(LatLon a, LatLon b, double t) {
        double clamped = Math.max(0.0, Math.min(1.0, t));
        return new LatLon(a.lat + (b.lat - a.lat) * clamped,
                a.lon + (b.lon - a.lon) * clamped);
    }

    static class BranchCandidate {
        final ArrayList<LatLon> way;
        final int junctionIndex;
        final double screenX;
        final double screenY;
        final double axisDiffDeg;
        final double forwardMeters;

        BranchCandidate(ArrayList<LatLon> way, int junctionIndex, double screenX, double screenY,
                        double axisDiffDeg, double forwardMeters) {
            this.way = way;
            this.junctionIndex = junctionIndex;
            this.screenX = screenX;
            this.screenY = screenY;
            this.axisDiffDeg = axisDiffDeg;
            this.forwardMeters = forwardMeters;
        }
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
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "CiaoBeeline/0.36 (Wear OS navigation)");

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

    private void reloadRoutingOptions() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        apiKey = p.getString(PREF_API_KEY, apiKey);
        destinationText = p.getString(PREF_DESTINATION, destinationText);
        routeMode = p.getString(PREF_ROUTE_MODE, routeMode == null ? "fastest" : routeMode);
        allowFastRoads = p.getBoolean(PREF_ALLOW_FAST_ROADS, allowFastRoads);
    }

    private void loadPrefsForService() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        apiKey = p.getString(PREF_API_KEY, "");
        destinationText = p.getString(PREF_DESTINATION, "");
        routeMode = p.getString(PREF_ROUTE_MODE, "fastest");
        allowFastRoads = p.getBoolean(PREF_ALLOW_FAST_ROADS, false);
        navSource = p.getString(PREF_NAV_SOURCE, "text");
        directTargetLabel = p.getString(PREF_NAV_TARGET_LABEL, "");
        directTarget = null;
        plannedRoutePoints.clear();
        plannedWaypointStartIndex = 0;

        try {
            String latRaw = p.getString(PREF_NAV_TARGET_LAT, "");
            String lonRaw = p.getString(PREF_NAV_TARGET_LON, "");
            if (!latRaw.isEmpty() && !lonRaw.isEmpty()) {
                directTarget = new LatLon(Double.parseDouble(latRaw), Double.parseDouble(lonRaw));
            }
        } catch (Exception ignored) {
            directTarget = null;
        }

        try {
            JSONArray a = new JSONArray(p.getString(PREF_NAV_ROUTE_POINTS, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                double lat = o.optDouble("lat", Double.NaN);
                double lon = o.optDouble("lon", Double.NaN);
                if (!Double.isNaN(lat) && !Double.isNaN(lon)) plannedRoutePoints.add(new LatLon(lat, lon));
            }
        } catch (Exception ignored) {
            plannedRoutePoints.clear();
        }
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


    // V0.32: derive heading from actual movement when Android's GPS fix does not provide
    // a usable bearing. This is intentionally independent from the map snap.
    private void updateMovementBearing(Location loc) {
        if (loc == null) return;
        long now = System.currentTimeMillis();
        if (lastMovementFix != null) {
            long dt = loc.getTime() > 0 && lastMovementFix.getTime() > 0
                    ? loc.getTime() - lastMovementFix.getTime() : 0;
            double d = distanceMeters(lastMovementFix.getLatitude(), lastMovementFix.getLongitude(),
                    loc.getLatitude(), loc.getLongitude());
            if (d >= 3.0 && (dt <= 0 || dt <= 6000)) {
                lastMovementBearing = bearing(lastMovementFix.getLatitude(), lastMovementFix.getLongitude(),
                        loc.getLatitude(), loc.getLongitude());
                lastMovementBearingMs = now;
            }
        }
        try { lastMovementFix = new Location(loc); } catch (Exception ignored) { lastMovementFix = loc; }
    }

    private double effectiveTravelBearing() {
        if (currentLocation == null) return Double.NaN;
        float speedKmh = currentLocation.hasSpeed() ? Math.max(0, currentLocation.getSpeed() * 3.6f) : 0;
        if (currentLocation.hasBearing() && speedKmh >= 5.0f) {
            return currentLocation.getBearing();
        }
        long now = System.currentTimeMillis();
        if (!Double.isNaN(lastMovementBearing) && lastMovementBearingMs > 0 &&
                now - lastMovementBearingMs <= 4000) {
            return lastMovementBearing;
        }
        return currentLocation.hasBearing() ? currentLocation.getBearing() : Double.NaN;
    }

    // Match only the corridor around the already travelled part of the route. A full-route
    // nearest-point search is ambiguous around loops, parallel roads and return segments.
    private RouteMatch matchRouteTracked(ArrayList<LatLon> pts, double lat, double lon) {
        if (pts == null || pts.size() < 2) return matchRoute(pts, lat, lon);

        int anchor = Math.max(0, Math.min(lastTrackingRouteIndex, pts.size() - 2));
        int from = walkRouteIndexBackward(pts, anchor, 90.0);
        int to = walkRouteIndexForward(pts, anchor, 650.0);
        RouteMatch m = matchRouteRange(pts, lat, lon, from, to);

        // Routes are calculated from the current GPS position, so forward progress should
        // normally be monotonic. Allow a tiny backward correction for GPS jitter only.
        if (m.index > lastTrackingRouteIndex) {
            lastTrackingRouteIndex = m.index;
        } else if (lastTrackingRouteIndex - m.index <= 3) {
            lastTrackingRouteIndex = Math.max(0, m.index);
        }
        return m;
    }

    private static int walkRouteIndexBackward(ArrayList<LatLon> pts, int start, double meters) {
        double acc = 0;
        int i = Math.max(0, Math.min(start, pts.size() - 2));
        while (i > 0 && acc < meters) {
            acc += distanceMeters(pts.get(i).lat, pts.get(i).lon, pts.get(i - 1).lat, pts.get(i - 1).lon);
            i--;
        }
        return i;
    }

    private static int walkRouteIndexForward(ArrayList<LatLon> pts, int start, double meters) {
        double acc = 0;
        int i = Math.max(0, Math.min(start, pts.size() - 2));
        while (i < pts.size() - 2 && acc < meters) {
            acc += distanceMeters(pts.get(i).lat, pts.get(i).lon, pts.get(i + 1).lat, pts.get(i + 1).lon);
            i++;
        }
        return Math.max(i, start + 1);
    }

    private static RouteMatch matchRouteRange(ArrayList<LatLon> pts, double lat, double lon, int from, int to) {
        if (pts == null || pts.isEmpty()) return new RouteMatch(0, lat, lon, 9999);
        if (pts.size() == 1) return matchRoute(pts, lat, lon);

        int first = Math.max(0, Math.min(from, pts.size() - 2));
        int last = Math.max(first, Math.min(to, pts.size() - 2));
        double best = 1e18;
        int bestIndex = first;
        double bestLat = pts.get(first).lat;
        double bestLon = pts.get(first).lon;
        double latRad = Math.toRadians(lat);
        double metersPerDegLat = 111320.0;
        double metersPerDegLon = Math.cos(latRad) * 111320.0;

        for (int i = first; i <= last; i++) {
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

    private static double routeHeadingAhead(ArrayList<LatLon> pts, RouteMatch match, double lookAheadMeters) {
        if (pts == null || pts.size() < 2 || match == null) return Double.NaN;

        int idx = Math.max(0, Math.min(match.index, pts.size() - 2));
        double startLat = match.lat;
        double startLon = match.lon;
        double prevLat = startLat;
        double prevLon = startLon;
        double acc = 0;
        LatLon target = null;

        for (int i = idx + 1; i < pts.size(); i++) {
            LatLon p = pts.get(i);
            acc += distanceMeters(prevLat, prevLon, p.lat, p.lon);
            target = p;
            if (acc >= lookAheadMeters) break;
            prevLat = p.lat;
            prevLon = p.lon;
        }

        if (target == null || distanceMeters(startLat, startLon, target.lat, target.lon) < 2.0) {
            return Double.NaN;
        }

        return bearing(startLat, startLon, target.lat, target.lon);
    }

    private static double routeHeadingAfterIndex(ArrayList<LatLon> pts, int startIndex, double lookAheadMeters) {
        if (pts == null || pts.size() < 2) return Double.NaN;
        int start = Math.max(0, Math.min(startIndex, pts.size() - 2));
        LatLon origin = pts.get(start);
        LatLon prev = origin;
        LatLon target = null;
        double acc = 0.0;
        for (int i = start + 1; i < pts.size(); i++) {
            LatLon p = pts.get(i);
            acc += distanceMeters(prev.lat, prev.lon, p.lat, p.lon);
            target = p;
            if (acc >= lookAheadMeters) break;
            prev = p;
        }
        if (target == null || distanceMeters(origin.lat, origin.lon, target.lat, target.lon) < 2.0) return Double.NaN;
        return bearing(origin.lat, origin.lon, target.lat, target.lon);
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

    private static String buildScreenLine(ArrayList<LatLon> pts, RouteMatch match, Location loc, String turn, int distToTurn, double displayBearing) {
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
        if (!Double.isNaN(displayBearing) && speedKmh >= 5.0f) {
            // Heading-up follows actual movement. displayBearing can come either from
            // Android GPS bearing or from consecutive GPS positions (V0.32 fallback).
            head = displayBearing;
        } else if (loc.hasBearing() && speedKmh >= GPS_BEARING_MIN_SPEED_KMH) {
            head = loc.getBearing();
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
