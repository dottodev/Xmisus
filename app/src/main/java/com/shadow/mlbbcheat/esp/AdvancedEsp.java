package com.shadow.mlbbcheat.esp;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

import com.shadow.mlbbcheat.aim.AimEngine;
import com.shadow.mlbbcheat.models.PlayerData;
import com.shadow.mlbbcheat.utils.BehaviorMimic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Advanced ESP engine with built-in detection-avoidance draw modes.
 *
 * Feature set:
 *   - enemy boxes with HP bars (tier colored) + damage-preview notch
 *   - level / mana / hero / spell-CD labels
 *   - ult-ready and recall-flash markers
 *   - off-screen direction arrows (edge clamped)
 *   - ghost trails (last-known positions)
 *   - minimap dots sized by threat
 *   - priority-target ring (AimEngine scoring)
 *   - aim line to nearest killable target
 *
 * Bypass draw modes (the "stealth" half of the engine):
 *   - visibleCheckOnly: never draw players not on your screen
 *   - captureSuppress: blank the overlay while the screen is recorded/shared
 *   - humanFade: alpha ramps over a per-target random window instead of
 *     popping in (screen-share/report footage shows nothing alarming)
 *   - reactionGate: newly-spotted enemies are ignored for a short human
 *     reaction window (no instant lock-on visuals)
 *   - clutterBudget: at most N entities drawn, nearest wins (overdraw itself
 *     is a pattern + a performance anomaly)
 *   - distanceFade: far targets fade out gradually (never pop)
 *   - frameSkip: rendering is throttled to a jittered cadence
 *   - positionJitter: drawn positions get sub-pixel human noise
 *   - alphaDither: per-frame alpha wobble that prevents a static,
 *     machine-perfect overlay signature
 */
public class AdvancedEsp {

    public static final int TIER_HP_HIGH = 1;
    public static final int TIER_HP_MID = 2;
    public static final int TIER_HP_LOW = 3;

    public static final int STATE_NORMAL = 0;
    public static final int STATE_ULTR = 1;
    public static final int STATE_RECALL = 2;

    private static final long GHOST_TRAIL_MS = 4000L;
    private static final long REACTION_GATE_MS = 260L;
    private static final long FADE_MIN_MS = 180L;
    private static final long FADE_MAX_MS = 420L;
    private static final float ARROW_EDGE_PAD = 30f;
    private static final float ARROW_MIN_RADIUS = 60f;
    private static final float HP_BAR_W = 96f;
    private static final float HP_BAR_H = 10f;
    private static final float MAX_DRAW_DISTANCE = 1400f;
    private static final float MAX_DRAW_DISTANCE_SQ = MAX_DRAW_DISTANCE * MAX_DRAW_DISTANCE;
    private static final int DEFAULT_CLUTTER_BUDGET = 8;

    private final Random rng = new Random();
    private final Map<Integer, TargetMemory> memory = new HashMap<>();
    private final List<GhostDot> ghosts = new ArrayList<>();

    private final Paint boxPaint = new Paint();
    private final Paint boxLowPaint = new Paint();
    private final Paint barBgPaint = new Paint();
    private final Paint barHpPaint = new Paint();
    private final Paint barManaPaint = new Paint();
    private final Paint barPreviewPaint = new Paint();
    private final Paint textPaint = new Paint();
    private final Paint smallPaint = new Paint();
    private final Paint labelPaint = new Paint();
    private final Paint dotPaint = new Paint();
    private final Paint ghostPaint = new Paint();
    private final Paint ringPaint = new Paint();
    private final Paint linePaint = new Paint();
    private final Paint arrowPaint = new Paint();

    private final EspConfig config = new EspConfig();
    private List<PlayerData> enemies = new ArrayList<>();
    private float mapScale = 0.05f;
    private float mapOffsetX = 0f;
    private float mapOffsetY = 0f;
    private boolean captureActive = false;
    private long frame = 0L;
    private int frameSkip = 0;
    private long lastDrawMs = 0L;

