package com.github.ma1co.openmemories.tweak;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

/**
 * Drifting 3D starfield + dual-harmonic oscilloscope + gliding live telemetry HUD.
 * Pure Android API 10 Canvas drawing; zero per-frame allocations; fades to pure black in FULL_DIM.
 */
final class ScreensaverView extends View {
    private static final int STAR_COUNT = 55;

    private final float[] starX = new float[STAR_COUNT];
    private final float[] starY = new float[STAR_COUNT];
    private final float[] starZ = new float[STAR_COUNT];
    private final float[] starSpd = new float[STAR_COUNT];

    private final Paint starPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint waveCyanPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint waveAmberPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint();
    private final Paint hudTitlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hudSubPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hudBoxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private long seed = 0x5A6000C0DE1L;
    private float phase = 0f;
    private float hudX = 36f;
    private float hudY = 56f;
    private float hudVx = 1.15f;
    private float hudVy = 0.72f;

    private float alpha = 1.0f;
    private boolean fullDim;
    private int rssiDbm = -50;
    private String line1 = "OPENMEMORIES";
    private String line2 = "Wi-Fi: --";
    private String line3 = "DIM IN 05:00";

    ScreensaverView(Context context) {
        super(context);
        setBackgroundColor(Color.BLACK);

        waveCyanPaint.setStyle(Paint.Style.STROKE);
        waveCyanPaint.setStrokeWidth(2.0f);
        waveAmberPaint.setStyle(Paint.Style.STROKE);
        waveAmberPaint.setStrokeWidth(1.5f);
        gridPaint.setStrokeWidth(1.0f);

        hudTitlePaint.setTypeface(Typeface.MONOSPACE);
        hudTitlePaint.setTextSize(17f);
        hudSubPaint.setTypeface(Typeface.MONOSPACE);
        hudSubPaint.setTextSize(13f);
        hudBoxPaint.setStyle(Paint.Style.STROKE);
        hudBoxPaint.setStrokeWidth(1.0f);

        for (int i = 0; i < STAR_COUNT; i++) {
            resetStar(i, true);
        }
    }

    void updateTelemetry(String l1, String l2, String l3, int rssi) {
        this.line1 = l1;
        this.line2 = l2;
        this.line3 = l3;
        this.rssiDbm = rssi;
    }

