package com.algogyeyak.user.repoository;

import com.algogyeyak.user.entity.UserPreference;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserPreferenceRepository extends JpaRepository<UserPreference, Long> {

    Optional<UserPreference> findByUserId(Long userId);

    boolean existsByUserId(Long userId);
}
