package com.winlator;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.OperationCanceledException;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.xenvironment.RootFS;
import com.winlator.xenvironment.RootFSInstaller;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

public final class ThorProbeActivity extends AppCompatActivity {
    private static final String TAG = "ThorFnaf3Probe";
    static final String CAMPAIGN_CONTAINER = "thor_campaign_container_id";
    static final String SAVE_FILE = ".wine/drive_c/users/xuser/AppData/Roaming/MMFApplications/freddy3";
    private static final int PICK_GAME = 61;
    private static final String GAME_NAME = "FiveNightsatFreddys3.exe";
    private static final long GAME_LENGTH = 116_634_069L;
    private static final String GAME_SHA256 =
            "705ebbda9704e3b3d0f4bdbae25f691abd10713191068d66ca1df6fedc5e2260";

    private File sourceDir;
    private File game;
    private ThorTitleView title;
    private ThorDocumentRead activeImport;
    private boolean busy;
    private boolean preparing;
    private boolean importFailed;
    private boolean launched;
    private boolean setupMode;
    private int importPercent = -1;
    private boolean installingRuntime;
    private int runtimePercent;
    private String message;
    private int messageColor = ThorStyle.MUTED;
    private ThorUpdates updates;
    private ThorBootPanel.Screen lower;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sourceDir = new File(getFilesDir(), "steam-game");
        game = new File(sourceDir, GAME_NAME);
        updates = new ThorUpdates(this);

