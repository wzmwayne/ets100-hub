package com.ets.unpack.hub;

import android.content.Context;
import android.content.SharedPreferences;

/** 动画调速钩子：被构建期插桩到 PK 答题动画时长处。 */
public final class Tuning {
    private static SharedPreferences p() {
        try { return Hub.get().ctx().getSharedPreferences("etshub_tuning", Context.MODE_PRIVATE); }
        catch (Throwable t) { return null; }
    }
    public static long dur(boolean correct, long base) {
        try {
            SharedPreferences sp = p();
            if (sp != null) {
                float spd = sp.getFloat("pk_speed", 1.0f);
                if (spd == 0f) return 0L;                 // speed=0: 跳过反馈动画
                int cm = sp.getInt("pk_correct_ms", 0);
                int wm = sp.getInt("pk_wrong_ms", 0);
                if (correct && cm > 0) return cm;
                if (!correct && wm > 0) return wm;
                if (spd > 0f && spd != 1.0f) {
                    long v = (long) ((double) base / (double) spd);
                    return v < 1 ? 1 : v;
                }
            }
        } catch (Throwable ignored) {}
        return base;
    }
    public static long dur(long base) { return dur(true, base); }
    /** 仅用于“下一题”延时；speed=0 时直接为 0，不涉及倒计时。 */
    public static long delay(long base) {
        try {
            SharedPreferences sp = p();
            if (sp != null) {
                float spd = sp.getFloat("pk_speed", 1.0f);
                if (spd == 0f) return 0L;
                if (spd > 0f && spd != 1.0f) {
                    long v = (long) ((double) base / (double) spd);
                    return v < 1 ? 1 : v;
                }
            }
        } catch (Throwable ignored) {}
        return base;
    }
    public static int getCorrectMs() { try { return p().getInt("pk_correct_ms", 0); } catch (Throwable t) { return 0; } }
    public static int getWrongMs() { try { return p().getInt("pk_wrong_ms", 0); } catch (Throwable t) { return 0; } }
    public static float getSpeed() { try { return p().getFloat("pk_speed", 1.0f); } catch (Throwable t) { return 1.0f; } }
    public static String setConfig(int correctMs, int wrongMs, float speed) {
        if (correctMs < 0) correctMs = 0;
        if (wrongMs < 0) wrongMs = 0;
        if (speed < 0f) speed = 1.0f;
        try {
            SharedPreferences sp = p();
            if (sp != null) sp.edit().putInt("pk_correct_ms", correctMs).putInt("pk_wrong_ms", wrongMs).putFloat("pk_speed", speed).commit();
            return getConfig();
        } catch (Throwable t) { return "fail " + t; }
    }
    public static String getConfig() {
        try {
            SharedPreferences sp = p();
            if (sp != null) return "correct=" + sp.getInt("pk_correct_ms", 0) + "ms wrong=" + sp.getInt("pk_wrong_ms", 0)
                    + "ms speed=" + sp.getFloat("pk_speed", 1.0f) + "x";
        } catch (Throwable ignored) {}
        return "?";
    }
}
