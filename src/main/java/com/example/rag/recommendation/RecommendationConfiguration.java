package com.example.rag.recommendation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RecommendationConfiguration {
    @Bean(destroyMethod = "close")
    EmbeddingClient embeddingClient(@Value("${SILICONFLOW_API_KEY}") String apiKey) {
        return new EmbeddingClient(apiKey);
    }

    @Bean(destroyMethod = "close")
    QdrantClient qdrantClient(@Value("${QDRANT_URL:http://127.0.0.1:6333}") String baseUrl) {
        return new QdrantClient(baseUrl);
    }

    @Bean(destroyMethod = "close")
    RecommendationChatClient recommendationChatClient(@Value("${SILICONFLOW_API_KEY}") String apiKey) {
        return new RecommendationChatClient(apiKey);
    }

    @Bean
    ProfileQueryBuilder profileQueryBuilder() {
        return new ProfileQueryBuilder();
    }

    @Bean
    RecommendationValidator recommendationValidator() {
        return new RecommendationValidator();
    }
}
