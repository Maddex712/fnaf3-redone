package com.winlator;

import android.content.ContentResolver;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;

final class ThorDocumentRead {
    private final CancellationSignal signal = new CancellationSignal();
    private volatile boolean cancelled;
    private InputStream stream;

    InputStream open(ContentResolver resolver, Uri source) throws IOException {
        check();
        AssetFileDescriptor descriptor = resolver.openAssetFileDescriptor(source, "r", signal);
        if (descriptor == null) throw new IOException("Unable to read the selected file.");
        InputStream input;
        try {
            input = descriptor.createInputStream();
        } catch (IOException error) {
            descriptor.close();
            throw error;
        }
        synchronized (this) {
            if (!cancelled) {
                stream = input;
                return input;
            }
        }
        input.close();
        throw new InterruptedIOException("File selection cancelled.");
    }

    void check() {
        if (cancelled) throw new OperationCanceledException();
    }

    void cancel() {
        InputStream input;
        synchronized (this) {
            if (cancelled) return;
            cancelled = true;
            input = stream;
        }
        new Thread(() -> {
            signal.cancel();
            if (input != null) {
                try {
                    input.close();
                } catch (IOException ignored) {
                }
            }
        }, "thor-cancel-file").start();
    }
}

