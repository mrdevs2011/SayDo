package uz.saydo.app.kernel.voice;

import java.util.Arrays;

/**
 * Ultra-low latency streaming audio capture ring buffer & VAD energy monitor.
 * Provides sub-40ms reaction times and dynamic noise-floor calibration.
 */
public final class NativeAudioCapture {

    public interface AudioFrameListener {
        void onAudioFrame(short[] pcmBuffer, int length, double rmsEnergy, boolean isVoiceActive);
    }

    private final int sampleRate;
    private final int bufferCapacity;
    private final short[] circularBuffer;
    private int writeHead = 0;
    private double ambientNoiseFloor = 200.0; // Initial estimate
    private AudioFrameListener frameListener;

    public NativeAudioCapture(int sampleRate, int capacitySamples) {
        this.sampleRate = Math.max(8000, sampleRate);
        this.bufferCapacity = Math.max(1024, capacitySamples);
        this.circularBuffer = new short[bufferCapacity];
    }

    public synchronized void setFrameListener(AudioFrameListener listener) {
        this.frameListener = listener;
    }

    /**
     * Ingests incoming raw PCM short frame (16kHz mono).
     */
    public synchronized void writePcmData(short[] pcm, int offset, int length) {
        if (pcm == null || length <= 0) return;

        double sumSquares = 0.0;
        for (int i = 0; i < length; i++) {
            short sample = pcm[offset + i];
            sumSquares += (double) sample * sample;
            circularBuffer[writeHead] = sample;
            writeHead = (writeHead + 1) % bufferCapacity;
        }

        double rms = Math.sqrt(sumSquares / length);
        // Adaptive noise floor exponential moving average
        if (rms < ambientNoiseFloor * 1.5) {
            ambientNoiseFloor = (0.95 * ambientNoiseFloor) + (0.05 * rms);
        }

        boolean isSpeech = rms > (ambientNoiseFloor * 2.2);

        if (frameListener != null) {
            short[] copy = Arrays.copyOfRange(pcm, offset, offset + length);
            frameListener.onAudioFrame(copy, length, rms, isSpeech);
        }
    }

    public synchronized double getAmbientNoiseFloor() {
        return ambientNoiseFloor;
    }

    public synchronized void reset() {
        writeHead = 0;
        Arrays.fill(circularBuffer, (short) 0);
    }
}
