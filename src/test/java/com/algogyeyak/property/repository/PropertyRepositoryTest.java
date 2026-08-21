package com.algogyeyak.property.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.entity.PropertyStatus;
import com.algogyeyak.property.entity.PropertyType;
import com.algogyeyak.property.entity.TransactionType;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

@DataJpaTest
class PropertyRepositoryTest {

    @Autowired
    private PropertyRepository propertyRepository;

    private Property save(Long userId) {
        return propertyRepository.save(Property.builder()
                .userId(userId)
                .title("테스트 매물")
                .propertyType(PropertyType.OFFICETEL)
                .transactionType(TransactionType.JEONSE)
                .deposit(10_000_000L)
                .area(20.0)
                .description("테스트 매물")
                .build());
    }

    @Test
    void 대상_유저_중_매물을_등록한_유저가_없으면_0을_반환한다() {
        save(99L);

        assertThat(propertyRepository.countDistinctUserIdIn(List.of(1L))).isZero();
    }

    @Test
    void 지정한_유저_중_매물을_등록한_유저_수만_distinct하게_집계한다() {
        save(1L);
        save(2L);
        save(2L);

        assertThat(propertyRepository.countDistinctUserIdIn(List.of(1L, 2L, 3L))).isEqualTo(2);
    }

    @Test
    void 대상_목록에_없는_유저의_매물은_집계하지_않는다() {
        save(1L);
        save(99L);

        assertThat(propertyRepository.countDistinctUserIdIn(List.of(1L))).isEqualTo(1);
    }

    @Test
    void 삭제된_매물도_등록_이력으로_집계한다() {
        Property property = save(1L);
        property.delete();
        propertyRepository.save(property);

        assertThat(propertyRepository.countDistinctUserIdIn(List.of(1L))).isEqualTo(1);
    }

    // createdAt(@CreatedDate)은 JPA auditing(@EnableJpaAuditing, JpaAuditingConfig)에 의존하는데,
    // @DataJpaTest는 이 애플리케이션의 슬라이스 컨텍스트에 JpaAuditingConfig를 자동으로 포함하지
    // 않아 단독 실행 시 항상 null로 남는다(ChecklistRepositoryTest에 동일하게 문서화된 문제).
    // ChecklistRepositoryTest의 touchUpdatedAt()은 저장 후 saveAndFlush()로 값을 다시 써넣는
    // 방식인데, updatedAt과 달리 createdAt은 @Column(updatable = false)라 그 방식이 안 통한다
    // (Hibernate가 UPDATE 문 자체에 이 컬럼을 아예 안 실음) - INSERT 시점에 값이 실리도록,
    // 저장 전(transient 상태)에 미리 필드를 지정해야 한다.
    private Property saveWithCreatedAt(Long userId, LocalDateTime createdAt) {
        Property property = Property.builder()
                .userId(userId)
                .title("테스트 매물")
                .propertyType(PropertyType.OFFICETEL)
                .transactionType(TransactionType.JEONSE)
                .deposit(10_000_000L)
                .area(20.0)
                .description("테스트 매물")
                .build();
        ReflectionTestUtils.setField(property, "createdAt", createdAt);
        return propertyRepository.save(property);
    }

    // 회귀 테스트 - AdminStatsService.summary()의 newProperties 카드는 findCreatedAtBetween(추이
    // 차트)과 항상 합이 맞아야 한다(둘 다 "등록 발생" 자체를 센다). 예전엔 이 카운트만 ACTIVE로
    // 필터링해서, 기간 내 등록 후 삭제된 매물이 있으면 추이 차트 합계보다 이 카드가 더 작게 나와
    // 같은 화면에서 숫자가 어긋났다.
    @Test
    void 신규_등록_매물수는_기간_내_등록되었으면_이후_삭제되어도_집계된다() {
        LocalDateTime rangeStart = LocalDateTime.now().minusMinutes(1);
        saveWithCreatedAt(1L, LocalDateTime.now());

        Property deleted = saveWithCreatedAt(2L, LocalDateTime.now());
        deleted.delete();
        propertyRepository.save(deleted);
        LocalDateTime rangeEnd = LocalDateTime.now().plusMinutes(1);

        assertThat(propertyRepository.countByCreatedAtBetween(rangeStart, rangeEnd)).isEqualTo(2);
    }

    // #233 - hasSignal 필터용 signalPropertyIds 파라미터가 다른 조건들과 동일한
    // "(:param IS NULL OR ...)" 패턴으로 동작하는지 실제 쿼리로 검증한다. 다른 조건들은 전부 단일
    // 값(Long/String/enum) 기준으로 이미 검증돼 있었는데, 컬렉션 파라미터에 대한 null 체크는
    // Hibernate/DB 조합에 따라 동작이 갈릴 수 있어 별도로 확인이 필요했다.
    private Pageable defaultPageable() {
        return PageRequest.of(0, 20);
    }

    // 5차 멘토링 피드백 6-3 - title(건물명) 조건이 다른 텍스트 조건(region)과 동일한 LIKE 부분일치
    // 패턴으로 동작하는지 실제 쿼리로 검증한다.
    @Test
    void title_조건으로_필터링하면_건물명이_부분일치하는_매물만_반환한다() {
        Property raemian = propertyRepository.save(Property.builder()
                .userId(1L).title("래미안 강남").propertyType(PropertyType.OFFICETEL)
                .transactionType(TransactionType.JEONSE).deposit(10_000_000L).area(20.0).build());
        propertyRepository.save(Property.builder()
                .userId(1L).title("힐스테이트").propertyType(PropertyType.OFFICETEL)
                .transactionType(TransactionType.JEONSE).deposit(10_000_000L).area(20.0).build());

        Page<Property> result = propertyRepository.search(
                1L, PropertyStatus.ACTIVE,
                null, "래미안", null, null, null, null, null, null, null, null,
                null,
                defaultPageable()
        );

        assertThat(result.getContent()).extracting(Property::getId).containsExactly(raemian.getId());
    }

    @Test
    void signalPropertyIds가_null이면_필터링_없이_전체_매물을_반환한다() {
        Property property1 = save(1L);
        Property property2 = save(1L);

        Page<Property> result = propertyRepository.search(
                1L, PropertyStatus.ACTIVE,
                null, null, null, null, null, null, null, null, null, null,
                null,
                defaultPageable()
        );

        assertThat(result.getContent()).extracting(Property::getId)
                .containsExactlyInAnyOrder(property1.getId(), property2.getId());
    }

    @Test
    void signalPropertyIds에_담긴_id의_매물만_반환한다() {
        Property signaled = save(1L);
        save(1L);

        Page<Property> result = propertyRepository.search(
                1L, PropertyStatus.ACTIVE,
                null, null, null, null, null, null, null, null, null, null,
                List.of(signaled.getId()),
                defaultPageable()
        );

        assertThat(result.getContent()).extracting(Property::getId).containsExactly(signaled.getId());
    }

    @Test
    void signalPropertyIds가_빈_리스트면_결과가_없다() {
        save(1L);

        Page<Property> result = propertyRepository.search(
                1L, PropertyStatus.ACTIVE,
                null, null, null, null, null, null, null, null, null, null,
                List.of(),
                defaultPageable()
        );

        assertThat(result.getContent()).isEmpty();
    }
}
