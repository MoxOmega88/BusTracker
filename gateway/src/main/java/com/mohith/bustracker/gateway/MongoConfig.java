package com.mohith.bustracker.gateway.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;

@Configuration
@ConditionalOnProperty(name = "bustracker.auth.enabled", havingValue = "true")
public class MongoConfig {
    @Bean
    MongoTemplate mongoTemplate(@Value("${bustracker.auth.mongodb-uri}") String uri) {
        return new MongoTemplate(new SimpleMongoClientDatabaseFactory(uri));   // the URI must include the database name
    }
}