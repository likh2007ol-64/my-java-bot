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

    /** GET-based API call — for most methods */
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

    /**
     * POST-based API call with application/x-www-form-urlencoded body.
     * Required for methods that accept large/complex string parameters
     * (e.g. photos.saveMessagesPhoto where 'photo' is an encoded JSON blob).
     */
    public JsonNode callMethodPost(String method, Map<String, String> params) {
        try {
            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("access_token", config.getVkToken());
            body.add("v", VK_API_VERSION);
            params.forEach(body::add);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(body, headers);

            ResponseEntity<String> resp = restTemplate.postForEntity(
                    VK_API_BASE + method, entity, String.class);
            return objectMapper.readTree(resp.getBody());
        } catch (Exception e) {
            log.error("VK API POST call failed: {} — {}", method, e.getMessage());
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
     * Retries up to 3 times to handle transient VK upload server errors (504, empty photo).
     *
     * VK flow:
     *   1. photos.getMessagesUploadServer  → upload URL
     *   2. multipart POST to upload URL   → {server, photo, hash}
     *   3. photos.saveMessagesPhoto (POST) → photo object
     */
    public String uploadPhoto(File photoFile) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            String result = tryUploadPhoto(photoFile, attempt);
            if (result != null) return result;
            if (attempt < 3) {
                try { Thread.sleep(2500); } catch (InterruptedException ignored) {}
                log.info("[{}] Retrying upload (attempt {}/3)...", photoFile.getName(), attempt + 1);
            }
        }
        log.warn("[{}] All 3 upload attempts failed.", photoFile.getName());
        return null;
    }

    private String tryUploadPhoto(File photoFile, int attempt) {
        try {
            // ── Step 1: get upload URL ──────────────────────────────────────
            JsonNode serverResp = callMethod("photos.getMessagesUploadServer",
                    Map.of("peer_id", "0"));
            if (serverResp == null) {
                log.warn("[{}] getMessagesUploadServer returned null (network?)", photoFile.getName());
                return null;
            }
            if (serverResp.has("error")) {
                int code = serverResp.get("error").path("error_code").asInt();
                String msg  = serverResp.get("error").path("error_msg").asText();
                log.warn("[{}] VK error getMessagesUploadServer: code={} msg='{}'. " +
                         "Ensure VK_TOKEN has 'photos' permission (regenerate with photos scope).",
                        photoFile.getName(), code, msg);
                return null;
            }
            String uploadUrl = serverResp.get("response").get("upload_url").asText();
            log.info("[{}] Upload URL obtained, uploading {} bytes...",
                    photoFile.getName(), photoFile.length());

            // ── Step 2: multipart POST to VK upload server ──────────────────
            // Explicitly set image/png content-type on the file part
            HttpHeaders filePartHeaders = new HttpHeaders();
            filePartHeaders.setContentType(MediaType.IMAGE_PNG);
            HttpEntity<FileSystemResource> filePart =
                    new HttpEntity<>(new FileSystemResource(photoFile), filePartHeaders);

            MultiValueMap<String, Object> multipart = new LinkedMultiValueMap<>();
            multipart.add("photo", filePart);

            HttpHeaders reqHeaders = new HttpHeaders();
            reqHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
            HttpEntity<MultiValueMap<String, Object>> req = new HttpEntity<>(multipart, reqHeaders);

            ResponseEntity<String> uploadResp = restTemplate.postForEntity(uploadUrl, req, String.class);
            String rawUpload = uploadResp.getBody();
            log.info("[{}] Upload server response: {}", photoFile.getName(), rawUpload);

            JsonNode uploadResult = objectMapper.readTree(rawUpload);
            String photoField = uploadResult.has("photo")
                    ? uploadResult.get("photo").asText("") : "";

            if (photoField.isBlank() || photoField.equals("[]")) {
                log.warn("[{}] Upload returned empty photo field — VK rejected the file (wrong format?). Full response: {}",
                        photoFile.getName(), rawUpload);
                return null;
            }

            // ── Step 3: save photo via POST (photo field is large JSON blob) ─
            String server = uploadResult.has("server")
                    ? uploadResult.get("server").asText() : "0";
            String hash   = uploadResult.has("hash")
                    ? uploadResult.get("hash").asText() : "";

            log.info("[{}] Saving photo (server={}, hash={}, photo_len={})...",
                    photoFile.getName(), server, hash, photoField.length());

            Map<String, String> saveParams = new HashMap<>();
            saveParams.put("server", server);
            saveParams.put("photo",  photoField);   // raw string — must go via POST body
            saveParams.put("hash",   hash);

            JsonNode saveResp = callMethodPost("photos.saveMessagesPhoto", saveParams);

            if (saveResp == null) {
                log.warn("[{}] saveMessagesPhoto returned null", photoFile.getName());
                return null;
            }
            if (saveResp.has("error")) {
                int code = saveResp.get("error").path("error_code").asInt();
                String msg  = saveResp.get("error").path("error_msg").asText();
                log.warn("[{}] VK error saveMessagesPhoto: code={} msg='{}'",
                        photoFile.getName(), code, msg);
                return null;
            }
            if (saveResp.has("response") && saveResp.get("response").size() > 0) {
                JsonNode photo = saveResp.get("response").get(0);
                long ownerId = photo.get("owner_id").asLong();
                long photoId = photo.get("id").asLong();
                String attachment = "photo" + ownerId + "_" + photoId;
                log.info("[{}] ✅ Photo saved successfully: {}", photoFile.getName(), attachment);
                return attachment;
            }
            log.warn("[{}] saveMessagesPhoto response has no data: {}",
                    photoFile.getName(), saveResp);
        } catch (Exception e) {
            log.warn("[{}] Photo upload exception: {}", photoFile.getName(), e.getMessage(), e);
        }
        return null;
    }
}
