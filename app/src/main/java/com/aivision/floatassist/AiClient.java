package com.aivision.floatassist;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** DeepSeek（OpenAI 兼容）接口客户端 */
public final class AiClient {

    public enum Mode {ASK, TRANSLATE}

    public interface Cb {
        void onOk(String text);

        void onError(String message);
    }

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    public static final String ASR_MODEL = "qwen3-asr-flash";

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .build();

    private AiClient() {
    }

    public static void test(Context ctx, Cb cb) {
        String key = Prefs.key(ctx);
        if (key.isEmpty()) {
            cb.onError("还没有填写 API Key");
            return;
        }
        new Thread(() -> {
            try {
                String body = new JSONObject()
                        .put("model", Prefs.model(ctx))
                        .put("messages", new JSONArray()
                                .put(new JSONObject().put("role", "user").put("content", "只回复两个字：正常"))
                        )
                        .put("max_tokens", 32)
                        .toString();
                String text = call(ctx, body, key);
                cb.onOk(text);
            } catch (Exception e) {
                cb.onError(readable(e));
            }
        }, "ai-test").start();
    }

    /**
     * @param shot     截图（可为空）
     * @param ocrText  本地 OCR 文字（可为空）
     * @param question 用户问题（翻译模式忽略）
     */
    public static void ask(Context ctx, Bitmap shot, String ocrText, String question, Mode mode, Cb cb) {
        String key = Prefs.key(ctx);
        if (key.isEmpty()) {
            cb.onError("还没有填写 DeepSeek API Key，请回到 App 里配置");
            return;
        }
        final String ocr = ocrText == null ? "" : ocrText.trim();
        new Thread(() -> {
            try {
                boolean useVision = !Prefs.visionModel(ctx).isEmpty() && shot != null;
                String model = useVision ? Prefs.visionModel(ctx) : Prefs.model(ctx);

                if (mode == Mode.TRANSLATE) {
                    String target = detectTarget(ocr);
                    String system = translatePrompt(target);
                    String userText = ocr.isEmpty()
                            ? "把这张截图里的所有文字翻译成" + target + "，只输出译文。"
                            : "把下面这段文字翻译成" + target + "，只输出译文：\n\n" + trim(ocr, 3500);

                    String result = clean(call(ctx, body(model, system, userText, useVision, shot, 0.1f, 3500), key));
                    if (isEcho(ocr, result) || ("简体中文".equals(target) && ocr.length() > 20 && !hasCjk(result))) {
                        String strict = userText + "\n\n再次强调：必须把内容翻译成" + target + "，禁止照抄原文，不要输出原文对照，只输出译文。";
                        result = clean(call(ctx, body(model, system, strict, useVision, shot, 0.0f, 3500), key));
                    }
                    boolean missingTarget = "简体中文".equals(target) ? (countLatin(ocr) > 30 && !hasCjk(result))
                            : (countCjk(ocr) > 20 && !hasLatin(result));
                    if (isEcho(ocr, result) || result.trim().isEmpty() || missingTarget) {
                        cb.onOk("这次没有拿到译文（模型返回了原文或空内容）。\n可以再点一次三击重试；如果一直这样，请在配置页换一个模型或检查接口余额。\n\n原文：\n" + (result.trim().isEmpty() ? trim(ocr, 1200) : result));
                        return;
                    }
                    cb.onOk(result);
                    return;
                }

                String system = askPrompt();
                String text = (question == null || question.trim().isEmpty())
                        ? "请描述并解释这张截图的内容。" : question.trim();
                String ocrBlock = ocr.isEmpty() ? "" : "\n\n【本地 OCR 识别到的文字，可能有错漏】\n" + trim(ocr, 4000);
                String userText = text + ocrBlock;
                String result = clean(call(ctx, body(model, system, userText, useVision, shot, 0.4f, 1200), key));
                cb.onOk(result);
            } catch (Exception e) {
                cb.onError(readable(e));
            }
        }, "ai-ask").start();
    }

