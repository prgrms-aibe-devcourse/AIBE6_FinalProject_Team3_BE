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

    // createdAt은 @CreatedDate + updatable=false라 저장 시점의 실제 시각으로만 채워진다 - 기간
    // 경계를 테스트할 때는 저장 전후로 now() 기준 여유를 둔 범위를 만들어 감싼다.
    //
    // 회귀 테스트 - AdminStatsService.summary()의 newProperties 카드는 findCreatedAtBetween(추이
    // 차트)과 항상 합이 맞아야 한다(둘 다 "등록 발생" 자체를 센다). 예전엔 이 카운트만 ACTIVE로
    // 필터링해서, 기간 내 등록 후 삭제된 매물이 있으면 추이 차트 합계보다 이 카드가 더 작게 나와
    // 같은 화면에서 숫자가 어긋났다.
    @Test
    void 신규_등록_매물수는_기간_내_등록되었으면_이후_삭제되어도_집계된다() {
        LocalDateTime rangeStart = LocalDateTime.now().minusMinutes(1);
        save(1L);
        Property deleted = save(2L);
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

    @Test
    void signalPropertyIds가_null이면_필터링_없이_전체_매물을_반환한다() {
        Property property1 = save(1L);
        Property property2 = save(1L);

        Page<Property> result = propertyRepository.search(
                1L, PropertyStatus.ACTIVE,
                null, null, null, null, null, null, null, null, null,
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
                null, null, null, null, null, null, null, null, null,
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
                null, null, null, null, null, null, null, null, null,
                List.of(),
                defaultPageable()
        );

        assertThat(result.getContent()).isEmpty();
    }
}
