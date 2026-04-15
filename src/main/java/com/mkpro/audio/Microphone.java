package com.mkpro.audio;

import javax.sound.sampled.*;
import java.util.function.Consumer;

public class Microphone {
    private TargetDataLine targetDataLine;
    private Thread captureThread;
    private volatile boolean isCapturing;
    private volatile boolean isMuted;
    private final int sampleRate;

    public Microphone(int sampleRate) {
        this.sampleRate = sampleRate;
    }

    public void start(Consumer<byte[]> onAudioData) throws LineUnavailableException {
        if (targetDataLine != null && targetDataLine.isOpen()) {
            return;
        }

        AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);

        if (!AudioSystem.isLineSupported(info)) {
            throw new LineUnavailableException("Microphone not supported for format: " + format);
        }

        targetDataLine = (TargetDataLine) AudioSystem.getLine(info);
        targetDataLine.open(format);
        targetDataLine.start();

        isCapturing = true;
        captureThread = new Thread(() -> {
            byte[] buffer = new byte[2048];
            while (isCapturing) {
                int bytesRead = targetDataLine.read(buffer, 0, buffer.length);
                if (bytesRead > 0 && !isMuted) {
                    byte[] chunk = new byte[bytesRead];
                    System.arraycopy(buffer, 0, chunk, 0, bytesRead);
                    onAudioData.accept(chunk);
                }
            }
        }, "Microphone-Capture-Thread");
        captureThread.setDaemon(true);
        captureThread.start();
    }

    public void setMuted(boolean muted) {
        this.isMuted = muted;
    }

    public void stop() {
        isCapturing = false;
        if (targetDataLine != null) {
            targetDataLine.stop();
            targetDataLine.close();
            targetDataLine = null;
        }
        if (captureThread != null) {
            captureThread.interrupt();
        }
    }
}
