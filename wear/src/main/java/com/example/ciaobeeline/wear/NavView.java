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
 * Ciao Beeline - round navigation renderer for the Fossil Carlyle.
 *
 * Clean-room UI designed for quick readability on a 240x240 round display.
 * It keeps the existing phone -> watch JSON protocol unchanged:
 *   mode, turn, dist, speed, limit, line
 *
 * Optional forward-compatible fields:
 *   progress : 0.0 .. 1.0
 *   exit     : roundabout exit number
 *   roads    : grey context polylines, e.g. "x,y;x,y|x,y;x,y"
 */
public class NavView extends View {

    private static final float W = 240f;
    private static final float H = 240f;

    // Rider marker is intentionally low: the road grows upward in heading-up view.
    private static final float MARKER_X = 120f;
    private static final float MARKER_Y = 164f;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint routeShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint routePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint roadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mutedTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thinLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progressTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private String mode = "WAIT";
    private String turn = "STRAIGHT";
    private int dist = 300;
    private int speed = 0;
    private int limit = -1;
    private int roundaboutExit = -1;
    private float progress = -1f;

    // Logical 240x240 route coordinates coming from the phone.
    private String line = "120,164;120,146;120,126;137,108;153,93;157,69";
    private String roads = "";

    private final ArrayList<PointF> targetPts = new ArrayList<>();
    private final ArrayList<PointF> displayPts = new ArrayList<>();
    private float displayDist = dist;
    private float displaySpeed = speed;
    private boolean animating = false;

    public NavView(Context context) {
        super(context);
        setKeepScreenOn(true);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);

        bgPaint.setStyle(Paint.Style.FILL);
        bgPaint.setColor(Color.BLACK);

        routeShadowPaint.setStyle(Paint.Style.STROKE);
        routeShadowPaint.setColor(Color.rgb(24, 24, 24));
        routeShadowPaint.setStrokeWidth(17f);
        routeShadowPaint.setStrokeCap(Paint.Cap.ROUND);
        routeShadowPaint.setStrokeJoin(Paint.Join.ROUND);

        routePaint.setStyle(Paint.Style.STROKE);
        routePaint.setColor(Color.WHITE);
        routePaint.setStrokeWidth(9.5f);
        routePaint.setStrokeCap(Paint.Cap.ROUND);
        routePaint.setStrokeJoin(Paint.Join.ROUND);

        roadPaint.setStyle(Paint.Style.STROKE);
        roadPaint.setColor(Color.rgb(68, 68, 68));
        roadPaint.setStrokeWidth(3.6f);
        roadPaint.setStrokeCap(Paint.Cap.ROUND);
        roadPaint.setStrokeJoin(Paint.Join.ROUND);

        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

