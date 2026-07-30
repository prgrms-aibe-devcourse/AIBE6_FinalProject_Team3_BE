package com.algogyeyak.property.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.property.dto.PropertyReportRequest;
import com.algogyeyak.property.dto.PropertyReportResponse;
import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.entity.PropertyReportReason;
import com.algogyeyak.property.entity.PropertyType;
import com.algogyeyak.property.entity.TransactionType;
import com.algogyeyak.property.repository.PropertyReportRepository;
import com.algogyeyak.property.repository.PropertyRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PropertyReportServiceTest {

    @Mock
    private PropertyRepository propertyRepository;

    @Mock
    private PropertyReportRepository propertyReportRepository;

    private PropertyReportService propertyReportService;

    private static final Long USER_ID = 1L;
    private static final Long PROPERTY_ID = 101L;

    @BeforeEach
    void setUp() {
        propertyReportService = new PropertyReportService(propertyRepository, propertyReportRepository);
    }

    private Property ownedProperty(Long userId) {
        return Property.builder()
                .userId(userId)
                .propertyType(PropertyType.OFFICETEL)
                .transactionType(TransactionType.JEONSE)
                .deposit(30_000_000L)
                .monthlyRent(null)
                .area(23.5)
                .description(null)
                .build();
    }

    @Test
    void 본인_매물을_정상적으로_신고하면_접수상태로_저장된다() {
        Property property = ownedProperty(USER_ID);
        when(propertyRepository.findById(PROPERTY_ID)).thenReturn(Optional.of(property));
        when(propertyReportRepository.existsByPropertyIdAndReporterId(PROPERTY_ID, USER_ID)).thenReturn(false);
        when(propertyReportRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PropertyReportRequest request = new PropertyReportRequest(PropertyReportReason.PRICE_MISMATCH, null);

        PropertyReportResponse response = propertyReportService.report(USER_ID, PROPERTY_ID, request);

        assertThat(response.propertyId()).isEqualTo(PROPERTY_ID);
        assertThat(response.reason()).isEqualTo("PRICE_MISMATCH");
        assertThat(response.status()).isEqualTo("RECEIVED");
        assertThat(response.detail()).isNull();
    }

    @Test
    void ETC_사유이고_detail이_있으면_detail이_그대로_저장된다() {
        Property property = ownedProperty(USER_ID);
        when(propertyRepository.findById(PROPERTY_ID)).thenReturn(Optional.of(property));
        when(propertyReportRepository.existsByPropertyIdAndReporterId(PROPERTY_ID, USER_ID)).thenReturn(false);
        when(propertyReportRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PropertyReportRequest request = new PropertyReportRequest(PropertyReportReason.ETC, "사진과 실제 구조가 달라요");

        PropertyReportResponse response = propertyReportService.report(USER_ID, PROPERTY_ID, request);

        assertThat(response.reason()).isEqualTo("ETC");
        assertThat(response.detail()).isEqualTo("사진과 실제 구조가 달라요");
    }

    @Test
    void ETC가_아닌_사유에_detail을_보내도_저장시에는_null로_강제된다() {
        Property property = ownedProperty(USER_ID);
        when(propertyRepository.findById(PROPERTY_ID)).thenReturn(Optional.of(property));
        when(propertyReportRepository.existsByPropertyIdAndReporterId(PROPERTY_ID, USER_ID)).thenReturn(false);
        when(propertyReportRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PropertyReportRequest request = new PropertyReportRequest(PropertyReportReason.DUPLICATE, "무시되어야 할 값");

        PropertyReportResponse response = propertyReportService.report(USER_ID, PROPERTY_ID, request);

        assertThat(response.detail()).isNull();
    }

    @Test
    void reason이_없으면_예외가_발생한다() {
        Property property = ownedProperty(USER_ID);
        when(propertyRepository.findById(PROPERTY_ID)).thenReturn(Optional.of(property));

        PropertyReportRequest request = new PropertyReportRequest(null, null);

        assertThatThrownBy(() -> propertyReportService.report(USER_ID, PROPERTY_ID, request))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.REPORT_REASON_REQUIRED);
    }

    @Test
    void ETC_사유인데_detail이_없으면_예외가_발생한다() {
        Property property = ownedProperty(USER_ID);
        when(propertyRepository.findById(PROPERTY_ID)).thenReturn(Optional.of(property));

        PropertyReportRequest request = new PropertyReportRequest(PropertyReportReason.ETC, "  ");

        assertThatThrownBy(() -> propertyReportService.report(USER_ID, PROPERTY_ID, request))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.REPORT_DETAIL_REQUIRED);
    }

    @Test
    void 존재하지_않는_매물을_신고하면_예외가_발생한다() {
        when(propertyRepository.findById(999L)).thenReturn(Optional.empty());

        PropertyReportRequest request = new PropertyReportRequest(PropertyReportReason.PRICE_MISMATCH, null);

        assertThatThrownBy(() -> propertyReportService.report(USER_ID, 999L, request))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.PROPERTY_NOT_FOUND);
    }

    @Test
    void 삭제된_매물을_신고하면_예외가_발생한다() {
        Property property = ownedProperty(USER_ID);
        property.delete();
        when(propertyRepository.findById(PROPERTY_ID)).thenReturn(Optional.of(property));

        PropertyReportRequest request = new PropertyReportRequest(PropertyReportReason.PRICE_MISMATCH, null);

        assertThatThrownBy(() -> propertyReportService.report(USER_ID, PROPERTY_ID, request))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.PROPERTY_NOT_FOUND);
    }

    @Test
    void 본인_소유가_아닌_매물을_신고하면_예외가_발생한다() {
        Property property = ownedProperty(999L);
        when(propertyRepository.findById(PROPERTY_ID)).thenReturn(Optional.of(property));

        PropertyReportRequest request = new PropertyReportRequest(PropertyReportReason.PRICE_MISMATCH, null);

        assertThatThrownBy(() -> propertyReportService.report(USER_ID, PROPERTY_ID, request))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.PROPERTY_ACCESS_DENIED);
    }

    @Test
    void ETC_사유_detail이_500자를_넘으면_예외가_발생한다() {
        Property property = ownedProperty(USER_ID);
        when(propertyRepository.findById(PROPERTY_ID)).thenReturn(Optional.of(property));

        String tooLong = "가".repeat(501);
        PropertyReportRequest request = new PropertyReportRequest(PropertyReportReason.ETC, tooLong);

        assertThatThrownBy(() -> propertyReportService.report(USER_ID, PROPERTY_ID, request))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.REPORT_DETAIL_TOO_LONG);
    }

    @Test
    void ETC가_아닌_사유는_detail이_길어도_통과한다() {
        Property property = ownedProperty(USER_ID);
        when(propertyRepository.findById(PROPERTY_ID)).thenReturn(Optional.of(property));
        when(propertyReportRepository.existsByPropertyIdAndReporterId(PROPERTY_ID, USER_ID)).thenReturn(false);
        when(propertyReportRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // 어차피 엔티티 생성 시 null로 버려지는 값이라 길이 제한에 안 걸려야 한다.
        String tooLong = "가".repeat(501);
        PropertyReportRequest request = new PropertyReportRequest(PropertyReportReason.DUPLICATE, tooLong);

        PropertyReportResponse response = propertyReportService.report(USER_ID, PROPERTY_ID, request);

        assertThat(response.detail()).isNull();
    }

    @Test
    void 동일_매물을_중복_신고하면_예외가_발생한다() {
        Property property = ownedProperty(USER_ID);
        when(propertyRepository.findById(PROPERTY_ID)).thenReturn(Optional.of(property));
        when(propertyReportRepository.existsByPropertyIdAndReporterId(PROPERTY_ID, USER_ID)).thenReturn(true);

        PropertyReportRequest request = new PropertyReportRequest(PropertyReportReason.PRICE_MISMATCH, null);

        assertThatThrownBy(() -> propertyReportService.report(USER_ID, PROPERTY_ID, request))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.REPORT_DUPLICATE);
    }
}
