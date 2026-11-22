package com.winlator;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Terminal sheet used by every launcher and in-game supporting screen. It mirrors the
 * AlertDialog.Builder calls the 1.0 flows already make, so their callbacks are unchanged.
 */
final class ThorSheet {
    enum Tone { NORMAL, OK, WARN, ERROR, DISABLED }

    static final class Row {
        final String label;
        String value;
        String sub;
        String mark;
        Tone tone = Tone.NORMAL;
        boolean clickable = true;
        boolean keepOpen;

        Row(String label) { this.label = label; }
        Row keepOpen() { keepOpen = true; return this; }
        Row value(String value) { this.value = value; return this; }
        Row sub(String sub) { this.sub = sub; return this; }
        Row mark(String mark) { this.mark = mark; return this; }
        Row tone(Tone tone) { this.tone = tone; return this; }
        Row inert() { clickable = false; return this; }
    }

    private final Context context;
    private final float u;
    private String title = "";
    private String meta;
    private CharSequence message;
    private Tone messageTone = Tone.NORMAL;
    private final List<Row> rows = new ArrayList<>();
    private DialogInterface.OnClickListener rowListener;
    private boolean[] checked;
    private DialogInterface.OnMultiChoiceClickListener multiListener;
    private int singleChoice = -2;
    private View custom;
    private final String[] buttonText = new String[3];
    private final DialogInterface.OnClickListener[] buttonListener = new DialogInterface.OnClickListener[3];
    private DialogInterface.OnCancelListener cancelListener;
    private DialogInterface.OnDismissListener dismissListener;
    private DialogInterface.OnKeyListener keyListener;
    private int initialFocus = 0;
    private boolean safeDefault;

    private Dialog dialog;
    private View confirmDown;
    private ScrollView scroll;
    /** Own selection over rows then footer buttons, so D-pad works the same in touch mode. */
    private final List<View> targets = new ArrayList<>();
    private int cursor;
    private final List<RowView> rowViews = new ArrayList<>();
    private TextView messageView;

    ThorSheet(Context context) {
        this.context = context;
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int width = Math.max(metrics.widthPixels, metrics.heightPixels);
        int height = Math.min(metrics.widthPixels, metrics.heightPixels);
        u = Math.max(.55f, Math.min(width / 1920f, height / 1080f));
    }

    ThorSheet setTitle(CharSequence value) { title = value == null ? "" : value.toString(); return this; }
    ThorSheet setMeta(String value) { meta = value; return this; }
    ThorSheet setMessage(CharSequence value) { message = value; return this; }
    ThorSheet setMessage(CharSequence value, Tone tone) { message = value; messageTone = tone; return this; }
    ThorSheet setView(View view) { custom = view; return this; }
    ThorSheet setInitialFocus(int index) { initialFocus = index; return this; }
    /** Destructive confirmations start on the negative button so a stray A cannot confirm them. */
    ThorSheet safeDefault() { safeDefault = true; return this; }
    ThorSheet setOnCancelListener(DialogInterface.OnCancelListener listener) { cancelListener = listener; return this; }
    ThorSheet setOnDismissListener(DialogInterface.OnDismissListener listener) { dismissListener = listener; return this; }
    ThorSheet setOnKeyListener(DialogInterface.OnKeyListener listener) { keyListener = listener; return this; }

    ThorSheet setPositiveButton(CharSequence text, DialogInterface.OnClickListener listener) {
        return button(0, text, listener);
    }

    ThorSheet setNegativeButton(CharSequence text, DialogInterface.OnClickListener listener) {
        return button(1, text, listener);
    }

    ThorSheet setNeutralButton(CharSequence text, DialogInterface.OnClickListener listener) {
        return button(2, text, listener);
    }

    private ThorSheet button(int slot, CharSequence text, DialogInterface.OnClickListener listener) {
        buttonText[slot] = text == null ? null : text.toString();
        buttonListener[slot] = listener;
        return this;
    }

    /** Plain labels; "Name   ·   Value" splits into a left label and a right-aligned value. */
    ThorSheet setItems(CharSequence[] labels, DialogInterface.OnClickListener listener) {
        rows.clear();
        for (CharSequence label : labels) rows.add(split(label.toString()));
        rowListener = listener;
        return this;
    }

