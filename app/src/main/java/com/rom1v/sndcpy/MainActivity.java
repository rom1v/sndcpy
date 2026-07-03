package com.rom1v.sndcpy;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import java.util.UUID;

public class MainActivity extends Activity {

    // ── Preferences ──────────────────────────────────────────────────────────
    private static final String PREFS_NAME      = "sndcpy_prefs";
    private static final String PREF_SERVER_IP  = "server_ip";
    private static final String PREF_SERVER_PORT = "server_port";
    private static final int    DEFAULT_PORT    = 28200;

    // ── Request codes ─────────────────────────────────────────────────────────
    private static final int REQUEST_CODE_PERMISSION_AUDIO = 1;
    private static final int REQUEST_CODE_START_CAPTURE   = 2;
    private static final int REQUEST_CODE_SERVER_LIST     = 3;

    // ── Views ─────────────────────────────────────────────────────────────────
    private EditText    editNickname;
    private EditText    editServerIp;
    private EditText    editServerPort;
    private Button      btnStartStop;
    private TextView    tvStatus;
    private ImageButton btnServerHistory;

    // ── State ─────────────────────────────────────────────────────────────────
    private boolean isStreaming = false;

    /**
     * The UUID of the server entry currently loaded into the fields.
     * Null when no history entry is loaded (i.e. fresh / unsaved server).
     * On successful connect: if null → create new entry; if set → update that entry.
     */
    private String currentServerId = null;

    private ServerHistoryManager historyManager;

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        historyManager = new ServerHistoryManager(this);

        editNickname     = findViewById(R.id.editNickname);
        editServerIp     = findViewById(R.id.editServerIp);
        editServerPort   = findViewById(R.id.editServerPort);
        btnStartStop     = findViewById(R.id.btnStartStop);
        tvStatus         = findViewById(R.id.tvStatus);
        btnServerHistory = findViewById(R.id.btnServerHistory);

        // Restore last-used IP/port (not loading from history — just the bare fields)
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String savedIp   = prefs.getString(PREF_SERVER_IP, "");
        int    savedPort = prefs.getInt(PREF_SERVER_PORT, DEFAULT_PORT);
        editServerIp.setText(savedIp);
        editServerPort.setText(String.valueOf(savedPort));

        // Auto-populate nickname with a random 5-digit number if this is a fresh session
        ensureNickname();

        // Check if service is already running
        isStreaming = RecordService.isRunning();
        updateUiState();

        btnStartStop.setOnClickListener(v -> {
            if (isStreaming) {
                stopStreaming();
            } else {
                startStreaming();
            }
        });