    public AdvancedEsp() {
        boxPaint.setColor(Color.GREEN);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(4f);

        boxLowPaint.setColor(Color.RED);
        boxLowPaint.setStyle(Paint.Style.STROKE);
        boxLowPaint.setStrokeWidth(4f);

        barBgPaint.setColor(0x66000000);
        barBgPaint.setStyle(Paint.Style.FILL);

        barHpPaint.setColor(Color.GREEN);
        barHpPaint.setStyle(Paint.Style.FILL);

        barManaPaint.setColor(0xFF4FC3F7);
        barManaPaint.setStyle(Paint.Style.FILL);

        barPreviewPaint.setColor(0x88FFFFFF);
        barPreviewPaint.setStyle(Paint.Style.FILL);

        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(30f);
        textPaint.setAntiAlias(true);

        smallPaint.setColor(0xCCFFFFFF);
        smallPaint.setTextSize(20f);
        smallPaint.setAntiAlias(true);

        labelPaint.setColor(Color.YELLOW);
        labelPaint.setTextSize(22f);
        labelPaint.setAntiAlias(true);

        dotPaint.setColor(Color.RED);
        dotPaint.setStyle(Paint.Style.FILL);

        ghostPaint.setColor(0x44FF0000);
        ghostPaint.setStyle(Paint.Style.FILL);

        ringPaint.setColor(Color.CYAN);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(5f);

        linePaint.setColor(Color.YELLOW);
        linePaint.setStrokeWidth(3f);

        arrowPaint.setColor(Color.rgb(255, 165, 0));
        arrowPaint.setStrokeWidth(4f);
        arrowPaint.setStyle(Paint.Style.STROKE);
    }

    // ------------------------------------------------------------------
    // Configuration
    // ------------------------------------------------------------------

    public static class EspConfig {
        public boolean showBoxes = true;
        public boolean showHpBars = true;
        public boolean showManaBar = false;
        public boolean showLabels = true;
        public boolean showCd = false;
        public boolean showArrows = true;
        public boolean showGhosts = true;
        public boolean showMapDots = true;
        public boolean showAimLine = false;
        public boolean showPriorityRing = true;
        public boolean visibleCheckOnly = true;
        public boolean captureSuppress = true;
        public boolean humanFade = true;
        public boolean reactionGate = true;
        public boolean distanceFade = true;
        public boolean positionJitter = true;
        public boolean alphaDither = true;
        public boolean frameSkip = true;
        public int clutterBudget = DEFAULT_CLUTTER_BUDGET;
        public float maxDrawDistance = MAX_DRAW_DISTANCE;
    }

    public EspConfig config() {
        return config;
    }

    public void setEnemies(List<PlayerData> list) {
        this.enemies = list == null ? new ArrayList<>() : list;
    }

    public void setMapTransform(float scale, float ox, float oy) {
        this.mapScale = scale;
        this.mapOffsetX = ox;
        this.mapOffsetY = oy;
    }

    public void setCaptureActive(boolean active) {
        this.captureActive = active;
    }

    public boolean shouldSkipFrame() {
        if (!config.frameSkip) return false;
        frame++;
        if (frameSkip > 0) {
            frameSkip--;
            return true;
        }
        frameSkip = (int) BehaviorMimic.idleDelayMs(1, 3);
        return false;
    }

    public boolean suppressedByCapture() {
        return config.captureSuppress && captureActive;
    }

    public boolean canDrawAnything() {
        if (suppressedByCapture()) return false;
        if (config.clutterBudget <= 0) return false;
        return true;
    }

    // ------------------------------------------------------------------
    // Per-target memory (fade state, first-sight, ghosts)
    // ------------------------------------------------------------------

    private static final class TargetMemory {
        long firstSeenMs = 0L;
        float alpha = 0f;
        float jitterX = 0f;
        float jitterY = 0f;
        float lastX = Float.NaN;
        float lastY = Float.NaN;
        final List<GhostDot> trail = new ArrayList<>(8);
    }

    private TargetMemory memoryFor(int id) {
        TargetMemory m = memory.get(id);
        if (m == null) {
            m = new TargetMemory();
            m.firstSeenMs = System.currentTimeMillis();
            memory.put(id, m);
        }
        return m;
    }

    public boolean justSpotted(int id, long nowMs) {
        if (!config.reactionGate) return false;
        TargetMemory m = memory.get(id);
        if (m == null) return false;
        return nowMs - m.firstSeenMs < REACTION_GATE_MS;
    }

    public float fadeAlpha(TargetMemory m, long nowMs) {
        if (!config.humanFade) return 1f;
        float t = (nowMs - m.firstSeenMs) / 1000f;
        if (t >= 1f) return 1f;
        if (t < 0f) return 0f;
        return t;
    }

    public float ditherAlpha(float base, int seed) {
        if (!config.alphaDither) return base;
        float wobble = (rng.nextInt(24) - 12) / 255f;
        return Math.max(0f, Math.min(1f, base + wobble));
    }