    ThorSheet setRows(List<Row> items, DialogInterface.OnClickListener listener) {
        rows.clear();
        rows.addAll(items);
        rowListener = listener;
        return this;
    }

    ThorSheet setMultiChoiceItems(CharSequence[] labels, boolean[] selected,
                                  DialogInterface.OnMultiChoiceClickListener listener) {
        setItems(labels, null);
        checked = selected;
        multiListener = listener;
        return this;
    }

    ThorSheet setSingleChoiceItems(CharSequence[] labels, int selected, DialogInterface.OnClickListener listener) {
        setItems(labels, listener);
        singleChoice = selected;
        initialFocus = Math.max(0, selected);
        return this;
    }

    private static Row split(String label) {
        int dot = label.indexOf('·');
        if (dot <= 0) return new Row(label.trim());
        return new Row(label.substring(0, dot).trim()).value(label.substring(dot + 1).trim());
    }

    void setRowValue(int index, String value, Tone tone) {
        if (index < 0 || index >= rows.size()) return;
        rows.get(index).value = value;
        if (tone != null) rows.get(index).tone = tone;
        if (index < rowViews.size()) rowViews.get(index).invalidate();
    }

    void setMessageText(CharSequence value, Tone tone) {
        message = value;
        messageTone = tone;
        if (messageView != null) {
            messageView.setText(value);
            messageView.setTextColor(toneColor(tone, ThorStyle.TEXT));
            messageView.setVisibility(TextUtils.isEmpty(value) ? View.GONE : View.VISIBLE);
        }
    }

    Dialog create() {
        dialog = new Dialog(context, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
        dialog.setCanceledOnTouchOutside(true);
        dialog.setOnCancelListener(cancelListener);
        dialog.setOnDismissListener(dismissListener);
        dialog.setOnKeyListener((window, key, event) -> {
            if (keyListener != null && keyListener.onKey(window, key, event)) return true;
            if (navigate(key, event)) return true;
            if (event.getAction() != KeyEvent.ACTION_UP) return key == KeyEvent.KEYCODE_BACK
                    || key == KeyEvent.KEYCODE_BUTTON_B || key == KeyEvent.KEYCODE_ESCAPE;
            if (key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_BUTTON_B || key == KeyEvent.KEYCODE_ESCAPE) {
                back();
                return true;
            }
            return false;
        });

        FrameLayout scrim = new FrameLayout(context);
        scrim.setBackgroundColor(0xb8000000);
        scrim.setOnClickListener(view -> dialog.cancel());
        // Clickable layouts are auto-focusable; keep Android focus off them so the first D-pad press
        // moves the sheet cursor instead of being spent leaving touch mode.
        scrim.setFocusable(false);

        Panel panel = new Panel(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setClickable(true);
        panel.setFocusable(false);
        int pad = px(34);
        panel.setPadding(pad, px(26), pad, px(26));

        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(ThorStyle.PANEL_HI);
        header.setPadding(px(22), 0, px(22), 0);
        header.addView(label(title.toUpperCase(java.util.Locale.ROOT), 28, ThorStyle.TEXT),
                new LinearLayout.LayoutParams(0, px(60), 1f));
        if (meta != null) header.addView(label(meta, 25, ThorStyle.AMBER_HI), new LinearLayout.LayoutParams(-2, px(60)));
        panel.addView(header, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, px(18), 0, px(10));
        messageView = label(message == null ? "" : message, 27, toneColor(messageTone, ThorStyle.TEXT));
        messageView.setSingleLine(false);
        messageView.setLineSpacing(px(8), 1f);
        messageView.setPadding(px(22), px(10), px(22), px(14));
        messageView.setVisibility(TextUtils.isEmpty(message) ? View.GONE : View.VISIBLE);
        body.addView(messageView, new LinearLayout.LayoutParams(-1, -2));
        rowViews.clear();
        for (int i = 0; i < rows.size(); ++i) {
            RowView row = new RowView(context, i);
            rowViews.add(row);
            body.addView(row, new LinearLayout.LayoutParams(-1, rowHeight(rows.get(i))));
        }
        if (custom != null) {
            if (custom.getParent() instanceof ViewGroup) ((ViewGroup) custom.getParent()).removeView(custom);
            if (custom instanceof TextView) {
                ((TextView) custom).setTextColor(ThorStyle.TEXT);
                ((TextView) custom).setTypeface(ThorStyle.MONO);
            }
            body.addView(custom, new LinearLayout.LayoutParams(-1, -2));
        }
        scroll = new ScrollView(context);
        scroll.setFocusable(false);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.addView(body, new FrameLayout.LayoutParams(-1, -2));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout footer = new LinearLayout(context);
        footer.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        footer.setPadding(0, px(16), 0, 0);
        TextView hint = label(hintText(), 21, ThorStyle.MUTED);
        footer.addView(hint, new LinearLayout.LayoutParams(0, -2, 1f));
        View primary = null;
        targets.clear();
        targets.addAll(rowViews);
        for (int slot : new int[]{2, 1, 0}) {
            if (buttonText[slot] == null) continue;
            FooterButton button = new FooterButton(context, slot);
            targets.add(button);
            if (safeDefault ? slot == 1 || primary == null : slot == 0 || primary == null) primary = button;
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, px(62));
            params.leftMargin = px(16);
            footer.addView(button, params);
        }
        panel.addView(new Divider(context), new LinearLayout.LayoutParams(-1, px(2)));
        panel.addView(footer, new LinearLayout.LayoutParams(-1, -2));

        int maxWidth = Math.min(px(1240), context.getResources().getDisplayMetrics().widthPixels - px(60));
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(maxWidth, -2, Gravity.CENTER);
        panelParams.topMargin = panelParams.bottomMargin = px(36);
        scrim.addView(panel, panelParams);
        panel.maxHeight = context.getResources().getDisplayMetrics().heightPixels - px(72);

        dialog.setContentView(scrim);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        cursor = !rowViews.isEmpty() ? Math.max(0, Math.min(initialFocus, rowViews.size() - 1))
                : primary != null ? targets.indexOf(primary) : -1;
        dialog.setOnShowListener(shown -> {
            if (custom != null && custom.isFocusable()) custom.requestFocus();
            reveal();
        });
        return dialog;
    }

    Dialog show() {
        Dialog result = create();
        result.show();
        return result;
    }

    private boolean isCurrent(View view) {
        return cursor >= 0 && cursor < targets.size() && targets.get(cursor) == view;
    }

    private void moveTo(int index) {
        if (targets.isEmpty()) return;
        cursor = Math.max(0, Math.min(index, targets.size() - 1));
        for (View target : targets) target.invalidate();
        reveal();
        targets.get(cursor).sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED);
    }