        btnServerHistory.setOnClickListener(v -> {
            Intent intent = new Intent(this, ServerListActivity.class);
            startActivityForResult(intent, REQUEST_CODE_SERVER_LIST);
        });
    }

    // ── Nickname helpers ──────────────────────────────────────────────────────

    /** If the nickname field is empty, fill it with a random 5-digit number. */
    private void ensureNickname() {
        if (TextUtils.isEmpty(editNickname.getText().toString().trim())) {
            int random5 = 10000 + (int) (Math.random() * 90000);
            editNickname.setText(String.valueOf(random5));
        }
    }

    // ── Streaming control ─────────────────────────────────────────────────────

    private void startStreaming() {
        String ip      = editServerIp.getText().toString().trim();
        String portStr = editServerPort.getText().toString().trim();

        if (TextUtils.isEmpty(ip)) {
            editServerIp.setError(getString(R.string.error_invalid_ip));
            return;
        }

        int port;
        try {
            port = Integer.parseInt(portStr);
            if (port < 1 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            editServerPort.setError(getString(R.string.error_invalid_port));
            return;
        }

        // Persist last-used values so they survive restarts
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(PREF_SERVER_IP, ip)
                .putInt(PREF_SERVER_PORT, port)
                .apply();

        // Hide keyboard
        InputMethodManager imm =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(editServerIp.getWindowToken(), 0);

        // Request RECORD_AUDIO if not yet granted
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    REQUEST_CODE_PERMISSION_AUDIO);
            return;
        }

        requestMediaProjection();
    }

    private void requestMediaProjection() {
        MediaProjectionManager mgr =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mgr.createScreenCaptureIntent(), REQUEST_CODE_START_CAPTURE);
        tvStatus.setText(getString(R.string.status_connecting));
    }

    private void stopStreaming() {
        RecordService.stop(this);
        isStreaming = false;
        updateUiState();
    }

    // ── History save / update ─────────────────────────────────────────────────

    /**
     * Called once media-projection permission is granted and streaming is confirmed.
     * Saves a new entry or updates the existing one (identified by {@link #currentServerId}).
     */
    private void persistServerEntry() {
        String ip       = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_SERVER_IP, "");
        int    port     = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(PREF_SERVER_PORT, DEFAULT_PORT);
        String nickname = editNickname.getText().toString().trim();
        if (TextUtils.isEmpty(nickname)) {
            ensureNickname();
            nickname = editNickname.getText().toString().trim();
        }

        if (currentServerId == null) {
            // Brand-new entry — generate a fresh UUID
            currentServerId = UUID.randomUUID().toString();
        }

        ServerEntry entry = new ServerEntry(currentServerId, nickname, ip, port,
                System.currentTimeMillis());
        historyManager.saveOrUpdate(entry);
    }

    // ── Activity results ──────────────────────────────────────────────────────

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        if (requestCode == REQUEST_CODE_PERMISSION_AUDIO) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                requestMediaProjection();
            } else {
                Toast.makeText(this, "Microphone permission required",
                        Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CODE_START_CAPTURE) {
            if (resultCode == Activity.RESULT_OK) {
                // Permission granted — save/update history entry first
                persistServerEntry();

                String ip   = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .getString(PREF_SERVER_IP, "");
                int    port = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .getInt(PREF_SERVER_PORT, DEFAULT_PORT);
                RecordService.start(this, data, ip, port);
                isStreaming = true;
                updateUiState();
            } else {
                tvStatus.setText(getString(R.string.status_idle));
                Toast.makeText(this, "Permission denied — cannot capture audio",
                        Toast.LENGTH_SHORT).show();
            }

        } else if (requestCode == REQUEST_CODE_SERVER_LIST
                && resultCode == Activity.RESULT_OK && data != null) {
            // User selected a server from the history list
            currentServerId = data.getStringExtra(ServerListActivity.EXTRA_ENTRY_ID);
            String nickname = data.getStringExtra(ServerListActivity.EXTRA_ENTRY_NICKNAME);
            String ip       = data.getStringExtra(ServerListActivity.EXTRA_ENTRY_IP);
            int    port     = data.getIntExtra(ServerListActivity.EXTRA_ENTRY_PORT, DEFAULT_PORT);

            editNickname.setText(nickname);
            editServerIp.setText(ip);
            editServerPort.setText(String.valueOf(port));
        }
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private void updateUiState() {
        if (isStreaming) {
            btnStartStop.setText(getString(R.string.btn_stop_streaming));
            btnStartStop.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(0xFFB71C1C)); // red
            tvStatus.setText(getString(R.string.status_streaming));
            tvStatus.setTextColor(0xFF2E7D32); // green
            editNickname.setEnabled(false);
            editServerIp.setEnabled(false);
            editServerPort.setEnabled(false);
            btnServerHistory.setEnabled(false);
        } else {
            btnStartStop.setText(getString(R.string.btn_start_streaming));
            btnStartStop.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(0xFF1565C0)); // blue
            tvStatus.setText(getString(R.string.status_idle));
            tvStatus.setTextColor(0xFF757575); // grey
            editNickname.setEnabled(true);
            editServerIp.setEnabled(true);
            editServerPort.setEnabled(true);
            btnServerHistory.setEnabled(true);
        }
    }
}
