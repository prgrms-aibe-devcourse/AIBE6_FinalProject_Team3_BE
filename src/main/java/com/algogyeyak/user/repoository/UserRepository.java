package com.algogyeyak.user.repoository;

import com.algogyeyak.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    boolean existsByNickname(String nickname);

    // 본인 닉네임은 중복 검사에서 제외하기 위한 메서드
    boolean existsByNicknameAndIdNot(String nickname, Long id);
}
