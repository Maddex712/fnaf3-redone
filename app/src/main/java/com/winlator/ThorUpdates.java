package com.winlator;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.ProgressBar;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;

import javax.net.ssl.HttpsURLConnection;

final class ThorUpdates {
    private static final String STAGED_VERSION = "verified_version";
    private static final String STAGED_SHA256 = "verified_sha256";
    private static final int PICK_APK = 62;
    private static final int ALLOW_INSTALL = 63;
    private static final long MAX_APK_BYTES = 350L * 1024 * 1024;
    private static final String APK_MIME = "application/vnd.android.package-archive";

    private final ThorProbeActivity activity;
    private final File packageFile;
    private final android.content.SharedPreferences preferences;
    private Transfer active;

    ThorUpdates(ThorProbeActivity activity) {
        this.activity = activity;
        packageFile = new File(activity.getCacheDir(), "updates/verified.apk");
        preferences = activity.getSharedPreferences("thor_updates", android.content.Context.MODE_PRIVATE);
        try {
            if (preferences.getLong(STAGED_VERSION, Long.MAX_VALUE) <= versionCode(installedPackage())) {
                if (Files.deleteIfExists(packageFile.toPath()) || !packageFile.exists())
                    preferences.edit().remove(STAGED_VERSION).remove(STAGED_SHA256).apply();
            }
        } catch (IOException | PackageManager.NameNotFoundException error) {
            android.util.Log.w("ThorUpdates", "Could not clean the previous APK", error);
        }
    }

    void show() {
        java.util.List<ThorSheet.Row> choices = new java.util.ArrayList<>();
        boolean online = !activity.getString(R.string.thor_update_feed).isEmpty();
        choices.add(new ThorSheet.Row("Check for updates").value(online ? ">" : "No feed")
                .tone(online ? ThorSheet.Tone.NORMAL : ThorSheet.Tone.DISABLED));
        choices.add(new ThorSheet.Row("Select update APK").value(">").sub("Verified against this installation's signature"));
        if (packageFile.isFile())
            choices.add(new ThorSheet.Row("Install staged update").value("Ready").tone(ThorSheet.Tone.OK));
        new ThorSheet(activity).setTitle("Updates").setMeta(installedVersion())
                .setRows(choices, (dialog, position) -> {
                    if (position == 0) check();
                    else if (position == 1) pickApk();
                    else verifyStaged();
                }).setNegativeButton("Done", null).show();
    }

    private String installedVersion() {
        try {
            return activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
            return "";
        }
    }

