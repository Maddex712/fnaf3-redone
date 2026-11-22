package com.winlator;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

final class ThorProgress {
    enum Badge {
        SHIFT("Clocked in", "Enter the office in the original game.", 30),
        CAMERA("Eyes on the building", "Open the live camera monitor.", 20),
        VENT("Check the vents", "Switch to the original vent map.", 20),
        ROUND("Camera round", "Visit five different room cameras.", 50),
        FIRST_NIGHT("First night complete", "Reach Night 2 in the original save.", 100),
        RESTART("Systems technician", "Open the original system restart panel.", 35),
        VENT_ROUND("Duct inspection", "Visit all five vent cameras.", 60),
        THIRD_NIGHT("Third night", "Reach Night 3 in the original save.", 125),
        FIFTH_NIGHT("Fifth night", "Reach Night 5 in the original save.", 150);

        final String title;
        final String requirement;
        final int points;

        Badge(String title, String requirement, int points) {
            this.title = title;
            this.requirement = requirement;
            this.points = points;
        }
    }

    enum Decoration {
        NONE("Empty desk", 0, 0),
        MOTH("Pocket moth", 50, 0xffb9a38b),
        LANTERN("Pocket lantern", 70, 0xffceab64),
        RABBIT("Stargazer plush", 95, 0xff97b7b6);

        final String label;
        final int points;
        final int color;

        Decoration(String label, int points, int color) {
            this.label = label;
            this.points = points;
            this.color = color;
        }
    }

    private final SharedPreferences preferences;
    private final File originalSave;
    private int badgeMask;
    private int roomMask;
    private int ventMask;
    private int wallet;
    private int owned;
    private Decoration displayed;
    private int savedNight;

    ThorProgress(Context context) {
        preferences = context.getSharedPreferences("thor_redone_progress", Context.MODE_PRIVATE);
        int campaign = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                .getInt(ThorProbeActivity.CAMPAIGN_CONTAINER, 1);
        originalSave = new File(context.getFilesDir(),
                "rootfs/home/xuser-" + campaign + "/" + ThorProbeActivity.SAVE_FILE);
        badgeMask = preferences.getInt("badges", 0);
        roomMask = preferences.getInt("rooms", 0);
        ventMask = preferences.getInt("vents", 0);
        wallet = preferences.getInt("wallet", 0);
        owned = preferences.getInt("decorations", 1);
        int selected = preferences.getInt("displayed", 0);
        Decoration[] choices = Decoration.values();
        displayed = selected >= 0 && selected < choices.length ? choices[selected] : Decoration.NONE;
        refreshSave();
    }

    boolean recordOffice() {
        return earn(Badge.SHIFT);
    }

    boolean recordCamera(boolean vent, int camera) {
        boolean changed = earn(Badge.CAMERA);
        if (vent) {
            changed = earn(Badge.VENT) || changed;
            if (camera < 0 || camera >= 5) return changed;
            int bit = 1 << camera;
            if ((ventMask & bit) != 0) return changed;
            ventMask |= bit;
            preferences.edit().putInt("vents", ventMask).apply();
            if (Integer.bitCount(ventMask) == 5) earn(Badge.VENT_ROUND);
            return true;
        }
        if (camera < 0 || camera >= 10) return changed;
        int bit = 1 << camera;
        if ((roomMask & bit) != 0) return changed;
        roomMask |= bit;
        preferences.edit().putInt("rooms", roomMask).apply();
        if (Integer.bitCount(roomMask) >= 5) earn(Badge.ROUND);
        return true;
    }

    boolean recordMaintenance() {
        return earn(Badge.RESTART);
    }

    boolean refreshSave() {
        int previous = savedNight;
        int night = readNight();
        if (night > 0) savedNight = night;
        boolean earned = savedNight >= 2 && earn(Badge.FIRST_NIGHT);
        if (savedNight >= 3) earned = earn(Badge.THIRD_NIGHT) || earned;
        if (savedNight >= 5) earned = earn(Badge.FIFTH_NIGHT) || earned;
        return earned || previous != savedNight;
    }

