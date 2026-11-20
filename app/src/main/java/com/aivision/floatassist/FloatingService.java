package com.aivision.floatassist;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 悬浮球服务：单击存图 / 双击提问 / 三击翻译。
 * 悬浮球窗口只有 48dp 大小且不获取输入焦点，其余区域触摸事件全部下发给其他应用，不打断正常操作。
 */
public class FloatingService extends Service implements CaptureManager.CallbackHost {

    public static final String ACTION_START = "com.aivision.floatassist.START";
    public static final String ACTION_STOP = "com.aivision.floatassist.STOP";
    public static final String ACTION_STATE = "com.aivision.floatassist.STATE";
    public static final String EXTRA_CODE = "code";
    public static final String EXTRA_DATA = "data";
    public static final String EXTRA_STATE_TEXT = "state_text";

    private static final int NOTIF_ID = 1001;
    private static final String CHANNEL_ID = "ai_float_service";
    private static final int BUTTON_DP = 48;
    private static final long MULTI_TAP_WINDOW = 330;
    private static final long LONG_PRESS_MS = 2000;

    private WindowManager wm;
    private Handler main;
    private TextView button;
    private WindowManager.LayoutParams buttonParams;
    private int buttonSize;

    private CaptureManager capture;
    private boolean busy;

    private static final int MODE_NONE = 0;
    private static final int MODE_LISTEN = 1;
    private static final int MODE_SEE = 2;
    private int mode = MODE_NONE;
    private AudioCapture audioCapture;
    private static final int SEE_INTERVAL_MS = 15000;
    private static final int SEE_MAX_SHOTS = 8;
    private final ArrayList<Bitmap> seenShots = new ArrayList<>();
    private final ArrayList<Long> shotTimes = new ArrayList<>();
    private Runnable seeLoop;
    private long sessionStart;

    private int tapCount;
    private Runnable tapResolve;

    private PanelState panel;
    private View toastView;
    private View menuView;

    private LiveSubtitleEngine subtitleEngine;
    private View subtitleView;
    private TextView subZh;
    private TextView subEn;


    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        main = new Handler(Looper.getMainLooper());
        capture = new CaptureManager(this);
        buttonSize = Ui.dp(this, BUTTON_DP);
        createChannel();
        addButton();
        Prefs.setRunning(this, true);
        publishState("悬浮球已就绪");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForegroundCompat();

        if (intent.hasExtra(EXTRA_DATA)) {
            int code = intent.getIntExtra(EXTRA_CODE, 0);
            Intent data = intent.getParcelableExtra(EXTRA_DATA);
            boolean ok = capture.start(code, data, this);
            Prefs.setRunning(this, ok);
            publishState(ok ? "悬浮球已就绪" : "截图授权失败，请重新开启");
            if (!ok) showToast("截图授权失败，请回到 App 重新开启");
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        stopSubtitles();
        if (audioCapture != null) {
            audioCapture.discard();
            audioCapture = null;
        }
        stopSeeLoop();
        clearShots();
        releaseUi();
        if (capture != null) capture.release();
        Prefs.setRunning(this, false);
        publishState("已停止");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------------- 通知 ----------------

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "AI 识图悬浮助手", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("保持悬浮球与截图授权运行");
            ch.setShowBadge(false);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setSmallIcon(R.drawable.ic_launcher_fg)
                .setContentTitle("AI 识图悬浮助手")
                .setContentText("单击存图 · 双击提问 · 三击翻译")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void startForegroundCompat() {
        Notification n = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Exception e) {
            try {
                startForeground(NOTIF_ID, n);
            } catch (Exception ignored) {
            }
        }
    }

    private void publishState(String text) {
        Intent i = new Intent(ACTION_STATE).setPackage(getPackageName());
        i.putExtra(EXTRA_STATE_TEXT, text);
        i.putExtra("running", Prefs.running(this));
        sendBroadcast(i);
    }

    // ---------------- 悬浮球 ----------------

    private void addButton() {
        button = new TextView(this);
        button.setText("AI");
        button.setTextColor(Color.WHITE);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setGravity(Gravity.CENTER);
        button.setBackgroundResource(R.drawable.bg_float_button);
        button.setAlpha(Prefs.buttonAlpha(this));
        button.setElevation(Ui.dp(this, 6));
        button.setContentDescription("AI 识图悬浮助手");

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        buttonParams = new WindowManager.LayoutParams(
                buttonSize, buttonSize, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                android.graphics.PixelFormat.TRANSLUCENT);
        buttonParams.gravity = Gravity.TOP | Gravity.START;

        int sx = Ui.screenWidth(this);
        int sy = Ui.screenHeight(this);
        int savedX = Prefs.buttonX(this);
        int savedY = Prefs.buttonY(this);
        buttonParams.x = savedX >= 0 ? Math.min(savedX, sx - buttonSize) : sx - buttonSize - Ui.dp(this, 6);
        buttonParams.y = savedY >= 0 ? Math.min(savedY, sy - buttonSize) : sy / 3;

        button.setOnTouchListener(new DragTapListener());
        try {
            wm.addView(button, buttonParams);
        } catch (Exception ignored) {
        }
    }

