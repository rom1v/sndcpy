package com.rom1v.sndcpy;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * RecordService: Captures phone's audio playback and streams it as raw PCM
 * directly over TCP to the configured server IP:port. No ADB required.
 *
 * The service auto-reconnects if the connection drops, and runs indefinitely
 * until explicitly stopped.
 */
public class RecordService extends Service {

    private static final String TAG = "sndcpy";
    private static final String CHANNEL_ID = "sndcpy";
    private static final int NOTIFICATION_ID = 1;

    private static final String ACTION_RECORD = "com.rom1v.sndcpy.RECORD";
    private static final String ACTION_STOP = "com.rom1v.sndcpy.STOP";
    private static final String EXTRA_MEDIA_PROJECTION_DATA = "mediaProjectionData";
    private static final String EXTRA_SERVER_IP = "serverIp";
    private static final String EXTRA_SERVER_PORT = "serverPort";

    private static final int MSG_CONNECTION_ESTABLISHED = 1;
    private static final int MSG_CONNECTION_LOST = 2;

    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNELS = 2;
    private static final int BUFFER_MS = 15;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int RECONNECT_DELAY_MS = 3_000;

    // Static flag so MainActivity can query running state
    private static volatile boolean sRunning = false;

    private final Handler handler = new ConnectionHandler(this);
    private MediaProjection mediaProjection;
    private Thread recorderThread;

    private AudioManager audioManager;
    private int savedVolume = -1; // -1 means not yet saved

    // --- Public API ---