    private int readNight() {
        if (!originalSave.isFile() || originalSave.length() > 16_384) return 0;
        try (BufferedReader input = new BufferedReader(new FileReader(originalSave))) {
            boolean gameSection = false;
            String line;
            int remaining = 256;
            while (remaining-- > 0 && (line = input.readLine()) != null) {
                String value = line.trim();
                if (value.startsWith("[")) {
                    gameSection = "[freddy3]".equalsIgnoreCase(value);
                } else if (gameSection && value.startsWith("level=")) {
                    int night = Integer.parseInt(value.substring(6).trim());
                    return night >= 1 && night <= 99 ? night : 0;
                }
            }
        } catch (IOException | NumberFormatException ignored) {
        }
        return 0;
    }

    private boolean earn(Badge badge) {
        int bit = 1 << badge.ordinal();
        if ((badgeMask & bit) != 0) return false;
        badgeMask |= bit;
        wallet += badge.points;
        preferences.edit().putInt("badges", badgeMask).putInt("wallet", wallet).apply();
        return true;
    }

    Decoration displayItem() {
        return displayed;
    }

    String summary() {
        return (savedNight > 0 ? "Saved Night " + savedNight + "   ·   " : "")
                + wallet + " points   ·   " + Integer.bitCount(badgeMask) + "/" + Badge.values().length + " achievements";
    }

    int wallet() { return wallet; }
    int savedNight() { return savedNight; }
    int earnedCount() { return Integer.bitCount(badgeMask); }
    int badgeCount() { return Badge.values().length; }

    Badge nextBadge() {
        for (Badge badge : Badge.values()) if ((badgeMask & (1 << badge.ordinal())) == 0) return badge;
        return null;
    }

    boolean earned(Badge badge) { return (badgeMask & (1 << badge.ordinal())) != 0; }
    int roomCount() { return Integer.bitCount(roomMask); }
    int ventCount() { return Integer.bitCount(ventMask); }
    boolean owns(Decoration item) { return (owned & (1 << item.ordinal())) != 0; }

    /** Requirement text with live progress for a badge that is not earned yet. */
    String progressText(Badge badge) {
        if (earned(badge)) return "Earned";
        if (badge == Badge.ROUND) return badge.requirement + "  " + roomCount() + "/5";
        if (badge == Badge.VENT_ROUND) return badge.requirement + "  " + ventCount() + "/5";
        return badge.requirement;
    }

    /** Desk slot action: store the item on the desk, place an owned item, or buy and place it. */
    String deskAction(Decoration item) {
        if (item == Decoration.NONE) return null;
        if (displayed == item) {
            displayed = Decoration.NONE;
            preferences.edit().putInt("displayed", displayed.ordinal()).apply();
            return item.label + " stored.";
        }
        if (owns(item)) {
            equip(item, false, () -> {});
            return item.label + " is on the desk.";
        }
        if (wallet < item.points) return (item.points - wallet) + " more points needed for " + item.label + ".";
        equip(item, true, () -> {});
        return "Bought " + item.label + " and put it on the desk.";
    }

