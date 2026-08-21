package com.algogyeyak.auth.jwt;

import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.enums.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserAuthStatusCacheServiceTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final UserAuthStatusCacheService service = new UserAuthStatusCacheService(redisTemplate);

    UserAuthStatusCacheServiceTest() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private User user(Long id) {
        User user = User.createOAuthUser("test@example.com", "테스트유저", null);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private final String[] capturedJson = new String[1];

    @Test
    void saveThenFindRoundTripsThroughJson() {
        User user = user(1L);
        when(valueOperations.get("auth:user-status:1")).thenAnswer(invocation -> capturedJson[0]);
        org.mockito.Mockito.doAnswer(invocation -> {
            capturedJson[0] = invocation.getArgument(1);
            return null;
        }).when(valueOperations).set(eq("auth:user-status:1"), anyString(), eq(Duration.ofSeconds(30)));

        service.save(1L, user);
        Optional<UserAuthStatusCacheService.CachedUserStatus> found = service.find(1L);

        assertTrue(found.isPresent());
        assertEquals(user.getEmail(), found.get().email());
        assertEquals(Role.USER, found.get().role());
        assertFalse(found.get().isWithdrawn());
        verify(valueOperations).set(eq("auth:user-status:1"), anyString(), eq(Duration.ofSeconds(30)));
    }

    @Test
    void findReturnsEmptyOnCacheMiss() {
        when(valueOperations.get("auth:user-status:1")).thenReturn(null);

        assertTrue(service.find(1L).isEmpty());
    }

    @Test
    void findFailsOpenWhenRedisThrows() {
        when(valueOperations.get("auth:user-status:1")).thenThrow(new QueryTimeoutException("redis down"));

        assertTrue(service.find(1L).isEmpty());
    }

    @Test
    void saveDoesNotPropagateWhenRedisThrows() {
        doThrow(new QueryTimeoutException("redis down"))
                .when(valueOperations).set(anyString(), anyString(), eq(Duration.ofSeconds(30)));

        service.save(1L, user(1L));
        // 예외 없이 여기까지 도달하면 fail-open이 의도대로 동작한 것이다.
    }

    @Test
    void evictAfterCommitDeletesImmediatelyWhenNoTransactionActive() {
        assertFalse(TransactionSynchronizationManager.isSynchronizationActive());

        service.evictAfterCommit(1L);

        verify(redisTemplate).delete("auth:user-status:1");
    }

    @Test
    void evictAfterCommitDefersDeleteUntilCommitWhenTransactionActive() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.evictAfterCommit(1L);
            verify(redisTemplate, never()).delete("auth:user-status:1");

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(sync -> sync.afterCommit());

            verify(redisTemplate).delete("auth:user-status:1");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
