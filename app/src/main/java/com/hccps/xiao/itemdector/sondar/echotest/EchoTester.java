package com.hccps.xiao.itemdector.sondar.echotest;


import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class EchoTester {
    private static final String TAG = "EchoTester";

    // Audio configuration - based on Phase 1 findings
    private static final int SAMPLE_RATE = 48000; // Hz
    private static final int CHIRP_MIN_FREQ = 18000; // Hz
    private static final int CHIRP_MAX_FREQ = 22000; // Hz
    private static final int CHIRP_DURATION_MS = 20; // ms
    private static final int CHIRP_GAP_MS = 500; // ms between chirps
    private static final int BUFFER_SIZE = SAMPLE_RATE / 10; // 100ms buffer
    private static final int TEST_DURATION_MS = 5000; // 5 seconds test

    // Detection thresholds
    private static final double ECHO_THRESHOLD = 40; // Minimum energy for echo detection
    private static final double SIGNAL_NOISE_RATIO_THRESHOLD = 0.5; // Minimum SNR for valid echo

    // Audio components
    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private short[] chirpTemplate;

    // Test state
    private AtomicBoolean isRunning = new AtomicBoolean(false);
    private ExecutorService executor;
    private EchoTestCallback callback;

    // Constructor
    public EchoTester() {
        executor = Executors.newSingleThreadExecutor();
        initAudio();
        generateChirpTemplate();
    }

    // Initialize audio components
    private void initAudio() {
        try {
            // Configure audio record
            int recordBufferSize = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT) * 2;

            Log.d(TAG, "Record buffer size: " + recordBufferSize);

            try {
                audioRecord = new AudioRecord(
                        MediaRecorder.AudioSource.UNPROCESSED, // Raw audio
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        recordBufferSize);

                Log.d(TAG, "Using UNPROCESSED audio source");
            } catch (Exception e) {
                Log.w(TAG, "Failed to initialize UNPROCESSED audio source. Using MIC instead: " + e.getMessage());

                audioRecord = new AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        recordBufferSize);
            }

            // Configure audio track
            int playBufferSize = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT) * 2;

            Log.d(TAG, "Play buffer size: " + playBufferSize);

            audioTrack = new AudioTrack.Builder()
                    .setAudioAttributes(new android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(playBufferSize)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build();

            Log.d(TAG, "Audio components initialized successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error initializing audio components", e);
        }
    }

    // Generate chirp signal
    private void generateChirpTemplate() {
        int chirpSamples = (int) (SAMPLE_RATE * CHIRP_DURATION_MS / 1000.0);
        chirpTemplate = new short[chirpSamples];

        // Calculate chirp rate (Hz/s)
        double chirpRate = (double) (CHIRP_MAX_FREQ - CHIRP_MIN_FREQ) / (CHIRP_DURATION_MS / 1000.0);

        // Generate linear chirp with Hamming window
        for (int i = 0; i < chirpSamples; i++) {
            double time = (double) i / SAMPLE_RATE;
            double instantFreq = CHIRP_MIN_FREQ + chirpRate * time;
            double phase = 2 * Math.PI * (CHIRP_MIN_FREQ * time + 0.5 * chirpRate * time * time);

            // Apply Hamming window
            double window = 0.54 - 0.46 * Math.cos(2 * Math.PI * i / (chirpSamples - 1));

            // Use maximum amplitude for better SNR
            double amplitude = Short.MAX_VALUE * 0.95 * window;
            chirpTemplate[i] = (short) (amplitude * Math.sin(phase));
        }

        // Log chirp statistics
        short minVal = Short.MAX_VALUE;
        short maxVal = Short.MIN_VALUE;

        for (short s : chirpTemplate) {
            minVal = (short) Math.min(minVal, s);
            maxVal = (short) Math.max(maxVal, s);
        }

        Log.d(TAG, String.format("Chirp generated: %d samples, %.2f ms, %d-%d Hz, amplitude range: %d to %d",
                chirpSamples, (float)CHIRP_DURATION_MS, CHIRP_MIN_FREQ, CHIRP_MAX_FREQ, minVal, maxVal));
    }

    // Start echo detection test
    public void startTest(EchoTestCallback callback) {
        if (isRunning.get()) {
            Log.w(TAG, "Test already running");
            return;
        }

        this.callback = callback;
        isRunning.set(true);

        executor.execute(() -> {
            try {
                if (audioRecord == null || audioTrack == null) {
                    throw new IllegalStateException("Audio components not initialized");
                }

                Log.i(TAG, "Starting echo detection test");

                // Start recording
                audioRecord.startRecording();
                audioTrack.play();

                List<short[]> allRecordings = new ArrayList<>();
                List<Long> chirpTimes = new ArrayList<>();

                long startTime = System.currentTimeMillis();
                long testEndTime = startTime + TEST_DURATION_MS;
                long nextChirpTime = startTime;

                while (isRunning.get() && System.currentTimeMillis() < testEndTime) {
                    long currentTime = System.currentTimeMillis();
                    int progress = (int) ((currentTime - startTime) * 100 / TEST_DURATION_MS);
                    callback.onProgress(progress);

                    // Time to emit a chirp?
                    if (currentTime >= nextChirpTime) {
                        Log.d(TAG, "Emitting chirp at " + (currentTime - startTime) + "ms");

                        // Play chirp
                        audioTrack.write(chirpTemplate, 0, chirpTemplate.length);
                        chirpTimes.add(currentTime);

                        // Schedule next chirp
                        nextChirpTime = currentTime + CHIRP_GAP_MS;
                    }

                    // Read audio data
                    short[] buffer = new short[BUFFER_SIZE];
                    int bytesRead = audioRecord.read(buffer, 0, BUFFER_SIZE);

                    if (bytesRead > 0) {
                        short[] recording = new short[bytesRead];
                        System.arraycopy(buffer, 0, recording, 0, bytesRead);
                        allRecordings.add(recording);

                        // Log signal stats for debugging
                        logSignalStats(recording);
                    }

                    // Small sleep to avoid burning CPU
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }

                // Stop recording
                audioRecord.stop();
                audioTrack.stop();

                // Process results
                TestResult result = analyzeRecordings(allRecordings, chirpTimes);
                callback.onTestComplete(result);

                Log.i(TAG, "Echo detection test completed");

            } catch (Exception e) {
                Log.e(TAG, "Error during echo detection test", e);
                callback.onError("Test failed: " + e.getMessage());
            } finally {
                isRunning.set(false);
            }
        });
    }

    // Stop the current test
    public void stopTest() {
        if (isRunning.get()) {
            Log.i(TAG, "Stopping echo detection test");
            isRunning.set(false);
        }
    }

    // Release resources
    public void release() {
        Log.i(TAG, "Releasing resources");
        stopTest();

        if (audioRecord != null) {
            audioRecord.release();
            audioRecord = null;
        }

        if (audioTrack != null) {
            audioTrack.release();
            audioTrack = null;
        }

        if (executor != null) {
            executor.shutdown();
            executor = null;
        }
    }

    // Log signal statistics
    private void logSignalStats(short[] signal) {
        if (signal == null || signal.length == 0) return;

        short min = Short.MAX_VALUE;
        short max = Short.MIN_VALUE;
        double sum = 0;
        double sumSquared = 0;

        for (short s : signal) {
            min = (short) Math.min(min, s);
            max = (short) Math.max(max, s);
            sum += s;
            sumSquared += (double) s * s;
        }

        double mean = sum / signal.length;
        double rms = Math.sqrt(sumSquared / signal.length);

        Log.d(TAG, String.format("Signal stats: min=%d, max=%d, mean=%.2f, rms=%.2f, range=%d",
                min, max, mean, rms, max - min));
    }

    // Analyze recorded data
    private TestResult analyzeRecordings(List<short[]> recordings, List<Long> chirpTimes) {
        TestResult result = new TestResult();

        if (recordings.isEmpty() || chirpTimes.isEmpty()) {
            Log.w(TAG, "No recordings or chirps to analyze");
            return result;
        }

        Log.i(TAG, "Analyzing " + recordings.size() + " recordings with " + chirpTimes.size() + " chirps");

        // Concatenate all recordings
        int totalSamples = 0;
        for (short[] recording : recordings) {
            totalSamples += recording.length;
        }

        short[] allSamples = new short[totalSamples];
        int position = 0;

        for (short[] recording : recordings) {
            System.arraycopy(recording, 0, allSamples, position, recording.length);
            position += recording.length;
        }

        // Calculate overall signal statistics
        short min = Short.MAX_VALUE;
        short max = Short.MIN_VALUE;
        double sum = 0;
        double sumSquared = 0;

        for (short s : allSamples) {
            min = (short) Math.min(min, s);
            max = (short) Math.max(max, s);
            sum += s;
            sumSquared += (double) s * s;
        }

        double mean = sum / allSamples.length;
        double rms = Math.sqrt(sumSquared / allSamples.length);

        result.minValue = min;
        result.maxValue = max;
        result.meanValue = mean;
        result.rmsValue = rms;

        // Analyze each chirp for echoes
        List<Double> echoEnergies = new ArrayList<>();
        List<Double> noiseEnergies = new ArrayList<>();
        List<Double> echoDelays = new ArrayList<>();

        for (Long chirpTime : chirpTimes) {
            // Determine sample indices for this chirp
            long sampleOffset = (chirpTime - chirpTimes.get(0)) * SAMPLE_RATE / 1000;
            int chirpStart = (int) Math.max(0, sampleOffset);

            // Look for the emitted chirp and subsequent echoes
            if (chirpStart + chirpTemplate.length < allSamples.length) {

                // Calculate energy in expected echo region
                // (Look 5-50ms after chirp for echoes based on distance)
                int echoStartOffset = (int) (5 * SAMPLE_RATE / 1000); // 5ms
                int echoEndOffset = (int) (50 * SAMPLE_RATE / 1000); // 50ms

                int echoStart = chirpStart + chirpTemplate.length + echoStartOffset;
                int echoEnd = Math.min(allSamples.length, chirpStart + chirpTemplate.length + echoEndOffset);

                // Calculate background noise level
                // (Use samples before the chirp)
                int noiseStart = Math.max(0, chirpStart - 1000);
                int noiseEnd = chirpStart;

                // Process if we have valid ranges
                if (echoEnd > echoStart && noiseEnd > noiseStart) {
                    double echoEnergy = calculateEnergy(allSamples, echoStart, echoEnd);
                    double noiseEnergy = calculateEnergy(allSamples, noiseStart, noiseEnd);

                    echoEnergies.add(echoEnergy);
                    noiseEnergies.add(noiseEnergy);

                    // Try to detect peak in echo region
                    int peakIndex = findPeakIndex(allSamples, echoStart, echoEnd);
                    if (peakIndex > 0) {
                        double delayMs = (peakIndex - chirpStart - chirpTemplate.length) * 1000.0 / SAMPLE_RATE;
                        echoDelays.add(delayMs);

                        Log.d(TAG, String.format("Chirp #%d: Echo energy=%.2f, noise=%.2f, delay=%.2fms",
                                chirpTimes.indexOf(chirpTime), echoEnergy, noiseEnergy, delayMs));
                    }
                }
            }
        }

        // Calculate final results
        if (!echoEnergies.isEmpty() && !noiseEnergies.isEmpty()) {
            // Calculate average echo energy
            double totalEchoEnergy = 0;
            for (Double energy : echoEnergies) {
                totalEchoEnergy += energy;
            }
            double avgEchoEnergy = totalEchoEnergy / echoEnergies.size();

            // Calculate average noise energy
            double totalNoiseEnergy = 0;
            for (Double energy : noiseEnergies) {
                totalNoiseEnergy += energy;
            }
            double avgNoiseEnergy = totalNoiseEnergy / noiseEnergies.size();

            // Calculate SNR
            double snr = avgNoiseEnergy > 0 ? 10 * Math.log10(avgEchoEnergy / avgNoiseEnergy) : 0;

            // Calculate average delay
            double totalDelay = 0;
            for (Double delay : echoDelays) {
                totalDelay += delay;
            }
            double avgDelay = echoDelays.isEmpty() ? 0 : totalDelay / echoDelays.size();

            // Determine peak amplitude
            double peakAmplitude = max - min;

            // Set result values
            result.signalEnergy = avgEchoEnergy;
            result.snr = snr;
            result.peakAmplitude = peakAmplitude;
            result.echoDelayMs = avgDelay;
            result.echoCount = echoDelays.size();

            // Determine if echoes were detected
            result.echoDetected = avgEchoEnergy > ECHO_THRESHOLD && snr > SIGNAL_NOISE_RATIO_THRESHOLD;

            // Set signal quality description
            if (result.echoDetected) {
                if (snr > 10) {
                    result.signalQuality = "Excellent";
                } else if (snr > 5) {
                    result.signalQuality = "Good";
                } else {
                    result.signalQuality = "Fair";
                }
            } else {
                if (avgEchoEnergy > ECHO_THRESHOLD / 2) {
                    result.signalQuality = "Poor";
                } else {
                    result.signalQuality = "Very Poor";
                }
            }
        }

        Log.i(TAG, String.format("Analysis complete: echoes=%b, energy=%.2f, SNR=%.2f dB, count=%d",
                result.echoDetected, result.signalEnergy, result.snr, result.echoCount));

        return result;
    }

    // Calculate energy of signal in a range
    private double calculateEnergy(short[] signal, int start, int end) {
        double energy = 0;
        for (int i = start; i < end; i++) {
            energy += signal[i] * signal[i];
        }
        return energy / (end - start);
    }

    // Find the index of the peak in a range
    private int findPeakIndex(short[] signal, int start, int end) {
        int peakIndex = -1;
        short peakValue = 0;

        for (int i = start; i < end; i++) {
            if (Math.abs(signal[i]) > Math.abs(peakValue)) {
                peakValue = signal[i];
                peakIndex = i;
            }
        }

        return peakIndex;
    }

    // Echo test callback interface
    public interface EchoTestCallback {
        void onProgress(int percentComplete);
        void onTestComplete(TestResult result);
        void onError(String errorMessage);
    }

    // Class to hold test results
    public static class TestResult {
        public boolean echoDetected = false;
        public String signalQuality = "Unknown";
        public double signalEnergy = 0;
        public double snr = 0;
        public double peakAmplitude = 0;
        public double echoDelayMs = 0;
        public int echoCount = 0;
        public short minValue = 0;
        public short maxValue = 0;
        public double meanValue = 0;
        public double rmsValue = 0;
    }
}