package app.klabs.pitcrew;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;
import com.hivemq.client.mqtt.lifecycle.MqttClientConnectedContext;
import com.hivemq.client.mqtt.lifecycle.MqttClientDisconnectedContext;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class MqttService extends Service {

    private static final String TAG = "MqttService";
    private static final String CHANNEL_ID = "pitcrew_channel";
    private static final int NOTIFICATION_ID = 1;

    public static final String EXTRA_SESSION_ID = "session_id";
    public static final String EXTRA_BROKER_HOST = "broker_host";
    public static final String EXTRA_BROKER_PORT = "broker_port";
    public static final String EXTRA_DEVICE_ID = "device_id";

    private final IBinder binder = new LocalBinder();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<ChatMessage> messages = Collections.synchronizedList(new ArrayList<>());

    private Mqtt3AsyncClient client;
    private SessionConfig config;
    private String deviceId;
    private MessageListener messageListener;
    private boolean connected = false;

    public interface MessageListener {
        void onMessageReceived(ChatMessage message);
        void onConnectionStateChanged(boolean connected);
    }

    public class LocalBinder extends Binder {
        public MqttService getService() {
            return MqttService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notification_connecting)));

        if (intent != null) {
            String sessionId = intent.getStringExtra(EXTRA_SESSION_ID);
            String host = intent.getStringExtra(EXTRA_BROKER_HOST);
            int port = intent.getIntExtra(EXTRA_BROKER_PORT, SessionConfig.DEFAULT_PORT);
            deviceId = intent.getStringExtra(EXTRA_DEVICE_ID);

            if (sessionId != null && host != null && deviceId != null) {
                config = new SessionConfig(sessionId, host, port);
                connectMqtt();
            }
        }

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        disconnectMqtt();
        super.onDestroy();
    }

    // --- Public API for bound clients ---

    public List<ChatMessage> getMessages() {
        return new ArrayList<>(messages);
    }

    public void sendMessage(String text) {
        if (client == null || !connected || config == null || deviceId == null) {
            Log.w(TAG, "Cannot send: not connected");
            return;
        }
        try {
            JSONObject json = new JSONObject();
            json.put("from", deviceId);
            json.put("text", text);
            byte[] payload = json.toString().getBytes(StandardCharsets.UTF_8);

            client.publishWith()
                    .topic(config.getChatTopic())
                    .payload(payload)
                    .send()
                    .whenComplete((result, throwable) -> {
                        if (throwable != null) {
                            Log.e(TAG, "Publish failed: " + throwable.getMessage());
                        }
                    });
        } catch (JSONException e) {
            Log.e(TAG, "Failed to build chat message", e);
        }
    }

    public void setMessageListener(MessageListener listener) {
        this.messageListener = listener;
    }

    public boolean isConnected() {
        return connected;
    }

    // --- MQTT ---

    private void connectMqtt() {
        if (client != null) {
            try { client.disconnect(); } catch (Exception ignored) {}
        }

        Log.i(TAG, "Connecting to " + config.getBrokerHost() + ":" + config.getBrokerPort());

        client = MqttClient.builder()
                .useMqttVersion3()
                .serverHost(config.getBrokerHost())
                .serverPort(config.getBrokerPort())
                .automaticReconnect()
                    .initialDelay(3, TimeUnit.SECONDS)
                    .maxDelay(30, TimeUnit.SECONDS)
                    .applyAutomaticReconnect()
                .addConnectedListener((MqttClientConnectedContext ctx) -> {
                    Log.i(TAG, "MQTT connected");
                    connected = true;
                    subscribeAndAnnounce();
                    updateNotification(getString(R.string.notification_connected));
                    notifyConnectionState(true);
                })
                .addDisconnectedListener((MqttClientDisconnectedContext ctx) -> {
                    Log.w(TAG, "MQTT disconnected: " + ctx.getCause().getMessage());
                    connected = false;
                    updateNotification(getString(R.string.notification_disconnected));
                    notifyConnectionState(false);
                })
                .willPublish()
                    .topic(config.getStatusTopic(deviceId))
                    .payload("{\"status\":\"offline\"}".getBytes(StandardCharsets.UTF_8))
                    .retain(true)
                    .applyWillPublish()
                .buildAsync();

        client.connect()
                .whenComplete((ack, throwable) -> {
                    if (throwable != null) {
                        Log.e(TAG, "MQTT connect failed: " + throwable.getMessage());
                        updateNotification(getString(R.string.notification_disconnected));
                    }
                });
    }

    private void subscribeAndAnnounce() {
        // Publish online status
        client.publishWith()
                .topic(config.getStatusTopic(deviceId))
                .payload("{\"status\":\"online\"}".getBytes(StandardCharsets.UTF_8))
                .retain(true)
                .send();

        // Subscribe to chat
        client.subscribeWith()
                .topicFilter(config.getChatTopic())
                .callback(publish -> {
                    if (publish.getPayload().isPresent()) {
                        byte[] bytes = new byte[publish.getPayload().get().remaining()];
                        publish.getPayload().get().get(bytes);
                        handleChatPayload(bytes);
                    }
                })
                .send()
                .whenComplete((subAck, throwable) -> {
                    if (throwable != null) {
                        Log.e(TAG, "Subscribe failed: " + throwable.getMessage());
                    } else {
                        Log.i(TAG, "Subscribed to " + config.getChatTopic());
                    }
                });
    }

    private void handleChatPayload(byte[] payload) {
        try {
            JSONObject json = new JSONObject(new String(payload, StandardCharsets.UTF_8));
            String from = json.optString("from", "?");
            String text = json.optString("text", "");
            ChatMessage msg = new ChatMessage(from, text);
            messages.add(msg);

            mainHandler.post(() -> {
                if (messageListener != null) {
                    messageListener.onMessageReceived(msg);
                }
            });
        } catch (JSONException e) {
            Log.w(TAG, "Malformed chat message payload");
        }
    }

    private void disconnectMqtt() {
        if (client != null) {
            try {
                // Publish offline before disconnect
                client.publishWith()
                        .topic(config.getStatusTopic(deviceId))
                        .payload("{\"status\":\"offline\"}".getBytes(StandardCharsets.UTF_8))
                        .retain(true)
                        .send();
                client.disconnect();
            } catch (Exception e) {
                Log.w(TAG, "Error during disconnect: " + e.getMessage());
            }
            client = null;
        }
        connected = false;
    }

    // --- Notifications ---

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel),
                    NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    private void notifyConnectionState(boolean isConnected) {
        mainHandler.post(() -> {
            if (messageListener != null) {
                messageListener.onConnectionStateChanged(isConnected);
            }
        });
    }
}
