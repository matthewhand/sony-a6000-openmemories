package com.github.ma1co.openmemories.tweak;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Locale;

/**
 * Multi-style burn-in-safe Canvas screensaver (Android API 10 compatible):
 *   1. Matrix Rain (default)
 *   2. Motion Gallery (recent SD-card motion stills, graceful Matrix fallback when empty)
 *   3. Starfield Warp (3D parallax starfield)
 *   4. Digital Clock (drifting NTP-synced time & date)
 *   5. a6000 Bouncer (classic corner-bouncing neon emblem)
 *   6. Minimal Dark (single gentle breathing standby indicator)
 *
 * Debug telemetry HUD is optional (default ON, toggleable OFF).
 */
final class ScreensaverView extends View {
    /**
     * Non-zero RGB565 near-black (0x0841) so Sony's hardware video overlay colorkey (0x0000)
     * never punches the camera overlay through the screensaver canvas.
     */
    static final int BG_NON_COLORKEY_BLACK = Color.rgb(8, 8, 8);
    private static final int STAR_COUNT = 64;
    private static final int MATRIX_COLS = 28;
    private static final String MATRIX_GLYPHS = "0123456789ABCDEF$#@*+<>:=ｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾅﾆﾇﾈﾉ";
    private static final int[] BOUNCER_COLORS = new int[] {
            Color.rgb(34, 211, 238),
            Color.rgb(74, 222, 128),
            Color.rgb(251, 191, 36),
            Color.rgb(244, 114, 182),
            Color.rgb(129, 140, 248)
    };

    private final float[] starX = new float[STAR_COUNT];
    private final float[] starY = new float[STAR_COUNT];
    private final float[] starZ = new float[STAR_COUNT];
    private final float[] starSpd = new float[STAR_COUNT];

    private final float[] colY = new float[MATRIX_COLS];
    private final float[] colSpd = new float[MATRIX_COLS];
    private final int[] colLen = new int[MATRIX_COLS];
    private final int[] colCharSeed = new int[MATRIX_COLS];

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint matrixPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clockPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint datePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hudTitlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hudSubPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hudBoxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hudBgPaint = new Paint();
    private final Rect srcRect = new Rect();
    private final RectF dstRect = new RectF();

    private long seed = 0x5A6000C0DE1L;
    private int tickCount;
    private float hudX = 24f;
    private float hudY = 30f;
    private float hudVx = 1.05f;
    private float hudVy = 0.68f;

    private float bounceX = 64f;
    private float bounceY = 64f;
    private float bounceVx = 2.1f;
    private float bounceVy = 1.55f;
    private int bounceColorIdx;

    private float clockX = 48f;
    private float clockY = 96f;
    private float clockVx = 0.65f;
    private float clockVy = 0.45f;

    private float alpha = 1.0f;
    private boolean fullDim;
    private boolean showHud = true;
    private ScreensaverPolicy.Style style = ScreensaverPolicy.Style.MATRIX;
    private Bitmap galleryBitmap;
    private String galleryCaption = "";

    private String line1 = "A6000 HUB";
    private String line2 = "Wi-Fi: --";
    private String line3 = "DIM 05:00";

    ScreensaverView(Context context) {
        super(context);
        setBackgroundColor(BG_NON_COLORKEY_BLACK);

        matrixPaint.setTypeface(Typeface.MONOSPACE);
        matrixPaint.setTextSize(15f);

        clockPaint.setTypeface(Typeface.MONOSPACE);
        clockPaint.setTextSize(42f);
        clockPaint.setFakeBoldText(true);

        datePaint.setTypeface(Typeface.MONOSPACE);
        datePaint.setTextSize(15f);

        hudTitlePaint.setTypeface(Typeface.MONOSPACE);
        hudTitlePaint.setTextSize(15f);
        hudSubPaint.setTypeface(Typeface.MONOSPACE);
        hudSubPaint.setTextSize(12f);
        hudBoxPaint.setStyle(Paint.Style.STROKE);
        hudBoxPaint.setStrokeWidth(1.0f);

        for (int i = 0; i < STAR_COUNT; i++) resetStar(i, true);
        for (int i = 0; i < MATRIX_COLS; i++) resetColumn(i, true);
    }

