package com.winlator;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import androidx.preference.PreferenceManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lower-screen Fazbear Shift Manager in the 1240 x 1080 panel space on a 4 px grid. The chrome is the
 * series' quiet gray terminal; the one rich moment is the shift card, a real frame from the player's
 * Camera Archive behind the original game's night card. The maintenance green marks the current night.
 */
final class ThorManagerView extends View {
    enum Tab { SHIFTS, RECORDS, ASSIGNMENTS, DESK, OPTIONS }

    interface Host {
        void startNight();
        void startArcade(ThorArcade stage);
        void returnToCampaign();
        void openArchive();
        void openControllerMappings();
        void restartToApply();
        void openSetup();
        void openLicenses();
        void managerBack();
    }

    // Graphite ramp, three text roles, one accent (the original maintenance terminal green).
    private static final int BASE = 0xff0e0e0e;
    private static final int FRAME_FILL = 0xff161616;
    private static final int RAISED = 0xff1e1e1e;
    private static final int RAISED_ON = 0xff2a2a28;
    private static final int LINE = 0x1affffff;
    private static final int LINE_STRONG = 0x33ffffff;
    private static final int PRIMARY = 0xffecebe6;
    private static final int SECONDARY = 0xffa9a9a3;
    private static final int MUTED = 0xff6f6f6b;
    private static final int ACCENT = 0xff8fe05a;
    private static final int ERROR = 0xffd9644f;

    private static final String[] TAB_LABELS = {"SHIFTS", "RECORDS", "ARCADE", "DESK"};
    private static final ThorProgress.Decoration[] ITEMS = {
            ThorProgress.Decoration.MOTH, ThorProgress.Decoration.LANTERN, ThorProgress.Decoration.RABBIT};

    private final Paint paint = ThorStyle.textPaint();
    private final Paint art = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Host host;
    private final ThorArcade arcadeSession;
    private final int[] launchLayout;
    private final List<Target> targets = new ArrayList<>();
    private ThorProgress progress;
    private Tab tab = Tab.SHIFTS;
    private boolean focused;
    private String focusKey = "start";
    private int shopIndex;
    private int arcadeIndex;
    private boolean guideOpen;
    private String notice;
    private float scale = 1, width = 1240;
    private Bitmap archiveFrame;
    private int[] archiveCounts = {0, 0};

    private static final class Target {
        final String key;
        final RectF box;
        final Runnable action;
        Target(String key, float l, float t, float r, float b, Runnable action) {
            this.key = key;
            box = new RectF(l, t, r, b);
            this.action = action;
        }
    }

    ThorManagerView(Context context, Host host, ThorArcade arcadeSession, int[] launchLayout) {
        super(context);
        this.host = host;
        this.arcadeSession = arcadeSession;
        this.launchLayout = launchLayout;
        progress = new ThorProgress(context);
        setClickable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        if (arcadeSession != null) arcadeIndex = arcadeSession.ordinal();
        loadArchiveFrame();
        ThorAssets.prepare(context, this::invalidate);
    }

    /** Settings that apply on the next launch: display mode, swapped, 4:3, smooth, perspective, renderer. */
    static int[] layoutSettings(Context context) {
        SharedPreferences s = PreferenceManager.getDefaultSharedPreferences(context);
        return new int[]{s.getInt("thor_display_mode", 0), s.getBoolean("thor_swap_screens", false) ? 1 : 0,
                s.getBoolean("thor_fit_office", false) ? 1 : 0, s.getBoolean("thor_smooth_picture", true) ? 1 : 0,
                s.getBoolean("thor_office_perspective", false) ? 1 : 0, s.getBoolean("thor_dxvk", true) ? 1 : 0};
    }

