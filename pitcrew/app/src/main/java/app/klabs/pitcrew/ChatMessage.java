package app.klabs.pitcrew;

public class ChatMessage {
    private final String from;
    private final String text;

    public ChatMessage(String from, String text) {
        this.from = from;
        this.text = text;
    }

    public String getFrom() { return from; }
    public String getText() { return text; }
}