    public float[] jitteredPosition(int id, float x, float y) {
        if (!config.positionJitter) return new float[]{x, y};
        TargetMemory m = memoryFor(id);
        if (Float.isNaN(m.lastX)) {
            m.lastX = x;
            m.lastY = y;
            m.jitterX = (rng.nextFloat() - 0.5f) * 4f;
            m.jitterY = (rng.nextFloat() - 0.5f) * 4f;
            return new float[]{x + m.jitterX, y + m.jitterY};
        }
        m.jitterX += (rng.nextFloat() - 0.5f) * 1.2f;
        m.jitterY += (rng.nextFloat() - 0.5f) * 1.2f;
        if (m.jitterX > 3f) m.jitterX = 3f;
        if (m.jitterX < -3f) m.jitterX = -3f;
        if (m.jitterY > 3f) m.jitterY = 3f;
        if (m.jitterY < -3f) m.jitterY = -3f;
        m.lastX = x;
        m.lastY = y;
        return new float[]{x + m.jitterX, y + m.jitterY};
    }

    public void recordGhost(int id, float wx, float wy, long nowMs) {
        TargetMemory m = memoryFor(id);
        GhostDot last = m.trail.isEmpty() ? null : m.trail.get(m.trail.size() - 1);
        if (last == null || Math.abs(last.wx - wx) > 40f || Math.abs(last.wy - wy) > 40f) {
            m.trail.add(new GhostDot(wx, wy, nowMs));
        }
        while (!m.trail.isEmpty()
                && nowMs - m.trail.get(0).seenMs > GHOST_TRAIL_MS) {
            m.trail.remove(0);
        }
        ghosts.clear();
        for (TargetMemory tm : memory.values()) {
            ghosts.addAll(tm.trail);
        }
    }

    // ------------------------------------------------------------------
    // Scoring
    // ------------------------------------------------------------------

    public static int hpTier(PlayerData p) {
        if (p.hp < 30f) return TIER_HP_LOW;
        if (p.hp < 70f) return TIER_HP_MID;
        return TIER_HP_HIGH;
    }

    public static float threatScore(PlayerData p) {
        float score = 0f;
        score += Math.max(0f, 100f - p.hp) * 0.5f;
        score += Math.max(0f, p.level - 8f) * 3f;
        if (p.ultReady) score += 12f;
        if (p.recalling) score -= 15f;
        if (p.spell1Cd > 0f) score -= p.spell1Cd * 0.2f;
        if (p.spell2Cd > 0f) score -= p.spell2Cd * 0.2f;
        return Math.max(0f, score);
    }

    public static PlayerData pickPriorityTarget(List<PlayerData> list) {
        PlayerData best = null;
        float bestScore = -1f;
        for (PlayerData p : list) {
            if (!p.isEnemy || !p.isAlive()) continue;
            float s = threatScore(p) + 1f / (1f + p.distanceTo(0f, 0f));
            if (s > bestScore) {
                bestScore = s;
                best = p;
            }
        }
        return best;
    }

    public static int stateOf(PlayerData p) {
        if (p.recalling) return STATE_RECALL;
        if (p.ultReady) return STATE_ULTR;
        return STATE_NORMAL;
    }

    // ------------------------------------------------------------------
    // Draw command model
    // ------------------------------------------------------------------

    public static final class BoxCmd {
        public final RectF rect;
        public final int tier;
        public final int alpha;
        BoxCmd(RectF r, int tier, int alpha) {
            this.rect = r;
            this.tier = tier;
            this.alpha = alpha;
        }
    }

    public static final class BarCmd {
        public final float x, y, ratio, manaRatio, previewRatio;
        public final int tier;
        public final int alpha;
        BarCmd(float x, float y, float ratio, float manaRatio, float previewRatio, int tier, int alpha) {
            this.x = x;
            this.y = y;
            this.ratio = ratio;
            this.manaRatio = manaRatio;
            this.previewRatio = previewRatio;
            this.tier = tier;
            this.alpha = alpha;
        }
    }

    public static final class LabelCmd {
        public final float x, y;
        public final String text;
        public final int style;
        public final int alpha;
        LabelCmd(float x, float y, String text, int style, int alpha) {
            this.x = x;
            this.y = y;
            this.text = text;
            this.style = style;
            this.alpha = alpha;
        }
    }

    public static final class ArrowCmd {
        public final float sx, sy, ex, ey;
        public final int alpha;
        ArrowCmd(float sx, float sy, float ex, float ey, int alpha) {
            this.sx = sx;
            this.sy = sy;
            this.ex = ex;
            this.ey = ey;
            this.alpha = alpha;
        }
    }

