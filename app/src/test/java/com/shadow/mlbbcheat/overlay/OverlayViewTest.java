package com.shadow.mlbbcheat.overlay;

import static org.junit.Assert.*;

import android.graphics.RectF;

import com.shadow.mlbbcheat.models.PlayerData;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class OverlayViewTest {

    @Test
    public void worldToScreenRect_scalesWorldCoords() {
        PlayerData p = new PlayerData(1, true, 500f, 1000f, 100f);
        RectF r = OverlayView.worldToScreenRect(p, 2f);
        assertEquals(1000f, r.centerX(), 0.001f);
        assertEquals(2000f, r.centerY(), 0.001f);
    }

    @Test
    public void isInMapBounds_acceptsInsidePoints() {
        assertTrue(OverlayView.isInMapBounds(50f, 50f, 100f, 100f));
    }

    @Test
    public void isInMapBounds_rejectsOutsidePoints() {
        assertFalse(OverlayView.isInMapBounds(150f, 50f, 100f, 100f));
        assertFalse(OverlayView.isInMapBounds(50f, -5f, 100f, 100f));
    }

    @Test
    public void findNearestEnemy_returnsClosest() {
        PlayerData near = new PlayerData(1, true, 10f, 10f, 100f);
        PlayerData far = new PlayerData(2, true, 1000f, 1000f, 100f);
        PlayerData result = OverlayView.findNearestEnemy(
            java.util.Arrays.asList(near, far), 0f, 0f);
        assertEquals(1, result.id);
    }
}