        mutedTextPaint.setColor(Color.rgb(150, 150, 150));
        mutedTextPaint.setTextAlign(Paint.Align.CENTER);
        mutedTextPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));

        thinLinePaint.setStyle(Paint.Style.STROKE);
        thinLinePaint.setStrokeWidth(1.5f);
        thinLinePaint.setColor(Color.rgb(54, 54, 54));

        progressTrackPaint.setStyle(Paint.Style.STROKE);
        progressTrackPaint.setStrokeWidth(4f);
        progressTrackPaint.setStrokeCap(Paint.Cap.ROUND);
        progressTrackPaint.setColor(Color.rgb(48, 48, 48));

        progressPaint.setStyle(Paint.Style.STROKE);
        progressPaint.setStrokeWidth(4f);
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
                if (progress >= 0f) progress = clamp(progress, 0f, 1f);
            }

            roads = o.optString("roads", roads);

            String newLine = o.optString("line", line);
            if (!newLine.equals(line) || targetPts.isEmpty()) {
                line = newLine;
                ArrayList<PointF> parsed = normalizedRoute(parseLine(line));
                if (!parsed.isEmpty()) {
                    // Geometry is replaced atomically: morphing points with different
                    // counts bends the road and makes roundabouts look wrong.
                    targetPts.clear();
                    targetPts.addAll(parsed);
                    displayPts.clear();
                    displayPts.addAll(copyPoints(parsed));
                }
            }

            startSmoothAnimation();
        } catch (Exception ignored) {
            // Keep the last valid navigation state on malformed packets.
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

            float dd = dist - displayDist;
            displayDist += dd * 0.28f;
            if (Math.abs(dd) > 0.6f) keepGoing = true;

            float ds = speed - displaySpeed;
            displaySpeed += ds * 0.30f;
            if (Math.abs(ds) > 0.4f) keepGoing = true;

            postInvalidate();

            if (keepGoing) {
                postDelayed(this, 35);
            } else {
                displayDist = dist;
                displaySpeed = speed;
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
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int width = getWidth();
        int height = getHeight();
        float scale = Math.min(width, height) / W;

        canvas.save();
        canvas.scale(scale, scale);
        canvas.translate((width / scale - W) / 2f, (height / scale - H) / 2f);

        canvas.drawCircle(120f, 120f, 120f, bgPaint);

        Path clip = new Path();
        clip.addCircle(120f, 120f, 118.5f, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);

        if ("WAIT".equals(mode)) {
            drawWait(canvas);
        } else if ("STOP".equals(mode)) {
            drawStop(canvas);
        } else if ("OFF_ROUTE".equals(mode)) {
            drawOffRoute(canvas);
        } else {
            drawNavigation(canvas);
        }

        canvas.restore();
        canvas.restore();
    }

    private void drawNavigation(Canvas c) {
        // Main navigation field first; UI is layered above it.
        drawContextRoads(c);

        ArrayList<PointF> pts = displayPts.isEmpty()
                ? normalizedRoute(parseLine(line))
                : copyPoints(displayPts);

        drawRoute(c, pts);
        drawPositionMarker(c);

        drawTopGuidance(c);
        drawBottomStatus(c);
        drawProgress(c);

        if ("REROUTE".equals(mode)) {
            drawRerouteBadge(c);
        }
    }

    private void drawRoute(Canvas c, ArrayList<PointF> pts) {
        if (pts.size() < 2) return;

        Path p = exactPath(pts);
        c.drawPath(p, routeShadowPaint);
        c.drawPath(p, routePaint);
    }

    private Path exactPath(ArrayList<PointF> pts) {
        Path path = new Path();
        if (pts.isEmpty()) return path;

        path.moveTo(pts.get(0).x, pts.get(0).y);
        for (int i = 1; i < pts.size(); i++) {
            path.lineTo(pts.get(i).x, pts.get(i).y);
        }
        return path;
    }


    private void drawContextRoads(Canvas c) {
        if (roads == null || roads.trim().isEmpty()) return;

        String[] polylines = roads.split("\\|");
        for (String polyline : polylines) {
            ArrayList<PointF> pts = normalizedContext(parseLine(polyline));
            if (pts.size() < 2) continue;

            Path p = exactPath(pts);
            c.drawPath(p, roadPaint);
        }
    }

    /**
     * Rider marker: fixed low on the display. The route itself moves/changes around it.
     * The white wedge is deliberately simple so it remains readable in sunlight.
     */
    private void drawPositionMarker(Canvas c) {
        Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
        halo.setStyle(Paint.Style.FILL);
        halo.setColor(Color.BLACK);
        c.drawCircle(MARKER_X, MARKER_Y, 13.5f, halo);

        Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(3.5f);
        ring.setColor(Color.WHITE);
        c.drawCircle(MARKER_X, MARKER_Y, 9.5f, ring);

        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(Color.WHITE);

        Path wedge = new Path();
        wedge.moveTo(MARKER_X, MARKER_Y - 15f);
        wedge.lineTo(MARKER_X - 5.5f, MARKER_Y - 4.5f);
        wedge.lineTo(MARKER_X + 5.5f, MARKER_Y - 4.5f);
        wedge.close();
        c.drawPath(wedge, fill);

        Paint center = new Paint(Paint.ANTI_ALIAS_FLAG);
        center.setColor(Color.BLACK);
        center.setStyle(Paint.Style.FILL);
        c.drawCircle(MARKER_X, MARKER_Y, 3.2f, center);
    }

    private void drawTopGuidance(Canvas c) {
        // Black translucent-looking plate made with opaque black, to keep route from crossing text.
        Paint plate = new Paint(Paint.ANTI_ALIAS_FLAG);
        plate.setStyle(Paint.Style.FILL);
        plate.setColor(Color.BLACK);
        RectF topPlate = new RectF(25f, 7f, 215f, 59f);
        c.drawRoundRect(topPlate, 22f, 22f, plate);

        drawTurnIcon(c, 52f, 34f, turn, 0.88f);

        textPaint.setTextAlign(Paint.Align.RIGHT);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

        String distanceText = formatDistance(Math.round(displayDist));
        if (distanceText.endsWith(" m")) {
            String number = distanceText.substring(0, distanceText.length() - 2);
            textPaint.setTextSize(number.length() >= 4 ? 28f : 34f);
            c.drawText(number, 181f, 43f, textPaint);

            mutedTextPaint.setTextAlign(Paint.Align.LEFT);
            mutedTextPaint.setTextSize(11f);
            mutedTextPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            c.drawText("m", 186f, 42f, mutedTextPaint);
            mutedTextPaint.setTextAlign(Paint.Align.CENTER);
        } else {
            textPaint.setTextSize(distanceText.length() > 6 ? 24f : 28f);
            c.drawText(distanceText, 204f, 43f, textPaint);
        }

        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    private void drawBottomStatus(Canvas c) {
        // A subtle separator helps the speed information stay readable over route geometry.
        c.drawLine(57f, 197f, 183f, 197f, thinLinePaint);

        // Current speed, left side.
        int currentSpeed = Math.max(0, Math.round(displaySpeed));
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint.setTextSize(21f);
        c.drawText(String.valueOf(currentSpeed), 78f, 220f, textPaint);

        mutedTextPaint.setTextSize(7.5f);
        mutedTextPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        c.drawText("km/h", 78f, 231f, mutedTextPaint);

        // Tiny centre cue: useful when route geometry is nearly straight.
        drawMiniDirectionCue(c, 120f, 218f, turn);

        // Legal speed limit, right side. If unknown, keep the UI balanced with a dash.
        if (limit > 0) {
            drawSpeedLimit(c, 165f, 217f, 14.5f);
        } else {
            mutedTextPaint.setTextSize(17f);
            c.drawText("—", 165f, 222f, mutedTextPaint);
        }
    }

    private void drawMiniDirectionCue(Canvas c, float cx, float cy, String t) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.rgb(180, 180, 180));
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2.3f);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);

        if ("ROUND".equals(t)) {
            RectF r = new RectF(cx - 8f, cy - 8f, cx + 8f, cy + 8f);
            c.drawArc(r, 40f, 270f, false, p);
            return;
        }

        Path path = new Path();
        if ("LEFT".equals(t)) {
            path.moveTo(cx + 6f, cy + 7f);
            path.lineTo(cx + 6f, cy - 3f);
            path.lineTo(cx - 6f, cy - 3f);
            c.drawPath(path, p);
            drawArrowHead(c, cx - 6f, cy - 3f, 180f, p, 5f);
        } else if ("RIGHT".equals(t)) {
            path.moveTo(cx - 6f, cy + 7f);
            path.lineTo(cx - 6f, cy - 3f);
            path.lineTo(cx + 6f, cy - 3f);
            c.drawPath(path, p);
            drawArrowHead(c, cx + 6f, cy - 3f, 0f, p, 5f);
        } else {
            path.moveTo(cx, cy + 7f);
            path.lineTo(cx, cy - 7f);
            c.drawPath(path, p);
            drawArrowHead(c, cx, cy - 7f, -90f, p, 5f);
        }
    }

    private void drawTurnIcon(Canvas c, float cx, float cy, String t, float scale) {
        Paint ip = new Paint(Paint.ANTI_ALIAS_FLAG);
        ip.setColor(Color.WHITE);
        ip.setStyle(Paint.Style.STROKE);
        ip.setStrokeWidth(4.5f * scale);
        ip.setStrokeCap(Paint.Cap.ROUND);
        ip.setStrokeJoin(Paint.Join.ROUND);

        if ("ROUND".equals(t)) {
            float r = 13f * scale;
            RectF rr = new RectF(cx - r, cy - r, cx + r, cy + r);
            c.drawArc(rr, 35f, 285f, false, ip);

            Path head = new Path();
            head.moveTo(cx + 12f * scale, cy - 1f * scale);
            head.lineTo(cx + 15f * scale, cy - 11f * scale);
            head.lineTo(cx + 5f * scale, cy - 8f * scale);
            c.drawPath(head, ip);

            if (roundaboutExit > 0) {
                textPaint.setTextSize(10f * scale);
                textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
                c.drawText(String.valueOf(roundaboutExit), cx, cy + 3.5f * scale, textPaint);
            }
            return;
        }

        Path p = new Path();
        float span = 11f * scale;
        float drop = 12f * scale;

        if ("LEFT".equals(t)) {
            p.moveTo(cx + span, cy + drop);
            p.lineTo(cx + span, cy - 2f * scale);
            p.quadTo(cx + span, cy - 10f * scale, cx + 2f * scale, cy - 10f * scale);
            p.lineTo(cx - span, cy - 10f * scale);
            c.drawPath(p, ip);
            drawArrowHead(c, cx - span, cy - 10f * scale, 180f, ip, 7f * scale);
            return;
        }

        if ("RIGHT".equals(t)) {
            p.moveTo(cx - span, cy + drop);
            p.lineTo(cx - span, cy - 2f * scale);
            p.quadTo(cx - span, cy - 10f * scale, cx - 2f * scale, cy - 10f * scale);
            p.lineTo(cx + span, cy - 10f * scale);
            c.drawPath(p, ip);
            drawArrowHead(c, cx + span, cy - 10f * scale, 0f, ip, 7f * scale);
            return;
        }

        p.moveTo(cx, cy + 13f * scale);
        p.lineTo(cx, cy - 13f * scale);
        c.drawPath(p, ip);
        drawArrowHead(c, cx, cy - 13f * scale, -90f, ip, 7f * scale);
    }

    private void drawArrowHead(Canvas c, float x, float y, float degrees, Paint p, float size) {
        double a = Math.toRadians(degrees);
        float ux = (float) Math.cos(a);
        float uy = (float) Math.sin(a);
        float px = -uy;
        float py = ux;

        float backX = x - ux * size;
        float backY = y - uy * size;

        Path head = new Path();
        head.moveTo(backX + px * size * 0.62f, backY + py * size * 0.62f);
        head.lineTo(x, y);
        head.lineTo(backX - px * size * 0.62f, backY - py * size * 0.62f);
        c.drawPath(head, p);
    }

    private void drawProgress(Canvas c) {
        if (progress < 0f) return;

        RectF ring = new RectF(7f, 7f, 233f, 233f);
        c.drawArc(ring, 205f, 130f, false, progressTrackPaint);
        c.drawArc(ring, 205f, 130f * progress, false, progressPaint);
    }

    private void drawSpeedLimit(Canvas c, float cx, float cy, float radius) {
        if (limit <= 0) return;

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.WHITE);
        c.drawCircle(cx, cy, radius, p);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3.4f);
        p.setColor(Color.rgb(220, 38, 38));
        c.drawCircle(cx, cy, radius - 1.6f, p);

        textPaint.setColor(Color.BLACK);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint.setTextSize(limit >= 100 ? 9.5f : 11.5f);
        c.drawText(String.valueOf(limit), cx, cy + 3.8f, textPaint);
        textPaint.setColor(Color.WHITE);
    }

    private void drawRerouteBadge(Canvas c) {
        Paint pill = new Paint(Paint.ANTI_ALIAS_FLAG);
        pill.setColor(Color.rgb(35, 35, 35));
        pill.setStyle(Paint.Style.FILL);
        RectF r = new RectF(76f, 66f, 164f, 89f);
        c.drawRoundRect(r, 11.5f, 11.5f, pill);

        textPaint.setTextSize(10f);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        c.drawText("RICALCOLO", 120f, 81f, textPaint);
    }

    private void drawWait(Canvas c) {
        Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeCap(Paint.Cap.ROUND);

        ring.setStrokeWidth(2f);
        ring.setColor(Color.rgb(55, 55, 55));
        c.drawCircle(120f, 120f, 72f, ring);

        ring.setStrokeWidth(5f);
        ring.setColor(Color.WHITE);
        RectF rr = new RectF(94f, 94f, 146f, 146f);
        c.drawArc(rr, -75f, 245f, false, ring);

        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint.setTextSize(18f);
        c.drawText("CIAO", 120f, 72f, textPaint);

        mutedTextPaint.setTextSize(11.5f);
        mutedTextPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
        c.drawText("attendo navigazione", 120f, 170f, mutedTextPaint);
    }

    private void drawStop(Canvas c) {
        Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(4f);
        ring.setColor(Color.rgb(80, 80, 80));
        c.drawCircle(120f, 111f, 36f, ring);

        Paint stop = new Paint(Paint.ANTI_ALIAS_FLAG);
        stop.setStyle(Paint.Style.FILL);
        stop.setColor(Color.WHITE);
        c.drawRoundRect(new RectF(109f, 100f, 131f, 122f), 3f, 3f, stop);

        textPaint.setTextSize(17f);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        c.drawText("NAVIGAZIONE FERMATA", 120f, 166f, textPaint);

        mutedTextPaint.setTextSize(11f);
        c.drawText("avvia dal telefono", 120f, 185f, mutedTextPaint);
    }

    private void drawOffRoute(Canvas c) {
        ArrayList<PointF> pts = displayPts.isEmpty()
                ? normalizedRoute(parseLine(line))
                : copyPoints(displayPts);

        Paint faintShadow = new Paint(routeShadowPaint);
        faintShadow.setColor(Color.rgb(20, 20, 20));
        if (pts.size() >= 2) c.drawPath(exactPath(pts), faintShadow);

        Paint faint = new Paint(routePaint);
        faint.setColor(Color.rgb(74, 74, 74));
        faint.setStrokeWidth(7.5f);
        if (pts.size() >= 2) c.drawPath(exactPath(pts), faint);

        drawPositionMarker(c);

        Paint pill = new Paint(Paint.ANTI_ALIAS_FLAG);
        pill.setStyle(Paint.Style.FILL);
        pill.setColor(Color.WHITE);
        c.drawRoundRect(new RectF(57f, 24f, 183f, 60f), 18f, 18f, pill);

        textPaint.setColor(Color.BLACK);
        textPaint.setTextSize(14f);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        c.drawText("FUORI ROTTA", 120f, 47f, textPaint);
        textPaint.setColor(Color.WHITE);

        mutedTextPaint.setTextSize(11f);
        c.drawText("ricalcolo in corso", 120f, 79f, mutedTextPaint);

        textPaint.setTextSize(29f);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        c.drawText(formatDistance(Math.round(displayDist)), 120f, 215f, textPaint);
    }

    private ArrayList<PointF> normalizedRoute(ArrayList<PointF> src) {
        ArrayList<PointF> out = new ArrayList<>();
        if (src.isEmpty()) return out;

        // Only anchor the first point to the rider marker.
        // Never clamp individual points: clamping deforms curves and roundabouts.
        PointF first = src.get(0);
        float dx = MARKER_X - first.x;
        float dy = MARKER_Y - first.y;

        for (PointF p : src) {
            out.add(new PointF(p.x + dx, p.y + dy));
        }
        return out;
    }

    private ArrayList<PointF> normalizedContext(ArrayList<PointF> src) {
        ArrayList<PointF> out = new ArrayList<>();
        for (PointF p : src) {
            out.add(new PointF(clamp(p.x, 8f, 232f), clamp(p.y, 58f, 190f)));
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

    private float clamp(float value, float low, float high) {
        return Math.max(low, Math.min(high, value));
    }
}
