package com.algogyeyak.property.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.property.client.AddressResolutionResult;
import com.algogyeyak.property.client.KakaoAddressClient;
import com.algogyeyak.property.dto.PropertyRegisterRequest;
import com.algogyeyak.property.dto.PropertyRegisterResponse;
import com.algogyeyak.property.entity.PropertyStatus;
import com.algogyeyak.property.entity.PropertyType;
import com.algogyeyak.property.entity.TransactionType;
import com.algogyeyak.property.repository.PropertyRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PropertyServiceTest {

    @Mock
    private PropertyRepository propertyRepository;

    @Mock
    private KakaoAddressClient kakaoAddressClient;

    private PropertyService propertyService;

    private static final Long USER_ID = 1L;

    @BeforeEach
    void setUp() {
        propertyService = new PropertyService(propertyRepository, kakaoAddressClient);
    }

    private AddressResolutionResult resolvedAddress() {
        return AddressResolutionResult.builder()
                .resolved(true)
                .roadAddress("서울특별시 강남구 테헤란로 123")
                .jibunAddress("서울특별시 강남구 역삼동 123-45")
                .latitude(37.4995539438207)
                .longitude(127.031393491745)
                .build();
    }

    @Test
    void 전세_등록에_성공하면_주소와_상태가_포함된_응답을_반환한다() {
        PropertyRegisterRequest request = new PropertyRegisterRequest(
                "서울특별시 강남구 테헤란로 123",
                PropertyType.OFFICETEL,
                TransactionType.JEONSE,
                30_000_000L,
                null,
                23.5,
                "역세권 오피스텔",
                List.of("https://cdn.algogyeyak.com/img/abc.jpg")
        );

        when(kakaoAddressClient.resolve(anyString())).thenReturn(resolvedAddress());
        when(propertyRepository.existsByUserIdAndTransactionTypeAndStatusAndAddress_RoadAddress(
                eq(USER_ID), eq(TransactionType.JEONSE), eq(PropertyStatus.ACTIVE), anyString()
        )).thenReturn(false);
        when(propertyRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PropertyRegisterResponse response = propertyService.register(USER_ID, request);

        assertThat(response.status()).isEqualTo("ACTIVE");
        assertThat(response.address().roadAddress()).isEqualTo("서울특별시 강남구 테헤란로 123");
        assertThat(response.address().latitude()).isEqualTo(37.4995539438207);
        assertThat(response.marketComparison().status()).isEqualTo("UNAVAILABLE");
        assertThat(response.notice()).isNull();
    }

    @Test
    void 주소_확인에_실패하면_예외가_발생한다() {
        PropertyRegisterRequest request = new PropertyRegisterRequest(
                "존재하지 않는 주소",
                PropertyType.OFFICETEL,
                TransactionType.JEONSE,
                30_000_000L,
                null,
                23.5,
                null,
                null
        );

        when(kakaoAddressClient.resolve(anyString())).thenReturn(AddressResolutionResult.unresolved());

        assertThatThrownBy(() -> propertyService.register(USER_ID, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("주소");
    }

    @Test
    void 동일_조건으로_중복_등록하면_예외가_발생한다() {
        PropertyRegisterRequest request = new PropertyRegisterRequest(
                "서울특별시 강남구 테헤란로 123",
                PropertyType.OFFICETEL,
                TransactionType.JEONSE,
                30_000_000L,
                null,
                23.5,
                null,
                null
        );

        when(kakaoAddressClient.resolve(anyString())).thenReturn(resolvedAddress());
        when(propertyRepository.existsByUserIdAndTransactionTypeAndStatusAndAddress_RoadAddress(
                eq(USER_ID), eq(TransactionType.JEONSE), eq(PropertyStatus.ACTIVE), anyString()
        )).thenReturn(true);

        assertThatThrownBy(() -> propertyService.register(USER_ID, request))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 월세인데_월임대료가_없으면_예외가_발생한다() {
        PropertyRegisterRequest request = new PropertyRegisterRequest(
                "서울특별시 강남구 테헤란로 123",
                PropertyType.OFFICETEL,
                TransactionType.MONTHLY_RENT,
                5_000_000L,
                null,
                23.5,
                null,
                null
        );

        assertThatThrownBy(() -> propertyService.register(USER_ID, request))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 전세인데_월임대료를_입력하면_예외가_발생한다() {
        PropertyRegisterRequest request = new PropertyRegisterRequest(
                "서울특별시 강남구 테헤란로 123",
                PropertyType.OFFICETEL,
                TransactionType.JEONSE,
                30_000_000L,
                500_000L,
                23.5,
                null,
                null
        );

        assertThatThrownBy(() -> propertyService.register(USER_ID, request))
                .isInstanceOf(BusinessException.class);
    }
}
