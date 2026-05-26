package com.j2j.bot;

import com.j2j.bot.db.LibraryMetadataService;
import com.j2j.bot.image.ImageUploadService;
import com.j2j.bot.rag.KnowledgeService;
import com.j2j.bot.vk.VkLongPollingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
@Slf4j
@RequiredArgsConstructor
public class J2JBotApplication implements CommandLineRunner {

    private final LibraryMetadataService libraryMetadataService;
    private final ImageUploadService imageUploadService;
    private final KnowledgeService knowledgeService;
    private final VkLongPollingService vkLongPollingService;

    public static void main(String[] args) {
        SpringApplication.run(J2JBotApplication.class, args);
    }

    @Override
    public void run(String... args) {
        log.info("=== J2J Bot starting (Phase 1: RAG + Library Management) ===");

        libraryMetadataService.init();
        log.info("Database initialized.");

        knowledgeService.initStore();
        log.info("Vector store initialized. Indexed chunks: {}", knowledgeService.getChunkCount());

        imageUploadService.uploadImages();

        System.out.println();
        System.out.println("✅ Фаза 1 бота J2J_Bot завершена.");
        System.out.println("   - Реализованы: ответы на теорию через RAG (VectorStore + AllMiniLM + DeepSeek), персонаж, управление библиотекой.");
        System.out.println("   - Команды /run и /explain отключены (заглушка).");
        System.out.println("   - Заполните секреты: VK_TOKEN, DEEPSEEK_API_KEY, ADMIN_IDS.");
        System.out.println("   - Замените файлы в static/ на реальные PNG.");
        System.out.println("   - Запустите приложение и протестируйте.");
        System.out.println();

        vkLongPollingService.startPolling();
    }
}
