package app.klabs.pitcrew;

public class SessionConfig {
    private final String sessionId;
    private final String brokerHost;
    private final int brokerPort;

    public static final String DEFAULT_HOST = "broker.hivemq.com";
    public static final int DEFAULT_PORT = 1883;

    public SessionConfig(String sessionId, String brokerHost, int brokerPort) {
        this.sessionId = sessionId;
        this.brokerHost = brokerHost;
        this.brokerPort = brokerPort;
    }

    public String getSessionId() { return sessionId; }
    public String getBrokerHost() { return brokerHost; }
    public int getBrokerPort() { return brokerPort; }

    public String getChatTopic() {
        return sessionId + "/fiesta/chat";
    }

    public String getStatusTopic(String deviceId) {
        return sessionId + "/fiesta/device/" + deviceId + "/status";
    }
}