    public static final class DotCmd {
        public final float x, y;
        public final float radius;
        public final int color;
        public final int alpha;
        DotCmd(float x, float y, float radius, int color, int alpha) {
            this.x = x;
            this.y = y;
            this.radius = radius;
            this.color = color;
            this.alpha = alpha;
        }
    }

    public static final class RingCmd {
        public final float x, y;
        public final float radius;
        public final int alpha;
        RingCmd(float x, float y, float radius, int alpha) {
            this.x = x;
            this.y = y;
            this.radius = radius;
            this.alpha = alpha;
        }
    }

    public static final class LineCmd {
        public final float sx, sy, ex, ey;
        public final int alpha;
        LineCmd(float sx, float sy, float ex, float ey, int alpha) {
            this.sx = sx;
            this.sy = sy;
            this.ex = ex;
            this.ey = ey;
            this.alpha = alpha;
        }
    }

    public static final class DrawBatch {
        public final List<BoxCmd> boxes = new ArrayList<>();
        public final List<BarCmd> bars = new ArrayList<>();
        public final List<LabelCmd> labels = new ArrayList<>();
        public final List<ArrowCmd> arrows = new ArrayList<>();
        public final List<DotCmd> dots = new ArrayList<>();
        public final List<RingCmd> rings = new ArrayList<>();
        public final List<LineCmd> lines = new ArrayList<>();
        public final List<GhostDot> ghosts = new ArrayList<>();
        public boolean empty() {
            return boxes.isEmpty() && bars.isEmpty() && labels.isEmpty()
                    && arrows.isEmpty() && dots.isEmpty() && rings.isEmpty()
                    && lines.isEmpty() && ghosts.isEmpty();
        }
    }

    public static final class GhostDot {
        public final float wx, wy;
        public final long seenMs;
        GhostDot(float wx, float wy, long seenMs) {
            this.wx = wx;
            this.wy = wy;
            this.seenMs = seenMs;
        }
    }

    // ------------------------------------------------------------------
    // Frame computation
    // ------------------------------------------------------------------

