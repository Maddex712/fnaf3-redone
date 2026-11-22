package com.winlator;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.preference.PreferenceManager;

import com.winlator.xserver.Pointer;
import com.winlator.xserver.XKeycode;
import com.winlator.xserver.XServer;

final class ThorController implements InputManager.InputDeviceListener {
    enum Action {
        POINTER("Pointer click", KeyEvent.KEYCODE_BUTTON_A),
        MONITOR("Raise or lower monitor", KeyEvent.KEYCODE_BUTTON_X),
        VENT_MAP("Toggle vent map", KeyEvent.KEYCODE_BUTTON_L1),
        AUDIO("Play audio", KeyEvent.KEYCODE_BUTTON_R1),
        PREVIOUS_CAMERA("Previous camera", KeyEvent.KEYCODE_DPAD_LEFT),
        NEXT_CAMERA("Next camera", KeyEvent.KEYCODE_DPAD_RIGHT),
        MUTE_CALL("Mute call", KeyEvent.KEYCODE_BUTTON_Y),
        SNAPSHOT("Save camera snapshot", KeyEvent.KEYCODE_BUTTON_R2),
        MAINTENANCE("Open or close maintenance", KeyEvent.KEYCODE_BUTTON_L2);

        final String label;
        final int initialKey;

        Action(String label, int initialKey) {
            this.label = label;
            this.initialKey = initialKey;
        }

        String preference() {
            return "thor_control_" + name();
        }
    }

    private static final int[] KEYS = {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, -1
    };
    private static final String[] KEY_LABELS = {
            "A", "B", "X", "Y", "L1", "R1", "L2", "R2", "D-pad left", "D-pad right", "D-pad up", "D-pad down", "Unassigned"
    };

    private static final int KEY_SOURCE = 1;
    private static final int AXIS_SOURCE = 2;
    private final Activity activity;
    private final InputManager inputManager;
    private boolean listening;
    private int controllerDevice = -1;
    private final XServer xServer;
    private final ThorDualScreen desk;
    private final SharedPreferences preferences;
    private final SparseIntArray pressed = new SparseIntArray();
    private final SparseArray<XKeycode> guestKeys = new SparseArray<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable pointerTick = this::movePointer;
    private boolean pointerPressed;
    private boolean officePan;
    private boolean ticking;
    private float horizontal;
    private float vertical;

    ThorController(Activity activity, XServer xServer, ThorDualScreen desk) {
        this.activity = activity;
        inputManager = (InputManager) activity.getSystemService(Context.INPUT_SERVICE);
        this.xServer = xServer;
        this.desk = desk;
        preferences = PreferenceManager.getDefaultSharedPreferences(activity);
    }

    void resume() {
        if (!listening && inputManager != null) {
            inputManager.registerInputDeviceListener(this, handler);
            listening = true;
        }
    }

    void pause() {
        if (listening) {
            inputManager.unregisterInputDeviceListener(this);
            listening = false;
        }
        reset();
    }

    @Override public void onInputDeviceAdded(int deviceId) {}
    @Override public void onInputDeviceChanged(int deviceId) {}
    @Override public void onInputDeviceRemoved(int deviceId) {
        if (controllerDevice == deviceId) reset();
    }

    private void chooseController(int deviceId) {
        if (controllerDevice != -1 && controllerDevice != deviceId) reset();
        controllerDevice = deviceId;
    }

    boolean key(KeyEvent event) {
        InputDevice device = event.getDevice();
        int key = event.getKeyCode();
        if (desk != null && key != KeyEvent.KEYCODE_BUTTON_START && key != KeyEvent.KEYCODE_BACK
                && event.getAction() != KeyEvent.ACTION_MULTIPLE) {
            boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
            int menuKey = key == KeyEvent.KEYCODE_ESCAPE ? KeyEvent.KEYCODE_BUTTON_B : key;
            if (desk.menuKey(menuKey, down)) {
                if (!down) pressed.delete(key);
                return true;
            }
        }
        boolean mappedAsController = KeyEvent.isGamepadButton(key)
                || key >= KeyEvent.KEYCODE_DPAD_UP && key <= KeyEvent.KEYCODE_DPAD_RIGHT;
        boolean controller = event.isFromSource(InputDevice.SOURCE_GAMEPAD)
                || event.isFromSource(InputDevice.SOURCE_JOYSTICK)
                || mappedAsController && device != null && device.supportsSource(InputDevice.SOURCE_GAMEPAD);
        if (!controller || !mappedAsController) return keyboardShortcut(event);
        chooseController(event.getDeviceId());
        if (event.getAction() == KeyEvent.ACTION_UP) return release(key, KEY_SOURCE);
        if (event.getAction() == KeyEvent.ACTION_DOWN) return press(key, KEY_SOURCE);
        return false;
    }

