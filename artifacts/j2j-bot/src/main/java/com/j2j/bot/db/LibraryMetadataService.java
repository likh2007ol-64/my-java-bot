package com.j2j.bot.db;

import com.j2j.bot.config.AppConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
@Slf4j
@RequiredArgsConstructor
public class LibraryMetadataService {

    private final AppConfig config;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Connection getConnection() throws SQLException {
        File dbFile = new File(config.getSqliteDbPath());
        dbFile.getParentFile().mkdirs();
        return DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
    }

    public void init() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS library_metadata (
                    id INTEGER PRIMARY KEY,
                    books_count INTEGER DEFAULT 0,
                    chunks_count INTEGER DEFAULT 0,
                    last_indexed TEXT,
                    updated_at TEXT
                )
            """);
            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM library_metadata");
            if (rs.next() && rs.getInt(1) == 0) {
                stmt.executeUpdate("INSERT INTO library_metadata (id, books_count, chunks_count) VALUES (1, 0, 0)");
            }
            log.info("SQLite database initialized at: {}", config.getSqliteDbPath());
        } catch (Exception e) {
            log.error("Failed to initialize SQLite database: {}", e.getMessage(), e);
        }
    }

    public void updateIndexingResult(int booksCount, int chunksCount) {
        String now = LocalDateTime.now().format(FMT);
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE library_metadata SET books_count=?, chunks_count=?, last_indexed=?, updated_at=? WHERE id=1")) {
            ps.setInt(1, booksCount);
            ps.setInt(2, chunksCount);
            ps.setString(3, now);
            ps.setString(4, now);
            ps.executeUpdate();
        } catch (Exception e) {
            log.error("Failed to update library metadata: {}", e.getMessage());
        }
    }

    public LibraryMetadata getMetadata() {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT books_count, chunks_count, last_indexed FROM library_metadata WHERE id=1")) {
            if (rs.next()) {
                return new LibraryMetadata(
                        rs.getInt("books_count"),
                        rs.getInt("chunks_count"),
                        rs.getString("last_indexed")
                );
            }
        } catch (Exception e) {
            log.error("Failed to get library metadata: {}", e.getMessage());
        }
        return new LibraryMetadata(0, 0, null);
    }

    public record LibraryMetadata(int booksCount, int chunksCount, String lastIndexed) {
        public String formatStatus() {
            return String.format("""
                    📚 Статус библиотеки:
                    • Книг (PDF): %d
                    • Чанков в базе: %d
                    • Последняя индексация: %s""",
                    booksCount, chunksCount,
                    lastIndexed != null ? lastIndexed : "никогда");
        }
    }
}
