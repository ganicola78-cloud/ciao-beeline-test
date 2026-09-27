package com.example.ciaobeeline.wear;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Clean-room "Beeline-style" renderer for the Fossil Carlyle.
 *
 * Keeps the existing /nav_update JSON protocol used by Ciao Beeline:
 *   mode, turn, dist, speed, limit, line
 *
 * Optional forward-compatible fields:
 *   progress : 0.0 .. 1.0
 *   exit     : roundabout exit number
 *   roads    : one or more grey road polylines:
 *              "x,y;x,y;x,y|x,y;x,y"
 *
 * Logical canvas is 240 x 240 and automatically scales to the watch panel.
 */
public class NavView extends View {

    private static final float W = 240f;
    private static final float H = 240f;
    private static final float MARKER_X = 120f;
    private static final float MARKER_Y = 140f;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint routeUnderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint routePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint roadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mutedTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progressTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private String mode = "WAIT";
    private String turn = "STRAIGHT";
    private int dist = 300;
    private int speed = 0;
    private int limit = -1;
    private int roundaboutExit = -1;
    private float progress = -1f;

    private String line = "120,140;120,118;142,99;142,72;112,43";
    private String roads = "";

    private final ArrayList<PointF> targetPts = new ArrayList<>();
    private final ArrayList<PointF> displayPts = new ArrayList<>();
    private float displayDist = dist;
    private boolean animating = false;

    public NavView(Context c) {
        super(c);
        setKeepScreenOn(true);

        bgPaint.setStyle(Paint.Style.FILL);
        bgPaint.setColor(Color.BLACK);

        routeUnderPaint.setStyle(Paint.Style.STROKE);
        routeUnderPaint.setColor(Color.rgb(25, 25, 25));
        routeUnderPaint.setStrokeWidth(15f);
        routeUnderPaint.setStrokeCap(Paint.Cap.ROUND);
        routeUnderPaint.setStrokeJoin(Paint.Join.ROUND);

        routePaint.setStyle(Paint.Style.STROKE);
        routePaint.setColor(Color.WHITE);
        routePaint.setStrokeWidth(9.5f);
        routePaint.setStrokeCap(Paint.Cap.ROUND);
        routePaint.setStrokeJoin(Paint.Join.ROUND);

        roadPaint.setStyle(Paint.Style.STROKE);
        roadPaint.setColor(Color.rgb(88, 88, 88));
        roadPaint.setStrokeWidth(3.2f);
        roadPaint.setStrokeCap(Paint.Cap.ROUND);
        roadPaint.setStrokeJoin(Paint.Join.ROUND);

        markerFill.setStyle(Paint.Style.FILL);
        markerFill.setColor(Color.WHITE);

        markerStroke.setStyle(Paint.Style.STROKE);
        markerStroke.setColor(Color.BLACK);
        markerStroke.setStrokeWidth(3f);
        markerStroke.setStrokeJoin(Paint.Join.ROUND);

        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

        mutedTextPaint.setColor(Color.rgb(165, 165, 165));
        mutedTextPaint.setTextAlign(Paint.Align.CENTER);
        mutedTextPaint.setTypeface(Typeface.DEFAULT);

        progressTrackPaint.setStyle(Paint.Style.STROKE);
        progressTrackPaint.setStrokeWidth(3.5f);
        progressTrackPaint.setStrokeCap(Paint.Cap.ROUND);
        progressTrackPaint.setColor(Color.rgb(55, 55, 55));

        progressPaint.setStyle(Paint.Style.STROKE);
        progressPaint.setStrokeWidth(3.5f);
        progressPaint.setStrokeCap(Paint.Cap.ROUND);
        progressPaint.setColor(Color.WHITE);

        ArrayList<PointF> initial = normalizedRoute(parseLine(line));
        targetPts.addAll(copyPoints(initial));
        displayPts.addAll(copyPoints(initial));
    }

    public void update(String json) {
        try {
            JSONObject o = new JSONObject(json);

            mode = o.optString("mode", mode);
            turn = o.optString("turn", turn);
            dist = o.optInt("dist", dist);
            speed = o.optInt("speed", speed);
            limit = o.optInt("limit", limit);
            roundaboutExit = o.optInt("exit", roundaboutExit);

            if (o.has("progress")) {
                progress = (float) o.optDouble("progress", progress);
                if (progress >= 0f) progress = Math.max(0f, Math.min(1f, progress));
            }

            roads = o.optString("roads", roads);

            String newLine = o.optString("line", line);
            if (!newLine.equals(line) || targetPts.isEmpty()) {
                line = newLine;
                ArrayList<PointF> parsed = normalizedRoute(parseLine(line));
                if (!parsed.isEmpty()) {
                    targetPts.clear();
                    targetPts.addAll(parsed);

                    if (displayPts.isEmpty()) {
                        displayPts.addAll(copyPoints(targetPts));
                    } else {
                        resampleDisplayToTargetCount();
                    }
                }
            }

            startSmoothAnimation();
        } catch (Exception ignored) {
        }
    }

