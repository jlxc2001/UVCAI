package com.tencent.nanodetncnn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
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
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Toast;

import java.nio.ByteBuffer;

public class ScreenDetectService extends Service {
    public static final String ACTION_STOP = "com.tencent.nanodetncnn.action.STOP_SCREEN_DETECT";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";
    public static final String EXTRA_MODEL_ID = "modelId";
    public static final String EXTRA_CPU_GPU = "cpuGpu";

    private static final String CHANNEL_ID = "screen_detect";
    private static final int NOTIFICATION_ID = 10086;

    // 32 位弱性能设备建议 200~350ms；数值越大越省资源，识别刷新越慢。
    private static final long INFER_INTERVAL_MS = 220;
    // 先降到 640 宽再送进 native，降低 32 位设备内存和 CPU 压力。
    private static final int MAX_DETECT_WIDTH = 640;

    private final NanoDetNcnn detector = new NanoDetNcnn();

    private WindowManager windowManager;
    private DetectionOverlayView overlayView;
    private WindowManager.LayoutParams overlayParams;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread captureThread;
    private Handler captureHandler;
    private final Handler mainHandler = new Handler();

    private int screenWidth;
    private int screenHeight;
    private int screenDensity;
    private long lastInferTime;
    private boolean detecting;

    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override
        public void onStop() {
            stopCapture();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundCompat();
        createOverlay();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (intent == null) {
            return START_NOT_STICKY;
        }

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        int modelId = intent.getIntExtra(EXTRA_MODEL_ID, 3);
        int cpuGpu = intent.getIntExtra(EXTRA_CPU_GPU, 0);

        // 32 位设备建议强制 CPU；如果你确认 Vulkan 可用，再允许用户选 GPU。
        if ("armeabi-v7a".equals(Build.CPU_ABI)) {
            cpuGpu = 0;
        }

        boolean loaded = detector.loadModel(getAssets(), modelId, cpuGpu);
        if (!loaded) {
            Toast.makeText(this, "NanoDet 模型加载失败", Toast.LENGTH_LONG).show();
            stopSelf();
            return START_NOT_STICKY;
        }

        startCapture(resultCode, resultData);
        return START_STICKY;
    }

    private void startForegroundCompat() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "屏幕识别",
                    NotificationManager.IMPORTANCE_LOW
            );
            nm.createNotificationChannel(channel);
        }

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        builder.setContentTitle("NanoDet 屏幕识别运行中")
                .setContentText("正在通过录屏帧识别当前画面，并用悬浮窗绘制识别框")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true);

        startForeground(NOTIFICATION_ID, builder.build());
    }

    private void createOverlay() {
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (windowManager == null || overlayView != null) {
            return;
        }

        overlayView = new DetectionOverlayView(this);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        overlayParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_FULLSCREEN
                        // 防止悬浮窗自己被 MediaProjection 录进去，避免识别框递归污染画面。
                        | WindowManager.LayoutParams.FLAG_SECURE,
                PixelFormat.TRANSLUCENT
        );
        overlayParams.gravity = Gravity.TOP | Gravity.LEFT;

        windowManager.addView(overlayView, overlayParams);
    }

    private void startCapture(int resultCode, Intent resultData) {
        stopCapture();

        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (manager == null || resultData == null) {
            stopSelf();
            return;
        }

        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        screenDensity = metrics.densityDpi;

        captureThread = new HandlerThread("screen-capture-nanodet");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader reader) {
                handleImage(reader);
            }
        }, captureHandler);

        mediaProjection = manager.getMediaProjection(resultCode, resultData);
        if (mediaProjection == null) {
            stopSelf();
            return;
        }
        mediaProjection.registerCallback(projectionCallback, captureHandler);

        virtualDisplay = mediaProjection.createVirtualDisplay(
                "nanodet-screen-capture",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                captureHandler
        );
    }

    private void handleImage(ImageReader reader) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) {
                return;
            }

            long now = SystemClock.uptimeMillis();
            if (detecting || now - lastInferTime < INFER_INTERVAL_MS) {
                return;
            }
            detecting = true;
            lastInferTime = now;

            Bitmap bitmap = imageToBitmap(image);
            if (bitmap == null) {
                detecting = false;
                return;
            }

            Bitmap detectBitmap = maybeScaleBitmap(bitmap);
            if (detectBitmap != bitmap) {
                bitmap.recycle();
            }

            final float[] result = detector.detectBitmap(detectBitmap);
            detectBitmap.recycle();

            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (overlayView != null) {
                        overlayView.updateDetections(result);
                    }
                }
            });
        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            if (image != null) {
                image.close();
            }
            detecting = false;
        }
    }

    private Bitmap imageToBitmap(Image image) {
        Image.Plane[] planes = image.getPlanes();
        if (planes == null || planes.length == 0) {
            return null;
        }

        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int width = image.getWidth();
        int height = image.getHeight();
        int rowPaddingPixels = (rowStride - pixelStride * width) / pixelStride;

        Bitmap padded = Bitmap.createBitmap(width + rowPaddingPixels, height, Bitmap.Config.ARGB_8888);
        buffer.rewind();
        padded.copyPixelsFromBuffer(buffer);

        Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, width, height);
        padded.recycle();
        return cropped;
    }

    private Bitmap maybeScaleBitmap(Bitmap bitmap) {
        if (bitmap.getWidth() <= MAX_DETECT_WIDTH) {
            return bitmap;
        }
        int newWidth = MAX_DETECT_WIDTH;
        int newHeight = Math.max(1, bitmap.getHeight() * newWidth / bitmap.getWidth());
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true);
    }

    private void stopCapture() {
        try {
            if (virtualDisplay != null) {
                virtualDisplay.release();
                virtualDisplay = null;
            }
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }

            MediaProjection mp = mediaProjection;
            mediaProjection = null;
            if (mp != null) {
                try {
                    mp.unregisterCallback(projectionCallback);
                } catch (Throwable ignored) {
                }
                try {
                    mp.stop();
                } catch (Throwable ignored) {
                }
            }

            if (captureThread != null) {
                captureThread.quitSafely();
                captureThread = null;
                captureHandler = null;
            }
            if (overlayView != null) {
                overlayView.clear();
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private void removeOverlay() {
        if (windowManager != null && overlayView != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Throwable ignored) {
            }
            overlayView = null;
        }
    }

    @Override
    public void onDestroy() {
        stopCapture();
        removeOverlay();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
