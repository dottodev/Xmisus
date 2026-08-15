package com.shadow.mlbbcheat.aim;

import static org.junit.Assert.*;

import com.shadow.mlbbcheat.models.PlayerData;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class AimEngineTest {

    @Test
    public void selectTarget_prefersKillableLowHp() {
        PlayerData low = new PlayerData(1, true, 100f, 0f, 50f);
        PlayerData close = new PlayerData(2, true, 200f, 0f, 3000f);
        AimEngine.Target t = AimEngine.selectTarget(
                Arrays.asList(low, close), 0f, 0f, 2f, 100f, 300f);
        assertEquals(1, t.player.id);
        assertTrue(t.killable);
    }

    @Test
    public void selectTarget_fallsBackToClosest() {
        PlayerData far = new PlayerData(1, true, 500f, 0f, 3000f);
        PlayerData near = new PlayerData(2, true, 100f, 0f, 3000f);
        AimEngine.Target t = AimEngine.selectTarget(
                Arrays.asList(far, near), 0f, 0f, 2f, 100f, 600f);
        assertEquals(2, t.player.id);
        assertFalse(t.killable);
    }

    @Test
    public void selectTarget_ignoresOutOfRange() {
        PlayerData far = new PlayerData(1, true, 5000f, 0f, 50f);
        assertNull(AimEngine.selectTarget(
                Arrays.asList(far), 0f, 0f, 2f, 100f, 600f));
    }

    @Test
    public void selectTarget_ignoresAlliesAndDead() {
        PlayerData ally = new PlayerData(1, false, 50f, 0f, 100f);
        PlayerData dead = new PlayerData(2, true, 60f, 0f, 0f);
        assertNull(AimEngine.selectTarget(
                Arrays.asList(ally, dead), 0f, 0f, 2f, 100f, 600f));
    }

    @Test
    public void project_scalesByZoom() {
        float[] p = AimEngine.project(250f, 125f, 2f);
        assertEquals(500f, p[0], 0.001f);
        assertEquals(250f, p[1], 0.001f);
    }

    @Test
    public void skillDragVector_pointsTowardTarget() {
        float[] drag = AimEngine.skillDragVector(0f, 0f, 500f, 0f, 100f);
        // magnitude = aim radius + human-error noise (capped per axis)
        assertTrue("x=" + drag[0], drag[0] > 90f);
        assertTrue("x=" + drag[0], drag[0] < 115f);
        assertTrue("y=" + drag[1], Math.abs(drag[1]) <= 15f);
    }

    @Test
    public void skillDragVector_zeroLengthNoOp() {
        float[] drag = AimEngine.skillDragVector(50f, 50f, 50f, 50f, 100f);
        assertEquals(0f, drag[0], 0.001f);
        assertEquals(0f, drag[1], 0.001f);
    }

    @Test
    public void leadTarget_leadsInDirectionOfMotion() {
        PlayerData moving = new PlayerData(1, true, 1000f, 0f, 100f);
        float[] lead = AimEngine.leadTarget(
                moving, new float[]{500f, 0f}, 2f, 1000f);
        assertTrue("lead x " + lead[0], lead[0] > 2000f);
    }

    @Test
    public void skillRangePx_scales() {
        assertEquals(600f, AimEngine.skillRangePx(300f, 2f), 0.001f);
    }
}
