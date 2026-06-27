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
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private String mode = "WAIT";
    private String turn = "RIGHT";
    private int dist = 300;
    private int speed = 0;

    // linea demo / default
    private String line = "120,200;120,165;145,140;145,95;105,60";

    public NavView(Context c) {
        super(c);

        bgPaint.setStyle(Paint.Style.FILL);
        bgPaint.setColor(Color.BLACK);

        routePaint.setStyle(Paint.Style.STROKE);
        routePaint.setColor(Color.WHITE);
        routePaint.setStrokeWidth(10f);
        routePaint.setStrokeCap(Paint.Cap.ROUND);
        routePaint.setStrokeJoin(Paint.Join.ROUND);

        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(Color.WHITE);

        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setColor(Color.BLACK);
        strokePaint.setStrokeWidth(3f);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);

        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void update(String json) {
        try {
            JSONObject o = new JSONObject(json);
            mode = o.optString("mode", mode);
            turn = o.optString("turn", turn);
            dist = o.optInt("dist", dist);
            speed = o.optInt("speed", speed);
            line = o.optString("line", line);
            postInvalidate();
        } catch (Exception ignored) {
        }
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

        // sfondo rotondo
        c.drawCircle(120, 120, 120, bgPaint);

        if ("WAIT".equals(mode)) {
            drawWait(c);
        } else if ("OFF_ROUTE".equals(mode)) {
            drawOffRoute(c);
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

    private void drawOffRoute(Canvas c) {
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint.setTextSize(22);
        c.drawText("FUORI", 120, 80, textPaint);
        c.drawText("TRACCIA", 120, 108, textPaint);

        textPaint.setTextSize(46);
        c.drawText("↖", 120, 155, textPaint);

        textPaint.setTextSize(22);
        c.drawText(dist + " m", 120, 192, textPaint);
    }

    private void drawNav(Canvas c) {
        // box più alto per evitare che la linea finisca sopra i numeri
        RectF routeBox = new RectF(42, 28, 198, 118);

        drawRouteLine(c, routeBox);
        drawTriangle(c, 120, 140, 16);
        drawBottom(c);

        if ("REROUTE".equals(mode)) {
            textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            textPaint.setTextSize(12);
            c.drawText("ricalcolo", 120, 20, textPaint);
        }
    }

    private void drawRouteLine(Canvas c, RectF box) {
        ArrayList<PointF> pts = parseLine(line);
        if (pts.size() < 2) return;

        float minX = pts.get(0).x;
        float maxX = pts.get(0).x;
        float minY = pts.get(0).y;
        float maxY = pts.get(0).y;

        for (PointF p : pts) {
            if (p.x < minX) minX = p.x;
            if (p.x > maxX) maxX = p.x;
            if (p.y < minY) minY = p.y;
            if (p.y > maxY) maxY = p.y;
        }

        float srcW = Math.max(1f, maxX - minX);
        float srcH = Math.max(1f, maxY - minY);

        float scale = Math.min(box.width() / srcW, box.height() / srcH);

        float dstW = srcW * scale;
        float dstH = srcH * scale;

        float offsetX = box.left + (box.width() - dstW) / 2f;
        float offsetY = box.top + (box.height() - dstH) / 2f;

        Path path = new Path();

        for (int i = 0; i < pts.size(); i++) {
            PointF src = pts.get(i);
            float x = offsetX + (src.x - minX) * scale;
            float y = offsetY + (src.y - minY) * scale;

            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }

        c.drawPath(path, routePaint);
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
        // tutto più in alto
        float distY = 182f;
        float speedY = 206f;

        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint.setTextSize(22);
        c.drawText(turnSymbol(turn) + " " + dist + " m", 120, distY, textPaint);

        // velocità ingrandita circa come la riga dei metri
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint.setTextSize(22);
        c.drawText(speed + " km/h", 120, speedY, textPaint);
    }

    private String turnSymbol(String t) {
        if ("LEFT".equals(t)) return "↰";
        if ("RIGHT".equals(t)) return "↱";
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
