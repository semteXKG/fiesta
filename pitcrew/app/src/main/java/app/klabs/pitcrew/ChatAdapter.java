package app.klabs.pitcrew;

import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.ViewHolder> {

    private static final int TYPE_SENT = 0;
    private static final int TYPE_RECEIVED = 1;

    private final List<ChatMessage> messages = new ArrayList<>();
    private final String ownDeviceId;

    public ChatAdapter(String ownDeviceId) {
        this.ownDeviceId = ownDeviceId;
    }

    public void addMessage(ChatMessage message) {
        messages.add(message);
        notifyItemInserted(messages.size() - 1);
    }

    public void setMessages(List<ChatMessage> msgs) {
        messages.clear();
        messages.addAll(msgs);
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return messages.get(position).getFrom().equals(ownDeviceId) ? TYPE_SENT : TYPE_RECEIVED;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_chat_message, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ChatMessage msg = messages.get(position);
        boolean isSent = getItemViewType(position) == TYPE_SENT;

        holder.textFrom.setText(msg.getFrom());
        holder.textMessage.setText(msg.getText());

        // Align sent messages right, received left
        holder.bubbleContainer.setGravity(isSent ? Gravity.END : Gravity.START);
        holder.textFrom.setGravity(isSent ? Gravity.END : Gravity.START);
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final LinearLayout bubbleContainer;
        final TextView textFrom;
        final TextView textMessage;

        ViewHolder(View itemView) {
            super(itemView);
            bubbleContainer = itemView.findViewById(R.id.bubbleContainer);
            textFrom = itemView.findViewById(R.id.textFrom);
            textMessage = itemView.findViewById(R.id.textMessage);
        }
    }
}
