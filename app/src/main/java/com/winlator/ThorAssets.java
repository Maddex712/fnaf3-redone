package com.winlator;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import android.util.Log;
import android.util.SparseArray;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Interface art read at runtime from the player's own imported Steam executable (build 1892839,
 * verified by SHA-256 at import). Only a fixed list of interface images is read: the camera map,
 * monitor plates and labels, maintenance terminal text and night cards. Nothing is bundled in the
 * APK; the decoded images stay in app-private storage. The game executable is never run here.
 */
final class ThorAssets {
    static final int MAP = 533;
    static final int PLATE = 427;
    static final int CAM_PLATE = 82;
    static final int PLAY_AUDIO = 367;
    static final int MAP_TOGGLE = 432;
    static final int SEAL_VENT = 81;
    static final int AUDIO_DEVICES = 545;
    static final int CAMERA_SYSTEM = 546;
    static final int VENTILATION = 556;
    static final int REBOOT_ALL = 595;
    static final int ERROR = 602;
    static final int MUTE_CALL = 700;
    /** Original "12:00 AM / Nth night" cards, indexed by night; night 1 has none. */
    static final int[] NIGHT_CARDS = {0, 0, 838, 839, 843, 844, 845, 431};

    private static final int[] HANDLES = {MAP, PLATE, CAM_PLATE, PLAY_AUDIO, MAP_TOGGLE, SEAL_VENT,
            AUDIO_DEVICES, CAMERA_SYSTEM, VENTILATION, REBOOT_ALL, ERROR, MUTE_CALL, 838, 839, 843, 844, 845, 431};
    private static final String GAME = "steam-game/FiveNightsatFreddys3.exe";
    private static final long GAME_LENGTH = 116_634_069L;
    private static final long BANK = 2_640_285L;
    private static final long BANK_END = 92_359_199L;
    private static final int BANK_SIZE = 89_718_906;
    private static final int IMAGE_COUNT = 1066;
    private static final String FOLDER = "fnaf3-ui-1892839";

    private static final SparseArray<Bitmap> images = new SparseArray<>();
    private static boolean loading;
    private static boolean ready;
    private static final java.util.List<Runnable> waiting = new java.util.ArrayList<>();

    private ThorAssets() {}

    static synchronized Bitmap get(int handle) {
        return handle == 0 ? null : images.get(handle);
    }

    static synchronized boolean isReady() {
        return ready;
    }

    /** Loads the cached images, extracting them first if needed; calls back on the main thread. */
    static void prepare(Context context, Runnable onReady) {
        Context app = context.getApplicationContext();
        synchronized (ThorAssets.class) {
            if (ready) {
                if (onReady != null) onReady.run();
                return;
            }
            if (onReady != null) waiting.add(onReady);
            if (loading) return;
            loading = true;
        }
        new Thread(() -> {
            boolean ok = false;
            try {
                File folder = new File(app.getFilesDir(), FOLDER);
                if (!complete(folder)) extract(new File(app.getFilesDir(), GAME), folder);
                SparseArray<Bitmap> loaded = new SparseArray<>();
                for (int handle : HANDLES) {
                    Bitmap bitmap = BitmapFactory.decodeFile(new File(folder, handle + ".png").getPath());
                    if (bitmap != null) loaded.put(handle, bitmap);
                }
                synchronized (ThorAssets.class) {
                    for (int i = 0; i < loaded.size(); ++i) images.put(loaded.keyAt(i), loaded.valueAt(i));
                }
                ok = loaded.size() == HANDLES.length;
            } catch (IOException | RuntimeException error) {
                Log.w("ThorAssets", "Interface art unavailable; using drawn fallbacks", error);
            } finally {
                synchronized (ThorAssets.class) {
                    loading = false;
                    ready = ok;
                }
            }
            java.util.List<Runnable> callbacks;
            synchronized (ThorAssets.class) {
                callbacks = new java.util.ArrayList<>(waiting);
                waiting.clear();
            }
            Handler main = new Handler(Looper.getMainLooper());
            for (Runnable callback : callbacks) main.post(callback);
        }, "fnaf3-ui-art").start();
    }

