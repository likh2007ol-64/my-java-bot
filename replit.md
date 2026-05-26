# J2J_Bot — VK-бот для «Java-старт | Jump2Java»

VK-бот учебный ассистент: отвечает на теоретические вопросы по Java с помощью RAG-системы (векторный поиск по PDF-учебникам + DeepSeek API), отправляет PNG-персонажей и предоставляет административное управление библиотекой.

## Run & Operate

**Сборка:**
```bash
cd artifacts/j2j-bot && mvn package -DskipTests
```

**Запуск (через workflow «J2J Bot»):**
```bash
cd artifacts/j2j-bot && java -jar target/j2j-bot-1.0.0.jar
```

**Пересборка после изменений:**
```bash
cd artifacts/j2j-bot && mvn package -DskipTests && # затем restart workflow
```

## Секреты (обязательные)

- `VK_TOKEN` — токен сообщества ВКонтакте с правами на сообщения
- `DEEPSEEK_API_KEY` — API-ключ от platform.deepseek.com
- `ADMIN_IDS` — VK user ID администратора (числом)

## Опциональные переменные окружения

- `KNOWLEDGE_ROOT` — путь к папке с PDF (по умолчанию `/app/shared/Литература`)
- `VECTOR_STORE_PATH` — путь к файлу векторного хранилища (по умолчанию `./data/vector_store.json`)
- `SQLITE_DB_PATH` — путь к SQLite БД (по умолчанию `./data/j2j_bot.db`)
- `STATIC_DIR` — путь к папке с PNG-файлами (по умолчанию `./static`)
- `PORT` — порт веб-сервера Spring Boot (по умолчанию `8080`, не критично для бота)

## Стек

- **Java 19** (GraalVM) / **Spring Boot 3.3.5** / **Maven 3.8.6**
- **LangChain4j 0.36.2** — embedding-модель AllMiniLM-L6-v2 (многоязычная)
- **Файловый векторный стор** — cosine similarity, JSON-персистентность (./data/vector_store.json)
- **SQLite** — хранение метаданных индексации (дата, кол-во книг/чанков)
- **Apache PDFBox 3.0.3** — извлечение текста из PDF
- **DeepSeek API** — генерация ответов (модель deepseek-chat, температура 0.2)
- **VK Long Polling API** — получение сообщений (v5.131)

## Где что лежит

```
artifacts/j2j-bot/
├── pom.xml                          — Maven конфигурация
├── static/                          — PNG-изображения робота (заменить на реальные!)
│   ├── robot_greeting.png           — /start, /help
│   ├── robot_thinking.png           — обработка вопроса
│   ├── robot_happy.png              — успешный ответ
│   └── robot_sad.png                — ответ не найден / ошибка
├── data/                            — runtime данные (создаётся автоматически)
│   ├── vector_store.json            — векторное хранилище (после /reload_knowledge)
│   └── j2j_bot.db                   — SQLite метаданные
└── src/main/java/com/j2j/bot/
    ├── J2JBotApplication.java       — точка входа
    ├── config/AppConfig.java        — конфигурация из application.properties
    ├── vk/VkApiClient.java          — VK API HTTP-клиент
    ├── vk/VkLongPollingService.java — Long Polling цикл
    ├── vk/VkKeyboard.java           — JSON-клавиатуры VK
    ├── rag/EmbeddingService.java    — LangChain4j AllMiniLM embedding
    ├── rag/VectorStore.java         — файловый векторный стор (cosine sim)
    ├── rag/KnowledgeService.java    — индексация PDF, поиск
    ├── deepseek/DeepSeekService.java— запросы к DeepSeek API
    ├── handler/MessageHandler.java  — маршрутизация входящих сообщений
    ├── admin/AdminService.java      — административные команды
    ├── db/LibraryMetadataService.java — SQLite метаданные библиотеки
    └── image/ImageUploadService.java  — загрузка PNG в VK
```

## Архитектурные решения

- **Векторный стор без внешнего сервера**: вместо ChromaDB использован файловый JSON-стор с cosine similarity — проще деплоить, не требует Python/ChromaDB сервера. Для версии 2.0 можно заменить на Chroma.
- **AllMiniLM-L6-v2 вместо BAAI/bge-m3**: модель запускается локально через LangChain4j ONNX без внешних вызовов. Для полного многоязычного покрытия в 2.0 рекомендуется перейти на BGE-M3 через HTTP-сервис.
- **VK Long Polling**: бот сам запрашивает обновления — не требует публичного HTTPS URL (в отличие от Callback API).
- **Однократная загрузка PNG**: при старте бот загружает все 4 изображения в VK один раз, хранит attachment-строки в памяти.

## Команды бота

| Команда | Кто | Действие |
|---|---|---|
| /start, /help | Все | Приветствие с robot_greeting.png |
| /about | Все | Информация о боте |
| /run, /explain | Все | Заглушка (версия 2.0) |
| /admin | Админ | Меню управления с клавиатурой |
| /reload_knowledge | Админ | Полная переиндексация PDF |
| /clear_knowledge | Админ | Очистка базы знаний |
| /list_files [путь] | Админ | Просмотр файлов в Литературе |
| /delete_file path | Админ | Удаление файла (с подтверждением) |
| /delete_folder path | Админ | Удаление папки (с подтверждением) |
| /status | Админ | Статус библиотеки |

## Настройка для деплоя на BotHost

1. Установить Java 17+ на BotHost
2. Скопировать JAR (`target/j2j-bot-1.0.0.jar`) и папку `static/`
3. Заменить PNG-заглушки на реальные изображения робота
4. Установить переменные окружения: VK_TOKEN, DEEPSEEK_API_KEY, ADMIN_IDS
5. Запустить: `java -jar j2j-bot-1.0.0.jar`

## Gotchas

- После изменения Java-кода нужна пересборка: `mvn package -DskipTests`, затем перезапуск workflow
- Первый запуск /reload_knowledge может занять несколько минут (зависит от объёма PDF)
- PNG-файлы в static/ являются заглушками 1x1px — замените на реальные изображения перед тестированием
- VK_TOKEN должен быть токеном **сообщества**, не личным токеном
- В VK настройках группы → Сообщения должно быть включено Long Polling API
