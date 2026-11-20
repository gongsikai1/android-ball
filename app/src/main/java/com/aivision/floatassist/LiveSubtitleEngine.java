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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 实时屏幕声音字幕：每 2.6 秒切一段录音，先转写，再翻译成另一种语言。
 * 中文语音 → 中文字幕 + 英文字幕；英文语音 → 中文字幕 + 英文字幕。
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
    private static final long CHUNK_MS = 2600;
    private static final int MAX_QUEUE = 2;

    private final android.content.Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();

    private AudioRecord record;
    private Thread recThread;
    private Thread workThread;
    private volatile boolean running;
    private int seq;

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
            running = true;
            record.startRecording();
            recThread = new Thread(this::recordLoop, "subtitle-rec");
            workThread = new Thread(this::workLoop, "subtitle-asr");
            recThread.start();
            workThread.start();
            return true;
        } catch (Exception e) {
            running = false;
            release();
            return false;
        }
    }

    private void recordLoop() {
        short[] buf = new short[2048];
        ByteBuffer bytes = ByteBuffer.allocate(buf.length * 2).order(ByteOrder.LITTLE_ENDIAN);
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
            bytes.clear();
            for (int i = 0; i < n; i++) bytes.putShort(buf[i]);
            acc.write(bytes.array(), 0, n * 2);

            if (SystemClock.elapsedRealtime() - chunkStart >= CHUNK_MS) {
                byte[] data = acc.toByteArray();
                acc.reset();
                chunkStart = SystemClock.elapsedRealtime();
                if (data.length >= 3200) {
                    while (queue.size() >= MAX_QUEUE) queue.poll();
                    queue.offer(data);
                }
            }
        }
    }

    private void workLoop() {
        while (running) {
            byte[] data;
            try {
                data = queue.poll(300, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (data == null) continue;
            File wav = writeWav(data);
            if (wav == null) continue;

            String text = transcribeBlocking(wav);
            wav.delete();
            if (!running) break;
            if (text == null) {
                // 识别失败：提示一次后继续尝试
                String err = lastError;
                main.post(() -> listener.onError(err));
                continue;
            }
            text = text.trim();
            if (text.isEmpty()) continue;

            boolean sourceChinese = "英文".equals(AiClient.detectTarget(text));
            final String source = text;
            main.post(() -> listener.onSource(source, sourceChinese));

            String target = sourceChinese ? "英文" : "简体中文";
            String translated = translateBlocking(text, target);
            if (!running) break;
            final String trans = translated == null ? "" : translated;
            if (trans.isEmpty()) continue;
            main.post(() -> listener.onTranslated(source, trans, sourceChinese));
        }
    }

    private String lastError = "";

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
            if (!latch.await(30, TimeUnit.SECONDS)) lastError = "语音识别超时";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        lastError = err.get();
        if (ref.get() == null && !lastError.isEmpty() && !running) {
            main.post(() -> listener.onError(lastError));
        }
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
                lastError = message;
                latch.countDown();
            }
        });
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return ref.get();
    }

    private File writeWav(byte[] pcm) {
        File dir = new File(context.getCacheDir(), "subtitle_chunks");
        if (!dir.exists()) dir.mkdirs();
        File f = new File(dir, "chunk_" + (seq++) + ".wav");
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
        queue.clear();
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
        if (workThread != null) {
            workThread.interrupt();
            workThread = null;
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
