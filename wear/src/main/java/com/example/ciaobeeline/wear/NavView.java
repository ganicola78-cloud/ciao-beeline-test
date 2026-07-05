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

public class NavView extends View {

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint routePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sideRoadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private String mode = "WAIT";
    private String turn = "RIGHT";
    private int dist = 300;
    private int speed = 0;
    private int limit = -1;

    // Linea demo/default
    private String line = "120,200;120,165;145,140;145,95;105,60";

    // V0.15: valori visualizzati con animazione fluida.
    private final ArrayList<PointF> targetPts = new ArrayList<>();
    private final ArrayList<PointF> displayPts = new ArrayList<>();
    private float displayDist = dist;
    private float displaySpeed = speed;
    private boolean animating = false;

    public NavView(Context c) {
        super(c);

        bgPaint.setStyle(Paint.Style.FILL);
        bgPaint.setColor(Color.BLACK);

        routePaint.setStyle(Paint.Style.STROKE);
        routePaint.setColor(Color.WHITE);
        routePaint.setStrokeWidth(10f);
        routePaint.setStrokeCap(Paint.Cap.ROUND);
        routePaint.setStrokeJoin(Paint.Join.ROUND);

        sideRoadPaint.setStyle(Paint.Style.STROKE);
        sideRoadPaint.setColor(Color.rgb(170, 170, 170));
        sideRoadPaint.setStrokeWidth(3.5f);
        sideRoadPaint.setStrokeCap(Paint.Cap.ROUND);
        sideRoadPaint.setStrokeJoin(Paint.Join.ROUND);

        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(Color.WHITE);

        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setColor(Color.BLACK);
        strokePaint.setStrokeWidth(3f);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);

        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);

        progressPaint.setStyle(Paint.Style.STROKE);
        progressPaint.setColor(Color.rgb(210, 210, 210));
        progressPaint.setStrokeWidth(4f);
        progressPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    public void update(String json) {
        try {
            JSONObject o = new JSONObject(json);

            mode = o.optString("mode", mode);
            turn = o.optString("turn", turn);

            int newDist = o.optInt("dist", dist);
            int newSpeed = o.optInt("speed", speed);

            dist = newDist;
            speed = newSpeed;
            limit = o.optInt("limit", limit);

            String newLine = o.optString("line", line);

            if (!newLine.equals(line)) {
                line = newLine;
                ArrayList<PointF> parsed = parseLine(line);

                if (!parsed.isEmpty()) {
                    targetPts.clear();
                    targetPts.addAll(parsed);

                    if (displayPts.isEmpty()) {
                        displayPts.addAll(copyPoints(targetPts));
                    } else {
                        adaptDisplayPointCount();
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
        post(smoothRunnable);
    }

    private final Runnable smoothRunnable = new Runnable() {
        @Override
        public void run() {
            boolean keepGoing = false;

            if (!targetPts.isEmpty()) {
                if (displayPts.isEmpty()) {
                    displayPts.addAll(copyPoints(targetPts));
                }

                adaptDisplayPointCount();

                for (int i = 0; i < displayPts.size() && i < targetPts.size(); i++) {
                    PointF d = displayPts.get(i);
                    PointF t = targetPts.get(i);

                    float dx = t.x - d.x;
                    float dy = t.y - d.y;

                    d.x += dx * 0.28f;
                    d.y += dy * 0.28f;

                    if (Math.abs(dx) > 0.8f || Math.abs(dy) > 0.8f) {
                        keepGoing = true;
                    }
                }
            }

            float distDelta = dist - displayDist;
            float speedDelta = speed - displaySpeed;

            displayDist += distDelta * 0.30f;
            displaySpeed += speedDelta * 0.35f;

            if (Math.abs(distDelta) > 1.0f || Math.abs(speedDelta) > 0.5f) {
                keepGoing = true;
            }

            postInvalidate();

            if (keepGoing) {
                postDelayed(this, 50);
            } else {
                displayDist = dist;
                displaySpeed = speed;

                if (!targetPts.isEmpty()) {
                    displayPts.clear();
                    displayPts.addAll(copyPoints(targetPts));
                }

                animating = false;
                postInvalidate();
            }
        }
    };

    private void adaptDisplayPointCount() {
        if (targetPts.isEmpty()) return;

        if (displayPts.isEmpty()) {
            displayPts.addAll(copyPoints(targetPts));
            return;
        }

        while (displayPts.size() < targetPts.size()) {
            PointF last = displayPts.get(displayPts.size() - 1);
            displayPts.add(new PointF(last.x, last.y));
        }

        while (displayPts.size() > targetPts.size()) {
            displayPts.remove(displayPts.size() - 1);
        }
    }

    private ArrayList<PointF> copyPoints(ArrayList<PointF> src) {
        ArrayList<PointF> out = new ArrayList<>();

        for (PointF p : src) {
            out.add(new PointF(p.x, p.y));
        }

        return out;
    }


    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);

        int w = getWidth();
        int h = getHeight();

        float scale = Math.min(w, h) / 240f;

        c.save();
        c.scale(scale, scale);
        c.translate((w / scale - 240f) / 2f, (h / scale - 240f) / 2f);

        c.drawCircle(120, 120, 120, bgPaint);

        if ("WAIT".equals(mode)) {
            drawWait(c);
        } else if ("OFF_ROUTE".equals(mode)) {
            drawOffRoute(c);
        } else if ("STOP".equals(mode)) {
            drawStop(c);
        } else {
            drawNav(c);
        }

        c.restore();
    }

    private void drawWait(Canvas c) {
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint.setTextSize(22);
        c.drawText("CIAO", 120, 95, textPaint);

        textPaint.setTextSize(18);
        c.drawText("BEELINE", 120, 120, textPaint);

        textPaint.setTypeface(Typeface.DEFAULT);
        textPaint.setTextSize(13);
        c.drawText("apri app telefono", 120, 155, textPaint);
    }

    private void drawStop(Canvas c) {
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint.setTextSize(22);
        c.drawText("NAV", 120, 98, textPaint);
        c.drawText("STOP", 120, 126, textPaint);

        textPaint.setTypeface(Typeface.DEFAULT);
        textPaint.setTextSize(13);
        c.drawText("avvia dal telefono", 120, 156, textPaint);
    }

    private void drawOffRoute(Canvas c) {
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint.setTextSize(20);
        c.drawText("FUORI", 120, 76, textPaint);
        c.drawText("TRACCIA", 120, 101, textPaint);

        textPaint.setTextSize(48);
        c.drawText("↖", 120, 153, textPaint);

        textPaint.setTextSize(22);
        c.drawText(formatDistance(Math.round(displayDist)), 120, 190, textPaint);
    }

    private void drawNav(Canvas c) {
        RectF routeBox = new RectF(18, 18, 222, 148);

        ArrayList<PointF> screenPts = transformedLine(routeBox);

        drawSideRoads(c, screenPts);
        drawRoutePath(c, screenPts);
        drawRoundaboutHint(c);
        drawTriangle(c, 120, 140, 16);
        drawBottom(c);
        drawSpeedLimit(c);

        if ("REROUTE".equals(mode)) {
            textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            textPaint.setTextSize(12);
            c.drawText("ricalcolo", 120, 20, textPaint);
        }
    }

    private void drawRoutePath(Canvas c, ArrayList<PointF> pts) {
        if (pts.size() < 2) return;

        Path path = new Path();
        path.moveTo(pts.get(0).x, pts.get(0).y);

        for (int i = 1; i < pts.size(); i++) {
            path.lineTo(pts.get(i).x, pts.get(i).y);
        }

        c.drawPath(path, routePaint);
    }

    private void drawSideRoads(Canvas c, ArrayList<PointF> pts) {
        if (pts.size() < 3) return;

        int drawn = 0;

        for (int i = 1; i < pts.size() - 1 && drawn < 5; i += 2) {
            PointF prev = pts.get(i - 1);
            PointF cur = pts.get(i);
            PointF next = pts.get(i + 1);

            float vx = next.x - prev.x;
            float vy = next.y - prev.y;

            float len = (float) Math.sqrt(vx * vx + vy * vy);
            if (len < 8f) continue;

            vx /= len;
            vy /= len;

            float px = -vy;
            float py = vx;

            // Non disegnare laterali troppo in basso, per non coprire i numeri.
            if (cur.y > 122) continue;

            float sideLen = 26f;

            // Alterna lato destro/sinistro per simulare incroci Beeline.
            int sign = (i % 4 == 1) ? 1 : -1;

            float x1 = cur.x;
            float y1 = cur.y;
            float x2 = cur.x + px * sideLen * sign;
            float y2 = cur.y + py * sideLen * sign;

            // Mantieni dentro il quadrante utile.
            if (x2 < 22 || x2 > 218 || y2 < 20 || y2 > 128) continue;

            c.drawLine(x1, y1, x2, y2, sideRoadPaint);

            // Aggiunge una piccola seconda laterale sull'altro lato vicino alle curve,
            // visivamente simile alle stradine secondarie nel Beeline.
            if (Math.abs(angleAt(prev, cur, next)) > 22 && drawn < 4) {
                float x3 = cur.x - px * 18f * sign;
                float y3 = cur.y - py * 18f * sign;
                if (x3 >= 22 && x3 <= 218 && y3 >= 20 && y3 <= 128) {
                    c.drawLine(cur.x, cur.y, x3, y3, sideRoadPaint);
                }
            }

            drawn++;
        }
    }

    private float angleAt(PointF a, PointF b, PointF c) {
        float a1 = (float) Math.atan2(b.y - a.y, b.x - a.x);
        float a2 = (float) Math.atan2(c.y - b.y, c.x - b.x);
        float d = (float) Math.toDegrees(a2 - a1);

        while (d > 180) d -= 360;
        while (d < -180) d += 360;

        return d;
    }

    private ArrayList<PointF> transformedLine(RectF box) {
        ArrayList<PointF> pts;

        if (!displayPts.isEmpty()) {
            pts = copyPoints(displayPts);
        } else {
            pts = parseLine(line);
            if (!pts.isEmpty() && targetPts.isEmpty()) {
                targetPts.addAll(copyPoints(pts));
                displayPts.addAll(copyPoints(pts));
            }
        }

        ArrayList<PointF> out = new ArrayList<>();

        // V0.15: rotta frontale + transizione morbida.
        // La linea viene animata sul Carlyle invece di saltare di colpo.
        float cx = 120f;
        float cy = 140f;
        float angle = routeStartAngleDegrees(pts, cx, cy);
        float rotate = -angle;

        double rad = Math.toRadians(rotate);
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);

        for (PointF p : pts) {
            float dx = p.x - cx;
            float dy = p.y - cy;

            float rx = cx + dx * cos - dy * sin;
            float ry = cy + dx * sin + dy * cos;

            float x = Math.max(box.left, Math.min(box.right, rx));
            float y = Math.max(box.top, Math.min(box.bottom, ry));

            out.add(new PointF(x, y));
        }

        return out;
    }

    private void drawRoundaboutHint(Canvas c) {
        if (!"ROUND".equals(turn)) return;

        // V0.13: la rotonda non viene più disegnata come strada finta al centro,
        // perché risultava imprecisa. Mostriamo un piccolo segnale rotonda vicino
        // alle indicazioni, mentre la linea bianca resta la rotta reale.
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(4f);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(Color.WHITE);

        RectF r = new RectF(34, 160, 58, 184);
        c.drawArc(r, 40, 300, false, p);

        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint.setTextSize(14f);
        textPaint.setColor(Color.WHITE);
        c.drawText("↻", 46, 177, textPaint);
    }

    private void drawSpeedLimit(Canvas c) {
        if (limit <= 0) return;

        float cx = 184f;
        float cy = 174f;
        float r = 17f;

        Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        circlePaint.setStyle(Paint.Style.FILL);
        circlePaint.setColor(Color.WHITE);
        c.drawCircle(cx, cy, r, circlePaint);

        circlePaint.setStyle(Paint.Style.STROKE);
        circlePaint.setStrokeWidth(4f);
        circlePaint.setColor(Color.rgb(220, 45, 45));
        c.drawCircle(cx, cy, r - 2f, circlePaint);

        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint.setTextSize(limit >= 100 ? 12f : 15f);
        textPaint.setColor(Color.BLACK);
        c.drawText(String.valueOf(limit), cx, cy + 5f, textPaint);
        textPaint.setColor(Color.WHITE);
    }


    private void drawTriangleAlignedToRoute(Canvas c, ArrayList<PointF> pts, float cx, float cy, float halfWidth) {
        float angle = routeStartAngleDegrees(pts, cx, cy);

        c.save();
        c.rotate(angle, cx, cy);
        drawTriangle(c, cx, cy, halfWidth);
        c.restore();
    }

    private float routeStartAngleDegrees(ArrayList<PointF> pts, float cx, float cy) {
        if (pts == null || pts.size() < 2) {
            return 0f;
        }

        PointF best = null;
        float bestDist = Float.MAX_VALUE;

        for (PointF p : pts) {
            float dx = p.x - cx;
            float dy = p.y - cy;
            float d = (float) Math.sqrt(dx * dx + dy * dy);

            if (d > 8f && d < bestDist) {
                best = p;
                bestDist = d;
            }
        }

        if (best == null) {
            best = pts.get(Math.min(1, pts.size() - 1));
        }

        float dx = best.x - cx;
        float dy = best.y - cy;

        if (Math.abs(dx) < 1f && Math.abs(dy) < 1f) {
            return 0f;
        }

        // La freccia base punta verso l'alto. Con atan2(dx, -dy) otteniamo
        // la rotazione necessaria per allinearla al primo tratto della rotta.
        return (float) Math.toDegrees(Math.atan2(dx, -dy));
    }

    private void drawTriangle(Canvas c, float cx, float cy, float halfWidth) {
        float height = 28f;

        Path tri = new Path();
        tri.moveTo(cx, cy - height / 2f);
        tri.lineTo(cx - halfWidth, cy + height / 2f);
        tri.lineTo(cx + halfWidth, cy + height / 2f);
        tri.close();

        c.drawPath(tri, fillPaint);
        c.drawPath(tri, strokePaint);
    }

    private void drawBottom(Canvas c) {
        float distY = 182f;
        float speedY = 207f;

        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint.setTextSize(24);
        c.drawText(turnSymbol(turn) + " " + formatDistance(Math.round(displayDist)), 120, distY, textPaint);

        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint.setTextSize(22);
        c.drawText(Math.round(displaySpeed) + " km/h", 120, speedY, textPaint);
    }

    private String formatDistance(int meters) {
        if (meters < 0) meters = 0;

        if (meters < 1000) {
            return meters + " m";
        }

        if (meters < 10000) {
            float km = meters / 1000f;
            return String.format(java.util.Locale.US, "%.1f km", km);
        }

        return Math.round(meters / 1000f) + " km";
    }

    private String turnSymbol(String t) {
        if ("LEFT".equals(t)) return "↰";
        if ("RIGHT".equals(t)) return "↱";
        if ("ROUND".equals(t)) return "↻";
        return "↑";
    }

    private ArrayList<PointF> parseLine(String s) {
        ArrayList<PointF> out = new ArrayList<>();
        try {
            String[] pairs = s.split(";");
            for (String pair : pairs) {
                String[] xy = pair.split(",");
                if (xy.length == 2) {
                    out.add(new PointF(
                            Float.parseFloat(xy[0].trim()),
                            Float.parseFloat(xy[1].trim())
                    ));
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }
}
