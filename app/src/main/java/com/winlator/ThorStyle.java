package com.winlator;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

import java.util.Random;

/**
 * The Redone series' interface language, shared with FNaF 1 and FNaF 2 Redone: monospace type, white
 * text with a >> marker, the gray Shift Manager frame and square tabs, outlined boxes and a faint
 * terminal grain. Everything here is original interface art.
 */
final class ThorStyle {
    // Shift Manager (FNaF 1 / FNaF 2 Redone values)
    static final int BG = 0xff000000;
    static final int PANEL = 0xff202020;
    static final int PANEL_HI = 0xff393939;
    static final int INNER = 0xff161616;
    static final int FIELD = 0xff242424;
    static final int FIELD_FOCUS = 0xff4c4c47;
    static final int TAB = 0xff303030;
    static final int FRAME = 0xff555555;
    static final int SHADOW = 0xff090909;
    static final int RULE = 0xff555555;
    static final int OUTLINE = 0xff777777;
    static final int OUTLINE_ON = 0xffcccccc;
    static final int OUTLINE_FOCUS = 0xffd0d0c8;
    static final int PANEL_EDGE = 0xff8a8a8a;
    static final int TEXT = 0xffeeeeea;
    static final int TEXT_HI = 0xffffffff;
    static final int MUTED = 0xffb9b9b9;
    static final int DISABLED = 0xff666666;
    static final int GREEN = 0xff96c149;
    static final int RED = 0xffd65a4a;

    // Night Security Desk (FNaF 2 Redone terminal values)
    static final int DESK_BG = 0xff111613;
    static final int DESK_HEADER = 0xff30382f;
    static final int DESK_TEXT = 0xffb9c2ac;
    static final int DESK_LINE = 0xff677362;
    static final int DESK_BUTTON = 0xbb141714;
    static final int DESK_BUTTON_ON = 0xff454b36;
    static final int DESK_EDGE = 0xff72786b;
    static final int DESK_EDGE_ON = 0xffd4e3a7;
    static final int DESK_LAMP = 0xffd6e5a3;
    static final int DESK_LAMP_OFF = 0xff343c32;

    // Compatibility names used by the sheet and monitor hints
    static final int OLIVE_DIM = RULE;
    static final int OLIVE = OUTLINE;
    static final int OLIVE_LIGHT = OUTLINE_ON;
    static final int AMBER = OUTLINE_FOCUS;
    static final int AMBER_HI = TEXT_HI;
    static final int PHOSPHOR = GREEN;
    static final int RUST = RED;

    static final Typeface MONO = Typeface.MONOSPACE;

    private static Bitmap[] grain;

    private ThorStyle() {}

