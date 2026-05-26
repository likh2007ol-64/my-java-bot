package com.j2j.bot.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.util.Arrays;
import java.util.List;

@Configuration
@Getter
public class AppConfig {

    @Value("${vk.token}")
    private String vkToken;

    @Value("${deepseek.api.key}")
    private String deepseekApiKey;

    @Value("${deepseek.api.url}")
    private String deepseekApiUrl;

    @Value("${deepseek.model}")
    private String deepseekModel;

    @Value("${deepseek.temperature}")
    private double deepseekTemperature;

    @Value("${admin.ids}")
    private String adminIdsRaw;

    @Value("${knowledge.root}")
    private String knowledgeRoot;

    @Value("${vector.store.path}")
    private String vectorStorePath;

    @Value("${sqlite.db.path}")
    private String sqliteDbPath;

    @Value("${static.dir}")
    private String staticDir;

    @Value("${rag.max.results:5}")
    private int ragMaxResults;

    @Value("${rag.min.score:0.4}")
    private double ragMinScore;

    public List<String> getAdminIds() {
        if (adminIdsRaw == null || adminIdsRaw.isBlank()) return List.of();
        return Arrays.asList(adminIdsRaw.split(","));
    }

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
