package com.algogyeyak.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.enums.Role;
import com.algogyeyak.user.enums.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;

/**
 * AdminUserControllerTest는 UserRepository를 mock으로 대체하므로 search()의 실제 LIKE/정확일치
 * SQL이 의도대로 동작하는지는 검증하지 못한다 - 실제 H2로 그 지점만 확인한다(관리자 유저 목록 조회,
 * GET /admin/users).
 */
@DataJpaTest
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    private User save(String email, String nickname, Role role, UserStatus status) {
        User user = User.createLocalUser(email, "hash", nickname);
        if (role == Role.ADMIN) {
            user.grantAdminRole();
        }
        if (status == UserStatus.SUSPENDED) {
            user.suspend();
        } else if (status == UserStatus.WITHDRAWN) {
            user.withdraw();
        }
        return userRepository.save(user);
    }

    @Test
    void 이메일_부분일치로_검색된다() {
        save("target@example.com", "닉네임1", Role.USER, UserStatus.ACTIVE);
        save("other@example.com", "닉네임2", Role.USER, UserStatus.ACTIVE);

        var result = userRepository.search("target", null, null, null, PageRequest.of(0, 20));

        assertThat(result.getContent()).extracting(User::getEmail).containsExactly("target@example.com");
    }

    @Test
    void 닉네임_부분일치로_검색된다() {
        save("a@example.com", "관리자후보", Role.USER, UserStatus.ACTIVE);
        save("b@example.com", "일반유저", Role.USER, UserStatus.ACTIVE);

        var result = userRepository.search(null, "관리자", null, null, PageRequest.of(0, 20));

        assertThat(result.getContent()).extracting(User::getNickname).containsExactly("관리자후보");
    }

    @Test
    void role은_정확히_일치하는_값만_반환한다() {
        save("admin@example.com", "관리자", Role.ADMIN, UserStatus.ACTIVE);
        save("user@example.com", "일반유저", Role.USER, UserStatus.ACTIVE);

        var result = userRepository.search(null, null, Role.ADMIN, null, PageRequest.of(0, 20));

        assertThat(result.getContent()).extracting(User::getRole).containsExactly(Role.ADMIN);
    }

    @Test
    void status는_정확히_일치하는_값만_반환한다() {
        save("suspended@example.com", "정지유저", Role.USER, UserStatus.SUSPENDED);
        save("active@example.com", "활성유저", Role.USER, UserStatus.ACTIVE);

        var result = userRepository.search(null, null, null, UserStatus.SUSPENDED, PageRequest.of(0, 20));

        assertThat(result.getContent()).extracting(User::getStatus).containsExactly(UserStatus.SUSPENDED);
    }

    @Test
    void 필터가_전부_없으면_전체_유저를_반환한다() {
        save("a@example.com", "유저A", Role.USER, UserStatus.ACTIVE);
        save("b@example.com", "유저B", Role.ADMIN, UserStatus.SUSPENDED);

        var result = userRepository.search(null, null, null, null, PageRequest.of(0, 20));

        assertThat(result.getTotalElements()).isEqualTo(2);
    }

    // 회귀 테스트 - ESCAPE '\'가 없으면 검색어에 포함된 리터럴 "_"가 SQL LIKE 와일드카드(임의의 한
    // 글자)로 해석돼, 이 검색이 "test_user@example.com"뿐 아니라 "testXuser@example.com"까지
    // 걸려버린다. AdminUserService.escapeLikePattern()이 넘기는 것과 동일하게 이미 이스케이프된
    // 입력("test\\_user")을 직접 주어, ESCAPE '\'가 그 이스케이프를 실제로 해석해 "_"를 리터럴
    // 문자로만 매칭하는지 확인한다.
    @Test
    void 이스케이프된_밑줄은_와일드카드가_아니라_리터럴_문자로_매칭된다() {
        save("test_user@example.com", "닉네임1", Role.USER, UserStatus.ACTIVE);
        save("testXuser@example.com", "닉네임2", Role.USER, UserStatus.ACTIVE);

        var result = userRepository.search("test\\_user", null, null, null, PageRequest.of(0, 20));

        assertThat(result.getContent()).extracting(User::getEmail).containsExactly("test_user@example.com");
    }

    @Test
    void 여러_필터가_동시에_AND_조건으로_적용된다() {
        save("target@example.com", "타겟닉네임", Role.ADMIN, UserStatus.ACTIVE);
        // 이메일은 일치하지만 role이 다른 유저 - AND 조건이면 결과에서 빠져야 한다.
        save("target2@example.com", "타겟닉네임2", Role.USER, UserStatus.ACTIVE);

        var result = userRepository.search("target", "타겟", Role.ADMIN, UserStatus.ACTIVE, PageRequest.of(0, 20));

        assertThat(result.getContent()).extracting(User::getEmail).containsExactly("target@example.com");
    }
}
