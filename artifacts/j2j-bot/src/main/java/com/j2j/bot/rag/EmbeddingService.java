package com.j2j.bot.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

@Service
@Slf4j
public class EmbeddingService {

    private EmbeddingModel embeddingModel;

    @PostConstruct
    public void init() {
        log.info("Loading AllMiniLmL6V2 embedding model (multilingual)...");
        try {
            embeddingModel = new AllMiniLmL6V2EmbeddingModel();
            log.info("Embedding model loaded successfully.");
        } catch (Exception e) {
            log.error("Failed to load embedding model: {}", e.getMessage(), e);
        }
    }

    /**
     * Generates an embedding vector for the given text.
     */
    public float[] embed(String text) {
        if (embeddingModel == null) {
            log.warn("Embedding model not available, returning zero vector");
            return new float[384];
        }
        try {
            Embedding embedding = embeddingModel.embed(text).content();
            return embedding.vector();
        } catch (Exception e) {
            log.error("Embedding failed for text: {}", e.getMessage());
            return new float[384];
        }
    }

    public boolean isAvailable() {
        return embeddingModel != null;
    }
}
