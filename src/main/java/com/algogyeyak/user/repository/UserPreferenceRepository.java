package com.algogyeyak.user.repository;

import com.algogyeyak.user.entity.UserPreference;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserPreferenceRepository extends JpaRepository<UserPreference, Long> {

    Optional<UserPreference> findByUserId(Long userId);

    boolean existsByUserId(Long userId);

    // 회원 탈퇴 시 정리용 - 다른 도메인이 참조하지 않는 본인 전용 검색 선호도 설정이라 하드 삭제한다.
    void deleteByUserId(Long userId);
}
