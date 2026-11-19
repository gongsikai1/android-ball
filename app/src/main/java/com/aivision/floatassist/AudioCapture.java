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

/**
 * 录制手机屏幕正在播放的声音（AudioPlaybackCapture）。
 * 只在 Android 10+ 可用，不申请麦克风权限，录的是应用播放的媒体声。
 */
public class AudioCapture {

    public interface Listener {
        void onLimitReached();
    }

    private static final int SAMPLE_RATE = 16000;
    private static final int MAX_SECONDS = 150;

    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ByteArrayOutputStream pcm = new ByteArrayOutputStream();

    private AudioRecord record;
    private Thread thread;
    private volatile boolean running;
    private long startMs;
    private long totalMs;
    private boolean limitNotified;

    public AudioCapture(Listener listener) {
        this.listener = listener;
    }

    public boolean isRunning() {
        return running;
    }

    public long durationMs() {
        return running ? Math.max(totalMs, SystemClock.elapsedRealtime() - startMs) : totalMs;
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
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build();

            int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int bufSize = Math.max(minBuf * 4, 8192);

            record = new AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufSize)
                    .setAudioPlaybackCaptureConfig(config)
                    .build();

            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                record.release();
                record = null;
                return false;
            }

            synchronized (pcm) {
                pcm.reset();
            }
            startMs = SystemClock.elapsedRealtime();
            totalMs = 0;
            limitNotified = false;
            running = true;
            record.startRecording();
            thread = new Thread(this::loop, "audio-capture");
            thread.start();
            return true;
        } catch (Exception e) {
            release();
            return false;
        }
    }

    private void loop() {
        short[] buf = new short[2048];
        ByteBuffer bytes = ByteBuffer.allocate(buf.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        while (running) {
            int n;
            try {
                n = record.read(buf, 0, buf.length);
            } catch (Exception e) {
                break;
            }
            if (n > 0) {
                bytes.clear();
                for (int i = 0; i < n; i++) bytes.putShort(buf[i]);
                synchronized (pcm) {
                    pcm.write(bytes.array(), 0, n * 2);
                }
            }
            long elapsed = SystemClock.elapsedRealtime() - startMs;
            if (elapsed >= MAX_SECONDS * 1000L) {
                running = false;
                totalMs = elapsed;
                if (listener != null && !limitNotified) {
                    limitNotified = true;
                    main.post(listener::onLimitReached);
                }
                break;
            }
        }
        totalMs = Math.max(totalMs, SystemClock.elapsedRealtime() - startMs);
    }

    /** 停止并写入 WAV 文件，返回 null 表示没有有效音频 */
    public File stopAndSave(File outFile) {
        running = false;
        AudioRecord r = record;
        if (r != null) {
            try {
                r.stop();
            } catch (Exception ignored) {
            }
        }
        if (thread != null) {
            try {
                thread.join(800);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
        release();
        byte[] data;
        synchronized (pcm) {
            data = pcm.toByteArray();
        }
        if (data.length < 3200) return null; // < 0.1s
        try (FileOutputStream out = new FileOutputStream(outFile)) {
            out.write(wavHeader(data.length));
            out.write(data);
        } catch (Exception e) {
            return null;
        }
        totalMs = data.length / 2L * 1000L / SAMPLE_RATE;
        return outFile;
    }

    public static byte[] wavHeader(int dataLen) {
        byte[] header = new byte[44];
        ByteBuffer b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes());
        b.putInt(36 + dataLen);
        b.put("WAVE".getBytes());
        b.put("fmt ".getBytes());
        b.putInt(16);
        b.putShort((short) 1);
        b.putShort((short) 1);
        b.putInt(SAMPLE_RATE);
        b.putInt(SAMPLE_RATE * 2);
        b.putShort((short) 2);
        b.putShort((short) 16);
        b.put("data".getBytes());
        b.putInt(dataLen);
        return header;
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

    public void discard() {
        running = false;
        AudioRecord r = record;
        if (r != null) {
            try {
                r.stop();
            } catch (Exception ignored) {
            }
        }
        release();
        synchronized (pcm) {
            pcm.reset();
        }
    }
}
