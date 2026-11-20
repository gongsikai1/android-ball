package com.aivision.floatassist;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 实时屏幕声音双语字幕。
 * 优先走百炼 Qwen-ASR-Realtime 流式识别（边说边出字，句末定稿），
 * Key/模型不可用时自动回退到 1 秒分块的 qwen3-asr-flash。
 * 中文语音：上中文、下英文；英文语音：上中文译文、下英文原文。
 */
public class LiveSubtitleEngine {

    public interface Listener {
        /** 实时草稿：固定中文在上、英文在下，未出的那行显示占位 */
        void onPartial(String zh, String en);

        /** 一句定稿，等待翻译 */
        void onFinal(String zh, String en);

        /** 中英两行都就绪 */
        void onTranslated(String zh, String en);

        /** 模式提示（如回退到分块识别） */
        void onMode(String notice);

        void onError(String message);
    }

    private static final int MODE_CONNECTING = 0;
    private static final int MODE_STREAM = 1;
    private static final int MODE_CHUNK = 2;

    private static final int RATE = 16000;
    private static final int FRAME_BYTES = 3200; // 100ms @16k mono 16bit
    private static final int BYTES_PER_MS = RATE * 2 / 1000; // 32 bytes/ms

    private boolean wsEnabled = true;
    private int chunkMs = 1000;
    private int overlapBytes = 300 * BYTES_PER_MS;
    private int minChunkBytes = 500 * BYTES_PER_MS;

    private final android.content.Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile int mode = MODE_CONNECTING;
    private volatile boolean running;
    private volatile boolean recording;
    private RealtimeAsrClient stream;
    private final Runnable connectTimeout = this::onConnectTimeout;

    private AudioRecord record;
    private Thread recThread;

    private ThreadPoolExecutor asrPool;
    private ThreadPoolExecutor translatePool;

    // ---- 字幕显示协调（主线程访问）----
    private int sentenceId = 0;
    private boolean translating = false;
    private String pendingDraft = "";
    private int pendingDraftKind = LANG_ZH;
    private boolean hasPendingDraft = false;
    private String lastContext = "";

    private final AtomicInteger seqGen = new AtomicInteger(0);
    private final AtomicInteger failStreak = new AtomicInteger(0);
    private final AtomicInteger okCount = new AtomicInteger(0);
    private volatile String lastError = "";
    private volatile boolean errorReported = false;
    private volatile int lastChunkShown = 0;

    public LiveSubtitleEngine(android.content.Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public boolean isRunning() {
        return running;
    }

    public boolean start(MediaProjection projection) {
        if (Build.VERSION.SDK_INT < 29 || projection == null) return false;
        try {
            AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build();
            AudioFormat format = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build();
            int minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            record = new AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(Math.max(minBuf * 4, 8192))
                    .setAudioPlaybackCaptureConfig(config)
                    .build();
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                record.release();
                record = null;
                return false;
            }

            // 回退用的分块管线
            asrPool = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(2), (r, executor) -> {
                        if (!executor.isShutdown()) {
                            executor.getQueue().poll();
                            try {
                                executor.execute(r);
                            } catch (Exception ignored) {
                            }
                        }
                    });
            translatePool = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(3), (r, executor) -> {
                        if (!executor.isShutdown()) {
                            executor.getQueue().poll();
                            try {
                                executor.execute(r);
                            } catch (Exception ignored) {
                            }
                        }
                    });