    /**
     * Compute the draw batch for one frame. Pure logic — no canvas.
     * screenW/H are overlay dimensions; cameraX/Y is the player world pos.
     */
    public DrawBatch computeBatch(long nowMs, float screenW, float screenH,
                                  float cameraX, float cameraY) {
        DrawBatch batch = new DrawBatch();
        if (!canDrawAnything()) return batch;

        List<PlayerData> visible = new ArrayList<>();
        for (PlayerData p : enemies) {
            if (!p.isEnemy || !p.isAlive()) continue;
            if (!p.isFresh(nowMs, GHOST_TRAIL_MS)) continue;
            float dx = p.x - cameraX;
            float dy = p.y - cameraY;
            float distSq = dx * dx + dy * dy;
            if (config.maxDrawDistance > 0f && distSq > config.maxDrawDistance * config.maxDrawDistance) {
                continue;
            }
            if (config.visibleCheckOnly && !p.visible) continue;
            if (justSpotted(p.id, nowMs)) continue;
            visible.add(p);
        }

        visible.sort((a, b) -> Float.compare(a.distanceTo(cameraX, cameraY),
                b.distanceTo(cameraX, cameraY)));

        int budget = config.clutterBudget;
        int drawn = 0;
        float cx = screenW / 2f;
        float cy = screenH / 2f;
        PlayerData priority = pickPriorityTarget(enemies);

        for (PlayerData p : visible) {
            if (drawn >= budget) break;
            drawn++;
            TargetMemory m = memoryFor(p.id);
            float fade = fadeAlpha(m, nowMs);
            if (fade <= 0.02f) continue;
            float alpha = ditherAlpha(fade, p.id);
            int alpha255 = Math.round(alpha * 255f);

            recordGhost(p.id, p.x, p.y, nowMs);

            float[] jp = jitteredPosition(p.id, p.x, p.y);
            float sx = cx + (jp[0] - cameraX) * 2f;
            float sy = cy + (jp[1] - cameraY) * 2f;
            float dist = p.distanceTo(cameraX, cameraY);

            if (config.distanceFade && dist > 500f) {
                float fade2 = Math.max(0f, 1f - (dist - 500f) / 800f);
                alpha = ditherAlpha(alpha * fade2, p.id + 7);
                alpha255 = Math.round(alpha * 255f);
                if (alpha255 < 24) continue;
            }

            if (config.showBoxes) {
                RectF rect = new RectF(sx - 60f, sy - 120f, sx + 60f, sy + 120f);
                batch.boxes.add(new BoxCmd(rect, hpTier(p), alpha255));
            }

            if (config.showHpBars) {
                float ratio = Math.max(0f, Math.min(1f, p.hp / 100f));
                float mana = p.manaRatio < 0f ? 0f : Math.max(0f, Math.min(1f, p.manaRatio));
                float preview = Math.max(0f, Math.min(1f, ratio + 0.12f));
                batch.bars.add(new BarCmd(sx - HP_BAR_W / 2f, sy + 128f,
                        ratio, mana, preview, hpTier(p), alpha255));
            }

            if (config.showLabels) {
                StringBuilder sb = new StringBuilder();
                sb.append('L').append(p.level);
                if (p.heroId != PlayerData.HERO_UNKNOWN) {
                    sb.append(" H").append(p.heroId);
                }
                if (config.showCd && (p.spell1Cd > 0f || p.spell2Cd > 0f)) {
                    sb.append(" CD").append(String.format("%.1f",
                            Math.max(p.spell1Cd, p.spell2Cd)));
                }
                batch.labels.add(new LabelCmd(sx, sy - 132f, sb.toString(),
                        stateOf(p), alpha255));
            }

            if (config.showArrows && isOffScreen(sx, sy, screenW, screenH)) {
                float[] arrow = arrowToEdge(cx, cy, sx, sy, screenW, screenH);
                batch.arrows.add(new ArrowCmd(cx, cy, arrow[0], arrow[1], alpha255));
            }

            if (config.showMapDots) {
                float mapX = p.x * mapScale + mapOffsetX;
                float mapY = p.y * mapScale + mapOffsetY;
                float rad = 7f + Math.min(8f, p.level * 0.5f);
                int color = p.ultReady ? 0xFFFF00FF : Color.RED;
                batch.dots.add(new DotCmd(mapX, mapY, rad, color, alpha255));
            }

            if (config.showPriorityRing && p.id == (priority == null ? -1 : priority.id)) {
                batch.rings.add(new RingCmd(sx, sy, 150f, alpha255));
            }

            if (config.showAimLine && priority != null && p.id == priority.id) {
                batch.lines.add(new LineCmd(cx, cy, sx, sy, alpha255));
            }
        }

        if (config.showGhosts) {
            for (GhostDot g : ghosts) {
                long age = nowMs - g.seenMs;
                if (age > GHOST_TRAIL_MS) continue;
                float gx = cx + (g.wx - cameraX) * 2f;
                float gy = cy + (g.wy - cameraY) * 2f;
                if (gx < 0 || gx > screenW || gy < 0 || gy > screenH) continue;
                float a = 1f - (float) age / GHOST_TRAIL_MS;
                batch.ghosts.add(new GhostDot(gx, gy, (long) (a * 255f)));
            }
        }

        return batch;
    }

    public static boolean isOffScreen(float sx, float sy, float sw, float sh) {
        return sx < 0f || sx > sw || sy < 0f || sy > sh;
    }

    public static float[] arrowToEdge(float cx, float cy, float tx, float ty,
                                      float sw, float sh) {
        float dx = tx - cx;
        float dy = ty - cy;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1f) return new float[]{cx, cy};
        float ux = dx / len;
        float uy = dy / len;

        float edgeX = cx + ux * (sw / 2f + ARROW_EDGE_PAD);
        float edgeY = cy + uy * (sh / 2f + ARROW_EDGE_PAD);

        float maxX = sw - ARROW_EDGE_PAD;
        float maxY = sh - ARROW_EDGE_PAD;
        if (edgeX > maxX) {
            float t = (maxX - cx) / ux;
            edgeY = cy + uy * t;
            edgeX = maxX;
        } else if (edgeX < ARROW_EDGE_PAD) {
            float t = (ARROW_EDGE_PAD - cx) / ux;
            edgeY = cy + uy * t;
            edgeX = ARROW_EDGE_PAD;
        }
        if (edgeY > maxY) {
            float t = (maxY - cy) / uy;
            edgeX = cx + ux * t;
            edgeY = maxY;
        } else if (edgeY < ARROW_EDGE_PAD) {
            float t = (ARROW_EDGE_PAD - cy) / uy;
            edgeX = cx + ux * t;
            edgeY = ARROW_EDGE_PAD;
        }

