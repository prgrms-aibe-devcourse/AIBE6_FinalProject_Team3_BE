package com.algogyeyak.property.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.entity.PropertyStatus;
import com.algogyeyak.property.entity.PropertyType;
import com.algogyeyak.property.entity.TransactionType;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

@DataJpaTest
class PropertyRepositoryTest {

    @Autowired
    private PropertyRepository propertyRepository;

    // createdAt은 @CreatedDate + updatable=false라 저장 시점의 실제 시각으로만 채워진다 - 기간
    // 경계를 테스트할 때는 저장 전후로 now() 기준 여유를 둔 범위를 만들어 감싼다.
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
    void 기간_내_등록이_없으면_0을_반환한다() {
        save(1L);

        LocalDateTime pastStart = LocalDateTime.now().minusDays(10);
        LocalDateTime pastEnd = LocalDateTime.now().minusDays(5);

        assertThat(propertyRepository.countDistinctUserIdByCreatedAtBetween(pastStart, pastEnd)).isZero();
    }

    @Test
    void 기간_내_동일_유저가_여러_건_등록해도_1명으로_집계한다() {
        LocalDateTime rangeStart = LocalDateTime.now().minusMinutes(1);
        save(1L);
        save(1L);
        LocalDateTime rangeEnd = LocalDateTime.now().plusMinutes(1);

        assertThat(propertyRepository.countDistinctUserIdByCreatedAtBetween(rangeStart, rangeEnd)).isEqualTo(1);
    }

    @Test
    void 등록한_유저_수만큼_distinct하게_집계한다() {
        LocalDateTime rangeStart = LocalDateTime.now().minusMinutes(1);
        save(1L);
        save(2L);
        save(2L);
        save(3L);
        LocalDateTime rangeEnd = LocalDateTime.now().plusMinutes(1);

        assertThat(propertyRepository.countDistinctUserIdByCreatedAtBetween(rangeStart, rangeEnd)).isEqualTo(3);
    }

    @Test
    void 삭제된_매물도_기간_내_등록_이력이면_집계한다() {
        LocalDateTime rangeStart = LocalDateTime.now().minusMinutes(1);
        Property property = save(1L);
        property.delete();
        propertyRepository.save(property);
        LocalDateTime rangeEnd = LocalDateTime.now().plusMinutes(1);

        assertThat(propertyRepository.countDistinctUserIdByCreatedAtBetween(rangeStart, rangeEnd)).isEqualTo(1);
    }

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