    private void startSmoothAnimation() {
        if (animating) return;
        animating = true;
        post(animationTick);
    }

    private final Runnable animationTick = new Runnable() {
        @Override
        public void run() {
            boolean keepGoing = false;

            if (!targetPts.isEmpty()) {
                if (displayPts.isEmpty()) displayPts.addAll(copyPoints(targetPts));
                resampleDisplayToTargetCount();

                for (int i = 0; i < targetPts.size(); i++) {
                    PointF d = displayPts.get(i);
                    PointF t = targetPts.get(i);

                    float dx = t.x - d.x;
                    float dy = t.y - d.y;

                    d.x += dx * 0.24f;
                    d.y += dy * 0.24f;

                    if (Math.abs(dx) > 0.45f || Math.abs(dy) > 0.45f) {
                        keepGoing = true;
                    }
                }
            }

            float dd = dist - displayDist;
            displayDist += dd * 0.30f;
            if (Math.abs(dd) > 0.75f) keepGoing = true;

            postInvalidate();

            if (keepGoing) {
                postDelayed(this, 40);
            } else {
                displayDist = dist;
                displayPts.clear();
                displayPts.addAll(copyPoints(targetPts));
                animating = false;
                postInvalidate();
            }
        }
    };

    private void resampleDisplayToTargetCount() {
        if (targetPts.isEmpty()) return;

        if (displayPts.isEmpty()) {
            displayPts.addAll(copyPoints(targetPts));
            return;
        }

        while (displayPts.size() < targetPts.size()) {
            PointF p = displayPts.get(displayPts.size() - 1);
            displayPts.add(new PointF(p.x, p.y));
        }

        while (displayPts.size() > targetPts.size()) {
            displayPts.remove(displayPts.size() - 1);
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);

        int w = getWidth();
        int h = getHeight();
        float scale = Math.min(w, h) / W;

        c.save();
        c.scale(scale, scale);
        c.translate((w / scale - W) / 2f, (h / scale - H) / 2f);

        c.drawCircle(W / 2f, H / 2f, 120f, bgPaint);

        Path clip = new Path();
        clip.addCircle(120f, 120f, 118.5f, Path.Direction.CW);
        c.save();
        c.clipPath(clip);

        if ("WAIT".equals(mode)) {
            drawWait(c);
        } else if ("STOP".equals(mode)) {
            drawStop(c);
        } else if ("OFF_ROUTE".equals(mode)) {
            drawOffRoute(c);
        } else {
            drawNavigation(c);
        }

        c.restore();
        c.restore();
    }

    private void drawNavigation(Canvas c) {
        drawContextRoads(c);

        ArrayList<PointF> pts = displayPts.isEmpty()
                ? normalizedRoute(parseLine(line))
                : copyPoints(displayPts);

        drawRoute(c, pts);
        drawPositionMarker(c);

        if ("REROUTE".equals(mode)) {
            drawRerouteBadge(c);
        }

        drawSpeedLimit(c);
        drawBottomGuidance(c);
        drawProgress(c);
    }

    private void drawRoute(Canvas c, ArrayList<PointF> pts) {
        if (pts.size() < 2) return;

        Path p = smoothPath(pts);
        c.drawPath(p, routeUnderPaint);
        c.drawPath(p, routePaint);
    }

    private Path smoothPath(ArrayList<PointF> pts) {
        Path path = new Path();
        if (pts.isEmpty()) return path;

        path.moveTo(pts.get(0).x, pts.get(0).y);

        if (pts.size() == 2) {
            path.lineTo(pts.get(1).x, pts.get(1).y);
            return path;
        }

        for (int i = 1; i < pts.size() - 1; i++) {
            PointF cur = pts.get(i);
            PointF next = pts.get(i + 1);
            float mx = (cur.x + next.x) * 0.5f;
            float my = (cur.y + next.y) * 0.5f;
            path.quadTo(cur.x, cur.y, mx, my);
        }

        PointF last = pts.get(pts.size() - 1);
        path.lineTo(last.x, last.y);
        return path;
    }

