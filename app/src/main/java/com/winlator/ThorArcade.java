package com.winlator;

enum ThorArcade {
    BALLOON("BB’s Air Adventure", 2, "Open CAM 08 and double-tap the drawing on the left wall."),
    MANGLE("Mangle’s Quest", 2,
            "On CAM 07, find the Freddy cabinet. Tap its top-left, bottom-left, top-right and bottom-right buttons in order."),
    CHICA("Chica’s Party", 3, "Tap the small gray cupcakes on CAM 02 by the doorway, CAM 03 by the right wall, CAM 04 in the left corner, and CAM 06 beside the cabinet."),
    STAGE("Stage 01", 4, "Center the 3×3 wall tiles under the window next to the box. Number them 1 to 9 from the top left, then tap 3 9 5 2 4 8."),
    SHADOW("Shadow Bonnie", 5, "Pan to the far right, then double-tap the small Shadow Bonnie figure on the desk."),
    PARTY("Happiest Day", 5, "On CAM 03, double-tap the Puppet drawing on the right wall.");

    final String title;
    final int night;
    final String instructions;

    ThorArcade(String title, int night, String instructions) {
        this.title = title;
        this.night = night;
        this.instructions = instructions;
    }

    String profileName() {
        return night == 2 ? "FNaF 3 Arcade" : "FNaF 3 Arcade · Night " + night;
    }

    static boolean isPracticeProfile(String name) {
        for (ThorArcade stage : values()) if (stage.profileName().equals(name)) return true;
        return false;
    }

    static ThorArcade parse(String value) {
        if (value == null) return null;
        for (ThorArcade stage : values()) if (stage.name().equals(value)) return stage;
        return null;
    }
}

