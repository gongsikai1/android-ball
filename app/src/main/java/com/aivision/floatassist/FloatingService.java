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

import java.util.concurrent.CountDownLatch;
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

    private WindowManager wm;
    private Handler main;
    private TextView button;
    private WindowManager.LayoutParams buttonParams;
    private int buttonSize;

    private CaptureManager capture;
    private boolean busy;

    private int tapCount;
    private Runnable tapResolve;

    private PanelState panel;
    private View toastView;
    private View menuView;


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
            tick(20);
            showQuickMenu();
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
                    main.postDelayed(longPress, 520);
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
        if (tapCount >= 3) {
            main.post(this::resolveTaps);
        } else {
            main.postDelayed(tapResolve, MULTI_TAP_WINDOW);
        }
    }

    private void resolveTaps() {
        int count = tapCount;
        tapCount = 0;
        if (count == 1) {
            onSingleTap();
        } else if (count == 2) {
            onDoubleTap();
        } else if (count >= 3) {
            onTripleTap();
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

    private void runCapture(Consumer<Bitmap> then) {
        if (!capture.isReady()) {
            showToast("截图授权已失效，请回到 App 重新开启悬浮助手");
            return;
        }
        if (busy) return;
        busy = true;
        if (button != null) button.setVisibility(View.INVISIBLE);
        new Thread(() -> {
            Bitmap bmp;
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            bmp = capture.capture();
            final Bitmap shot = bmp;
            main.post(() -> {
                busy = false;
                if (button != null) button.setVisibility(View.VISIBLE);
                if (shot == null) {
                    showToast("截图失败，请再试一次");
                    return;
                }
                then.accept(shot);
            });
        }, "screen-shot").start();
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

        // 截图缩略图
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
        OcrHelper.recognize(shot, text -> {
            if (st.ocrDone) return;
            st.ocr = text;
            st.ocrDone = true;
            st.latch.countDown();
        });

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
            Prefs.setRunning(this, false);
            publishState("截图授权已结束，请重新开启");
            showToast("截图授权已结束，请回到 App 重新开启");
        });
    }
}
