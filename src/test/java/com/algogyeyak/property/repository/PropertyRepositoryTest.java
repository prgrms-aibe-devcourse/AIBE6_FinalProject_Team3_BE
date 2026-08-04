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

@DataJpaTest
class PropertyRepositoryTest {

    @Autowired
    private PropertyRepository propertyRepository;

    private Property save(Long userId) {
        return propertyRepository.save(Property.builder()
                .userId(userId)
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
    @Test
    void 신규_등록_매물수는_기간과_활성_상태를_모두_만족해야_집계된다() {
        LocalDateTime rangeStart = LocalDateTime.now().minusMinutes(1);
        save(1L);
        Property deleted = save(2L);
        deleted.delete();
        propertyRepository.save(deleted);
        LocalDateTime rangeEnd = LocalDateTime.now().plusMinutes(1);

        assertThat(propertyRepository.countByStatusAndCreatedAtBetween(PropertyStatus.ACTIVE, rangeStart, rangeEnd))
                .isEqualTo(1);
    }
}
