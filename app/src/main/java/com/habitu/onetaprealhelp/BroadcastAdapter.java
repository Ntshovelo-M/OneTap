package com.habitu.onetaprealhelp;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class BroadcastAdapter extends RecyclerView.Adapter<BroadcastAdapter.BroadcastViewHolder> {

    private final List<BroadcastItem> items = new ArrayList<>();

    public void setItems(List<BroadcastItem> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public BroadcastViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_broadcast_alert, parent, false);
        return new BroadcastViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull BroadcastViewHolder holder, int position) {
        BroadcastItem item = items.get(position);
        holder.tvMessage.setText(item.getMessage());
        holder.tvTime.setText(formatRelativeTime(item.getTimestamp()));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    private String formatRelativeTime(long timestamp) {
        long diffMillis = System.currentTimeMillis() - timestamp;
        long minutes = diffMillis / (60 * 1000);
        long hours = minutes / 60;
        long days = hours / 24;

        if (minutes < 1) return "Just now";
        if (minutes < 60) return minutes + (minutes == 1 ? " minute ago" : " minutes ago");
        if (hours < 24) return hours + (hours == 1 ? " hour ago" : " hours ago");
        return days + (days == 1 ? " day ago" : " days ago");
    }

    static class BroadcastViewHolder extends RecyclerView.ViewHolder {
        TextView tvMessage;
        TextView tvTime;

        BroadcastViewHolder(@NonNull View itemView) {
            super(itemView);
            tvMessage = itemView.findViewById(R.id.tv_alert_message);
            tvTime = itemView.findViewById(R.id.tv_alert_time);
        }
    }
}