    private void reveal() {
        if (cursor < 0 || cursor >= rowViews.size() || scroll == null) return;
        View row = rowViews.get(cursor);
        row.post(() -> row.requestRectangleOnScreen(new android.graphics.Rect(0, 0, row.getWidth(), row.getHeight()), false));
    }

    /** D-pad moves the >> cursor, A or the D-pad center confirms. Returns true when consumed. */
    private boolean navigate(int key, KeyEvent event) {
        boolean confirm = key == KeyEvent.KEYCODE_BUTTON_A || key == KeyEvent.KEYCODE_DPAD_CENTER
                || key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_NUMPAD_ENTER;
        boolean vertical = key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN;
        boolean horizontal = key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT;
        if (!confirm && !vertical && !horizontal) return false;
        if (targets.isEmpty()) return confirm || vertical;
        int rowsCount = rowViews.size();
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (vertical && rowsCount == 0 && scroll != null) {
                // Text pages (licences, guides, messages): the D-pad scrolls the text.
                scroll.smoothScrollBy(0, (key == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1) * px(160));
                return true;
            }
            if (vertical) {
                boolean down = key == KeyEvent.KEYCODE_DPAD_DOWN;
                if (cursor < 0) moveTo(0);
                else if (cursor < rowsCount) {
                    if (down) moveTo(cursor + 1 < rowsCount ? cursor + 1 : primaryIndex());
                    else if (cursor > 0) moveTo(cursor - 1);
                } else if (!down && rowsCount > 0) {
                    moveTo(rowsCount - 1);
                }
                return true;
            }
            if (horizontal) {
                if (cursor < rowsCount) return false;
                int next = cursor + (key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                if (next >= rowsCount && next < targets.size()) moveTo(next);
                return true;
            }
            // A release that began on the previous screen must not confirm this one.
            View target = cursor >= 0 && cursor < targets.size() ? targets.get(cursor) : null;
            confirmDown = event.getRepeatCount() == 0 && target != null && target.isClickable() ? target : null;
            if (confirmDown != null) confirmDown.setPressed(true);
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_UP && confirm) {
            View pressed = confirmDown;
            confirmDown = null;
            if (pressed != null) {
                pressed.setPressed(false);
                if (isCurrent(pressed)) pressed.performClick();
            }
        }
        return true;
    }