    void step(float currentAlpha, boolean isFullDim) {
        this.alpha = currentAlpha;
        this.fullDim = isFullDim;
        if (fullDim) {
            invalidate();
            return;
        }

        phase += 0.085f;
        for (int i = 0; i < STAR_COUNT; i++) {
            starZ[i] -= starSpd[i];
            if (starZ[i] <= 0.08f) {
                resetStar(i, false);
            }
        }

        int w = getWidth();
        int h = getHeight();
        if (w > 260 && h > 120) {
            float boxW = 248f;
            float boxH = 68f;
            hudX += hudVx;
            hudY += hudVy;
            if (hudX < 14f) { hudX = 14f; hudVx = -hudVx; }
            else if (hudX + boxW > w - 14f) { hudX = w - 14f - boxW; hudVx = -hudVx; }
            if (hudY < 14f) { hudY = 14f; hudVy = -hudVy; }
            else if (hudY + boxH > h - 14f) { hudY = h - 14f - boxH; hudVy = -hudVy; }
        }
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(Color.BLACK);
        if (fullDim || alpha <= 0.005f) return;

        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        float cx = w * 0.5f;
        float cy = h * 0.5f;

        gridPaint.setColor(Color.argb((int) (32 * alpha), 56, 189, 248));
        canvas.drawLine(24f, cy, w - 24f, cy, gridPaint);
        for (int gx = w / 8; gx < w; gx += w / 8) {
            canvas.drawLine(gx, cy - 6f, gx, cy + 6f, gridPaint);
        }

        for (int i = 0; i < STAR_COUNT; i++) {
            float invZ = 1.0f / starZ[i];
            float sx = cx + starX[i] * invZ * (w * 0.38f);
            float sy = cy + starY[i] * invZ * (h * 0.38f);
            if (sx < 0 || sx >= w || sy < 0 || sy >= h) {
                resetStar(i, false);
                continue;
            }
            float depth = 1.0f - starZ[i];
            if (depth < 0f) depth = 0f;
            int a = (int) ((45 + 185 * depth) * alpha);
            float radius = 0.9f + 2.1f * depth;
            if ((i & 3) == 0) {
                starPaint.setColor(Color.argb(a, 103, 232, 249));
            } else if ((i & 7) == 1) {
                starPaint.setColor(Color.argb(a, 251, 191, 36));
            } else {
                starPaint.setColor(Color.argb(a, 210, 224, 238));
            }
            canvas.drawCircle(sx, sy, radius, starPaint);
        }

        float sig = (rssiDbm + 95f) / 60f;
        if (sig < 0.25f) sig = 0.25f;
        if (sig > 1.0f) sig = 1.0f;
        float amp1 = h * 0.14f * sig;
        float amp2 = h * 0.08f * sig;

        waveCyanPaint.setColor(Color.argb((int) (150 * alpha), 34, 211, 238));
        waveAmberPaint.setColor(Color.argb((int) (115 * alpha), 245, 158, 11));

        float prevX = 24f;
        float prevY1 = cy;
        float prevY2 = cy;
        int steps = 72;
        float span = w - 48f;
        for (int s = 0; s <= steps; s++) {
            float u = (float) s / (float) steps;
            float env = (float) Math.sin(Math.PI * u);
            float x = 24f + u * span;
            float y1 = cy + env * amp1 * (float) (Math.sin(u * 12.566 + phase) * 0.72
                    + Math.sin(u * 25.132 - phase * 1.6) * 0.28);
            float y2 = cy + env * amp2 * (float) (Math.cos(u * 9.424 - phase * 0.85) * 0.65
                    + Math.sin(u * 18.849 + phase * 1.3) * 0.35);
            if (s > 0) {
                canvas.drawLine(prevX, prevY1, x, y1, waveCyanPaint);
                canvas.drawLine(prevX, prevY2, x, y2, waveAmberPaint);
            }
            prevX = x;
            prevY1 = y1;
            prevY2 = y2;
        }

        hudBoxPaint.setColor(Color.argb((int) (75 * alpha), 56, 189, 248));
        hudTitlePaint.setColor(Color.argb((int) (215 * alpha), 165, 243, 252));
        hudSubPaint.setColor(Color.argb((int) (175 * alpha), 253, 230, 138));
        canvas.drawRect(hudX, hudY, hudX + 248f, hudY + 68f, hudBoxPaint);
        canvas.drawText(line1, hudX + 10f, hudY + 22f, hudTitlePaint);
        canvas.drawText(line2, hudX + 10f, hudY + 42f, hudSubPaint);
        canvas.drawText(line3, hudX + 10f, hudY + 59f, hudSubPaint);
    }

    private void resetStar(int idx, boolean randomDepth) {
        starX[idx] = nextRandSigned();
        starY[idx] = nextRandSigned();
        if (Math.abs(starX[idx]) < 0.06f && Math.abs(starY[idx]) < 0.06f) {
            starX[idx] = 0.18f;
        }
        starZ[idx] = randomDepth ? (0.15f + 0.85f * nextRand01()) : 1.0f;
        starSpd[idx] = 0.008f + 0.012f * nextRand01();
    }

    private float nextRand01() {
        seed = (seed * 6364136223846793005L + 1442695040888963407L);
        return ((seed >>> 40) & 0xFFFFFFL) / (float) 0x1000000L;
    }

    private float nextRandSigned() {
        return nextRand01() * 2.0f - 1.0f;
    }
}
