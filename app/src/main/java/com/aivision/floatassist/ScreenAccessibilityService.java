package com.aivision.floatassist;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.provider.Settings;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 备用截图通道：无障碍服务截图。
 * 系统录屏（小米 HyperOS 等）抢占 MediaProjection 时，用这条通道仍能取到画面。
 * 只在用户点击悬浮球时调用，不会读取窗口内容。
 */
public class ScreenAccessibilityService extends AccessibilityService {

    private static volatile ScreenAccessibilityService instance;
    private static volatile String lastError = "";

    public static boolean isReady() {
        return Build.VERSION.SDK_INT >= 30 && instance != null;
    }

    public static String lastError() {
        return lastError;
    }

    /** 是否在系统设置里启用了本服务（不一定已连接） */
    public static boolean isEnabledInSettings(android.content.Context c) {
        try {
            String enabled = Settings.Secure.getString(c.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null) return false;
            String me = c.getPackageName() + "/" + ScreenAccessibilityService.class.getName();
            String meShort = c.getPackageName() + "/.ScreenAccessibilityService";
            return enabled.contains(me) || enabled.contains(meShort);
        } catch (Exception e) {
            return false;
        }
    }

    /** 阻塞式截图，必须在子线程调用 */
    public static Bitmap captureSync(long timeoutMs) {
        ScreenAccessibilityService s = instance;
        if (s == null || Build.VERSION.SDK_INT < 30) {
            lastError = "无障碍截图服务未连接";
            return null;
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Bitmap> ref = new AtomicReference<>();
        try {
            s.takeScreenshot(Display.DEFAULT_DISPLAY, s.getMainExecutor(), new TakeScreenshotCallback() {
                @Override
                public void onSuccess(ScreenshotResult result) {
                    HardwareBuffer buffer = null;
                    try {
                        buffer = result.getHardwareBuffer();
                        Bitmap hw = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                        if (hw != null) {
                            ref.set(hw.copy(Bitmap.Config.ARGB_8888, false));
                        }
                        lastError = "";
                    } catch (Exception e) {
                        lastError = "转换截图失败：" + e.getMessage();
                    } finally {
                        if (buffer != null) {
                            try {
                                buffer.close();
                            } catch (Exception ignored) {
                            }
                        }
                        latch.countDown();
                    }
                }

                @Override
                public void onFailure(int errorCode) {
                    lastError = "无障碍截图失败（错误码 " + errorCode + "）";
                    latch.countDown();
                }
            });
        } catch (Exception e) {
            lastError = "无障碍截图异常：" + e.getMessage();
            return null;
        }
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        Bitmap bmp = ref.get();
        if (bmp == null && lastError.isEmpty()) lastError = "无障碍截图超时";
        return bmp;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        lastError = "";
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
