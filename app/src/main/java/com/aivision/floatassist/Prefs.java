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

    public static boolean running(Context c) {
        return sp(c).getBoolean("running", false);
    }

    public static void setRunning(Context c, boolean running) {
        sp(c).edit().putBoolean("running", running).apply();
    }
}
