package com.example.ghostprotocol;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    public static final String PREFS_NAME = "GhostProtocolPrefs";
    public static final String KEY_UNLOCK_PIN = "unlock_pin_hash";
    public static final String KEY_DURESS_PIN = "duress_pin_hash";
    public static final String KEY_FROM_UNLOCK = "from_unlock";
    public static final String KEY_SOS_ACTIVE = "sos_active";

    private SharedPreferences prefs;
    private TextView unlockStatusView;
    private TextView duressStatusView;
    private TextView locationStatusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#0D1117"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(24));

        TextView title = new TextView(this);
        title.setText("STEALTH SOS // GHOST PROTOCOL");
        title.setTextColor(Color.parseColor("#58A6FF"));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Anti-Coercion Dual-PIN Security System");
        subtitle.setTextColor(Color.parseColor("#8B949E"));
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        subtitle.setPadding(0, dp(4), 0, dp(20));
        root.addView(subtitle);

        LinearLayout statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusCard.setBackground(roundedBg("#161B22", 12));
        statusCard.setPadding(dp(16), dp(16), dp(16), dp(16));

        unlockStatusView = createStatusRow();
        duressStatusView = createStatusRow();
        locationStatusView = createStatusRow();

        statusCard.addView(unlockStatusView);
        statusCard.addView(duressStatusView);
        statusCard.addView(locationStatusView);
        root.addView(statusCard);

        Button permBtn = new Button(this);
        permBtn.setText("GRANT EMERGENCY PERMISSIONS");
        permBtn.setTextColor(Color.WHITE);
        permBtn.setBackground(roundedBg("#238636", 10));
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnParams.topMargin = dp(18);
        permBtn.setLayoutParams(btnParams);
        permBtn.setOnClickListener(v -> requestEmergencyPermissions());
        root.addView(permBtn);

        scroll.addView(root);
        setContentView(scroll);

        updateStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private TextView createStatusRow() {
        TextView tv = new TextView(this);
        tv.setTextColor(Color.parseColor("#C9D1D9"));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setPadding(0, dp(6), 0, dp(6));
        return tv;
    }

    private void updateStatus() {
        boolean unlockCustom = prefs.contains(KEY_UNLOCK_