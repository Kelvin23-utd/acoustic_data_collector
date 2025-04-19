package com.hccps.xiao.itemdector.sondar.echotest;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.ScrollView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;


public class EchoTestActivity extends AppCompatActivity {
    private static final String TAG = "EchoTest";
    private static final int PERMISSION_REQUEST_CODE = 101;
    private static final int AUTO_TEST_COUNT = 10;

    // UI Components
    private Button testButton;
    private Button autoTestButton;
    private TextView statusText;
    private TextView resultText;
    private ProgressBar progressBar;
    private ScrollView scrollView;

    // Test Components
    private EchoTester echoTester;
    private boolean isRunningTest = false;
    private boolean isRunningAutoTest = false;
    private Handler mainHandler;

    // Auto-test variables
    private int currentAutoTestNumber = 0;
    private List<EchoTester.TestResult> autoTestResults = new ArrayList<>();
    private StringBuilder resultsBuilder = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_echo_test);

        // Initialize UI components
        testButton = findViewById(R.id.test_button);
        autoTestButton = findViewById(R.id.auto_test_button);
        statusText = findViewById(R.id.status_text);
        resultText = findViewById(R.id.result_text);
        progressBar = findViewById(R.id.progress_bar);

        // Initialize handler for UI updates
        mainHandler = new Handler(Looper.getMainLooper());

        // Set up single test button click listener
        testButton.setOnClickListener(v -> {
            if (isRunningTest) {
                stopTest();
            } else {
                startTest();
            }
        });

        // Set up auto test button click listener
        autoTestButton.setOnClickListener(v -> {
            if (isRunningAutoTest) {
                stopAutoTest();
            } else {
                startAutoTest();
            }
        });

        // Create the echo tester
        echoTester = new EchoTester();

        // Check for required permissions
        if (!hasPermissions()) {
            requestPermissions();
            statusText.setText("Waiting for microphone permission");
        } else {
            statusText.setText("Ready to test echo detection");
        }
    }

    private boolean hasPermissions() {
        return ContextCompat.checkSelfPermission(this,
                Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
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
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                statusText.setText("Ready to test echo detection");
                testButton.setEnabled(true);
                autoTestButton.setEnabled(true);
            } else {
                statusText.setText("Microphone permission required");
                testButton.setEnabled(false);
                autoTestButton.setEnabled(false);
            }
        }
    }

    private void startTest() {
        if (!hasPermissions()) {
            requestPermissions();
            return;
        }

        isRunningTest = true;
        testButton.setText("Stop Test");
        autoTestButton.setEnabled(false);
        statusText.setText("Running echo detection test...");
        resultText.setText("");
        progressBar.setVisibility(View.VISIBLE);

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

                    // Display the results
                    String resultStr = String.format(
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
                            result.rmsValue);

                    resultText.setText(resultStr);
                    testButton.setText("Start Test");
                    autoTestButton.setEnabled(true);
                    isRunningTest = false;
                });
            }

            @Override
            public void onError(String errorMessage) {
                mainHandler.post(() -> {
                    progressBar.setVisibility(View.INVISIBLE);
                    statusText.setText("Test failed: " + errorMessage);
                    testButton.setText("Start Test");
                    autoTestButton.setEnabled(true);
                    isRunningTest = false;
                });
            }
        });
    }

    private void startAutoTest() {
        if (!hasPermissions()) {
            requestPermissions();
            return;
        }

        isRunningAutoTest = true;
        currentAutoTestNumber = 0;
        autoTestResults.clear();
        resultsBuilder = new StringBuilder();

        // Add timestamp header to results
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        String timestamp = sdf.format(new Date());
        resultsBuilder.append("AUTO TEST SESSION - ").append(timestamp).append("\n\n");

        resultText.setText(resultsBuilder.toString());
        autoTestButton.setText("Stop Auto Test");
        testButton.setEnabled(false);

        runNextAutoTest();
    }

    private void runNextAutoTest() {
        if (!isRunningAutoTest || currentAutoTestNumber >= AUTO_TEST_COUNT) {
            finishAutoTest();
            return;
        }

        currentAutoTestNumber++;
        statusText.setText("Auto Test: Running test " + currentAutoTestNumber + " of " + AUTO_TEST_COUNT);
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);

        // Start individual test
        echoTester.startTest(new EchoTester.EchoTestCallback() {
            @Override
            public void onProgress(int percentComplete) {
                mainHandler.post(() -> {
                    progressBar.setProgress(percentComplete);
                    statusText.setText("Auto Test " + currentAutoTestNumber + "/" +
                            AUTO_TEST_COUNT + ": " + percentComplete + "% complete");
                });
            }

            @Override
            public void onTestComplete(EchoTester.TestResult result) {
                mainHandler.post(() -> {
                    // Add this result to our collection
                    autoTestResults.add(result);

                    // Append individual result to the results text
                    resultsBuilder.append("TEST #").append(currentAutoTestNumber).append(":\n");
                    resultsBuilder.append("Echo: ").append(result.echoDetected ? "YES" : "NO")
                            .append(" | Quality: ").append(result.signalQuality)
                            .append(" | SNR: ").append(String.format("%.2f dB", result.snr))
                            .append(" | Energy: ").append(String.format("%.2f", result.signalEnergy))
                            .append("\n\n");

                    resultText.setText(resultsBuilder.toString());

                    // Wait a moment before starting the next test
                    mainHandler.postDelayed(() -> runNextAutoTest(), 3000);
                });
            }

            @Override
            public void onError(String errorMessage) {
                mainHandler.post(() -> {
                    resultsBuilder.append("TEST #").append(currentAutoTestNumber)
                            .append(" ERROR: ").append(errorMessage).append("\n\n");
                    resultText.setText(resultsBuilder.toString());

                    // Continue with next test despite error
                    mainHandler.postDelayed(() -> runNextAutoTest(), 500);
                });
            }
        });
    }

    private void finishAutoTest() {
        isRunningAutoTest = false;
        progressBar.setVisibility(View.INVISIBLE);
        autoTestButton.setText("Auto Test (10x)");
        testButton.setEnabled(true);

        if (autoTestResults.isEmpty()) {
            statusText.setText("Auto test completed with no results");
            return;
        }

        // Calculate stats from all tests
        int echoCount = 0;
        double totalSnr = 0;
        double totalEnergy = 0;

        for (EchoTester.TestResult result : autoTestResults) {
            if (result.echoDetected) {
                echoCount++;
            }
            totalSnr += result.snr;
            totalEnergy += result.signalEnergy;
        }

        double echoRate = (double) echoCount / autoTestResults.size() * 100;
        double avgSnr = totalSnr / autoTestResults.size();
        double avgEnergy = totalEnergy / autoTestResults.size();

        // Add summary to results
        resultsBuilder.append("===== AUTO TEST SUMMARY =====\n");
        resultsBuilder.append(String.format("Tests Run: %d\n", autoTestResults.size()));
        resultsBuilder.append(String.format("Echo Detection Rate: %.1f%% (%d/%d)\n",
                echoRate, echoCount, autoTestResults.size()));
        resultsBuilder.append(String.format("Average SNR: %.2f dB\n", avgSnr));
        resultsBuilder.append(String.format("Average Signal Energy: %.2f\n", avgEnergy));

        resultText.setText(resultsBuilder.toString());
        statusText.setText("Auto test completed: " + echoCount + "/" + autoTestResults.size() + " echoes detected");
    }

    private void stopTest() {
        echoTester.stopTest();
        statusText.setText("Test stopped");
        testButton.setText("Start Test");
        autoTestButton.setEnabled(true);
        progressBar.setVisibility(View.INVISIBLE);
        isRunningTest = false;
    }

    private void stopAutoTest() {
        isRunningAutoTest = false;
        echoTester.stopTest();
        finishAutoTest();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        echoTester.release();
    }
}