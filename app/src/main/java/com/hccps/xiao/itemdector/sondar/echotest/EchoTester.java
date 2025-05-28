package com.hccps.xiao.itemdector.sondar.echotest;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class EchoTester {
    private static final String TAG = "EchoTester";

    // Audio configuration
    private static final int SAMPLE_RATE = 48000; // Hz
    private static final int CHIRP_MIN_FREQ = 15000; // Hz
    private static final int CHIRP_MAX_FREQ = 17000; // Hz
    private static final int CHIRP_DURATION_MS = 15; // ms
    private static final int CHIRP_GAP_MS = 400; // ms between chirps
    private static final int BUFFER_SIZE = SAMPLE_RATE / 10; // 100ms buffer
    private static final int TEST_DURATION_MS = 10000; // 10 seconds test

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

    // Data collection
    private List<ChirpData> chirpDataList = new ArrayList<>();
    private List<short[]> allRecordings = new ArrayList<>();
    private List<Long> chirpTimes = new ArrayList<>();
    private String sessionId;
    private boolean saveRawData = true;
    private File outputDirectory;
    private Context context;

    // Metadata for the session
    private JSONObject sessionMetadata = new JSONObject();

    // Constructor
    public EchoTester(Context context) {
        this.context = context;
        executor = Executors.newSingleThreadExecutor();
        initAudio();

        // Create session ID first, then setup directories
        createSessionId();
        setupOutputDirectory();

        // Generate chirp template after directory is set up
        generateChirpTemplate();

        // Initialize metadata
        initSessionMetadata();
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

    private void createSessionId() {
        // Create a unique session ID using timestamp
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
        sessionId = "echo_test_" + sdf.format(new Date());
    }

    // Modified setupOutputDirectory method prioritizing internal storage

    private void setupOutputDirectory() {
        try {
            // Use internal app storage as primary option (like SondarDataCollector)
            File baseDir = new File(context.getFilesDir(), "EchoTest");
            if (!baseDir.exists()) {
                boolean success = baseDir.mkdirs();
                if (!success) {
                    Log.e(TAG, "Failed to create base directory: " + baseDir.getAbsolutePath());
                }
            }

            // Create session directory
            outputDirectory = new File(baseDir, sessionId);
            Log.d(TAG, "Attempting to create directory: " + outputDirectory.getAbsolutePath());

            if (!outputDirectory.exists()) {
                boolean success = outputDirectory.mkdirs();
                if (!success) {
                    Log.e(TAG, "Failed to create output directory: " + outputDirectory.getAbsolutePath());
                }
            }

            Log.d(TAG, "Output directory: " + outputDirectory.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Error setting up output directory", e);
            // Create a temporary fallback in cache directory
            outputDirectory = new File(context.getCacheDir(), "EchoTest/" + sessionId);
            try {
                if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
                    Log.e(TAG, "Failed to create fallback directory");
                }
            } catch (Exception e2) {
                Log.e(TAG, "Failed to create even the fallback directory", e2);
            }
        }
    }

    private void initSessionMetadata() {
        try {
            sessionMetadata.put("sessionId", sessionId);
            sessionMetadata.put("testStartTime", System.currentTimeMillis());
            sessionMetadata.put("sampleRate", SAMPLE_RATE);
            sessionMetadata.put("chirpMinFreq", CHIRP_MIN_FREQ);
            sessionMetadata.put("chirpMaxFreq", CHIRP_MAX_FREQ);
            sessionMetadata.put("chirpDurationMs", CHIRP_DURATION_MS);
            sessionMetadata.put("chirpGapMs", CHIRP_GAP_MS);
            sessionMetadata.put("bufferSize", BUFFER_SIZE);
            sessionMetadata.put("testDurationMs", TEST_DURATION_MS);
            sessionMetadata.put("deviceModel", android.os.Build.MODEL);
            sessionMetadata.put("androidVersion", android.os.Build.VERSION.RELEASE);
        } catch (JSONException e) {
            Log.e(TAG, "Error creating session metadata", e);
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

        // Save chirp template
        saveChirpTemplate();
    }

    private void saveChirpTemplate() {
        try {
            // Make sure directory exists
            if (!outputDirectory.exists()) {
                if (!outputDirectory.mkdirs()) {
                    Log.e(TAG, "Failed to create output directory: " + outputDirectory.getAbsolutePath());
                    return;
                }
            }

            // Create file in the directory
            File chirpFile = new File(outputDirectory, "chirp_template.raw");
            Log.d(TAG, "Saving chirp template to: " + chirpFile.getAbsolutePath());

            // Simplified and safer file writing
            try (FileOutputStream fos = new FileOutputStream(chirpFile);
                 DataOutputStream dos = new DataOutputStream(fos)) {

                // Write each sample
                for (short sample : chirpTemplate) {
                    dos.writeShort(sample);
                }

                Log.d(TAG, "Chirp template saved successfully");
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to save chirp template: " + e.getMessage(), e);
            // Continue execution even if file save fails
        }
    }

    // Configuration methods
    public void setSaveRawData(boolean saveRawData) {
        this.saveRawData = saveRawData;
    }

    public void setTestDuration(int durationMs) {
        try {
            sessionMetadata.put("testDurationMs", durationMs);
        } catch (JSONException e) {
            Log.e(TAG, "Error updating test duration in metadata", e);
        }
    }

    // Start echo detection test
    public void startTest(EchoTestCallback callback) {
        if (isRunning.get()) {
            Log.w(TAG, "Test already running");
            return;
        }

        this.callback = callback;
        isRunning.set(true);

        // Create new lists for this test
        chirpDataList.clear();
        allRecordings.clear();
        chirpTimes.clear();

        executor.execute(() -> {
            try {
                if (audioRecord == null || audioTrack == null) {
                    throw new IllegalStateException("Audio components not initialized");
                }

                Log.i(TAG, "Starting echo detection test");

                // Start recording
                audioRecord.startRecording();
                audioTrack.play();

                long startTime = System.currentTimeMillis();
                long testEndTime = startTime + TEST_DURATION_MS;
                long nextChirpTime = startTime;

                try {
                    sessionMetadata.put("actualStartTime", startTime);
                } catch (JSONException e) {
                    Log.e(TAG, "Error updating metadata", e);
                }

                // Create raw audio file to save all recorded samples
                File rawAudioFile = null;
                DataOutputStream rawAudioStream = null;

                if (saveRawData) {
                    try {
                        rawAudioFile = new File(outputDirectory, "raw_recording.pcm");
                        rawAudioStream = new DataOutputStream(new BufferedOutputStream(
                                new FileOutputStream(rawAudioFile)));
                    } catch (IOException e) {
                        Log.e(TAG, "Failed to create raw audio file", e);
                        rawAudioStream = null;
                    }
                }

                while (isRunning.get() && System.currentTimeMillis() < testEndTime) {
                    long currentTime = System.currentTimeMillis();
                    int progress = (int) ((currentTime - startTime) * 100 / TEST_DURATION_MS);
                    callback.onProgress(progress);

                    // Time to emit a chirp?
                    if (currentTime >= nextChirpTime) {
                        long emitTime = System.currentTimeMillis();
                        Log.d(TAG, "Emitting chirp at " + (emitTime - startTime) + "ms");

                        // Play chirp
                        audioTrack.write(chirpTemplate, 0, chirpTemplate.length);
                        chirpTimes.add(emitTime);

                        // Create chirp data object
                        ChirpData chirpData = new ChirpData();
                        chirpData.timestamp = emitTime;
                        chirpData.emitTimeFromStart = emitTime - startTime;
                        chirpDataList.add(chirpData);

                        // Schedule next chirp
                        nextChirpTime = currentTime + CHIRP_GAP_MS;
                    }

                    // Read audio data
                    short[] buffer = new short[BUFFER_SIZE];
                    int samplesRead = audioRecord.read(buffer, 0, BUFFER_SIZE);

                    if (samplesRead > 0) {
                        short[] recording = new short[samplesRead];
                        System.arraycopy(buffer, 0, recording, 0, samplesRead);
                        allRecordings.add(recording);

                        // Save raw audio data
                        if (saveRawData && rawAudioStream != null) {
                            try {
                                for (int i = 0; i < samplesRead; i++) {
                                    rawAudioStream.writeShort(recording[i]);
                                }
                            } catch (IOException e) {
                                Log.e(TAG, "Error writing to raw audio file", e);
                                // Close and nullify to prevent further attempts
                                try {
                                    rawAudioStream.close();
                                } catch (IOException e2) {
                                    // Ignore
                                }
                                rawAudioStream = null;
                            }
                        }

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

                // Close raw audio file
                if (rawAudioStream != null) {
                    try {
                        rawAudioStream.close();
                        Log.d(TAG, "Raw audio saved to " + rawAudioFile.getAbsolutePath());
                    } catch (IOException e) {
                        Log.e(TAG, "Error closing raw audio file", e);
                    }
                }

                // Stop recording
                long endTime = System.currentTimeMillis();
                audioRecord.stop();
                audioTrack.stop();

                try {
                    sessionMetadata.put("actualEndTime", endTime);
                    sessionMetadata.put("actualDuration", endTime - startTime);
                    sessionMetadata.put("chirpCount", chirpTimes.size());
                } catch (JSONException e) {
                    Log.e(TAG, "Error updating metadata", e);
                }

                // Save metadata and chirp timing data
                saveSessionMetadata();
                saveChirpTimingData();

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

    private void saveSessionMetadata() {
        try {
            File metadataFile = new File(outputDirectory, "session_metadata.json");
            FileOutputStream fos = new FileOutputStream(metadataFile);
            fos.write(sessionMetadata.toString(4).getBytes());
            fos.close();
            Log.d(TAG, "Session metadata saved to " + metadataFile.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Failed to save session metadata", e);
            // Continue execution even if file save fails
        }
    }

    private void saveChirpTimingData() {
        try {
            File chirpTimingFile = new File(outputDirectory, "chirp_timing.csv");
            FileOutputStream fos = new FileOutputStream(chirpTimingFile);

            // Write header
            fos.write("chirp_index,timestamp,time_from_start_ms\n".getBytes());

            // Write chirp timing data
            for (int i = 0; i < chirpDataList.size(); i++) {
                ChirpData chirpData = chirpDataList.get(i);
                String line = i + "," + chirpData.timestamp + "," + chirpData.emitTimeFromStart + "\n";
                fos.write(line.getBytes());
            }

            fos.close();
            Log.d(TAG, "Chirp timing data saved to " + chirpTimingFile.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "Failed to save chirp timing data", e);
            // Continue execution even if file save fails
        }
    }

    public void saveRawDataAsCsv() {
        try {
            File rawDataCsv = new File(outputDirectory, "raw_data.csv");
            FileOutputStream fos = new FileOutputStream(rawDataCsv);

            // Write header
            fos.write("sample_index,value\n".getBytes());

            // Write all samples
            int sampleIndex = 0;
            for (short[] recording : allRecordings) {
                for (short sample : recording) {
                    String line = sampleIndex + "," + sample + "\n";
                    fos.write(line.getBytes());
                    sampleIndex++;
                }
            }

            fos.close();
            Log.d(TAG, "Raw data saved as CSV to " + rawDataCsv.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "Failed to save raw data as CSV", e);
            // Continue execution even if file save fails
        }
    }

    public void exportDataAsWav() {
        try {
            // Calculate total number of samples
            int totalSamples = 0;
            for (short[] recording : allRecordings) {
                totalSamples += recording.length;
            }

            // Create buffer for all samples
            short[] allSamples = new short[totalSamples];
            int position = 0;
            for (short[] recording : allRecordings) {
                System.arraycopy(recording, 0, allSamples, position, recording.length);
                position += recording.length;
            }

            // Write WAV file
            File wavFile = new File(outputDirectory, "recording.wav");
            writeWavFile(wavFile, allSamples, SAMPLE_RATE);
            Log.d(TAG, "WAV file saved to " + wavFile.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "Failed to export data as WAV", e);
            // Continue execution even if file save fails
        }
    }

    private void writeWavFile(File outputFile, short[] samples, int sampleRate) throws IOException {
        // Create output streams
        FileOutputStream fos = new FileOutputStream(outputFile);
        BufferedOutputStream bos = new BufferedOutputStream(fos);
        DataOutputStream dos = new DataOutputStream(bos);

        try {
            // Calculate sizes
            int dataSize = samples.length * 2;  // 16-bit audio = 2 bytes per sample
            int fileSize = 36 + dataSize;

            // RIFF header
            writeString(dos, "RIFF");            // ChunkID
            dos.writeInt(fileSize);              // ChunkSize
            writeString(dos, "WAVE");            // Format

            // fmt subchunk
            writeString(dos, "fmt ");            // Subchunk1ID
            dos.writeInt(16);                    // Subchunk1Size (16 for PCM)
            dos.writeShort(1);                   // AudioFormat (1 = PCM)
            dos.writeShort(1);                   // NumChannels (1 = mono)
            dos.writeInt(sampleRate);            // SampleRate
            dos.writeInt(sampleRate * 2);        // ByteRate = SampleRate * NumChannels * BitsPerSample/8
            dos.writeShort(2);                   // BlockAlign = NumChannels * BitsPerSample/8
            dos.writeShort(16);                  // BitsPerSample

            // data subchunk
            writeString(dos, "data");            // Subchunk2ID
            dos.writeInt(dataSize);              // Subchunk2Size

            // Write the audio samples
            // DataOutputStream.writeShort() writes in big-endian,
            // but WAV needs little-endian, so we need to swap bytes
            for (short sample : samples) {
                // Write little-endian short
                dos.writeByte(sample & 0xFF);         // Low byte
                dos.writeByte((sample >> 8) & 0xFF);  // High byte
            }
        } finally {
            dos.close();
        }
    }

    // Helper method to write a string to the output stream
    private void writeString(DataOutputStream out, String s) throws IOException {
        for (int i = 0; i < s.length(); i++) {
            out.writeByte(s.charAt(i));
        }
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

        // Save analysis results
        saveAnalysisResults(result, echoEnergies, noiseEnergies, echoDelays);

        Log.i(TAG, String.format("Analysis complete: echoes=%b, energy=%.2f, SNR=%.2f dB, count=%d",
                result.echoDetected, result.signalEnergy, result.snr, result.echoCount));

        return result;
    }

    private void saveAnalysisResults(TestResult result, List<Double> echoEnergies,
                                     List<Double> noiseEnergies, List<Double> echoDelays) {
        try {
            // Save summary results
            JSONObject analysisResults = new JSONObject();
            analysisResults.put("echoDetected", result.echoDetected);
            analysisResults.put("signalQuality", result.signalQuality);
            analysisResults.put("signalEnergy", result.signalEnergy);
            analysisResults.put("snr", result.snr);
            analysisResults.put("peakAmplitude", result.peakAmplitude);
            analysisResults.put("echoDelayMs", result.echoDelayMs);
            analysisResults.put("echoCount", result.echoCount);
            analysisResults.put("minValue", result.minValue);
            analysisResults.put("maxValue", result.maxValue);
            analysisResults.put("meanValue", result.meanValue);
            analysisResults.put("rmsValue", result.rmsValue);

            File analysisFile = new File(outputDirectory, "analysis_results.json");
            FileOutputStream fos = new FileOutputStream(analysisFile);
            fos.write(analysisResults.toString(4).getBytes());
            fos.close();

            // Save detailed chirp analysis
            File detailedFile = new File(outputDirectory, "detailed_analysis.csv");
            FileOutputStream detailedFos = new FileOutputStream(detailedFile);

            // Write header
            detailedFos.write("chirp_index,echo_energy,noise_energy,echo_delay_ms\n".getBytes());

            // Write detailed data
            int numChirps = Math.min(chirpTimes.size(),
                    Math.min(echoEnergies.size(),
                            Math.min(noiseEnergies.size(), echoDelays.size())));

            for (int i = 0; i < numChirps; i++) {
                String line = i + "," + echoEnergies.get(i) + "," +
                        noiseEnergies.get(i) + "," + echoDelays.get(i) + "\n";
                detailedFos.write(line.getBytes());
            }

            detailedFos.close();

            Log.d(TAG, "Analysis results saved to " + outputDirectory.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Failed to save analysis results", e);
            // Continue execution even if file save fails
        }
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

    // Helper class to store chirp data
    private static class ChirpData {
        long timestamp;        // System time when chirp was emitted
        long emitTimeFromStart; // Time from test start when chirp was emitted
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

    // Get the session directory path
    public String getSessionDirectoryPath() {
        return outputDirectory.getAbsolutePath();
    }
}