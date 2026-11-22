package com.winlator;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.util.AtomicFile;
import android.util.Log;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.DateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

final class ThorGallery {
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor();
    private static final int MANUAL_LIMIT = 30;
    private static final String SIGHTINGS = "rare_sightings";

    private final File directory;
    private int currentCamera = -1;
    private long stableSince;
    private int autoQueued;
    private final AtomicInteger manualQueued = new AtomicInteger();

    ThorGallery(Context context) {
        directory = new File(context.getFilesDir(), "camera-archive");
    }

    void reset() {
        currentCamera = -1;
    }

    void update(Bitmap scene, Rect crop, int camera, long now) {
        if (camera < 1 || camera > 15) {
            reset();
            return;
        }
        if (camera != currentCamera) {
            currentCamera = camera;
            stableSince = now;
            return;
        }
        int bit = 1 << (camera - 1);
        if (now - stableSince < 900 || (autoQueued & bit) != 0) return;
        File file = new File(directory, String.format(Locale.ROOT, "camera-%02d.jpg", camera));
        autoQueued |= bit;
        if (!file.exists()) save(scene, crop, file, false);
    }

    boolean snapshot(Bitmap scene, Rect crop) {
        File[] files = directory.listFiles((dir, name) -> name.matches("snapshot-[0-9]+\\.jpg"));
        if ((files == null ? 0 : files.length) + manualQueued.get() >= MANUAL_LIMIT) return false;
        long time = System.currentTimeMillis();
        File file = new File(directory, "snapshot-" + time + ".jpg");
        manualQueued.incrementAndGet();
        save(scene, crop, file, true);
        return true;
    }

    private void save(Bitmap frame, Rect crop, File destination, boolean manual) {
        Bitmap snapshot = Bitmap.createBitmap(frame, crop.left, crop.top, crop.width(), crop.height());
        WRITER.execute(() -> {
            try {
                if (!directory.isDirectory() && !directory.mkdirs()) return;
                AtomicFile target = new AtomicFile(destination);
                FileOutputStream output = target.startWrite();
                try {
                    if (!snapshot.compress(Bitmap.CompressFormat.JPEG, 85, output))
                        throw new IOException("Could not encode the camera snapshot");
                    target.finishWrite(output);
                } catch (IOException error) {
                    target.failWrite(output);
                    throw error;
                }
            } catch (IOException error) {
                Log.w("ThorGallery", "Could not store camera snapshot", error);
            } finally {
                snapshot.recycle();
                if (manual) manualQueued.decrementAndGet();
            }
        });
    }

    /** Capture and rare-sighting counts for the launcher monitor. */
    static int[] counts(Context context) {
        File directory = new File(context.getFilesDir(), "camera-archive");
        File[] files = directory.listFiles((dir, name) -> name.matches("(camera-[0-9]{2}|snapshot-[0-9]+)\\.jpg"));
        Set<String> sightings = preferences(context).getStringSet(SIGHTINGS, java.util.Collections.emptySet());
        int rare = 0;
        if (files != null) for (File file : files) if (sightings.contains(file.getName())) ++rare;
        return new int[]{files == null ? 0 : files.length, rare};
    }

    static void show(Activity activity) {
        int[] counts = counts(activity);
        java.util.List<ThorSheet.Row> rows = new java.util.ArrayList<>();
        rows.add(new ThorSheet.Row("All captures").value(Integer.toString(counts[0]))
                .sub("Room cameras, vent cameras and snapshots"));
        rows.add(new ThorSheet.Row("Rare sightings").value(Integer.toString(counts[1]))
                .sub("Captures you marked as rare"));
        new ThorSheet(activity).setTitle("Camera archive").setMeta(counts[0] + " captures")
                .setRows(rows, (dialog, index) -> showCollection(activity, index == 1))
                .setPositiveButton("Done", null).show();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("thor_camera_archive", Context.MODE_PRIVATE);
    }

    private static void showCollection(Activity activity, boolean rareOnly) {
        File directory = new File(activity.getFilesDir(), "camera-archive");
        Set<String> sightings = preferences(activity).getStringSet(SIGHTINGS, java.util.Collections.emptySet());
        File[] files = directory.listFiles((dir, name) -> name.matches("(camera-[0-9]{2}|snapshot-[0-9]+)\\.jpg")
                && (!rareOnly || sightings.contains(name)));
        ThorSheet dialog = new ThorSheet(activity).setTitle(rareOnly ? "Rare sightings" : "All captures");
        if (files == null || files.length == 0) {
            dialog.setMeta("Empty").setMessage(rareOnly ? "Mark a capture as a rare sighting to find it here."
                    : "Visit a room or vent camera to start the archive.", ThorSheet.Tone.DISABLED);
        } else {
            Arrays.sort(files);
            java.util.List<ThorSheet.Row> rows = new java.util.ArrayList<>();
            for (File file : files) {
                ThorSheet.Row row = new ThorSheet.Row(label(file));
                if (sightings.contains(file.getName())) row.value("Rare").tone(ThorSheet.Tone.WARN);
                rows.add(row);
            }
            dialog.setMeta(files.length + (files.length == 1 ? " capture" : " captures"))
                    .setRows(rows, (window, index) -> showImage(activity, files[index], rareOnly));
        }
        dialog.setNegativeButton("Back", (window, button) -> show(activity)).setPositiveButton("Done", null).show();
    }

    private static String label(File file) {
        String name = file.getName();
        if (name.startsWith("camera-")) {
            int camera = Integer.parseInt(name.substring(7, 9));
            return String.format(Locale.ROOT, "%s %02d", camera > 10 ? "Vent camera" : "Room camera", camera);
        }
        long time = Long.parseLong(name.substring(9, name.length() - 4));
        return "Snapshot   ·   " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(new Date(time));
    }

    private static void showImage(Activity activity, File file, boolean rareOnly) {
        Bitmap screenshot = BitmapFactory.decodeFile(file.getPath());
        if (screenshot == null) {
            new ThorSheet(activity).setTitle(label(file)).setMeta("Unreadable")
                    .setMessage("This capture could not be opened.", ThorSheet.Tone.ERROR)
                    .setNegativeButton("Back", (dialog, button) -> showCollection(activity, rareOnly)).show();
            return;
        }
        ImageView image = new ImageView(activity);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setAdjustViewBounds(true);
        image.setImageBitmap(screenshot);
        image.setBackgroundColor(ThorStyle.SHADOW);
        int pad = Math.round(6 * activity.getResources().getDisplayMetrics().density);
        image.setPadding(pad, pad, pad, pad);
        SharedPreferences settings = preferences(activity);
        Set<String> sightings = new HashSet<>(settings.getStringSet(SIGHTINGS, java.util.Collections.emptySet()));
        boolean marked = sightings.contains(file.getName());
        new ThorSheet(activity).setTitle(label(file)).setMeta(marked ? "Rare sighting" : null).setView(image)
                .setNeutralButton(marked ? "Remove mark" : "Mark rare", (dialog, button) -> {
                    if (marked) sightings.remove(file.getName());
                    else sightings.add(file.getName());
                    settings.edit().putStringSet(SIGHTINGS, sightings).apply();
                    showCollection(activity, rareOnly);
                })
                .setNegativeButton("Back", (dialog, button) -> showCollection(activity, rareOnly)).show();
    }
}