        float dx2 = edgeX - cx;
        float dy2 = edgeY - cy;
        float l2 = (float) Math.sqrt(dx2 * dx2 + dy2 * dy2);
        if (l2 < ARROW_MIN_RADIUS) {
            edgeX = cx + ux * ARROW_MIN_RADIUS;
            edgeY = cy + uy * ARROW_MIN_RADIUS;
        }
        return new float[]{edgeX, edgeY};
    }

    // ------------------------------------------------------------------
    // Rendering (canvas-side)
    // ------------------------------------------------------------------

    public void render(Canvas canvas, DrawBatch batch) {
        if (batch == null || batch.empty()) return;

        for (BoxCmd c : batch.boxes) {
            Paint p = c.tier == TIER_HP_LOW ? boxLowPaint : boxPaint;
            p.setAlpha(c.alpha);
            canvas.drawRect(c.rect, p);
        }

        for (BarCmd c : batch.bars) {
            barBgPaint.setAlpha(c.alpha);
            canvas.drawRect(new RectF(c.x, c.y, c.x + HP_BAR_W, c.y + HP_BAR_H), barBgPaint);
            float w = HP_BAR_W * c.ratio;
            if (w > 0f) {
                barHpPaint.setColor(c.tier == TIER_HP_LOW ? Color.RED
                        : c.tier == TIER_HP_MID ? Color.YELLOW : Color.GREEN);
                barHpPaint.setAlpha(c.alpha);
                canvas.drawRect(new RectF(c.x, c.y, c.x + w, c.y + HP_BAR_H), barHpPaint);
            }
            float pw = HP_BAR_W * c.previewRatio;
            if (pw > w) {
                barPreviewPaint.setAlpha(c.alpha / 2);
                canvas.drawRect(new RectF(c.x + w, c.y, c.x + pw, c.y + HP_BAR_H), barPreviewPaint);
            }
            if (c.manaRatio > 0f) {
                barManaPaint.setAlpha(c.alpha);
                canvas.drawRect(new RectF(c.x, c.y + HP_BAR_H + 2f,
                        c.x + HP_BAR_W * c.manaRatio, c.y + HP_BAR_H + 4f), barManaPaint);
            }
        }

        for (LabelCmd c : batch.labels) {
            Paint p = c.style == STATE_RECALL ? labelPaint
                    : c.style == STATE_ULTR ? dotPaint : smallPaint;
            p.setAlpha(c.alpha);
            canvas.drawText(c.text, c.x, c.y, p);
        }

        for (ArrowCmd c : batch.arrows) {
            arrowPaint.setAlpha(c.alpha);
            float dx = c.ex - c.sx;
            float dy = c.ey - c.sy;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len < 1f) continue;
            float ux = dx / len;
            float uy = dy / len;
            canvas.drawLine(c.sx, c.sy, c.ex, c.ey, arrowPaint);
            float bx = c.ex - ux * 22f;
            float by = c.ey - uy * 22f;
            float px = -uy * 12f;
            float py = ux * 12f;
            canvas.drawLine(c.ex, c.ey, bx + px, by + py, arrowPaint);
            canvas.drawLine(c.ex, c.ey, bx - px, by - py, arrowPaint);
        }

        for (DotCmd c : batch.dots) {
            dotPaint.setColor(c.color);
            dotPaint.setAlpha(c.alpha);
            canvas.drawCircle(c.x, c.y, c.radius, dotPaint);
        }

        for (RingCmd c : batch.rings) {
            ringPaint.setAlpha(c.alpha);
            canvas.drawCircle(c.x, c.y, c.radius, ringPaint);
        }

        for (LineCmd c : batch.lines) {
            linePaint.setAlpha(c.alpha);
            canvas.drawLine(c.sx, c.sy, c.ex, c.ey, linePaint);
        }

        for (GhostDot g : batch.ghosts) {
            ghostPaint.setAlpha((int) g.seenMs);
            canvas.drawCircle(g.wx, g.wy, 14f, ghostPaint);
        }
    }

    /** Age out dead targets so memory stays bounded. */
    public void prune(long nowMs) {
        List<Integer> dead = new ArrayList<>();
        for (Map.Entry<Integer, TargetMemory> e : memory.entrySet()) {
            if (nowMs - e.getValue().firstSeenMs > 30_000L) {
                dead.add(e.getKey());
            }
        }
        for (Integer id : dead) memory.remove(id);
        ghosts.clear();
        for (TargetMemory tm : memory.values()) ghosts.addAll(tm.trail);
    }
}