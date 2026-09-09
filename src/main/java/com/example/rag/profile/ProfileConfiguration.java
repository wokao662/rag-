package com.example.rag.profile;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ProfileConfiguration {
    @Bean(destroyMethod = "close")
    UserProfileExtractor userProfileExtractor(@Value("${SILICONFLOW_API_KEY}") String apiKey) {
        return new UserProfileExtractor(apiKey);
    }

    @Bean(destroyMethod = "close")
    ConversationalProfileAgent conversationalProfileAgent(
            @Value("${SILICONFLOW_API_KEY}") String apiKey
    ) {
        return new ConversationalProfileAgent(apiKey);
    }

    @Bean
    UserProfileValidator userProfileValidator() {
        return new UserProfileValidator();
    }

    @Bean
    UserProfileMerger userProfileMerger() {
        return new UserProfileMerger();
    }

    @Bean
    ProfileReadinessPolicy profileReadinessPolicy() {
        return new ProfileReadinessPolicy();
    }
}
