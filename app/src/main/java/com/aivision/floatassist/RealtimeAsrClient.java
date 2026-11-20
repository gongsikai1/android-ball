package com.aivision.floatassist;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.net.URI;
import java.util.Base64;
import java.util.LinkedList;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * 百炼 Qwen-ASR-Realtime 流式转写客户端（WebSocket，OpenAI Realtime 风格协议）。
 * 音频按 100ms 一帧发送；服务端回 conversation.item.input_audio_transcription.text
 * （实时草稿：text + stash）与 .completed（整句定稿）。
 */
public class RealtimeAsrClient {

    public interface Listener {
        void onReady();

        /** 实时草稿（会说边出字），lang 为 zh/yue/en... */
        void onPartial(String text, String lang);

        /** 整句定稿 */
        void onFinal(String transcript, String lang);

        void onError(String message);

        void onClosed();
    }

    private static final String MODEL = "qwen3-asr-flash-realtime";
    private static final int MAX_PENDING_FRAMES = 40;

    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinkedList<byte[]> pending = new LinkedList<>();

    private WebSocket socket;
    private volatile boolean ready;
    private volatile boolean closed;
    private volatile boolean finished;

    public RealtimeAsrClient(Listener listener) {
        this.listener = listener;
    }

    public static String endpoint(android.content.Context ctx) {
        String base = Prefs.baseUrl(ctx);
        String host;
        try {
            URI uri = URI.create(base);
            host = uri.getHost();
        } catch (Exception e) {
            host = null;
        }
        if (host == null || host.isEmpty()) host = "dashscope.aliyuncs.com";
        // 百炼/千问域名都支持同一路径；DeepSeek 等非阿里地址回退默认百炼域名
        if (!host.contains("aliyuncs.com") && !host.contains("qianwenaiapi.com")) {
            host = "dashscope.aliyuncs.com";
        }
        return "wss://" + host + "/api-ws/v1/realtime?model=" + MODEL;
    }

    public void connect(String apiKey) {
        connect(apiKey, null);
    }

    public void connect(String apiKey, String url) {
        if (url == null || url.isEmpty()) url = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime?model=" + MODEL;
        OkHttpClient client = new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build();
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + apiKey)
                .header("OpenAI-Beta", "realtime=v1")
                .header("user-agent", "AIFloatAssistant/1.6")
                .build();
        socket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                sendSessionUpdate(webSocket);
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                handleEvent(text);
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                String msg = t == null ? "连接失败" : t.getMessage();
                if (response != null && response.code() >= 400) {
                    msg = "HTTP " + response.code() + "（检查 Key、模型是否开通）";
                }
                if (!closed) {
                    final String m = msg;
                    main.post(() -> listener.onError(m));
                }
                closed = true;
                main.post(listener::onClosed);
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                if (!closed) {
                    closed = true;
                    if (!finished) main.post(listener::onClosed);
                }
            }
        });
    }

    private void sendSessionUpdate(WebSocket ws) {
        try {
            JSONObject session = new JSONObject()
                    .put("modalities", new org.json.JSONArray().put("text"))
                    .put("input_audio_format", "pcm")
                    .put("sample_rate", 16000)
                    .put("turn_detection", new JSONObject()
                            .put("type", "server_vad")
                            .put("threshold", 0.0)
                            .put("silence_duration_ms", 400));
            JSONObject event = new JSONObject()
                    .put("event_id", "event_" + System.nanoTime())
                    .put("type", "session.update")
                    .put("session", session);
            ws.send(event.toString());
        } catch (Exception e) {
            final String msg = "会话配置失败：" + e.getMessage();
            main.post(() -> listener.onError(msg));
        }
    }

    private void handleEvent(String raw) {
        try {
            JSONObject data = new JSONObject(raw);
            String type = data.optString("type");
            if ("session.updated".equals(type)) {
                ready = true;
                flushPending();
                main.post(listener::onReady);
                return;
            }
            if ("conversation.item.input_audio_transcription.text".equals(type)) {
                String draft = (data.optString("text") + data.optString("stash")).trim();
                final String lang = data.optString("language", "");
                if (!draft.isEmpty()) main.post(() -> listener.onPartial(draft, lang));
                return;
            }
            if ("conversation.item.input_audio_transcription.completed".equals(type)) {
                final String transcript = data.optString("transcript").trim();
                final String lang = data.optString("language", "");
                if (!transcript.isEmpty()) main.post(() -> listener.onFinal(transcript, lang));
                return;
            }
            if ("conversation.item.input_audio_transcription.failed".equals(type)) {
                JSONObject err = data.optJSONObject("error");
                final String msg = err == null ? "转写失败" : err.optString("message", "转写失败");
                main.post(() -> listener.onError(msg));
                return;
            }
            if ("error".equals(type)) {
                JSONObject err = data.optJSONObject("error");
                final String msg = err == null ? "实时识别报错" : err.optString("message", "实时识别报错");
                main.post(() -> listener.onError(msg));
                return;
            }
            if ("session.finished".equals(type)) {
                finished = true;
                if (socket != null) socket.close(1000, "done");
            }
        } catch (Exception ignored) {
        }
    }

    /** 供录音线程调用：一帧 100ms PCM16 */
    public void feed(byte[] frame) {
        if (closed) return;
        if (!ready) {
            synchronized (pending) {
                if (pending.size() >= MAX_PENDING_FRAMES) pending.removeFirst();
                pending.add(frame);
            }
            return;
        }
        sendFrame(frame);
    }

    private void flushPending() {
        LinkedList<byte[]> copy;
        synchronized (pending) {
            copy = new LinkedList<>(pending);
            pending.clear();
        }
        for (byte[] f : copy) sendFrame(f);
    }

    private void sendFrame(byte[] frame) {
        WebSocket ws = socket;
        if (ws == null) return;
        try {
            JSONObject event = new JSONObject()
                    .put("event_id", "event_" + System.nanoTime())
                    .put("type", "input_audio_buffer.append")
                    .put("audio", Base64.getEncoder().encodeToString(frame));
            ws.send(event.toString());
        } catch (Exception ignored) {
        }
    }

    public boolean isReady() {
        return ready;
    }

    /** 结束会话：先发 session.finish，服务端把最后一句定稿后再关 */
    public void finish() {
        WebSocket ws = socket;
        if (ws == null) return;
        try {
            ws.send(new JSONObject()
                    .put("event_id", "event_" + System.nanoTime())
                    .put("type", "session.finish")
                    .toString());
        } catch (Exception ignored) {
        }
    }

    public void close() {
        closed = true;
        WebSocket ws = socket;
        if (ws != null) {
            try {
                ws.close(1000, "stop");
            } catch (Exception ignored) {
            }
        }
        socket = null;
    }
}
