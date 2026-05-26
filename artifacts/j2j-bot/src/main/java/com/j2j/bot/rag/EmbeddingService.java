package com.j2j.bot.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Pure-Java embedding service using a TF-IDF inspired hashing approach.
 * No native libraries required (no ONNX, no libstdc++).
 *
 * Strategy:
 * - Tokenise text into unigrams + bigrams.
 * - Hash each token into a fixed-size float vector (random projection hashing).
 * - L2-normalise the result.
 *
 * This is a lightweight baseline suitable for MVP. Quality is below
 * transformer models, but it works without external dependencies and
 * supports both Russian and English text without translation.
 *
 * For production quality, replace with an external embedding API call
 * (e.g., DeepSeek's embedding endpoint or a separate Python microservice).
 */
@Service
@Slf4j
public class EmbeddingService {

    private static final int DIM = 384;
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Set<String> STOP_WORDS_RU = Set.of(
            "и", "в", "не", "он", "на", "я", "что", "тот", "быть", "с",
            "а", "весь", "это", "как", "она", "по", "но", "они", "к", "у",
            "же", "вы", "за", "бы", "из", "от", "так"
    );
    private static final Set<String> STOP_WORDS_EN = Set.of(
            "the", "a", "an", "in", "on", "at", "to", "for", "of", "and",
            "or", "but", "is", "are", "was", "were", "be", "been", "being",
            "have", "has", "had", "do", "does", "did", "will", "would",
            "could", "should", "may", "might", "this", "that", "these", "those"
    );

    @PostConstruct
    public void init() {
        log.info("Pure-Java embedding service ready (dim={}, no native libs required).", DIM);
        // Warm up SHA-256
        try {
            MessageDigest.getInstance("SHA-256");
            log.info("Embedding service warm-up OK.");
        } catch (Exception e) {
            log.error("SHA-256 not available: {}", e.getMessage());
        }
    }

    /**
     * Embed text into a float vector of dimension DIM using random-projection hashing.
     */
    public float[] embed(String text) {
        if (text == null || text.isBlank()) return new float[DIM];

        List<String> tokens = tokenise(text.toLowerCase());
        if (tokens.isEmpty()) return new float[DIM];

        float[] vec = new float[DIM];

        // Unigrams
        for (String token : tokens) {
            addTokenToVec(vec, token, 1.0f);
        }

        // Bigrams (improve phrase-level similarity)
        for (int i = 0; i < tokens.size() - 1; i++) {
            addTokenToVec(vec, tokens.get(i) + "_" + tokens.get(i + 1), 0.7f);
        }

        return l2Normalize(vec);
    }

    private void addTokenToVec(float[] vec, String token, float weight) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            // Use hash bytes to set multiple dimensions
            for (int i = 0; i < DIM; i++) {
                int byteIdx = i % hash.length;
                int bitIdx = (i / hash.length) % 8;
                int bit = (hash[byteIdx] >> bitIdx) & 1;
                vec[i] += weight * (bit == 1 ? 1.0f : -1.0f);
            }
        } catch (Exception e) {
            // SHA-256 is always available
        }
    }

    private float[] l2Normalize(float[] vec) {
        double norm = 0.0;
        for (float v : vec) norm += (double) v * v;
        if (norm == 0.0) return vec;
        float scale = (float) (1.0 / Math.sqrt(norm));
        for (int i = 0; i < vec.length; i++) vec[i] *= scale;
        return vec;
    }

    private List<String> tokenise(String text) {
        List<String> tokens = new ArrayList<>();
        var matcher = TOKEN_PATTERN.matcher(text);
        while (matcher.find()) {
            String t = matcher.group();
            if (t.length() >= 2 && !STOP_WORDS_RU.contains(t) && !STOP_WORDS_EN.contains(t)) {
                tokens.add(t);
            }
        }
        return tokens;
    }

    public boolean isAvailable() {
        return true;
    }
}
