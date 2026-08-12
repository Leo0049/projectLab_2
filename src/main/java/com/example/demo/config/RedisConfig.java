package com.example.demo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 設定。
 *
 * 連線本身（host / port / password / database / timeout / lettuce pool）交由
 * Spring Boot 依 application.yml 的 {@code spring.data.redis.*} 自動組態，
 * 這裡只負責序列化設定。
 *
 * 註：先前版本以 embedded-redis 在 @PostConstruct 啟動內嵌服務，已改為連線
 * docker-compose.yml 提供的真實 Redis。
 */
@Configuration
public class RedisConfig {

    /**
     * 通用 RedisTemplate：key 用字串、value 用 JSON，供快取 DTO 使用。
     * StringRedisTemplate 由 Spring Boot 自動組態提供，不需在此重複宣告。
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory redisConnectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(redisConnectionFactory);

        StringRedisSerializer keySerializer = new StringRedisSerializer();
        template.setKeySerializer(keySerializer);
        template.setHashKeySerializer(keySerializer);

        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer();
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        return template;
    }
}
