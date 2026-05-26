package com.j2j.bot.vk;

import com.fasterxml.jackson.databind.JsonNode;
import com.j2j.bot.handler.MessageHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Slf4j
@RequiredArgsConstructor
public class VkLongPollingService {

    private final VkApiClient vkApiClient;
    private final MessageHandler messageHandler;

    private final AtomicBoolean running = new AtomicBoolean(false);

    public void startPolling() {
        if (running.getAndSet(true)) {
            log.warn("Long polling already running");
            return;
        }

        Thread pollingThread = new Thread(this::pollLoop, "vk-long-poll");
        pollingThread.setDaemon(true);
        pollingThread.start();
        log.info("VK Long Polling started.");
    }

    private void pollLoop() {
        long groupId = vkApiClient.getGroupId();
        if (groupId == 0) {
            log.error("Cannot start long polling: group ID not available. Check VK_TOKEN.");
            return;
        }
        log.info("Bot group ID: {}", groupId);

        String server;
        String key;
        String ts;

        try {
            JsonNode pollServer = vkApiClient.getLongPollServer(groupId);
            if (pollServer == null || !pollServer.has("response")) {
                log.error("Cannot get Long Poll server. Check VK_TOKEN and group settings.");
                return;
            }
            JsonNode resp = pollServer.get("response");
            server = resp.get("server").asText();
            key = resp.get("key").asText();
            ts = resp.get("ts").asText();
        } catch (Exception e) {
            log.error("Failed to initialize long polling: {}", e.getMessage());
            return;
        }

        log.info("Long poll server: {}", server);

        while (running.get()) {
            try {
                JsonNode updates = vkApiClient.getLongPollUpdates(server, key, ts);
                if (updates == null) {
                    Thread.sleep(3000);
                    continue;
                }

                if (updates.has("failed")) {
                    int failed = updates.get("failed").asInt();
                    log.warn("Long poll failed code: {}", failed);
                    if (failed == 1) {
                        ts = updates.get("ts").asText();
                    } else {
                        // Reconnect
                        JsonNode newServer = vkApiClient.getLongPollServer(groupId);
                        if (newServer != null && newServer.has("response")) {
                            JsonNode r = newServer.get("response");
                            server = r.get("server").asText();
                            key = r.get("key").asText();
                            ts = r.get("ts").asText();
                        }
                    }
                    continue;
                }

                ts = updates.get("ts").asText();

                if (updates.has("updates")) {
                    for (JsonNode update : updates.get("updates")) {
                        processUpdate(update);
                    }
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error in polling loop: {}", e.getMessage(), e);
                try { Thread.sleep(5000); } catch (InterruptedException ie) { break; }
            }
        }
        log.info("Long polling stopped.");
    }

    private void processUpdate(JsonNode update) {
        try {
            String type = update.get("type").asText();
            if (!"message_new".equals(type)) return;

            JsonNode obj = update.get("object");
            if (obj == null) return;

            JsonNode message = obj.has("message") ? obj.get("message") : obj;
            if (message == null) return;

            long peerId = message.get("peer_id").asLong();
            long fromId = message.has("from_id") ? message.get("from_id").asLong() : peerId;
            String text = message.has("text") ? message.get("text").asText("") : "";

            if (text.isBlank()) return;

            messageHandler.handleMessage(peerId, fromId, text);
        } catch (Exception e) {
            log.error("Error processing update: {}", e.getMessage(), e);
        }
    }

    public void stop() {
        running.set(false);
    }
}
