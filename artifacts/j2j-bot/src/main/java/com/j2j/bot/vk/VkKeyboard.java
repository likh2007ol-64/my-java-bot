package com.j2j.bot.vk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Builds VK keyboard JSON strings.
 */
public class VkKeyboard {

    private static final ObjectMapper mapper = new ObjectMapper();

    public static String buildAdminKeyboard() {
        ObjectNode keyboard = mapper.createObjectNode();
        keyboard.put("one_time", false);
        keyboard.put("inline", false);

        ArrayNode buttons = mapper.createArrayNode();

        buttons.add(buildRow(
                buildButton("🔄 Переиндексировать всё", "secondary", "/reload_knowledge"),
                buildButton("🗑 Очистить базу", "negative", "/clear_knowledge")
        ));
        buttons.add(buildRow(
                buildButton("📂 Показать файлы", "secondary", "/list_files"),
                buildButton("❌ Удалить файл/папку", "negative", "/delete_file")
        ));
        buttons.add(buildRow(
                buildButton("📦 Обновить из ZIP", "secondary", "/update_library"),
                buildButton("ℹ️ Статус библиотеки", "primary", "/status")
        ));

        keyboard.set("buttons", buttons);
        try {
            return mapper.writeValueAsString(keyboard);
        } catch (Exception e) {
            return "{}";
        }
    }

    public static String buildConfirmKeyboard(String confirmPayload, String cancelPayload) {
        ObjectNode keyboard = mapper.createObjectNode();
        keyboard.put("one_time", true);
        keyboard.put("inline", false);

        ArrayNode buttons = mapper.createArrayNode();
        buttons.add(buildRow(
                buildButton("✅ Да, подтверждаю", "negative", confirmPayload),
                buildButton("❌ Отмена", "secondary", cancelPayload)
        ));
        keyboard.set("buttons", buttons);
        try {
            return mapper.writeValueAsString(keyboard);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static ArrayNode buildRow(ObjectNode... btns) {
        ArrayNode row = mapper.createArrayNode();
        for (ObjectNode btn : btns) row.add(btn);
        return row;
    }

    private static ObjectNode buildButton(String label, String color, String payload) {
        ObjectNode btn = mapper.createObjectNode();
        btn.put("color", color);

        ObjectNode action = mapper.createObjectNode();
        action.put("type", "text");
        action.put("label", label);
        action.put("payload", "{\"command\":\"" + payload + "\"}");
        btn.set("action", action);
        return btn;
    }
}
