package com.zynolist.phonelink;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.WindowManager;

import java.nio.ByteBuffer;
import java.util.Collections;

public final class CameraStreamer {
    public interface Sink {
        void onJpeg(byte[] jpeg, String facing);
        void onError(String message);
    }

    private static final String TAG = "PhoneLinkCamera";
    private static final long MIN_FRAME_GAP_MS = 350;
    private static final int TARGET_WIDTH = 640;
    private static final int TARGET_HEIGHT = 480;

    private final Context context;
    private final Sink sink;
    private HandlerThread thread;
    private Handler handler;
    private CameraManager manager;
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader reader;
    private String facing = "back";
    private volatile boolean running;
    private long lastFrameAt;
    private int sensorOrientation = 90;
    private int cameraFacingValue = CameraCharacteristics.LENS_FACING_BACK;

    public CameraStreamer(Context context, Sink sink) {
        this.context = context.getApplicationContext();
        this.sink = sink;
    }

    public synchronized void start(String requestedFacing) throws Exception {
        stop();
        facing = "front".equals(requestedFacing) ? "front" : "back";
        manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) throw new Exception("Cámara no disponible");
        String cameraId = findCameraId();
        if (cameraId == null) throw new Exception("No se encontró la cámara " + facing);
        thread = new HandlerThread("phonelink-camera");
        thread.start();
        handler = new Handler(thread.getLooper());
        running = true;
        lastFrameAt = 0;
        final String id = cameraId;
        handler.post(() -> open(id));
    }

    private String findCameraId() throws CameraAccessException {
        int wanted = "front".equals(facing)
                ? CameraCharacteristics.LENS_FACING_FRONT
                : CameraCharacteristics.LENS_FACING_BACK;
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
            Integer value = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (value == null || value != wanted) continue;
            cameraFacingValue = value;
            Integer orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = orientation == null ? 90 : orientation;
            return id;
        }
        return null;
    }

    private void open(String cameraId) {
        try {
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    if (!running) {
                        camera.close();
                        return;
                    }
                    device = camera;
                    createSession();
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    if (device == camera) device = null;
                    fail("Cámara desconectada");
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    camera.close();
                    if (device == camera) device = null;
                    fail("Error de cámara (" + error + ")");
                }
            }, handler);
        } catch (Exception e) {
            fail(message(e));
        }
    }

    private void createSession() {
        try {
            CameraCharacteristics characteristics =
                    manager.getCameraCharacteristics(device.getId());
            StreamConfigurationMap map = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size size = chooseSize(map == null ? null : map.getOutputSizes(ImageFormat.JPEG));
            reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.JPEG, 2);
            reader.setOnImageAvailableListener(this::onImage, handler);
            device.createCaptureSession(Collections.singletonList(reader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession configured) {
                            if (!running) {
                                configured.close();
                                return;
                            }
                            session = configured;
                            try {
                                CaptureRequest.Builder builder =
                                        device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                builder.addTarget(reader.getSurface());
                                builder.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation());
                                applyFocusMode(characteristics, builder);
                                configured.setRepeatingRequest(builder.build(), null, handler);
                            } catch (Exception e) {
                                fail(message(e));
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession configured) {
                            fail("No se pudo configurar la cámara");
                        }
                    }, handler);
        } catch (Exception e) {
            fail(message(e));
        }
    }

    private void applyFocusMode(CameraCharacteristics characteristics,
                                CaptureRequest.Builder builder) {
        try {
            int[] modes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            if (modes == null) return;
            for (int mode : modes) {
                if (mode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) {
                    builder.set(CaptureRequest.CONTROL_AF_MODE, mode);
                    return;
                }
            }
        } catch (Exception ignored) {
        }
    }

    private Size chooseSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) {
            return new Size(TARGET_WIDTH, TARGET_HEIGHT);
        }
        Size best = null;
        long targetArea = (long) TARGET_WIDTH * TARGET_HEIGHT;
        long maxArea = 1280L * 720L;
        for (Size size : sizes) {
            long area = (long) size.getWidth() * size.getHeight();
            if (area < targetArea || area > maxArea) continue;
            if (best == null || area < (long) best.getWidth() * best.getHeight()) best = size;
        }
        if (best != null) return best;
        for (Size size : sizes) {
            if (best == null
                    || (long) size.getWidth() * size.getHeight()
                    < (long) best.getWidth() * best.getHeight()) {
                best = size;
            }
        }
        return best == null ? new Size(TARGET_WIDTH, TARGET_HEIGHT) : best;
    }

    private int jpegOrientation() {
        int rotation = 0;
        try {
            WindowManager windowManager =
                    (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (windowManager != null && windowManager.getDefaultDisplay() != null) {
                switch (windowManager.getDefaultDisplay().getRotation()) {
                    case Surface.ROTATION_90: rotation = 90; break;
                    case Surface.ROTATION_180: rotation = 180; break;
                    case Surface.ROTATION_270: rotation = 270; break;
                    default: rotation = 0; break;
                }
            }
        } catch (Exception ignored) {
        }
        if (cameraFacingValue == CameraCharacteristics.LENS_FACING_FRONT) {
            return (sensorOrientation + rotation) % 360;
        }
        return (sensorOrientation - rotation + 360) % 360;
    }

    private void onImage(ImageReader imageReader) {
        Image image = null;
        try {
            image = imageReader.acquireLatestImage();
            if (image == null) return;
            long now = SystemClock.elapsedRealtime();
            if (now - lastFrameAt < MIN_FRAME_GAP_MS) return;
            lastFrameAt = now;
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            if (running && bytes.length > 0) sink.onJpeg(bytes, facing);
        } catch (Exception e) {
            Log.w(TAG, "frame: " + e);
        } finally {
            if (image != null) image.close();
        }
    }

    private void fail(String text) {
        Log.w(TAG, text);
        if (running && sink != null) sink.onError(text);
    }

    public synchronized void stop() {
        running = false;
        try {
            if (session != null) session.close();
        } catch (Exception ignored) {
        }
        session = null;
        try {
            if (device != null) device.close();
        } catch (Exception ignored) {
        }
        device = null;
        try {
            if (reader != null) reader.close();
        } catch (Exception ignored) {
        }
        reader = null;
        if (thread != null) {
            thread.quitSafely();
            try {
                thread.join(800);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
        handler = null;
    }

    public boolean isRunning() {
        return running;
    }

    public String facing() {
        return facing;
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }
}
