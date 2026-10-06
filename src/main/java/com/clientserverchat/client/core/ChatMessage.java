package com.clientserverchat.client.core;

import java.time.Instant;

/** UI-independent event, so the view never parses protocol text or log messages. */
public record ChatMessage(String conversation, String sender, String content, Instant time, boolean outgoing, boolean file) {
    public static final String GROUP = "#multicast";
}
