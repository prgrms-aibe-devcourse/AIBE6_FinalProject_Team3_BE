package com.algogyeyak.user.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.enums.Role;
import com.algogyeyak.user.enums.UserStatus;
import com.algogyeyak.user.repository.UserRepository;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * {@link AdminUserServiceTest}는 UserRepository를 mock으로 대체해 findAllByRoleAndStatusForUpdate가
 * 반환한 결과의 크기에 따른 분기만 검증한다 - PESSIMISTIC_WRITE가 실제로 두 트랜잭션을 직렬화하는지는
 * 실제 DB가 있어야 확인할 수 있다. 이 테스트는 H2(FOR UPDATE 행 잠금을 지원)로 두 관리자가 정확히
 * 동시에 서로를 강등하는 시나리오를 재현해, 정확히 하나만 성공하는지 직접 검증한다.
 */
@SpringBootTest
class AdminUserServiceConcurrentDemotionIntegrationTest {

    @Autowired
    private AdminUserService adminUserService;

    @Autowired
    private UserRepository userRepository;

    private User saveAdmin(String email, String nickname) {
        User admin = User.createLocalUser(email, "hash", nickname);
        admin.grantAdminRole();
        return userRepository.saveAndFlush(admin);
    }

    @Test
    @Timeout(15)
    void concurrentMutualDemotionLeavesExactlyOneAdmin() throws Exception {
        User admin1 = saveAdmin("admin1@example.com", "관리자1");
        User admin2 = saveAdmin("admin2@example.com", "관리자2");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger conflictCount = new AtomicInteger();

        try {
            Future<?> a = executor.submit(() -> attemptDemote(admin2.getId(), successCount, conflictCount));
            Future<?> b = executor.submit(() -> attemptDemote(admin1.getId(), successCount, conflictCount));
            a.get(10, TimeUnit.SECONDS);
            b.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(1);

        // findAllByRoleAndStatusForUpdate는 PESSIMISTIC_WRITE라 트랜잭션 밖에서는 호출할 수 없다 -
        // 여기서는 락이 필요 없는 단순 카운트로 최종 상태만 확인한다.
        long remainingActiveAdmins = userRepository.countByRoleAndStatus(Role.ADMIN, UserStatus.ACTIVE);
        assertThat(remainingActiveAdmins).isEqualTo(1);
    }

    private void attemptDemote(Long targetUserId, AtomicInteger successCount, AtomicInteger conflictCount) {
        try {
            adminUserService.updateRole(targetUserId, Role.USER);
            successCount.incrementAndGet();
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.ADMIN_LAST_ADMIN_ACCOUNT) {
                conflictCount.incrementAndGet();
            } else {
                throw e;
            }
        }
    }
}