        title = new ThorTitleView(this);
        title.setListener(this::activate);
        setContentView(title);
        immersive();
        title.requestFocus();
        // Like FNaF 1 and 2 Redone there is one title: with the Steam file imported the app goes
        // straight into the game, whose title carries the menu. Setup only appears when needed.
        setupMode = !game.isFile() || savedInstanceState != null;
        refresh();
        if (!setupMode) prepareRuntime(null);
    }

    private boolean behindGame;

    @Override
    protected void onStop() {
        super.onStop();
        // The game brings its own lower screen.
        hideLower();
        // Only a launch that actually covered this screen counts; the first resume after a launch
        // from onCreate is not a return from the game.
        if (launched) behindGame = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        showLower();
        refresh();
        if (!behindGame) return;
        behindGame = false;
        launched = false;
        ThorSession.Next next = ThorSession.take();
        switch (next) {
            case EXIT:
                finishAndRemoveTask();
                return;
            case RESTART:
                prepareRuntime(null);
                return;
            case ARCADE:
                prepareRuntime(ThorSession.arcade());
                return;
            default:
                setupMode = true;
                updateState(false, next == ThorSession.Next.SETUP ? null : "The game closed. Select Play to continue.");
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) return;
        immersive();
        title.requestFocus();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (title != null && setupMode && event.getAction() != KeyEvent.ACTION_MULTIPLE
                && title.key(key, event.getAction() == KeyEvent.ACTION_DOWN)) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(android.view.MotionEvent event) {
        if (setupMode && event.isFromSource(android.view.InputDevice.SOURCE_JOYSTICK)
                && event.getAction() == android.view.MotionEvent.ACTION_MOVE) {
            float y = event.getAxisValue(android.view.MotionEvent.AXIS_HAT_Y);
            if (Math.abs(y) < .5f) y = event.getAxisValue(android.view.MotionEvent.AXIS_Y);
            int direction = y < -.6f ? -1 : y > .6f ? 1 : 0;
            if (direction != stickDirection && direction != 0)
                title.key(direction < 0 ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_DOWN, true);
            stickDirection = direction;
            return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }

    private int stickDirection;

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private void activate(ThorTitleView.Entry entry) {
        switch (entry.id) {
            case "import": pickGame(); break;
            case "cancel":
                if (activeImport != null) {
                    activeImport.cancel();
                    activeImport = null;
                    updateState(false, "Import cancelled.");
                }
                break;
            case "play":
                if (game.isFile()) prepareRuntime(null);
                else updateState(false, "Import the Steam executable to play.");
                break;
            case "updates":
                if (!busy) updates.show();
                break;
            case "exit": finishAndRemoveTask(); break;
            default: break;
        }
    }

    private void pickGame() {
        if (busy) return;
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType("*/*");
        startActivityForResult(picker, PICK_GAME);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (updates.onActivityResult(requestCode, resultCode, data)) return;
        if (requestCode != PICK_GAME || resultCode != Activity.RESULT_OK || data == null
                || data.getData() == null || busy) return;

        Uri source = data.getData();
        ThorDocumentRead request = new ThorDocumentRead();
        activeImport = request;
        importPercent = 0;
        updateState(true, "Verifying the Steam executable…");
        new Thread(() -> {
            String message;
            boolean failed = false;
            try {
                importOriginal(source, request);
                message = "Steam executable imported.";
            } catch (OperationCanceledException error) {
                message = "Import cancelled.";
            } catch (IOException | NoSuchAlgorithmException | SecurityException error) {
                Log.w(TAG, "Import failed", error);
                message = "Import failed. " + error.getMessage();
                failed = true;
            }
            String result = message;
            boolean failure = failed;
            runOnUiThread(() -> {
                if (activeImport != request) return;
                activeImport = null;
                importFailed = failure;
                updateState(false, result);
            });
        }, "fnaf3-import").start();
    }

    @Override
    protected void onDestroy() {
        if (activeImport != null) activeImport.cancel();
        updates.cancel();
        hideLower();
        super.onDestroy();
    }

    private void updateState(boolean inProgress, String text) {
        if (isFinishing() || isDestroyed()) return;
        busy = inProgress;
        if (!inProgress) preparing = false;
        if (activeImport == null) importPercent = -1;
        message = text;
        boolean error = text != null && (text.startsWith("Import failed") || text.startsWith("Unable")
                || text.contains("could not") || text.startsWith("Import the") || text.startsWith("The game closed"));
        messageColor = error ? ThorStyle.RED : text != null && text.equals("Steam executable imported.")
                ? ThorStyle.GREEN : ThorStyle.MUTED;
        refresh();
    }

    /** Title-style setup: the same stacked title and >> menu, with plain status text on the right. */
    private void refresh() {
        if (title == null || isFinishing() || isDestroyed()) return;
        boolean imported = game.isFile();
        boolean importing = activeImport != null;
        List<ThorTitleView.Entry> entries = new ArrayList<>();
        String keep;
        if (installingRuntime) {
            // First launch after an install: the Wine and Box64 runtime unpacks once, behind the title.
            title.setEntries(entries, null);
            title.setInfo(new String[]{"First launch", "Unpacking the Windows runtime that runs your copy "
                    + "of FNaF 3. This happens once after installing."}, ThorStyle.MUTED);
            title.setProgress(Math.max(0, runtimePercent));
            title.setStatus("Preparing FNaF 3…", ThorStyle.MUTED);
            lower("First launch", new String[]{"Unpacking the Windows runtime that runs your copy of FNaF 3. "
                    + "This happens once after installing."}, ThorStyle.MUTED, Math.max(0, runtimePercent), false);
            return;
        }
        if (!setupMode && !importing) {
            title.setEntries(entries, null);
            title.setInfo(null, ThorStyle.TEXT);
            title.setProgress(-1);
            title.setStatus(message != null && messageColor == ThorStyle.RED ? message : "Loading the original game\u2026",
                    message != null && messageColor == ThorStyle.RED ? ThorStyle.RED : ThorStyle.MUTED);
            boolean failed = message != null && messageColor == ThorStyle.RED;
            lower(failed ? "Could not start" : "Loading the original game",
                    new String[]{failed ? message : "Your Steam copy of FNaF 3 is starting in the private runtime."},
                    failed ? ThorStyle.RED : ThorStyle.MUTED, -1, false);
            return;
        }
        if (importing) {
            entries.add(new ThorTitleView.Entry("cancel", "Cancel import"));
            keep = "cancel";
            title.setInfo(new String[]{"Verifying the Steam executable", "Copying FiveNightsatFreddys3.exe and "
                    + "checking its size and SHA-256."}, ThorStyle.MUTED);
            title.setProgress(Math.max(0, importPercent));
        } else {
            if (imported) entries.add(new ThorTitleView.Entry("play", "Play"));
            entries.add(new ThorTitleView.Entry("import", imported ? "Re-import Steam file" : "Import Steam file"));
            entries.add(new ThorTitleView.Entry("updates", "Updates"));
            entries.add(new ThorTitleView.Entry("exit", "Exit"));
            keep = imported && !importFailed ? "play" : "import";
            String[] info;
            if (preparing) {
                info = new String[]{"Starting", "Preparing the private runtime\u2026"};
            } else if (message != null) {
                info = new String[]{importFailed ? "Import failed" : imported ? "Ready" : "Setup", message};
            } else if (imported) {
                info = new String[]{"Ready", "Steam build 1892839 is verified on this device.",
                        "Re-importing keeps your saves, unlocks and points."};
            } else {
                info = new String[]{"Setup", "Select FiveNightsatFreddys3.exe from Steam build 1892839.",
                        "The file is verified and copied into the app. It stays on this device."};
            }
            title.setInfo(info, message != null ? messageColor : ThorStyle.MUTED);
            title.setProgress(preparing ? 0 : -1);
        }
        title.setEntries(entries, keep);
        if (importing) {
            lower("Verifying the Steam executable", new String[]{"Copying FiveNightsatFreddys3.exe and checking "
                    + "its size and SHA-256."}, ThorStyle.MUTED, Math.max(0, importPercent), true);
        } else {
            String[] shown = title.info();
            String[] rest = new String[Math.max(0, shown.length - 1)];
            System.arraycopy(shown, 1, rest, 0, rest.length);
            lower(shown.length > 0 ? shown[0] : "Setup", rest, message != null ? messageColor : ThorStyle.MUTED,
                    preparing ? 0 : -1, true);
        }
        title.setStatus("STEAM BUILD 1892839  /  " + (importing ? "VERIFYING" : imported ? "VERIFIED" : "NOT IMPORTED")
                + "     " + versionName() + "     D-PAD  MOVE     A  SELECT", ThorStyle.MUTED);
    }

    private void lower(String heading, String[] lines, int color, int progress, boolean menu) {
        if (lower != null) lower.panel.set(heading, lines, color, progress, menu);
    }

    /** Covers the Thor's lower screen while this screen is up, unless single-screen mode is chosen. */
    private void showLower() {
        if (PreferenceManager.getDefaultSharedPreferences(this).getInt("thor_display_mode", 0) == 2) {
            hideLower();
            return;
        }
        android.view.Display display = ThorBootPanel.lowerDisplay(this);
        if (display == null) {
            hideLower();
            return;
        }
        if (lower != null && lower.isShowing() && lower.getDisplay().getDisplayId() == display.getDisplayId()) return;
        hideLower();
        try {
            lower = new ThorBootPanel.Screen(this, display);
            lower.show();
        } catch (android.view.WindowManager.InvalidDisplayException error) {
            Log.w(TAG, "Lower screen is not available", error);
            lower = null;
        }
    }

    private void hideLower() {
        if (lower == null) return;
        try {
            lower.dismiss();
        } catch (RuntimeException ignored) {
            // The display went away first.
        }
        lower = null;
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
            return "";
        }
    }

    private void importOriginal(Uri source, ThorDocumentRead request) throws IOException, NoSuchAlgorithmException {
        if (!sourceDir.isDirectory() && !sourceDir.mkdirs()) {
            throw new IOException("Unable to open private game storage.");
        }

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        File staging = File.createTempFile("steam-", ".pending", sourceDir);
        try {
            long total = 0;
            int lastProgress = 0;
            byte[] bytes = new byte[256 * 1024];
            try (InputStream input = request.open(getContentResolver(), source);
                 FileOutputStream output = new FileOutputStream(staging)) {
                int count;
                while ((count = input.read(bytes)) != -1) {
                    request.check();
                    total += count;
                    if (total > GAME_LENGTH) throw unsupportedFile();
                    digest.update(bytes, 0, count);
                    output.write(bytes, 0, count);
                    int percent = (int) (100 * total / GAME_LENGTH);
                    if (percent > lastProgress) {
                        lastProgress = percent;
                        runOnUiThread(() -> {
                            if (activeImport == request) {
                                importPercent = percent;
                                title.setProgress(percent);
                            }
                        });
                    }
                }
                output.getFD().sync();
            }
            if (total != GAME_LENGTH || !MessageDigest.isEqual(expectedHash(), digest.digest())) {
                throw unsupportedFile();
            }
            synchronized (request) {
                request.check();
                Files.move(staging.toPath(), game.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
            Log.i(TAG, "Validated and imported Steam build 1892839");
        } finally {
            if (staging.exists() && !staging.delete()) Log.w(TAG, "Could not remove import staging file");
        }
    }

    private static byte[] expectedHash() {
        byte[] result = new byte[32];
        for (int i = 0; i < result.length; ++i) {
            result[i] = (byte) Integer.parseInt(GAME_SHA256.substring(2 * i, 2 * i + 2), 16);
        }
        return result;
    }

    private static IOException unsupportedFile() {
        return new IOException("Expected the original FiveNightsatFreddys3.exe from Steam build 1892839.");
    }

    private void prepareRuntime(ThorArcade arcade) {
        if (busy || !game.isFile()) return;
        preparing = true;
        updateState(true, null);
        RootFS root = RootFS.find(this);
        if (root.isValid() && root.getVersion() == RootFSInstaller.LATEST_VERSION) {
            prepareContainer(arcade);
        } else {
            installingRuntime = true;
            refresh();
            RootFSInstaller.install(this, percent -> {
                runtimePercent = percent;
                if (title != null) title.setProgress(Math.max(1, percent));
            }, completed -> {
                installingRuntime = false;
                if (isFinishing() || isDestroyed()) return;
                if (completed) prepareContainer(arcade);
                else updateState(false, "The bundled runtime could not be installed.");
            });
        }
    }

    private void prepareContainer(ThorArcade arcade) {
        ContainerManager manager = new ContainerManager(this);
        for (Container container : manager.getContainers()) {
            String name = container.getName();
            if (arcade == null ? !ThorArcade.isPracticeProfile(name) : arcade.profileName().equals(name)) {
                startGame(container, arcade);
                return;
            }
        }

        try {
            JSONObject settings = new JSONObject();
            settings.put("name", arcade == null ? "FNaF 3 Campaign" : arcade.profileName());
            settings.put("drives", "D:" + sourceDir.getAbsolutePath());
            settings.put("graphicsDriver", "turnip,zink");
            settings.put("dxwrapper", "wined3d");
            manager.createContainerAsync(settings, container -> {
                if (isFinishing() || isDestroyed()) return;
                if (container != null) startGame(container, arcade);
                else updateState(false, "The private game profile could not be created.");
            });
        } catch (JSONException error) {
            Log.e(TAG, "Unable to set up the private game profile", error);
            updateState(false, "The private game profile could not be configured.");
        }
    }

    private void startGame(Container container, ThorArcade arcade) {
        android.content.SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);
        int mode = settings.getInt("thor_display_mode", 0);
        if (arcade == null) settings.edit().putInt(CAMPAIGN_CONTAINER, container.id).apply();
        boolean dual = mode != 2;
        Intent launch = new Intent(this, XServerDisplayActivity.class);
        launch.putExtra("container_id", container.id);
        launch.putExtra("exec_path", game.getAbsolutePath());
        launch.putExtra("thor_game", true);
        launch.putExtra("thor_use_presentation", dual);
        try {
            if (arcade != null) {
                seedArcadeSave(container, arcade.night);
                launch.putExtra("thor_arcade_stage", arcade.name());
            }
            prepareDisplayBridge();
            launch.putExtra("thor_display_bridge", true);
            launch.putExtra("thor_swap_screens", settings.getBoolean("thor_swap_screens", false));
            launch.putExtra("thor_fit_office", settings.getBoolean("thor_fit_office", false));
            launch.putExtra("thor_smooth_picture", settings.getBoolean("thor_smooth_picture", true));
            launch.putExtra("thor_office_perspective", settings.getBoolean("thor_office_perspective", false));
        } catch (IOException error) {
            Log.e(TAG, "Display bridge setup failed", error);
            updateState(false, arcade != null ? "Unable to prepare the Arcade profile." : "Unable to prepare the selected display mode.");
            return;
        }
        launched = true;
        setupMode = false;
        startActivity(launch);
        overridePendingTransition(0, 0);
        updateState(false, null);
    }

    private void seedArcadeSave(Container profile, int night) throws IOException {
        File save = new File(profile.getRootDir(), SAVE_FILE);
        Files.createDirectories(save.getParentFile().toPath());
        String record;
        if (save.isFile()) {
            if (save.length() > 16384) throw new IOException("The practice save is too large.");
            record = new String(Files.readAllBytes(save.toPath()), java.nio.charset.StandardCharsets.ISO_8859_1);
            java.util.regex.Matcher level = java.util.regex.Pattern.compile("(?im)^level=([0-9]+)\r?$").matcher(record);
            if (!level.find()) throw new IOException("The practice save has no level.");
            String targetNight = Integer.toString(night);
            if (level.group(1).equals(targetNight)) return;
            record = record.substring(0, level.start(1)) + targetNight + record.substring(level.end(1));
        } else {
            record = "[freddy3]\r\nlevel=" + night + "\r\ncine=1\r\n";
        }
        android.util.AtomicFile target = new android.util.AtomicFile(save);
        FileOutputStream output = target.startWrite();
        try {
            output.write(record.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
            target.finishWrite(output);
        } catch (IOException error) {
            target.failWrite(output);
            throw error;
        }
    }

    private void prepareDisplayBridge() throws IOException {
        File stock = new File(RootFS.find(this).getRootDir(), "opt/wine/lib/wine/i386-windows/d3d9.dll");
        try (InputStream source = Files.newInputStream(stock.toPath())) {
            installBridgeFile(source, "thor-wined3d9.dll");
        }
        Files.deleteIfExists(new File(sourceDir, "thor-display-active.flag").toPath());
        Files.deleteIfExists(new File(sourceDir, "thor-office.frames").toPath());
        Files.deleteIfExists(new File(sourceDir, "thor-scene.frames").toPath());
        try (InputStream source = getAssets().open("thor-dual/d3d9.dll")) {
            installBridgeFile(source, "d3d9.dll");
        }
        // The bridge draws through DXVK (Direct3D 9 to Vulkan) when this file is present.
        File dxvk = new File(sourceDir, "thor-dxvk-d3d9.dll");
        if (PreferenceManager.getDefaultSharedPreferences(this).getBoolean("thor_dxvk", true)) {
            try (InputStream source = getAssets().open("thor-dual/dxvk-d3d9.dll")) {
                installBridgeFile(source, "thor-dxvk-d3d9.dll");
            }
        } else {
            Files.deleteIfExists(dxvk.toPath());
        }
    }

    private void installBridgeFile(InputStream source, String name) throws IOException {
        File staging = File.createTempFile("bridge-", ".pending", sourceDir);
        try {
            Files.copy(source, staging.toPath(), StandardCopyOption.REPLACE_EXISTING);
            Files.move(staging.toPath(), new File(sourceDir, name).toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(staging.toPath());
        }
    }
}
