package app.klabs.pitcrew;

import android.content.Context;
import android.content.SharedPreferences;

public class PitcrewPreferences {

    private static final String PREFS_NAME = "pitcrew_prefs";
    private static final String KEY_SESSION_ID = "session_id";
    private static final String KEY_BROKER_HOST = "broker_host";
    private static final String KEY_BROKER_PORT = "broker_port";
    private static final String KEY_DEVICE_ID = "device_id";

    private final SharedPreferences prefs;

    public PitcrewPreferences(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public void saveSessionConfig(SessionConfig config) {
        prefs.edit()
                .putString(KEY_SESSION_ID, config.getSessionId())
                .putString(KEY_BROKER_HOST, config.getBrokerHost())
                .putInt(KEY_BROKER_PORT, config.getBrokerPort())
                .apply();
    }

    public SessionConfig getSessionConfig() {
        String sessionId = prefs.getString(KEY_SESSION_ID, null);
        if (sessionId == null) return null;
        String host = prefs.getString(KEY_BROKER_HOST, SessionConfig.DEFAULT_HOST);
        int port = prefs.getInt(KEY_BROKER_PORT, SessionConfig.DEFAULT_PORT);
        return new SessionConfig(sessionId, host, port);
    }

    public void saveDeviceId(String deviceId) {
        prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply();
    }

    public String getDeviceId() {
        return prefs.getString(KEY_DEVICE_ID, null);
    }
}
