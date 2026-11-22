package com.winlator;

import android.app.Presentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.winlator.xserver.Pointer;
import com.winlator.xserver.XKeycode;
import com.winlator.xserver.XServer;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

final class ThorDualScreen implements DisplayManager.DisplayListener {
    private static final Rect CAMERA_CROP = new Rect(77, 38, 505, 376);
    private static final Rect MAINTENANCE_CROP = new Rect(28, 86, 405, 384);
    private static final Rect OFFICE_CROP = new Rect(4, 4, 516, 388);
    private static final Rect SCENE_HEADER = new Rect(0, 0, 512, 36);
    private static final Rect FULL_SCENE = new Rect(0, 0, 512, 384);
    private static final int CAMERA_VISIBLE = 1;
    private static final int IN_OFFICE = 2;
    private static final int MONITOR_SWITCH = 4;
    private static final int MAINTENANCE_VISIBLE = 8;
    private static final int MAINTENANCE_SWITCH = 16;
    private enum Phase { WAIT_NIGHT, WAIT_CAMERA, SELECT_ROOM, TRIGGER, PLAYING, DONE, FAILED }
    private enum Opening { NONE, MONITOR, MAINTENANCE }
    private static final long MAX_FRAME_AGE_MS = 700;
    private static final long PANEL_OPEN_TIMEOUT_MS = 8000;

    static {
        System.loadLibrary("winlator");
    }

    private static native boolean copyFrame(ByteBuffer mapped, int[] pixels, int[] metadata,
            int oldSequence, int width, int height);

    private final XServerDisplayActivity activity;
    private final XServer xServer;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final DisplayManager displays;
    private final ScreenView upper;
    private final DeskPanel upperDesk;
    private final boolean swapped;
    private final boolean arcade;
    private final ThorArcade arcadeStage;
    private boolean enteredPracticeNight;
    private final boolean usePresentation;
    private final ArcadeShortcut arcadeShortcut;
    private final boolean fitOffice;
    private final boolean smoothPicture;
    private final boolean perspective;
    private final FrameSource scene;
    private final FrameSource office;
    private final ThorProgress progress;
    private final ThorGallery gallery;
    private final DeskDecoration decoration;
    private long lastSaveCheck;
    private MonitorPresentation presentation;
    private boolean active;
    private boolean cameraVisible;
    private boolean maintenanceVisible;
    private boolean buttonDown;
    private long lastSceneAt;
    private long lastPairAt;
    private int acceptedPanel;
    private final File consumerFlag;
    private Opening opening = Opening.NONE;
    private boolean inOffice;
    private long openDeadline;
    private Runnable pendingPress;
    private Runnable nextTap;
    private final Runnable releaseTap = this::finishTap;

    // Redone title over the original game's title. BOOT: game loading. TITLE: menu shown.
    // PENDING: a night was chosen and the original menu is being driven. HIDDEN: the game is playing.
    // ORIGINAL: the player chose the original menu (extras) and drives it directly.
    private enum TitleState { BOOT, TITLE, PENDING, HIDDEN, ORIGINAL }
    private enum TitleAction { LOAD, NEW }
    private static final float[] NEW_GAME_POINT = {100, 225};
    private static final float[] LOAD_GAME_POINT = {110, 262};
    private static final int[][] MAINTENANCE_ROWS = {{150, 188}, {150, 217}, {150, 246}, {150, 299}, {150, 324}};
    private final ThorTitleView titleView;
    private final ThorManagerView upperManager;
    private final int[] launchLayout;
    private TitleState titleState = TitleState.BOOT;
    private TitleAction titleAction = TitleAction.LOAD;
    private boolean managerFocus;
    private boolean titleOnScreen;
    private boolean extrasOnScreen;
    private long titleSince;
    private long titleLastSeen;
    private long nextTitleClick;
    private int titleClicks;
    private int maintenanceRow;

    ThorDualScreen(XServerDisplayActivity activity, FrameLayout root, XServer xServer, File sourceDir) {
        this.activity = activity;
        this.xServer = xServer;
        this.displays = (DisplayManager) activity.getSystemService(Context.DISPLAY_SERVICE);
        consumerFlag = new File(sourceDir, "thor-display-active.flag");
        progress = new ThorProgress(activity);
        gallery = new ThorGallery(activity);
        scene = new FrameSource(new File(sourceDir, "thor-scene.frames"), 512, 384);
        office = new FrameSource(new File(sourceDir, "thor-office.frames"), 524, 395);
        arcadeStage = ThorArcade.parse(activity.getIntent().getStringExtra("thor_arcade_stage"));
        arcade = arcadeStage != null;
        usePresentation = activity.getIntent().getBooleanExtra("thor_use_presentation", true);
        swapped = usePresentation && activity.getIntent().getBooleanExtra("thor_swap_screens", false);
        arcadeShortcut = arcadeStage == ThorArcade.BALLOON ? new ArcadeShortcut() : null;
        fitOffice = activity.getIntent().getBooleanExtra("thor_fit_office", false);
        smoothPicture = activity.getIntent().getBooleanExtra("thor_smooth_picture", true);
        perspective = activity.getIntent().getBooleanExtra("thor_office_perspective", false);
        upper = new ScreenView(activity, swapped);
        upper.setVisibility(View.GONE);
        root.addView(upper, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        upperDesk = swapped ? new DeskPanel(activity, "Panels unlock once the office is on screen") : null;
        if (upperDesk != null) {
            upperDesk.setVisibility(View.GONE);
            root.addView(upperDesk, new FrameLayout.LayoutParams(-1, -1));
        }
        decoration = new DeskDecoration(activity);
        decoration.setVisibility(View.GONE);
        float density = activity.getResources().getDisplayMetrics().density;
        FrameLayout.LayoutParams item = new FrameLayout.LayoutParams((int)(95 * density), (int)(78 * density),
                android.view.Gravity.BOTTOM | android.view.Gravity.RIGHT);
        item.rightMargin = (int)(16 * density);
        item.bottomMargin = (int)(14 * density);
        root.addView(decoration, item);

        launchLayout = ThorManagerView.layoutSettings(activity);
        titleView = new ThorTitleView(activity);
        titleView.setOverArt(true);
        titleView.setListener(new ThorTitleView.Listener() {
            @Override
            public void onSelect(ThorTitleView.Entry entry) {
                selectTitle(entry);
            }

            @Override
            public void onHighlight(ThorTitleView.Entry entry) {
                previewTab(entry);
            }
        });
        root.addView(titleView, new FrameLayout.LayoutParams(-1, -1));
        upperManager = new ThorManagerView(activity, managerHost, arcadeStage, launchLayout);
        upperManager.setVisibility(View.GONE);
        root.addView(upperManager, new FrameLayout.LayoutParams(-1, -1));
        rebuildTitle();
        setTitleState(TitleState.BOOT);
    }

    void resume() {
        if (active || displays == null) return;
        active = true;
        displays.registerDisplayListener(this, handler);
        updateDisplay();
        setConsumerActive(true);
        handler.post(this::poll);
    }

    void pause() {
        if (!active) return;
        active = false;
        handler.removeCallbacksAndMessages(null);
        cancelTap();
        displays.unregisterDisplayListener(this);
        dismissMonitor();
    }

    private static Display lowerDisplay(Context context, int activeDisplayId) {
        DisplayManager manager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        if (manager == null) return null;
        for (Display candidate : manager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)) {
            if (candidate.getDisplayId() != activeDisplayId && candidate.isValid()
                    && candidate.getState() == Display.STATE_ON
                    && "Screen-2".equals(candidate.getName())) return candidate;
        }
        return null;
    }

    private void updateDisplay() {
        if (!active || !usePresentation) return;
        int contentDisplayId = activity.getWindowManager().getDefaultDisplay().getDisplayId();
        Display target = lowerDisplay(activity, contentDisplayId);
        if (presentation != null && (target == null
                || presentation.getDisplay().getDisplayId() != target.getDisplayId())) dismissMonitor();
        if (presentation != null || target == null) return;
        MonitorPresentation monitor = new MonitorPresentation(activity, target);
        try {
            monitor.show();
            presentation = monitor;
            setConsumerActive(true);
        } catch (WindowManager.InvalidDisplayException ignored) {
            monitor.dismiss();
        }
    }