    private void drawContextRoads(Canvas c) {
        if (roads == null || roads.trim().isEmpty()) return;

        String[] polylines = roads.split("\\|");
        for (String polyline : polylines) {
            ArrayList<PointF> pts = normalizedContext(parseLine(polyline));
            if (pts.size() < 2) continue;

            Path p = new Path();
            p.moveTo(pts.get(0).x, pts.get(0).y);
            for (int i = 1; i < pts.size(); i++) {
                p.lineTo(pts.get(i).x, pts.get(i).y);
            }
            c.drawPath(p, roadPaint);
        }
    }

    private void drawPositionMarker(Canvas c) {
        // Beeline-style heading-up marker: the route rotates, marker stays fixed.
        Path arrow = new Path();
        arrow.moveTo(MARKER_X, MARKER_Y - 13f);
        arrow.lineTo(MARKER_X - 9.5f, MARKER_Y + 10f);
        arrow.lineTo(MARKER_X, MARKER_Y + 6f);
        arrow.lineTo(MARKER_X + 9.5f, MARKER_Y + 10f);
        arrow.close();

        c.drawPath(arrow, markerFill);
        c.drawPath(arrow, markerStroke);
    }

    private void drawBottomGuidance(Canvas c) {
        // Next manoeuvre: bottom-left.
        drawTurnIcon(c, 62f, 198f, turn);

        // Distance: bottom-right.
        textPaint.setTextAlign(Paint.Align.RIGHT);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint.setTextSize(22f);
        c.drawText(formatDistance(Math.round(displayDist)), 205f, 205f, textPaint);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    private void drawTurnIcon(Canvas c, float cx, float cy, String t) {
        Paint ip = new Paint(Paint.ANTI_ALIAS_FLAG);
        ip.setColor(Color.WHITE);
        ip.setStyle(Paint.Style.STROKE);
        ip.setStrokeWidth(5f);
        ip.setStrokeCap(Paint.Cap.ROUND);
        ip.setStrokeJoin(Paint.Join.ROUND);

        if ("ROUND".equals(t)) {
            RectF r = new RectF(cx - 14f, cy - 14f, cx + 14f, cy + 14f);
            c.drawArc(r, 35f, 285f, false, ip);

            Path head = new Path();
            head.moveTo(cx + 13f, cy - 2f);
            head.lineTo(cx + 16f, cy - 12f);
            head.lineTo(cx + 6f, cy - 8f);
            c.drawPath(head, ip);

            if (roundaboutExit > 0) {
                textPaint.setTextSize(11f);
                textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
                c.drawText(String.valueOf(roundaboutExit), cx, cy + 4f, textPaint);
            }
            return;
        }

        Path p = new Path();

        if ("LEFT".equals(t)) {
            p.moveTo(cx + 10f, cy + 12f);
            p.lineTo(cx + 10f, cy - 2f);
            p.quadTo(cx + 10f, cy - 10f, cx + 2f, cy - 10f);
            p.lineTo(cx - 10f, cy - 10f);

            c.drawPath(p, ip);
            drawArrowHead(c, cx - 10f, cy - 10f, 180f, ip);
            return;
        }

        if ("RIGHT".equals(t)) {
            p.moveTo(cx - 10f, cy + 12f);
            p.lineTo(cx - 10f, cy - 2f);
            p.quadTo(cx - 10f, cy - 10f, cx - 2f, cy - 10f);
            p.lineTo(cx + 10f, cy - 10f);

            c.drawPath(p, ip);
            drawArrowHead(c, cx + 10f, cy - 10f, 0f, ip);
            return;
        }

        p.moveTo(cx, cy + 13f);
        p.lineTo(cx, cy - 13f);
        c.drawPath(p, ip);
        drawArrowHead(c, cx, cy - 13f, -90f, ip);
    }

    private void drawArrowHead(Canvas c, float x, float y, float degrees, Paint p) {
        double a = Math.toRadians(degrees);
        float ux = (float) Math.cos(a);
        float uy = (float) Math.sin(a);
        float px = -uy;
        float py = ux;

        float backX = x - ux * 8f;
        float backY = y - uy * 8f;

        Path head = new Path();
        head.moveTo(backX + px * 5f, backY + py * 5f);
        head.lineTo(x, y);
        head.lineTo(backX - px * 5f, backY - py * 5f);
        c.drawPath(head, p);
    }

    private void drawProgress(Canvas c) {
        if (progress < 0f) return;

        float left = 66f;
        float right = 174f;
        float y = 224f;

        c.drawLine(left, y, right, y, progressTrackPaint);
        c.drawLine(left, y, left + (right - left) * progress, y, progressPaint);
    }

    private void drawSpeedLimit(Canvas c) {
        if (limit <= 0) return;

        float cx = 192f;
        float cy = 46f;
        float r = 14f;

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.WHITE);
        c.drawCircle(cx, cy, r, p);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3.5f);
        p.setColor(Color.rgb(220, 45, 45));
        c.drawCircle(cx, cy, r - 1.5f, p);