    /** The newest capture the player's own Camera Archive holds, decoded small. */
    private void loadArchiveFrame() {
        archiveCounts = ThorGallery.counts(getContext());
        File[] files = new File(getContext().getFilesDir(), "camera-archive")
                .listFiles((dir, name) -> name.matches("(camera-[0-9]{2}|snapshot-[0-9]+)\\.jpg"));
        File newest = null;
        if (files != null) for (File file : files) if (newest == null || file.lastModified() > newest.lastModified()) newest = file;
        if (newest == null) {
            archiveFrame = null;
            return;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        archiveFrame = BitmapFactory.decodeFile(newest.getPath(), options);
    }

    void refresh() {
        progress = new ThorProgress(getContext());
        loadArchiveFrame();
        invalidate();
    }

    Tab tab() { return tab; }

    void show(Tab value) {
        tab = value;
        guideOpen = false;
        notice = null;
        focusKey = defaultFocus();
        invalidate();
    }

    void setFocused(boolean value) {
        focused = value;
        if (value && findTarget(focusKey) == null) focusKey = defaultFocus();
        invalidate();
    }

    private String defaultFocus() {
        switch (tab) {
            case RECORDS: return "badge0";
            case ASSIGNMENTS: return "arcade" + arcadeIndex;
            case DESK: return "deskAction";
            case OPTIONS: return "opt0";
            default: return "start";
        }
    }

    // ------------------------------------------------------------------ input

    boolean key(int keyCode, boolean down) {
        if (!down) return true;
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_L1: cycleTab(-1); return true;
            case KeyEvent.KEYCODE_BUTTON_R1: cycleTab(1); return true;
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_ESCAPE:
                if (guideOpen) { guideOpen = false; invalidate(); }
                else host.managerBack();
                return true;
            case KeyEvent.KEYCODE_BUTTON_Y:
                if (tab == Tab.ASSIGNMENTS || arcadeSession != null) {
                    guideOpen = !guideOpen;
                    invalidate();
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER: {
                Target target = findTarget(focusKey);
                if (target != null) target.action.run();
                invalidate();
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (adjust(keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1)) return true;
                move(keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP: move(0, -1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: move(0, 1); return true;
            default: return true;
        }
    }

    private void cycleTab(int direction) {
        if (arcadeSession != null) return;
        int next = Math.floorMod((tab == Tab.OPTIONS ? 0 : tab.ordinal()) + direction, 4);
        show(Tab.values()[next]);
    }

    private boolean adjust(int direction) {
        if (tab == Tab.DESK && "deskAction".equals(focusKey)) {
            shopIndex = Math.floorMod(shopIndex + direction, ITEMS.length);
            notice = null;
            invalidate();
            return true;
        }
        if (tab == Tab.OPTIONS && "opt5".equals(focusKey)) {
            SharedPreferences s = PreferenceManager.getDefaultSharedPreferences(getContext());
            int value = Math.max(25, Math.min(175, s.getInt("thor_controller_sensitivity", 100) + 5 * direction));
            s.edit().putInt("thor_controller_sensitivity", value).apply();
            invalidate();
            return true;
        }
        return false;
    }

    private void move(int dx, int dy) {
        Target current = findTarget(focusKey);
        if (current == null) {
            if (!targets.isEmpty()) focusKey = targets.get(0).key;
            invalidate();
            return;
        }
        Target best = null;
        float bestScore = Float.MAX_VALUE;
        for (Target candidate : targets) {
            if (candidate == current) continue;
            float cx = candidate.box.centerX() - current.box.centerX();
            float cy = candidate.box.centerY() - current.box.centerY();
            boolean ahead = dy != 0 ? Math.signum(cy) == dy && Math.abs(cy) > 4
                    : Math.signum(cx) == dx && Math.abs(cx) > 4 && Math.abs(cy) < current.box.height();
            if (!ahead) continue;
            float score = dy != 0 ? Math.abs(cy) + Math.abs(cx) * .6f : Math.abs(cx) + Math.abs(cy) * 2f;
            if (score < bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        if (best != null) focusKey = best.key;
        invalidate();
    }

    private Target findTarget(String key) {
        for (Target target : targets) if (target.key.equals(key)) return target;
        return null;
    }

    private Target downTarget;

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = (event.getX() - (getWidth() - width * scale) / 2) / scale, y = event.getY() / scale;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downTarget = null;
                for (Target target : targets) if (target.box.contains(x, y)) downTarget = target;
                if (downTarget != null) {
                    focusKey = downTarget.key;
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (downTarget != null && downTarget.box.contains(x, y)) {
                    performClick();
                    downTarget.action.run();
                    invalidate();
                }
                downTarget = null;
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas canvas) {
        scale = getHeight() / 1080f;
        width = Math.min(1240, getWidth() / scale);
        float w = width;
        targets.clear();
        canvas.drawColor(BASE);
        int save = canvas.save();
        canvas.translate((getWidth() - w * scale) / 2, 0);
        canvas.scale(scale, scale);

        fill(canvas, 16, 16, w - 16, 1064, FRAME_FILL);
        stroke(canvas, 16, 16, w - 16, 1064, LINE_STRONG);
        fill(canvas, 32, 32, w - 32, 88, RAISED);
        t(canvas, "FAZBEAR SHIFT MANAGER", 52, 70, 28, PRIMARY);
        int night = progress.savedNight();
        tr(canvas, arcadeSession != null ? "Practice" : "Fazbear's Fright", w - 52, 70, 24, SECONDARY);
        if (arcadeSession == null) tabs(canvas, w);

        if (arcadeSession != null) practice(canvas, w);
        else if (tab == Tab.RECORDS) records(canvas, w);
        else if (tab == Tab.ASSIGNMENTS) assignments(canvas, w);
        else if (tab == Tab.DESK) desk(canvas, w);
        else if (tab == Tab.OPTIONS) options(canvas, w);
        else shifts(canvas, w, night);

        if (focused) {
            Target target = findTarget(focusKey);
            if (target != null) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2);
                paint.setColor(PRIMARY);
                canvas.drawRect(target.box.left - 4, target.box.top - 4, target.box.right + 4, target.box.bottom + 4, paint);
                paint.setStyle(Paint.Style.FILL);
            }
        }
        canvas.restoreToCount(save);
    }

    private void tabs(Canvas c, float w) {
        float tw = (w - 64) / 4;
        for (int i = 0; i < 4; ++i) {
            boolean active = tab.ordinal() == i;
            float l = 32 + i * tw;
            if (active) fill(c, l, 104, l + tw, 152, RAISED_ON);
            t(c, TAB_LABELS[i], l + 20, 136, 22, active ? PRIMARY : SECONDARY);
            int index = i;
            targets.add(new Target("tab" + i, l, 104, l + tw, 152, () -> show(Tab.values()[index])));
        }
        fill(c, 32, 152, w - 32, 154, LINE_STRONG);
        if (focused) tr(c, "L1  R1", w - 48, 136, 18, MUTED);
    }

    private void shifts(Canvas c, float w, int night) {
        // The moment: the newest Camera Archive frame behind the original night card.
        RectF card = new RectF(32, 176, w - 32, 584);
        fill(c, card.left, card.top, card.right, card.bottom, 0xff080808);
        if (archiveFrame != null) {
            int save = c.save();
            c.clipRect(card);
            float s = Math.max(card.width() / archiveFrame.getWidth(), card.height() / archiveFrame.getHeight());
            float dw = archiveFrame.getWidth() * s, dh = archiveFrame.getHeight() * s;
            art.setAlpha(200);
            c.drawBitmap(archiveFrame, null, new RectF(card.right - dw, card.centerY() - dh / 2, card.right, card.centerY() + dh / 2), art);
            art.setAlpha(255);
            paint.setShader(new LinearGradient(card.left, 0, card.left + card.width() * .72f, 0,
                    new int[]{0xf2080808, 0xcc080808, 0x00080808}, new float[]{0f, .55f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(card, paint);
            paint.setShader(null);
            c.restoreToCount(save);
        }
        stroke(c, card.left, card.top, card.right, card.bottom, LINE);
        Bitmap nightCard = night >= 2 && night < ThorAssets.NIGHT_CARDS.length ? ThorAssets.get(ThorAssets.NIGHT_CARDS[night]) : null;
        if (nightCard != null) {
            art.setAlpha(255);
            c.drawBitmap(nightCard, null, new RectF(64, 208, 64 + nightCard.getWidth() * 2, 208 + nightCard.getHeight() * 2), art);
        } else {
            t(c, "12:00 AM", 64, 256, 40, PRIMARY);
            t(c, night > 0 ? "Night " + night : "First shift", 64, 312, 40, PRIMARY);
        }
        t(c, archiveFrame != null ? "Last seen on your cameras" : "Saved on this device", 64, 472, 20, SECONDARY);
        String start = night > 0 ? "Start night " + night : "Start a new game";
        float bw = ThorStyle.width(paint, start, 26) + 112;
        fill(c, 64, 496, 64 + bw, 552, PRIMARY);
        keycapDark(c, 80, 508, "A");
        t(c, start, 128, 534, 26, 0xff111111);
        targets.add(new Target("start", 64, 496, 64 + bw, 552, host::startNight));

        // Progress strip instead of night buttons that could not be chosen.
        t(c, "Progress", 32, 632, 20, SECONDARY);
        tr(c, night > 0 ? "Night " + Math.min(night, 5) + " of 5" + (night > 5 ? ", extras open" : "") : "Not started",
                w - 32, 632, 20, SECONDARY);
        float gap = 8, seg = (w - 64 - gap * 4) / 5;
        for (int i = 0; i < 5; ++i) {
            float l = 32 + i * (seg + gap);
            int n = i + 1;
            if (night > n) fill(c, l, 648, l + seg, 660, 0xff8a8a86);
            else if (night == n || night > 5 && n == 5) fill(c, l, 648, l + seg, 660, ACCENT);
            else fill(c, l, 648, l + seg, 660, 0xff2a2a2a);
        }

        ThorProgress.Badge next = progress.nextBadge();
        row(c, w, 692, "records", "Next achievement",
                next == null ? "Every achievement earned" : next.title, next == null ? "" : "+" + next.points + " pts",
                () -> show(Tab.RECORDS));
        row(c, w, 780, "assign", "Arcade practice", ThorArcade.values().length + " minigames, separate saves", "",
                () -> show(Tab.ASSIGNMENTS));
        ThorProgress.Decoration item = progress.displayItem();
        row(c, w, 868, "shop", "Desk shop", item == ThorProgress.Decoration.NONE ? "Nothing on the desk" : item.label + " on the desk",
                String.format(Locale.US, "%,d pts", progress.wallet()), () -> show(Tab.DESK));
        row(c, w, 956, "options", "Settings", "Screens, picture and controller", "", () -> show(Tab.OPTIONS));
    }

    private void row(Canvas c, float w, float top, String key, String label, String value, String meta, Runnable action) {
        fill(c, 32, top, w - 32, top + 1, LINE);
        t(c, label, 32, top + 52, 22, SECONDARY);
        paint.setTextSize(26);
        float right = w - 64 - (meta.isEmpty() ? 0 : ThorStyle.width(paint, meta, 22) + 24);
        String text = TextUtils.ellipsize(value, new TextPaint(paint), right - 328, TextUtils.TruncateAt.END).toString();
        t(c, text, 328, top + 52, 26, PRIMARY);
        if (!meta.isEmpty()) tr(c, meta, w - 64, top + 52, 22, SECONDARY);
        chevron(c, w - 44, top + 44);
        targets.add(new Target(key, 32, top + 8, w - 32, top + 80, action));
    }

    private void records(Canvas c, float w) {
        t(c, "Personnel record", 32, 212, 32, PRIMARY);
        tr(c, progress.earnedCount() + " of " + progress.badgeCount() + " earned, "
                + String.format(Locale.US, "%,d pts", progress.wallet()), w - 32, 212, 22, SECONDARY);
        ThorProgress.Badge[] badges = ThorProgress.Badge.values();
        for (int i = 0; i < badges.length; ++i) {
            ThorProgress.Badge badge = badges[i];
            boolean done = progress.earned(badge);
            float top = 240 + i * 72;
            fill(c, 32, top, w - 32, top + 1, LINE);
            if (done) check(c, 44, top + 24);
            else stroke(c, 44, top + 24, 68, top + 48, LINE_STRONG);
            t(c, badge.title, 92, top + 34, 24, done ? PRIMARY : SECONDARY);
            t(c, progress.progressText(badge), 92, top + 60, 18, MUTED);
            tr(c, "+" + badge.points, w - 32, top + 34, 22, done ? ACCENT : MUTED);
            targets.add(new Target("badge" + i, 32, top + 4, w - 32, top + 68, () -> {}));
        }
        float top = 240 + badges.length * 72 + 16;
        fill(c, 32, top, w - 32, top + 104, RAISED);
        if (archiveFrame != null) {
            RectF thumb = new RectF(48, top + 12, 48 + 108, top + 92);
            c.drawBitmap(archiveFrame, null, thumb, art);
        }
        t(c, "Camera archive", 180, top + 46, 26, PRIMARY);
        t(c, archiveCounts[0] + (archiveCounts[0] == 1 ? " capture, " : " captures, ") + archiveCounts[1] + " rare",
                180, top + 78, 20, SECONDARY);
        chevron(c, w - 52, top + 52);
        targets.add(new Target("archive", 32, top, w - 32, top + 104, host::openArchive));
    }

    private void assignments(Canvas c, float w) {
        t(c, "Arcade practice", 32, 212, 32, PRIMARY);
        tr(c, "Separate saves, campaign untouched", w - 32, 212, 22, SECONDARY);
        ThorArcade[] stages = ThorArcade.values();
        for (int i = 0; i < stages.length; ++i) {
            float top = 240 + i * 72;
            if (("arcade" + i).equals(focusKey)) arcadeIndex = i;
            fill(c, 32, top, w - 32, top + 1, LINE);
            t(c, stages[i].title, 32, top + 46, 26, PRIMARY);
            tr(c, "Night " + stages[i].night, w - 64, top + 46, 22, SECONDARY);
            chevron(c, w - 44, top + 38);
            ThorArcade stage = stages[i];
            targets.add(new Target("arcade" + i, 32, top + 4, w - 32, top + 68, () -> host.startArcade(stage)));
        }
        guide(c, w, stages[arcadeIndex], 704);
    }

    private void guide(Canvas c, float w, ThorArcade stage, float top) {
        fill(c, 32, top, w - 32, top + 1, LINE);
        if (!guideOpen) {
            keycap(c, 32, top + 28, "A");
            t(c, "Start practice", 84, top + 54, 22, SECONDARY);
            keycap(c, 300, top + 28, "Y");
            t(c, "Show guide", 352, top + 54, 22, SECONDARY);
            t(c, "The guide explains how to open " + stage.title + " and contains spoilers", 32, top + 108, 20, MUTED);
            targets.add(new Target("guide", 296, top + 20, 520, top + 72, () -> guideOpen = true));
            return;
        }
        t(c, "Guide, " + stage.title, 32, top + 52, 22, SECONDARY);
        TextPaint body = new TextPaint(paint);
        body.setTextSize(24);
        body.setColor(PRIMARY);
        StaticLayout layout = StaticLayout.Builder.obtain(stage.instructions, 0, stage.instructions.length(), body,
                Math.round(w - 64)).setMaxLines(5).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(8, 1f).build();
        int save = c.save();
        c.translate(32, top + 72);
        layout.draw(c);
        c.restoreToCount(save);
        targets.add(new Target("guide", 32, top + 16, w - 32, top + 260, () -> guideOpen = false));
    }

    private void practice(Canvas c, float w) {
        ThorArcade stage = arcadeSession;
        RectF card = new RectF(32, 120, w - 32, 440);
        fill(c, card.left, card.top, card.right, card.bottom, RAISED);
        t(c, "Arcade practice, night " + stage.night, 64, 176, 22, SECONDARY);
        t(c, stage.title, 64, 240, 44, PRIMARY);
        t(c, "Runs on its own save. The campaign is untouched", 64, 288, 22, SECONDARY);
        float bw = ThorStyle.width(paint, "Start practice", 26) + 112;
        fill(c, 64, 352, 64 + bw, 408, PRIMARY);
        keycapDark(c, 80, 364, "A");
        t(c, "Start practice", 128, 390, 26, 0xff111111);
        targets.add(new Target("start", 64, 352, 64 + bw, 408, host::startNight));
        guide(c, w, stage, 472);
        fill(c, 32, 968, w - 32, 969, LINE);
        t(c, "Return to campaign", 32, 1024, 24, PRIMARY);
        chevron(c, w - 44, 1016);
        targets.add(new Target("campaign", 32, 976, w - 32, 1048, host::returnToCampaign));
    }

    private void desk(Canvas c, float w) {
        ThorProgress.Decoration item = ITEMS[shopIndex];
        t(c, "Desk shop", 32, 212, 32, PRIMARY);
        tr(c, String.format(Locale.US, "%,d pts to spend", progress.wallet()), w - 32, 212, 22, SECONDARY);
        RectF stage = new RectF(32, 240, w - 32, 640);
        fill(c, stage.left, stage.top, stage.right, stage.bottom, RAISED);
        ThorProgress.drawDecoration(c, paint, item, stage.centerX() - 200, 300, 400, 300);
        arrow(c, 72, 440, -1);
        arrow(c, w - 72, 440, 1);
        targets.add(new Target("prev", 32, 240, 160, 640, () -> { shopIndex = Math.floorMod(shopIndex - 1, ITEMS.length); notice = null; }));
        targets.add(new Target("next", w - 160, 240, w - 32, 640, () -> { shopIndex = (shopIndex + 1) % ITEMS.length; notice = null; }));
        t(c, item.label, 32, 708, 36, PRIMARY);
        tr(c, (shopIndex + 1) + " of " + ITEMS.length, w - 32, 708, 22, SECONDARY);
        boolean owned = progress.owns(item);
        boolean on = progress.displayItem() == item;
        boolean affordable = progress.wallet() >= item.points;
        String action = on ? "Take it off the desk" : owned ? "Put it on the desk"
                : affordable ? "Buy for " + item.points + " pts" : "Needs " + item.points + " pts";
        boolean enabled = owned || affordable;
        float bw = ThorStyle.width(paint, action, 26) + 112;
        fill(c, 32, 740, 32 + bw, 796, enabled ? PRIMARY : RAISED);
        if (enabled) keycapDark(c, 48, 752, "A");
        t(c, action, enabled ? 96 : 56, 778, 26, enabled ? 0xff111111 : MUTED);
        targets.add(new Target("deskAction", 32, 740, 32 + bw, 796, () -> notice = progress.deskAction(item)));
        t(c, notice != null ? notice : "Items sit in the corner of the office view", 32, 852, 22,
                notice != null && notice.contains("more points") ? ERROR : SECONDARY);
    }

    private void options(Canvas c, float w) {
        Context context = getContext();
        SharedPreferences s = PreferenceManager.getDefaultSharedPreferences(context);
        int[] current = layoutSettings(context);
        String[] modes = {"Auto", "Dual screen", "Single screen"};
        String[][] rows = {
                {"Display", modes[Math.max(0, Math.min(2, current[0]))]},
                {"Screen order", current[1] == 1 ? "Cameras on top" : "Office on top"},
                {"4:3 office view", current[2] == 1 ? "On" : "Off"},
                {"Smooth scaling", current[3] == 1 ? "On" : "Off"},
                {"Office perspective", current[4] == 1 ? "On" : "Off"},
                {"Renderer", current[5] == 1 ? "DXVK, 60 fps" : "WineD3D, 30 fps"},
                {"Look sensitivity", s.getInt("thor_controller_sensitivity", 100) + "%"},
                {"Controller mappings", ""},
                {"Updates and Steam import", ""},
                {"Open-source licenses", ""}};
        t(c, "Settings", 32, 212, 32, PRIMARY);
        tr(c, "Saved on this device", w - 32, 212, 22, SECONDARY);
        for (int i = 0; i < rows.length; ++i) {
            float top = 240 + i * 64;
            fill(c, 32, top, w - 32, top + 1, LINE);
            t(c, rows[i][0], 32, top + 42, 26, PRIMARY);
            if (!rows[i][1].isEmpty()) tr(c, rows[i][1], w - 64, top + 42, 24, SECONDARY);
            chevron(c, w - 44, top + 34);
            int index = i;
            targets.add(new Target("opt" + i, 32, top + 4, w - 32, top + 60, () -> option(index)));
        }
        boolean pending = !java.util.Arrays.equals(current, launchLayout);
        if (pending) {
            t(c, "Screen, picture and renderer changes apply after a restart", 32, 920, 22, SECONDARY);
            float bw = ThorStyle.width(paint, "Restart now", 26) + 112;
            fill(c, 32, 944, 32 + bw, 1000, PRIMARY);
            keycapDark(c, 48, 956, "A");
            t(c, "Restart now", 96, 982, 26, 0xff111111);
            targets.add(new Target("restart", 32, 944, 32 + bw, 1000, host::restartToApply));
        }
    }

    private void option(int index) {
        SharedPreferences s = PreferenceManager.getDefaultSharedPreferences(getContext());
        SharedPreferences.Editor e = s.edit();
        switch (index) {
            case 0: e.putInt("thor_display_mode", (s.getInt("thor_display_mode", 0) + 1) % 3); break;
            case 1: e.putBoolean("thor_swap_screens", !s.getBoolean("thor_swap_screens", false)); break;
            case 2: e.putBoolean("thor_fit_office", !s.getBoolean("thor_fit_office", false)); break;
            case 3: e.putBoolean("thor_smooth_picture", !s.getBoolean("thor_smooth_picture", true)); break;
            case 4: e.putBoolean("thor_office_perspective", !s.getBoolean("thor_office_perspective", false)); break;
            case 5: e.putBoolean("thor_dxvk", !s.getBoolean("thor_dxvk", true)); break;
            case 6: {
                int value = s.getInt("thor_controller_sensitivity", 100) + 25;
                s.edit().putInt("thor_controller_sensitivity", value > 175 ? 25 : value).apply();
                invalidate();
                return;
            }
            case 7: host.openControllerMappings(); return;
            case 8: host.openSetup(); return;
            default: host.openLicenses(); return;
        }
        e.apply();
        invalidate();
    }

    /** Licence pages open as sheets on the activity's own (upper) window. */
    static void showLicenses(android.app.Activity activity) {
        new ThorSheet(activity).setTitle("Open-source licenses").setMeta("LGPL 2.1")
                .setMessage(asset(activity, "licenses/NOTICE.txt"))
                .setNeutralButton("Full LGPL text", (dialog, which) -> new ThorSheet(activity)
                        .setTitle("GNU LGPL 2.1").setMessage(asset(activity, "licenses/LGPL-2.1.txt"))
                        .setNegativeButton("Back", (d, b) -> showLicenses(activity)).show())
                .setNegativeButton("Back", null).show();
    }

    private static String asset(Context context, String path) {
        try (java.io.InputStream input = context.getAssets().open(path)) {
            return new String(readAll(input), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException error) {
            return "The licence text could not be opened.";
        }
    }

    private static byte[] readAll(java.io.InputStream input) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = input.read(buffer)) != -1) out.write(buffer, 0, n);
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ primitives

    private void fill(Canvas c, float l, float t, float r, float b, int color) {
        ThorStyle.fill(c, paint, l, t, r, b, color);
    }

    private void stroke(Canvas c, float l, float t, float r, float b, int color) {
        ThorStyle.outline(c, paint, l, t, r, b, 2, color);
    }

    private void t(Canvas c, String s, float x, float y, float size, int color) {
        ThorStyle.text(c, paint, s, x, y, size, color);
    }

    private void tr(Canvas c, String s, float right, float y, float size, int color) {
        ThorStyle.textRight(c, paint, s, right, y, size, color);
    }

    private void keycap(Canvas c, float l, float t, String label) {
        ThorStyle.keycap(c, paint, l, t, l + 40, t + 32, label, LINE_STRONG, SECONDARY, 1);
    }

    private void keycapDark(Canvas c, float l, float t, String label) {
        ThorStyle.keycap(c, paint, l, t, l + 32, t + 32, label, 0x66111111, 0xff111111, 1);
    }

    private void chevron(Canvas c, float x, float y) {
        Path path = new Path();
        path.moveTo(x - 6, y - 10);
        path.lineTo(x + 2, y - 2);
        path.lineTo(x - 6, y + 6);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2);
        paint.setColor(MUTED);
        c.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void arrow(Canvas c, float x, float y, int direction) {
        Path path = new Path();
        path.moveTo(x + 12 * direction, y);
        path.lineTo(x - 12 * direction, y - 20);
        path.lineTo(x - 12 * direction, y + 20);
        path.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(SECONDARY);
        c.drawPath(path, paint);
    }

    private void check(Canvas c, float l, float t) {
        fill(c, l, t, l + 24, t + 24, ACCENT);
        Path path = new Path();
        path.moveTo(l + 5, t + 12);
        path.lineTo(l + 10, t + 18);
        path.lineTo(l + 19, t + 6);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3);
        paint.setColor(0xff111111);
        c.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);
    }
}
