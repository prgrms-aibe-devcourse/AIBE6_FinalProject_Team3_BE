package com.algogyeyak.global.config;

import com.algogyeyak.marketdata.config.MarketComparisonProperties;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * 캐시(Redis) 설정. RedisCacheManager를 직접 빈으로 등록한다 - Boot의 자동구성용 커스터마이저
 * 인터페이스(RedisCacheManagerBuilderCustomizer)는 버전마다 소속 모듈/패키지가 달라져서(Boot 4가
 * autoconfigure를 모듈별로 쪼개면서 위치가 바뀜) 대신 spring-data-redis 코어 API만으로 직접
 * 구성하는 편이 더 안정적이다.
 *
 * 값 직렬화를 GenericJackson2JsonRedisSerializer로 바꾸는 이유: 기본값(JDK 직렬화)은 캐싱
 * 대상인 MarketComparisonResponse가 Serializable을 구현하지 않는 record라 그대로 쓰면
 * 캐시에 쓰는 시점에 실패한다. JSON으로 저장하면 Redis 안의 값도 사람이 읽을 수 있어 디버깅에도
 * 유리하다.
 *
 * marketComparison 캐시(TTL)는 MarketComparisonProperties.cacheTtlMinutes()를 그대로
 * 쓴다 - market-data 도메인의 다른 정책값과 마찬가지로 재배포 없이 튜닝 가능하게 두기 위해
 * 하드코딩하지 않았다.
 */
@Configuration
@EnableCaching
@RequiredArgsConstructor
public class RedisCacheConfig {

    private final MarketComparisonProperties marketComparisonProperties;

    @Bean
    public CacheManager cacheManager(RedisConnectionFactory redisConnectionFactory) {
        RedisCacheConfiguration marketComparisonConfig = defaultConfig()
                .entryTtl(Duration.ofMinutes(marketComparisonProperties.cacheTtlMinutes()));

        return RedisCacheManager.builder(redisConnectionFactory)
                .cacheDefaults(defaultConfig())
                .withCacheConfiguration("marketComparison", marketComparisonConfig)
                .build();
    }

    private RedisCacheConfiguration defaultConfig() {
        return RedisCacheConfiguration.defaultCacheConfig()
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(new GenericJackson2JsonRedisSerializer())
                )
                .disableCachingNullValues();
    }
}