    private boolean keyboardShortcut(KeyEvent event) {
        int key = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_UP) return release(key, KEY_SOURCE);
        if (event.getAction() != KeyEvent.ACTION_DOWN || !event.isFromSource(InputDevice.SOURCE_KEYBOARD)
                || desk == null || !desk.isNightActive() || !event.hasNoModifiers()) return false;
        if (pressed.get(key) != 0) return true;
        Action action;
        switch (key) {
            case KeyEvent.KEYCODE_TAB: action = Action.MONITOR; break;
            case KeyEvent.KEYCODE_M: action = Action.MAINTENANCE; break;
            case KeyEvent.KEYCODE_V: action = Action.VENT_MAP; break;
            case KeyEvent.KEYCODE_E: action = Action.AUDIO; break;
            case KeyEvent.KEYCODE_PAGE_UP: action = Action.PREVIOUS_CAMERA; break;
            case KeyEvent.KEYCODE_PAGE_DOWN: action = Action.NEXT_CAMERA; break;
            case KeyEvent.KEYCODE_F12: action = Action.SNAPSHOT; break;
            default: return false;
        }
        if (!desk.control(action)) return false;
        pressed.put(key, KEY_SOURCE);
        return true;
    }

    private boolean press(int key, int source) {
        int sources = pressed.get(key);
        if (sources != 0) {
            pressed.put(key, sources | source);
            return true;
        }
        if (key == KeyEvent.KEYCODE_BUTTON_START) {
            activity.onBackPressed();
        } else if (officePan(key, true) || nightVertical(key)) {
            // handled by the office view or the open panel
        } else if (!runAction(key)) {
            XKeycode guest = gameKey(key);
            if (guest == null) return false;
            xServer.injectKeyPress(guest);
            guestKeys.put(key, guest);
        }
        pressed.put(key, source);
        return true;
    }

    private boolean release(int key, int source) {
        int sources = pressed.get(key);
        if ((sources & source) == 0) return false;
        sources &= ~source;
        if (sources != 0) {
            pressed.put(key, sources);
            return true;
        }
        pressed.delete(key);
        officePan(key, false);
        XKeycode guest = guestKeys.get(key);
        if (guest != null) {
            xServer.injectKeyRelease(guest);
            guestKeys.remove(key);
        }
        if (pointerPressed && key == assigned(preferences, Action.POINTER)) releasePointer();
        return true;
    }

    private boolean runAction(int key) {
        for (Action action : Action.values()) {
            if (assigned(preferences, action) != key) continue;
            if (action == Action.POINTER) {
                if (xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_LEFT)) return false;
                xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT);
                pointerPressed = true;
                return true;
            }
            return desk != null && desk.control(action);
        }
        return false;
    }

    private int officePanKey;

    /** D-pad left/right looks around the office while no panel is open. */
    private boolean officePan(int key, boolean down) {
        if (key != KeyEvent.KEYCODE_DPAD_LEFT && key != KeyEvent.KEYCODE_DPAD_RIGHT || desk == null) return false;
        if (!down) {
            if (officePanKey == key) {
                officePanKey = 0;
                desk.panOffice(0f);
            }
            return false;
        }
        if (!desk.canPanOffice()) return false;
        officePanKey = key;
        desk.panOffice(key == KeyEvent.KEYCODE_DPAD_LEFT ? -.37f : .37f);
        return true;
    }

    /** D-pad up/down selects maintenance systems or steps cameras during a night. */
    private boolean nightVertical(int key) {
        if (desk == null || key != KeyEvent.KEYCODE_DPAD_UP && key != KeyEvent.KEYCODE_DPAD_DOWN) return false;
        return desk.navigateVertical(key == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
    }

    private int menuDirection;
    private final Runnable menuRepeat = new Runnable() {
        @Override
        public void run() {
            if (menuDirection == 0 || desk == null || !desk.menuKey(menuDirection, true)) {
                menuDirection = 0;
                return;
            }
            handler.postDelayed(this, 150);
        }
    };

    /** Stick and hat steer the title menus like a D-pad, with key repeat. */
    private boolean menuMotion(MotionEvent event) {
        if (desk == null || !desk.titleShown()) {
            if (menuDirection != 0) {
                menuDirection = 0;
                handler.removeCallbacks(menuRepeat);
            }
            return false;
        }
        float x = event.getAxisValue(MotionEvent.AXIS_X), y = event.getAxisValue(MotionEvent.AXIS_Y);
        float hx = event.getAxisValue(MotionEvent.AXIS_HAT_X), hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
        int direction = hy < -.5f || y < -.6f ? KeyEvent.KEYCODE_DPAD_UP : hy > .5f || y > .6f ? KeyEvent.KEYCODE_DPAD_DOWN
                : hx < -.5f || x < -.6f ? KeyEvent.KEYCODE_DPAD_LEFT : hx > .5f || x > .6f ? KeyEvent.KEYCODE_DPAD_RIGHT : 0;
        if (direction != menuDirection) {
            handler.removeCallbacks(menuRepeat);
            menuDirection = direction;
            if (direction != 0) {
                desk.menuKey(direction, true);
                handler.postDelayed(menuRepeat, 420);
            }
        }
        return true;
    }

    private XKeycode gameKey(int key) {
        if (desk != null && desk.isNightActive()) return null;
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_LEFT: return XKeycode.KEY_A;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return XKeycode.KEY_D;
            case KeyEvent.KEYCODE_DPAD_UP: return XKeycode.KEY_W;
            case KeyEvent.KEYCODE_DPAD_DOWN: return XKeycode.KEY_S;
            case KeyEvent.KEYCODE_BUTTON_B: return XKeycode.KEY_SPACE;
            default: return null;
        }
    }

    boolean motion(MotionEvent event) {
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.getAction() != MotionEvent.ACTION_MOVE)
            return false;
        if (!activity.hasWindowFocus()) return true;
        chooseController(event.getDeviceId());
        if (menuMotion(event)) return true;

        float hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X);
        float hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
        axisKey(KeyEvent.KEYCODE_DPAD_LEFT, hatX < -.5f);
        axisKey(KeyEvent.KEYCODE_DPAD_RIGHT, hatX > .5f);
        axisKey(KeyEvent.KEYCODE_DPAD_UP, hatY < -.5f);
        axisKey(KeyEvent.KEYCODE_DPAD_DOWN, hatY > .5f);
        float left = Math.max(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE));
        float right = Math.max(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS));
        axisKey(KeyEvent.KEYCODE_BUTTON_L2, left > .55f);
        axisKey(KeyEvent.KEYCODE_BUTTON_R2, right > .55f);

        horizontal = axis(event, MotionEvent.AXIS_X);
        vertical = axis(event, MotionEvent.AXIS_Y);
        if (desk != null && desk.canPanOffice()) {
            if (horizontal != 0f || officePan) desk.panOffice(horizontal * sensitivity());
            officePan = horizontal != 0f;
            stopPointerTicks();
        } else {
            officePan = false;
            if ((horizontal != 0f || vertical != 0f) && !ticking) {
                ticking = true;
                handler.post(pointerTick);
            }
        }
        return true;
    }

    private void axisKey(int key, boolean down) {
        if (down) {
            if ((pressed.get(key) & AXIS_SOURCE) == 0) press(key, AXIS_SOURCE);
        } else {
            release(key, AXIS_SOURCE);
        }
    }

    private static float axis(MotionEvent event, int axis) {
        float value = event.getAxisValue(axis);
        return Math.abs(value) <= .20f ? 0f : value;
    }

    private float sensitivity() {
        return preferences.getInt("thor_controller_sensitivity", 100) / 100f;
    }

    private void movePointer() {
        if (!activity.hasWindowFocus() || horizontal == 0f && vertical == 0f
                || desk != null && desk.canPanOffice()) {
            ticking = false;
            return;
        }
        float speed = 18f * sensitivity();
        xServer.injectPointerMoveDelta(Math.round(horizontal * speed), Math.round(vertical * speed));
        handler.postDelayed(pointerTick, 16);
    }

    private void stopPointerTicks() {
        handler.removeCallbacks(pointerTick);
        ticking = false;
    }

    void reset() {
        controllerDevice = -1;
        officePanKey = 0;
        menuDirection = 0;
        handler.removeCallbacks(menuRepeat);
        pressed.clear();
        for (int i = 0; i < guestKeys.size(); ++i) xServer.injectKeyRelease(guestKeys.valueAt(i));
        guestKeys.clear();
        officePan = false;
        horizontal = vertical = 0f;
        stopPointerTicks();
        releasePointer();
    }

    private void releasePointer() {
        if (!pointerPressed) return;
        pointerPressed = false;
        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
    }

    private static final String[] SHORT_LABELS = {
            "A", "B", "X", "Y", "L1", "R1", "L2", "R2", "◀", "▶", "▲", "▼", "—"
    };

    /** Short keycap label for the current binding, read from the saved controller mappings. */
    static String keyLabel(Context context, Action action) {
        return SHORT_LABELS[indexOf(assigned(PreferenceManager.getDefaultSharedPreferences(context), action))];
    }

    static void showSettings(Activity activity) {
        showSettings(activity, 0);
    }

    private static void showSettings(Activity activity, int focus) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
        Action[] actions = Action.values();
        java.util.List<ThorSheet.Row> rows = new java.util.ArrayList<>();
        for (Action action : actions) {
            int key = assigned(preferences, action);
            rows.add(new ThorSheet.Row(action.label).value(name(key))
                    .tone(key < 0 ? ThorSheet.Tone.DISABLED : ThorSheet.Tone.NORMAL));
        }
        rows.add(new ThorSheet.Row("Look sensitivity")
                .value(preferences.getInt("thor_controller_sensitivity", 100) + "%"));
        rows.add(new ThorSheet.Row("Input test").value(">"));
        rows.add(new ThorSheet.Row("Keyboard shortcuts").value(">"));
        new ThorSheet(activity).setTitle("Controller mappings").setMeta("START  Return menu")
                .setInitialFocus(focus)
                .setRows(rows, (dialog, index) -> {
                    if (index == actions.length) chooseSensitivity(activity, preferences);
                    else if (index == actions.length + 1) showInputTest(activity);
                    else if (index == actions.length + 2) showKeyboardShortcuts(activity);
                    else chooseButton(activity, preferences, actions[index]);
                })
                .setPositiveButton("Done", null)
                .show();
    }

    private static void showKeyboardShortcuts(Activity activity) {
        String[][] keys = {{"Tab", "Camera monitor"}, {"M", "Maintenance"}, {"V", "Vent map"}, {"E", "Audio lure"},
                {"Page Up / Down", "Select camera"}, {"F12", "Camera snapshot"}};
        java.util.List<ThorSheet.Row> rows = new java.util.ArrayList<>();
        for (String[] key : keys) rows.add(new ThorSheet.Row(key[1]).value(key[0]).inert());
        new ThorSheet(activity).setTitle("Keyboard shortcuts").setRows(rows, null)
                .setNegativeButton("Back", (dialog, which) -> showSettings(activity, Action.values().length + 2))
                .show();
    }

    private static void showInputTest(Activity activity) {
        TextView readout = new TextView(activity);
        int padding = Math.round(22 * activity.getResources().getDisplayMetrics().density);
        readout.setPadding(padding, padding / 2, padding, padding);
        readout.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                Math.max(18f, Math.min(activity.getResources().getDisplayMetrics().heightPixels,
                        activity.getResources().getDisplayMetrics().widthPixels) / 1080f * 34f));
        readout.setText("Press a controller button or move either stick.\nB returns here; use Back on screen to leave.");
        android.app.Dialog dialog = new ThorSheet(activity).setTitle("Input test")
                .setView(readout)
                .setNegativeButton("Back", (window, which) -> showSettings(activity, Action.values().length + 1))
                .setOnKeyListener((window, key, event) -> {
                    if (!event.isFromSource(InputDevice.SOURCE_GAMEPAD)
                            && !event.isFromSource(InputDevice.SOURCE_JOYSTICK)) return false;
                    if (event.getAction() == KeyEvent.ACTION_DOWN) readout.setText(KeyEvent.keyCodeToString(key));
                    return true;
                })
                .create();
        readout.setOnGenericMotionListener((view, event) -> {
            if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK)) return false;
            readout.setText(String.format(java.util.Locale.ROOT, "Left %.2f, %.2f   ·   Right %.2f, %.2f",
                    event.getAxisValue(MotionEvent.AXIS_X), event.getAxisValue(MotionEvent.AXIS_Y),
                    event.getAxisValue(MotionEvent.AXIS_Z), event.getAxisValue(MotionEvent.AXIS_RZ)));
            return true;
        });
        readout.setFocusable(true);
        readout.setFocusableInTouchMode(true);
        dialog.show();
        readout.requestFocus();
    }

    private static void chooseButton(Activity activity, SharedPreferences preferences, Action action) {
        new ThorSheet(activity).setTitle(action.label).setMeta("Now " + name(assigned(preferences, action)))
                .setSingleChoiceItems(KEY_LABELS, indexOf(assigned(preferences, action)), (dialog, index) -> {
                    int key = KEYS[index];
                    int previous = assigned(preferences, action);
                    SharedPreferences.Editor editor = preferences.edit();
                    for (Action binding : Action.values()) {
                        if (binding != action && key >= 0 && assigned(preferences, binding) == key)
                            editor.putInt(binding.preference(), previous);
                    }
                    editor.putInt(action.preference(), key).apply();
                    dialog.dismiss();
                    showSettings(activity, action.ordinal());
                })
                .setNegativeButton("Cancel", (dialog, which) -> showSettings(activity, action.ordinal()))
                .show();
    }

    private static void chooseSensitivity(Activity activity, SharedPreferences preferences) {
        SeekBar value = new SeekBar(activity);
        value.setMax(150);
        value.setProgress(preferences.getInt("thor_controller_sensitivity", 100) - 25);
        value.setProgressTintList(android.content.res.ColorStateList.valueOf(ThorStyle.AMBER));
        value.setThumbTintList(android.content.res.ColorStateList.valueOf(ThorStyle.AMBER_HI));
        value.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(ThorStyle.OLIVE));
        int padding = Math.round(24 * activity.getResources().getDisplayMetrics().density);
        value.setPadding(padding, padding, padding, padding);
        TextView readout = new TextView(activity);
        readout.setText((value.getProgress() + 25) + "%");
        readout.setTextColor(ThorStyle.AMBER_HI);
        readout.setTypeface(ThorStyle.MONO);
        readout.setPadding(padding, 0, padding, 0);
        value.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                readout.setText((progress + 25) + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        android.widget.LinearLayout column = new android.widget.LinearLayout(activity);
        column.setOrientation(android.widget.LinearLayout.VERTICAL);
        column.addView(readout);
        column.addView(value);
        new ThorSheet(activity).setTitle("Look sensitivity").setMeta("D-pad  ◀ ▶")
                .setView(column)
                .setOnKeyListener((window, key, event) -> {
                    if (key != KeyEvent.KEYCODE_DPAD_LEFT && key != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                    if (event.getAction() == KeyEvent.ACTION_DOWN)
                        value.setProgress(value.getProgress() + (key == KeyEvent.KEYCODE_DPAD_RIGHT ? 5 : -5));
                    return true;
                })
                .setPositiveButton("Save", (dialog, which) -> {
                    preferences.edit().putInt("thor_controller_sensitivity", value.getProgress() + 25).apply();
                    showSettings(activity, Action.values().length);
                })
                .setNegativeButton("Cancel", (dialog, which) -> showSettings(activity, Action.values().length))
                .show();
        value.requestFocus();
    }

    private static int assigned(SharedPreferences preferences, Action action) {
        return preferences.getInt(action.preference(), action.initialKey);
    }

    private static int indexOf(int key) {
        for (int i = 0; i < KEYS.length; ++i) if (KEYS[i] == key) return i;
        return KEYS.length - 1;
    }

    private static String name(int key) {
        return KEY_LABELS[indexOf(key)];
    }
}