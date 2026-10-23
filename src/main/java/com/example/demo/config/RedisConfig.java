package com.example.demo.config;

import com.example.demo.common.NamespacedRedisKeySerializer;
import com.example.demo.common.RedisKeyNamespace;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.util.Arrays;

/**
 * Redis 設定。
 *
 * 連線本身（host / port / password / database / timeout / lettuce pool）交由
 * Spring Boot 依 application.yml 的 {@code spring.data.redis.*} 自動組態，
 * 這裡負責序列化與鍵的命名空間（ADR-0013）。
 *
 * 註：先前版本以 embedded-redis 在 @PostConstruct 啟動內嵌服務，已改為連線
 * docker-compose.yml 提供的真實 Redis。
 */
@Configuration
public class RedisConfig {

    /**
     * 不加命名空間的字串模板的 bean 名稱。只給與環境無關的鍵使用（目前僅 {@code fbtoken:}，見 ADR-0013）。
     */
    public static final String GLOBAL_STRING_TEMPLATE = "globalStringRedisTemplate";

    /**
     * 通用 RedisTemplate：key 加命名空間、value 用 JSON，供快取 DTO 使用。
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory redisConnectionFactory,
                                                       Environment environment,
                                                       @Value("${app.redis.key-namespace:}") String keyNamespace) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(redisConnectionFactory);

        template.setKeySerializer(namespacedKeys(environment, keyNamespace));
        // hash 欄位是資料，不是鍵，不加前綴
        template.setHashKeySerializer(new StringRedisSerializer());

        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer();
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        return template;
    }

    /**
     * 主要的字串模板：鍵加命名空間。Redis 服務一律注入這個（{@link Primary}）。
     * 只替換鍵的序列化器；hash 欄位與 value 維持 Spring 預設的字串序列化。
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory redisConnectionFactory,
                                                   Environment environment,
                                                   @Value("${app.redis.key-namespace:}") String keyNamespace) {
        StringRedisTemplate template = new StringRedisTemplate(redisConnectionFactory);
        template.setKeySerializer(namespacedKeys(environment, keyNamespace));
        template.afterPropertiesSet();
        return template;
    }

    /**
     * 不加命名空間的字串模板（ADR-0013）。只給與環境無關的鍵使用。
     *
     * <p>{@code fbtoken:} 的單次使用紀錄必須跨環境共用：同一個 Firebase 專案的權杖在每個環境都有效，
     * 若加上前綴，A 環境用過的權杖到 B 環境會再被接受一次。
     */
    @Bean(name = GLOBAL_STRING_TEMPLATE)
    public StringRedisTemplate globalStringRedisTemplate(RedisConnectionFactory redisConnectionFactory) {
        StringRedisTemplate template = new StringRedisTemplate(redisConnectionFactory);
        template.afterPropertiesSet();
        return template;
    }

    /**
     * 組出鍵的序列化器。prod 未設定命名空間時在這裡拋出例外，應用程式啟動失敗（fail-closed）。
     */
    private static RedisSerializer<String> namespacedKeys(Environment environment, String configuredNamespace) {
        boolean production = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        String namespace = RedisKeyNamespace.resolveForProfile(production, configuredNamespace);
        return new NamespacedRedisKeySerializer(namespace);
    }
}
