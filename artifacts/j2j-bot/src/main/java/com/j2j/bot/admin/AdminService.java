package com.j2j.bot.admin;

import com.j2j.bot.config.AppConfig;
import com.j2j.bot.db.LibraryMetadataService;
import com.j2j.bot.rag.KnowledgeService;
import com.j2j.bot.vk.VkApiClient;
import com.j2j.bot.vk.VkKeyboard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class AdminService {

    private final AppConfig config;
    private final VkApiClient vkApiClient;
    private final KnowledgeService knowledgeService;
    private final LibraryMetadataService metadataService;

    // Tracks pending confirmations: userId -> pending action
    private final Map<Long, PendingConfirmation> pendingConfirmations = new HashMap<>();

    public boolean isAdmin(long userId) {
        return config.getAdminIds().contains(String.valueOf(userId));
    }

    /**
     * Handles an admin command. Returns true if the message was handled as an admin command.
     */
    public boolean handleCommand(long peerId, long fromId, String text) {
        if (!isAdmin(fromId)) return false;

        String lower = text.strip();

        // Check for pending confirmation
        if (pendingConfirmations.containsKey(fromId)) {
            return handleConfirmation(peerId, fromId, lower);
        }

        if (lower.equals("/admin")) {
            sendAdminMenu(peerId);
            return true;
        }
        if (lower.equals("/reload_knowledge") || lower.equals("🔄 переиндексировать всё")) {
            handleReloadKnowledge(peerId);
            return true;
        }
        if (lower.equals("/clear_knowledge") || lower.equals("🗑 очистить базу")) {
            handleClearKnowledgeRequest(peerId, fromId);
            return true;
        }
        if (lower.startsWith("/list_files") || lower.equals("📂 показать файлы")) {
            String path = lower.startsWith("/list_files ")
                    ? lower.substring("/list_files".length()).trim()
                    : "";
            handleListFiles(peerId, path);
            return true;
        }
        if (lower.startsWith("/delete_file") || lower.equals("❌ удалить файл/папку")) {
            if (lower.equals("/delete_file") || lower.equals("❌ удалить файл/папку")) {
                vkApiClient.sendMessage(peerId, """
                        Укажите путь к файлу для удаления:
                        /delete_file <относительный_путь>
                        Например: /delete_file КнигаJava/chapter1.pdf""");
            } else {
                String filePath = lower.substring("/delete_file".length()).trim();
                handleDeleteFileRequest(peerId, fromId, filePath, false);
            }
            return true;
        }
        if (lower.startsWith("/delete_folder")) {
            if (lower.equals("/delete_folder")) {
                vkApiClient.sendMessage(peerId, """
                        Укажите путь к папке для удаления:
                        /delete_folder <относительный_путь>
                        Например: /delete_folder СтараяКнига""");
            } else {
                String folderPath = lower.substring("/delete_folder".length()).trim();
                handleDeleteFileRequest(peerId, fromId, folderPath, true);
            }
            return true;
        }
        if (lower.equals("/status") || lower.equals("ℹ️ статус библиотеки")) {
            handleStatus(peerId);
            return true;
        }
        if (lower.equals("/update_library") || lower.equals("📦 обновить из zip")) {
            vkApiClient.sendMessage(peerId, "📦 Обновление из ZIP будет доступно в версии 2.0.");
            return true;
        }
        if (lower.equals("да, подтверждаю") || lower.equals("✅ да, подтверждаю")) {
            return handleConfirmation(peerId, fromId, "confirm");
        }
        if (lower.equals("отмена") || lower.equals("❌ отмена")) {
            return handleConfirmation(peerId, fromId, "cancel");
        }

        return false;
    }

    private void sendAdminMenu(long peerId) {
        String keyboard = VkKeyboard.buildAdminKeyboard();
        vkApiClient.sendMessageWithKeyboard(peerId,
                "🛠 Панель администратора J2J_Bot\nВыберите действие:", keyboard);
    }

    private void handleReloadKnowledge(long peerId) {
        vkApiClient.sendMessage(peerId, "🔄 Начинаю переиндексацию... Это может занять несколько минут.");
        Thread thread = new Thread(() -> {
            String result = knowledgeService.reloadKnowledge();
            vkApiClient.sendMessage(peerId, result);
        }, "indexing-thread");
        thread.setDaemon(true);
        thread.start();
    }

    private void handleClearKnowledgeRequest(long peerId, long fromId) {
        pendingConfirmations.put(fromId, new PendingConfirmation("clear_knowledge", null));
        String keyboard = VkKeyboard.buildConfirmKeyboard("confirm", "cancel");
        vkApiClient.sendMessageWithKeyboard(peerId,
                "⚠️ Вы уверены, что хотите очистить всю базу знаний? Это действие необратимо.",
                keyboard);
    }

    private void handleDeleteFileRequest(long peerId, long fromId, String path, boolean isFolder) {
        File target = resolveSecurePath(path);
        if (target == null) {
            vkApiClient.sendMessage(peerId, "❌ Недопустимый путь. Доступ разрешён только внутри папки Литература.");
            return;
        }
        if (!target.exists()) {
            vkApiClient.sendMessage(peerId, "❌ Файл/папка не найден: " + path);
            return;
        }
        pendingConfirmations.put(fromId,
                new PendingConfirmation(isFolder ? "delete_folder" : "delete_file", path));
        String keyboard = VkKeyboard.buildConfirmKeyboard("confirm", "cancel");
        vkApiClient.sendMessageWithKeyboard(peerId,
                "⚠️ Подтвердите удаление " + (isFolder ? "папки" : "файла") + ": " + path,
                keyboard);
    }

    private boolean handleConfirmation(long peerId, long fromId, String response) {
        PendingConfirmation pending = pendingConfirmations.remove(fromId);
        if (pending == null) return false;

        if (response.equals("cancel")) {
            vkApiClient.sendMessage(peerId, "❌ Действие отменено.");
            return true;
        }

        switch (pending.action()) {
            case "clear_knowledge" -> {
                knowledgeService.clearKnowledge();
                vkApiClient.sendMessage(peerId, "🗑 База знаний очищена.");
            }
            case "delete_file" -> {
                File target = resolveSecurePath(pending.path());
                if (target != null && target.exists()) {
                    boolean deleted = target.delete();
                    vkApiClient.sendMessage(peerId, deleted
                            ? "✅ Файл удалён: " + pending.path()
                            : "❌ Не удалось удалить файл: " + pending.path());
                } else {
                    vkApiClient.sendMessage(peerId, "❌ Файл не найден: " + pending.path());
                }
            }
            case "delete_folder" -> {
                File target = resolveSecurePath(pending.path());
                if (target != null && target.exists()) {
                    boolean deleted = deleteRecursively(target);
                    vkApiClient.sendMessage(peerId, deleted
                            ? "✅ Папка удалена: " + pending.path()
                            : "❌ Не удалось удалить папку: " + pending.path());
                } else {
                    vkApiClient.sendMessage(peerId, "❌ Папка не найдена: " + pending.path());
                }
            }
        }
        return true;
    }

    private void handleListFiles(long peerId, String relativePath) {
        File dir;
        if (relativePath.isBlank()) {
            dir = new File(config.getKnowledgeRoot());
        } else {
            dir = resolveSecurePath(relativePath);
            if (dir == null) {
                vkApiClient.sendMessage(peerId, "❌ Недопустимый путь.");
                return;
            }
        }

        if (!dir.exists()) {
            vkApiClient.sendMessage(peerId, "❌ Папка не найдена: " + dir.getAbsolutePath());
            return;
        }

        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            vkApiClient.sendMessage(peerId, "📂 Папка пуста: " + dir.getPath());
            return;
        }

        Arrays.sort(files);
        StringBuilder sb = new StringBuilder("📂 Содержимое папки ");
        sb.append(relativePath.isBlank() ? "Литература" : relativePath).append(":\n\n");

        for (File f : files) {
            sb.append(f.isDirectory() ? "📁 " : "📄 ").append(f.getName());
            if (!f.isDirectory()) {
                sb.append(" (").append(formatSize(f.length())).append(")");
            }
            sb.append("\n");
        }

        String msg = sb.toString();
        if (msg.length() > 4000) msg = msg.substring(0, 4000) + "\n...(список обрезан)";
        vkApiClient.sendMessage(peerId, msg);
    }

    private void handleStatus(long peerId) {
        LibraryMetadataService.LibraryMetadata meta = metadataService.getMetadata();
        vkApiClient.sendMessage(peerId, meta.formatStatus());
    }

    /**
     * Resolves a path securely within KNOWLEDGE_ROOT.
     * Returns null if the path escapes the root directory.
     */
    private File resolveSecurePath(String relativePath) {
        try {
            File root = new File(config.getKnowledgeRoot()).getCanonicalFile();
            File target = new File(root, relativePath).getCanonicalFile();
            // Prevent path traversal and deletion of root itself
            if (!target.getPath().startsWith(root.getPath()) || target.equals(root)) {
                return null;
            }
            return target;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursively(child)) return false;
                }
            }
        }
        return file.delete();
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    private record PendingConfirmation(String action, String path) {}
}
