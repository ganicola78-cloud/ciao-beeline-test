package com.example.ciaobeeline.mobile;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.*;
import android.view.View;
import android.graphics.*;
import android.content.Context;

import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import org.json.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class MainActivity extends Activity {
    private static final String PATH = "/nav_update";
    private EditText apiKeyEdit, destLatEdit, destLonEdit;
    private TextView status;
    private LocationManager locationManager;
    private Location currentLocation;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<LatLon> route = new ArrayList<>();
    private boolean running = false;
    private long lastRouteMs = 0;
    private double offRouteMeters = 9999;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        locationManager = (LocationManager)getSystemService(LOCATION_SERVICE);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(24,24,24,24);
        TextView title = new TextView(this); title.setText("Ciao Beeline Test - Phone"); title.setTextSize(22); root.addView(title);
        apiKeyEdit = new EditText(this); apiKeyEdit.setHint("OpenRouteService API key"); root.addView(apiKeyEdit);
        destLatEdit = new EditText(this); destLatEdit.setHint("Destinazione latitudine es. 45.4642"); root.addView(destLatEdit);
        destLonEdit = new EditText(this); destLonEdit.setHint("Destinazione longitudine es. 9.1900"); root.addView(destLonEdit);
        Button start = new Button(this); start.setText("Start live routing"); root.addView(start);
        Button test = new Button(this); test.setText("Invia demo al Carlyle"); root.addView(test);
        status = new TextView(this); status.setText("Pronto. Installa anche l'app Wear sul Carlyle."); status.setTextSize(16); root.addView(status);
        setContentView(root);
        start.setOnClickListener(v -> startRouting());
        test.setOnClickListener(v -> sendDemo());
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 5);
    }

    private void startRouting() {
        running = true;
        try {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 1, listener);
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000, 1, listener);
            }
            status.setText("Live routing avviato. Attendo GPS...");
        } catch (Exception e) { status.setText("GPS errore: " + e.getMessage()); }
    }

    private final LocationListener listener = loc -> {
        currentLocation = loc;
        if (!running) return;
        long now = System.currentTimeMillis();
        if (route.isEmpty() || now - lastRouteMs > 30000 || offRouteMeters > 60) {
            requestRoute();
        } else {
            sendNavUpdate(false);
        }
    };

    private void requestRoute() {
        if (currentLocation == null) return;
        final String key = apiKeyEdit.getText().toString().trim();
        if (key.length() < 8) { status.setText("Inserisci API key OpenRouteService."); return; }
        final double destLat, destLon;
        try { destLat = Double.parseDouble(destLatEdit.getText().toString().trim()); destLon = Double.parseDouble(destLonEdit.getText().toString().trim()); }
        catch(Exception e) { status.setText("Inserisci lat/lon destinazione valide."); return; }
        lastRouteMs = System.currentTimeMillis();
        status.setText("Calcolo rotta online...");
        new Thread(() -> {
            try {
                URL url = new URL("https://api.openrouteservice.org/v2/directions/driving-car/geojson");
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                c.setRequestMethod("POST"); c.setDoOutput(true);
                c.setRequestProperty("Authorization", key);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                String body = "{\"coordinates\":[[" + currentLocation.getLongitude() + "," + currentLocation.getLatitude() + "],[" + destLon + "," + destLat + "]]}";
                try(OutputStream os = c.getOutputStream()) { os.write(body.getBytes(StandardCharsets.UTF_8)); }
                InputStream is = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
                String txt = readAll(is);
                if (c.getResponseCode() >= 400) throw new RuntimeException(txt);
                JSONObject json = new JSONObject(txt);
                JSONArray coords = json.getJSONArray("features").getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates");
                ArrayList<LatLon> newRoute = new ArrayList<>();
                for (int i=0;i<coords.length();i++) {
                    JSONArray p = coords.getJSONArray(i); newRoute.add(new LatLon(p.getDouble(1), p.getDouble(0)));
                }
                synchronized(route) { route.clear(); route.addAll(newRoute); }
                handler.post(() -> { status.setText("Rotta ricevuta: " + newRoute.size() + " punti"); sendNavUpdate(true); });
            } catch(Exception e) { handler.post(() -> status.setText("Routing errore: " + e.getMessage())); }
        }).start();
    }

    private void sendNavUpdate(boolean recalculated) {
        if (currentLocation == null) return;
        ArrayList<LatLon> copy; synchronized(route) { copy = new ArrayList<>(route); }
        if (copy.isEmpty()) { sendDemo(); return; }
        int nearest = nearestIndex(copy, currentLocation.getLatitude(), currentLocation.getLongitude());
        offRouteMeters = distanceMeters(copy.get(nearest).lat, copy.get(nearest).lon, currentLocation.getLatitude(), currentLocation.getLongitude());
        float speedKmh = Math.max(0, currentLocation.getSpeed() * 3.6f);
        String line = buildScreenLine(copy, nearest, currentLocation);
        String turn = inferTurn(copy, nearest);
        int dist = distanceToNextBend(copy, nearest);
        try {
            JSONObject o = new JSONObject();
            o.put("mode", offRouteMeters > 60 ? "OFF_ROUTE" : (recalculated ? "REROUTE" : "NAV"));
            o.put("speed", Math.round(speedKmh));
            o.put("dist", dist);
            o.put("turn", turn);
            o.put("line", line);
            sendToWear(o.toString());
            status.setText("Inviato: " + o.optString("mode") + " speed " + Math.round(speedKmh) + " km/h, off " + Math.round(offRouteMeters) + " m");
        } catch(JSONException ignored) {}
    }

    private void sendDemo() {
        sendToWear("{\"mode\":\"NAV\",\"speed\":36,\"dist\":300,\"turn\":\"RIGHT\",\"line\":\"120,200;120,165;145,140;145,95;105,60\"}");
        status.setText("Demo inviata al Carlyle.");
    }

    private void sendToWear(String msg) {
        Wearable.getNodeClient(this).getConnectedNodes().addOnSuccessListener(nodes -> {
            if (nodes.isEmpty()) { status.setText("Nessun Carlyle collegato trovato."); return; }
            for (Node n: nodes) Wearable.getMessageClient(this).sendMessage(n.getId(), PATH, msg.getBytes(StandardCharsets.UTF_8));
        });
    }

    private static String readAll(InputStream is) throws IOException { BufferedReader br = new BufferedReader(new InputStreamReader(is)); StringBuilder sb=new StringBuilder(); String l; while((l=br.readLine())!=null) sb.append(l); return sb.toString(); }
    private static int nearestIndex(ArrayList<LatLon> pts, double lat, double lon) { double best=1e18; int idx=0; for(int i=0;i<pts.size();i++){ double d=distanceMeters(lat,lon,pts.get(i).lat,pts.get(i).lon); if(d<best){best=d;idx=i;}} return idx; }
    private static double distanceMeters(double la1,double lo1,double la2,double lo2){ double R=6371000, p1=Math.toRadians(la1), p2=Math.toRadians(la2); double dp=Math.toRadians(la2-la1), dl=Math.toRadians(lo2-lo1); double a=Math.sin(dp/2)*Math.sin(dp/2)+Math.cos(p1)*Math.cos(p2)*Math.sin(dl/2)*Math.sin(dl/2); return R*2*Math.atan2(Math.sqrt(a),Math.sqrt(1-a)); }
    private static double bearing(double la1,double lo1,double la2,double lo2){ double y=Math.sin(Math.toRadians(lo2-lo1))*Math.cos(Math.toRadians(la2)); double x=Math.cos(Math.toRadians(la1))*Math.sin(Math.toRadians(la2))-Math.sin(Math.toRadians(la1))*Math.cos(Math.toRadians(la2))*Math.cos(Math.toRadians(lo2-lo1)); return (Math.toDegrees(Math.atan2(y,x))+360)%360; }
    private static double angleDiff(double a,double b){ double d=(b-a+540)%360-180; return d; }
    private static String inferTurn(ArrayList<LatLon> pts, int idx){ if(idx+8>=pts.size()) return "STRAIGHT"; double b1=bearing(pts.get(idx).lat,pts.get(idx).lon,pts.get(Math.min(idx+3,pts.size()-1)).lat,pts.get(Math.min(idx+3,pts.size()-1)).lon); double b2=bearing(pts.get(Math.min(idx+3,pts.size()-1)).lat,pts.get(Math.min(idx+3,pts.size()-1)).lon,pts.get(Math.min(idx+8,pts.size()-1)).lat,pts.get(Math.min(idx+8,pts.size()-1)).lon); double d=angleDiff(b1,b2); if(d>35) return "RIGHT"; if(d<-35) return "LEFT"; return "STRAIGHT"; }
    private static int distanceToNextBend(ArrayList<LatLon> pts, int idx){ double acc=0; for(int i=idx;i<pts.size()-8;i++){ double b1=bearing(pts.get(i).lat,pts.get(i).lon,pts.get(i+3).lat,pts.get(i+3).lon); double b2=bearing(pts.get(i+3).lat,pts.get(i+3).lon,pts.get(i+8).lat,pts.get(i+8).lon); if(Math.abs(angleDiff(b1,b2))>35) return (int)Math.max(20, acc); acc += distanceMeters(pts.get(i).lat,pts.get(i).lon,pts.get(i+1).lat,pts.get(i+1).lon); if(acc>999) break; } return (int)Math.min(999, acc); }
    private static String buildScreenLine(ArrayList<LatLon> pts, int idx, Location loc){ if(idx>=pts.size()) return "120,200;120,80"; double head = loc.hasBearing()? loc.getBearing() : bearing(pts.get(idx).lat,pts.get(idx).lon,pts.get(Math.min(idx+2,pts.size()-1)).lat,pts.get(Math.min(idx+2,pts.size()-1)).lon); StringBuilder sb=new StringBuilder(); double lat0=loc.getLatitude(), lon0=loc.getLongitude(); int added=0; for(int i=idx;i<pts.size() && added<18;i+=2){ LatLon p=pts.get(i); double dist=distanceMeters(lat0,lon0,p.lat,p.lon); if(dist>280 && added>3) break; double br=bearing(lat0,lon0,p.lat,p.lon); double rel=Math.toRadians(angleDiff(head, br)); double x=Math.sin(rel)*dist; double y=Math.cos(rel)*dist; int sx=(int)Math.round(120 + x*0.55); int sy=(int)Math.round(200 - y*0.55); sx=Math.max(20,Math.min(220,sx)); sy=Math.max(20,Math.min(210,sy)); if(sb.length()>0) sb.append(';'); sb.append(sx).append(',').append(sy); added++; } return sb.toString(); }
    static class LatLon { double lat,lon; LatLon(double a,double b){lat=a;lon=b;} }
}
