package com.hccps.xiao.itemdector.sondar.echotest;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class EchoTestActivity extends AppCompatActivity {
    private static final String TAG = "EchoTest";
    private static final int PERMISSION_REQUEST_CODE = 101;

    // Required permissions
    private static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.RECORD_AUDIO,
            // No storage permissions needed when using internal storage
    };

    // UI Components
    private Button testButton;
    private Button exportButton;
    private TextView statusText;
    private TextView resultText;
    private ProgressBar progressBar;
    private ScrollView resultScrollView;
    private RadioGroup testModeGroup;
    private RadioButton standardTestRadio;
    private RadioButton dataCollectionRadio;
    private SeekBar durationSeekBar;
    private TextView durationValueText;

    // Test Components
    private EchoTester echoTester;
    private boolean isRunningTest = false;
    private Handler mainHandler;
    private String lastSessionPath = null;
    private int testDurationMs = 10000; // Default 10 seconds

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_echo_test);

        // Initialize UI components
        testButton = findViewById(R.id.test_button);
        exportButton = findViewById(R.id.export_button);
        statusText = findViewById(R.id.status_text);
        resultText = findViewById(R.id.result_text);
        progressBar = findViewById(R.id.progress_bar);
        resultScrollView = findViewById(R.id.result_scroll_view);
        testModeGroup = findViewById(R.id.test_mode_group);
        standardTestRadio = findViewById(R.id.standard_test_radio);
        dataCollectionRadio = findViewById(R.id.data_collection_radio);
        durationSeekBar = findViewById(R.id.duration_seek_bar);
        durationValueText = findViewById(R.id.duration_value_text);

        // Set up duration seek bar
        durationSeekBar.setMax(30); // Max 30 seconds
        durationSeekBar.setProgress(10); // Default 10 seconds
        updateDurationText(10);

        durationSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int duration = Math.max(1, progress); // Minimum 1 second
                updateDurationText(duration);
                testDurationMs = duration * 1000;
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // Initialize handler for UI updates
        mainHandler = new Handler(Looper.getMainLooper());

        // Set up button click listeners
        testButton.setOnClickListener(v -> {
            if (isRunningTest) {
                stopTest();
            } else {
                startTest();
            }
        });

        exportButton.setOnClickListener(v -> {
            exportLastSession();
        });

        // Initially disable export button
        exportButton.setEnabled(false);

        // Initialize tester with application context
        echoTester = new EchoTester(getApplicationContext());

        // Check for required permissions
        if (!hasAllPermissions()) {
            requestPermissions();
            statusText.setText("Waiting for required permissions");
        } else {
            statusText.setText("Ready to test echo detection");
        }
    }

    private void updateDurationText(int seconds) {
        durationValueText.setText(String.format(Locale.US, "%d seconds", seconds));
    }

    private boolean hasAllPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.RECORD_AUDIO},
                PERMISSION_REQUEST_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) {
                statusText.setText("Ready to test echo detection");
                testButton.setEnabled(true);
            } else {
                statusText.setText("All permissions required for testing");
                testButton.setEnabled(false);
                Toast.makeText(this, "Permissions are required for the app to function", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void startTest() {
        if (!hasAllPermissions()) {
            requestPermissions();
            return;
        }

        isRunningTest = true;
        testButton.setText("Stop Test");
        statusText.setText("Running echo detection test...");
        resultText.setText("");
        progressBar.setProgress(0);
        progressBar.setVisibility(View.VISIBLE);
        exportButton.setEnabled(false);

        // Configure test mode
        boolean isDataCollectionMode = dataCollectionRadio.isChecked();
        echoTester.setSaveRawData(true); // Always save raw data
        echoTester.setTestDuration(testDurationMs);

        // Start the echo test
        echoTester.startTest(new EchoTester.EchoTestCallback() {
            @Override
            public void onProgress(int percentComplete) {
                mainHandler.post(() -> {
                    progressBar.setProgress(percentComplete);
                    statusText.setText("Testing: " + percentComplete + "% complete");
                });
            }

            @Override
            public void onTestComplete(EchoTester.TestResult result) {
                mainHandler.post(() -> {
                    progressBar.setVisibility(View.INVISIBLE);
                    statusText.setText("Test completed");

                    // Save session path for export
                    lastSessionPath = echoTester.getSessionDirectoryPath();

                    // Enable export button
                    exportButton.setEnabled(true);

                    // Export data as WAV for easier processing
                    echoTester.exportDataAsWav();

                    StringBuilder resultBuilder = new StringBuilder();

                    // Show file paths if in data collection mode
                    if (isDataCollectionMode) {
                        resultBuilder.append("Data Collection Complete\n\n");
                        resultBuilder.append("Files saved to:\n");
                        resultBuilder.append(lastSessionPath).append("\n\n");
                        resultBuilder.append("Files:\n");
                        resultBuilder.append("- raw_recording.pcm (Binary PCM data)\n");
                        resultBuilder.append("- recording.wav (WAV format audio)\n");
                        resultBuilder.append("- chirp_template.raw (Binary chirp data)\n");
                        resultBuilder.append("- session_metadata.json (Test parameters)\n");
                        resultBuilder.append("- chirp_timing.csv (Chirp timestamps)\n");
                        resultBuilder.append("- analysis_results.json (Basic analysis)\n");
                        resultBuilder.append("- detailed_analysis.csv (Per-chirp analysis)\n\n");
                        resultBuilder.append("Use the EXPORT button to share these files.\n\n");
                    }

                    // Always show basic results too
                    resultBuilder.append("Echo Detection Results:\n\n");
                    resultBuilder.append(String.format(
                            "Echo Detection: %s\n" +
                                    "Signal Quality: %s\n" +
                                    "Signal Energy: %.2f\n" +
                                    "SNR: %.2f dB\n" +
                                    "Peak Amplitude: %.2f\n" +
                                    "Echo Delay: %.2f ms\n" +
                                    "Echo Count: %d\n\n" +
                                    "Raw Stats:\n" +
                                    "Min: %d | Max: %d | Mean: %.2f | RMS: %.2f",
                            result.echoDetected ? "YES" : "NO",
                            result.signalQuality,
                            result.signalEnergy,
                            result.snr,
                            result.peakAmplitude,
                            result.echoDelayMs,
                            result.echoCount,
                            result.minValue,
                            result.maxValue,
                            result.meanValue,
                            result.rmsValue));

                    resultText.setText(resultBuilder.toString());
                    resultScrollView.fullScroll(View.FOCUS_UP); // Scroll to top

                    testButton.setText("Start Test");
                    isRunningTest = false;
                });
            }

            @Override
            public void onError(String errorMessage) {
                mainHandler.post(() -> {
                    progressBar.setVisibility(View.INVISIBLE);
                    statusText.setText("Test failed: " + errorMessage);
                    testButton.setText("Start Test");
                    isRunningTest = false;
                });
            }
        });
    }

    private void stopTest() {
        echoTester.stopTest();
        statusText.setText("Test stopped");
        testButton.setText("Start Test");
        progressBar.setVisibility(View.INVISIBLE);
        isRunningTest = false;
    }

    // In your EchoTestActivity.java, update the export method:

    private void exportLastSession() {
        if (lastSessionPath == null) {
            Toast.makeText(this, "No test data available to export", Toast.LENGTH_SHORT).show();
            return;
        }

        // Get the directory
        File sessionDir = new File(lastSessionPath);
        if (!sessionDir.exists() || !sessionDir.isDirectory()) {
            Toast.makeText(this, "Session directory not found", Toast.LENGTH_SHORT).show();
            return;
        }

        // Use a file chooser to let the user select where to save or share the files
        Intent shareIntent = new Intent(Intent.ACTION_SEND_MULTIPLE);
        shareIntent.setType("*/*");

        ArrayList<Uri> files = new ArrayList<>();
        File[] fileList = sessionDir.listFiles();

        if (fileList != null) {
            for (File file : fileList) {
                try {
                    // Use FileProvider for secure sharing
                    Uri uri = FileProvider.getUriForFile(
                            this,
                            getApplicationContext().getPackageName() + ".provider",
                            file);
                    files.add(uri);
                    Log.d(TAG, "Added file to share: " + file.getName() + " with URI: " + uri);
                } catch (Exception e) {
                    Log.e(TAG, "Error sharing file: " + file.getName(), e);
                }
            }
        }

        if (files.isEmpty()) {
            Toast.makeText(this, "No files found to export", Toast.LENGTH_SHORT).show();
            return;
        }

        shareIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, files);
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        // Create a chooser for sharing
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String shareTitle = "Share Echo Test Data - " + timeStamp;

        try {
            startActivity(Intent.createChooser(shareIntent, shareTitle));
        } catch (Exception e) {
            Log.e(TAG, "Error launching share intent", e);
            Toast.makeText(this, "Error sharing files: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        echoTester.release();
    }
}