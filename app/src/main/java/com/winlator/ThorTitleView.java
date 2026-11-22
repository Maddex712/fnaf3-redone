package com.winlator;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * The Redone title, laid out like FNaF 1 and FNaF 2 Redone in a 1920 x 1080 space: stacked
 * monospace title, left-aligned menu with a white >> marker and a terminal texture. Inside the game
 * it is drawn over the original title's live art; the left and bottom are shaded so only the art on
 * the right shows. A page mode mirrors FNaF 2's "use the lower terminal" heading.
 */
final class ThorTitleView extends View {
    interface Listener {
        void onSelect(Entry entry);
        default void onHighlight(Entry entry) {}
    }

    static final class Entry {
        final String id;
        final String label;
        boolean enabled = true;
        String note;

        Entry(String id, String label) {
            this.id = id;
            this.label = label;
        }

        Entry disabled(String why) {
            enabled = false;
            note = why;
            return this;
        }
    }

    private final Paint paint = ThorStyle.textPaint();
    private final RectF screen = new RectF();
    private final List<Entry> entries = new ArrayList<>();
    private Listener listener;
    private int selected;
    private float u = 1, ox, oy;
    private boolean overArt;
    private String status;
    private int statusColor = ThorStyle.MUTED;
    private String[] info;
    private int infoColor = ThorStyle.TEXT;
    private int progress = -1;
    private String pageHeading;
    private String[] pageLines;
    private int touchRow = -1;
    private boolean ticking;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!ticking) return;
            invalidate();
            postDelayed(this, 66);
        }
    };

    ThorTitleView(Context context) {
        super(context);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setDefaultFocusHighlightEnabled(false);
        setClickable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    void setListener(Listener value) { listener = value; }

    /** True while drawn over the running game's title, which then shows through on the right. */
    void setOverArt(boolean value) {
        if (overArt == value) return;
        overArt = value;
        invalidate();
    }

    void setEntries(List<Entry> values, String keep) {
        String current = keep != null ? keep : selected < entries.size() ? entries.get(selected).id : null;
        entries.clear();
        entries.addAll(values);
        selected = 0;
        for (int i = 0; i < entries.size(); ++i) if (entries.get(i).id.equals(current)) selected = i;
        if (selected < entries.size()) setContentDescription(entries.get(selected).label);
        invalidate();
    }

    void select(String id) {
        for (int i = 0; i < entries.size(); ++i) if (entries.get(i).id.equals(id)) selected = i;
        invalidate();
    }

    Entry selectedEntry() { return selected < entries.size() ? entries.get(selected) : null; }

    void setStatus(String value, int color) {
        status = value;
        statusColor = color;
        invalidate();
    }

    /** Right-hand text block used by setup (no game art behind the menu). */
    void setInfo(String[] lines, int color) {
        info = lines;
        infoColor = color;
        invalidate();
    }

    void setProgress(int value) {
        progress = value;
        invalidate();
    }

    /** FNaF 2-style page: the menu steps aside while the lower terminal shows the page. */
    void setPage(String heading, String... lines) {
        pageHeading = heading;
        pageLines = lines;
        invalidate();
    }

    boolean inPage() { return pageHeading != null; }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateTicking();
    }

    @Override
    protected void onDetachedFromWindow() {
        ticking = false;
        removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        updateTicking();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        updateTicking();
    }

    private void updateTicking() {
        boolean run = isAttachedToWindow() && getWindowVisibility() == VISIBLE && isShown();
        if (run == ticking) return;
        ticking = run;
        removeCallbacks(tick);
        if (run) post(tick);
    }

    /** D-pad up/down moves, A or Enter selects. Returns true when the key was used. */
    boolean key(int keyCode, boolean down) {
        boolean up = keyCode == KeyEvent.KEYCODE_DPAD_UP;
        boolean dn = keyCode == KeyEvent.KEYCODE_DPAD_DOWN;
        boolean confirm = keyCode == KeyEvent.KEYCODE_BUTTON_A || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (inPage() || entries.isEmpty() || !up && !dn && !confirm) return false;
        if (!down) return true;
        if (confirm) {
            activate(selected);
        } else {
            selected = Math.floorMod(selected + (dn ? 1 : -1), entries.size());
            highlight();
        }
        return true;
    }

    private void highlight() {
        Entry entry = entries.get(selected);
        setContentDescription(entry.label);
        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED);
        invalidate();
        if (listener != null) listener.onHighlight(entry);
    }

    private void activate(int index) {
        if (index < 0 || index >= entries.size()) return;
        selected = index;
        invalidate();
        if (listener != null) listener.onSelect(entries.get(index));
    }

    private int step() { return entries.size() > 7 ? 62 : 71; }
    private float x(float v) { return ox + v * u; }
    private float y(float v) { return oy + v * u; }

    private int rowAt(float px, float py) {
        if (inPage()) return -1;
        for (int i = 0; i < entries.size(); ++i) {
            float base = 532 + i * step();
            if (py >= y(base - 50) && py < y(base - 50 + step()) && px >= x(40) && px < x(860)) return i;
        }
        return -1;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchRow = rowAt(event.getX(), event.getY());
                if (touchRow >= 0 && touchRow != selected) {
                    selected = touchRow;
                    highlight();
                }
                return true;
            case MotionEvent.ACTION_UP:
                int row = rowAt(event.getX(), event.getY());
                if (row >= 0 && row == touchRow) {
                    performClick();
                    activate(row);
                }
                touchRow = -1;
                return true;
            case MotionEvent.ACTION_CANCEL:
                touchRow = -1;
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        int w = getWidth(), h = getHeight();
        u = Math.min(w / 1920f, h / 1080f);
        ox = (w - 1920 * u) / 2;
        oy = (h - 1080 * u) / 2;
        screen.set(0, 0, w, h);
        if (overArt) ThorStyle.titleShade(canvas, paint, screen, u);
        else canvas.drawColor(ThorStyle.BG);
        if (inPage()) ThorStyle.fill(canvas, paint, 0, 0, w, h, 0x8a000000);
        ThorStyle.interference(canvas, paint, screen, now, u);

        if (inPage()) {
            ThorStyle.text(canvas, paint, pageHeading, x(80), y(110), 52 * u, ThorStyle.TEXT_HI);
            float line = y(470);
            for (int i = 0; pageLines != null && i < pageLines.length; ++i) {
                float size = i == 0 ? 44 : 36;
                ThorStyle.text(canvas, paint, pageLines[i], x(80), line, size * u, i == 0 ? ThorStyle.TEXT : ThorStyle.MUTED);
                line += (i == 0 ? 96 : 72) * u;
            }
            return;
        }

        String[] title = {"Five", "Nights", "at", "Freddy's", "3"};
        for (int i = 0; i < title.length; ++i)
            ThorStyle.text(canvas, paint, title[i], x(80), y(120 + i * 87), 74 * u, ThorStyle.TEXT_HI);

        for (int i = 0; i < entries.size(); ++i) {
            Entry entry = entries.get(i);
            float base = y(532 + i * step());
            if (i == selected) ThorStyle.text(canvas, paint, ">>", x(80), base, 44 * u, ThorStyle.TEXT_HI);
            ThorStyle.text(canvas, paint, entry.label, x(170), base, 44 * u,
                    entry.enabled ? ThorStyle.TEXT_HI : ThorStyle.DISABLED);
            if (entry.note != null && i == selected) {
                float right = x(170) + ThorStyle.width(paint, entry.label, 44 * u) + 28 * u;
                ThorStyle.text(canvas, paint, entry.note, right, base - 4 * u, 26 * u,
                        entry.enabled ? ThorStyle.MUTED : ThorStyle.DISABLED);
            }
        }

        if (info != null) {
            TextPaint body = new TextPaint(paint);
            body.setTypeface(ThorStyle.MONO);
            body.setTextSize(34 * u);
            float top = y(540);
            for (int i = 0; i < info.length; ++i) {
                body.setColor(i == 0 ? ThorStyle.TEXT_HI : infoColor);
                body.setTextSize((i == 0 ? 40 : 30) * u);
                StaticLayout layout = StaticLayout.Builder.obtain(info[i], 0, info[i].length(), body,
                        Math.round(820 * u)).setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .setLineSpacing(8 * u, 1f).build();
                int save = canvas.save();
                canvas.translate(x(1000), top);
                layout.draw(canvas);
                canvas.restoreToCount(save);
                top += layout.getHeight() + (i == 0 ? 34 : 18) * u;
            }
            if (progress >= 0) {
                float l = x(1000), r = x(1820), t = top + 20 * u;
                ThorStyle.outline(canvas, paint, l, t, r, t + 34 * u, 2 * u, ThorStyle.OUTLINE_ON);
                float inner = r - l - 10 * u;
                if (progress > 0) {
                    ThorStyle.fill(canvas, paint, l + 5 * u, t + 5 * u, l + 5 * u + inner * progress / 100f, t + 29 * u,
                            ThorStyle.TEXT);
                    ThorStyle.textRight(canvas, paint, progress + "%", r, t + 80 * u, 30 * u, ThorStyle.TEXT);
                } else {
                    float span = inner * .22f, start = l + 5 * u + (now % 1600L) / 1600f * (inner - span);
                    ThorStyle.fill(canvas, paint, start, t + 5 * u, start + span, t + 29 * u, ThorStyle.TEXT);
                }
            }
        }

        if (status != null) ThorStyle.text(canvas, paint, status, x(80), y(1046), 26 * u, statusColor);
    }
}