    private void releaseUi() {
        stopSubtitles();
        closePanel();
        removeView(toastView, true);
        toastView = null;
        removeView(menuView, true);
        menuView = null;
        removeView(button, false);
        button = null;
    }

    private void removeView(View v, boolean unused) {
        if (v == null || wm == null) return;
        try {
            wm.removeView(v);
        } catch (Exception ignored) {
        }
    }

    /** 拖动 + 单击/双击/三击 + 长按菜单 */
    private class DragTapListener implements View.OnTouchListener {
        private float downRawX, downRawY;
        private int startX, startY;
        private boolean moved;
        private boolean longPressed;
        private final Runnable longPress = () -> {
            longPressed = true;
            tapCount = 0;
            if (tapResolve != null) main.removeCallbacks(tapResolve);
            tick(30);
            toggleSubtitles();
        };

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    startX = buttonParams.x;
                    startY = buttonParams.y;
                    moved = false;
                    longPressed = false;
                    main.postDelayed(longPress, LONG_PRESS_MS);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downRawX;
                    float dy = event.getRawY() - downRawY;
                    if (!moved && Math.hypot(dx, dy) > Ui.dp(FloatingService.this, 8)) {
                        moved = true;
                        main.removeCallbacks(longPress);
                        closeMenu();
                    }
                    if (moved) {
                        int sx = Ui.screenWidth(FloatingService.this);
                        int sy = Ui.screenHeight(FloatingService.this);
                        buttonParams.x = Math.max(0, Math.min((int) (startX + dx), sx - buttonSize));
                        buttonParams.y = Math.max(Ui.dp(FloatingService.this, 24),
                                Math.min((int) (startY + dy), sy - buttonSize - Ui.dp(FloatingService.this, 48)));
                        try {
                            wm.updateViewLayout(button, buttonParams);
                        } catch (Exception ignored) {
                        }
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    main.removeCallbacks(longPress);
                    return true;
                case MotionEvent.ACTION_UP:
                    main.removeCallbacks(longPress);
                    if (moved) {
                        snapToEdge();
                    } else if (!longPressed) {
                        v.performClick();
                        registerTap();
                    }
                    return true;
            }
            return false;
        }
    }

    private void snapToEdge() {
        int sx = Ui.screenWidth(this);
        int target = buttonParams.x + buttonSize / 2 < sx / 2 ? Ui.dp(this, 4) : sx - buttonSize - Ui.dp(this, 4);
        buttonParams.x = target;
        try {
            wm.updateViewLayout(button, buttonParams);
        } catch (Exception ignored) {
        }
        Prefs.saveButtonPos(this, buttonParams.x, buttonParams.y);
    }

    private void registerTap() {
        tick(12);
        closeMenu();
        tapCount++;
        if (tapResolve == null) tapResolve = this::resolveTaps;
        main.removeCallbacks(tapResolve);
        main.postDelayed(tapResolve, MULTI_TAP_WINDOW);
    }

    private void resolveTaps() {
        int count = Math.min(tapCount, 5);
        tapCount = 0;
        if (count == 1) {
            onSingleTap();
        } else if (count == 2) {
            onDoubleTap();
        } else if (count == 3) {
            onTripleTap();
        } else if (count == 4) {
            onQuadTap();
        } else if (count >= 5) {
            onQuintTap();
        }
    }

    private void tick(int ms) {
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null) {
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            }
        } catch (Exception ignored) {
        }
    }

    // ---------------- 三个动作 ----------------

    private void onSingleTap() {
        runCapture(bmp -> {
            showToast("已存入 AI 截图库");
            new Thread(() -> {
                ShotStore.Shot shot = ShotStore.add(this, bmp, "");
                OcrHelper.recognize(bmp, text -> ShotStore.updateOcr(this, shot, text));
            }, "shot-save").start();
        });
    }

    private void onDoubleTap() {
        runCapture(bmp -> {
            PanelState st = showPanel("结合截图提问", bmp, true);
            st.input.requestFocus();
            st.input.postDelayed(() -> {
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(st.input, InputMethodManager.SHOW_IMPLICIT);
            }, 300);
        });
    }

    private void onTripleTap() {
        runCapture(bmp -> {
            PanelState st = showPanel("翻译截图", bmp, false);
            setLoading(st, true, "正在识别文字…");
            ensureOcr(st, ocr -> {
                String target = AiClient.detectTarget(ocr);
                st.baseTitle = "翻译截图 · 译为" + target;
                if (st.title != null) st.title.setText(st.baseTitle);
                if (TextUtils.isEmpty(ocr) && Prefs.visionModel(this).isEmpty()) {
                    setLoading(st, false, "没有识别到文字。\n如果截图是纯图片，可在 App 里配置视觉模型后再试。");
                    return;
                }
                setLoading(st, true, "正在翻译…");
                AiClient.ask(this, bmp, ocr, "", AiClient.Mode.TRANSLATE, new AiClient.Cb() {
                    @Override
                    public void onOk(String text) {
                        main.post(() -> {
                            if (panel != st) return;
                            setLoading(st, false, TextUtils.isEmpty(text) ? "未识别到文字" : text);
                        });
                    }

                    @Override
                    public void onError(String message) {
                        main.post(() -> {
                            if (panel != st) return;
                            setLoading(st, false, message);
                        });
                    }
                });
            });
        });
    }

    // ---------------- 四击听屏 / 五击看+听 ----------------

    private void onQuadTap() {
        if (mode == MODE_LISTEN) {
            stopListenAndSummarize();
        } else if (mode == MODE_SEE) {
            showToast("正在看+听，请五击结束");
        } else {
            startListen();
        }
    }

    private void onQuintTap() {
        if (mode == MODE_SEE) {
            stopSeeAndSummarize();
        } else if (mode == MODE_LISTEN) {
            showToast("正在只听屏幕，请四击结束；要同时看画面，请结束后再五击");
        } else {
            startSee();
        }
    }

    private void startListen() {
        if (subtitleEngine != null) stopSubtitles();
        if (Build.VERSION.SDK_INT < 29) {
            showToast("系统版本过低，无法录制屏幕声音（需要 Android 10 及以上）");
            return;
        }
        if (!capture.isReady()) {
            showToast("录音通道不可用：系统录屏可能正在占用，请关闭系统录屏后重新开启悬浮助手");
            return;
        }
        AudioCapture a = new AudioCapture(this::onAudioLimit);
        if (!a.start(capture.getProjection())) {
            showToast("无法录制屏幕声音，请重新开启悬浮助手后再试");
            return;
        }
        audioCapture = a;
        mode = MODE_LISTEN;
        sessionStart = System.currentTimeMillis();
        updateBallMode();
        tick(25);
        showToast("开始听屏幕，再次四击结束并总结");
    }

    private void startSee() {
        if (subtitleEngine != null) stopSubtitles();
        if (Build.VERSION.SDK_INT < 29) {
            showToast("系统版本过低，无法录制屏幕声音（需要 Android 10 及以上）");
            return;
        }
        if (!capture.isReady() && !ScreenAccessibilityService.isReady()) {
            showToast("截图通道不可用，请关闭系统录屏或在 App 开启「无障碍截图」备用");
            return;
        }
        clearShots();
        audioCapture = null;
        if (capture.isReady()) {
            AudioCapture a = new AudioCapture(this::onAudioLimit);
            if (a.start(capture.getProjection())) {
                audioCapture = a;
            } else {
                showToast("无法录制屏幕声音，这次只记录画面");
            }
        } else {
            showToast("截图授权被系统录屏占用，这次只记录画面（无声音）");
        }
        mode = MODE_SEE;
        sessionStart = System.currentTimeMillis();
        updateBallMode();
        tick(25);
        startSeeLoop();
        showToast("开始看+听屏幕，再次五击结束并总结");
    }

    private void onAudioLimit() {
        main.post(() -> {
            if (mode == MODE_LISTEN) {
                showToast("已达 150 秒录制上限，自动结束并总结");
                stopListenAndSummarize();
            } else if (mode == MODE_SEE) {
                showToast("已达 150 秒录制上限，自动结束并总结");
                stopSeeAndSummarize();
            }
        });
    }

    private void stopListenAndSummarize() {
        if (mode != MODE_LISTEN || audioCapture == null) return;
        long ms = audioCapture.durationMs();
        File wav = saveAudio();
        mode = MODE_NONE;
        updateBallMode();
        if (wav == null || ms < 800) {
            showToast("没有录到有效声音（可能该应用禁止被录制）");
            return;
        }
        final PanelState st = showPanel("听屏总结", null, false);
        setLoading(st, true, "正在转写屏幕声音…");
        AiClient.transcribe(this, wav, new AiClient.Cb() {
            @Override
            public void onOk(String text) {
                main.post(() -> {
                    if (panel != st) return;
                    final String t = text == null ? "" : text.trim();
                    if (t.isEmpty()) {
                        setLoading(st, false, "没有识别到语音内容。\n可能是这段时间屏幕没有声音，或该应用禁止被录制。");
                        return;
                    }
                    setLoading(st, true, "正在总结…");
                    AiClient.summarize(FloatingService.this, null, t, "", new AiClient.Cb() {
                        @Override
                        public void onOk(String summary) {
                            main.post(() -> {
                                if (panel != st) return;
                                setLoading(st, false, summary + "\n\n—— 声音原文 ——\n" + clip(t, 1600));
                            });
                        }

                        @Override
                        public void onError(String message) {
                            main.post(() -> {
                                if (panel != st) return;
                                setLoading(st, false, message + "\n\n—— 声音原文 ——\n" + clip(t, 1600));
                            });
                        }
                    });
                });
            }

            @Override
            public void onError(String message) {
                main.post(() -> {
                    if (panel != st) return;
                    setLoading(st, false, message);
                });
            }
        });
    }

    private void stopSeeAndSummarize() {
        if (mode != MODE_SEE) return;
        stopSeeLoop();
        long ms = audioCapture != null ? audioCapture.durationMs() : 0;
        File wav = saveAudio();
        final List<Bitmap> shots = new ArrayList<>(seenShots);
        final List<Long> times = new ArrayList<>(shotTimes);
        seenShots.clear();
        shotTimes.clear();
        mode = MODE_NONE;
        updateBallMode();
        if (shots.isEmpty() && wav == null) {
            showToast("没有记录到内容");
            return;
        }
        final PanelState st = showPanel("看+听总结", shots.isEmpty() ? null : shots.get(shots.size() - 1), false);
        setLoading(st, true, "正在按时间轴整理画面与声音…");
        if (wav == null || ms < 800) {
            buildTimeline(st, shots, times, new String[0], "没有录到有效声音");
        } else {
            splitAndTranscribe(st, wav, shots, times);
        }
    }

    /** 按 15 秒一段切分音频并并行转写，与截图时间对齐 */
    private void splitAndTranscribe(PanelState st, File wav, List<Bitmap> shots, List<Long> times) {
        new Thread(() -> {
            byte[] all;
            try {
                all = Files.readAllBytes(wav.toPath());
            } catch (Exception e) {
                main.post(() -> buildTimeline(st, shots, times, new String[0], "读取录音失败"));
                return;
            }
            if (all.length <= 44) {
                main.post(() -> buildTimeline(st, shots, times, new String[0], "没有录到有效声音"));
                return;
            }
            int dataLen = all.length - 44;
            int segBytes = 16000 * 2 * 15;
            int count = (dataLen + segBytes - 1) / segBytes;
            count = Math.max(1, Math.min(SEE_MAX_SHOTS, count));
            File dir = new File(getCacheDir(), "asr");
            if (!dir.exists()) dir.mkdirs();
            final String[] texts = new String[count];
            final String[] error = new String[1];
            final File[] segFiles = new File[count];

            ExecutorService pool = Executors.newFixedThreadPool(Math.min(4, count));
            CountDownLatch latch = new CountDownLatch(count);
            for (int i = 0; i < count; i++) {
                final int idx = i;
                int off = i * segBytes;
                int len = Math.min(segBytes, dataLen - off);
                File seg = new File(dir, "seg_" + idx + "_" + System.currentTimeMillis() + ".wav");
                segFiles[idx] = seg;
                try (FileOutputStream out = new FileOutputStream(seg)) {
                    out.write(AudioCapture.wavHeader(len));
                    out.write(all, 44 + off, len);
                } catch (Exception e) {
                    texts[idx] = "";
                    latch.countDown();
                    continue;
                }
                pool.submit(() -> AiClient.transcribe(this, seg, new AiClient.Cb() {
                    @Override
                    public void onOk(String text) {
                        texts[idx] = text == null ? "" : text.trim();
                        latch.countDown();
                    }

                    @Override
                    public void onError(String message) {
                        if (error[0] == null) error[0] = message;
                        texts[idx] = "";
                        latch.countDown();
                    }
                }));
            }
            try {
                latch.await(150, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            pool.shutdownNow();
            for (File f : segFiles) if (f != null) f.delete();
            main.post(() -> buildTimeline(st, shots, times, texts, error[0]));
        }, "asr-split").start();
    }

    private void buildTimeline(PanelState st, List<Bitmap> shots, List<Long> times, String[] texts, String error) {
        if (panel != st) return;
        StringBuilder sb = new StringBuilder();
        if (texts.length == 0) {
            for (int i = 0; i < shots.size(); i++) {
                long sec = times.size() > i ? times.get(i) / 1000 : 0;
                sb.append(String.format(Locale.CHINA, "%02d:%02d", sec / 60, sec % 60))
                        .append(" 图").append(i + 1).append("\n");
            }
        } else {
            for (int i = 0; i < texts.length; i++) {
                int start = i * 15;
                int end = (i + 1) * 15;
                sb.append(String.format(Locale.CHINA, "%02d:%02d–%02d:%02d", start / 60, start % 60, end / 60, end % 60));
                StringBuilder imgs = new StringBuilder();
                for (int j = 0; j < shots.size(); j++) {
                    long sec = times.size() > j ? times.get(j) / 1000 : -1;
                    if (sec >= start && sec < end) {
                        if (imgs.length() > 0) imgs.append("、");
                        imgs.append("图").append(j + 1);
                    }
                }
                if (imgs.length() > 0) sb.append("（").append(imgs).append("）");
                sb.append("\n声音：").append(texts[i] == null || texts[i].trim().isEmpty() ? "（无语音）" : texts[i].trim());
                sb.append("\n\n");
            }
        }
        if (error != null && !error.isEmpty() && allEmpty(texts)) {
            sb.insert(0, "（语音转写失败：" + error + "）\n\n");
        }
        final String timeline = sb.toString();
        setLoading(st, true, "正在总结…");
        AiClient.summarizeTimeline(this, shots, timeline, new AiClient.Cb() {
            @Override
            public void onOk(String summary) {
                main.post(() -> {
                    if (panel != st) return;
                    setLoading(st, false, summary + "\n\n—— 时间轴 ——\n" + clip(timeline, 1200));
                });
            }

            @Override
            public void onError(String message) {
                main.post(() -> {
                    if (panel != st) return;
                    setLoading(st, false, message);
                });
            }
        });
    }

    private static boolean allEmpty(String[] texts) {
        for (String t : texts) if (t != null && !t.trim().isEmpty()) return false;
        return true;
    }

    private void startSeeLoop() {
        stopSeeLoop();
        seeLoop = new Runnable() {
            @Override
            public void run() {
                if (mode != MODE_SEE) return;
                final long offset = SystemClock.elapsedRealtime() - sessionStart;
                new Thread(() -> {
                    Bitmap b = captureAny();
                    if (b == null) return;
                    final Bitmap scaled = scale(b, 1024);
                    main.post(() -> {
                        if (mode != MODE_SEE) {
                            scaled.recycle();
                            return;
                        }
                        if (seenShots.size() >= SEE_MAX_SHOTS) {
                            scaled.recycle();
                            return;
                        }
                        seenShots.add(scaled);
                        shotTimes.add(offset);
                    });
                }, "see-shot").start();
                main.postDelayed(this, SEE_INTERVAL_MS);
            }
        };
        main.post(seeLoop);
    }

    private void stopSeeLoop() {
        if (seeLoop != null) {
            main.removeCallbacks(seeLoop);
            seeLoop = null;
        }
    }

    private void clearShots() {
        for (Bitmap b : seenShots) b.recycle();
        seenShots.clear();
        shotTimes.clear();
    }

    private void updateBallMode() {
        if (button == null) return;
        if (subtitleEngine != null) {
            button.setText("译");
            button.setAlpha(1f);
            return;
        }
        button.setText(mode == MODE_LISTEN ? "听" : mode == MODE_SEE ? "看" : "AI");
        button.setAlpha(mode == MODE_NONE ? Prefs.buttonAlpha(this) : 1f);
    }

    // ---------------- 长按 2 秒：实时双语字幕 ----------------

    private void toggleSubtitles() {
        if (subtitleEngine != null) {
            stopSubtitles();
            showToast("实时字幕已关闭");
        } else {
            startSubtitles();
        }
    }

    private void startSubtitles() {
        if (Build.VERSION.SDK_INT < 29) {
            showToast("系统版本过低，实时字幕需要 Android 10 及以上");
            return;
        }
        if (mode != MODE_NONE) {
            showToast("正在听/看屏幕录制，请先结束再开实时字幕");
            return;
        }
        if (!capture.isReady()) {
            showToast("声音通道不可用：系统录屏可能占用了授权，请关闭系统录屏后重新开启悬浮助手");
            return;
        }
        showSubtitleWindow();
        LiveSubtitleEngine engine = new LiveSubtitleEngine(this, new LiveSubtitleEngine.Listener() {
            @Override
            public void onPartial(String zh, String en) {
                if (subZh == null || subEn == null) return;
                subZh.setText(zh);
                subEn.setText(en);
            }

            @Override
            public void onFinal(String zh, String en) {
                if (subZh == null || subEn == null) return;
                subZh.setText(zh);
                subEn.setText(en);
            }

            @Override
            public void onTranslated(String zh, String en) {
                if (subZh == null || subEn == null) return;
                subZh.setText(zh);
                subEn.setText(en);
            }

            @Override
            public void onMode(String notice) {
                if (subZh == null || subEn == null) return;
                subZh.setText("正在识别…");
                subEn.setText(notice);
            }

            @Override
            public void onError(String message) {
                if (subZh == null || subEn == null) return;
                subZh.setText("实时字幕不可用");
                subEn.setText(message);
                LiveSubtitleEngine e = subtitleEngine;
                subtitleEngine = null;
                updateBallMode();
                if (e != null) new Thread(e::stop, "subtitle-stop").start();
            }
        });
        if (!engine.start(capture.getProjection())) {
            hideSubtitleWindow();
            showToast("无法监听屏幕声音，请重新开启悬浮助手后再试");
            return;
        }
        subtitleEngine = engine;
        updateBallMode();
        tick(35);
        showToast("实时字幕已开启，再次长按 2 秒关闭");
    }

    private void stopSubtitles() {
        LiveSubtitleEngine engine = subtitleEngine;
        subtitleEngine = null;
        hideSubtitleWindow();
        updateBallMode();
        if (engine != null) new Thread(engine::stop, "subtitle-stop").start();
    }

    private void showSubtitleWindow() {
        hideSubtitleWindow();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_subtitle);
        int ph = Ui.dp(this, 14);
        int pv = Ui.dp(this, 10);
        root.setPadding(ph, pv, ph, pv);

        subZh = new TextView(this);
        subZh.setText("正在听屏幕声音…");
        subZh.setTextColor(Color.WHITE);
        subZh.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        subZh.setTypeface(Typeface.DEFAULT_BOLD);
        subZh.setMaxLines(2);
        subZh.setGravity(Gravity.CENTER);
        subZh.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        subZh.setEllipsize(android.text.TextUtils.TruncateAt.END);
        subZh.setLineSpacing(Ui.dp(this, 2), 1f);
        root.addView(subZh, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        subEn = new TextView(this);
        subEn.setText("Listening…");
        subEn.setTextColor(0xCCFFFFFF);
        subEn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        subEn.setMaxLines(2);
        subEn.setGravity(Gravity.CENTER);
        subEn.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        subEn.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams enLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        enLp.topMargin = Ui.dp(this, 4);
        root.addView(subEn, enLp);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                Ui.screenWidth(this) - Ui.dp(this, 24),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                android.graphics.PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.y = Ui.dp(this, 76);
        try {
            wm.addView(root, params);
            subtitleView = root;
        } catch (Exception e) {
            subtitleView = null;
        }
    }

    private void hideSubtitleWindow() {
        if (subtitleView != null) {
            try {
                wm.removeView(subtitleView);
            } catch (Exception ignored) {
            }
        }
        subtitleView = null;
        subZh = null;
        subEn = null;
    }

    private File saveAudio() {
        if (audioCapture == null) return null;
        File dir = new File(getFilesDir(), "audio");
        if (!dir.exists()) dir.mkdirs();
        File out = new File(dir, "listen_" + System.currentTimeMillis() + ".wav");
        File f = audioCapture.stopAndSave(out);
        audioCapture = null;
        if (f != null) cleanupAudio(dir);
        return f;
    }

    private void cleanupAudio(File dir) {
        File[] files = dir.listFiles();
        if (files == null || files.length <= 4) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < files.length - 4; i++) files[i].delete();
    }

    private Bitmap scale(Bitmap b, int max) {
        int w = b.getWidth();
        int h = b.getHeight();
        int big = Math.max(w, h);
        if (big <= max) return b;
        float r = max / (float) big;
        return Bitmap.createScaledBitmap(b, Math.round(w * r), Math.round(h * r), true);
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private void runCapture(Consumer<Bitmap> then) {
        boolean canProj = capture.isReady();
        boolean canAcc = ScreenAccessibilityService.isReady();
        if (!canProj && !canAcc) {
            showToast("截图通道被占用：系统录屏可能正在运行。请关闭系统录屏，或在 App 里开启「无障碍截图」备用");
            return;
        }
        if (busy) return;
        busy = true;
        if (button != null) button.setVisibility(View.INVISIBLE);
        new Thread(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            Bitmap bmp = captureAny();
            final Bitmap shot = bmp;
            main.post(() -> {
                busy = false;
                if (button != null) button.setVisibility(View.VISIBLE);
                if (shot == null) {
                    if (canAcc) {
                        showToast("截图失败，请再试一次");
                    } else {
                        showToast("截图失败：系统录屏占用了截图通道，请在 App 开启「无障碍截图」备用");
                    }
                    return;
                }
                then.accept(shot);
            });
        }, "screen-shot").start();
    }

    /** 优先 MediaProjection；取不到帧或黑屏时自动切换到无障碍截图 */
    private Bitmap captureAny() {
        Bitmap bmp = null;
        if (capture.isReady()) {
            bmp = capture.capture();
            if (bmp != null && isBlank(bmp)) {
                bmp.recycle();
                bmp = null;
            }
        }
        if (bmp == null) {
            bmp = ScreenAccessibilityService.captureSync(3500);
        }
        return bmp;
    }

    /** 判断是不是整屏纯黑（系统录屏抢占时的典型表现） */
    private boolean isBlank(Bitmap b) {
        if (b == null) return true;
        int w = b.getWidth();
        int h = b.getHeight();
        if (w <= 0 || h <= 0) return true;
        int stepX = Math.max(1, w / 16);
        int stepY = Math.max(1, h / 16);
        for (int y = stepY / 2; y < h; y += stepY) {
            for (int x = stepX / 2; x < w; x += stepX) {
                if ((b.getPixel(x, y) & 0x00FFFFFF) != 0) return false;
            }
        }
        return true;
    }

    // ---------------- 面板 ----------------

    private static class PanelState {
        Bitmap shot;
        boolean askMode;
        volatile String ocr;
        volatile boolean ocrDone;
        final CountDownLatch latch = new CountDownLatch(1);

        LinearLayout root;
        TextView title;
        String baseTitle;
        TextView answer;
        ScrollView answerScroll;
        EditText input;
        TextView send;
        FrameLayout thumbBox;
    }

    private PanelState showPanel(String titleText, Bitmap shot, boolean askMode) {
        closePanel();
        final PanelState st = new PanelState();
        st.shot = shot;
        st.askMode = askMode;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_panel);
        int p = Ui.dp(this, 16);
        root.setPadding(p, p, p, Ui.dp(this, 12));
        st.root = root;

        // 标题栏
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        st.title = new TextView(this);
        st.title.setText(titleText);
        st.baseTitle = titleText;
        st.title.setTextColor(Color.WHITE);
        st.title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        st.title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(st.title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView close = chip("关闭");
        close.setOnClickListener(v -> closePanel());
        header.addView(close);
        root.addView(header);

        // 输入框（提问模式，放在顶部，避免被键盘遮挡）
        if (askMode) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.topMargin = Ui.dp(this, 12);

            st.input = new EditText(this);
            st.input.setHint("想问我截图里的什么？");
            st.input.setTextColor(Color.WHITE);
            st.input.setHintTextColor(0x99FFFFFF);
            st.input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            st.input.setBackgroundResource(R.drawable.bg_input_dark);
            st.input.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
            st.input.setMinHeight(Ui.dp(this, 44));
            st.input.setMaxLines(3);
            st.input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            st.input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND);
            st.input.setOnEditorActionListener((v, actionId, event) -> {
                sendQuestion(st);
                return true;
            });
            row.addView(st.input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            st.send = chip("发送");
            LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sendLp.leftMargin = Ui.dp(this, 8);
            st.send.setBackgroundResource(R.drawable.bg_btn_primary);
            st.send.setTextColor(Color.WHITE);
            st.send.setTypeface(Typeface.DEFAULT_BOLD);
            st.send.setOnClickListener(v -> sendQuestion(st));
            row.addView(st.send, sendLp);

            root.addView(row, rowLp);
        }

        // 截图缩略图（听屏总结没有截图）
        if (shot != null) {
            FrameLayout thumbBox = new FrameLayout(this);
            ImageView thumb = new ImageView(this);
            thumb.setImageBitmap(shot);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            GradientDrawable round = new GradientDrawable();
            round.setColor(0xFF2A2F3A);
            round.setCornerRadius(Ui.dp(this, 10));
            thumb.setBackground(round);
            thumb.setClipToOutline(true);
            thumbBox.addView(thumb, new FrameLayout.LayoutParams(Ui.dp(this, 128), Ui.dp(this, 84)));
            LinearLayout.LayoutParams thumbLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            thumbLp.topMargin = Ui.dp(this, 12);
            root.addView(thumbBox, thumbLp);
        }

        // 回答区
        st.answerScroll = new ScrollView(this);
        st.answerScroll.setVerticalScrollBarEnabled(true);
        st.answer = new TextView(this);
        st.answer.setTextColor(0xFFE9ECF2);
        st.answer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        st.answer.setLineSpacing(Ui.dp(this, 3), 1f);
        st.answer.setTextIsSelectable(true);
        st.answer.setText(askMode ? "可以问我这张截图里的任何内容，也可以直接发送让我帮你看看。" : "");
        st.answerScroll.addView(st.answer);
        LinearLayout.LayoutParams ansLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ansLp.topMargin = Ui.dp(this, 12);
        root.addView(st.answerScroll, ansLp);

        // 底部操作
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams ftLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ftLp.topMargin = Ui.dp(this, 10);

        TextView copy = chip("复制结果");
        copy.setOnClickListener(v -> {
            String text = st.answer.getText().toString();
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("AI 识图结果", text));
            showToast("已复制");
        });
        footer.addView(copy);

        TextView close2 = chip("收起");
        LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipLp.leftMargin = Ui.dp(this, 8);
        close2.setOnClickListener(v -> closePanel());
        footer.addView(close2, chipLp);
        root.addView(footer, ftLp);

        panel = st;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                Math.min(Ui.screenWidth(this) - Ui.dp(this, 28), Ui.dp(this, 600)),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_DIM_BEHIND
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.y = Ui.dp(this, 64);
        params.dimAmount = 0.35f;
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED;
        try {
            wm.addView(root, params);
        } catch (Exception e) {
            panel = null;
            showToast("浮窗创建失败，请检查悬浮窗权限");
            return st;
        }

        // 面板出现后异步 OCR，供提问/翻译使用
        if (shot != null) {
            OcrHelper.recognize(shot, text -> {
                if (st.ocrDone) return;
                st.ocr = text;
                st.ocrDone = true;
                st.latch.countDown();
            });
        }

        return st;
    }

    private TextView chip(String label) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(0xFFE9ECF2);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setGravity(Gravity.CENTER);
        tv.setBackgroundResource(R.drawable.bg_chip_dark);
        tv.setPadding(Ui.dp(this, 14), Ui.dp(this, 7), Ui.dp(this, 14), Ui.dp(this, 7));
        tv.setClickable(true);
        return tv;
    }

    private void setLoading(PanelState st, boolean loading, String text) {
        if (st == null) return;
        if (st.send != null) {
            st.send.setEnabled(!loading);
            st.send.setAlpha(loading ? 0.5f : 1f);
        }
        if (st.title != null) st.title.setText(loading ? st.baseTitle + " · 思考中" : st.baseTitle);
        if (st.answer != null) st.answer.setText(TextUtils.isEmpty(text) ? "…" : text);
        resizeAnswer(st);
    }

    private void resizeAnswer(PanelState st) {
        if (st == null || st.answerScroll == null) return;
        st.answer.post(() -> {
            int content = st.answer.getHeight();
            int max = (int) (Ui.screenHeight(this) * 0.42);
            ViewGroup.LayoutParams lp = st.answerScroll.getLayoutParams();
            int target = Math.min(content, max);
            if (target > 0 && lp.height != target) {
                lp.height = target;
                st.answerScroll.setLayoutParams(lp);
            }
        });
    }

    private void closePanel() {
        if (panel == null) return;
        PanelState old = panel;
        panel = null;
        if (old.input != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(old.input.getWindowToken(), 0);
        }
        try {
            wm.removeView(old.root);
        } catch (Exception ignored) {
        }
    }

    private void sendQuestion(PanelState st) {
        if (st == null || st.input == null) return;
        String q = st.input.getText().toString().trim();
        if (q.isEmpty()) q = "这张截图里有什么？请帮我看看";
        final String question = q;
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(st.input.getWindowToken(), 0);

        setLoading(st, true, "正在看图思考…");
        ensureOcr(st, ocr -> {
            if (TextUtils.isEmpty(ocr) && Prefs.visionModel(this).isEmpty()) {
                setLoading(st, false, "这张截图里没有识别到文字，我暂时看不了图。\n可以在 App 配置页填写视觉模型，或换一张有文字的截图。");
                return;
            }
            AiClient.ask(this, st.shot, ocr, question, AiClient.Mode.ASK, new AiClient.Cb() {
                @Override
                public void onOk(String text) {
                    main.post(() -> {
                        if (panel != st) return;
                        setLoading(st, false, TextUtils.isEmpty(text) ? "（没有返回内容）" : text);
                    });
                }

                @Override
                public void onError(String message) {
                    main.post(() -> {
                        if (panel != st) return;
                        setLoading(st, false, message);
                    });
                }
            });
        });
    }

    private void ensureOcr(PanelState st, Consumer<String> then) {
        String current = st.ocr;
        if (current != null) {
            then.accept(current);
            return;
        }
        if (st.ocrDone) {
            then.accept("");
            return;
        }
        new Thread(() -> {
            try {
                st.latch.await(6, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            String text = st.ocr == null ? "" : st.ocr;
            main.post(() -> then.accept(text));
        }, "wait-ocr").start();
    }

    // ---------------- 快捷菜单 ----------------

    private void showQuickMenu() {
        closeMenu();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackgroundResource(R.drawable.bg_panel);
        int p = Ui.dp(this, 8);
        row.setPadding(p, p, p, p);

        TextView settings = chip("打开设置");
        settings.setOnClickListener(v -> {
            closeMenu();
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        });
        row.addView(settings);

        TextView stop = chip("关闭悬浮球");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(this, 8);
        stop.setOnClickListener(v -> {
            closeMenu();
            stopSelf();
        });
        row.addView(stop, lp);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                android.graphics.PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        int sx = Ui.screenWidth(this);
        params.x = Math.max(Ui.dp(this, 8), Math.min(buttonParams.x, sx - Ui.dp(this, 220)));
        params.y = Math.max(Ui.dp(this, 24), buttonParams.y + buttonSize + Ui.dp(this, 8));
        try {
            wm.addView(row, params);
            menuView = row;
            main.postDelayed(this::closeMenu, 4000);
        } catch (Exception ignored) {
        }
    }

    private void closeMenu() {
        if (menuView == null) return;
        try {
            wm.removeView(menuView);
        } catch (Exception ignored) {
        }
        menuView = null;
    }

    // ---------------- 轻提示 ----------------

    private void showToast(String msg) {
        if (toastView != null) {
            removeView(toastView, true);
            toastView = null;
        }
        TextView tv = new TextView(this);
        tv.setText(msg);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setBackgroundResource(R.drawable.bg_toast);
        tv.setPadding(Ui.dp(this, 16), Ui.dp(this, 9), Ui.dp(this, 16), Ui.dp(this, 9));
        tv.setMaxWidth(Ui.screenWidth(this) - Ui.dp(this, 60));

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                android.graphics.PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.y = Ui.dp(this, 120);
        try {
            wm.addView(tv, params);
            toastView = tv;
            main.postDelayed(() -> {
                if (toastView == tv) {
                    removeView(tv, true);
                    toastView = null;
                }
            }, 2200);
        } catch (Exception ignored) {
        }
    }

    // ---------------- 回调 ----------------

    @Override
    public void onProjectionStop() {
        main.post(() -> {
            stopSubtitles();
            boolean fallback = ScreenAccessibilityService.isReady();
            Prefs.setRunning(this, true);
            publishState(fallback ? "系统录屏占用了截图授权，已切换无障碍截图" : "截图授权已结束，请重新开启");
            if (fallback) {
                showToast("系统录屏占用了截图，已自动切换到无障碍截图");
            } else {
                showToast("截图授权被系统录屏中断，请关闭系统录屏后回 App 重新开启");
            }
        });
    }
}
