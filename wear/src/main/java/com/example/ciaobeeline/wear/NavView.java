package com.example.ciaobeeline.wear;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import org.json.*;
import java.util.*;

public class NavView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String mode = "WAIT";
    private String turn = "RIGHT";
    private int dist = 300;
    private int speed = 0;
    private String line = "120,200;120,165;145,140;145,95;105,60";

    public NavView(Context c) { super(c); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND); }

    public void update(String json) {
        try {
            JSONObject o = new JSONObject(json);
            mode = o.optString("mode", "NAV");
            turn = o.optString("turn", "STRAIGHT");
            dist = o.optInt("dist", dist);
            speed = o.optInt("speed", speed);
            line = o.optString("line", line);
            postInvalidate();
        } catch(Exception ignored) {}
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float scale = Math.min(w,h) / 240f;
        c.save();
        c.scale(scale, scale);
        c.translate((w/scale-240)/2f, (h/scale-240)/2f);

        p.setStyle(Paint.Style.FILL); p.setColor(Color.BLACK); c.drawCircle(120,120,120,p);

        if ("WAIT".equals(mode)) drawWait(c);
        else if ("OFF_ROUTE".equals(mode)) drawOffRoute(c);
        else drawNav(c);
        c.restore();
    }

    private void drawWait(Canvas c) {
        p.setColor(Color.WHITE); p.setTextAlign(Paint.Align.CENTER); p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        p.setTextSize(22); c.drawText("CIAO", 120, 95, p);
        p.setTextSize(18); c.drawText("BEELINE", 120, 120, p);
        p.setTypeface(Typeface.DEFAULT); p.setTextSize(13); c.drawText("apri app telefono", 120, 155, p);
    }

    private void drawOffRoute(Canvas c) {
        p.setColor(Color.WHITE); p.setTextAlign(Paint.Align.CENTER); p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        p.setTextSize(22); c.drawText("FUORI", 120, 78, p);
        c.drawText("TRACCIA", 120, 105, p);
        p.setTextSize(46); c.drawText("↖", 120, 155, p);
        p.setTextSize(22); c.drawText(dist + " m", 120, 190, p);
    }

    private void drawNav(Canvas c) {
        drawRouteLine(c);
        drawTriangle(c);
        drawBottom(c);
        if ("REROUTE".equals(mode)) {
            p.setColor(Color.WHITE); p.setTextAlign(Paint.Align.CENTER); p.setTextSize(12); p.setTypeface(Typeface.DEFAULT_BOLD);
            c.drawText("ricalcolo", 120, 35, p);
        }
    }

    private void drawRouteLine(Canvas c) {
        ArrayList<PointF> pts = parseLine(line);
        if (pts.size() < 2) return;
        Path path = new Path(); path.moveTo(pts.get(0).x, pts.get(0).y);
        for (int i=1;i<pts.size();i++) path.lineTo(pts.get(i).x, pts.get(i).y);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(10); p.setColor(Color.WHITE); c.drawPath(path, p);
        p.setStyle(Paint.Style.FILL);
    }

    private void drawTriangle(Canvas c) {
        Path tri = new Path();
        tri.moveTo(120, 150); tri.lineTo(103, 184); tri.lineTo(137, 184); tri.close();
        p.setColor(Color.WHITE); p.setStyle(Paint.Style.FILL); c.drawPath(tri, p);
        p.setColor(Color.BLACK); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(3); c.drawPath(tri, p);
        p.setStyle(Paint.Style.FILL);
    }

    private void drawBottom(Canvas c) {
        p.setColor(Color.WHITE); p.setTextAlign(Paint.Align.CENTER); p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        p.setTextSize(22); c.drawText(turnSymbol(turn) + "  " + dist + " m", 120, 214, p);
        p.setTextSize(14); p.setTypeface(Typeface.DEFAULT); c.drawText(speed + " km/h", 120, 233, p);
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
            for (String pair: pairs) {
                String[] xy = pair.split(",");
                if (xy.length == 2) out.add(new PointF(Float.parseFloat(xy[0]), Float.parseFloat(xy[1])));
            }
        } catch(Exception ignored) {}
        return out;
    }
}
