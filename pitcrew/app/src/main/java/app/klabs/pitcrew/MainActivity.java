package app.klabs.pitcrew;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private View waitingContainer;
    private View nameContainer;
    private View chatContainer;
    private TextInputEditText editDeviceName;
    private TextInputEditText editMessage;
    private RecyclerView recyclerMessages;

    private PitcrewPreferences preferences;
    private ChatAdapter chatAdapter;
    private MqttService mqttService;
    private boolean serviceBound = false;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            mqttService = ((MqttService.LocalBinder) binder).getService();
            serviceBound = true;

            // Load message history
            chatAdapter.setMessages(mqttService.getMessages());
            scrollToBottom();

            // Listen for new messages
            mqttService.setMessageListener(new MqttService.MessageListener() {
                @Override
                public void onMessageReceived(ChatMessage message) {
                    chatAdapter.addMessage(message);
                    scrollToBottom();
                }

                @Override
                public void onConnectionStateChanged(boolean connected) {
                    // Could update UI indicator here in future
                }
            });
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mqttService = null;
            serviceBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        preferences = new PitcrewPreferences(this);

        waitingContainer = findViewById(R.id.waitingContainer);
        nameContainer = findViewById(R.id.nameContainer);
        chatContainer = findViewById(R.id.chatContainer);
        editDeviceName = findViewById(R.id.editDeviceName);
        editMessage = findViewById(R.id.editMessage);
        recyclerMessages = findViewById(R.id.recyclerMessages);

        MaterialButton buttonJoin = findViewById(R.id.buttonJoin);
        MaterialButton buttonSend = findViewById(R.id.buttonSend);

        buttonJoin.setOnClickListener(v -> onJoinClicked());
        buttonSend.setOnClickListener(v -> onSendClicked());

        // Handle deep link if launched from QR scan
        handleIntent(getIntent());

        // Decide initial state
        SessionConfig config = preferences.getSessionConfig();
        String deviceId = preferences.getDeviceId();

        if (config != null && deviceId != null) {
            showChat(config, deviceId);
        } else if (config != null) {
            showNameEntry();
        } else {
            showWaiting();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onDestroy() {
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
        super.onDestroy();
    }

    // --- Deep link handling ---

    private void handleIntent(Intent intent) {
        if (intent == null || intent.getData() == null) return;

        Uri uri = intent.getData();
        if (!"pitstopper".equals(uri.getScheme()) || !"join".equals(uri.getHost())) return;

        String sessionId = uri.getQueryParameter("session");
        String host = uri.getQueryParameter("host");
        String portStr = uri.getQueryParameter("port");

        if (TextUtils.isEmpty(sessionId)) {
            Toast.makeText(this, "Invalid QR: missing session", Toast.LENGTH_SHORT).show();
            return;
        }

        String brokerHost = TextUtils.isEmpty(host) ? SessionConfig.DEFAULT_HOST : host;
        int brokerPort = SessionConfig.DEFAULT_PORT;
        if (!TextUtils.isEmpty(portStr)) {
            try { brokerPort = Integer.parseInt(portStr); } catch (NumberFormatException ignored) {}
        }

        SessionConfig config = new SessionConfig(sessionId, brokerHost, brokerPort);
        preferences.saveSessionConfig(config);

        Log.i(TAG, "Session received: " + sessionId + " @ " + brokerHost + ":" + brokerPort);

        String deviceId = preferences.getDeviceId();
        if (deviceId != null) {
            showChat(config, deviceId);
        } else {
            showNameEntry();
        }
    }

    // --- State transitions ---

    private void showWaiting() {
        waitingContainer.setVisibility(View.VISIBLE);
        nameContainer.setVisibility(View.GONE);
        chatContainer.setVisibility(View.GONE);
    }

    private void showNameEntry() {
        waitingContainer.setVisibility(View.GONE);
        nameContainer.setVisibility(View.VISIBLE);
        chatContainer.setVisibility(View.GONE);
    }

    private void showChat(SessionConfig config, String deviceId) {
        waitingContainer.setVisibility(View.GONE);
        nameContainer.setVisibility(View.GONE);
        chatContainer.setVisibility(View.VISIBLE);

        chatAdapter = new ChatAdapter(deviceId);
        recyclerMessages.setLayoutManager(new LinearLayoutManager(this));
        recyclerMessages.setAdapter(chatAdapter);

        startAndBindService(config, deviceId);
    }

    // --- Button handlers ---

    private void onJoinClicked() {
        String name = editDeviceName.getText() != null ? editDeviceName.getText().toString().trim() : "";
        if (TextUtils.isEmpty(name)) {
            editDeviceName.setError("Required");
            return;
        }

        preferences.saveDeviceId(name);

        SessionConfig config = preferences.getSessionConfig();
        if (config != null) {
            showChat(config, name);
        } else {
            showWaiting();
        }
    }

    private void onSendClicked() {
        if (mqttService == null || !serviceBound) return;
        String text = editMessage.getText() != null ? editMessage.getText().toString().trim() : "";
        if (TextUtils.isEmpty(text)) return;

        mqttService.sendMessage(text);
        editMessage.setText("");
    }

    // --- Service management ---

    private void startAndBindService(SessionConfig config, String deviceId) {
        Intent intent = new Intent(this, MqttService.class);
        intent.putExtra(MqttService.EXTRA_SESSION_ID, config.getSessionId());
        intent.putExtra(MqttService.EXTRA_BROKER_HOST, config.getBrokerHost());
        intent.putExtra(MqttService.EXTRA_BROKER_PORT, config.getBrokerPort());
        intent.putExtra(MqttService.EXTRA_DEVICE_ID, deviceId);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void scrollToBottom() {
        if (chatAdapter != null && chatAdapter.getItemCount() > 0) {
            recyclerMessages.scrollToPosition(chatAdapter.getItemCount() - 1);
        }
    }
}
