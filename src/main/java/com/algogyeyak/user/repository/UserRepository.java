package com.algogyeyak.user.repository;

import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.enums.Role;
import com.algogyeyak.user.enums.UserStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
     */
    @Query("""
            SELECT u FROM User u
            WHERE (:email IS NULL OR u.email LIKE CONCAT('%', :email, '%'))
              AND (:nickname IS NULL OR u.nickname LIKE CONCAT('%', :nickname, '%'))
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

    // 관리자 통계 대시보드: 역할별 분포 카드용.
    long countByRole(Role role);

    // 관리자 통계 대시보드: 기간별 가입 추이용. DB별 날짜 절삭 함수(FUNCTION('DATE', ...) 등) 차이에
    // 기대지 않기 위해 원본 시각만 가져오고, 일자별 집계는 서비스에서 LocalDateTime::toLocalDate로 한다.
    // end는 배타적 상한(다음날 00:00)으로 넘어온다 - 대상 마지막 날짜를 하루 전체 포함시키기 위함.
    @Query("SELECT u.createdAt FROM User u WHERE u.createdAt >= :start AND u.createdAt < :end")
    List<LocalDateTime> findCreatedAtBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
}