    void setStyle(ScreensaverPolicy.Style newStyle) {
        this.style = newStyle == null ? ScreensaverPolicy.Style.MATRIX : newStyle;
    }

    void setShowHud(boolean enabled) {
        this.showHud = enabled;
    }

    void setGalleryBitmap(Bitmap bmp, String caption) {
        Bitmap prev = this.galleryBitmap;
        this.galleryBitmap = bmp;
        this.galleryCaption = caption == null ? "" : caption;
        if (prev != null && prev != bmp && !prev.isRecycled()) {
            prev.recycle();
        }
    }

    void clearGalleryBitmap() {
        setGalleryBitmap(null, "");
    }

    void updateTelemetry(String l1, String l2, String l3, int rssi) {
        this.line1 = l1;
        this.line2 = l2;
        this.line3 = l3;
    }

    void step(float currentAlpha, boolean isFullDim) {
        this.alpha = currentAlpha;
        this.fullDim = isFullDim;
        if (fullDim) {
            invalidate();
            return;
        }
        tickCount++;
        int w = getWidth();
        int h = getHeight();

        if (style == ScreensaverPolicy.Style.MATRIX || (style == ScreensaverPolicy.Style.GALLERY && galleryBitmap == null)) {
            int maxRows = Math.max(18, (h > 0 ? h / 16 : 20) + 12);
            for (int i = 0; i < MATRIX_COLS; i++) {
                colY[i] += colSpd[i];
                if ((tickCount + i) % 3 == 0) {
                    colCharSeed[i] = (colCharSeed[i] + 7) % MATRIX_GLYPHS.length();
                }
                if (colY[i] - colLen[i] > maxRows) {
                    resetColumn(i, false);
                }
            }
        } else if (style == ScreensaverPolicy.Style.STARFIELD) {
            for (int i = 0; i < STAR_COUNT; i++) {
                starZ[i] -= starSpd[i];
                if (starZ[i] <= 0.08f) resetStar(i, false);
            }
        } else if (style == ScreensaverPolicy.Style.BOUNCER && w > 180 && h > 90) {
            float boxW = 148f;
            float boxH = 52f;
            bounceX += bounceVx;
            bounceY += bounceVy;
            boolean hit = false;
            if (bounceX < 8f) { bounceX = 8f; bounceVx = -bounceVx; hit = true; }
            else if (bounceX + boxW > w - 8f) { bounceX = w - 8f - boxW; bounceVx = -bounceVx; hit = true; }
            if (bounceY < 8f) { bounceY = 8f; bounceVy = -bounceVy; hit = true; }
            else if (bounceY + boxH > h - 8f) { bounceY = h - 8f - boxH; bounceVy = -bounceVy; hit = true; }
            if (hit) bounceColorIdx = (bounceColorIdx + 1) % BOUNCER_COLORS.length;
        } else if (style == ScreensaverPolicy.Style.CLOCK && w > 240 && h > 110) {
            float cw = 216f;
            float ch = 72f;
            clockX += clockVx;
            clockY += clockVy;
            if (clockX < 18f) { clockX = 18f; clockVx = -clockVx; }
            else if (clockX + cw > w - 18f) { clockX = w - 18f - cw; clockVx = -clockVx; }
            if (clockY < 56f) { clockY = 56f; clockVy = -clockVy; }
            else if (clockY + ch > h - 18f) { clockY = h - 18f - ch; clockVy = -clockVy; }
        }

        if (showHud && w > 260 && h > 120) {
            float boxW = 248f;
            float boxH = 64f;
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
        if (fullDim || alpha <= 0.005f) {
            canvas.drawColor(Color.BLACK);
            return;
        }
        canvas.drawColor(BG_NON_COLORKEY_BLACK);

        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        switch (style) {
            case GALLERY:
                if (galleryBitmap != null && !galleryBitmap.isRecycled()) {
                    drawGallery(canvas, w, h);
                } else {
                    drawMatrix(canvas, w, h);
                    if (showHud) {
                        hudSubPaint.setColor(Color.argb((int) (170 * alpha), 74, 222, 128));
                        canvas.drawText("Motion Gallery: waiting for first motion still...", 16f, h - 14f, hudSubPaint);
                    }
                }
                break;
            case STARFIELD:
                drawStarfield(canvas, w, h);
                break;
            case CLOCK:
                drawClock(canvas, w, h);
                break;
            case BOUNCER:
                drawBouncer(canvas);
                break;
            case MINIMAL:
                drawMinimal(canvas, w, h);
                break;
            case MATRIX:
            default:
                drawMatrix(canvas, w, h);
                break;
        }

        if (showHud) {
            drawHud(canvas);
        }
    }

    private void drawMatrix(Canvas canvas, int w, int h) {
        float colW = (float) w / (float) MATRIX_COLS;
        float rowH = 16f;
        int glyphCount = MATRIX_GLYPHS.length();
        for (int c = 0; c < MATRIX_COLS; c++) {
            float x = c * colW + 2f;
            int headRow = (int) colY[c];
            int len = colLen[c];
            for (int t = 0; t < len; t++) {
                int row = headRow - t;
                float y = row * rowH;
                if (y < 0f || y > h + rowH) continue;
                int idx = Math.abs(colCharSeed[c] + row * 13 + c * 5) % glyphCount;
                String ch = MATRIX_GLYPHS.substring(idx, idx + 1);
                if (t == 0) {
                    matrixPaint.setColor(Color.argb((int) (245 * alpha), 225, 255, 235));
                } else {
                    float fade = 1.0f - ((float) t / (float) len);
                    int g = (int) (70 + 185 * fade);
                    int a = (int) ((35 + 195 * fade) * alpha);
                    matrixPaint.setColor(Color.argb(a, 0, g, (int) (35 * fade)));
                }
                canvas.drawText(ch, x, y, matrixPaint);
            }
        }
    }

    private void drawGallery(Canvas canvas, int w, int h) {
        int bw = galleryBitmap.getWidth();
        int bh = galleryBitmap.getHeight();
        if (bw <= 0 || bh <= 0) return;
        float scale = Math.min((float) w / (float) bw, (float) h / (float) bh);
        float dw = bw * scale;
        float dh = bh * scale;
        float left = (w - dw) * 0.5f;
        float top = (h - dh) * 0.5f;
        srcRect.set(0, 0, bw, bh);
        dstRect.set(left, top, left + dw, top + dh);
        paint.setAlpha((int) (255 * alpha));
        canvas.drawBitmap(galleryBitmap, srcRect, dstRect, paint);
        paint.setAlpha(255);

        if (galleryCaption.length() > 0) {
            hudBgPaint.setColor(Color.argb((int) (165 * alpha), 0, 0, 0));
            canvas.drawRect(0f, h - 26f, w, h, hudBgPaint);
            hudSubPaint.setColor(Color.argb((int) (220 * alpha), 165, 243, 252));
            canvas.drawText("MOTION GALLERY  " + galleryCaption, 12f, h - 8f, hudSubPaint);
        }
    }

    private void drawStarfield(Canvas canvas, int w, int h) {
        float cx = w * 0.5f;
        float cy = h * 0.5f;
        for (int i = 0; i < STAR_COUNT; i++) {
            float invZ = 1.0f / starZ[i];
            float sx = cx + starX[i] * invZ * (w * 0.40f);
            float sy = cy + starY[i] * invZ * (h * 0.40f);
            if (sx < 0 || sx >= w || sy < 0 || sy >= h) {
                resetStar(i, false);
                continue;
            }
            float depth = Math.max(0f, 1.0f - starZ[i]);
            int a = (int) ((45 + 200 * depth) * alpha);
            float radius = 0.8f + 2.3f * depth;
            if ((i & 3) == 0) paint.setColor(Color.argb(a, 103, 232, 249));
            else if ((i & 7) == 1) paint.setColor(Color.argb(a, 251, 191, 36));
            else paint.setColor(Color.argb(a, 224, 236, 248));
            canvas.drawCircle(sx, sy, radius, paint);
        }
    }

    private void drawClock(Canvas canvas, int w, int h) {
        Calendar c = new GregorianCalendar();
        String timeStr = String.format(Locale.US, "%02d:%02d:%02d",
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND));
        String dateStr = String.format(Locale.US, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
        clockPaint.setColor(Color.argb((int) (230 * alpha), 165, 243, 252));
        datePaint.setColor(Color.argb((int) (175 * alpha), 148, 163, 184));
        canvas.drawText(timeStr, clockX, clockY, clockPaint);
        canvas.drawText(dateStr, clockX + 4f, clockY + 24f, datePaint);
    }

    private void drawBouncer(Canvas canvas) {
        int rgb = BOUNCER_COLORS[bounceColorIdx];
        int a = (int) (230 * alpha);
        int c = Color.argb(a, Color.red(rgb), Color.green(rgb), Color.blue(rgb));
        hudBoxPaint.setColor(c);
        canvas.drawRect(bounceX, bounceY, bounceX + 148f, bounceY + 52f, hudBoxPaint);
        clockPaint.setTextSize(26f);
        clockPaint.setColor(c);
        canvas.drawText("a6000", bounceX + 18f, bounceY + 32f, clockPaint);
        clockPaint.setTextSize(42f);
        hudSubPaint.setColor(Color.argb((int) (185 * alpha), 226, 232, 240));
        canvas.drawText("E-MOUNT LAN", bounceX + 18f, bounceY + 46f, hudSubPaint);
    }

    private void drawMinimal(Canvas canvas, int w, int h) {
        float pulse = 0.35f + 0.65f * (float) (0.5 + 0.5 * Math.sin(tickCount * 0.08));
        float dx = (w * 0.5f) + (float) Math.sin(tickCount * 0.011) * (w * 0.25f);
        float dy = (h * 0.5f) + (float) Math.cos(tickCount * 0.009) * (h * 0.20f);
        paint.setColor(Color.argb((int) (160 * pulse * alpha), 34, 211, 238));
        canvas.drawCircle(dx, dy, 3.2f, paint);
    }

    private void drawHud(Canvas canvas) {
        hudBgPaint.setColor(Color.argb((int) (145 * alpha), 0, 0, 0));
        hudBoxPaint.setColor(Color.argb((int) (95 * alpha), 56, 189, 248));
        hudTitlePaint.setColor(Color.argb((int) (220 * alpha), 165, 243, 252));
        hudSubPaint.setColor(Color.argb((int) (185 * alpha), 253, 230, 138));
        canvas.drawRect(hudX, hudY, hudX + 248f, hudY + 64f, hudBgPaint);
        canvas.drawRect(hudX, hudY, hudX + 248f, hudY + 64f, hudBoxPaint);
        canvas.drawText(line1, hudX + 10f, hudY + 20f, hudTitlePaint);
        canvas.drawText(line2, hudX + 10f, hudY + 38f, hudSubPaint);
        canvas.drawText(line3, hudX + 10f, hudY + 55f, hudSubPaint);
    }

    private void resetColumn(int idx, boolean randomStart) {
        colY[idx] = randomStart ? (-10f + nextRand01() * 34f) : (-2f - nextRand01() * 14f);
        colSpd[idx] = 0.25f + nextRand01() * 0.55f;
        colLen[idx] = 6 + (int) (nextRand01() * 11f);
        colCharSeed[idx] = (int) (nextRand01() * MATRIX_GLYPHS.length());
    }

    private void resetStar(int idx, boolean randomDepth) {
        starX[idx] = nextRandSigned();
        starY[idx] = nextRandSigned();
        if (Math.abs(starX[idx]) < 0.06f && Math.abs(starY[idx]) < 0.06f) starX[idx] = 0.18f;
        starZ[idx] = randomDepth ? (0.15f + 0.85f * nextRand01()) : 1.0f;
        starSpd[idx] = 0.008f + 0.013f * nextRand01();
    }

    private float nextRand01() {
        seed = (seed * 6364136223846793005L + 1442695040888963407L);
        return ((seed >>> 40) & 0xFFFFFFL) / (float) 0x1000000L;
    }

    private float nextRandSigned() {
        return nextRand01() * 2.0f - 1.0f;
    }
}
