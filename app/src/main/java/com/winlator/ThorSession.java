package com.winlator;

/**
 * What the boot activity should do after a game session closes. The game activity sets a request
 * before it finishes; the boot activity consumes it when it resumes.
 */
final class ThorSession {
    enum Next { NONE, EXIT, RESTART, ARCADE, SETUP }

    private static Next next = Next.NONE;
    private static ThorArcade arcade;

    private ThorSession() {}

    static synchronized void request(Next value) {
        next = value;
        arcade = null;
    }

    static synchronized void requestArcade(ThorArcade stage) {
        next = Next.ARCADE;
        arcade = stage;
    }

    static synchronized Next peek() {
        return next;
    }

    static synchronized ThorArcade arcade() {
        return arcade;
    }

    static synchronized Next take() {
        Next value = next;
        next = Next.NONE;
        return value;
    }
}