    private static boolean complete(File folder) {
        for (int handle : HANDLES) if (!new File(folder, handle + ".png").isFile()) return false;
        return true;
    }

    private static void extract(File game, File folder) throws IOException {
        if (!game.isFile() || game.length() != GAME_LENGTH) throw new IOException("Steam executable not imported");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Cannot create art folder");
        java.util.HashSet<Integer> wanted = new java.util.HashSet<>();
        for (int handle : HANDLES) wanted.add(handle);
        try (RandomAccessFile input = new RandomAccessFile(game, "r")) {
            input.seek(BANK);
            if (u16(input) != 0x6666 || u16(input) != 0 || le32(input) != BANK_SIZE || le32(input) != IMAGE_COUNT)
                throw new IOException("Unexpected image bank");
            for (int i = 0; i < IMAGE_COUNT; ++i) {
                int handle = le32(input), raw = le32(input), packed = le32(input);
                if (raw < 32 || raw > 64 << 20 || packed < 1 || packed > input.length() - input.getFilePointer())
                    throw new IOException("Invalid image record");
                if (!wanted.contains(handle)) {
                    input.seek(input.getFilePointer() + packed);
                    continue;
                }
                byte[] compressed = new byte[packed];
                input.readFully(compressed);
                write(decode(inflate(compressed, raw)), new File(folder, handle + ".png"));
            }
            if (input.getFilePointer() != BANK_END) throw new IOException("Image bank did not end correctly");
        }
    }

    private static byte[] inflate(byte[] compressed, int raw) throws IOException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            byte[] data = new byte[raw];
            int length = inflater.inflate(data);
            if (length != raw || !inflater.finished()) throw new IOException("Incomplete image record");
            return data;
        } catch (DataFormatException error) {
            throw new IOException("Corrupt image record", error);
        } finally {
            inflater.end();
        }
    }

    /** 32-byte header, BGR rows padded to an even width, then an optional alpha plane or a colour key. */
    private static Bitmap decode(byte[] data) throws IOException {
        int w = u16(data, 12), h = u16(data, 14), flags = data[17] & 255;
        if (w < 1 || h < 1 || w > 4096 || h > 4096 || data[16] != 4 || flags != 0 && flags != 16)
            throw new IOException("Unsupported image format");
        int colorStride = (w + (w & 1)) * 3, alphaStride = (w + 3) & ~3;
        int alphaStart = 32 + colorStride * h;
        if (data.length != alphaStart + ((flags & 16) != 0 ? alphaStride * h : 0)) throw new IOException("Invalid image layout");
        int[] pixels = new int[w * h];
        int keyR = data[28] & 255, keyG = data[29] & 255, keyB = data[30] & 255;
        for (int y = 0; y < h; ++y) {
            for (int x = 0; x < w; ++x) {
                int src = 32 + y * colorStride + x * 3;
                int b = data[src] & 255, g = data[src + 1] & 255, r = data[src + 2] & 255;
                int a = (flags & 16) != 0 ? data[alphaStart + y * alphaStride + x] & 255
                        : r == keyR && g == keyG && b == keyB ? 0 : 255;
                pixels[y * w + x] = a << 24 | r << 16 | g << 8 | b;
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888);
    }

    private static void write(Bitmap bitmap, File file) throws IOException {
        AtomicFile target = new AtomicFile(file);
        FileOutputStream output = target.startWrite();
        try {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IOException("Cannot store image");
            target.finishWrite(output);
        } catch (IOException error) {
            target.failWrite(output);
            throw error;
        } finally {
            bitmap.recycle();
        }
    }

    private static int u16(byte[] b, int p) {
        return (b[p] & 255) | (b[p + 1] & 255) << 8;
    }

    private static int u16(RandomAccessFile in) throws IOException {
        int lo = in.read(), hi = in.read();
        if ((lo | hi) < 0) throw new IOException("Unexpected end of file");
        return lo | hi << 8;
    }

    private static int le32(RandomAccessFile in) throws IOException {
        return Integer.reverseBytes(in.readInt());
    }
}