    private void dismissMonitor() {
        cancelTap();
        setConsumerActive(active);
        if (presentation != null) presentation.screen.releaseTouch();
        inOffice = false;
        decoration.setVisibility(View.GONE);
        opening = Opening.NONE;
        cameraVisible = false;
        maintenanceVisible = false;
        lastSceneAt = 0;
        lastPairAt = 0;
        acceptedPanel = 0;
        upper.setVisibility(View.GONE);
        if (upperDesk != null) upperDesk.setVisibility(View.GONE);
        if (presentation != null) {
            presentation.dismiss();
            presentation = null;
        }
    }

    private void setConsumerActive(boolean enabled) {
        try {
            if (enabled) {
                if (!consumerFlag.exists()) Files.createFile(consumerFlag.toPath());
            } else {
                Files.deleteIfExists(consumerFlag.toPath());
            }
        } catch (IOException error) {
            android.util.Log.w("ThorDualScreen", "Cannot change render capture state", error);
        }
    }

    private void poll() {
        if (!active) return;
        {
            long now = SystemClock.uptimeMillis();
            boolean sceneUpdated = scene.read();
            boolean officeUpdated = office.read();
            if (sceneUpdated) lastSceneAt = now;
            boolean frameFresh = lastSceneAt != 0 && now - lastSceneAt <= MAX_FRAME_AGE_MS;
            int flags = scene.metadata[2];
            boolean currentCamera = frameFresh && (flags & CAMERA_VISIBLE) != 0;
            boolean currentMaintenance = frameFresh && (flags & MAINTENANCE_VISIBLE) != 0;
            int currentPanel = currentCamera ? CAMERA_VISIBLE : currentMaintenance ? MAINTENANCE_VISIBLE : 0;
            if (currentPanel != acceptedPanel) {
                acceptedPanel = 0;
                lastPairAt = 0;
            }
            boolean newOffice = frameFresh && (flags & IN_OFFICE) != 0;
            if (newOffice != inOffice) {
                inOffice = newOffice;
                if (inOffice) {
                    if (arcade) enteredPracticeNight = true;
                    else progress.recordOffice();
                }
                refreshProgress();
            }
            updatePanelRequest(now, flags, frameFresh);
            if (!arcade && now - lastSaveCheck >= 2000) {
                lastSaveCheck = now;
                if (progress.refreshSave()) refreshProgress();
            }
            boolean completePair = currentPanel != 0 && scene.metadata[1] == office.metadata[1]
                    && office.metadata[0] != 0;
            if (completePair && (sceneUpdated || officeUpdated)) {
                acceptedPanel = currentPanel;
                lastPairAt = now;
                scene.upload();
                if (currentCamera && !arcade) {
                    boolean vent = ventMap();
                    int selected = selectedCamera(vent ? VENTS : ROOMS);
                    if (progress.recordCamera(vent, selected)) refreshProgress();
                    gallery.update(scene.bitmap, CAMERA_CROP, selected < 0 ? -1 : selected + (vent ? 11 : 1), now);
                }
                office.upload();
                upper.invalidate();
                if (presentation != null) presentation.screen.invalidate();
            }
            if (presentation != null && swapped && newOffice && !currentCamera && !currentMaintenance && sceneUpdated) {
                scene.upload();
                presentation.screen.invalidate();
            }
            boolean pairFresh = acceptedPanel == currentPanel && lastPairAt != 0 && now - lastPairAt <= MAX_FRAME_AGE_MS;
            boolean showCamera = currentCamera && pairFresh;
            boolean showMaintenance = currentMaintenance && pairFresh;
            if (cameraVisible != showCamera || maintenanceVisible != showMaintenance) {
                if (!showCamera) gallery.reset();
                if (panelVisible()) cancelTap();
                upper.releaseTouch();
                if (presentation != null) presentation.screen.releaseTouch();
                boolean maintenanceOpened = showMaintenance && !maintenanceVisible;
                cameraVisible = showCamera;
                maintenanceVisible = showMaintenance;
                if (showMaintenance && !arcade) progress.recordMaintenance();
                if (maintenanceOpened) {
                    maintenanceRow = 0;
                    moveGuestPointer(MAINTENANCE_ROWS[0][0], MAINTENANCE_ROWS[0][1]);
                }
                updateControls();
            }
            if (arcadeShortcut != null) arcadeShortcut.update(now, frameFresh);
            if (sceneUpdated) watchTitle(now);
            else if (!frameFresh) titleOnScreen = false;
            updateTitle(now);
        }
        handler.postDelayed(this::poll, 24);
    }

    private void refreshProgress() {
        decoration.setVisibility(inOffice && !swapped && !arcade
                && progress.displayItem() != ThorProgress.Decoration.NONE ? View.VISIBLE : View.GONE);
        decoration.invalidate();
        updateControls();
    }

    private boolean panelVisible() {
        return cameraVisible || maintenanceVisible;
    }

    private void updateControls() {
        refreshMonitorHints();
        boolean menu = titleShown();
        titleView.setVisibility(menu ? View.VISIBLE : View.GONE);
        upperManager.setVisibility(menu && managerFocus && presentation == null ? View.VISIBLE : View.GONE);
        upper.setVisibility(presentation != null && panelVisible() ? View.VISIBLE : View.GONE);
        if (upperDesk != null) {
            upperDesk.setVisibility(presentation != null && inOffice && !panelVisible() ? View.VISIBLE : View.GONE);
            upperDesk.update();
        }
        if (presentation != null) presentation.updateControls();
    }

    private static final int[][] ROOMS = {
            {335, 339}, {441, 326}, {482, 304}, {482, 271}, {400, 283},
            {331, 284}, {332, 250}, {383, 242}, {415, 218}, {470, 236}
    };
    private static final int[][] VENTS = {
            {334, 204}, {363, 262}, {405, 293}, {448, 252}, {462, 323}
    };

    boolean isNightActive() {
        return active && inOffice;
    }

    boolean canPanOffice() {
        return isNightActive() && activity.hasWindowFocus() && !panelVisible()
                && opening == Opening.NONE && pendingPress == null && !buttonDown;
    }

    void panOffice(float amount) {
        if (!canPanOffice()) return;
        float normalized = Math.min(1f, Math.max(-1f, amount * 2.7f));
        moveGuestPointer(256 + normalized * 250, 190);
    }

    /** D-pad up/down in a night: choose a maintenance system, or step cameras. */
    boolean navigateVertical(int direction) {
        if (!isNightActive()) return false;
        if (maintenanceVisible) {
            maintenanceRow = Math.floorMod(maintenanceRow + direction, MAINTENANCE_ROWS.length);
            moveGuestPointer(MAINTENANCE_ROWS[maintenanceRow][0], MAINTENANCE_ROWS[maintenanceRow][1]);
            return true;
        }
        return cameraVisible && stepCamera(direction);
    }

    boolean control(ThorController.Action action) {
        if (!isNightActive()) return false;
        switch (action) {
            case MONITOR:
                return requestPanel(Opening.MONITOR);
            case MAINTENANCE:
                return requestPanel(Opening.MAINTENANCE);
            case VENT_MAP:
                if (!cameraVisible) return false;
                routeTap(266, 331);
                return true;
            case AUDIO:
                if (!cameraVisible || ventMap()) return false;
                routeTap(265, 291);
                return true;
            case NEXT_CAMERA:
                return stepCamera(1);
            case PREVIOUS_CAMERA:
                return stepCamera(-1);
            case SNAPSHOT:
                if (!cameraVisible || arcade) return false;
                boolean saved = gallery.snapshot(scene.bitmap, CAMERA_CROP);
                android.widget.Toast.makeText(activity, saved ? "Snapshot queued" : "Camera archive is full",
                        android.widget.Toast.LENGTH_SHORT).show();
                return true;
            case MUTE_CALL:
                clickGuest(57, 20, false);
                return true;
            default:
                return false;
        }
    }

