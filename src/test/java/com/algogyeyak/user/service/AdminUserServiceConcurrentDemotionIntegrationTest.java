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
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;

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

    // 이 클래스가 쓰는 @SpringBootTest 컨텍스트는 다른 테스트(예: SecurityRoleEnforcementIntegrationTest)와
    // 같은 H2 인스턴스를 공유하고, 그 테스트들은 정리 없이 실제 ADMIN+ACTIVE 유저를 남겨둔다. 이 테스트의
    // 전제("활성 관리자는 지금 만드는 두 명뿐")가 깨지면 rejectIfLastActiveAdmin의 size<=1 가드 자체가
    // 정당하게 트리거되지 않아(다른 관리자가 남아있으니 실제로 "마지막"이 아님) 둘 다 성공해버린다 -
    // 락 실패가 아니라 전제 위반이다. 그래서 경쟁 시작 전에 이 두 명 외의 활성 관리자를 전부 정지시켜
    // 전제를 강제로 성립시킨다.
    private void suspendOtherActiveAdmins(Long... excludeIds) {
        // 이 시점엔 아직 경쟁이 시작되지 않았으니 락(findAllByRoleAndStatusForUpdate, PESSIMISTIC_WRITE)이
        // 필요 없다 - 오히려 그 메서드는 활성 트랜잭션을 요구해 여기서 그냥 호출하면
        // TransactionRequiredException이 난다. 락이 필요 없는 일반 조회로 대상만 찾고, 각각
        // saveAndFlush로 즉시 커밋해 이후 경쟁에서 최신 상태로 보이게 한다.
        var excluded = Set.of(excludeIds);
        userRepository.search(null, null, Role.ADMIN, UserStatus.ACTIVE, Pageable.unpaged())
                .stream()
                .filter(admin -> !excluded.contains(admin.getId()))
                .forEach(admin -> {
                    admin.suspend();
                    userRepository.saveAndFlush(admin);
                });
    }

    @Test
    @Timeout(15)
    void concurrentMutualDemotionLeavesExactlyOneAdmin() throws Exception {
        User admin1 = saveAdmin("admin1@example.com", "관리자1");
        User admin2 = saveAdmin("admin2@example.com", "관리자2");
        suspendOtherActiveAdmins(admin1.getId(), admin2.getId());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger conflictCount = new AtomicInteger();

        try {
            // 각자 상대방을 강등하는 행위자다 - admin1이 admin2를, admin2가 admin1을 동시에 강등 시도한다.
            Future<?> a = executor.submit(() -> attemptDemote(admin1.getId(), admin2.getId(), successCount, conflictCount));
            Future<?> b = executor.submit(() -> attemptDemote(admin2.getId(), admin1.getId(), successCount, conflictCount));
            a.get(10, TimeUnit.SECONDS);
            b.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(1);

        // 전역 카운트(countByRoleAndStatus)는 suspendOtherActiveAdmins()로 정리했어도, 이 검증
        // 시점 사이에 다른 병렬 테스트가 새로 관리자를 만들면 다시 흔들릴 수 있다 - 대신 이 테스트가
        // 직접 만든 두 계정의 최종 상태만 정확히 확인한다: 정확히 하나만 여전히 ADMIN+ACTIVE여야 한다.
        User reloadedAdmin1 = userRepository.findById(admin1.getId()).orElseThrow();
        User reloadedAdmin2 = userRepository.findById(admin2.getId()).orElseThrow();
        long stillAdminCount = Stream.of(reloadedAdmin1, reloadedAdmin2)
                .filter(user -> user.getRole() == Role.ADMIN && user.getStatus() == UserStatus.ACTIVE)
                .count();
        assertThat(stillAdminCount).isEqualTo(1);
    }

    private void attemptDemote(Long actorId, Long targetUserId, AtomicInteger successCount, AtomicInteger conflictCount) {
        try {
            adminUserService.updateRole(actorId, "actor" + actorId + "@example.com", targetUserId, Role.USER);
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
