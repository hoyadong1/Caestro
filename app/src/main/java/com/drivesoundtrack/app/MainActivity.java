package com.drivesoundtrack.app;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private final String[] names = {"Pulse", "Night", "Coast"};
    private TextView status;
    private Button play;
    private SeekBar speed;
    private TextView demoLabel;
    private final Button[] presetButtons = new Button[3];
    private DriveRestrictionsReader restrictionsReader;
    private boolean restricted;
    private double currentKmh;
    private int preset;
    private boolean playing;
    private boolean carAvailable;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            playing = intent.getBooleanExtra("playing", false);
            preset = intent.getIntExtra("preset", 0);
            carAvailable = intent.getBooleanExtra("carAvailable", false);
            double kmh = intent.getDoubleExtra("kmh", 0);
            currentKmh = kmh;
            int bpm = (int) Math.round(intent.getDoubleExtra("bpm", 92));
            status.setText(names[preset] + "   ·   " + Math.round(kmh) + " km/h   ·   " + bpm + " BPM" + (carAvailable ? "\n차량 연결" : "\n시연 모드"));
            play.setText(playing ? "일시정지" : "재생");
            for (int i = 0; i < presetButtons.length; i++) {
                presetButtons[i].setBackground(shape(i == preset ? Color.rgb(50, 183, 179) : Color.rgb(29, 39, 54), 16));
                presetButtons[i].setTextColor(i == preset ? Color.rgb(8, 19, 28) : Color.WHITE);
            }
            renderRestrictions();
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        FrameLayout outer = new FrameLayout(this);
        outer.setBackgroundColor(Color.rgb(9, 16, 27));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 26, 24, 26);
        root.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("DRIVE SOUNDTRACK", 34, Color.WHITE);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);
        TextView subtitle = text("주행이 만드는 일렉트로닉 사운드", 18, Color.rgb(169, 190, 207));
        root.addView(subtitle);
        status = text("Pulse  ·  시연 모드", 25, Color.rgb(120, 234, 224));
        status.setPadding(28, 28, 28, 28);
        status.setBackground(shape(Color.rgb(22, 39, 53), 20));
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-1, -2);
        gap.topMargin = 32;
        root.addView(status, gap);
        LinearLayout presets = new LinearLayout(this);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < names.length; i++) {
            final int choice = i;
            Button button = new Button(this);
            button.setText(names[i]);
            button.setTextSize(18);
            button.setAllCaps(false);
            button.setTextColor(Color.WHITE);
            button.setBackground(shape(i == 0 ? Color.rgb(50, 183, 179) : Color.rgb(29, 39, 54), 16));
            button.setOnClickListener(v -> send(SoundtrackService.PRESET, "preset", choice));
            presetButtons[i] = button;
            LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(0, 76, 1);
            buttonParams.setMargins(4, 0, 4, 0);
            presets.addView(button, buttonParams);
        }
        root.addView(presets, gap);
        play = new Button(this);
        play.setText("재생");
        play.setTextSize(23);
        play.setAllCaps(false);
        play.setTextColor(Color.rgb(8, 19, 28));
        play.setBackground(shape(Color.rgb(113, 233, 220), 18));
        play.setMinHeight(94);
        play.setOnClickListener(v -> send(playing ? SoundtrackService.PAUSE : SoundtrackService.PLAY));
        root.addView(play, gap);
        demoLabel = text("시연 속도", 14, Color.rgb(170, 189, 209));
        root.addView(demoLabel, gap);
        speed = new SeekBar(this);
        speed.setMax(120);
        speed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean user) {
                if (user && !carAvailable) send(SoundtrackService.DEMO_SPEED, "kmh", (double) value);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        root.addView(speed);
        TextView safety = text("주행 중에는 프리셋 변경과 시연 조작이 제한됩니다.", 15, Color.rgb(132, 153, 170));
        root.addView(safety, gap);
        int displayWidth = getResources().getDisplayMetrics().widthPixels;
        int width = Math.min(displayWidth - 40, (int) (1100 * getResources().getDisplayMetrics().density));
        outer.addView(root, new FrameLayout.LayoutParams(width, -2, Gravity.CENTER));
        setContentView(outer);
        startService(new Intent(this, SoundtrackService.class));
    }

    private TextView text(String value, int size, int color) {
        TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color); return v;
    }
    private GradientDrawable shape(int color, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radiusDp * getResources().getDisplayMetrics().density);
        return drawable;
    }
    private void send(String action) { startService(new Intent(this, SoundtrackService.class).setAction(action)); }
    private void send(String action, String key, int value) { startService(new Intent(this, SoundtrackService.class).setAction(action).putExtra(key, value)); }
    private void send(String action, String key, double value) { startService(new Intent(this, SoundtrackService.class).setAction(action).putExtra(key, value)); }
    private void renderRestrictions() {
        boolean canConfigure = !restricted && (!carAvailable || currentKmh < 5);
        for (Button button : presetButtons) button.setEnabled(canConfigure);
        int demoVisibility = carAvailable || restricted ? View.GONE : View.VISIBLE;
        speed.setVisibility(demoVisibility);
        demoLabel.setVisibility(demoVisibility);
    }
    @Override protected void onStart() {
        super.onStart();
        registerReceiver(receiver, new IntentFilter(SoundtrackService.STATUS), Context.RECEIVER_NOT_EXPORTED);
        send(SoundtrackService.STATUS_REQUEST);
        try {
            restrictionsReader = new DriveRestrictionsReader();
            restrictionsReader.start(this, value -> runOnUiThread(() -> { restricted = value; renderRestrictions(); }));
        } catch (LinkageError ignored) { }
    }
    @Override protected void onStop() {
        if (restrictionsReader != null) restrictionsReader.stop();
        unregisterReceiver(receiver);
        super.onStop();
    }
}
