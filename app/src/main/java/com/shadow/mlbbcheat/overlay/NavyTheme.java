package com.shadow.mlbbcheat.overlay;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;

/** Navy-blue + pure-white theme shared by the overlay and the launcher. */
public final class NavyTheme {

    public static final int NAVY_BG = Color.parseColor("#0A1628");
    public static final int NAVY_PANEL = Color.parseColor("#0D1B2E");
    public static final int NAVY_SURFACE = Color.parseColor("#14263F");
    public static final int NAVY_BORDER = Color.parseColor("#1E3A5F");
    public static final int WHITE = Color.parseColor("#FFFFFF");
    public static final int TEXT_MUTED = Color.parseColor("#A8B8CC");
    public static final int TEXT_DIM = Color.parseColor("#6B7F99");

    private NavyTheme() {
    }

    public static GradientDrawable rounded(Context c, int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    public static GradientDrawable bordered(Context c, int fill, int radiusDp, int borderColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        g.setStroke(dp(c, 1), borderColor);
        return g;
    }

    public static GradientDrawable navyGradient(int top, int bottom) {
        return new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, bottom});
    }

    public static int dp(Context c, int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }
}