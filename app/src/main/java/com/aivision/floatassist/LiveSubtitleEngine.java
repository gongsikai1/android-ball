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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 实时屏幕声音字幕（低延迟版）：
 * 每 1 秒切一段录音（带 0.3 秒重叠，减少断词），多段并行转写，
 * 转写结果直接上屏，翻译在独立线程池补齐，旧结果自动丢弃，保证字幕跟手。
 * 中文语音 → 上中文、下英文；英文语音 → 上中文译文、下英文原文。
 */
public class LiveSubtitleEngine {

    public interface Listener {
        /** 先出原文：sourceChinese=true 表示原话是中文 */
        void onSource(String text, boolean sourceChinese);

        /** 译文返回后补齐两行 */
        void onTranslated(String source, String translated, boolean sourceChinese);

        void onError(String message);
    }

    private static final int RATE = 16000;
    /** 每段录音长度：1 秒，短段意味着更低延迟 */
    private static final long CHUNK_MS = 1000;
    /** 段间重叠 0.3 秒，避免把词切成两半 */
    private static final int OVERLAP_BYTES = (int) (RATE * 2 * 0.3);
    /** 一段至少 0.5 秒才提交，太短识别不稳 */
    private static final int MIN_BYTES = RATE * 2 / 2;

    private final android.content.Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final Object lock = new Object();
    private volatile int latestSourceSeq = 0;
    private String lastText = "";

    private final AtomicInteger seqGen = new AtomicInteger(0);
    private final AtomicInteger failStreak = new AtomicInteger(0);
    private final AtomicInteger okCount = new AtomicInteger(0);
    private volatile String lastError = "";
    private volatile boolean errorReported = false;

    private ThreadPoolExecutor asrPool;
    private ExecutorService translatePool;

    private AudioRecord record;
    private Thread recThread;
    private volatile boolean running;

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

            // 转写 3 路并行，跟不上就丢最旧的段；翻译单独 2 路，不阻塞下一段转写
            asrPool = new ThreadPoolExecutor(3, 3, 30, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(2), (r, executor) -> {
                        if (!executor.isShutdown()) {
                            executor.getQueue().poll();
                            try {
                                executor.execute(r);
                            } catch (Exception ignored) {
                            }
                        }
                    });
            translatePool = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(2), (r, executor) -> {
                        if (!executor.isShutdown()) {
                            executor.getQueue().poll();
                            try {
                                executor.execute(r);
                            } catch (Exception ignored) {
                            }
                        }
                    });

            running = true;
            record.startRecording();
            recThread = new Thread(this::recordLoop, "subtitle-rec");
            recThread.start();
            return true;
        } catch (Exception e) {
            running = false;
            release();
            return false;
        }
    }

    private void recordLoop() {
        short[] buf = new short[1024];
        ByteBufferCache bytes = new ByteBufferCache(buf.length);
        ByteArrayOutputStream acc = new ByteArrayOutputStream();
        long chunkStart = SystemClock.elapsedRealtime();
        while (running) {
            int n;
            try {
                n = record.read(buf, 0, buf.length);
            } catch (Exception e) {
                break;
            }
            if (n <= 0) continue;
            bytes.write(buf, n);
            acc.write(bytes.array(), 0, n * 2);

            if (SystemClock.elapsedRealtime() - chunkStart >= CHUNK_MS) {
                byte[] data = acc.toByteArray();
                acc.reset();
                chunkStart = SystemClock.elapsedRealtime();
                // 保留尾部一小段作为下一段的开头，跨句更连贯
                if (data.length > OVERLAP_BYTES) {
                    acc.write(data, data.length - OVERLAP_BYTES, OVERLAP_BYTES);
                }
                if (data.length >= MIN_BYTES) {
                    final int mySeq = seqGen.incrementAndGet();
                    final byte[] payload = data;
                    try {
                        asrPool.execute(() -> process(mySeq, payload));
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    /** 转写 + 上屏；翻译转交独立线程池 */
    private void process(int mySeq, byte[] pcm) {
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

        synchronized (lock) {
            if (mySeq < latestSourceSeq) return; // 已有更新的段上屏，旧的丢弃
            if (clean.equals(lastText)) return;
            latestSourceSeq = mySeq;
            lastText = clean;
        }

        final boolean sourceChinese = "英文".equals(AiClient.detectTarget(clean));
        main.post(() -> listener.onSource(clean, sourceChinese));

        String target = sourceChinese ? "英文" : "简体中文";
        try {
            translatePool.execute(() -> {
                String translated = translateBlocking(clean, target);
                if (!running) return;
                if (translated == null || translated.isEmpty()) return;
                synchronized (lock) {
                    if (mySeq < latestSourceSeq) return; // 这条已被新字幕顶掉
                }
                final String shownTrans = translated;
                main.post(() -> listener.onTranslated(clean, shownTrans, sourceChinese));
            });
        } catch (Exception ignored) {
        }
    }

    private void reportError(String message) {
        if (errorReported) return;
        errorReported = true;
        final String msg = (message == null || message.isEmpty()) ? "语音识别失败" : message;
        main.post(() -> listener.onError(msg));
    }

    private String normalize(String text) {
        String s = text.replaceAll("\\s+", " ").trim();
        // 去掉 ASR 常见的空白标点前缀
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

    private String translateBlocking(String text, String target) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> ref = new AtomicReference<>(null);
        AiClient.translateText(context, text, target, new AiClient.Cb() {
            @Override
            public void onOk(String translated) {
                ref.set(translated);
                latch.countDown();
            }

            @Override
            public void onError(String message) {
                latch.countDown();
            }
        });
        try {
            latch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
        running = false;
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
        if (asrPool != null) {
            asrPool.getQueue().clear();
            asrPool.shutdownNow();
            asrPool = null;
        }
        if (translatePool != null) {
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

    /** 小工具：short[] → little-endian bytes，避免每轮重分配 */
    private static class ByteBufferCache {
        private final java.nio.ByteBuffer buffer;

        ByteBufferCache(int samples) {
            buffer = java.nio.ByteBuffer.allocate(samples * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        }

        void write(short[] src, int n) {
            buffer.clear();
            for (int i = 0; i < n; i++) buffer.putShort(src[i]);
        }

        byte[] array() {
            return buffer.array();
        }
    }
}
