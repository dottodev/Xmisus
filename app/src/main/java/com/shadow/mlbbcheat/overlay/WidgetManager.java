package com.shadow.mlbbcheat.overlay;

import android.content.Context;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;

public class WidgetManager {

    private final WindowManager windowManager;
    private final View widgetView;
    private final WindowManager.LayoutParams params;
    private boolean visible = false;

    public interface ToggleListener {
        void onToggle(String feature, boolean enabled);
    }

    public WidgetManager(Context context, ToggleListener listener) {
        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);

        Button espBtn = new Button(context);
        espBtn.setText("ESP: ON");
        espBtn.setOnClickListener(v -> {
            boolean on = espBtn.getText().toString().endsWith("ON");
            espBtn.setText("ESP: " + (on ? "OFF" : "ON"));
            listener.onToggle("esp", !on);
        });

        Button droneBtn = new Button(context);
        droneBtn.setText("Drone: OFF");
        droneBtn.setOnClickListener(v -> {
            boolean on = droneBtn.getText().toString().endsWith("ON");
            droneBtn.setText("Drone: " + (on ? "OFF" : "ON"));
            listener.onToggle("drone", !on);
        });

        Button aimBtn = new Button(context);
        aimBtn.setText("Aim: OFF");
        aimBtn.setOnClickListener(v -> {
            boolean on = aimBtn.getText().toString().endsWith("ON");
            aimBtn.setText("Aim: " + (on ? "OFF" : "ON"));
            listener.onToggle("aim", !on);
        });

        root.addView(espBtn);
        root.addView(droneBtn);
        root.addView(aimBtn);
        widgetView = root;

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 20;
        params.y = 200;

        widgetView.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_MOVE:
                    params.x = (int) event.getRawX() - widgetView.getWidth() / 2;
                    params.y = (int) event.getRawY() - widgetView.getHeight() / 2;
                    windowManager.updateViewLayout(widgetView, params);
                    return true;
                default:
                    return false;
            }
        });
    }

    public void show() {
        if (visible) return;
        windowManager.addView(widgetView, params);
        visible = true;
    }

    public void hide() {
        if (!visible) return;
        windowManager.removeView(widgetView);
        visible = false;
    }

    public boolean isVisible() {
        return visible;
    }
}
