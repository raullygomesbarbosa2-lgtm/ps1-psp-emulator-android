package com.raully.psxpsp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import com.raully.psxpsp.ps1.Ps1ControllerPort;

import java.util.HashMap;
import java.util.Map;

/** Translucent, multi-touch PlayStation-style digital controls. */
public final class Ps1TouchControlsView extends View {
    public interface Listener { void onButtonsChanged(int pressedMask); }

    private static final int BLUE = Color.rgb(143, 232, 255);
    private static final int[] BUTTON_MASKS = {
            Ps1ControllerPort.UP, Ps1ControllerPort.RIGHT, Ps1ControllerPort.DOWN,
            Ps1ControllerPort.LEFT, Ps1ControllerPort.TRIANGLE, Ps1ControllerPort.CIRCLE,
            Ps1ControllerPort.CROSS, Ps1ControllerPort.SQUARE, Ps1ControllerPort.SELECT,
            Ps1ControllerPort.START, Ps1ControllerPort.L1, Ps1ControllerPort.L2,
            Ps1ControllerPort.R1, Ps1ControllerPort.R2
    };
    private static final String[] LABELS = {
            "▲", "●", "▼", "◀", "△", "○", "×", "□", "SELECT", "START", "L1", "L2", "R1", "R2"
    };
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Map<Integer, Integer> pointerButtons = new HashMap<>();
    private Listener listener;
    private int pressedMask;

