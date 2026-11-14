package com.example.ghostprotocol;

import android.Manifest;
import android.app.ActivityManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "StealthSOS";
    public static final String KEY_FROM_UNLOCK = "key_from_unlock";

    // ------------------------------------------------------------ Storage
    private static final String PREFS_NAME = "stealth_sos_prefs";
    private static final String PREFIX_UNLOCK = "unlock_pin_";
    private static final String PREFIX_DURESS = "duress_pin_";
    private static final String SUFFIX_HASH = "_hash";
    private static final String SUFFIX_SALT = "_salt";
    private static final String KEY_UNLOCK_CUSTOM = "unlock_pin_is_custom";

    private static final String KEY_DURESS_CUSTOM = "duress_pin_is_custom";

    private static final String DEFAULT_UNLOCK_PIN = "1234";
    private static final String DEFAULT_DURESS_PIN = "9999";

    private static final int MIN_PIN_LENGTH = 4;
    private static final int MAX_PIN_LENGTH = 8;
    private static final int PBKDF2_ITERATIONS = 20000;
    private static final int PBKDF2_KEY_BITS = 256;

    // ------------------------------------------------------------ Firebase
    private static final String ALERTS_NODE = "EmergencyAlerts";
    private static final String STATUS_SOS = "SOS_TRIGGERED";
    private static boolean persistenceConfigured = false;

    // ------------------------------------------------------------ Fake shutdown
    private static final String STATE_FAKE_SHUTDOWN = "state_fake_shutdown";
    private static final int CORNER_SIZE_PX = 300;
    private static final int REQUIRED_CORNER_TAPS = 4;
    private static final long MAX_TAP_GAP_MS = 800L;

    // ------------------------------------------------------------ Lock guard
    private static final long BRING_TO_FRONT_COOLDOWN_MS = 500L;

    // ------------------------------------------------------------ State
    private SharedPreferences prefs;
    private final StringBuilder pinBuffer = new StringBuilder();
    private boolean unlocked = false;
    private boolean fakeShutdownActive = false;
    private int cornerTapCount = 0;
    private long lastCornerTapTime = 0L;
    private boolean awaitingPermissionResult = false;
    private long lastBringToFrontTime = 0L;
    private View overlayGuardView;

    private FusedLocationProviderClient fusedLocationClient;

    // ------------------------------------------------------------ Views
    private FrameLayout rootFrame;
    private LinearLayout lockLayout;
    private LinearLayout mainContent;
    private LinearLayout shutdownLogoLayout;
    private TextView pinDotsView;
    private TextView duressStatusView;
    private TextView unlockStatusView;
    private TextView locationStatusView;
    private TextView overlayStatusView;
    private Button grantLocationButton;
    private Button grantOverlayButton;

    private final ActivityResultLauncher<String[]> locationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> {
                        awaitingPermissionResult = false;
                        updateStatus();
                        ensurePinnedWhileLocked();
                    });

    private final ActivityResultLauncher<Intent> overlayPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        awaitingPermissionResult = false;
                        if (hasOverlayPermission()) {
                            Log.d(TAG, "Overlay permission granted");
                        }
                        updateStatus();
                        ensurePinnedWhileLocked();
                    });

    private final OnBackPressedCallback backCallback = new OnBackPressedCallback(true) {
        @Override
        public void handleOnBackPressed() {
            if (!unlocked || fakeShutdownActive) {
                return;
            }
            finish();
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        ensureDefaultPins();
        configureFirebasePersistence();

        buildUI();
        getOnBackPressedDispatcher().addCallback(this, backCallback);
        lockApp();

        if (savedInstanceState != null && savedInstanceState.getBoolean(STATE_FAKE_SHUTDOWN, false)) {
            activateFakeShutdown();
        }

        if (!hasLocationPermission()) {
            requestLocationPermission();
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_FAKE_SHUTDOWN, fakeShutdownActive);
    }

    @Override
    protected void onResume() {
        super.onResume();
        removeOverlayGuard();
        ensurePinnedWhileLocked();
        if (!unlocked && !awaitingPermissionResult) {
            setSystemBarsHidden(true);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!unlocked && !awaitingPermissionResult && !isFinishing()) {
            addOverlayGuard();
            new Handler(Looper.getMainLooper()).postDelayed(this::bringToFront, 200);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (unlocked && !fakeShutdownActive && !isChangingConfigurations()) {
            lockApp();
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (!unlocked && !awaitingPermissionResult) {
            bringToFront();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus && !unlocked && !awaitingPermissionResult) {
            new Handler(Looper.getMainLooper()).postDelayed(this::bringToFront, 200);
        }
        if (hasFocus && !unlocked && !awaitingPermissionResult) {
            setSystemBarsHidden(true);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!unlocked) {
            ensurePinnedWhileLocked();
        }
    }

    @Override
    protected void onDestroy() {
        removeOverlayGuard();
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (!unlocked || fakeShutdownActive) {
            return;
        }
        super.onBackPressed();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (fakeShutdownActive) {
            handleFakeShutdownTouch(ev);
            return true;
        }
        return super.dispatchTouchEvent(ev);
    }

    // ===================================================================== UI BUILD
    private void buildUI() {
        rootFrame = new FrameLayout(this);
        rootFrame.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ViewCompat.setOnApplyWindowInsetsListener(rootFrame, (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        mainContent = buildMainContent();
        lockLayout = buildLockLayout();
        shutdownLogoLayout = buildShutdownLogoLayout();

        rootFrame.addView(mainContent, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        rootFrame.addView(lockLayout, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        rootFrame.addView(shutdownLogoLayout, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(rootFrame);
    }

    private LinearLayout buildShutdownLogoLayout() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setBackgroundColor(Color.BLACK);
        layout.setVisibility(View.GONE);
        return layout;
    }

    private LinearLayout buildLockLayout() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setBackgroundColor(Color.parseColor("#0F1115"));
        layout.setPadding(dp(24), dp(24), dp(24), dp(24));
        layout.setClickable(true);

        TextView title = new TextView(this);
        title.setText("LOCK");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        layout.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView subtitle = new TextView(this);
        subtitle.setText("Enter your PIN to continue");
        subtitle.setTextColor(Color.parseColor("#9AA0A6"));
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subParams.topMargin = dp(8);
        layout.addView(subtitle, subParams);

        pinDotsView = new TextView(this);
        pinDotsView.setTextColor(Color.WHITE);
        pinDotsView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        pinDotsView.setGravity(Gravity.CENTER);
        pinDotsView.setLetterSpacing(0.3f);
        pinDotsView.setMinHeight(dp(56));
        LinearLayout.LayoutParams dotsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dotsParams.topMargin = dp(24);
        dotsParams.bottomMargin = dp(16);
        layout.addView(pinDotsView, dotsParams);

        layout.addView(buildKeypad(), new LinearLayout.LayoutParams(
                dp(300), ViewGroup.LayoutParams.WRAP_CONTENT));

        updatePinDots();
        return layout;
    }

    private View buildKeypad() {
        LinearLayout pad = new LinearLayout(this);
        pad.setOrientation(LinearLayout.VERTICAL);

        String[][] rows = {
                {"1", "2", "3"},
                {"4", "5", "6"},
                {"7", "8", "9"},
                {"DEL", "0", "OK"}
        };

        for (String[] row : rows) {
            LinearLayout rowLayout = new LinearLayout(this);
            rowLayout.setOrientation(LinearLayout.HORIZONTAL);
            for (String key : row) {
                Button button = makeKeyButton(key);
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(64), 1f);
                p.setMargins(dp(6), dp(6), dp(6), dp(6));
                rowLayout.addView(button, p);
            }
            pad.addView(rowLayout, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return pad;
    }

    private Button makeKeyButton(final String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, label.length() > 1 ? 16 : 24);
        b.setBackground(roundedBg(label.equals("OK") ? "#1E8E5A" : "#232830", 16));
        b.setOnClickListener(v -> onKeyPressed(label));
        if (label.equals("DEL")) {
            b.setOnLongClickListener(v -> {
                clearPinInput();
                return true;
            });
        }
        return b;
    }

    private LinearLayout buildMainContent() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        layout.setBackgroundColor(Color.parseColor("#0F1115"));
        layout.setPadding(dp(24), dp(48), dp(24), dp(24));

        TextView title = new TextView(this);
        title.setText("Stealth SOS Settings");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        layout.addView(title);

        layout.addView(makeActionButton("EXIT TO HOME SCREEN", "#1E8E5A", v -> {
            Intent startMain = new Intent(Intent.ACTION_MAIN);
            startMain.addCategory(Intent.CATEGORY_HOME);
            startMain.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(startMain);
        }), actionParams(24));

        TextView subtitle = new TextView(this);
        subtitle.setText("(Use the options below to manage your PINs.)");
        subtitle.setTextColor(Color.parseColor("#9AA0A6"));
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subParams.topMargin = dp(24);
        subParams.bottomMargin = dp(16);
        layout.addView(subtitle, subParams);

        unlockStatusView = makeStatusText();
        duressStatusView = makeStatusText();
        locationStatusView = makeStatusText();
        overlayStatusView = makeStatusText();
        layout.addView(unlockStatusView);
        layout.addView(duressStatusView);
        layout.addView(locationStatusView);
        layout.addView(overlayStatusView);

        layout.addView(makeActionButton("Change Duress PIN", "#C62828",
                v -> showChangePinDialog(true)), actionParams(24));
        layout.addView(makeActionButton("Change Unlock PIN", "#2F6FDE",
                v -> showChangePinDialog(false)), actionParams(12));

        grantLocationButton = makeActionButton("Grant location permission", "#5F6368",
                v -> requestLocationPermission());
        layout.addView(grantLocationButton, actionParams(12));

        grantOverlayButton = makeActionButton("Grant overlay permission", "#5F6368",
                v -> requestOverlayPermission());
        layout.addView(grantOverlayButton, actionParams(12));

        layout.addView(makeActionButton("Lock now", "#232830", v -> {
            lockApp();
            ensurePinnedWhileLocked();
        }), actionParams(12));

        return layout;
    }

    private TextView makeStatusText() {
        TextView tv = new TextView(this);
        tv.setTextColor(Color.parseColor("#C8CCDD"));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setPadding(0, dp(4), 0, dp(4));
        return tv;
    }

    private Button makeActionButton(String text, String colorHex, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setBackground(roundedBg(colorHex, 14));
        b.setOnClickListener(listener);
        return b;
    }

    private LinearLayout.LayoutParams actionParams(int topMarginDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        p.topMargin = dp(topMarginDp);
        return p;
    }

    private GradientDrawable roundedBg(String colorHex, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.parseColor(colorHex));
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    // ======================================================================== STATUS
    private void updateStatus() {
        if (duressStatusView == null) {
            return;
        }
        boolean unlockCustom = prefs.getBoolean(KEY_UNLOCK_CUSTOM, false);
        boolean duressCustom = prefs.getBoolean(KEY_DURESS_CUSTOM, false);
        boolean location = hasLocationPermission();
        boolean overlay = hasOverlayPermission();

        unlockStatusView.setText(unlockCustom
                ? "Unlock PIN: custom PIN set"
                : "Unlock PIN: default PIN in use");
        duressStatusView.setText(duressCustom
                ? "Duress PIN: custom PIN set"
                : "Duress PIN: default PIN in use");
        locationStatusView.setText(location
                ? "Location access: granted"
                : "Location access: not granted");
        overlayStatusView.setText(overlay
                ? "Overlay permission: granted"
                : "Overlay permission: not granted (lock may be bypassable)");
        grantLocationButton.setVisibility(location ? View.GONE : View.VISIBLE);
        grantOverlayButton.setVisibility(overlay ? View.GONE : View.VISIBLE);
    }

    // ======================================================================== PIN INPUT
    private void onKeyPressed(String key) {
        switch (key) {
            case "DEL":
                if (pinBuffer.length() > 0) {
                    pinBuffer.deleteCharAt(pinBuffer.length() - 1);
                    updatePinDots();
                }
                break;
            case "OK":
                submitPin();
                break;
            default:
                if (pinBuffer.length() < MAX_PIN_LENGTH) {
                    pinBuffer.append(key);
                    updatePinDots();
                }
                break;
        }
    }

    private void submitPin() {
        final String entered = pinBuffer.toString();
        clearPinInput();

        if (entered.length() < MIN_PIN_LENGTH) {
            showIncorrectPassword();
            return;
        }

        boolean isUnlockPin = verifyPin(PREFIX_UNLOCK, entered);
        boolean isDuressPin = verifyPin(PREFIX_DURESS, entered);

        if (isUnlockPin) {
            unlockApp();
        } else if (isDuressPin) {
            handleDuressPin();
        } else {
            showIncorrectPassword();
        }
    }

    private void showIncorrectPassword() {
        Toast.makeText(this, "Incorrect Password", Toast.LENGTH_SHORT).show();
    }

    private void clearPinInput() {
        pinBuffer.setLength(0);
        updatePinDots();
    }

    private void updatePinDots() {
        if (pinDotsView == null) {
            return;
        }
        StringBuilder dots = new StringBuilder();
        for (int i = 0; i < pinBuffer.length(); i++) {
            dots.append('\u25CF');
        }
        pinDotsView.setText(dots.length() == 0 ? "" : dots.toString());
    }

    // ======================================================================== LOCK/UNLOCK
    private void lockApp() {
        unlocked = false;
        clearPinInput();
        mainContent.setVisibility(View.GONE);
        lockLayout.setVisibility(View.VISIBLE);
        setSystemBarsHidden(true);
    }

    private void unlockApp() {
        unlocked = true;
        removeOverlayGuard();
        releasePinning();
        clearPinInput();
        lockLayout.setVisibility(View.GONE);
        mainContent.setVisibility(View.VISIBLE);
        rootFrame.setAlpha(1f);
        setSystemBarsHidden(false);
        updateStatus();
    }

    private void ensurePinnedWhileLocked() {
        if (unlocked || awaitingPermissionResult) {
            return;
        }
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am != null && am.getLockTaskModeState() == ActivityManager.LOCK_TASK_MODE_NONE) {
                startLockTask();
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not start screen pinning: " + e.getMessage());
        }
    }

    private void releasePinning() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am != null && am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE) {
                stopLockTask();
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not stop screen pinning: " + e.getMessage());
        }
    }

    // ======================================================================== LOCK GUARD (overlay + bring-to-front)
    private boolean hasOverlayPermission() {
        return Settings.canDrawOverlays(this);
    }

    private void requestOverlayPermission() {
        if (hasOverlayPermission()) {
            return;
        }
        awaitingPermissionResult = true;
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        overlayPermissionLauncher.launch(intent);
    }

    private void addOverlayGuard() {
        if (overlayGuardView != null || !hasOverlayPermission()) {
            return;
        }
        try {
            overlayGuardView = new View(getApplicationContext());
            overlayGuardView.setBackgroundColor(Color.BLACK);
            overlayGuardView.setOnTouchListener((v, event) -> {
                bringToFront();
                return true;
            });

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    PixelFormat.OPAQUE
            );
            params.gravity = Gravity.TOP | Gravity.START;

            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (wm != null) {
                wm.addView(overlayGuardView, params);
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not add overlay guard: " + e.getMessage());
            overlayGuardView = null;
        }
    }

    private void removeOverlayGuard() {
        if (overlayGuardView != null) {
            try {
                WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
                if (wm != null) {
                    wm.removeView(overlayGuardView);
                }
            } catch (Exception e) {
                Log.w(TAG, "Could not remove overlay guard: " + e.getMessage());
            }
            overlayGuardView = null;
        }
    }

    private void bringToFront() {
        if (unlocked || awaitingPermissionResult || isFinishing()) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastBringToFrontTime < BRING_TO_FRONT_COOLDOWN_MS) {
            return;
        }
        lastBringToFrontTime = now;

        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am != null) {
                am.moveTaskToFront(getTaskId(), 0);
            }
        } catch (Exception e) {
            Log.w(TAG, "moveTaskToFront failed: " + e.getMessage());
        }

        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                | Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }

    // ======================================================================== DURESS / FAKE SHUTDOWN
    private void handleDuressPin() {
        triggerSilentAlert();
        Toast.makeText(this, "Battery Low - 1% remaining. Shutting down...", Toast.LENGTH_LONG).show();
        lockLayout.setVisibility(View.GONE);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            shutdownLogoLayout.setVisibility(View.VISIBLE);
        }, 1500);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            shutdownLogoLayout.setVisibility(View.GONE);
            activateFakeShutdown();
        }, 3500);
    }

    private void activateFakeShutdown() {
        fakeShutdownActive = true;
        cornerTapCount = 0;
        lastCornerTapTime = 0L;

        rootFrame.setAlpha(0f);
        rootFrame.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setWindowBrightness(0f);
        setSystemBarsHidden(true);
    }

    private void deactivateFakeShutdown() {
        fakeShutdownActive = false;
        cornerTapCount = 0;
        lastCornerTapTime = 0L;

        rootFrame.setAlpha(1f);
        rootFrame.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setWindowBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE);
        setSystemBarsHidden(false);

        lockApp();
    }

    @SuppressWarnings("deprecation")
    private void handleFakeShutdownTouch(MotionEvent ev) {
        if (ev.getActionMasked() != MotionEvent.ACTION_DOWN) {
            return;
        }

        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
        int screenHeight = metrics.heightPixels;

        float x = ev.getRawX();
        float y = ev.getRawY();
        boolean inSecretCorner = x < CORNER_SIZE_PX && y > (screenHeight - CORNER_SIZE_PX);

        if (!inSecretCorner) {
            cornerTapCount = 0;
            return;
        }

        long now = SystemClock.elapsedRealtime();
        if (now - lastCornerTapTime > MAX_TAP_GAP_MS) {
            cornerTapCount = 0;
        }
        cornerTapCount++;
        lastCornerTapTime = now;

        if (cornerTapCount >= REQUIRED_CORNER_TAPS) {
            deactivateFakeShutdown();
        }
    }

    // ======================================================================== WINDOW HELPERS
    private void setWindowBrightness(float value) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = value;
        getWindow().setAttributes(lp);
    }

    private void setSystemBarsHidden(boolean hidden) {
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (controller == null) {
            return;
        }
        if (hidden) {
            controller.setSystemBarsBehavior(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            controller.hide(WindowInsetsCompat.Type.systemBars());
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars());
        }
    }

    // ======================================================================== FIREBASE
    private void configureFirebasePersistence() {
        if (persistenceConfigured) {
            return;
        }
        persistenceConfigured = true;
        try {
            FirebaseDatabase.getInstance().setPersistenceEnabled(true);
        } catch (Exception e) {
            Log.w(TAG, "Firebase persistence already configured: " + e.getMessage());
        }
    }

    private void triggerSilentAlert() {
        if (!hasLocationPermission()) {
            pushAlertToFirebase(null);
            return;
        }

        try {
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener(this::pushAlertToFirebase)
                    .addOnFailureListener(e -> pushAlertToFirebase(null));
        } catch (SecurityException e) {
            pushAlertToFirebase(null);
        }
    }

    private void pushAlertToFirebase(@Nullable Location location) {
        new Thread(() -> {
            Map<String, Object> alert = new HashMap<>();
            alert.put("status", STATUS_SOS);
            alert.put("timestamp", ServerValue.TIMESTAMP);
            alert.put("clientTimestamp", System.currentTimeMillis());
            alert.put("deviceModel", Build.MANUFACTURER + " " + Build.MODEL);

            if (location != null) {
                String addressStr = "Location fetching...";
                try {
                    Geocoder geocoder = new Geocoder(this, Locale.getDefault());
                    List<Address> addresses = geocoder.getFromLocation(location.getLatitude(), location.getLongitude(), 1);
                    if (addresses != null && !addresses.isEmpty()) {
                        addressStr = addresses.get(0).getAddressLine(0);
                    }
                } catch (Exception e) {
                    addressStr = "Address not found";
                }

                String mapsLink = "https://maps.google.com/?q=" + location.getLatitude() + "," + location.getLongitude();

                alert.put("locationAvailable", true);
                alert.put("latitude", location.getLatitude());
                alert.put("longitude", location.getLongitude());
                alert.put("accuracyMeters", (double) location.getAccuracy());
                alert.put("mapsLink", mapsLink);
                alert.put("address", addressStr);
            } else {
                alert.put("locationAvailable", false);
            }

            FirebaseDatabase.getInstance().getReference(ALERTS_NODE)
                    .push()
                    .setValue(alert)
                    .addOnFailureListener(e -> Log.w(TAG, "Alert upload failed: " + e.getMessage()));
        }).start();
    }

    // ======================================================================== PERMISSIONS
    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestLocationPermission() {
        awaitingPermissionResult = true;
        locationPermissionLauncher.launch(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        });
    }

    // ======================================================================== CHANGE PIN DIALOG
    private void showChangePinDialog(final boolean forDuress) {
        final EditText currentField = makePinField("Current unlock PIN");
        final EditText newField = makePinField(forDuress
                ? "New duress PIN (4-8 digits)" : "New unlock PIN (4-8 digits)");
        final EditText confirmField = makePinField("Confirm new PIN");

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(currentField);
        box.addView(newField);
        box.addView(confirmField);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(forDuress ? "Change Duress PIN" : "Change Unlock PIN")
                .setMessage(forDuress
                        ? "Entering this PIN on the lock screen silently sends an SOS alert and fakes a "
                        + "shutdown. It must differ from your unlock PIN."
                        : "This PIN unlocks the app. It must differ from your duress PIN.")
                .setView(box)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                    WindowManager.LayoutParams.FLAG_SECURE);
        }

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String current = currentField.getText().toString();
                    String newPin = newField.getText().toString();
                    String confirm = confirmField.getText().toString();

                    if (!verifyPin(PREFIX_UNLOCK, current)) {
                        currentField.setError("Incorrect unlock PIN");
                        return;
                    }
                    if (!newPin.matches("\\d{" + MIN_PIN_LENGTH + "," + MAX_PIN_LENGTH + "}")) {
                        newField.setError("Use " + MIN_PIN_LENGTH + " to " + MAX_PIN_LENGTH + " digits");
                        return;
                    }
                    if (!newPin.equals(confirm)) {
                        confirmField.setError("PINs do not match");
                        return;
                    }
                    if (forDuress && verifyPin(PREFIX_UNLOCK, newPin)) {
                        newField.setError("Must be different from your unlock PIN");
                        return;
                    }
                    if (!forDuress && verifyPin(PREFIX_DURESS, newPin)) {
                        newField.setError("Must be different from your duress PIN");
                        return;
                    }

                    storePin(forDuress ? PREFIX_DURESS : PREFIX_UNLOCK, newPin);
                    prefs.edit()
                            .putBoolean(forDuress ? KEY_DURESS_CUSTOM : KEY_UNLOCK_CUSTOM, true)
                            .commit();
                    updateStatus();
                    Toast.makeText(MainActivity.this,
                            forDuress ? "Duress PIN updated" : "Unlock PIN updated",
                            Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                }));

        dialog.show();
    }

    private EditText makePinField(String hint) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_PIN_LENGTH)});
        field.setSingleLine(true);
        return field;
    }

    // ======================================================================== PIN HASHING (PBKDF2)
    private void ensureDefaultPins() {
        if (!prefs.contains(PREFIX_UNLOCK + SUFFIX_HASH)) {
            storePin(PREFIX_UNLOCK, DEFAULT_UNLOCK_PIN);
            prefs.edit().putBoolean(KEY_UNLOCK_CUSTOM, false).commit();
        }
        if (!prefs.contains(PREFIX_DURESS + SUFFIX_HASH)) {
            storePin(PREFIX_DURESS, DEFAULT_DURESS_PIN);
            prefs.edit().putBoolean(KEY_DURESS_CUSTOM, false).commit();
        }
    }

    private void storePin(String prefix, String pin) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        byte[] hash = pbkdf2(pin, salt);
        prefs.edit()
                .putString(prefix + SUFFIX_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString(prefix + SUFFIX_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
                .commit();
    }

    private boolean verifyPin(String prefix, String pin) {
        String saltB64 = prefs.getString(prefix + SUFFIX_SALT, null);
        String hashB64 = prefs.getString(prefix + SUFFIX_HASH, null);
        if (saltB64 == null || hashB64 == null) {
            return false;
        }
        byte[] salt = Base64.decode(saltB64, Base64.NO_WRAP);
        byte[] expected = Base64.decode(hashB64, Base64.NO_WRAP);
        byte[] actual = pbkdf2(pin, salt);
        return MessageDigest.isEqual(expected, actual);
    }

    private static byte[] pbkdf2(String pin, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, PBKDF2_KEY_BITS);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
            return factory.generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("PBKDF2 unavailable", e);
        } finally {
            spec.clearPassword();
        }
    }
}