package com.zynolist.phonelink;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

public final class MicStreamer {
    public interface Sink {
        void onPcm(byte[] pcm);
    }

    private static final String TAG = "PhoneLinkMic";
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int CHUNK_BYTES = SAMPLE_RATE * 2 / 10;

    private final Sink sink;
    private AudioRecord record;
    private Thread thread;
    private volatile boolean running;

    public MicStreamer(Sink sink) {
        this.sink = sink;
    }

    public synchronized void start() throws Exception {
        stop();
        int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (minBuffer <= 0) throw new Exception("Micrófono no disponible");
        int bufferSize = Math.max(minBuffer * 2, CHUNK_BYTES * 4);
        AudioRecord recorder = new AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize);
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            recorder.release();
            throw new Exception("No se pudo abrir el micrófono");
        }
        record = recorder;
        running = true;
        recorder.startRecording();
        thread = new Thread(() -> capture(recorder), "phonelink-mic");
        thread.start();
    }

    private void capture(AudioRecord recorder) {
        byte[] buffer = new byte[CHUNK_BYTES];
        while (running) {
            int read;
            try {
                read = recorder.read(buffer, 0, buffer.length);
            } catch (Exception e) {
                if (running) Log.w(TAG, "lectura: " + e);
                break;
            }
            if (read <= 0) continue;
            int even = read - (read % 2);
            if (even <= 0) continue;
            byte[] chunk = new byte[even];
            System.arraycopy(buffer, 0, chunk, 0, even);
            try {
                sink.onPcm(chunk);
            } catch (Exception e) {
                Log.w(TAG, "envío: " + e);
            }
        }
    }

    public synchronized void stop() {
        running = false;
        AudioRecord recorder = record;
        record = null;
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (Exception ignored) {
            }
            try {
                recorder.release();
            } catch (Exception ignored) {
            }
        }
        Thread current = thread;
        thread = null;
        if (current != null) {
            current.interrupt();
            try {
                current.join(1000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public boolean isRunning() {
        return running;
    }

    public int sampleRate() {
        return SAMPLE_RATE;
    }
}
