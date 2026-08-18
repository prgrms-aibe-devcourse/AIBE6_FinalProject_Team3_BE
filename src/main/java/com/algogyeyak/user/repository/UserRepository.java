package com.algogyeyak.user.repository;

import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.enums.Role;
import com.algogyeyak.user.enums.UserStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByNickname(String nickname);

    // 본인 닉네임은 중복 검사에서 제외하기 위한 메서드
    boolean existsByNicknameAndIdNot(String nickname, Long id);

    /**
     * 관리자 페이지 유저 목록 조회. email/nickname은 부분일치(LIKE), role/status는 정확 매칭이며
     * 전부 선택 조건이다(null이면 필터링하지 않음) - PropertyRepository.search와 동일한 패턴.
     * email/nickname 파라미터는 호출부(AdminUserService.list)가 LIKE 와일드카드(%, _)를 이스케이프한
     * 뒤 넘긴다는 전제다 - ESCAPE '\'로 그 이스케이프를 실제로 해석한다.
     */
    @Query("""
            SELECT u FROM User u
            WHERE (:email IS NULL OR u.email LIKE CONCAT('%', :email, '%') ESCAPE '\\')
              AND (:nickname IS NULL OR u.nickname LIKE CONCAT('%', :nickname, '%') ESCAPE '\\')
              AND (:role IS NULL OR u.role = :role)
              AND (:status IS NULL OR u.status = :status)
            """)
    Page<User> search(
            @Param("email") String email,
            @Param("nickname") String nickname,
            @Param("role") Role role,
            @Param("status") UserStatus status,
            Pageable pageable
    );

    // 관리자 통계 대시보드: 기간별 가입 추이용. DB별 날짜 절삭 함수(FUNCTION('DATE', ...) 등) 차이에
    // 기대지 않기 위해 원본 시각만 가져오고, 일자별 집계는 서비스에서 LocalDateTime::toLocalDate로 한다.
    // end는 배타적 상한(다음날 00:00)으로 넘어온다 - 대상 마지막 날짜를 하루 전체 포함시키기 위함.
    @Query("SELECT u.createdAt FROM User u WHERE u.createdAt >= :start AND u.createdAt < :end")
    List<LocalDateTime> findCreatedAtBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    // 관리자 통계 대시보드: 신규 가입자 수 카드용(기간 내 가입).
    long countByCreatedAtBetween(LocalDateTime start, LocalDateTime end);

    // 마지막 남은 관리자 계정 보호(AdminUserService)용 - 실제로 로그인해 관리자 기능을 쓸 수 있는
    // 계정만 세려고 role뿐 아니라 status도 ACTIVE로 제한한다(SUSPENDED/WITHDRAWN인 ADMIN은 이미
    // 로그인 자체가 막혀 있어 그 수만큼 아무도 못 쓰는 셈이라 카운트에서 빼야 한다).
    long countByRoleAndStatus(Role role, UserStatus status);

    /**
     * countByRoleAndStatus와 대상은 같지만, 마지막 활성 관리자 보호(AdminUserService.rejectIfLastActiveAdmin)를
     * 원자적으로 만들기 위해 대상 행에 PESSIMISTIC_WRITE를 건다. 두 관리자가 동시에 서로를 강등/정지시키면,
     * 먼저 락을 잡은 트랜잭션이 커밋될 때까지 다른 트랜잭션은 이 조회에서 대기한다 - 그 뒤에야 락이 풀리고
     * 재개된 조회가 이미 반영된 최신 상태(강등된 관리자는 더 이상 조건에 안 걸림)를 보게 되어, 두 번째
     * 트랜잭션이 정확히 "마지막 남은 관리자"를 강등하려는 시도임을 감지할 수 있다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.role = :role AND u.status = :status")
    List<User> findAllByRoleAndStatusForUpdate(@Param("role") Role role, @Param("status") UserStatus status);

    // 관리자 통계 대시보드: 매물 등록자/미등록자 분포용 - 가입-등록 전환율을 보기 위해, 기간 내 가입한
    // 유저들의 id만 뽑아 PropertyRepository.countDistinctUserIdIn에 넘긴다.
    @Query("SELECT u.id FROM User u WHERE u.createdAt >= :start AND u.createdAt < :end")
    List<Long> findIdsByCreatedAtBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /**
     * 관리자 권한 변경(AdminUserService.updateRole)을 조건부 UPDATE로 처리한다. 기존 방식(엔티티를
     * 읽어 User.changeRole()로 필드만 바꾸고, 커밋 시점에 Hibernate dirty-checking이 UPDATE를
     * 내보내는 방식)은 "관리자가 읽은 시점엔 활성 상태였지만, 실제 커밋 시점엔 이미 본인 탈퇴가
     * 먼저 커밋된" 레이스에서 그 탈퇴를 무시하고 role을 덮어써 WITHDRAWN 상태의 계정이 ADMIN
     * 권한을 갖게 만들 수 있었다(UserWithdrawFieldOverwriteIntegrationTest 참고).
     * 이 UPDATE는 WHERE의 status 조건을 실행 시점의 최신 커밋 데이터로 평가한다(락을 잡는 쓰기
     * 문이라 findAllByRoleAndStatusForUpdate와 동일하게 REPEATABLE READ 스냅샷이 아니라 current
     * read) - 그 사이 탈퇴가 먼저 커밋됐다면 영향받은 row가 0건이 되어 안전하게 감지된다.
     */
    @Modifying
    @Query("UPDATE User u SET u.role = :role WHERE u.id = :id AND u.status <> :excludedStatus")
    int updateRoleIfNotWithdrawn(@Param("id") Long id, @Param("role") Role role, @Param("excludedStatus") UserStatus excludedStatus);

    /**
     * updateRoleIfNotWithdrawn과 동일한 이유로, 정지/활성화(AdminUserService.updateStatus)도
     * 조건부 UPDATE로 처리한다.
     */
    @Modifying
    @Query("UPDATE User u SET u.status = :status WHERE u.id = :id AND u.status <> :excludedStatus")
    int updateStatusIfNotWithdrawn(@Param("id") Long id, @Param("status") UserStatus status, @Param("excludedStatus") UserStatus excludedStatus);
}
