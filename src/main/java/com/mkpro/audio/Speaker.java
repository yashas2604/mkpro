package com.mkpro.audio;

import javax.sound.sampled.*;
import java.util.concurrent.LinkedBlockingQueue;

public class Speaker {
    private SourceDataLine sourceDataLine;
    private final int sampleRate;
    private final LinkedBlockingQueue<byte[]> audioQueue = new LinkedBlockingQueue<>();
    private Thread playbackThread;
    private volatile boolean isPlaying;

    public Speaker(int sampleRate) {
        this.sampleRate = sampleRate;
    }

    public void start() throws LineUnavailableException {
        if (sourceDataLine != null && sourceDataLine.isOpen()) {
            return;
        }

        AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);

        if (!AudioSystem.isLineSupported(info)) {
            throw new LineUnavailableException("Speaker not supported for format: " + format);
        }

        sourceDataLine = (SourceDataLine) AudioSystem.getLine(info);
        sourceDataLine.open(format);
        sourceDataLine.start();

        isPlaying = true;
        playbackThread = new Thread(() -> {
            while (isPlaying) {
                try {
                    byte[] data = audioQueue.take();
                    if (sourceDataLine != null && sourceDataLine.isOpen()) {
                        sourceDataLine.write(data, 0, data.length);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "Speaker-Playback-Thread");
        playbackThread.setDaemon(true);
        playbackThread.start();
    }

    public void play(byte[] audioData) {
        audioQueue.offer(audioData);
    }

    public void stop() {
        isPlaying = false;
        if (playbackThread != null) {
            playbackThread.interrupt();
        }
        if (sourceDataLine != null) {
            sourceDataLine.drain();
            sourceDataLine.stop();
            sourceDataLine.close();
            sourceDataLine = null;
        }
    }
}