            running = true;
            recording = true;
            mode = MODE_CONNECTING;
            record.startRecording();
            recThread = new Thread(this::recordLoop, "subtitle-rec");
            recThread.start();
            connectStream();
            return true;
        } catch (Exception e) {
            running = false;
            release();
            return false;
        }
    }

    // ---------------- 流式识别 ----------------

    private void connectStream() {
        String key = Prefs.key(context);
        if (key.isEmpty()) {
            switchToChunk("未配置 API Key，使用分块识别");
            return;
        }
        stream = new RealtimeAsrClient(new RealtimeAsrClient.Listener() {
            @Override
            public void onReady() {
                main.post(() -> {
                    if (!running || mode != MODE_CONNECTING) return;
                    mode = MODE_STREAM;
                    main.removeCallbacks(connectTimeout);
                });
            }

            @Override
            public void onPartial(String text, String lang) {
                main.post(() -> handleDraft(text, langKind(lang, text)));
            }

            @Override
            public void onFinal(String transcript, String lang) {
                main.post(() -> handleFinal(transcript, langKind(lang, transcript), lang));
            }

            @Override
            public void onError(String message) {
                main.post(() -> {
                    if (!running) return;
                    if (mode == MODE_CONNECTING) {
                        switchToChunk("实时识别不可用：" + message);
                    } else if (mode == MODE_STREAM) {
                        switchToChunk("实时识别中断：" + message);
                    }
                });
            }

            @Override
            public void onClosed() {
                main.post(() -> {
                    if (!running) return;
                    if (mode == MODE_STREAM) switchToChunk("实时识别断开，已切换分块模式");
                });
            }
        });
        main.postDelayed(connectTimeout, 4500);
        stream.connect(key, RealtimeAsrClient.endpoint(context));
    }

    private void onConnectTimeout() {
        if (!running) return;
        if (mode == MODE_CONNECTING) switchToChunk("实时识别连接超时，已切换分块模式");
    }

    /** 流式不可用：改走 1 秒分块，不打断字幕 */
    private void switchToChunk(String notice) {
        if (mode == MODE_CHUNK) return;
        mode = MODE_CHUNK;
        main.removeCallbacks(connectTimeout);
        final RealtimeAsrClient s = stream;
        stream = null;
        if (s != null) new Thread(s::close, "asr-close").start();
        listener.onMode(notice);
    }

    private static final int LANG_ZH = 0;
    private static final int LANG_EN = 1;
    private static final int LANG_OTHER = 2;

    /** 判断这句话是中文、英文还是其他语言/方言 */
    private int langKind(String lang, String text) {
        if (lang != null && !lang.isEmpty()) {
            if (lang.startsWith("zh") || "yue".equals(lang)) return LANG_ZH;
            if ("en".equals(lang)) return LANG_EN;
            return LANG_OTHER;
        }
        int cjk = 0, latin = 0;
        if (text != null) {
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c >= 0x4E00 && c <= 0x9FFF) cjk++;
                else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) latin++;
            }
        }
        if (cjk > 0 && cjk * 2 >= latin) return LANG_ZH;
        if (latin > 0) return LANG_EN;
        return LANG_OTHER;
    }

    // ---------------- 录音 ----------------

    private void recordLoop() {
        short[] buf = new short[1024];
        java.nio.ByteBuffer bytes = java.nio.ByteBuffer.allocate(buf.length * 2)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        ByteArrayOutputStream acc = new ByteArrayOutputStream();
        byte[] frame = new byte[FRAME_BYTES];
        int frameCount = 0;
        long chunkStart = SystemClock.elapsedRealtime();

        while (running && recording) {
            int n;
            try {
                n = record.read(buf, 0, buf.length);
            } catch (Exception e) {
                break;
            }
            if (n <= 0) continue;
            bytes.clear();
            for (int i = 0; i < n; i++) bytes.putShort(buf[i]);
            byte[] raw = bytes.array();
            int rawLen = n * 2;

            if (mode != MODE_CHUNK) {
                int off = 0;
                while (off < rawLen) {
                    int take = Math.min(FRAME_BYTES - frameCount, rawLen - off);
                    System.arraycopy(raw, off, frame, frameCount, take);
                    frameCount += take;
                    off += take;
                    if (frameCount == FRAME_BYTES) {
                        RealtimeAsrClient s = stream;
                        if (s != null) s.feed(frame.clone());
                        frameCount = 0;
                    }
                }
                continue;
            }

            // 分块模式
            frameCount = 0;
            acc.write(raw, 0, rawLen);
            if (SystemClock.elapsedRealtime() - chunkStart >= chunkMs) {
                byte[] data = acc.toByteArray();
                acc.reset();
                chunkStart = SystemClock.elapsedRealtime();
                if (overlapBytes > 0 && data.length > overlapBytes) {
                    acc.write(data, data.length - overlapBytes, overlapBytes);
                }
                if (data.length >= minChunkBytes) {
                    final int mySeq = seqGen.incrementAndGet();
                    final byte[] payload = data;
                    try {
                        asrPool.execute(() -> processChunk(mySeq, payload));
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    // ---------------- 分块识别（回退） --------------

    private void processChunk(int mySeq, byte[] pcm) {
        if (!running) return;
        File wav = writeWav(mySeq, pcm);
        if (wav == null) return;

        String text = transcribeBlocking(wav);
        wav.delete();
        if (!running) return;
        if (text == null) {
            if (failStreak.incrementAndGet() >= 2 && okCount.get() == 0) reportError(lastError);
            return;
        }
        failStreak.set(0);
        okCount.incrementAndGet();
        final String clean = normalize(text);
        if (clean.isEmpty()) return;
        if (mySeq < lastChunkShown) return;
        lastChunkShown = mySeq;

        int kind = langKind(null, clean);
        main.post(() -> handleFinal(clean, kind, null));
    }

    // ---------------- 字幕与翻译 --------------

    /** 流式草稿：边说话边出字；正在翻译时暂存，避免中英错位 */
    private void handleDraft(String text, int kind) {
        if (!running) return;
        String t = normalize(text);
        if (t.isEmpty()) return;
        if (translating) {
            pendingDraft = t;
            pendingDraftKind = kind;
            hasPendingDraft = true;
            return;
        }
        if (kind == LANG_ZH) listener.onPartial(t, "识别中…");
        else if (kind == LANG_EN) listener.onPartial("翻译中…", t);
        else listener.onPartial("识别中…", "识别中…");
    }

    /** 一句定稿：中文/英文直接上屏另一行待译；其他语言两行都翻译 */
    private void handleFinal(String text, int kind, String lang) {
        if (!running) return;
        String t = normalize(text);
        if (t.isEmpty()) return;
        sentenceId++;
        final int id = sentenceId;
        translating = true;
        hasPendingDraft = false;

        if (kind == LANG_ZH) listener.onFinal(t, "翻译中…");
        else if (kind == LANG_EN) listener.onFinal("翻译中…", t);
        else listener.onFinal("翻译中…", "翻译中…");

        final String ctx = lastContext;
        final int k = kind;
        try {
            translatePool.execute(() -> {
                String zh = "";
                String en = "";
                if (k == LANG_ZH) {
                    en = translateLineBlocking(t, "英文", ctx);
                } else if (k == LANG_EN) {
                    zh = translateLineBlocking(t, "简体中文", ctx);
                } else {
                    String[] pair = translateBiBlocking(t, ctx);
                    zh = pair[0];
                    en = pair[1];
                }
                final String fz = zh;
                final String fe = en;
                main.post(() -> finishTranslation(id, t, k, fz, fe));
            });
        } catch (Exception e) {
            translating = false;
        }
    }

    private void finishTranslation(int id, String src, int kind, String zh, String en) {
        if (id != sentenceId) return; // 已被更新的一句顶掉
        translating = false;
        String zhLine = kind == LANG_ZH ? src : (zh == null || zh.isEmpty() ? "翻译未返回" : zh);
        String enLine = kind == LANG_EN ? src : (en == null || en.isEmpty() ? "翻译未返回" : en);
        listener.onTranslated(zhLine, enLine);
        lastContext = src;
        if (hasPendingDraft) {
            String d = pendingDraft;
            int k = pendingDraftKind;
            hasPendingDraft = false;
            if (k == LANG_ZH) listener.onPartial(d, "识别中…");
            else if (k == LANG_EN) listener.onPartial("翻译中…", d);
            else listener.onPartial("识别中…", "识别中…");
        }
    }

    private String[] translateBiBlocking(String text, String prev) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> zh = new AtomicReference<>("");
        final AtomicReference<String> en = new AtomicReference<>("");
        AiClient.translateBilingual(context, text, prev, new AiClient.BiCb() {
            @Override
            public void onOk(String z, String e) {
                zh.set(z == null ? "" : z);
                en.set(e == null ? "" : e);
                latch.countDown();
            }

            @Override
            public void onError(String message) {
                lastError = message;
                latch.countDown();
            }
        });
        try {
            latch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return new String[]{zh.get(), en.get()};
    }

    private String translateLineBlocking(String text, String target, String prev) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> ref = new AtomicReference<>(null);
        AiClient.translateLine(context, text, target, prev, new AiClient.Cb() {
            @Override
            public void onOk(String translated) {
                ref.set(translated);
                latch.countDown();
            }

            @Override
            public void onError(String message) {
                lastError = message;
                latch.countDown();
            }
        });
        try {
            latch.await(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return ref.get();
    }

    private void reportError(String message) {
        if (errorReported) return;
        errorReported = true;
        final String msg = (message == null || message.isEmpty()) ? "语音识别失败" : message;
        main.post(() -> listener.onError(msg));
    }

    private String normalize(String text) {
        String s = text.replaceAll("\\s+", " ").trim();
        while (s.startsWith("，") || s.startsWith(",") || s.startsWith("。") || s.startsWith(".")) {
            s = s.substring(1).trim();
        }
        return s;
    }

    private String transcribeBlocking(File wav) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> ref = new AtomicReference<>(null);
        final AtomicReference<String> err = new AtomicReference<>("");
        AiClient.transcribe(context, wav, new AiClient.Cb() {
            @Override
            public void onOk(String text) {
                ref.set(text);
                latch.countDown();
            }

            @Override
            public void onError(String message) {
                err.set(message);
                latch.countDown();
            }
        });
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) lastError = "语音识别超时";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (err.get() != null && !err.get().isEmpty()) lastError = err.get();
        return ref.get();
    }

    private File writeWav(int mySeq, byte[] pcm) {
        File dir = new File(context.getCacheDir(), "subtitle_chunks");
        if (!dir.exists()) dir.mkdirs();
        File f = new File(dir, "chunk_" + mySeq + ".wav");
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(AudioCapture.wavHeader(pcm.length));
            out.write(pcm);
            return f;
        } catch (Exception e) {
            return null;
        }
    }

    public void stop() {
        recording = false;
        main.removeCallbacks(connectTimeout);
        AudioRecord r = record;
        if (r != null) {
            try {
                r.stop();
            } catch (Exception ignored) {
            }
        }
        if (recThread != null) {
            try {
                recThread.join(800);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            recThread = null;
        }
        // 先不判死 running，留 0.9 秒把最后一句定稿和译文收完
        RealtimeAsrClient s = stream;
        stream = null;
        if (s != null) {
            s.finish();
            try {
                Thread.sleep(900);
            } catch (InterruptedException ignored) {
            }
            s.close();
        }
        running = false;
        if (asrPool != null) {
            asrPool.getQueue().clear();
            asrPool.shutdownNow();
            asrPool = null;
        }
        if (translatePool != null) {
            translatePool.getQueue().clear();
            translatePool.shutdownNow();
            translatePool = null;
        }
        release();
        File dir = new File(context.getCacheDir(), "subtitle_chunks");
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
    }

    private void release() {
        if (record != null) {
            try {
                record.release();
            } catch (Exception ignored) {
            }
            record = null;
        }
    }
}
