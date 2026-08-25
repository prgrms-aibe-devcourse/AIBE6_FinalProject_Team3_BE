package com.algogyeyak.global.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.algogyeyak.marketdata.config.MarketComparisonProperties;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;

class RedisCacheConfigTest {

    private final RedisCacheConfig config =
            new RedisCacheConfig(new MarketComparisonProperties(300, 600, 3, 0.2, 6, 30));

    // Redis 장애 시에도 캐시 조회/저장/삭제/전체삭제가 예외를 던지지 않고 "fail-open"으로
    // 넘어가야 한다 - marketComparison 캐시는 성능 최적화용이라 Redis가 죽어도 서비스는
    // 살아있어야 한다는 요구사항을 검증한다.
    @Test
    void 캐시_조회_저장_삭제_전체삭제_실패시_예외를_삼키고_경고로그만_남긴다() {
        CacheErrorHandler errorHandler = config.errorHandler();
        Cache cache = mock(Cache.class);
        when(cache.getName()).thenReturn("marketComparison");
        RuntimeException redisDown = new RuntimeException("Redis connection refused");

        assertThatCode(() -> errorHandler.handleCacheGetError(redisDown, cache, "key"))
                .doesNotThrowAnyException();
        assertThatCode(() -> errorHandler.handleCachePutError(redisDown, cache, "key", "value"))
                .doesNotThrowAnyException();
        assertThatCode(() -> errorHandler.handleCacheEvictError(redisDown, cache, "key"))
                .doesNotThrowAnyException();
        assertThatCode(() -> errorHandler.handleCacheClearError(redisDown, cache))
                .doesNotThrowAnyException();
    }
}
