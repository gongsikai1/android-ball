package com.aivision.floatassist;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.nio.ByteBuffer;

/** 屏幕截图：MediaProjection + VirtualDisplay + ImageReader */
public class CaptureManager {

    public interface CallbackHost {
        void onProjectionStop();
    }

    private final Context context;
    private final Handler mainHandler = new Handler(android.os.Looper.getMainLooper());

    private HandlerThread thread;
    private Handler handler;
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private int width;
    private int height;
    private int density;
    private volatile boolean ready;

    public CaptureManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public android.media.projection.MediaProjection getProjection() {
        return projection;
    }

    public boolean isReady() {
        return ready;
    }

    public boolean start(int resultCode, Intent data, CallbackHost host) {
        if (ready) return true;
        if (data == null) return false;
        MediaProjectionManager mgr = (MediaProjectionManager) context.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mgr == null) return false;
        try {
            projection = mgr.getMediaProjection(resultCode, data);
        } catch (Exception e) {
            return false;
        }
        if (projection == null) return false;

        thread = new HandlerThread("screen-capture");
        thread.start();
        handler = new Handler(thread.getLooper());

        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                ready = false;
                if (host != null) new Handler(android.os.Looper.getMainLooper()).post(host::onProjectionStop);
            }
        }, handler);

        DisplayMetrics dm = new DisplayMetrics();
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return false;
        wm.getDefaultDisplay().getRealMetrics(dm);
        width = dm.widthPixels;
        height = dm.heightPixels;
        density = dm.densityDpi;

        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
        try {
            display = projection.createVirtualDisplay(
                    "ai-float-capture", width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.getSurface(), null, handler);
        } catch (Exception e) {
            return false;
        }
        ready = display != null;
        return ready;
    }

    /** 必须在子线程调用 */
    public Bitmap capture() {
        if (!ready || reader == null) return null;
        Image image = null;
        for (int i = 0; i < 30 && image == null; i++) {
            try {
                image = reader.acquireLatestImage();
            } catch (IllegalStateException e) {
                return null;
            }
            if (image == null) {
                try {
                    Thread.sleep(40);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        if (image == null) return null;
        try {
            return toBitmap(image);
        } catch (Exception e) {
            return null;
        } finally {
            image.close();
        }
    }

    private Bitmap toBitmap(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * width;
        int paddedWidth = width + rowPadding / pixelStride;

        Bitmap padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888);
        buffer.rewind();
        padded.copyPixelsFromBuffer(buffer);
        if (paddedWidth == width) return padded;
        Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, width, height);
        padded.recycle();
        return cropped;
    }

    public void release() {
        ready = false;
        try {
            if (display != null) display.release();
        } catch (Exception ignored) {
        }
        display = null;
        try {
            if (reader != null) reader.close();
        } catch (Exception ignored) {
        }
        reader = null;
        try {
            if (projection != null) projection.stop();
        } catch (Exception ignored) {
        }
        projection = null;
        if (thread != null) thread.quitSafely();
        thread = null;
        handler = null;
    }
}