        textPaint.setColor(Color.BLACK);
        textPaint.setTextSize(limit >= 100 ? 9.5f : 11f);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        c.drawText(String.valueOf(limit), cx, cy + 3.7f, textPaint);
        textPaint.setColor(Color.WHITE);
    }

    private void drawRerouteBadge(Canvas c) {
        Paint pill = new Paint(Paint.ANTI_ALIAS_FLAG);
        pill.setColor(Color.rgb(38, 38, 38));
        pill.setStyle(Paint.Style.FILL);
        RectF r = new RectF(83f, 18f, 157f, 42f);
        c.drawRoundRect(r, 12f, 12f, pill);

        textPaint.setTextSize(11f);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        c.drawText("RICALCOLO", 120f, 34f, textPaint);
    }

    private void drawWait(Canvas c) {
        textPaint.setTextSize(20f);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        c.drawText("CIAO", 120f, 98f, textPaint);

        mutedTextPaint.setTextSize(13f);
        c.drawText("attendo navigazione", 120f, 128f, mutedTextPaint);

        Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(4f);
        ring.setStrokeCap(Paint.Cap.ROUND);
        ring.setColor(Color.WHITE);
        RectF r = new RectF(101f, 146f, 139f, 184f);
        c.drawArc(r, -70f, 230f, false, ring);
    }

    private void drawStop(Canvas c) {
        textPaint.setTextSize(18f);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        c.drawText("NAVIGAZIONE", 120f, 103f, textPaint);
        c.drawText("FERMATA", 120f, 128f, textPaint);

        mutedTextPaint.setTextSize(12f);
        c.drawText("avvia dal telefono", 120f, 158f, mutedTextPaint);
    }

    private void drawOffRoute(Canvas c) {
        // Keep a faint route trace in the background if available.
        ArrayList<PointF> pts = displayPts.isEmpty()
                ? normalizedRoute(parseLine(line))
                : copyPoints(displayPts);

        Paint faint = new Paint(routePaint);
        faint.setColor(Color.rgb(85, 85, 85));
        faint.setStrokeWidth(7f);
        if (pts.size() >= 2) c.drawPath(smoothPath(pts), faint);

        drawPositionMarker(c);

        textPaint.setTextSize(18f);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        c.drawText("FUORI ROTTA", 120f, 55f, textPaint);

        mutedTextPaint.setTextSize(12f);
        c.drawText("ricalcolo percorso", 120f, 75f, mutedTextPaint);

        textPaint.setTextSize(24f);
        c.drawText(formatDistance(Math.round(displayDist)), 120f, 207f, textPaint);
    }

    private ArrayList<PointF> normalizedRoute(ArrayList<PointF> src) {
        ArrayList<PointF> out = new ArrayList<>();
        if (src.isEmpty()) return out;

        PointF first = src.get(0);
        float dx = MARKER_X - first.x;
        float dy = MARKER_Y - first.y;

        for (PointF p : src) {
            float x = clamp(p.x + dx, 12f, 228f);
            float y = clamp(p.y + dy, 10f, 170f);
            out.add(new PointF(x, y));
        }
        return out;
    }

    private ArrayList<PointF> normalizedContext(ArrayList<PointF> src) {
        ArrayList<PointF> out = new ArrayList<>();
        for (PointF p : src) {
            out.add(new PointF(clamp(p.x, 8f, 232f), clamp(p.y, 8f, 178f)));
        }
        return out;
    }

    private ArrayList<PointF> parseLine(String s) {
        ArrayList<PointF> out = new ArrayList<>();
        if (s == null) return out;

        try {
            String[] pairs = s.split(";");
            for (String pair : pairs) {
                String[] xy = pair.split(",");
                if (xy.length != 2) continue;

                float x = Float.parseFloat(xy[0].trim());
                float y = Float.parseFloat(xy[1].trim());
                out.add(new PointF(x, y));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private ArrayList<PointF> copyPoints(ArrayList<PointF> src) {
        ArrayList<PointF> out = new ArrayList<>();
        for (PointF p : src) out.add(new PointF(p.x, p.y));
        return out;
    }

    private String formatDistance(int meters) {
        if (meters < 0) meters = 0;

        if (meters < 1000) return meters + " m";

        if (meters < 10000) {
            return String.format(Locale.US, "%.1f km", meters / 1000f);
        }

        return Math.round(meters / 1000f) + " km";
    }

    private float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
