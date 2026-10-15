package com.raully.psxpsp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Looper;
import android.view.View;

import com.raully.psxpsp.ps1.Ps1VideoFrame;

/** Native Android Canvas presentation for snapshots emitted by the PS1 engine. */
public final class Ps1VideoFrameView extends View {
    private final Paint bitmapPaint = new Paint();
    private final Paint placeholderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Bitmap bitmap;

    public Ps1VideoFrameView(Context context) {
        super(context);
        setBackgroundColor(Color.BLACK);
        setContentDescription("Saída de vídeo do núcleo PS1");
        bitmapPaint.setFilterBitmap(false);
        bitmapPaint.setDither(false);
        placeholderPaint.setColor(Color.rgb(143, 205, 225));
        placeholderPaint.setTextSize(16 * getResources().getDisplayMetrics().density);
        placeholderPaint.setTextAlign(Paint.Align.CENTER);
    }

    /** Present the newest frame; safe to call from a future emulation thread. */
    public void present(Ps1VideoFrame frame) {
        if (frame == null) throw new IllegalArgumentException("frame required");
        if (Looper.myLooper() != Looper.getMainLooper()) {
            post(() -> present(frame));
            return;
        }
        int width = frame.getWidth();
        int height = frame.getHeight();
        int[] pixels = frame.copyArgb();
        if (bitmap == null || bitmap.getWidth() != width || bitmap.getHeight() != height) {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        invalidate();
    }

    public void clearFrame() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            post(this::clearFrame);
            return;
        }
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        bitmap = null;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.BLACK);
        if (bitmap == null) {
            canvas.drawText("Aguardando quadro PS1", getWidth() / 2f,
                    getHeight() / 2f, placeholderPaint);
            return;
        }
        if (getWidth() <= 0 || getHeight() <= 0) return;
        float scale = Math.min(getWidth() / (float) bitmap.getWidth(),
                getHeight() / (float) bitmap.getHeight());
        float drawWidth = bitmap.getWidth() * scale;
        float drawHeight = bitmap.getHeight() * scale;
        float left = (getWidth() - drawWidth) / 2f;
        float top = (getHeight() - drawHeight) / 2f;
        canvas.drawBitmap(bitmap, null,
                new RectF(left, top, left + drawWidth, top + drawHeight), bitmapPaint);
    }
}