    private int primaryIndex() {
        return targets.size() > rowViews.size() ? targets.size() - 1 : Math.max(0, rowViews.size() - 1);
    }

    private String hintText() {
        boolean selectable = rowListener != null || multiListener != null;
        String back = buttonText[1] != null ? buttonText[1] : "Close";
        return (selectable ? "D-PAD  MOVE     A  SELECT     " : "A  CONFIRM     ") + "B  "
                + back.toUpperCase(java.util.Locale.ROOT);
    }

    private void back() {
        if (buttonText[1] != null) {
            DialogInterface.OnClickListener listener = buttonListener[1];
            if (listener != null) listener.onClick(dialog, DialogInterface.BUTTON_NEGATIVE);
            dialog.dismiss();
        } else {
            dialog.cancel();
        }
    }

    private void press(int slot) {
        DialogInterface.OnClickListener listener = buttonListener[slot];
        int which = slot == 0 ? DialogInterface.BUTTON_POSITIVE : slot == 1
                ? DialogInterface.BUTTON_NEGATIVE : DialogInterface.BUTTON_NEUTRAL;
        if (listener != null) listener.onClick(dialog, which);
        dialog.dismiss();
    }

    private void choose(int index) {
        Row row = rows.get(index);
        if (checked != null) {
            checked[index] = !checked[index];
            rowViews.get(index).invalidate();
            if (multiListener != null) multiListener.onClick(dialog, index, checked[index]);
            return;
        }
        if (singleChoice != -2) {
            singleChoice = index;
            for (RowView view : rowViews) view.invalidate();
            if (rowListener != null) rowListener.onClick(dialog, index);
            return;
        }
        if (rowListener == null || !row.clickable) return;
        if (row.keepOpen) {
            rowListener.onClick(dialog, index);
            return;
        }
        rowListener.onClick(dialog, index);
        dialog.dismiss();
    }

    private int rowHeight(Row row) {
        return px(row.sub != null ? 100 : 70);
    }

