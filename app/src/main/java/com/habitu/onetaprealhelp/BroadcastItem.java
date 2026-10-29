package com.habitu.onetaprealhelp;

public class BroadcastItem {

    private String message;
    private long timestamp;

    public BroadcastItem() {
        // Required for Firebase deserialization
    }

    public BroadcastItem(String message, long timestamp) {
        this.message = message;
        this.timestamp = timestamp;
    }

    public String getMessage() {
        return message;
    }

    public long getTimestamp() {
        return timestamp;
    }
}