    static Paint textPaint() {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG | Paint.FILTER_BITMAP_FLAG);
        paint.setTypeface(MONO);
        return paint;
    }

    static void text(Canvas canvas, Paint paint, String value, float x, float y, float size, int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        paint.setTextSize(size);
        paint.setColor(color);
        canvas.drawText(value, x, y, paint);
    }

    static void textRight(Canvas canvas, Paint paint, String value, float right, float y, float size, int color) {
        paint.setTextSize(size);
        text(canvas, paint, value, right - paint.measureText(value), y, size, color);
    }

    static float width(Paint paint, String value, float size) {
        paint.setTextSize(size);
        return paint.measureText(value);
    }

    static void fill(Canvas canvas, Paint paint, float l, float t, float r, float b, int color) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        canvas.drawRect(l, t, r, b, paint);
    }

    static void outline(Canvas canvas, Paint paint, float l, float t, float r, float b, float width, int color) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
        paint.setColor(color);
        float half = width / 2;
        canvas.drawRect(l + half, t + half, r - half, b - half, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    /** Shift Manager frame used by the lower terminal and the pop-up pages. */
    static void frame(Canvas canvas, Paint paint, RectF box, float u) {
        fill(canvas, paint, box.left, box.top, box.right, box.bottom, PANEL);
        outline(canvas, paint, box.left, box.top, box.right, box.bottom, 2 * u, FRAME);
        outline(canvas, paint, box.left + 11 * u, box.top + 11 * u, box.right - 11 * u, box.bottom - 11 * u,
                2 * u, SHADOW);
    }

    /** Outlined box with a left-aligned label, as in the FNaF 1 and 2 Shift Manager. */
    static void box(Canvas canvas, Paint paint, String label, float l, float t, float r, float b, float size,
                    boolean active, boolean focus, boolean enabled, float u) {
        fill(canvas, paint, l, t, r, b, active || focus ? FIELD_FOCUS : FIELD);
        outline(canvas, paint, l, t, r, b, (focus ? 3 : 2) * u,
                focus ? TEXT_HI : active ? OUTLINE_FOCUS : enabled ? OUTLINE : DISABLED);
        Paint.FontMetrics metrics = metrics(paint, size);
        text(canvas, paint, label, l + 14 * u, (t + b) / 2 - (metrics.ascent + metrics.descent) / 2, size,
                enabled ? TEXT : DISABLED);
    }

    static Paint.FontMetrics metrics(Paint paint, float size) {
        paint.setTextSize(size);
        return paint.getFontMetrics();
    }

    /** Physical-button prompt: an outlined keycap with a short binding label. */
    static void keycap(Canvas canvas, Paint paint, float l, float t, float r, float b, String label,
                       int edge, int textColor, float u) {
        outline(canvas, paint, l, t, r, b, 2 * u, edge);
        float size = Math.min((b - t) * .55f, (r - l - 12 * u) / Math.max(1, label.length()) / .62f);
        paint.setTextSize(size);
        float width = paint.measureText(label);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        float baseline = (t + b) / 2 - (metrics.ascent + metrics.descent) / 2;
        text(canvas, paint, label, (l + r - width) / 2, baseline, size, textColor);
    }

    static float keycapWidth(Paint paint, String label, float height, float u) {
        paint.setTextSize(height * .55f);
        return Math.max(height * 1.15f, paint.measureText(label) + 22 * u);
    }

    private static synchronized Bitmap[] grain() {
        if (grain == null) {
            grain = new Bitmap[4];
            Random random = new Random(2015);
            int[] pixels = new int[640 * 360];
            for (int frame = 0; frame < grain.length; ++frame) {
                for (int i = 0; i < pixels.length; ++i) pixels[i] = (random.nextInt(39) << 24) | 0x00ffffff;
                grain[frame] = Bitmap.createBitmap(pixels, 640, 360, Bitmap.Config.ARGB_8888);
            }
        }
        return grain;
    }

    /** The series' terminal texture: moving grain, 4 px scanlines and one slow bright sweep. */
    static void terminal(Canvas canvas, Paint paint, RectF area, long now, float u, int grainAlpha) {
        int save = canvas.save();
        canvas.clipRect(area);
        paint.setShader(null);
        paint.setColor(Color.WHITE);
        paint.setAlpha(grainAlpha);
        canvas.drawBitmap(grain()[(int) (now / 110 % 4)], null, area, paint);
        paint.setAlpha(255);
        paint.setColor(0x1a000000);
        paint.setStrokeWidth(Math.max(1, u));
        float step = 4 * Math.max(1, u);
        for (float y = area.top; y < area.bottom; y += step) canvas.drawLine(area.left, y, area.right, y, paint);
        float sweep = area.top + now % 7000 / 7000f * (area.height() + 40 * u) - 20 * u;
        paint.setColor(0x0cffffff);
        canvas.drawRect(area.left, sweep, area.right, sweep + 20 * u, paint);
        canvas.restoreToCount(save);
    }

    /** Title-screen interference: grain plus a few broad horizontal streaks, as on the FNaF 2 title. */
    static void interference(Canvas canvas, Paint paint, RectF area, long now, float u) {
        terminal(canvas, paint, area, now, u, 46);
        Random random = new Random(now / 90);
        paint.setShader(null);
        for (int i = 0; i < 16; ++i) {
            float y = area.top + random.nextFloat() * area.height();
            float length = area.width() * (.05f + random.nextFloat() * .3f);
            float x = area.left + random.nextFloat() * (area.width() - length);
            paint.setColor(Color.argb(10 + random.nextInt(26), 255, 255, 255));
            canvas.drawRect(x, y, x + length, y + (1 + random.nextInt(3)) * u, paint);
        }
    }

    /** Left and bottom shade that keeps the menu readable over live title art. */
    static void titleShade(Canvas canvas, Paint paint, RectF area, float u) {
        paint.setShader(new LinearGradient(area.left, 0, area.left + 1180 * u, 0,
                new int[]{0xff000000, 0xff000000, 0xc0000000, 0x00000000},
                new float[]{0f, .6f, .8f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(area, paint);
        paint.setShader(new LinearGradient(0, area.bottom - 230 * u, 0, area.bottom,
                new int[]{0x00000000, 0xe6000000, 0xff000000}, new float[]{0f, .55f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(area.left, area.bottom - 230 * u, area.right, area.bottom, paint);
        paint.setShader(null);
    }
}
