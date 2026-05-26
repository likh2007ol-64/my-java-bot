package com.j2j.bot.handler;

import com.j2j.bot.admin.AdminService;
import com.j2j.bot.deepseek.DeepSeekService;
import com.j2j.bot.image.ImageUploadService;
import com.j2j.bot.rag.KnowledgeService;
import com.j2j.bot.rag.VectorStore;
import com.j2j.bot.vk.VkApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class MessageHandler {

    private final VkApiClient vkApiClient;
    private final AdminService adminService;
    private final KnowledgeService knowledgeService;
    private final DeepSeekService deepSeekService;
    private final ImageUploadService imageUploadService;

    // Greeting text
    private static final String GREETING_TEXT = """
            Привет! Я Java-робот 🤖
            
            Помогаю студентам JUMP2JAVA с теорией.
            Спроси меня о Java — я отвечу на основе учебников.
            
            (Проверка кода появится позже)""";

    private static final String ABOUT_TEXT = """
            J2J_Bot — учебный ассистент для сообщества «Java-старт | Jump2Java»
            
            Версия: 1.0 — Теория (RAG)
            
            Функции:
            • Ответы на теоретические вопросы по Java на основе учебников
            • Многоязычный поиск (RU + EN)
            • Визуальный персонаж-робот
            
            Команды /run и /explain появятся в следующей версии.""";

    private static final String CODE_STUB_TEXT =
            "Функция проверки кода будет добавлена в следующей версии. " +
            "Пока я могу отвечать только на теоретические вопросы по Java.";

    public void handleMessage(long peerId, long fromId, String text) {
        String trimmed = text.strip();
        String lower = trimmed.toLowerCase();
        log.debug("Message from {} in chat {}: {}", fromId, peerId, trimmed);

        // Admin commands — check first
        if (adminService.isAdmin(fromId) && adminService.handleCommand(peerId, fromId, trimmed)) {
            return;
        }

        // Non-admin trying admin commands
        if (isAdminCommand(lower)) {
            vkApiClient.sendMessage(peerId, "У вас нет прав для выполнения этой команды.");
            return;
        }

        // /start or /help
        if (lower.equals("/start") || lower.equals("/help")) {
            sendWithImage(peerId, GREETING_TEXT, ImageUploadService.IMG_GREETING);
            return;
        }

        // /about
        if (lower.equals("/about")) {
            vkApiClient.sendMessage(peerId, ABOUT_TEXT);
            return;
        }

        // /run or /explain — disabled stubs
        if (lower.startsWith("/run") || lower.startsWith("/explain") ||
                lower.startsWith("```java") || lower.startsWith("```")) {
            sendWithImage(peerId, CODE_STUB_TEXT, ImageUploadService.IMG_SAD);
            return;
        }

        // Any other command starting with /
        if (trimmed.startsWith("/")) {
            vkApiClient.sendMessage(peerId, "Неизвестная команда. Напишите /help для справки.");
            return;
        }

        // Regular text — treat as a theory question
        handleTheoryQuestion(peerId, trimmed);
    }

    private void handleTheoryQuestion(long peerId, String question) {
        // Send thinking indicator
        sendWithImage(peerId, "Секунду, думаю... 🤔", ImageUploadService.IMG_THINKING);

        // Pause 1–2 seconds for UX feel
        try { Thread.sleep(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        // Check if knowledge base is indexed
        if (!knowledgeService.isIndexed()) {
            sendWithImage(peerId,
                    "База знаний ещё не загружена. Администратор должен выполнить /reload_knowledge.",
                    ImageUploadService.IMG_SAD);
            return;
        }

        // Search for relevant chunks
        List<VectorStore.SearchResult> results = knowledgeService.search(question);

        if (results.isEmpty()) {
            sendWithImage(peerId,
                    "Я не могу найти точный ответ на этот вопрос в нашей учебной базе. " +
                    "Попробуйте переформулировать запрос.",
                    ImageUploadService.IMG_SAD);
            return;
        }

        // Generate answer with DeepSeek
        String answer = deepSeekService.generateAnswer(question, results);

        if (answer == null) {
            sendWithImage(peerId,
                    "Сервис ответов временно недоступен, попробуйте позже.",
                    ImageUploadService.IMG_SAD);
            return;
        }

        // Determine if answer was found or not
        boolean notFound = deepSeekService.isNotFoundAnswer(answer);
        String imageKey = notFound ? ImageUploadService.IMG_SAD : ImageUploadService.IMG_HAPPY;

        // VK message limit is 4096 chars
        if (answer.length() > 4000) {
            // Split long answers
            int idx = 0;
            boolean first = true;
            while (idx < answer.length()) {
                int end = Math.min(idx + 4000, answer.length());
                String part = answer.substring(idx, end);
                if (first) {
                    sendWithImage(peerId, part, imageKey);
                    first = false;
                } else {
                    vkApiClient.sendMessage(peerId, part);
                }
                idx = end;
            }
        } else {
            sendWithImage(peerId, answer, imageKey);
        }
    }

    /**
     * Sends a message optionally with an image attachment.
     * Falls back to emoji prefix if image is not available.
     */
    private void sendWithImage(long peerId, String text, String imageKey) {
        if (imageUploadService.hasImage(imageKey)) {
            vkApiClient.sendMessage(peerId, text, imageUploadService.getAttachment(imageKey));
        } else {
            String emoji = imageUploadService.getFallbackEmoji(imageKey);
            vkApiClient.sendMessage(peerId, emoji.isBlank() ? text : emoji + " " + text);
        }
    }

    private boolean isAdminCommand(String lower) {
        return lower.equals("/admin") ||
               lower.equals("/reload_knowledge") ||
               lower.equals("/clear_knowledge") ||
               lower.startsWith("/list_files") ||
               lower.startsWith("/delete_file") ||
               lower.startsWith("/delete_folder") ||
               lower.equals("/status");
    }
}
