package com.shadow.mlbbcheat.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import com.shadow.mlbbcheat.models.PlayerData;

import java.util.List;

public class OverlayView extends View {

    private static final float BOX_HALF_W = 60f;
    private static final float BOX_HALF_H = 120f;
    private static final float MAP_DOT_RADIUS = 8f;

    private final Paint boxPaint = new Paint();
    private final Paint lowHpPaint = new Paint();
    private final Paint textPaint = new Paint();
    private final Paint dotPaint = new Paint();
    private final Paint linePaint = new Paint();

    private List<PlayerData> enemies;
    private float mapScale = 0.05f;
    private float mapOffsetX = 0f;
    private float mapOffsetY = 0f;
    private boolean stealthMode = false;

    /** Stealth: no boxes/lines, only distance text (honeypot-safe). */
    public void setStealthMode(boolean stealth) {
        this.stealthMode = stealth;
        invalidate();
    }

    public OverlayView(Context context) {
        super(context);
        boxPaint.setColor(Color.GREEN);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(4f);

        lowHpPaint.setColor(Color.RED);
        lowHpPaint.setStyle(Paint.Style.STROKE);
        lowHpPaint.setStrokeWidth(4f);

        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(28f);

        dotPaint.setColor(Color.RED);

        linePaint.setColor(Color.YELLOW);
        linePaint.setStrokeWidth(3f);
    }

    public void setEnemies(List<PlayerData> e) {
        this.enemies = e;
        invalidate();
    }

    public void setMapScale(float scale, float ox, float oy) {
        this.mapScale = scale;
        this.mapOffsetX = ox;
        this.mapOffsetY = oy;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (enemies == null) return;

        float centerX = getWidth() / 2f;
        float centerY = getHeight() / 2f;
        PlayerData nearest = findNearestEnemy(enemies, centerX, centerY);

        for (PlayerData p : enemies) {
            if (!p.isEnemy || !p.isAlive()) continue;

            if (stealthMode) {
                canvas.drawText("E " + Math.round(p.distanceTo(0f, 0f)) + "u",
                        centerX, centerY - 40f, textPaint);
                continue;
            }

            RectF box = worldToScreenRect(p, 2f);
            canvas.drawRect(box, p.hp < 30f ? lowHpPaint : boxPaint);
            canvas.drawText(String.valueOf((int) p.hp), box.left, box.top - 10f, textPaint);

            float mapX = p.x * mapScale + mapOffsetX;
            float mapY = p.y * mapScale + mapOffsetY;
            if (isInMapBounds(mapX, mapY, 400f, 400f)) {
                canvas.drawCircle(mapX, mapY, MAP_DOT_RADIUS, dotPaint);
            }

            if (nearest != null && p.id == nearest.id) {
                canvas.drawLine(centerX, centerY, box.centerX(), box.centerY(), linePaint);
            }
        }
    }

    public static RectF worldToScreenRect(PlayerData p, float scale) {
        float cx = p.x * scale;
        float cy = p.y * scale;
        return new RectF(cx - BOX_HALF_W, cy - BOX_HALF_H,
                cx + BOX_HALF_W, cy + BOX_HALF_H);
    }

    public static boolean isInMapBounds(float mapX, float mapY, float mapW, float mapH) {
        return mapX >= 0 && mapX <= mapW && mapY >= 0 && mapY <= mapH;
    }

    public static PlayerData findNearestEnemy(List<PlayerData> list, float px, float py) {
        PlayerData best = null;
        float bestDist = Float.MAX_VALUE;
        if (list == null) return null;
        for (PlayerData p : list) {
            if (!p.isEnemy || !p.isAlive()) continue;
            float d = p.distanceTo(px, py);
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return best;
    }
}
