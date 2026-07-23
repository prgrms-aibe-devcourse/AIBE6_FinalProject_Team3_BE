package com.algogyeyak.auth.token;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    // 잠금을 걸지 않는다 — issue()에서 이 결과가 비어 있으면(신규 유저) 바로 REQUIRES_NEW
    // 트랜잭션으로 같은 user_id를 INSERT한다. MySQL/InnoDB는 유니크 인덱스 검색이 매칭되는
    // 행을 못 찾으면 그 자리에 gap lock을 거는데, 그 상태에서 "같은 값"을 REQUIRES_NEW로
    // INSERT하면 바깥 트랜잭션 자신이 쥔 gap lock에 안쪽 트랜잭션이 막혀 자기 자신과 데드락/락
    // 대기 타임아웃이 난다. (H2는 이 gap lock 동작을 그대로 재현하지 않아 테스트로는 못 잡았다.)
    // 그래서 신규 유저 동시 발급 시 유실되는 rotate는 감수하고(단일 세션 모델이라 재로그인과
    // 동일하게 마지막 커밋만 유효해도 안전), 여기서는 잠그지 않는다.
    Optional<RefreshToken> findByUserId(Long userId);

    // token_hash는 무작위 값이라 "못 찾음 → 그 값으로 즉시 INSERT" 패턴이 없다(rotate()/revoke()
    // 모두 못 찾으면 그냥 실패 처리하고 끝) — 그래서 여기서는 gap lock을 걸어도 위와 같은
    // 자기 자신과의 데드락 위험이 없고, 동시 rotate() 요청의 lost update를 안전하게 막아준다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RefreshToken> findByTokenHash(String tokenHash);
}
