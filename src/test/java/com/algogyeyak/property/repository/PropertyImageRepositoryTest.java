package com.algogyeyak.property.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.entity.PropertyImage;
import com.algogyeyak.property.entity.PropertyType;
import com.algogyeyak.property.entity.TransactionType;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

@DataJpaTest
class PropertyImageRepositoryTest {

    @Autowired
    private PropertyImageRepository propertyImageRepository;

    @Autowired
    private PropertyRepository propertyRepository;

    private Property saveProperty() {
        return propertyRepository.save(Property.builder()
                .userId(1L)
                .title("테스트 매물")
                .propertyType(PropertyType.OFFICETEL)
                .transactionType(TransactionType.JEONSE)
                .deposit(10_000_000L)
                .area(20.0)
                .description("테스트 매물")
                .build());
    }

    @Test
    void findAllImageUrls는_매물에_상관없이_전체_이미지_URL을_반환한다() {
        Property property = saveProperty();
        property.addImage(PropertyImage.builder().imageUrl("https://cdn.example.com/a.jpg").build());
        property.addImage(PropertyImage.builder().imageUrl("https://cdn.example.com/b.jpg").build());
        propertyRepository.save(property);

        List<String> result = propertyImageRepository.findAllImageUrls();

        assertThat(result).containsExactlyInAnyOrder("https://cdn.example.com/a.jpg", "https://cdn.example.com/b.jpg");
    }

    @Test
    void 이미지가_없으면_빈_리스트를_반환한다() {
        saveProperty();

        assertThat(propertyImageRepository.findAllImageUrls()).isEmpty();
    }
}