    /** 目标语言：中文内容译成英文，其他语言译成简体中文 */
    public static String detectTarget(String text) {
        int cjk = 0, latin = 0;
        if (text != null) {
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c >= 0x4E00 && c <= 0x9FFF) cjk++;
                else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) latin++;
            }
        }
        if (cjk == 0) return "简体中文";
        return cjk * 2 >= latin ? "英文" : "简体中文";
    }

    private static String body(String model, String system, String userText, boolean useVision, Bitmap shot,
                               float temperature, int maxTokens) throws Exception {
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", system));
        JSONObject user = new JSONObject().put("role", "user");
        if (useVision) {
            JSONArray content = new JSONArray();
            content.put(new JSONObject().put("type", "text").put("text", userText));
            content.put(new JSONObject().put("type", "image_url")
                    .put("image_url", new JSONObject().put("url", dataUrl(shot))));
            user.put("content", content);
        } else {
            user.put("content", userText);
        }
        messages.put(user);
        return new JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("temperature", temperature)
                .put("max_tokens", maxTokens)
                .toString();
    }

    /** 模型是否只是把原文照抄了一遍 */
    private static boolean isEcho(String source, String result) {
        if (source == null || result == null) return false;
        String a = source.replaceAll("\\s+", "").toLowerCase();
        String b = result.replaceAll("\\s+", "").toLowerCase();
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.equals(b)) return true;
        if (a.length() >= 30) {
            String head = a.substring(0, 30);
            return b.contains(head) && b.length() <= a.length() * 1.4;
        }
        return false;
    }

    private static int countLatin(String s) {
        if (s == null) return 0;
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) count++;
        }
        return count;
    }

    private static boolean hasLatin(String s) {
        return countLatin(s) > 0;
    }

    private static int countCjk(String s) {
        if (s == null) return 0;
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) count++;
        }
        return count;
    }

    private static boolean hasCjk(String s) {
        if (s == null) return false;
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) {
                count++;
                if (count >= 5) return true;
            }
        }
        return false;
    }

    private static String clean(String s) {
        if (s == null) return "";
        String out = s.trim();
        if (out.startsWith("```")) {
            int nl = out.indexOf('\n');
            if (nl > 0) out = out.substring(nl + 1);
            if (out.endsWith("```")) out = out.substring(0, out.length() - 3);
        }
        out = out.replaceAll("(?i)^(以下是)?(译文|翻译|translation)\\s*[:：]\\s*", "");
        if ((out.startsWith("\u201c") && out.endsWith("\u201d")) || (out.startsWith("\"") && out.endsWith("\""))) {
            out = out.substring(1, out.length() - 1);
        }
        return out.trim();
    }

    /** 屏幕声音转写：千问 qwen3-asr-flash（OpenAI 兼容 chat/completions + input_audio） */
    public static void transcribe(Context ctx, File wav, Cb cb) {
        String key = Prefs.key(ctx);
        if (key.isEmpty()) {
            cb.onError("还没有填写 API Key，请回到 App 里配置");
            return;
        }
        if (wav == null || !wav.exists()) {
            cb.onError("没有录到有效声音");
            return;
        }
        new Thread(() -> {
            try {
                long size = wav.length();
                if (size > 9L * 1024 * 1024) {
                    cb.onError("录音太长，无法一次转写（建议控制在 3 分钟内）");
                    return;
                }
                byte[] bytes = new byte[(int) size];
                try (FileInputStream in = new FileInputStream(wav)) {
                    int off = 0;
                    while (off < bytes.length) {
                        int r = in.read(bytes, off, bytes.length - off);
                        if (r < 0) break;
                        off += r;
                    }
                }
                String b64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
                JSONArray content = new JSONArray();
                content.put(new JSONObject().put("type", "input_audio")
                        .put("input_audio", new JSONObject().put("data", "data:audio/wav;base64," + b64)));
                JSONArray messages = new JSONArray();
                messages.put(new JSONObject().put("role", "user").put("content", content));
                JSONObject body = new JSONObject()
                        .put("model", ASR_MODEL)
                        .put("messages", messages)
                        .put("stream", false)
                        .put("asr_options", new JSONObject().put("enable_itn", false));
                cb.onOk(clean(call(ctx, body.toString(), key)));
            } catch (Exception e) {
                if (e instanceof ApiException && ((ApiException) e).code == 404) {
                    cb.onError("当前接口不支持语音识别：需要百炼（qwen3-asr-flash）等支持 ASR 的接口");
                } else {
                    cb.onError(readable(e));
                }
            }
        }, "ai-asr").start();
    }

    /**
     * 总结「听到」或「看到 + 听到」的内容。
     *
     * @param shots      期间截取的屏幕画面（可为空）
     * @param transcript 声音转写（可为空）
     * @param screenText 最后一张截图的 OCR 文字（可为空，用于无视觉模型时兜底）
     */
    public static void summarize(Context ctx, List<Bitmap> shots, String transcript, String screenText, Cb cb) {
        String key = Prefs.key(ctx);
        if (key.isEmpty()) {
            cb.onError("还没有填写 API Key，请回到 App 里配置");
            return;
        }
        final List<Bitmap> images = new ArrayList<>();
        if (shots != null) {
            for (Bitmap b : shots) if (b != null) images.add(b);
        }
        final boolean hasImages = !images.isEmpty();
        final boolean useVision = hasImages && !Prefs.visionModel(ctx).isEmpty();
        new Thread(() -> {
            try {
                StringBuilder user = new StringBuilder();
                if (hasImages) {
                    user.append("这次同时记录了手机屏幕画面和声音，");
                    user.append(useVision ? "按时间顺序附上 " + Math.min(images.size(), 4) + " 张屏幕截图，" : "");
                    user.append("以及这段时间的屏幕声音转写。\n");
                }
                user.append("\n【声音转写】\n");
                if (transcript == null || transcript.trim().isEmpty()) {
                    user.append("（没有识别到语音内容）");
                } else {
                    user.append(trim(transcript.trim(), 6000));
                }
                if (screenText != null && !screenText.trim().isEmpty()) {
                    user.append("\n\n【最后一张截图中的文字】\n").append(trim(screenText.trim(), 2500));
                }
                user.append("\n\n").append(hasImages
                        ? "请按要求的格式总结画面与声音的内容。"
                        : "请按要求的格式总结这段声音的内容。");

                JSONArray messages = new JSONArray();
                messages.put(new JSONObject().put("role", "system").put("content", hasImages ? seePrompt() : hearPrompt()));
                JSONObject msg = new JSONObject().put("role", "user");
                if (useVision) {
                    JSONArray content = new JSONArray();
                    content.put(new JSONObject().put("type", "text").put("text", user.toString()));
                    int count = 0;
                    for (Bitmap b : images) {
                        if (count++ >= 4) break;
                        content.put(new JSONObject().put("type", "image_url")
                                .put("image_url", new JSONObject().put("url", dataUrl(b, 1024))));
                    }
                    msg.put("content", content);
                } else {
                    msg.put("content", user.toString());
                }
                messages.put(msg);

                String body = new JSONObject()
                        .put("model", useVision ? Prefs.visionModel(ctx) : Prefs.model(ctx))
                        .put("messages", messages)
                        .put("temperature", 0.3)
                        .put("max_tokens", 1200)
                        .toString();
                cb.onOk(clean(call(ctx, body, key)));
            } catch (Exception e) {
                cb.onError(readable(e));
            }
        }, "ai-summary").start();
    }

    /** 按时间轴总结「看 + 听」内容：截图 + 与其一一对应的时间段转写 */
    public static void summarizeTimeline(Context ctx, List<Bitmap> shots, String timeline, Cb cb) {
        String key = Prefs.key(ctx);
        if (key.isEmpty()) {
            cb.onError("还没有填写 API Key，请回到 App 里配置");
            return;
        }
        final List<Bitmap> images = new ArrayList<>();
        if (shots != null) for (Bitmap b : shots) if (b != null) images.add(b);
        final boolean useVision = !images.isEmpty() && !Prefs.visionModel(ctx).isEmpty();
        new Thread(() -> {
            try {
                StringBuilder user = new StringBuilder();
                user.append("这是这次「看 + 听」记录的时间轴：截图按时间顺序编号为图1、图2……，")
                        .append("每个时间段下面的「声音」是该时段的语音转写。\n\n")
                        .append(timeline)
                        .append("\n\n请按要求总结。");
                JSONArray messages = new JSONArray();
                messages.put(new JSONObject().put("role", "system").put("content", timelinePrompt()));
                JSONObject msg = new JSONObject().put("role", "user");
                if (useVision) {
                    JSONArray content = new JSONArray();
                    content.put(new JSONObject().put("type", "text").put("text", user.toString()));
                    int count = 0;
                    for (Bitmap b : images) {
                        if (count++ >= 8) break;
                        content.put(new JSONObject().put("type", "image_url")
                                .put("image_url", new JSONObject().put("url", dataUrl(b, 1024))));
                    }
                    msg.put("content", content);
                } else {
                    msg.put("content", user.toString());
                }
                messages.put(msg);
                String body = new JSONObject()
                        .put("model", useVision ? Prefs.visionModel(ctx) : Prefs.model(ctx))
                        .put("messages", messages)
                        .put("temperature", 0.3)
                        .put("max_tokens", 1400)
                        .toString();
                cb.onOk(clean(call(ctx, body, key)));
            } catch (Exception e) {
                cb.onError(readable(e));
            }
        }, "ai-timeline").start();
    }

    private static String call(Context ctx, String body, String key) throws Exception {
        String url = endpoint(Prefs.baseUrl(ctx));
        Request req = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + key)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(body, JSON))
                .build();
        try (Response resp = HTTP.newCall(req).execute()) {
            String raw = resp.body() == null ? "" : resp.body().string();
            if (!resp.isSuccessful()) {
                throw new ApiException(resp.code(), raw);
            }
            JSONObject obj = new JSONObject(raw);
            JSONArray choices = obj.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                throw new ApiException(500, raw);
            }
            Object content = choices.getJSONObject(0).optJSONObject("message").opt("content");
            if (content instanceof JSONArray) {
                StringBuilder sb = new StringBuilder();
                JSONArray arr = (JSONArray) content;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject part = arr.optJSONObject(i);
                    if (part != null && "text".equals(part.optString("type"))) sb.append(part.optString("text"));
                }
                return sb.toString();
            }
            return String.valueOf(content);
        }
    }

    private static String endpoint(String base) {
        String b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        if (b.endsWith("/chat/completions")) return b;
        return b + "/chat/completions";
    }

    private static String dataUrl(Bitmap bmp) {
        return dataUrl(bmp, 1280);
    }

    private static String dataUrl(Bitmap bmp, int max) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        if (Math.max(w, h) > max) {
            float r = max / (float) Math.max(w, h);
            bmp = Bitmap.createScaledBitmap(bmp, Math.round(w * r), Math.round(h * r), true);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out);
        return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    private static String hearPrompt() {
        return "你是声音总结助手。用户录下了一段手机屏幕声音（视频、播客、会议、课程等），下面是语音转写，可能有错漏。\n"
                + "请用简体中文输出：\n"
                + "1. 一句话主题\n"
                + "2. 3-6 条要点\n"
                + "3. 结论或待办（没有就省略）\n"
                + "只依据转写内容总结，不要编造，不要重复原文。";
    }

    private static String timelinePrompt() {
        return "你是「看 + 听」视频/屏幕内容总结助手。你会收到按时间顺序编号的截图（图1、图2……），以及按时间段对齐的声音转写。\n"
                + "请用简体中文输出：\n"
                + "1. 整体主题（一句话）\n"
                + "2. 按时间顺序梳理：每段一行，格式「mm:ss 画面：…；声音：…」，只写有信息的时间段\n"
                + "3. 3-5 条关键信息或结论\n"
                + "4. 待办（没有就省略）\n"
                + "要求：结合该时间段的截图与声音，不要编造画面或声音里没有的内容；转写有错漏时按上下文合理理解。";
    }

    private static String seePrompt() {
        return "你是「看 + 听」总结助手。你会收到按时间顺序排列的手机屏幕截图，以及同一段时间的屏幕声音转写。\n"
                + "请用简体中文输出：\n"
                + "1. 画面在做什么（应用或内容）\n"
                + "2. 声音在讲什么\n"
                + "3. 两者结合后的 3-6 条关键信息\n"
                + "4. 结论或待办（没有就省略）\n"
                + "不要编造截图里不存在的内容；看不清就说看不清。";
    }

    private static String askPrompt() {
        return "你是一个手机截图识图助手。用户会给你一张手机屏幕截图，可能是聊天记录、网页文章、表格、商品页、题目或纯图片。\n"
                + "要求：\n"
                + "1. 先结合画面内容回答问题，用简体中文，简洁直接，必要时分点。\n"
                + "2. 如果截图里有文字，优先依据文字回答；文字模糊或缺失要明确说明。\n"
                + "3. 不要编造截图中不存在的信息；信息不足时告诉用户还需要看哪里。\n"
                + "4. 回答控制在 400 字以内，不要输出「根据截图」这类套话。";
    }

    private static String translatePrompt(String target) {
        return "你是专业翻译引擎，负责把手机截图里的文字翻译成" + target + "。\n"
                + "严格要求：\n"
                + "1. 只输出译文，不要输出原文，不要做原文对照，不要解释、加标题或加引号。\n"
                + "2. 保留原文的段落、换行、列表结构，不要合并条目。\n"
                + "3. 时间、按钮、提示语这类界面文字也要翻译。\n"
                + "4. 人名、地名、专业术语使用通行译法；数字、代码、链接可保留原样。\n"
                + "5. 如果没有可翻译的文字，只回答「未识别到文字」。";
    }

    private static String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String readable(Exception e) {
        if (e instanceof ApiException) {
            ApiException ae = (ApiException) e;
            String detail = ae.body == null ? "" : ae.body;
            if (ae.code == 401) return "API Key 无效或已过期（401），请检查配置";
            if (ae.code == 402) return "账户余额不足（402），请先充值";
            if (ae.code == 429) return "请求过于频繁（429），稍后再试";
            if (ae.code == 404) return "接口地址或模型不存在（404），请检查接口地址与模型名";
            if (ae.code >= 500) return "服务端错误（" + ae.code + "），稍后再试";
            return "请求失败（" + ae.code + "）：" + trim(detail, 180);
        }
        String msg = e.getMessage();
        if (msg != null && (msg.contains("Unable to resolve host") || msg.contains("Failed to connect")
                || msg.contains("timeout") || msg.contains("Timeout"))) {
            return "网络连接失败，请检查手机网络后重试";
        }
        return "出错了：" + (msg == null ? e.getClass().getSimpleName() : trim(msg, 160));
    }

    static final class ApiException extends Exception {
        final int code;
        final String body;

        ApiException(int code, String body) {
            super("HTTP " + code);
            this.code = code;
            this.body = body;
        }
    }
}
