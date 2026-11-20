package com.aivision.floatassist;

import android.content.Context;
import android.content.SharedPreferences;

/** 本机配置存储（API Key 只保存在本机） */
public final class Prefs {
    private static final String NAME = "ai_float_assistant";

    private Prefs() {
    }

    public static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static String key(Context c) {
        return sp(c).getString("api_key", "");
    }

    public static String baseUrl(Context c) {
        String v = sp(c).getString("base_url", "");
        return v == null || v.trim().isEmpty() ? "https://api.deepseek.com" : v.trim();
    }

    public static String model(Context c) {
        String v = sp(c).getString("model", "");
        return v == null || v.trim().isEmpty() ? "deepseek-chat" : v.trim();
    }

    public static String visionModel(Context c) {
        String v = sp(c).getString("vision_model", "");
        return v == null ? "" : v.trim();
    }

    public static float buttonAlpha(Context c) {
        int v = sp(c).getInt("alpha", 85);
        if (v < 25) v = 25;
        return v / 100f;
    }

    public static int buttonX(Context c) {
        return sp(c).getInt("btn_x", -1);
    }

    public static int buttonY(Context c) {
        return sp(c).getInt("btn_y", -1);
    }

    public static void saveButtonPos(Context c, int x, int y) {
        sp(c).edit().putInt("btn_x", x).putInt("btn_y", y).apply();
    }


    // ---------------- 同声传译 ----------------

    public static boolean subtitleWs(Context c) {
        return sp(c).getBoolean("sub_ws", true);
    }

    /** 非 WebSocket 分块模式的切段间隔（毫秒） */
    public static int subtitleChunkMs(Context c) {
        int v = sp(c).getInt("sub_interval_ms", 1000);
        if (v < 300) v = 300;
        if (v > 10000) v = 10000;
        return v;
    }

    public static boolean subtitleOverlap(Context c) {
        return sp(c).getBoolean("sub_overlap", true);
    }

    /** 分段重叠时长（毫秒），不超过间隔的一半 */
    public static int subtitleOverlapMs(Context c) {
        int chunk = subtitleChunkMs(c);
        int v = sp(c).getInt("sub_overlap_ms", 300);
        if (v < 0) v = 0;
        if (v > chunk / 2) v = chunk / 2;
        return v;
    }

    public static boolean running(Context c) {
        return sp(c).getBoolean("running", false);
    }

    public static void setRunning(Context c, boolean running) {
        sp(c).edit().putBoolean("running", running).apply();
    }
}