    private boolean ventMap() {
        int count = 0;
        for (int x : new int[]{318, 350, 373, 407}) {
            int pixel = scene.renderedPixel(x, 225);
            int red = (pixel >> 16) & 255, green = (pixel >> 8) & 255, blue = pixel & 255;
            if (blue >= 110 && blue >= green + 12 && green >= red + 15) ++count;
        }
        return count >= 3;
    }

    private boolean stepCamera(int direction) {
        if (!cameraVisible) return false;
        int[][] cameras = ventMap() ? VENTS : ROOMS;
        int selected = selectedCamera(cameras);
        if (selected < 0) selected = direction > 0 ? cameras.length - 1 : 0;
        int next = Math.floorMod(selected + direction, cameras.length);
        routeTap(cameras[next][0], cameras[next][1]);
        return true;
    }

    private int selectedCamera(int[][] cameras) {
        for (int i = 0; i < cameras.length; ++i)
            if (isSelected(cameras[i][0], cameras[i][1])) return i;
        return -1;
    }

    private boolean isSelected(int x, int y) {
        int greenPixels = 0;
        for (int row = y - 2; row <= y + 2; ++row) {
            for (int col = x - 4; col <= x + 4; ++col) {
                int pixel = scene.renderedPixel(col, row);
                int red = (pixel >> 16) & 255, green = (pixel >> 8) & 255, blue = pixel & 255;
                if (green >= 110 && green >= red + 12 && blue <= 55) ++greenPixels;
            }
        }
        return greenPixels >= 14;
    }

    private boolean requestPanel(Opening panel) {
        if (!active || !activity.hasWindowFocus() || !inOffice || opening != Opening.NONE) return false;
        if (panelVisible()) {
            if (panel == Opening.MONITOR && cameraVisible) {
                routeTap(471, 63);
                return true;
            }
            if (panel == Opening.MAINTENANCE && maintenanceVisible) {
                routeTap(125, 322);
                return true;
            }
            return false;
        }
        opening = panel;
        openDeadline = SystemClock.uptimeMillis() + PANEL_OPEN_TIMEOUT_MS;
        moveGuestPointer(panel == Opening.MONITOR ? 506 : 5, 190);
        updateControls();
        return true;
    }

    private void updatePanelRequest(long now, int flags, boolean fresh) {
        if (opening == Opening.NONE) return;
        boolean monitor = opening == Opening.MONITOR;
        boolean press = fresh && (flags & (monitor ? MONITOR_SWITCH : MAINTENANCE_SWITCH)) != 0;
        if (!press && now < openDeadline && !panelVisible()) return;
        opening = Opening.NONE;
        if (press && !panelVisible()) clickGuest(monitor ? 471 : 207, monitor ? 63 : 361, false);
        updateControls();
    }

    private void moveGuestPointer(float x, float y) {
        int guestX = Math.min(xServer.screenInfo.width - 1,
                Math.max(0, Math.round(x * xServer.screenInfo.width / scene.width)));
        int guestY = Math.min(xServer.screenInfo.height - 1,
                Math.max(0, Math.round(y * xServer.screenInfo.height / scene.height)));
        xServer.injectPointerMove(guestX, guestY);
    }

    private void routeTap(float x, float y) {
        clickGuest(x, y, true);
    }

