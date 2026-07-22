package com.algogyeyak.property.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * createdAt/updatedAt 자동 관리를 위한 JPA Auditing 활성화.
 * 다른 도메인에서 이미 활성화했다면 중복 등록 문제 없는지 확인 후 하나로 통합할 것.
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}
