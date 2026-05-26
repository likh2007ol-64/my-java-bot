package com.j2j.bot.image;

import com.j2j.bot.config.AppConfig;
import com.j2j.bot.vk.VkApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class ImageUploadService {

    private final AppConfig config;
    private final VkApiClient vkApiClient;

    public static final String IMG_GREETING = "greeting";
    public static final String IMG_THINKING = "thinking";
    public static final String IMG_HAPPY = "happy";
    public static final String IMG_SAD = "sad";

    private final Map<String, String> attachments = new HashMap<>();

    // Fallback emojis when image upload fails
    private static final Map<String, String> FALLBACK_EMOJI = Map.of(
            IMG_GREETING, "🤖",
            IMG_THINKING, "🤔",
            IMG_HAPPY, "✅",
            IMG_SAD, "😕"
    );

    private static final Map<String, String> FILE_NAMES = Map.of(
            IMG_GREETING, "robot_greeting.png",
            IMG_THINKING, "robot_thinking.png",
            IMG_HAPPY, "robot_happy.png",
            IMG_SAD, "robot_sad.png"
    );

    public void uploadImages() {
        if (config.getVkToken().isBlank()) {
            log.warn("VK_TOKEN not set — skipping image upload. Images will use emoji fallback.");
            return;
        }

        for (Map.Entry<String, String> entry : FILE_NAMES.entrySet()) {
            String key = entry.getKey();
            String fileName = entry.getValue();
            File file = new File(config.getStaticDir(), fileName);

            if (!file.exists() || file.length() < 67) {
                log.warn("Image file is missing or placeholder (< 67 bytes): {} — using emoji fallback.", file.getAbsolutePath());
                continue;
            }

            String attachment = vkApiClient.uploadPhoto(file);
            if (attachment != null) {
                attachments.put(key, attachment);
                log.info("Uploaded image {}: {}", key, attachment);
            } else {
                log.warn("Failed to upload image {} — will use emoji fallback.", key);
            }
        }

        if (attachments.isEmpty()) {
            log.warn("No images uploaded — all messages will use emoji fallback instead of robot images.");
        } else {
            log.info("Successfully uploaded {}/{} images.", attachments.size(), FILE_NAMES.size());
        }
    }

    /**
     * Returns the VK attachment string for the given image key, or null if not uploaded.
     */
    public String getAttachment(String imageKey) {
        return attachments.get(imageKey);
    }

    /**
     * Returns the fallback emoji for the given image key.
     */
    public String getFallbackEmoji(String imageKey) {
        return FALLBACK_EMOJI.getOrDefault(imageKey, "");
    }

    /**
     * Returns true if the image has been successfully uploaded to VK.
     */
    public boolean hasImage(String imageKey) {
        return attachments.containsKey(imageKey);
    }
}
