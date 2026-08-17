package com.shadow.mlbbcheat.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.view.View;

import com.shadow.mlbbcheat.esp.AdvancedEsp;
import com.shadow.mlbbcheat.models.PlayerData;

import java.util.List;

/**
 * Thin view wrapper around {@link AdvancedEsp}. All drawing logic lives in
 * the engine; this view only feeds it the camera state and renders batches.
 */
public class OverlayView extends View {

    private final AdvancedEsp esp = new AdvancedEsp();
    private boolean stealthMode = false;

    public OverlayView(Context context) {
        super(context);
    }

    /** Stealth: nothing is drawn except a bare distance line (honeypot-safe). */
    public void setStealthMode(boolean stealth) {
        this.stealthMode = stealth;
        esp.config().showBoxes = !stealth;
        esp.config().showHpBars = !stealth;
        esp.config().showLabels = !stealth;
        esp.config().showArrows = !stealth;
        esp.config().showMapDots = !stealth;
        esp.config().showGhosts = !stealth;
        esp.config().showPriorityRing = !stealth;
        invalidate();
    }

    public boolean isStealthMode() {
        return stealthMode;
    }

    public AdvancedEsp espEngine() {
        return esp;
    }

    public void setEnemies(List<PlayerData> e) {
        esp.setEnemies(e);
        invalidate();
    }

    public void setMapScale(float scale, float ox, float oy) {
        esp.setMapTransform(scale, ox, oy);
        invalidate();
    }

    public void setCaptureActive(boolean active) {
        esp.setCaptureActive(active);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (esp.shouldSkipFrame()) return;
        long now = System.currentTimeMillis();
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        AdvancedEsp.DrawBatch batch = esp.computeBatch(now, getWidth(), getHeight(), cx, cy);
        esp.render(canvas, batch);
        esp.prune(now);
    }

    // ------------------------------------------------------------------
    // Static helpers kept for compatibility with existing callers/tests
    // ------------------------------------------------------------------

    public static RectF worldToScreenRect(PlayerData p, float scale) {
        float cx = p.x * scale;
        float cy = p.y * scale;
        return new RectF(cx - 60f, cy - 120f, cx + 60f, cy + 120f);
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