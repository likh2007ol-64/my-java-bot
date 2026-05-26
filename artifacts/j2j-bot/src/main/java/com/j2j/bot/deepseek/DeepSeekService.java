package com.j2j.bot.deepseek;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.j2j.bot.config.AppConfig;
import com.j2j.bot.rag.VectorStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class DeepSeekService {

    private final AppConfig config;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String NOT_FOUND_MARKER = "не могу найти точный ответ";

    /**
     * Generates an answer using DeepSeek API based on retrieved chunks.
     * Returns null if API is unavailable.
     */
    public String generateAnswer(String question, List<VectorStore.SearchResult> chunks) {
        if (config.getDeepseekApiKey().isBlank()) {
            return "⚠️ DEEPSEEK_API_KEY не задан. Установите переменную окружения.";
        }

        String chunksText = buildChunksText(chunks);
        String prompt = buildPrompt(question, chunksText);

        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", config.getDeepseekModel());
            requestBody.put("temperature", config.getDeepseekTemperature());
            requestBody.put("max_tokens", 2000);

            ArrayNode messages = objectMapper.createArrayNode();

            ObjectNode systemMsg = objectMapper.createObjectNode();
            systemMsg.put("role", "system");
            systemMsg.put("content", """
                    Ты — учебный ассистент для студентов, изучающих Java.
                    Отвечай только на основе предоставленных фрагментов учебных материалов.
                    Если ответа нет во фрагментах, честно скажи об этом.
                    Отвечай на русском языке. Никогда не используй свои знания, только предоставленные фрагменты.
                    """);
            messages.add(systemMsg);

            ObjectNode userMsg = objectMapper.createObjectNode();
            userMsg.put("role", "user");
            userMsg.put("content", prompt);
            messages.add(userMsg);

            requestBody.set("messages", messages);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(config.getDeepseekApiKey());

            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(requestBody), headers);

            ResponseEntity<String> response = restTemplate.postForEntity(
                    config.getDeepseekApiUrl(), entity, String.class);

            JsonNode responseJson = objectMapper.readTree(response.getBody());

            if (responseJson.has("choices") && !responseJson.get("choices").isEmpty()) {
                return responseJson.get("choices").get(0).get("message").get("content").asText();
            }

            log.error("Unexpected DeepSeek response: {}", response.getBody());
            return null;

        } catch (Exception e) {
            log.error("DeepSeek API call failed: {}", e.getMessage(), e);
            return null;
        }
    }

    private String buildChunksText(List<VectorStore.SearchResult> results) {
        if (results.isEmpty()) return "(фрагменты не найдены)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            VectorStore.EmbeddingEntry entry = results.get(i).entry();
            sb.append("--- Фрагмент ").append(i + 1).append(" ---\n");
            sb.append("Источник: ").append(entry.bookTitle());
            if (entry.author() != null && !entry.author().isBlank()) {
                sb.append(" / ").append(entry.author());
            }
            if (entry.chapter() != null && !entry.chapter().isBlank()) {
                sb.append(", глава: ").append(entry.chapter());
            }
            sb.append("\n");
            sb.append(entry.text()).append("\n\n");
        }
        return sb.toString();
    }

    private String buildPrompt(String question, String chunksText) {
        return String.format("""
                Ответь на русском языке, основываясь строго на предоставленных фрагментах.
                Если ответа нет во фрагментах, скажи "Я не могу найти точный ответ на этот вопрос в нашей учебной базе. Попробуйте переформулировать запрос".
                Не используй свои знания.
                Укажи источник(и) информации: название книги, автор, главу (если есть).
                
                Вопрос: %s
                
                Фрагменты:
                %s""", question, chunksText);
    }

    /**
     * Returns true if the generated answer indicates the info was not found.
     */
    public boolean isNotFoundAnswer(String answer) {
        if (answer == null) return false;
        return answer.toLowerCase().contains(NOT_FOUND_MARKER);
    }
}