    public Ps1TouchControlsView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        setClickable(true);
        setContentDescription("Controles de toque do PlayStation: direcional, botões, Select, Start e ombros");
        fill.setColor(Color.argb(82, 45, 144, 180));
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(2));
        stroke.setColor(Color.argb(220, 143, 232, 255));
        label.setColor(Color.WHITE);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTextSize(dp(18));
        label.setFakeBoldText(true);
    }

    public int getPressedMask() { return pressedMask; }

    public void releaseAll() {
        pointerButtons.clear();
        setPressedMask(0);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        float size = Math.min(dp(46), h * .18f);
        // D-pad buttons around a small open center.
        drawControl(canvas, w * .19f, h * .68f - size * .82f, size, 0, "▲");
        drawControl(canvas, w * .19f + size * .82f, h * .68f, size, 1, "●");
        drawControl(canvas, w * .19f, h * .68f + size * .82f, size, 2, "▼");
        drawControl(canvas, w * .19f - size * .82f, h * .68f, size, 3, "◀");
        // Face buttons use the familiar diamond arrangement.
        float fx = w * .82f, fy = h * .67f, r = size * .58f;
        drawCircle(canvas, fx, fy - size * .85f, r, 4, "△");
        drawCircle(canvas, fx + size * .85f, fy, r, 5, "○");
        drawCircle(canvas, fx, fy + size * .85f, r, 6, "×");
        drawCircle(canvas, fx - size * .85f, fy, r, 7, "□");
        // Select/Start and shoulder buttons are individually visible and tappable.
        float pillW = Math.min(dp(76), w * .13f), pillH = Math.max(dp(28), h * .12f);
        drawPill(canvas, w * .44f, h * .87f, pillW, pillH, 8, "SELECT");
        drawPill(canvas, w * .56f, h * .87f, pillW, pillH, 9, "START");
        float shoulderW = Math.min(dp(62), w * .11f), shoulderH = Math.max(dp(26), h * .12f);
        drawPill(canvas, w * .12f, h * .15f, shoulderW, shoulderH, 10, "L1");
        drawPill(canvas, w * .25f, h * .15f, shoulderW, shoulderH, 11, "L2");
        drawPill(canvas, w * .75f, h * .15f, shoulderW, shoulderH, 12, "R2");
        drawPill(canvas, w * .88f, h * .15f, shoulderW, shoulderH, 13, "R1");
    }

    private void drawControl(Canvas c, float cx, float cy, float size, int id, String text) {
        float half = size * .46f;
        RectF r = new RectF(cx - half, cy - half, cx + half, cy + half);
        drawRect(c, r, id, text);
    }

    private void drawCircle(Canvas c, float cx, float cy, float radius, int id, String text) {
        fill.setColor(isPressed(id) ? Color.argb(180, 75, 185, 220) : Color.argb(95, 55, 135, 165));
        c.drawCircle(cx, cy, radius, fill);
        c.drawCircle(cx, cy, radius, stroke);
        drawLabel(c, cx, cy, text);
    }

    private void drawPill(Canvas c, float cx, float cy, float width, float height, int id, String text) {
        RectF r = new RectF(cx - width / 2, cy - height / 2, cx + width / 2, cy + height / 2);
        drawRect(c, r, id, text);
    }

    private void drawRect(Canvas c, RectF r, int id, String text) {
        fill.setColor(isPressed(id) ? Color.argb(185, 75, 185, 220) : Color.argb(82, 45, 144, 180));
        c.drawRoundRect(r, dp(9), dp(9), fill);
        c.drawRoundRect(r, dp(9), dp(9), stroke);
        drawLabel(c, r.centerX(), r.centerY(), text);
    }

    private void drawLabel(Canvas c, float cx, float cy, String text) {
        label.setTextSize(text.length() > 2 ? dp(10) : dp(18));
        c.drawText(text, cx, cy - (label.ascent() + label.descent()) / 2, label);
    }

    private boolean isPressed(int id) { return (pressedMask & BUTTON_MASKS[id]) != 0; }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN
                || action == MotionEvent.ACTION_MOVE) {
            for (int i = 0; i < event.getPointerCount(); i++) {
                int id = event.getPointerId(i);
                pointerButtons.put(id, hitTest(event.getX(i), event.getY(i)));
            }
            updateMask();
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP
                || action == MotionEvent.ACTION_CANCEL) {
            int index = action == MotionEvent.ACTION_CANCEL ? -1 : event.getActionIndex();
            if (index < 0) pointerButtons.clear();
            else pointerButtons.remove(event.getPointerId(index));
            // Retest the remaining pointers on their current coordinates.
            for (int i = 0; i < event.getPointerCount(); i++) {
                if (i == index) continue;
                pointerButtons.put(event.getPointerId(i), hitTest(event.getX(i), event.getY(i)));
            }
            updateMask();
            return true;
        }
        return true;
    }

    private int hitTest(float x, float y) {
        float w = getWidth(), h = getHeight();
        float size = Math.min(dp(46), h * .18f);
        float dx = x - w * .19f, dy = y - h * .68f;
        if (Math.abs(dx) < size * .82f && Math.abs(dy) < size * .82f) {
            if (Math.abs(dx) < size * .42f && Math.abs(dy) < size * .42f) return 0;
            if (Math.abs(dx) > Math.abs(dy)) return dx > 0 ? Ps1ControllerPort.RIGHT : Ps1ControllerPort.LEFT;
            return dy > 0 ? Ps1ControllerPort.DOWN : Ps1ControllerPort.UP;
        }
        float fx = w * .82f, fy = h * .67f;
        float[][] faces = {{fx, fy - size * .85f}, {fx + size * .85f, fy},
                {fx, fy + size * .85f}, {fx - size * .85f, fy}};
        for (int i = 0; i < faces.length; i++) {
            float ox = x - faces[i][0], oy = y - faces[i][1];
            if (ox * ox + oy * oy <= size * size * .58f * .58f) return BUTTON_MASKS[4 + i];
        }
        float pillH = Math.max(dp(28), h * .12f);
        float pillW = Math.min(dp(76), w * .13f);
        if (inside(x, y, w * .44f, h * .87f, pillW, pillH)) return Ps1ControllerPort.SELECT;
        if (inside(x, y, w * .56f, h * .87f, pillW, pillH)) return Ps1ControllerPort.START;
        float shoulderW = Math.min(dp(62), w * .11f), shoulderH = Math.max(dp(26), h * .12f);
        if (inside(x, y, w * .12f, h * .15f, shoulderW, shoulderH)) return Ps1ControllerPort.L1;
        if (inside(x, y, w * .25f, h * .15f, shoulderW, shoulderH)) return Ps1ControllerPort.L2;
        if (inside(x, y, w * .75f, h * .15f, shoulderW, shoulderH)) return Ps1ControllerPort.R2;
        if (inside(x, y, w * .88f, h * .15f, shoulderW, shoulderH)) return Ps1ControllerPort.R1;
        return 0;
    }

    private static boolean inside(float x, float y, float cx, float cy, float w, float h) {
        return Math.abs(x - cx) <= w / 2 && Math.abs(y - cy) <= h / 2;
    }

    private void updateMask() {
        int mask = 0;
        for (int value : pointerButtons.values()) mask |= value;
        setPressedMask(mask);
    }

    private void setPressedMask(int mask) {
        if (pressedMask == mask) return;
        pressedMask = mask;
        invalidate();
        if (listener != null) listener.onButtonsChanged(mask);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