    private void clickGuest(float x, float y, boolean requireCamera) {
        if (!acceptClick(requireCamera)) return;
        if (pendingPress != null || buttonDown) {
            if (nextTap == null) nextTap = () -> clickGuest(x, y, requireCamera);
            return;
        }
        if (xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_LEFT)) return;
        moveGuestPointer(x, y);
        pendingPress = () -> {
            pendingPress = null;
            if (!acceptClick(requireCamera) || xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_LEFT)) {
                nextTap = null;
                return;
            }
            moveGuestPointer(x, y);
            xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT);
            buttonDown = true;
            handler.postDelayed(releaseTap, 82);
        };
        handler.postDelayed(pendingPress, 38);
    }

    private boolean acceptClick(boolean requirePanel) {
        return active && activity.hasWindowFocus() && (panelVisible() || inOffice && !requirePanel);
    }

    // ---------------------------------------------------------------- Redone title

    private final ThorManagerView.Host managerHost = new ThorManagerView.Host() {
        @Override public void startNight() {
            begin(arcade || progress.savedNight() > 0 ? TitleAction.LOAD : TitleAction.NEW);
        }
        @Override public void startArcade(ThorArcade stage) { activity.thorEnd(ThorSession.Next.ARCADE, stage); }
        @Override public void returnToCampaign() { activity.thorEnd(ThorSession.Next.RESTART, null); }
        @Override public void openArchive() { ThorGallery.show(activity); }
        @Override public void openControllerMappings() { ThorController.showSettings(activity); }
        @Override public void restartToApply() { activity.thorEnd(ThorSession.Next.RESTART, null); }
        @Override public void openSetup() { activity.thorEnd(ThorSession.Next.SETUP, null); }
        @Override public void openLicenses() { ThorManagerView.showLicenses(activity); }
        @Override public void managerBack() { closePage(); }
    };

    boolean titleShown() {
        return titleState == TitleState.BOOT || titleState == TitleState.TITLE || titleState == TitleState.PENDING;
    }

    /** Keys that the title, its lower pages or the original-menu mode use. */
    boolean menuKey(int key, boolean down) {
        if (titleState == TitleState.ORIGINAL) {
            if (key == android.view.KeyEvent.KEYCODE_BUTTON_B) {
                if (down) setTitleState(TitleState.TITLE);
                return true;
            }
            return false;
        }
        if (!titleShown() || key == android.view.KeyEvent.KEYCODE_BUTTON_START) return false;
        if (managerFocus) {
            manager().key(key, down);
            return true;
        }
        titleView.key(key, down);
        return true;
    }

    /** Back on a lower page returns to the title; returns false when there is no page to leave. */
    boolean backFromPage() {
        if (titleState == TitleState.ORIGINAL) {
            setTitleState(TitleState.TITLE);
            return true;
        }
        if (!managerFocus) return false;
        closePage();
        return true;
    }

    private ThorManagerView manager() {
        return presentation != null && presentation.manager != null ? presentation.manager : upperManager;
    }

    private void rebuildTitle() {
        java.util.List<ThorTitleView.Entry> entries = new java.util.ArrayList<>();
        String keep;
        if (arcade) {
            entries.add(new ThorTitleView.Entry("load", "Start Practice"));
            entries.add(new ThorTitleView.Entry("guide", "Guide"));
            entries.add(new ThorTitleView.Entry("campaign", "Campaign"));
            entries.add(new ThorTitleView.Entry("exit", "Exit"));
            keep = "load";
        } else {
            int night = progress.savedNight();
            entries.add(new ThorTitleView.Entry("new", "New Game"));
            if (night > 0) entries.add(new ThorTitleView.Entry("load", "Night " + night));
            if (extrasOnScreen) entries.add(new ThorTitleView.Entry("extras", "Extras"));
            entries.add(new ThorTitleView.Entry("arcade", "Arcade"));
            entries.add(new ThorTitleView.Entry("achievements", "Achievements"));
            entries.add(new ThorTitleView.Entry("shop", "Desk Shop"));
            entries.add(new ThorTitleView.Entry("settings", "Settings"));
            entries.add(new ThorTitleView.Entry("exit", "Exit"));
            keep = night > 0 ? "load" : "new";
        }
        ThorTitleView.Entry current = titleView.selectedEntry();
        titleView.setEntries(entries, current != null ? current.id : keep);
    }

    private void selectTitle(ThorTitleView.Entry entry) {
        switch (entry.id) {
            case "load": begin(TitleAction.LOAD); break;
            case "new":
                if (progress.savedNight() > 1) {
                    new ThorSheet(activity).setTitle("New game").setMeta("Saved Night " + progress.savedNight()).safeDefault()
                            .setMessage("Start again from Night 1? The original game replaces its saved night "
                                    + "when the new game begins.")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Start new game", (dialog, button) -> begin(TitleAction.NEW)).show();
                } else {
                    begin(TitleAction.NEW);
                }
                break;
            case "extras":
                setTitleState(TitleState.ORIGINAL);
                android.widget.Toast.makeText(activity, "Original menu. Press B to return.",
                        android.widget.Toast.LENGTH_SHORT).show();
                break;
            case "arcade": openPage(ThorManagerView.Tab.ASSIGNMENTS, "ARCADE", "Practice the six minigames"); break;
            case "guide": openPage(ThorManagerView.Tab.SHIFTS, "ARCADE PRACTICE", arcadeStage.title); break;
            case "achievements": openPage(ThorManagerView.Tab.RECORDS, "ACHIEVEMENTS", "Personnel record"); break;
            case "shop": openPage(ThorManagerView.Tab.DESK, "DESK SHOP", "Balance: " + progress.wallet() + " pts"); break;
            case "settings": openPage(ThorManagerView.Tab.OPTIONS, "SETTINGS", "Screens, picture and controller"); break;
            case "campaign": activity.thorEnd(ThorSession.Next.RESTART, null); break;
            case "exit": activity.thorEnd(ThorSession.Next.EXIT, null); break;
            default: break;
        }
    }

    private void previewTab(ThorTitleView.Entry entry) {
        if (managerFocus || presentation == null || arcade) return;
        ThorManagerView.Tab tab;
        switch (entry.id) {
            case "arcade": tab = ThorManagerView.Tab.ASSIGNMENTS; break;
            case "achievements": tab = ThorManagerView.Tab.RECORDS; break;
            case "shop": tab = ThorManagerView.Tab.DESK; break;
            case "settings": tab = ThorManagerView.Tab.OPTIONS; break;
            default: tab = ThorManagerView.Tab.SHIFTS;
        }
        if (manager().tab() != tab) manager().show(tab);
    }

    private void openPage(ThorManagerView.Tab tab, String heading, String line) {
        managerFocus = true;
        ThorManagerView target = manager();
        target.show(tab);
        target.setFocused(true);
        if (presentation != null) titleView.setPage(heading, line, "Use the lower screen", "B  Back to title");
        updateControls();
    }

    private void closePage() {
        managerFocus = false;
        manager().setFocused(false);
        manager().show(ThorManagerView.Tab.SHIFTS);
        titleView.setPage(null);
        rebuildTitle();
        updateControls();
    }

    private void begin(TitleAction action) {
        if (managerFocus) closePage();
        titleAction = action;
        titleClicks = 0;
        nextTitleClick = 0;
        setTitleState(TitleState.PENDING);
    }

    private void setTitleState(TitleState state) {
        titleState = state;
        activity.setThorCursorVisible(state == TitleState.ORIGINAL);
        if (!titleShown()) {
            managerFocus = false;
            titleView.setPage(null);
            manager().setFocused(false);
        }
        switch (state) {
            case BOOT:
                titleView.setStatus(arcade ? "Loading " + arcadeStage.title + " practice\u2026"
                        : "Loading the original game\u2026", ThorStyle.MUTED);
                break;
            case PENDING:
                titleView.setStatus(arcade ? "Starting practice\u2026" : titleAction == TitleAction.NEW
                        ? "Starting a new game\u2026" : "Starting Night " + Math.max(1, progress.savedNight()) + "\u2026",
                        ThorStyle.TEXT);
                break;
            case TITLE:
                titleView.setStatus(arcade ? "Arcade practice  /  " + arcadeStage.title : null, ThorStyle.MUTED);
                progress.refreshSave();
                rebuildTitle();
                manager().refresh();
                break;
            default:
                break;
        }
        releaseTitleInput();
        updateControls();
    }

    private void releaseTitleInput() {
        upper.releaseTouch();
        if (presentation != null) presentation.screen.releaseTouch();
    }

    private void watchTitle(long now) {
        boolean title = countBright(45, 210, 160, 240) >= 250 && countBright(45, 250, 175, 276) >= 250;
        if (title && !titleOnScreen) titleSince = now;
        if (title) titleLastSeen = now;
        titleOnScreen = title;
        titleView.setOverArt(title);
        boolean extras = title && countBright(45, 280, 175, 330) >= 250;
        if (title && extras != extrasOnScreen) {
            extrasOnScreen = extras;
            rebuildTitle();
        }
    }

    private int countBright(int x0, int y0, int x1, int y1) {
        int count = 0;
        int[] pixels = scene.pixels;
        for (int y = y0; y < y1; ++y) {
            for (int x = x0; x < x1; ++x) {
                int pixel = pixels[y * scene.width + x];
                int r = (pixel >> 16) & 255, g = (pixel >> 8) & 255, b = pixel & 255;
                if (g >= 170 && r >= 120 && b >= 80 && g >= b) ++count;
            }
        }
        return count;
    }

    private void updateTitle(long now) {
        switch (titleState) {
            case BOOT:
                if (titleOnScreen) setTitleState(TitleState.TITLE);
                else if (inOffice) setTitleState(TitleState.HIDDEN);
                break;
            case TITLE:
                if (inOffice) setTitleState(TitleState.HIDDEN);
                break;
            case PENDING:
                if (inOffice) {
                    setTitleState(TitleState.HIDDEN);
                } else if (titleOnScreen && now - titleSince >= 450 && now >= nextTitleClick) {
                    if (titleClicks >= 4) {
                        setTitleState(TitleState.TITLE);
                        titleView.setStatus("The original menu did not respond. Try again.", ThorStyle.RED);
                    } else {
                        clickTitle(titleAction == TitleAction.NEW ? NEW_GAME_POINT : LOAD_GAME_POINT);
                        ++titleClicks;
                        nextTitleClick = now + 2600;
                    }
                } else if (!titleOnScreen && titleClicks > 0 && now - titleLastSeen > 120) {
                    setTitleState(TitleState.HIDDEN);
                }
                break;
            case HIDDEN:
                if (titleOnScreen && now - titleSince >= 1500) setTitleState(TitleState.TITLE);
                break;
            case ORIGINAL:
                if (inOffice) setTitleState(TitleState.HIDDEN);
                break;
            default:
                break;
        }
    }

    /** Clicks the original title menu; the Redone title covers it while this happens. */
    private void clickTitle(float[] point) {
        if (pendingPress != null || buttonDown || xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_LEFT)) return;
        moveGuestPointer(point[0], point[1]);
        pendingPress = () -> {
            pendingPress = null;
            if (!active || xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_LEFT)) return;
            moveGuestPointer(point[0], point[1]);
            xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT);
            buttonDown = true;
            handler.postDelayed(releaseTap, 82);
        };
        handler.postDelayed(pendingPress, 120);
    }

    void releaseInput() {
        opening = Opening.NONE;
        cancelTap();
        upper.releaseTouch();
        if (upperDesk != null) upperDesk.releaseKeys();
        if (presentation != null) {
            presentation.screen.releaseTouch();
            presentation.releaseArcadeKeys();
        }
        updateControls();
    }

    private void cancelTap() {
        if (pendingPress != null) handler.removeCallbacks(pendingPress);
        pendingPress = null;
        nextTap = null;
        handler.removeCallbacks(releaseTap);
        releaseButton();
    }
    private void finishTap() {
        releaseButton();
        Runnable queued = nextTap;
        nextTap = null;
        if (queued != null) queued.run();
    }

    private void releaseButton() {
        if (!buttonDown) return;
        buttonDown = false;
        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
    }

    @Override
    public void onDisplayAdded(int displayId) {
        updateDisplay();
    }

    @Override
    public void onDisplayChanged(int displayId) {
        updateDisplay();
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        updateDisplay();
    }

    private final class ArcadeShortcut {
        private Phase phase = Phase.WAIT_NIGHT;
        private long expires;
        private long nextActionAt;

        void update(long now, boolean fresh) {
            if (!activity.hasWindowFocus()) return;
            switch (phase) {
                case WAIT_NIGHT:
                    if (inOffice && requestPanel(Opening.MONITOR)) {
                        expires = now + 24_000;
                        change(Phase.WAIT_CAMERA);
                    }
                    break;
                case WAIT_CAMERA:
                    if (now > expires) change(Phase.FAILED);
                    else if (cameraVisible) {
                        nextActionAt = now + 650;
                        change(Phase.SELECT_ROOM);
                    } else if (opening == Opening.NONE && pendingPress == null && !buttonDown) {
                        requestPanel(Opening.MONITOR);
                    }
                    break;
                case SELECT_ROOM:
                    if (now > expires) change(Phase.FAILED);
                    else if (cameraVisible && isSelected(ROOMS[7][0], ROOMS[7][1])) {
                        nextActionAt = now + 500;
                        change(Phase.TRIGGER);
                    } else if (cameraVisible && now >= nextActionAt) {
                        routeTap(ROOMS[7][0], ROOMS[7][1]);
                        nextActionAt = now + 700;
                    }
                    break;
                case TRIGGER:
                    if (fresh && arcadeTitle()) change(Phase.PLAYING);
                    else if (now > expires) change(Phase.FAILED);
                    else if (cameraVisible && now >= nextActionAt
                            && isSelected(ROOMS[7][0], ROOMS[7][1])) {
                        routeTap(121, 165);
                        routeTap(121, 165);
                        nextActionAt = now + 1300;
                    }
                    break;
                case PLAYING:
                    if (inOffice) change(Phase.DONE);
                    break;
                case FAILED:
                    if (fresh && arcadeTitle()) change(Phase.PLAYING);
                    break;
                default:
                    break;
            }
        }

        private boolean arcadeTitle() {
            int turquoise = 0;
            for (int y : new int[]{14, 18}) {
                for (int x = 18; x < 290; x += 2) {
                    int pixel = scene.pixels[y * scene.width + x];
                    int r = (pixel >> 16) & 255, g = (pixel >> 8) & 255, b = pixel & 255;
                    if (g >= 100 && b >= g - 35 && g >= r + 45) ++turquoise;
                }
            }
            return turquoise >= 115;
        }

        private void change(Phase next) {
            phase = next;
            android.util.Log.i("ThorArcade", "Practice shortcut " + next.name());
            updateControls();
        }

        boolean playing() {
            return phase == Phase.PLAYING;
        }

        String prompt() {
            switch (phase) {
                case WAIT_NIGHT: return "Select Load Game on the upper screen";
                case PLAYING: return "BB’s Air Adventure";
                case DONE: return "Return to the launcher to replay";
                case FAILED: return "Shortcut timed out. Open CAM 08 and double-tap the poster.";
                default: return "Opening BB’s Air Adventure…";
            }
        }
    }

    private final class MonitorPresentation extends Presentation {
        ScreenView screen;
        DeskPanel desk;
        ThorManagerView manager;
        DeskDecoration deskItem;

        void releaseArcadeKeys() {
            if (desk != null) desk.releaseKeys();
        }

        MonitorPresentation(Context context, Display display) {
            super(context, display);
        }

        @Override
        protected void onCreate(Bundle bundle) {
            super.onCreate(bundle);
            Window window = getWindow();
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            window.setBackgroundDrawableResource(android.R.color.black);
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

            FrameLayout content = new FrameLayout(getContext());
            screen = new ScreenView(getContext(), !swapped);
            content.addView(screen, new FrameLayout.LayoutParams(-1, -1));
            desk = new DeskPanel(getContext(), "Panels unlock once the office is on screen");
            content.addView(desk, new FrameLayout.LayoutParams(-1, -1));
            manager = new ThorManagerView(getContext(), managerHost, arcadeStage, launchLayout);
            content.addView(manager, new FrameLayout.LayoutParams(-1, -1));
            if (swapped && !arcade) {
                deskItem = new DeskDecoration(getContext());
                float density = getContext().getResources().getDisplayMetrics().density;
                FrameLayout.LayoutParams item = new FrameLayout.LayoutParams((int) (72 * density), (int) (58 * density),
                        android.view.Gravity.BOTTOM | android.view.Gravity.RIGHT);
                item.setMargins(0, 0, (int) (11 * density), (int) (11 * density));
                content.addView(deskItem, item);
            }
            setContentView(content);
            updateControls();
        }

        void updateControls() {
            if (screen == null) return;
            boolean showScreen = swapped && inOffice || panelVisible();
            boolean menu = titleShown();
            screen.setVisibility(showScreen ? View.VISIBLE : View.GONE);
            manager.setVisibility(menu && !showScreen ? View.VISIBLE : View.GONE);
            desk.setVisibility(showScreen || menu ? View.GONE : View.VISIBLE);
            desk.update();
            if (deskItem != null) {
                deskItem.setVisibility(inOffice && progress.displayItem() != ThorProgress.Decoration.NONE
                        ? View.VISIBLE : View.GONE);
                deskItem.invalidate();
            }
            screen.invalidate();
        }
    }

    /** Binding prompts drawn in the monitor's letterbox, refreshed on panel changes. */
    private String[][] monitorHints = new String[0][];

    private void refreshMonitorHints() {
        if (cameraVisible) {
            java.util.List<String[]> hints = new java.util.ArrayList<>();
            hints.add(new String[]{ThorController.keyLabel(activity, ThorController.Action.MONITOR), "Close"});
            hints.add(new String[]{ThorController.keyLabel(activity, ThorController.Action.VENT_MAP), "Vent map"});
            hints.add(new String[]{ThorController.keyLabel(activity, ThorController.Action.AUDIO), "Audio"});
            hints.add(new String[]{ThorController.keyLabel(activity, ThorController.Action.PREVIOUS_CAMERA) + " "
                    + ThorController.keyLabel(activity, ThorController.Action.NEXT_CAMERA), "Camera"});
            if (!arcade) hints.add(new String[]{ThorController.keyLabel(activity, ThorController.Action.SNAPSHOT), "Snapshot"});
            monitorHints = hints.toArray(new String[0][]);
        } else if (maintenanceVisible) {
            monitorHints = new String[][]{
                    {ThorController.keyLabel(activity, ThorController.Action.MAINTENANCE), "Close"},
                    {ThorController.keyLabel(activity, ThorController.Action.POINTER), "Tap a system to reboot"}};
        } else {
            monitorHints = new String[0][];
        }
    }

    /**
     * Night Security Desk in the 1240 x 1080 panel space on a 4 px grid. The original camera map from
     * the player's own game is the Cameras control; Maintenance lists the original green terminal
     * labels; the in-camera controls use the original Map Toggle and Play Audio labels. Graphite chrome
     * with the terminal green as the single accent.
     */
    private final class DeskPanel extends View {
        private static final int BASE = 0xff0e0e0e, RAISED = 0xff1a1a1a, RAISED_ON = 0xff262624;
        private static final int LINE = 0x1affffff, LINE_STRONG = 0x33ffffff;
        private static final int PRIMARY = 0xffecebe6, SECONDARY = 0xffa9a9a3, MUTED = 0xff6f6f6b;
        private static final int ACCENT = 0xff8fe05a;
        private final android.graphics.Paint paint = ThorStyle.textPaint();
        private final android.graphics.Paint art = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG
                | android.graphics.Paint.FILTER_BITMAP_FLAG);
        private final String waiting;
        private final java.util.EnumSet<XKeycode> heldKeys = java.util.EnumSet.noneOf(XKeycode.class);
        private final android.util.SparseArray<XKeycode> pointerKeys = new android.util.SparseArray<>();
        private final RectF cameraBox = new RectF(), serviceBox = new RectF(), muteBox = new RectF(), pauseBox = new RectF();
        private final RectF[] arrowBoxes = {new RectF(), new RectF(), new RectF(), new RectF()};
        private final XKeycode[] arrowKeys = {XKeycode.KEY_A, XKeycode.KEY_W, XKeycode.KEY_S, XKeycode.KEY_D};
        private final int[] arrowDirections = {180, 270, 90, 0};
        private float scale = 1, offset;
        private boolean keysVisible;
        private RectF pressedBox;

        DeskPanel(Context context, String waiting) {
            super(context);
            this.waiting = waiting;
            setClickable(true);
            setContentDescription("Security desk");
            ThorAssets.prepare(context, this::invalidate);
        }

        void releaseKeys() {
            for (XKeycode key : heldKeys) xServer.injectKeyRelease(key);
            heldKeys.clear();
            pointerKeys.clear();
            invalidate();
        }

        @Override
        protected void onDetachedFromWindow() {
            releaseKeys();
            super.onDetachedFromWindow();
        }

        void update() {
            boolean playing = arcade && (arcadeShortcut != null ? arcadeShortcut.playing() : enteredPracticeNight && !inOffice);
            if (keysVisible && !playing) releaseKeys();
            keysVisible = playing;
            invalidate();
        }

        private String key(ThorController.Action action) {
            return ThorController.keyLabel(getContext(), action);
        }

        private void fill(Canvas c, RectF r, int color) { ThorStyle.fill(c, paint, r.left, r.top, r.right, r.bottom, color); }
        private void text(Canvas c, String v, float x, float y, float size, int color) { ThorStyle.text(c, paint, v, x, y, size, color); }

        private void keycap(Canvas c, float l, float t, String label, boolean on) {
            float w = Math.max(48, ThorStyle.width(paint, label, 18) + 24);
            if (label.equals("◀▶") || label.equals("◀ ▶")) {
                ThorStyle.outline(c, paint, l, t, l + 64, t + 32, 2, on ? LINE_STRONG : LINE);
                triangle(c, l + 22, t + 16, 7, 180, on ? PRIMARY : MUTED);
                triangle(c, l + 42, t + 16, 7, 0, on ? PRIMARY : MUTED);
                return;
            }
            ThorStyle.keycap(c, paint, l, t, l + w, t + 32, label, on ? LINE_STRONG : LINE, on ? PRIMARY : MUTED, 1);
        }

        private void triangle(Canvas c, float x, float y, float size, int degrees, int color) {
            android.graphics.Path path = new android.graphics.Path();
            path.moveTo(size, 0);
            path.lineTo(-size * .7f, -size);
            path.lineTo(-size * .7f, size);
            path.close();
            int save = c.save();
            c.translate(x, y);
            c.rotate(degrees);
            paint.setShader(null);
            paint.setStyle(android.graphics.Paint.Style.FILL);
            paint.setColor(color);
            c.drawPath(path, paint);
            c.restoreToCount(save);
        }

        private void image(Canvas c, int handle, float l, float t, float height, int alpha) {
            android.graphics.Bitmap bitmap = ThorAssets.get(handle);
            if (bitmap == null) return;
            float w = bitmap.getWidth() * height / bitmap.getHeight();
            art.setAlpha(alpha);
            c.drawBitmap(bitmap, null, new RectF(l, t, l + w, t + height), art);
            art.setAlpha(255);
        }

        @Override
        protected void onDraw(Canvas c) {
            scale = getHeight() / 1080f;
            float w = Math.min(1240, getWidth() / scale);
            offset = (getWidth() - w * scale) / 2;
            c.drawColor(BASE);
            int save = c.save();
            c.translate(offset, 0);
            c.scale(scale, scale);
            boolean ready = inOffice && opening == Opening.NONE && !(arcade && arcadeShortcut != null);
            int night = arcade ? arcadeStage.night : progress.savedNight();

            text(c, arcade ? "Arcade practice" : "Security desk", 32, 52, 22, SECONDARY);
            text(c, night > 0 ? "Night " + night : "Night", 32, 92, 40, PRIMARY);
            String state = opening == Opening.MONITOR ? "Opening cameras" : opening == Opening.MAINTENANCE
                    ? "Opening maintenance" : keysVisible ? arcadeStage.title : inOffice ? "Monitor down" : "Standing by";
            ThorStyle.textRight(c, paint, state, w - 32, 92, 24, opening != Opening.NONE ? PRIMARY : SECONDARY);
            paint.setColor(inOffice ? ACCENT : 0xff3a3a3a);
            c.drawCircle(w - 52 - ThorStyle.width(paint, state, 24), 84, 6, paint);

            if (keysVisible) {
                float cell = (w - 64 - 3 * 16) / 4;
                for (int i = 0; i < 4; ++i) {
                    RectF box = arrowBoxes[i];
                    box.set(32 + i * (cell + 16), 120, 32 + i * (cell + 16) + cell, 760);
                    boolean down = heldKeys.contains(arrowKeys[i]);
                    fill(c, box, down ? RAISED_ON : RAISED);
                    ThorStyle.outline(c, paint, box.left, box.top, box.right, box.bottom, 2, down ? PRIMARY : LINE);
                    triangle(c, box.centerX(), box.centerY(), Math.min(64, cell * .25f), arrowDirections[i], down ? PRIMARY : SECONDARY);
                }
                cameraBox.setEmpty();
                serviceBox.setEmpty();
            } else {
                for (RectF box : arrowBoxes) box.setEmpty();
                // The moment: the original camera map is the Cameras control.
                cameraBox.set(32, 120, 760, 760);
                boolean camHot = opening == Opening.MONITOR || pressedBox == cameraBox;
                fill(c, cameraBox, camHot ? RAISED_ON : RAISED);
                ThorStyle.outline(c, paint, cameraBox.left, cameraBox.top, cameraBox.right, cameraBox.bottom, 2,
                        camHot ? PRIMARY : LINE);
                android.graphics.Bitmap map = ThorAssets.get(ThorAssets.MAP);
                if (map != null) {
                    float h = 524, mw = map.getWidth() * h / map.getHeight();
                    art.setAlpha(ready || camHot ? 255 : 90);
                    c.drawBitmap(map, null, new RectF(cameraBox.centerX() - mw / 2, 144, cameraBox.centerX() + mw / 2, 144 + h), art);
                    art.setAlpha(255);
                }
                keycap(c, 56, 696, key(ThorController.Action.MONITOR), ready || camHot);
                text(c, "Cameras", 120, 722, 28, ready || camHot ? PRIMARY : MUTED);
                ThorStyle.textRight(c, paint, camHot ? "Opening" : ready ? "Tap the map" : "Opens in the office",
                        cameraBox.right - 24, 720, 20, MUTED);

                serviceBox.set(784, 120, w - 32, 440);
                boolean serviceHot = opening == Opening.MAINTENANCE || pressedBox == serviceBox;
                fill(c, serviceBox, serviceHot ? RAISED_ON : RAISED);
                ThorStyle.outline(c, paint, serviceBox.left, serviceBox.top, serviceBox.right, serviceBox.bottom, 2,
                        serviceHot ? PRIMARY : LINE);
                keycap(c, 808, 144, key(ThorController.Action.MAINTENANCE), ready || serviceHot);
                text(c, "Maintenance", 872, 170, 28, ready || serviceHot ? PRIMARY : MUTED);
                int alpha = ready || serviceHot ? 255 : 90;
                if (ThorAssets.isReady()) {
                    image(c, ThorAssets.AUDIO_DEVICES, 808, 212, 36, alpha);
                    image(c, ThorAssets.CAMERA_SYSTEM, 808, 264, 36, alpha);
                    image(c, ThorAssets.VENTILATION, 808, 316, 36, alpha);
                } else {
                    text(c, "audio devices", 808, 240, 26, ACCENT);
                    text(c, "camera system", 808, 292, 26, ACCENT);
                    text(c, "ventilation", 808, 344, 26, ACCENT);
                }
                text(c, "▲▼ selects, " + key(ThorController.Action.POINTER) + " reboots", 808, 412, 20, MUTED);
            }

            RectF controls = new RectF(784, 464, w - 32, 760);
            if (!keysVisible) {
                fill(c, controls, RAISED);
                ThorStyle.outline(c, paint, controls.left, controls.top, controls.right, controls.bottom, 2, LINE);
            }
            if (arcade && (arcadeShortcut != null || !keysVisible)) {
                String guide = arcadeShortcut != null ? arcadeShortcut.prompt() : !enteredPracticeNight ? waiting : arcadeStage.instructions;
                android.text.TextPaint body = new android.text.TextPaint(paint);
                body.setTextSize(22);
                body.setColor(SECONDARY);
                float left = keysVisible ? 32 : 808, top = keysVisible ? 800 : 488;
                android.text.StaticLayout layout = android.text.StaticLayout.Builder.obtain(guide, 0, guide.length(), body,
                        Math.round(keysVisible ? w - 64 : controls.width() - 48)).setMaxLines(8).setLineSpacing(8, 1f).build();
                int g = c.save();
                c.translate(left, top);
                layout.draw(c);
                c.restoreToCount(g);
            } else if (!keysVisible) {
                text(c, "In cameras", 808, 504, 20, SECONDARY);
                boolean on = inOffice;
                keycap(c, 808, 528, key(ThorController.Action.VENT_MAP), on);
                if (ThorAssets.isReady()) image(c, ThorAssets.MAP_TOGGLE, 880, 524, 40, on ? 255 : 110);
                else text(c, "Map toggle", 880, 552, 24, on ? PRIMARY : MUTED);
                keycap(c, 808, 588, key(ThorController.Action.AUDIO), on);
                if (ThorAssets.isReady()) image(c, ThorAssets.PLAY_AUDIO, 880, 584, 40, on ? 255 : 110);
                else text(c, "Play audio", 880, 612, 24, on ? PRIMARY : MUTED);
                keycap(c, 808, 648, key(ThorController.Action.PREVIOUS_CAMERA) + key(ThorController.Action.NEXT_CAMERA), on);
                text(c, "Camera", 888, 672, 24, on ? PRIMARY : MUTED);
                keycap(c, 808, 704, key(ThorController.Action.SNAPSHOT), on);
                text(c, "Snapshot", 880, 728, 24, on ? PRIMARY : MUTED);
            }

            // During an arcade minigame the guide line sits here instead of the office's mute control.
            if (keysVisible) muteBox.setEmpty(); else muteBox.set(32, 792, 400, 864);
            android.graphics.Bitmap mute = keysVisible ? null : ThorAssets.get(ThorAssets.MUTE_CALL);
            boolean muteHot = pressedBox == muteBox;
            if (mute != null) {
                art.setAlpha(inOffice ? 255 : 110);
                c.drawBitmap(mute, null, new RectF(32, 796, 32 + 288, 862), art);
                art.setAlpha(255);
                if (muteHot) ThorStyle.outline(c, paint, 30, 794, 322, 864, 2, PRIMARY);
            } else if (!keysVisible) {
                fill(c, new RectF(32, 796, 320, 862), muteHot ? RAISED_ON : RAISED);
                text(c, "Mute call", 56, 840, 26, PRIMARY);
            }
            if (!keysVisible) keycap(c, 336, 812, key(ThorController.Action.MUTE_CALL), inOffice);

            pauseBox.set(784, 796, w - 32, 862);
            fill(c, pauseBox, pressedBox == pauseBox ? RAISED_ON : RAISED);
            ThorStyle.outline(c, paint, pauseBox.left, pauseBox.top, pauseBox.right, pauseBox.bottom, 2, LINE);
            keycap(c, 808, 812, "START", true);
            text(c, "Pause", 904, 838, 26, PRIMARY);

            ThorStyle.fill(c, paint, 32, 896, w - 32, 898, LINE);
            String record = arcade ? "Practice save, the campaign is untouched"
                    : (night > 0 ? "Saved night " + night + "    " : "") + String.format(java.util.Locale.US, "%,d pts", progress.wallet())
                    + "    " + progress.earnedCount() + " of " + progress.badgeCount() + " achievements";
            text(c, record, 32, 944, 22, SECONDARY);
            text(c, inOffice ? "Tap a panel or press its button" : waiting, 32, 988, 20, MUTED);
            c.restoreToCount(save);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int index = event.getActionIndex();
            float x = (event.getX(index) - offset) / scale, y = event.getY(index) / scale;
            int id = event.getPointerId(index);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (keysVisible) {
                        for (int i = 0; i < 4; ++i) {
                            if (!arrowBoxes[i].contains(x, y) || !activity.hasWindowFocus()) continue;
                            if (heldKeys.add(arrowKeys[i])) xServer.injectKeyPress(arrowKeys[i]);
                            pointerKeys.put(id, arrowKeys[i]);
                            invalidate();
                        }
                    }
                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        pressedBox = null;
                        for (RectF box : new RectF[]{cameraBox, serviceBox, muteBox, pauseBox})
                            if (box.contains(x, y)) pressedBox = box;
                        invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_POINTER_UP:
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    XKeycode held = pointerKeys.get(id);
                    if (held != null) {
                        pointerKeys.remove(id);
                        if (pointerKeys.indexOfValue(held) < 0 && heldKeys.remove(held)) xServer.injectKeyRelease(held);
                    }
                    if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                        releaseKeys();
                        pressedBox = null;
                    } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                        RectF box = pressedBox;
                        pressedBox = null;
                        if (box != null && box.contains(x, y)) {
                            performClick();
                            if (box == cameraBox) requestPanel(Opening.MONITOR);
                            else if (box == serviceBox) requestPanel(Opening.MAINTENANCE);
                            else if (box == muteBox) control(ThorController.Action.MUTE_CALL);
                            else if (box == pauseBox) activity.onBackPressed();
                        }
                    }
                    invalidate();
                    return true;
                }
                default:
                    return true;
            }
        }

        @Override
        public boolean performClick() {
            return super.performClick();
        }
    }

    private final class ScreenView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF area = new RectF();
        private final RectF header = new RectF();
        private final RectF meshArea = new RectF();
        private static final int MESH_COLUMNS = 24;
        private final float[] officeMesh = new float[(MESH_COLUMNS + 1) * 4];
        private final boolean monitor;
        private int touchId = -1;

        ScreenView(Context context, boolean monitor) {
            super(context);
            this.monitor = monitor;
            setClickable(monitor || swapped);
            paint.setFilterBitmap(smoothPicture);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawColor(Color.BLACK);
            if (!monitor) {
                if (fitOffice) setFittedArea(FULL_SCENE);
                else area.set(0, 0, getWidth(), getHeight());
                if (!panelVisible()) {
                    canvas.drawBitmap(scene.bitmap, FULL_SCENE, area, paint);
                    return;
                }
                if (perspective) drawCurvedOffice(canvas);
                else canvas.drawBitmap(office.bitmap, OFFICE_CROP, area, paint);
                header.set(area.left, area.top, area.right,
                        area.top + area.height() * SCENE_HEADER.height() / scene.height);
                canvas.drawBitmap(scene.bitmap, SCENE_HEADER, header, paint);
            } else if (panelVisible()) {
                Rect crop = cameraVisible ? CAMERA_CROP : MAINTENANCE_CROP;
                setFittedArea(crop);
                canvas.drawBitmap(scene.bitmap, crop, area, paint);
                drawHints(canvas);
            }
        }

        private final android.graphics.Paint hintPaint = ThorStyle.textPaint();

        private void drawHints(Canvas canvas) {
            String[][] hints = monitorHints;
            if (hints.length == 0) return;
            float u = Math.min(getWidth() / 1240f, getHeight() / 1080f);
            if (getWidth() > getHeight() * 1.4f) u = getHeight() / 1080f;
            float bottom = getHeight() - area.bottom;
            float side = getWidth() - area.right;
            if (bottom >= 30 * u) {
                float cap = Math.min(34 * u, bottom - 12 * u);
                float baseline = area.bottom + bottom / 2;
                float x = 40 * u;
                for (String[] hint : hints) {
                    float w = ThorStyle.keycapWidth(hintPaint, hint[0], cap, u);
                    if (x + w > getWidth()) break;
                    ThorStyle.keycap(canvas, hintPaint, x, baseline - cap / 2, x + w, baseline + cap / 2, hint[0],
                            ThorStyle.OLIVE_LIGHT, ThorStyle.TEXT, u);
                    x += w + 12 * u;
                    hintPaint.setTextSize(20 * u);
                    ThorStyle.text(canvas, hintPaint, hint[1], x, baseline + 7 * u, 20 * u, ThorStyle.MUTED);
                    x += hintPaint.measureText(hint[1]) + 40 * u;
                }
            } else if (side >= 170 * u) {
                float cap = 40 * u;
                float x = area.right + 28 * u;
                float y = area.top + 60 * u;
                ThorStyle.text(canvas, hintPaint, cameraVisible ? "CAMERAS" : "MAINTENANCE", x, y, 22 * u, ThorStyle.MUTED);
                y += 34 * u;
                for (String[] hint : hints) {
                    float w = ThorStyle.keycapWidth(hintPaint, hint[0], cap, u);
                    ThorStyle.keycap(canvas, hintPaint, x, y, x + w, y + cap, hint[0], ThorStyle.OLIVE_LIGHT, ThorStyle.TEXT, u);
                    hintPaint.setTextSize(20 * u);
                    String label = android.text.TextUtils.ellipsize(hint[1], new android.text.TextPaint(hintPaint),
                            side - 56 * u, android.text.TextUtils.TruncateAt.END).toString();
                    ThorStyle.text(canvas, hintPaint, label, x, y + cap + 28 * u, 20 * u, ThorStyle.MUTED);
                    y += cap + 62 * u;
                }
            }
        }

        private void drawCurvedOffice(Canvas canvas) {
            if (!meshArea.equals(area)) {
                meshArea.set(area);
                int next = 0;
                for (int row = 0; row <= 1; ++row) {
                    float sourceY = row * office.height;
                    float relativeY = (sourceY - OFFICE_CROP.top) / OFFICE_CROP.height() - .5f;
                    for (int col = 0; col <= MESH_COLUMNS; ++col) {
                        float sourceX = col * office.width / (float) MESH_COLUMNS;
                        float relativeX = (sourceX - OFFICE_CROP.left) / OFFICE_CROP.width() - .5f;
                        float magnification = 1f + .1f * (1f - 4f * relativeX * relativeX);
                        officeMesh[next++] = area.centerX() + relativeX * area.width();
                        officeMesh[next++] = area.centerY() + relativeY * area.height() * magnification;
                    }
                }
            }
            int layer = canvas.save();
            canvas.clipRect(area);
            canvas.drawBitmapMesh(office.bitmap, MESH_COLUMNS, 1, officeMesh, 0, null, 0, paint);
            canvas.restoreToCount(layer);
        }

        private void setFittedArea(Rect crop) {
            float scale = Math.min(getWidth() / (float) crop.width(), getHeight() / (float) crop.height());
            float width = crop.width() * scale;
            float height = crop.height() * scale;
            float left = (getWidth() - width) / 2;
            float top = (getHeight() - height) / 2;
            area.set(left, top, left + width, top + height);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            boolean allowOfficeTouch = !monitor && swapped && inOffice && !panelVisible();
            if (!activity.hasWindowFocus() || !(monitor && panelVisible()) && !allowOfficeTouch) {
                touchId = -1;
                return false;
            }
            Rect source = monitor ? cameraVisible ? CAMERA_CROP : MAINTENANCE_CROP : FULL_SCENE;
            int index = event.getActionIndex();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    touchId = area.contains(event.getX(), event.getY()) ? event.getPointerId(0) : -1;
                    return touchId >= 0;
                case MotionEvent.ACTION_CANCEL:
                    touchId = -1;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int tracked = event.findPointerIndex(touchId);
                    if (tracked < 0 || !area.contains(event.getX(tracked), event.getY(tracked))) {
                        touchId = -1;
                    } else if (allowOfficeTouch && canPanOffice()) {
                        moveGuestPointer(mapX(event.getX(tracked), source), mapY(event.getY(tracked), source));
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP:
                    if (touchId != event.getPointerId(index)) return true;
                    touchId = -1;
                    if (area.contains(event.getX(index), event.getY(index))) {
                        performClick();
                        clickGuest(mapX(event.getX(index), source), mapY(event.getY(index), source), monitor);
                    }
                    return true;
                default:
                    return true;
            }
        }

        private float mapX(float x, Rect source) {
            return source.left + (x - area.left) * source.width() / area.width();
        }

        private float mapY(float y, Rect source) {
            return source.top + (y - area.top) * source.height() / area.height();
        }

        void releaseTouch() {
            touchId = -1;
        }

        @Override
        public boolean performClick() {
            return super.performClick();
        }
    }

    private final class DeskDecoration extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        DeskDecoration(Context context) {
            super(context);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            ThorProgress.Decoration item = progress.displayItem();
            if (item == ThorProgress.Decoration.NONE) return;
            int checkpoint = canvas.save();
            canvas.scale(getWidth() / 100f, getHeight() / 82f);
            paint.setColor(0x77000000);
            canvas.drawOval(13, 69, 89, 80, paint);
            paint.setColor(item.color);
            if (item == ThorProgress.Decoration.MOTH) {
                canvas.drawOval(12, 26, 55, 64, paint);
                canvas.drawOval(46, 26, 89, 64, paint);
                paint.setColor(0xff626451);
                canvas.drawOval(42, 18, 59, 73, paint);
                paint.setColor(0xffe3cd9b);
                canvas.drawCircle(27, 43, 4, paint);
                canvas.drawCircle(73, 43, 4, paint);
            } else if (item == ThorProgress.Decoration.LANTERN) {
                canvas.drawRoundRect(24, 20, 76, 72, 8, 8, paint);
                paint.setColor(0xff483d26);
                canvas.drawRect(20, 17, 80, 25, paint);
                canvas.drawRect(20, 65, 80, 73, paint);
                paint.setColor(0xffffe296);
                canvas.drawRoundRect(32, 30, 68, 62, 6, 6, paint);
            } else {
                canvas.drawOval(30, 5, 43, 38, paint);
                canvas.drawOval(56, 5, 69, 38, paint);
                canvas.drawOval(24, 28, 76, 74, paint);
                paint.setColor(0xff343f47);
                canvas.drawCircle(42, 48, 3, paint);
                canvas.drawCircle(58, 48, 3, paint);
                paint.setColor(0xffd9cc9f);
                canvas.drawCircle(50, 58, 5, paint);
            }
            canvas.restoreToCount(checkpoint);
        }
    }

    private static final class FrameSource {
        private static final long MAPPED_BYTES = 64L + 1048L * 790L * 4L;

        private final File file;
        private final int width, height;
        private final int[][] buffers;
        private int[] pixels;
        private int[] staging;
        private int[] rendered;
        private final int[] metadata = new int[4];
        private ByteBuffer mapped;
        private final Bitmap bitmap;

        FrameSource(File file, int width, int height) {
            this.file = file;
            this.width = width;
            this.height = height;
            buffers = new int[3][width * height];
            rendered = pixels = buffers[0];
            staging = buffers[1];
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        }

        boolean read() {
            if (mapped == null) {
                if (file.length() != MAPPED_BYTES) return false;
                try (RandomAccessFile stream = new RandomAccessFile(file, "r")) {
                    if (stream.length() != MAPPED_BYTES) return false;
                    mapped = stream.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, MAPPED_BYTES);
                } catch (IOException ignored) {
                    return false;
                }
            }
            if (!copyFrame(mapped, staging, metadata, metadata[0], width, height)) return false;
            pixels = staging;
            for (int[] buffer : buffers) {
                if (buffer != pixels && buffer != rendered) {
                    staging = buffer;
                    break;
                }
            }
            return true;
        }

        void upload() {
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
            rendered = pixels;
        }

        int renderedPixel(int x, int y) {
            return rendered[y * width + x];
        }
    }
}