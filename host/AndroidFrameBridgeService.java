package com.winlator;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
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
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Captures the Android display with MediaProjection and publishes the newest RGBA frame
 * into the Winlator C: drive. AndroidFramePresenter.exe reads the same file from Wine.
 *
 * This first bridge deliberately uses a file protocol because it is easy to validate on
 * real devices and under Wine. Once the end-to-end chain is proven, it can be replaced by
 * an AHardwareBuffer/Vulkan zero-copy transport without changing the presenter contract.
 */
public final class AndroidFrameBridgeService extends Service {
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    public static final String EXTRA_FRAME_PATH = "frame_path";
    public static final String ACTION_STOP = "com.lm.androiddlsshost.STOP_BRIDGE";

    private static final String TAG = "AndroidDLSSBridge";
    private static final String CHANNEL = "android_dlss_capture";
    private static final int NOTIFICATION_ID = 7005;
    private static final long MIN_FRAME_GAP_NS = 33_000_000L; // validation bridge: <= ~30 fps
    private static final int HEADER_BYTES = 64;

    private HandlerThread workerThread;
    private Handler worker;
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader reader;
    private RandomAccessFile frameFile;
    private FileChannel channel;
    private final AtomicLong sequence = new AtomicLong();
    private volatile long lastWrittenNs;

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        workerThread = new HandlerThread("android-dlss-frame-writer");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        Notification n = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("Android DLSS Host")
                .setContentText("Captura de tela ativa para a cadeia DLSS5ForAll")
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        String framePath = intent.getStringExtra(EXTRA_FRAME_PATH);
        if (resultCode == 0 || resultData == null || framePath == null || framePath.isEmpty()) {
            Log.e(TAG, "missing MediaProjection permission or frame path");
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            startProjection(resultCode, resultData, new File(framePath));
        } catch (Exception e) {
            Log.e(TAG, "failed to start bridge", e);
            stopSelf();
        }
        return START_STICKY;
    }

    private void startProjection(int resultCode, Intent resultData, File target) throws Exception {
        closeCaptureOnly();
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs())
            throw new IllegalStateException("cannot create frame directory");
        frameFile = new RandomAccessFile(target, "rw");
        channel = frameFile.getChannel();

        DisplayMetrics dm = new DisplayMetrics();
        WindowManager wm = (WindowManager)getSystemService(Context.WINDOW_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.view.WindowMetrics metrics = wm.getCurrentWindowMetrics();
            android.graphics.Rect b = metrics.getBounds();
            dm.widthPixels = b.width(); dm.heightPixels = b.height();
            dm.densityDpi = getResources().getDisplayMetrics().densityDpi;
        } else {
            //noinspection deprecation
            wm.getDefaultDisplay().getRealMetrics(dm);
        }
        int width = Math.max(64, dm.widthPixels);
        int height = Math.max(64, dm.heightPixels);
        int density = Math.max(1, dm.densityDpi);

        MediaProjectionManager mpm = (MediaProjectionManager)getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(resultCode, resultData);
        if (projection == null) throw new IllegalStateException("MediaProjection returned null");
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() {
                Log.i(TAG, "MediaProjection stopped by system/user");
                stopSelf();
            }
        }, worker);

        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(this::onImageAvailable, worker);
        virtualDisplay = projection.createVirtualDisplay(
                "AndroidDLSSFrameBridge", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(), null, worker);
        Log.i(TAG, "bridge active " + width + "x" + height + " -> " + target);
    }

    private void onImageAvailable(ImageReader r) {
        Image image = null;
        try {
            image = r.acquireLatestImage();
            if (image == null || channel == null) return;
            long now = System.nanoTime();
            if (now - lastWrittenNs < MIN_FRAME_GAP_NS) return;

            Image.Plane[] planes = image.getPlanes();
            if (planes.length == 0) return;
            Image.Plane p = planes[0];
            if (p.getPixelStride() != 4) {
                Log.w(TAG, "unexpected pixelStride=" + p.getPixelStride());
                return;
            }
            ByteBuffer pixels = p.getBuffer().duplicate();
            int payloadBytes = pixels.remaining();
            int width = image.getWidth(), height = image.getHeight(), rowStride = p.getRowStride();
            int minimum = rowStride * Math.max(0, height - 1) + width * 4;
            if (payloadBytes < minimum) {
                Log.w(TAG, "short image buffer " + payloadBytes + " < " + minimum);
                return;
            }

            // Publish protocol: payload first, header last. Until the header sequence changes,
            // the Wine reader keeps rendering the previous complete frame.
            writeFully(channel, pixels, HEADER_BYTES);
            long seq = sequence.incrementAndGet();
            ByteBuffer h = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            h.put(new byte[]{'A','D','L','S','S','0','1',0});
            h.putInt(HEADER_BYTES);
            h.putInt(1);
            h.putLong(seq);
            h.putInt(width);
            h.putInt(height);
            h.putInt(rowStride);
            h.putInt(1); // RGBA8888
            h.putLong(image.getTimestamp());
            h.putInt(payloadBytes);
            h.putInt(1); // READY
            h.putLong(0L);
            h.flip();
            writeFully(channel, h, 0);
            long wantedSize = HEADER_BYTES + (long)payloadBytes;
            if (channel.size() != wantedSize) channel.truncate(wantedSize);
            lastWrittenNs = now;
        } catch (Throwable t) {
            Log.e(TAG, "frame write failed", t);
        } finally {
            if (image != null) image.close();
        }
    }

    private static void writeFully(FileChannel ch, ByteBuffer data, long offset) throws Exception {
        long pos = offset;
        while (data.hasRemaining()) {
            int n = ch.write(data, pos);
            if (n <= 0) throw new IllegalStateException("short file write");
            pos += n;
        }
    }

    private void closeCaptureOnly() {
        try { if (virtualDisplay != null) virtualDisplay.release(); } catch (Exception ignored) {}
        virtualDisplay = null;
        try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        reader = null;
        try { if (projection != null) projection.stop(); } catch (Exception ignored) {}
        projection = null;
        try { if (channel != null) channel.close(); } catch (Exception ignored) {}
        channel = null;
        try { if (frameFile != null) frameFile.close(); } catch (Exception ignored) {}
        frameFile = null;
    }

    @Override public void onDestroy() {
        closeCaptureOnly();
        if (workerThread != null) workerThread.quitSafely();
        stopForeground(true);
        super.onDestroy();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel c = new NotificationChannel(CHANNEL, "Captura Android DLSS", NotificationManager.IMPORTANCE_LOW);
        c.setDescription("Captura MediaProjection usada pelo Android DLSS Host");
        nm.createNotificationChannel(c);
    }
}
