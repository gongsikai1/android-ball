package com.aivision.floatassist;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/** AI 截图库：截图保存在应用私有目录，索引存在 SharedPreferences */
public final class ShotStore {

    private static final int MAX_SHOTS = 40;

    public static final class Shot {
        public String file;
        public long ts;
        public String ocr = "";

        public JSONObject toJson() throws Exception {
            return new JSONObject().put("file", file).put("ts", ts).put("ocr", ocr);
        }

        public static Shot from(JSONObject o) {
            Shot s = new Shot();
            s.file = o.optString("file");
            s.ts = o.optLong("ts");
            s.ocr = o.optString("ocr");
            return s;
        }
    }

    private ShotStore() {
    }

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "shots");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static File fileOf(Context c, Shot s) {
        return new File(dir(c), s.file);
    }

    public static synchronized Shot add(Context c, Bitmap bmp, String ocr) {
        Shot s = new Shot();
        s.ts = System.currentTimeMillis();
        s.file = "shot_" + s.ts + ".jpg";
        s.ocr = ocr == null ? "" : ocr;
        File f = new File(dir(c), s.file);
        try (FileOutputStream out = new FileOutputStream(f)) {
            bmp.compress(Bitmap.CompressFormat.JPEG, 92, out);
        } catch (Exception ignored) {
        }
        List<Shot> list = list(c);
        list.add(0, s);
        while (list.size() > MAX_SHOTS) {
            Shot old = list.remove(list.size() - 1);
            File of = new File(dir(c), old.file);
            if (of.exists()) of.delete();
        }
        persist(c, list);
        return s;
    }

    public static synchronized void updateOcr(Context c, Shot s, String ocr) {
        if (s == null || ocr == null) return;
        List<Shot> list = list(c);
        for (Shot item : list) {
            if (item.file.equals(s.file)) {
                item.ocr = ocr;
                break;
            }
        }
        persist(c, list);
    }

    public static synchronized List<Shot> list(Context c) {
        List<Shot> out = new ArrayList<>();
        String raw = Prefs.sp(c).getString("shots_index", "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                Shot s = Shot.from(arr.getJSONObject(i));
                if (s.file != null && !s.file.isEmpty() && new File(dir(c), s.file).exists()) out.add(s);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static synchronized int count(Context c) {
        return list(c).size();
    }

    public static synchronized void delete(Context c, Shot s) {
        List<Shot> list = list(c);
        list.remove(s);
        File f = new File(dir(c), s.file);
        if (f.exists()) f.delete();
        persist(c, list);
    }

    private static void persist(Context c, List<Shot> list) {
        JSONArray arr = new JSONArray();
        for (Shot s : list) {
            try {
                arr.put(s.toJson());
            } catch (Exception ignored) {
            }
        }
        Prefs.sp(c).edit().putString("shots_index", arr.toString()).apply();
    }

    public static String describe(Context c, Shot s) {
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA);
        String head = fmt.format(new java.util.Date(s.ts));
        if (s.ocr == null || s.ocr.trim().isEmpty()) return head + " · 暂无文字";
        String one = s.ocr.replaceAll("\\s+", " ").trim();
        if (one.length() > 28) one = one.substring(0, 28) + "…";
        return head + " · " + one;
    }
}
