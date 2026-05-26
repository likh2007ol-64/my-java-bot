package com.j2j.bot.vk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.j2j.bot.config.AppConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.File;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

@Component
@Slf4j
@RequiredArgsConstructor
public class VkApiClient {

    private static final String VK_API_BASE = "https://api.vk.com/method/";
    private static final String VK_API_VERSION = "5.131";

    private final AppConfig config;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Random random = new Random();

    public JsonNode callMethod(String method, Map<String, String> params) {
        try {
            UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(VK_API_BASE + method)
                    .queryParam("access_token", config.getVkToken())
                    .queryParam("v", VK_API_VERSION);
            params.forEach(builder::queryParam);

            String response = restTemplate.getForObject(builder.toUriString(), String.class);
            return objectMapper.readTree(response);
        } catch (Exception e) {
            log.error("VK API call failed: {} — {}", method, e.getMessage());
            return null;
        }
    }

    public void sendMessage(long peerId, String text) {
        sendMessage(peerId, text, null);
    }

    public void sendMessage(long peerId, String text, String attachment) {
        Map<String, String> params = new HashMap<>();
        params.put("peer_id", String.valueOf(peerId));
        params.put("message", text);
        params.put("random_id", String.valueOf(random.nextInt(Integer.MAX_VALUE)));
        if (attachment != null && !attachment.isBlank()) {
            params.put("attachment", attachment);
        }
        JsonNode resp = callMethod("messages.send", params);
        if (resp != null && resp.has("error")) {
            log.error("messages.send error: {}", resp.get("error"));
        }
    }

    public void sendMessageWithKeyboard(long peerId, String text, String keyboardJson) {
        Map<String, String> params = new HashMap<>();
        params.put("peer_id", String.valueOf(peerId));
        params.put("message", text);
        params.put("random_id", String.valueOf(random.nextInt(Integer.MAX_VALUE)));
        params.put("keyboard", keyboardJson);
        JsonNode resp = callMethod("messages.send", params);
        if (resp != null && resp.has("error")) {
            log.error("messages.send (keyboard) error: {}", resp.get("error"));
        }
    }

    public JsonNode getLongPollServer(long groupId) {
        Map<String, String> params = new HashMap<>();
        params.put("group_id", String.valueOf(groupId));
        return callMethod("groups.getLongPollServer", params);
    }

    public JsonNode getLongPollUpdates(String server, String key, String ts) {
        try {
            String url = server + "?act=a_check&key=" + key + "&ts=" + ts + "&wait=25";
            String response = restTemplate.getForObject(url, String.class);
            return objectMapper.readTree(response);
        } catch (Exception e) {
            log.error("Long poll request failed: {}", e.getMessage());
            return null;
        }
    }

    public long getGroupId() {
        JsonNode resp = callMethod("groups.getById", Map.of());
        if (resp != null && resp.has("response")) {
            JsonNode groups = resp.get("response");
            if (groups.isArray() && !groups.isEmpty()) {
                return groups.get(0).get("id").asLong();
            }
        }
        log.error("Could not get group ID from VK API");
        return 0;
    }

    /**
     * Upload a photo for use as a message attachment.
     * Returns attachment string like "photo{owner_id}_{photo_id}" or null on failure.
     */
    public String uploadPhoto(File photoFile) {
        try {
            JsonNode serverResp = callMethod("photos.getMessagesUploadServer", Map.of("peer_id", "0"));
            if (serverResp == null || !serverResp.has("response")) {
                log.warn("Could not get photo upload server");
                return null;
            }
            String uploadUrl = serverResp.get("response").get("upload_url").asText();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("photo", new FileSystemResource(photoFile));
            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

            ResponseEntity<String> uploadResp = restTemplate.postForEntity(uploadUrl, requestEntity, String.class);
            JsonNode uploadResult = objectMapper.readTree(uploadResp.getBody());

            Map<String, String> saveParams = new HashMap<>();
            saveParams.put("server", uploadResult.get("server").asText());
            saveParams.put("photo", uploadResult.get("photo").asText());
            saveParams.put("hash", uploadResult.get("hash").asText());
            JsonNode saveResp = callMethod("photos.saveMessagesPhoto", saveParams);

            if (saveResp != null && saveResp.has("response")) {
                JsonNode photo = saveResp.get("response").get(0);
                long ownerId = photo.get("owner_id").asLong();
                long photoId = photo.get("id").asLong();
                return "photo" + ownerId + "_" + photoId;
            }
        } catch (Exception e) {
            log.warn("Photo upload failed for {}: {}", photoFile.getName(), e.getMessage());
        }
        return null;
    }
}
