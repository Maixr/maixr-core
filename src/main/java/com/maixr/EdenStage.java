package com.maixr;

/** 十个纪元：完整度每 100‰ 跨一档。 */
public final class EdenStage {
    public static final String[] NAMES = {
            "残响", "篝火", "耕耘", "回响", "彷徨",
            "灯影", "豁朗", "通明", "星火", "弧光"
    };

    /** 天空颜色（RGB） */
    public static final int[] SKY = {
            0x22222A, 0x2E2838, 0x3C3440, 0x4A4048, 0x585250,
            0x66685C, 0x74806A, 0x82987A, 0x9CB8A0, 0xC2E2FF
    };
    /** 雾颜色（RGB） */
    public static final int[] FOG = {
            0x181820, 0x221E2A, 0x2C2834, 0x36323C, 0x403E44,
            0x4A4A4C, 0x565E52, 0x62725A, 0x7A9468, 0x9CC484
    };
    /** 敌对生物生成压制强度 0~1，越高越少 */
    public static final double[] SPAWN_SUPPRESS = {
            0.00, 0.06, 0.12, 0.20, 0.28,
            0.36, 0.46, 0.58, 0.72, 0.92
    };

    public static int clamp(int stage) {
        if (stage < 0) return 0;
        if (stage >= NAMES.length) return NAMES.length - 1;
        return stage;
    }

    public static String name(int stage) { return NAMES[clamp(stage)]; }
    public static int sky(int stage) { return SKY[clamp(stage)]; }
    public static int fog(int stage) { return FOG[clamp(stage)]; }
    public static double suppress(int stage) { return SPAWN_SUPPRESS[clamp(stage)]; }

    private EdenStage() {}
}
