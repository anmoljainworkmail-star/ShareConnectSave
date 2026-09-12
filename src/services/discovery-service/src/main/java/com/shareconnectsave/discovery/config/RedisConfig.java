package com.shareconnectsave.discovery.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

// Pattern: Cache-Aside — this bean is the client Discovery's later tickets
// (e.g. caching a User Service profile lookup) will inject: check Redis
// first, on a miss call User Service and write the result back here, so the
// *next* lookup for the same key is served from cache instead of hitting
// User Service again. Nothing in this ticket populates the cache yet — this
// class only makes the wiring correct once a feature package needs it.
//
// spring.data.redis.database: 0 in application.yml (not the client code
// below) is what actually pins this connection to logical DB index 0 — see
// T008's convention doc. This class is only concerned with HOW values get
// (de)serialized once connected, not WHICH database index is used.
@Configuration
public class RedisConfig {

    // Without an explicit template bean, Spring Data Redis's default
    // RedisTemplate<Object, Object> falls back to JDK serialization
    // (JdkSerializationRedisSerializer) for values — every cached object must
    // implement Serializable, cache entries are opaque binary blobs nobody can
    // read with `redis-cli GET`, and a single field rename on the cached class
    // breaks deserialization of every value already in the cache. JSON
    // serialization avoids all three problems and costs nothing extra since
    // Jackson is already on the classpath via spring-boot-starter-web.
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        return template;
    }
}