    private TextView label(CharSequence value, float size, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTypeface(ThorStyle.MONO);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * u);
        view.setTextColor(color);
        view.setSingleLine(true);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setIncludeFontPadding(false);
        return view;
    }

    private int px(float value) {
        return Math.round(value * u);
    }

    static int toneColor(Tone tone, int fallback) {
        switch (tone) {
            case OK: return ThorStyle.PHOSPHOR;
            case WARN: return ThorStyle.AMBER_HI;
            case ERROR: return ThorStyle.RUST;
            case DISABLED: return ThorStyle.DISABLED;
            default: return fallback;
        }
    }

    private final class Panel extends LinearLayout {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        int maxHeight = Integer.MAX_VALUE;

        Panel(Context context) {
            super(context);
            setWillNotDraw(false);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            box.set(0, 0, getWidth(), getHeight());
            ThorStyle.frame(canvas, paint, box, u);
        }
    }

    private final class Divider extends View {
        Divider(Context context) {
            super(context);
            setBackgroundColor(ThorStyle.OLIVE_DIM);
        }
    }

    private final class RowView extends View {
        private final Paint paint = ThorStyle.textPaint();
        private final int index;

        RowView(Context context, int index) {
            super(context);
            this.index = index;
            Row row = rows.get(index);
            boolean interactive = row.clickable && (rowListener != null || checked != null || singleChoice != -2);
            setFocusable(false);
            setClickable(interactive);
            setContentDescription(row.label + (row.value != null ? ", " + row.value : "")
                    + (row.sub != null ? ", " + row.sub : ""));
            setOnClickListener(view -> choose(index));
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent event) {
            if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) moveTo(targets.indexOf(this));
            return super.onTouchEvent(event);
        }

        @Override
        protected void drawableStateChanged() {
            super.drawableStateChanged();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            Row row = rows.get(index);
            float h = getHeight(), w = getWidth();
            boolean focus = isCurrent(this);
            boolean down = isPressed();
            if (focus || down) {
                ThorStyle.fill(canvas, paint, 0, 4 * u, w, h - 4 * u, ThorStyle.FIELD_FOCUS);
                ThorStyle.outline(canvas, paint, 0, 4 * u, w, h - 4 * u, 2 * u,
                        focus ? ThorStyle.AMBER_HI : ThorStyle.OLIVE_LIGHT);
            }
            float baseline = row.sub != null ? h * .44f : h * .62f;
            if (focus) ThorStyle.text(canvas, paint, ">>", 16 * u, baseline, 28 * u, ThorStyle.AMBER_HI);
            float x = 70 * u;
            String mark = row.mark;
            if (checked != null) mark = checked[index] ? "[X]" : "[ ]";
            else if (singleChoice != -2) mark = singleChoice == index ? "(•)" : "( )";
            if (mark != null) {
                boolean on = mark.contains("X") || mark.contains("•");
                ThorStyle.text(canvas, paint, mark, x, baseline, 28 * u, on ? ThorStyle.PHOSPHOR : ThorStyle.MUTED);
                x += 84 * u;
            }
            int labelColor = row.tone == Tone.DISABLED ? ThorStyle.DISABLED : focus ? ThorStyle.TEXT_HI : ThorStyle.TEXT;
            float right = w - 22 * u;
            if (row.value != null) {
                int valueColor = toneColor(row.tone, focus ? ThorStyle.AMBER_HI : ThorStyle.MUTED);
                paint.setTextSize(26 * u);
                ThorStyle.textRight(canvas, paint, row.value.toUpperCase(java.util.Locale.ROOT), right, baseline,
                        26 * u, valueColor);
                right -= paint.measureText(row.value) + 30 * u;
            }
            paint.setTextSize(30 * u);
            String label = TextUtils.ellipsize(row.label, new android.text.TextPaint(paint), right - x,
                    TextUtils.TruncateAt.END).toString();
            ThorStyle.text(canvas, paint, label, x, baseline, 30 * u, labelColor);
            if (row.sub != null) {
                paint.setTextSize(22 * u);
                String sub = TextUtils.ellipsize(row.sub, new android.text.TextPaint(paint), w - x - 22 * u,
                        TextUtils.TruncateAt.END).toString();
                ThorStyle.text(canvas, paint, sub, x, h * .78f, 22 * u,
                        row.tone == Tone.DISABLED ? ThorStyle.DISABLED : ThorStyle.MUTED);
            }
        }
    }

    private final class FooterButton extends View {
        private final Paint paint = ThorStyle.textPaint();
        private final String text;

        FooterButton(Context context, int slot) {
            super(context);
            text = buttonText[slot].toUpperCase(java.util.Locale.ROOT);
            setFocusable(false);
            setClickable(true);
            setContentDescription(buttonText[slot]);
            setOnClickListener(view -> press(slot));
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent event) {
            if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) moveTo(targets.indexOf(this));
            return super.onTouchEvent(event);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            paint.setTextSize(24 * u);
            int width = Math.round(paint.measureText(text) + 56 * u);
            setMeasuredDimension(Math.max(width, Math.round(150 * u)), MeasureSpec.getSize(heightSpec));
        }

        @Override
        protected void drawableStateChanged() {
            super.drawableStateChanged();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            boolean focus = isCurrent(this);
            boolean down = isPressed();
            ThorStyle.fill(canvas, paint, 0, 0, getWidth(), getHeight(), down || focus ? ThorStyle.FIELD_FOCUS : ThorStyle.PANEL);
            ThorStyle.outline(canvas, paint, 0, 0, getWidth(), getHeight(), (focus ? 3 : 2) * u,
                    focus ? ThorStyle.AMBER_HI : ThorStyle.OLIVE_LIGHT);
            paint.setTextSize(24 * u);
            float width = paint.measureText(text);
            Paint.FontMetrics metrics = paint.getFontMetrics();
            ThorStyle.text(canvas, paint, text, (getWidth() - width) / 2,
                    getHeight() / 2f - (metrics.ascent + metrics.descent) / 2, 24 * u,
                    focus ? ThorStyle.TEXT_HI : ThorStyle.TEXT);
        }
    }
}