    private void pickApk() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType(APK_MIME);
        activity.startActivityForResult(intent, PICK_APK);
    }

    boolean onActivityResult(int request, int result, Intent data) {
        if (request == PICK_APK) {
            if (result == android.app.Activity.RESULT_OK && data != null && data.getData() != null)
                stage(data.getData(), null);
            return true;
        }
        if (request == ALLOW_INSTALL) {
            if (activity.getPackageManager().canRequestPackageInstalls() && packageFile.isFile()) verifyStaged();
            return true;
        }
        return false;
    }

    void cancel() {
        if (active != null) active.cancel();
    }

    private void check() {
        String feed = activity.getString(R.string.thor_update_feed);
        if (feed.isEmpty()) {
            message("Updates", "There is no online update feed configured.");
            return;
        }
        transfer("Checking for updates", t -> {
            URL feedUrl = new URL(feed);
            JSONObject json;
            try (InputStream input = t.open(feedUrl)) {
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    t.requireActive();
                    if (body.size() + count > 65536) throw new IOException("Invalid release response.");
                    body.write(buffer, 0, count);
                }
                json = new JSONObject(new String(body.toByteArray(), StandardCharsets.UTF_8));
            }
            if (json.getLong("versionCode") <= versionCode(installedPackage())) {
                return () -> message("Updates", "The installed version is up to date.");
            }
            Release release = new Release(json, feedUrl);
            return () -> new ThorSheet(activity).setTitle("Update available").setMeta(installedVersion())
                    .setMessage("Version " + release.versionName + " is available.", ThorSheet.Tone.WARN)
                    .setNegativeButton("Later", null)
                    .setPositiveButton("Download", (dialog, choice) -> stage(null, release)).show();
        });
    }

    private void stage(Uri local, Release release) {
        transfer(release == null ? "Verifying update APK" : "Downloading update", t -> {
            File folder = packageFile.getParentFile();
            if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Update storage is unavailable.");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            File staging = File.createTempFile("update-", ".apk", folder);
            try {
                byte[] stagedDigest;
                try (InputStream input = release == null
                        ? t.document.open(activity.getContentResolver(), local) : t.open(release.apkUrl);
                     FileOutputStream output = new FileOutputStream(staging)) {
                    if (input == null) throw new IOException("Could not read the APK.");
                    byte[] buffer = new byte[128 * 1024];
                    long size = 0;
                    int count;
                    int lastPercent = -1;
                    while ((count = input.read(buffer)) != -1) {
                        t.requireActive();
                        size += count;
                        if (size > MAX_APK_BYTES || release != null && size > release.size)
                            throw new IOException("The APK exceeds the expected size.");
                        digest.update(buffer, 0, count);
                        output.write(buffer, 0, count);
                        if (release != null) {
                            int percent = (int) (size * 100 / release.size);
                            if (percent != lastPercent) {
                                lastPercent = percent;
                                t.progress(percent);
                            }
                        }
                    }
                    stagedDigest = digest.digest();
                    if (release != null && (size != release.size
                            || !MessageDigest.isEqual(release.sha256, stagedDigest)))
                        throw new IOException("The download failed its SHA-256 check.");
                    output.getFD().sync();
                }
                PackageInfo archive = validate(staging, release);
                synchronized (t) {
                    t.requireActive();
                    Files.move(staging.toPath(), packageFile.toPath(), StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                    preferences.edit().putLong(STAGED_VERSION, versionCode(archive))
                            .putString(STAGED_SHA256, hex(stagedDigest)).apply();
                }
                return () -> new ThorSheet(activity).setTitle("Install verified update").setMeta("Verified")
                        .setMessage("Version " + archive.versionName + " is ready for Android to install.",
                                ThorSheet.Tone.OK)
                        .setNegativeButton("Later", null)
                        .setPositiveButton("Install", (dialog, choice) -> showInstaller()).show();
            } finally {
                Files.deleteIfExists(staging.toPath());
            }
        });
    }

    private static int signatureFlags() {
        return Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
    }

    private PackageInfo installedPackage() throws PackageManager.NameNotFoundException {
        return activity.getPackageManager().getPackageInfo(activity.getPackageName(), signatureFlags());
    }

    private PackageInfo validate(File file, Release release) throws Exception {
        PackageInfo installed = installedPackage();
        PackageInfo archive = activity.getPackageManager().getPackageArchiveInfo(file.getPath(), signatureFlags());
        if (archive == null || !activity.getPackageName().equals(archive.packageName))
            throw new IOException("Choose an FNaF 3 Redone update APK.");
        if (versionCode(archive) <= versionCode(installed))
            throw new IOException("Choose an update newer than the installed build.");
        if (release != null && (versionCode(archive) != release.versionCode
                || !release.versionName.equals(archive.versionName)))
            throw new IOException("The APK version does not match the release.");
        Signature[] current = signers(installed);
        Signature[] incoming = signers(archive);
        if (current.length == 0 || incoming.length == 0
                || !new HashSet<>(Arrays.asList(current)).equals(new HashSet<>(Arrays.asList(incoming))))
            throw new IOException("The update's signing certificate does not match this installation.");
        if (archive.applicationInfo == null || archive.applicationInfo.minSdkVersion > Build.VERSION.SDK_INT)
            throw new IOException("This update requires a newer Android version.");
        return archive;
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) {
            result.append(Character.forDigit((item >> 4) & 15, 16));
            result.append(Character.forDigit(item & 15, 16));
        }
        return result.toString();
    }

    private static long versionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    private static Signature[] signers(PackageInfo info) {
        Signature[] signers = Build.VERSION.SDK_INT < 28 ? info.signatures
                : info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners();
        return signers == null ? new Signature[0] : signers;
    }

    private void verifyStaged() {
        transfer("Verifying staged update", t -> {
            String expected = preferences.getString(STAGED_SHA256, "");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(packageFile.toPath())) {
                byte[] buffer = new byte[128 * 1024];
                long size = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    t.requireActive();
                    size += count;
                    if (size > MAX_APK_BYTES) throw new IOException("The staged APK is too large.");
                    digest.update(buffer, 0, count);
                }
            }
            if (!expected.equals(hex(digest.digest())))
                throw new IOException("The staged APK did not pass its SHA-256 check. Select it again.");
            validate(packageFile, null);
            return this::showInstaller;
        });
    }

    private void showInstaller() {
        try {
            if (!activity.getPackageManager().canRequestPackageInstalls()) {
                new ThorSheet(activity).setTitle("Allow app updates")
                        .setMessage("Enable Allow from this source in Android settings, then return to install the APK.")
                        .setNegativeButton("Later", null)
                        .setPositiveButton("Open settings", (dialog, choice) -> {
                            try {
                                activity.startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                        Uri.fromParts("package", activity.getPackageName(), null)), ALLOW_INSTALL);
                            } catch (ActivityNotFoundException | SecurityException error) {
                                message("Updates", "Android could not open install permission settings.");
                            }
                        }).show();
                return;
            }
            Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".FileProvider", packageFile);
            Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_MIME)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setClipData(ClipData.newRawUri("FNaF 3 Redone update", uri));
            activity.startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException error) {
            message("Updates", "Android could not open the package installer.");
        }
    }

    private void message(String title, String body) {
        boolean good = body.contains("up to date");
        new ThorSheet(activity).setTitle(title).setMeta(installedVersion())
                .setMessage(body, good ? ThorSheet.Tone.OK : ThorSheet.Tone.ERROR)
                .setPositiveButton("Done", null).show();
    }

    private interface Work { Runnable run(Transfer transfer) throws Exception; }

    private void transfer(String title, Work work) {
        if (active != null) return;
        ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        bar.setMax(100);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(ThorStyle.AMBER));
        bar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(ThorStyle.AMBER));
        bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(ThorStyle.OLIVE_DIM));
        int pad = Math.round(22 * activity.getResources().getDisplayMetrics().density);
        bar.setPadding(pad, pad, pad, pad);
        Transfer t = new Transfer(bar);
        active = t;
        android.app.Dialog dialog = new ThorSheet(activity).setTitle(title).setView(bar)
                .setNegativeButton("Cancel", (window, button) -> t.cancel())
                .setOnCancelListener(window -> t.cancel()).show();
        new Thread(() -> {
            Runnable finish;
            try {
                finish = work.run(t);
            } catch (Exception error) {
                android.util.Log.w("ThorUpdates", "Update failed", error);
                String detail = error instanceof IOException ? error.getMessage() : "The update could not be verified.";
                finish = () -> message("Updates", detail);
            } finally {
                t.disconnect();
            }
            Runnable result = finish;
            activity.runOnUiThread(() -> {
                if (active != t) return;
                active = null;
                if (activity.isFinishing() || activity.isDestroyed()) return;
                dialog.dismiss();
                if (!t.cancelled) result.run();
            });
        }, "thor-update").start();
    }

    private final class Transfer {
        final ProgressBar bar;
        final ThorDocumentRead document = new ThorDocumentRead();
        volatile boolean cancelled;
        volatile HttpsURLConnection connection;

        Transfer(ProgressBar bar) { this.bar = bar; }

        InputStream open(URL url) throws IOException {
            requireActive();
            if (!"https".equals(url.getProtocol()) || url.getUserInfo() != null)
                throw new IOException("The update source must use HTTPS.");
            connection = (HttpsURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(9000);
            connection.setReadTimeout(12000);
            connection.setRequestProperty("User-Agent", "FNaF-3-Redone-Android");
            requireActive();
            if (connection.getResponseCode() != 200) throw new IOException("The update server is unavailable.");
            return connection.getInputStream();
        }

        void requireActive() throws InterruptedIOException {
            if (cancelled) throw new InterruptedIOException("Update cancelled.");
        }

        void cancel() {
            synchronized (this) {
                cancelled = true;
            }
            if (active == this) active = null;
            document.cancel();
            if (connection != null) new Thread(this::disconnect, "thor-cancel-update").start();
        }

        void disconnect() {
            HttpsURLConnection current = connection;
            if (current != null) current.disconnect();
        }

        void progress(int value) {
            activity.runOnUiThread(() -> {
                bar.setIndeterminate(false);
                bar.setProgress(value);
            });
        }
    }

    private static final class Release {
        final long size, versionCode;
        final String versionName;
        final URL apkUrl;
        final byte[] sha256;

        Release(JSONObject entry, URL feed) throws Exception {
            size = entry.getLong("size");
            versionCode = entry.getLong("versionCode");
            versionName = entry.getString("versionName");
            apkUrl = new URL(feed, entry.getString("apkUrl"));
            String digest = entry.getString("sha256");
            if (size <= 0 || size > MAX_APK_BYTES || !digest.matches("[a-fA-F0-9]{64}")
                    || !"https".equals(apkUrl.getProtocol()) || apkUrl.getUserInfo() != null
                    || !feed.getHost().equalsIgnoreCase(apkUrl.getHost())
                    || feed.getPort() != apkUrl.getPort())
                throw new IOException("The release manifest is invalid.");
            sha256 = new byte[32];
            for (int i = 0; i < sha256.length; ++i)
                sha256[i] = (byte) Integer.parseInt(digest.substring(i * 2, i * 2 + 2), 16);
        }
    }
}

