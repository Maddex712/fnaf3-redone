package com.winlator;

import android.app.Activity;
import android.app.Presentation;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.view.Display;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Lower screen while the boot and setup screen is up. It keeps the Shift Manager look in place of
 * the Android home screen and passes controller input to the activity, so the controller keeps
 * working even when the system moves input focus to the lower screen.
 */
final class ThorBootPanel extends View {
    private static final int BASE = 0xff0e0e0e;
    private static final int FRAME_FILL = 0xff161616;
    private static final int RAISED = 0xff1e1e1e;
    private static final int LINE = 0x1affffff;
    private static final int LINE_STRONG = 0x33ffffff;
    private static final int PRIMARY = 0xffecebe6;
    private static final int SECONDARY = 0xffa9a9a3;
    private static final int MUTED = 0xff6f6f6b;
    private static final int ACCENT = 0xff8fe05a;

    private final Paint paint = ThorStyle.textPaint();
    private String heading = "";
    private String[] lines = new String[0];
    private int lineColor = SECONDARY;
    private int progress = -1;
    private boolean menu;

    ThorBootPanel(Context context) {
        super(context);
    }

    /** Mirrors the boot screen: a heading, the status lines, optional progress and the menu hint. */
    void set(String heading, String[] lines, int lineColor, int progress, boolean menu) {
        this.heading = heading != null ? heading : "";
        this.lines = lines != null ? lines : new String[0];
        this.lineColor = lineColor == ThorStyle.RED ? 0xffd9644f : lineColor == ThorStyle.GREEN ? ACCENT : SECONDARY;
        this.progress = progress;
        this.menu = menu;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float scale = getHeight() / 1080f;
        float w = Math.min(1240, getWidth() / scale);
        canvas.drawColor(BASE);
        int save = canvas.save();
        canvas.translate((getWidth() - w * scale) / 2, 0);
        canvas.scale(scale, scale);

        ThorStyle.fill(canvas, paint, 16, 16, w - 16, 1064, FRAME_FILL);
        ThorStyle.outline(canvas, paint, 16, 16, w - 16, 1064, 2, LINE_STRONG);
        ThorStyle.fill(canvas, paint, 32, 32, w - 32, 88, RAISED);
        ThorStyle.text(canvas, paint, "FAZBEAR SHIFT MANAGER", 52, 70, 28, PRIMARY);
        ThorStyle.textRight(canvas, paint, "Fazbear's Fright", w - 52, 70, 24, SECONDARY);

        float y = 196;
        ThorStyle.text(canvas, paint, heading, 52, y, 44, PRIMARY);
        y += 72;
        for (String line : lines) {
            for (String row : wrap(line, w - 104, 28)) {
                ThorStyle.text(canvas, paint, row, 52, y, 28, lineColor);
                y += 44;
            }
            y += 16;
        }
        if (progress >= 0) {
            y += 8;
            ThorStyle.fill(canvas, paint, 52, y, w - 52, y + 8, LINE_STRONG);
            ThorStyle.fill(canvas, paint, 52, y, 52 + (w - 104) * Math.min(100, progress) / 100f, y + 8, ACCENT);
            ThorStyle.textRight(canvas, paint, progress + "%", w - 52, y + 52, 24, MUTED);
        }

        ThorStyle.fill(canvas, paint, 52, 980, w - 52, 982, LINE);
        if (menu) {
            float x = 52;
            x = hint(canvas, x, "D-PAD", "Move");
            x = hint(canvas, x, "A", "Select");
            ThorStyle.textRight(canvas, paint, "Menu on the top screen", w - 52, 1030, 24, MUTED);
        } else {
            ThorStyle.text(canvas, paint, "The Security Desk opens here with the game", 52, 1030, 24, MUTED);
        }
        canvas.restoreToCount(save);
    }

    private float hint(Canvas canvas, float x, String key, String label) {
        float cap = ThorStyle.keycapWidth(paint, key, 36, 1);
        ThorStyle.keycap(canvas, paint, x, 1004, x + cap, 1040, key, LINE_STRONG, SECONDARY, 1);
        x += cap + 12;
        ThorStyle.text(canvas, paint, label, x, 1031, 24, SECONDARY);
        return x + ThorStyle.width(paint, label, 24) + 40;
    }

    private List<String> wrap(String text, float width, float size) {
        List<String> rows = new ArrayList<>();
        StringBuilder row = new StringBuilder();
        for (String word : text.split(" ")) {
            String next = row.length() == 0 ? word : row + " " + word;
            if (row.length() > 0 && ThorStyle.width(paint, next, size) > width) {
                rows.add(row.toString());
                row.setLength(0);
                row.append(word);
            } else {
                row.setLength(0);
                row.append(next);
            }
        }
        if (row.length() > 0) rows.add(row.toString());
        return rows;
    }

    /** The Thor's lower display, or null on single-screen devices and in single-screen mode. */
    static Display lowerDisplay(Activity activity) {
        DisplayManager manager = (DisplayManager) activity.getSystemService(Context.DISPLAY_SERVICE);
        if (manager == null || activity.getWindowManager() == null) return null;
        int active = activity.getWindowManager().getDefaultDisplay().getDisplayId();
        for (Display candidate : manager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)) {
            if (candidate.getDisplayId() != active && candidate.isValid()
                    && candidate.getState() == Display.STATE_ON
                    && "Screen-2".equals(candidate.getName())) return candidate;
        }
        return null;
    }

    /** Presentation holding the panel; keys and stick motion go to the owning activity. */
    static final class Screen extends Presentation {
        private final Activity owner;
        final ThorBootPanel panel;

        Screen(Activity owner, Display display) {
            super(owner, display);
            this.owner = owner;
            panel = new ThorBootPanel(getContext());
        }

        @Override
        protected void onCreate(Bundle bundle) {
            super.onCreate(bundle);
            Window window = getWindow();
            window.setBackgroundDrawableResource(android.R.color.black);
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            setCancelable(false);
            setContentView(panel);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            return owner.dispatchKeyEvent(event);
        }

        @Override
        public boolean dispatchGenericMotionEvent(MotionEvent event) {
            return owner.dispatchGenericMotionEvent(event);
        }
    }
}
