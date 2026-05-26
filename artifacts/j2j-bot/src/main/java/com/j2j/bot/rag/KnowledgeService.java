package com.j2j.bot.rag;

import com.j2j.bot.config.AppConfig;
import com.j2j.bot.db.LibraryMetadataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
@RequiredArgsConstructor
public class KnowledgeService {

    private static final int CHUNK_SIZE = 500;
    private static final int CHUNK_OVERLAP = 50;

    private final AppConfig config;
    private final VectorStore vectorStore;
    private final EmbeddingService embeddingService;
    private final LibraryMetadataService metadataService;

    public void initStore() {
        vectorStore.load();
    }

    public boolean isIndexed() {
        return vectorStore.size() > 0;
    }

    public int getChunkCount() {
        return vectorStore.size();
    }

    /**
     * Full re-indexing of all PDF files in KNOWLEDGE_ROOT.
     * Returns a status message.
     */
    public String reloadKnowledge() {
        log.info("Starting full knowledge base re-indexing from: {}", config.getKnowledgeRoot());
        vectorStore.clear();

        File root = new File(config.getKnowledgeRoot());
        if (!root.exists() || !root.isDirectory()) {
            return "❌ Папка базы знаний не найдена: " + config.getKnowledgeRoot();
        }

        List<File> pdfFiles = findPdfFiles(root);
        if (pdfFiles.isEmpty()) {
            return "⚠️ PDF-файлы не найдены в папке: " + config.getKnowledgeRoot();
        }

        AtomicInteger chunkCount = new AtomicInteger(0);
        AtomicInteger bookCount = new AtomicInteger(0);
        List<String> errors = new ArrayList<>();

        for (File pdf : pdfFiles) {
            try {
                log.info("Indexing: {}", pdf.getName());
                int chunks = indexPdf(pdf);
                chunkCount.addAndGet(chunks);
                bookCount.incrementAndGet();
                log.info("  → {} chunks indexed from {}", chunks, pdf.getName());
            } catch (Exception e) {
                log.error("Failed to index {}: {}", pdf.getName(), e.getMessage());
                errors.add("• " + pdf.getName() + ": " + e.getMessage());
            }
        }

        vectorStore.save();
        metadataService.updateIndexingResult(bookCount.get(), chunkCount.get());

        StringBuilder result = new StringBuilder();
        result.append(String.format("✅ Индексация завершена:\n• Книг: %d\n• Чанков: %d",
                bookCount.get(), chunkCount.get()));
        if (!errors.isEmpty()) {
            result.append("\n\n⚠️ Ошибки при индексации:\n").append(String.join("\n", errors));
        }
        return result.toString();
    }

    private int indexPdf(File pdf) throws Exception {
        String title = extractTitle(pdf.getName());
        String author = "";

        try (PDDocument document = Loader.loadPDF(pdf)) {
            PDDocumentInformation info = document.getDocumentInformation();
            if (info != null) {
                if (info.getTitle() != null && !info.getTitle().isBlank()) {
                    title = info.getTitle();
                }
                if (info.getAuthor() != null && !info.getAuthor().isBlank()) {
                    author = info.getAuthor();
                }
            }

            PDFTextStripper stripper = new PDFTextStripper();
            int numPages = document.getNumberOfPages();
            List<String> chunks = new ArrayList<>();
            StringBuilder pageBuffer = new StringBuilder();

            for (int page = 1; page <= numPages; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = stripper.getText(document).trim();
                if (!pageText.isBlank()) {
                    pageBuffer.append(pageText).append("\n");
                }
                // Chunk when buffer gets large enough
                if (pageBuffer.length() > CHUNK_SIZE * 3 || page == numPages) {
                    chunks.addAll(splitIntoChunks(pageBuffer.toString()));
                    pageBuffer.setLength(0);
                }
            }

            int count = 0;
            for (int i = 0; i < chunks.size(); i++) {
                String chunk = chunks.get(i).trim();
                if (chunk.isBlank()) continue;

                float[] embedding = embeddingService.embed(chunk);
                String id = pdf.getName() + "_chunk_" + i;
                vectorStore.add(id, embedding, chunk, title, author, "", 0);
                count++;
            }
            return count;
        }
    }

    private List<String> splitIntoChunks(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) return chunks;

        String[] words = text.split("\\s+");
        StringBuilder current = new StringBuilder();

        for (String word : words) {
            current.append(word).append(" ");
            if (current.length() >= CHUNK_SIZE) {
                chunks.add(current.toString().trim());
                // Overlap: keep last ~CHUNK_OVERLAP chars
                String overlap = current.toString();
                if (overlap.length() > CHUNK_OVERLAP) {
                    overlap = overlap.substring(overlap.length() - CHUNK_OVERLAP);
                }
                current = new StringBuilder(overlap).append(" ");
            }
        }
        if (!current.toString().isBlank()) {
            chunks.add(current.toString().trim());
        }
        return chunks;
    }

    private List<File> findPdfFiles(File dir) {
        List<File> result = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files == null) return result;
        for (File f : files) {
            if (f.isDirectory()) {
                result.addAll(findPdfFiles(f));
            } else if (f.getName().toLowerCase().endsWith(".pdf")) {
                result.add(f);
            }
        }
        return result;
    }

    private String extractTitle(String filename) {
        String name = filename.replaceAll("\\.pdf$", "").replaceAll("[_\\-]", " ");
        return name.length() > 1
                ? Character.toUpperCase(name.charAt(0)) + name.substring(1)
                : name;
    }

    /**
     * Clears the entire knowledge base.
     */
    public void clearKnowledge() {
        vectorStore.clear();
        metadataService.updateIndexingResult(0, 0);
    }

    /**
     * Searches for the most relevant chunks for the given question.
     */
    public List<VectorStore.SearchResult> search(String question) {
        float[] queryEmbedding = embeddingService.embed(question);
        return vectorStore.search(queryEmbedding, config.getRagMaxResults(), config.getRagMinScore());
    }
}
