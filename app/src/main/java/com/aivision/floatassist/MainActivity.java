package com.aivision.floatassist;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

/** 首次配置 + 开启悬浮助手 */
public class MainActivity extends Activity {

    private static final int REQ_CAPTURE = 1001;
    private static final int REQ_NOTIFICATION = 1002;

    private EditText etKey, etBase, etModel, etVision;
    private TextView tvPerm, tvAlpha, tvStatus;
    private SeekBar seekAlpha;
    private Button btnGallery;

    private BroadcastReceiver stateReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etKey = findViewById(R.id.et_key);
        etBase = findViewById(R.id.et_base);
        etModel = findViewById(R.id.et_model);
        etVision = findViewById(R.id.et_vision);
        tvPerm = findViewById(R.id.tv_perm);
        tvAlpha = findViewById(R.id.tv_alpha);
        tvStatus = findViewById(R.id.tv_status);
        seekAlpha = findViewById(R.id.seek_alpha);
        btnGallery = findViewById(R.id.btn_gallery);

        loadConfig();

        findViewById(R.id.btn_save).setOnClickListener(v -> {
            saveConfig();
            toast("配置已保存");
        });

        findViewById(R.id.btn_test).setOnClickListener(v -> testConnection());

        findViewById(R.id.btn_perm).setOnClickListener(v -> requestOverlayPermission());

        findViewById(R.id.btn_start).setOnClickListener(v -> startAssistant());

        findViewById(R.id.btn_stop).setOnClickListener(v -> stopAssistant());

        btnGallery.setOnClickListener(v -> startActivity(new Intent(this, GalleryActivity.class)));

        seekAlpha.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (progress < 25) progress = 25;
                tvAlpha.setText(progress + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int value = Math.max(25, seekBar.getProgress());
                Prefs.sp(MainActivity.this).edit().putInt("alpha", value).apply();
            }
        });

        stateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String text = intent.getStringExtra(FloatingService.EXTRA_STATE_TEXT);
                boolean running = intent.getBooleanExtra("running", Prefs.running(MainActivity.this));
                if (!TextUtils.isEmpty(text)) tvStatus.setText("状态：" + text);
                if (!running) tvStatus.setText("状态：未运行");
            }
        };

        registerStateReceiver();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPermissionState();
        refreshStatus();
    }

    @Override
    protected void onDestroy() {
        if (stateReceiver != null) {
            try {
                unregisterReceiver(stateReceiver);
            } catch (Exception ignored) {
            }
        }
        super.onDestroy();
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerStateReceiver() {
        IntentFilter filter = new IntentFilter(FloatingService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, filter);
        }
    }

    private void loadConfig() {
        etKey.setText(Prefs.key(this));
        etBase.setText(Prefs.baseUrl(this));
        etModel.setText(Prefs.model(this));
        etVision.setText(Prefs.visionModel(this));
        int alpha = Prefs.sp(this).getInt("alpha", 85);
        if (alpha < 25) alpha = 25;
        seekAlpha.setProgress(alpha);
        tvAlpha.setText(alpha + "%");
    }

    private void saveConfig() {
        String base = etBase.getText().toString().trim();
        if (!base.isEmpty() && !base.startsWith("http://") && !base.startsWith("https://")) {
            base = "https://" + base;
        }
        if (base.isEmpty()) base = "https://api.deepseek.com";
        String model = etModel.getText().toString().trim();
        if (model.isEmpty()) model = "deepseek-chat";
        Prefs.sp(this).edit()
                .putString("api_key", etKey.getText().toString().trim())
                .putString("base_url", base)
                .putString("model", model)
                .putString("vision_model", etVision.getText().toString().trim())
                .apply();
        etBase.setText(base);
        etModel.setText(model);
    }

    private void testConnection() {
        saveConfig();
        String key = Prefs.key(this);
        if (key.isEmpty()) {
            toast("请先填写 DeepSeek API Key");
            return;
        }
        tvStatus.setText("状态：正在测试连接…");
        AiClient.test(this, new AiClient.Cb() {
            @Override
            public void onOk(String text) {
                tvStatus.setText("状态：连接成功（" + Prefs.model(MainActivity.this) + "）");
                toast("连接成功：" + text);
            }

            @Override
            public void onError(String message) {
                tvStatus.setText("状态：连接失败");
                toast(message);
            }
        });
    }

    private void refreshPermissionState() {
        boolean overlay = Settings.canDrawOverlays(this);
        tvPerm.setText(overlay ? "悬浮窗权限：已授予" : "悬浮窗权限：未授予");
        tvPerm.setTextColor(overlay ? 0xFF12B76A : 0xFFB42318);
    }

    private void refreshStatus() {
        int count = ShotStore.count(this);
        btnGallery.setText("AI 截图库（" + count + "）");
        tvStatus.setText(Prefs.running(this) ? "状态：悬浮球运行中" : "状态：未运行");
    }

    private void requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            toast("已拥有悬浮窗权限");
            return;
        }
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
        }
    }

    private void startAssistant() {
        saveConfig();

        if (Prefs.key(this).isEmpty()) {
            toast("请先填写 DeepSeek API Key");
            etKey.requestFocus();
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("请先授予悬浮窗权限");
            requestOverlayPermission();
            return;
        }
        requestNotificationPermissionIfNeeded();

        MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mgr == null) {
            toast("当前设备不支持屏幕截图");
            return;
        }
        tvStatus.setText("状态：等待截图授权…");
        startActivityForResult(mgr.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            tvStatus.setText("状态：已取消截图授权");
            toast("没有截图权限，悬浮助手无法识图");
            return;
        }
        Intent svc = new Intent(this, FloatingService.class)
                .setAction(FloatingService.ACTION_START)
                .putExtra(FloatingService.EXTRA_CODE, resultCode)
                .putExtra(FloatingService.EXTRA_DATA, data);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
            Prefs.setRunning(this, true);
            tvStatus.setText("状态：正在启动悬浮球…");
            toast("悬浮球已开启，可以退出 App 去其他界面使用");
        } catch (Exception e) {
            tvStatus.setText("状态：启动失败");
            toast("启动失败：" + e.getMessage());
        }
    }

    private void stopAssistant() {
        Intent svc = new Intent(this, FloatingService.class).setAction(FloatingService.ACTION_STOP);
        try {
            startService(svc);
        } catch (Exception ignored) {
        }
        Prefs.setRunning(this, false);
        tvStatus.setText("状态：未运行");
        toast("已停止悬浮助手");
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
