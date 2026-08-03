package com.algogyeyak.property.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.entity.PropertyType;
import com.algogyeyak.property.entity.TransactionType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

@DataJpaTest
class PropertyRepositoryTest {

    @Autowired
    private PropertyRepository propertyRepository;

    private Property newProperty(Long userId) {
        return Property.builder()
                .userId(userId)
                .propertyType(PropertyType.OFFICETEL)
                .transactionType(TransactionType.JEONSE)
                .deposit(10_000_000L)
                .area(20.0)
                .description("테스트 매물")
                .build();
    }

    @Test
    void 매물이_하나도_없으면_0을_반환한다() {
        assertThat(propertyRepository.countDistinctUserId()).isZero();
    }

    @Test
    void 동일_유저가_여러_건_등록해도_1명으로_집계한다() {
        propertyRepository.save(newProperty(1L));
        propertyRepository.save(newProperty(1L));

        assertThat(propertyRepository.countDistinctUserId()).isEqualTo(1);
    }

    @Test
    void 등록한_유저_수만큼_distinct하게_집계한다() {
        propertyRepository.save(newProperty(1L));
        propertyRepository.save(newProperty(2L));
        propertyRepository.save(newProperty(2L));
        propertyRepository.save(newProperty(3L));

        assertThat(propertyRepository.countDistinctUserId()).isEqualTo(3);
    }

    @Test
    void 삭제된_매물도_등록_이력으로_집계한다() {
        Property property = propertyRepository.save(newProperty(1L));
        property.delete();
        propertyRepository.save(property);

        assertThat(propertyRepository.countDistinctUserId()).isEqualTo(1);
    }
}