    public static void start(Context context, Intent projectionData, String serverIp, int serverPort) {
        Intent intent = new Intent(context, RecordService.class);
        intent.setAction(ACTION_RECORD);
        intent.putExtra(EXTRA_MEDIA_PROJECTION_DATA, projectionData);
        intent.putExtra(EXTRA_SERVER_IP, serverIp);
        intent.putExtra(EXTRA_SERVER_PORT, serverPort);
        context.startForegroundService(intent);
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, RecordService.class);
        intent.setAction(ACTION_STOP);
        context.startService(intent);
    }

    public static boolean isRunning() {
        return sRunning;
    }

    // --- Service lifecycle ---

    @Override
    public void onCreate() {
        super.onCreate();
        sRunning = true;

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_NONE);
        getNotificationManager().createNotificationChannel(channel);

        Notification notification = createNotification(false);
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        String action = intent.getAction();

        if (ACTION_STOP.equals(action)) {
            // Explicit stop — restore volume immediately (covers both Stop button and notification stop)
            restorePhoneAudio();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_RECORD.equals(action) && !isRecording()) {
            String serverIp = intent.getStringExtra(EXTRA_SERVER_IP);
            int serverPort = intent.getIntExtra(EXTRA_SERVER_PORT, 28200);

            Intent projData = intent.getParcelableExtra(EXTRA_MEDIA_PROJECTION_DATA);
            MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            mediaProjection = mgr.getMediaProjection(Activity.RESULT_OK, projData);

            if (mediaProjection != null) {
                startRecording(serverIp, serverPort);
            } else {
                Log.w(TAG, "Failed to get MediaProjection — stopping");
                stopSelf();
            }
        }

        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        sRunning = false;
        stopForeground(true);
        // Safety net: restore volume if the service is killed by OS or crashes
        // without going through ACTION_STOP (idempotent — only runs if savedVolume was set)
        restorePhoneAudio();
        if (recorderThread != null) {
            recorderThread.interrupt();
            recorderThread = null;
        }
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
    }

    // --- Recording logic ---

    private boolean isRecording() {
        return recorderThread != null && recorderThread.isAlive();
    }

    // --- Volume management ---

    /** Saves current media volume and mutes it so audio plays only on the PC. */
    private void mutePhoneAudio() {
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        savedVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
        Log.i(TAG, "Phone audio muted (saved volume: " + savedVolume + ")");
    }

    /**
     * Restores the media volume to what it was before muting.
     * Idempotent — safe to call multiple times; only acts if volume was previously saved.
     */
    private void restorePhoneAudio() {
        if (audioManager != null && savedVolume >= 0) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0);
            Log.i(TAG, "Phone audio restored to " + savedVolume);
            savedVolume = -1; // reset sentinel so subsequent calls are no-ops
        }
    }

    private void startRecording(final String serverIp, final int serverPort) {
        final AudioRecord recorder = createAudioRecord();

        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "AudioRecord not initialized — audio capture may be blocked by the playing app");
            stopSelf();
            return;
        }

        // Mute phone speaker so audio only plays on the PC
        mutePhoneAudio();
        recorder.startRecording();
        Log.i(TAG, "AudioRecord started. Streaming to " + serverIp + ":" + serverPort);

        recorderThread = new Thread(() -> {
            byte[] buf = new byte[SAMPLE_RATE * CHANNELS * 2 * BUFFER_MS / 1000]; // 2 bytes per sample (PCM_16BIT)

            while (!Thread.currentThread().isInterrupted()) {
                try {
                    // Connect to the PC server
                    Socket socket = new Socket();
                    socket.setTcpNoDelay(true);
                    socket.connect(new InetSocketAddress(serverIp, serverPort), CONNECT_TIMEOUT_MS);
                    Log.i(TAG, "Connected to " + serverIp + ":" + serverPort);
                    handler.sendEmptyMessage(MSG_CONNECTION_ESTABLISHED);

                    try (Socket s = socket) {
                        OutputStream out = s.getOutputStream();
                        // Stream PCM until the connection closes or we're interrupted
                        while (!Thread.currentThread().isInterrupted()) {
                            int r = recorder.read(buf, 0, buf.length);
                            if (r > 0) {
                                out.write(buf, 0, r);
                            } else if (r < 0) {
                                Log.w(TAG, "AudioRecord.read() error: " + r);
                                break;
                            }
                        }
                    }

                    Log.i(TAG, "Connection closed — reconnecting in " + RECONNECT_DELAY_MS + "ms");
                    handler.sendEmptyMessage(MSG_CONNECTION_LOST);

                } catch (IOException e) {
                    if (!Thread.currentThread().isInterrupted()) {
                        Log.d(TAG, "Connection failed (" + e.getMessage() + ") — retrying in " + RECONNECT_DELAY_MS + "ms");
                        handler.sendEmptyMessage(MSG_CONNECTION_LOST);
                    }
                }

                // Wait before reconnecting (don't hammer the network)
                if (!Thread.currentThread().isInterrupted()) {
                    try {
                        Thread.sleep(RECONNECT_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }

            // Cleanup
            recorder.stop();
            recorder.release();
            Log.i(TAG, "RecordService stopped");
            stopSelf();
        }, "sndcpy-recorder");

        recorderThread.start();
    }

    private AudioRecord createAudioRecord() {
        AudioPlaybackCaptureConfiguration captureConfig =
                new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build();

        AudioFormat audioFormat = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNELS == 2 ? AudioFormat.CHANNEL_IN_STEREO : AudioFormat.CHANNEL_IN_MONO)
                .build();

        return new AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(1024 * 1024) // 1MB capture buffer
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build();
    }

    // --- Notification ---

    private Notification createNotification(boolean connected) {
        int textRes = connected ? R.string.notification_forwarding : R.string.notification_waiting;
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getText(textRes))
                .setSmallIcon(R.drawable.ic_album_black_24dp)
                .addAction(createStopAction())
                .build();
    }

    private Notification.Action createStopAction() {
        Intent stopIntent = new Intent(this, RecordService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent pi = PendingIntent.getService(this, 0, stopIntent,
                PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
        Icon icon = Icon.createWithResource(this, R.drawable.ic_close_24dp);
        return new Notification.Action.Builder(icon, getString(R.string.action_stop), pi).build();
    }

    private NotificationManager getNotificationManager() {
        return (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    }

    // --- Handler ---

    private static final class ConnectionHandler extends Handler {
        private final RecordService service;

        ConnectionHandler(RecordService service) {
            this.service = service;
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_CONNECTION_ESTABLISHED:
                    service.getNotificationManager().notify(
                            NOTIFICATION_ID, service.createNotification(true));
                    break;
                case MSG_CONNECTION_LOST:
                    service.getNotificationManager().notify(
                            NOTIFICATION_ID, service.createNotification(false));
                    break;
            }
        }
    }
}