    /** The original desk-item shapes, drawn into any box (100 x 82 design space). */
    static void drawDecoration(android.graphics.Canvas canvas, android.graphics.Paint paint, Decoration item,
                               float left, float top, float width, float height) {
        if (item == Decoration.NONE) return;
        int checkpoint = canvas.save();
        float scale = Math.min(width / 100f, height / 82f);
        canvas.translate(left + (width - 100 * scale) / 2, top + (height - 82 * scale) / 2);
        canvas.scale(scale, scale);
        paint.setShader(null);
        paint.setStyle(android.graphics.Paint.Style.FILL);
        paint.setColor(0x77000000);
        canvas.drawOval(13, 69, 89, 80, paint);
        paint.setColor(item.color);
        if (item == Decoration.MOTH) {
            canvas.drawOval(12, 26, 55, 64, paint);
            canvas.drawOval(46, 26, 89, 64, paint);
            paint.setColor(0xff626451);
            canvas.drawOval(42, 18, 59, 73, paint);
            paint.setColor(0xffe3cd9b);
            canvas.drawCircle(27, 43, 4, paint);
            canvas.drawCircle(73, 43, 4, paint);
        } else if (item == Decoration.LANTERN) {
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

    void showAchievements(Activity activity) {
        refreshSave();
        java.util.List<ThorSheet.Row> rows = new java.util.ArrayList<>();
        for (Badge badge : Badge.values()) {
            boolean earned = (badgeMask & (1 << badge.ordinal())) != 0;
            String requirement = badge.requirement;
            if (!earned && badge == Badge.ROUND) requirement += "  " + Integer.bitCount(roomMask) + "/5";
            if (!earned && badge == Badge.VENT_ROUND) requirement += "  " + Integer.bitCount(ventMask) + "/5";
            rows.add(new ThorSheet.Row(badge.title).mark(earned ? "[X]" : "[ ]")
                    .value("+" + badge.points + " pts").sub(earned ? "Earned" : requirement)
                    .tone(earned ? ThorSheet.Tone.OK : ThorSheet.Tone.NORMAL).inert());
        }
        new ThorSheet(activity).setTitle("Achievements and challenges")
                .setMeta(wallet + " pts  ·  " + earnedCount() + "/" + badgeCount())
                .setMessage(savedNight > 0 ? "Saved Night " + savedNight + " in the original game." : null,
                        ThorSheet.Tone.OK)
                .setRows(rows, null).setPositiveButton("Done", null).show();
    }

    void showShop(Activity activity, Runnable update) {
        Decoration[] choices = Decoration.values();
        java.util.List<ThorSheet.Row> rows = new java.util.ArrayList<>();
        for (int i = 0; i < choices.length; ++i) {
            Decoration option = choices[i];
            ThorSheet.Row row = new ThorSheet.Row(option.label);
            if (option == displayed) row.value("Equipped").tone(ThorSheet.Tone.OK);
            else if ((owned & (1 << i)) != 0) row.value("Owned");
            else if (wallet < option.points) row.value(option.points + " pts").tone(ThorSheet.Tone.DISABLED)
                    .sub((option.points - wallet) + " more points needed");
            else row.value(option.points + " pts").tone(ThorSheet.Tone.WARN);
            rows.add(row);
        }
        new ThorSheet(activity).setTitle("Desk shop").setMeta(wallet + " pts")
                .setInitialFocus(displayed.ordinal())
                .setRows(rows, (dialog, index) -> chooseDecoration(activity, choices[index], update))
                .setNegativeButton("Done", null).show();
    }

    private void chooseDecoration(Activity activity, Decoration option, Runnable update) {
        int bit = 1 << option.ordinal();
        if ((owned & bit) != 0) {
            equip(option, false, update);
            return;
        }
        if (wallet < option.points) {
            new ThorSheet(activity).setTitle("More points needed").setMeta(wallet + " pts")
                    .setMessage(option.label + " costs " + option.points + " points.", ThorSheet.Tone.ERROR)
                    .setPositiveButton("Back to shop", (dialog, which) -> showShop(activity, update))
                    .setNegativeButton("Back", (dialog, which) -> showShop(activity, update)).show();
            return;
        }
        new ThorSheet(activity).setTitle(option.label).setMeta(wallet + " pts")
                .setMessage("Spend " + option.points + " points and put it on the desk?")
                .setNegativeButton("Cancel", (dialog, which) -> showShop(activity, update))
                .setPositiveButton("Buy and equip", (dialog, which) -> equip(option, true, update)).show();
    }

    private void equip(Decoration item, boolean purchase, Runnable update) {
        int bit = 1 << item.ordinal();
        if ((owned & bit) == 0) {
            if (!purchase || wallet < item.points) return;
            wallet -= item.points;
            owned |= bit;
        }
        displayed = item;
        preferences.edit().putInt("wallet", wallet).putInt("decorations", owned)
                .putInt("displayed", displayed.ordinal()).apply();
        update.run();
    }
}