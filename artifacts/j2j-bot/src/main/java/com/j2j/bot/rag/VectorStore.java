package com.j2j.bot.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.j2j.bot.config.AppConfig;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.*;

/**
 * Simple file-backed vector store using cosine similarity.
 * Stores embedding entries as JSON on disk for persistence between restarts.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class VectorStore {

    private final AppConfig config;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final List<EmbeddingEntry> entries = new ArrayList<>();

    @Getter
    private boolean loaded = false;

    public synchronized void load() {
        File storeFile = new File(config.getVectorStorePath());
        if (!storeFile.exists()) {
            log.info("No vector store found at {}. Starting empty.", storeFile.getAbsolutePath());
            loaded = true;
            return;
        }
        try {
            List<EmbeddingEntry> loaded = objectMapper.readValue(storeFile,
                    new TypeReference<List<EmbeddingEntry>>() {});
            entries.clear();
            entries.addAll(loaded);
            log.info("Loaded {} entries from vector store.", entries.size());
        } catch (Exception e) {
            log.error("Failed to load vector store: {}", e.getMessage(), e);
        }
        loaded = true;
    }

    public synchronized void save() {
        try {
            File storeFile = new File(config.getVectorStorePath());
            storeFile.getParentFile().mkdirs();
            objectMapper.writeValue(storeFile, entries);
            log.info("Saved {} entries to vector store.", entries.size());
        } catch (Exception e) {
            log.error("Failed to save vector store: {}", e.getMessage(), e);
        }
    }

    public synchronized void clear() {
        entries.clear();
        save();
        log.info("Vector store cleared.");
    }

    public synchronized void add(String id, float[] embedding, String text, String bookTitle, String author, String chapter, int page) {
        entries.removeIf(e -> e.id().equals(id));
        entries.add(new EmbeddingEntry(id, embedding, text, bookTitle, author, chapter, page));
    }

    public synchronized int size() {
        return entries.size();
    }

    /**
     * Search for the top-k most similar entries using cosine similarity.
     */
    public synchronized List<SearchResult> search(float[] queryEmbedding, int topK, double minScore) {
        List<SearchResult> results = new ArrayList<>();
        for (EmbeddingEntry entry : entries) {
            double score = cosineSimilarity(queryEmbedding, entry.embedding());
            if (score >= minScore) {
                results.add(new SearchResult(entry, score));
            }
        }
        results.sort((a, b) -> Double.compare(b.score(), a.score()));
        return results.subList(0, Math.min(topK, results.size()));
    }

    private double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return 0.0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0.0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    public record EmbeddingEntry(
            String id,
            float[] embedding,
            String text,
            String bookTitle,
            String author,
            String chapter,
            int page
    ) {}

    public record SearchResult(EmbeddingEntry entry, double score) {}
}
