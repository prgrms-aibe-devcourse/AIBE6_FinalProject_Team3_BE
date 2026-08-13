package com.algogyeyak.property.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.algogyeyak.checklist.repository.ChecklistItemRepository;
import com.algogyeyak.checklist.repository.ChecklistItemRepository.ChecklistProgressProjection;
import com.algogyeyak.checklist.repository.ChecklistRepository;
import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.global.response.PageResponse;
import com.algogyeyak.marketdata.dto.MarketComparisonResponse;
import com.algogyeyak.marketdata.dto.MarketComparisonUnavailableReason;
import com.algogyeyak.marketdata.service.MarketComparisonService;
import com.algogyeyak.property.client.AddressResolutionResult;
import com.algogyeyak.property.client.KakaoAddressClient;
import com.algogyeyak.property.dto.PropertyDetailResponse;
import com.algogyeyak.property.dto.PropertyImageRequest;
import com.algogyeyak.property.dto.PropertyListResponse;
import com.algogyeyak.property.dto.PropertyRegisterRequest;
import com.algogyeyak.property.dto.PropertyRegisterResponse;
import com.algogyeyak.property.dto.PropertySearchCondition;
import com.algogyeyak.property.dto.PropertyUpdateRequest;
import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.entity.PropertyAddress;
import com.algogyeyak.property.entity.PropertyStatus;
import com.algogyeyak.property.entity.PropertyType;
import com.algogyeyak.property.entity.TransactionType;
import com.algogyeyak.property.event.PropertyUpdatedEvent;
import com.algogyeyak.property.repository.PropertyReportRepository;
import com.algogyeyak.property.repository.PropertyRepository;
import com.algogyeyak.riskanalysis.repository.DepositSafetyCheckRepository;
import com.algogyeyak.riskanalysis.repository.PropertyRiskCheckRepository;
import com.algogyeyak.riskanalysis.repository.PropertyRiskRepository;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PropertyServiceTest {

    @Mock
    private PropertyRepository propertyRepository;

    @Mock
    private KakaoAddressClient kakaoAddressClient;

    @Mock
    private MarketComparisonService marketComparisonService;

    @Mock
    private ChecklistRepository checklistRepository;

    @Mock
    private ChecklistItemRepository checklistItemRepository;

    @Mock
    private PropertyReportRepository propertyReportRepository;

    @Mock
    private PropertyRiskRepository propertyRiskRepository;

    @Mock
    private PropertyRiskCheckRepository propertyRiskCheckRepository;

    @Mock
    private DepositSafetyCheckRepository depositSafetyCheckRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private PropertyService propertyService;

    private static final Long USER_ID = 1L;

    @BeforeEach
    void setUp() {
        propertyService = new PropertyService(
                propertyRepository, kakaoAddressClient, marketComparisonService,
                checklistRepository, checklistItemRepository, propertyReportRepository,
                propertyRiskRepository, propertyRiskCheckRepository, depositSafetyCheckRepository,
                eventPublisher
        );
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
                "테스트 매물",
                "서울특별시 강남구 테헤란로 123",
                PropertyType.OFFICETEL,
                TransactionType.JEONSE,
                30_000_000L,
                null,
                23.5,
                null,
                "역세권 오피스텔",
                List.of(new PropertyImageRequest("https://cdn.algogyeyak.com/img/abc.jpg", null))
        );

        when(kakaoAddressClient.resolve(anyString())).thenReturn(resolvedAddress());
        when(propertyRepository.existsByUserIdAndTransactionTypeAndStatusAndAddress_RoadAddress(
                eq(USER_ID), eq(TransactionType.JEONSE), eq(PropertyStatus.ACTIVE), anyString()
        )).thenReturn(false);
        when(propertyRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(marketComparisonService.compare(any())).thenReturn(MarketComparisonResponse.unavailable(MarketComparisonUnavailableReason.INSUFFICIENT_SAMPLE, "stub"));

        PropertyRegisterResponse response = propertyService.register(USER_ID, request);

        assertThat(response.status()).isEqualTo("ACTIVE");
        assertThat(response.address().roadAddress()).isEqualTo("서울특별시 강남구 테헤란로 123");
        assertThat(response.address().latitude()).isEqualTo(37.4995539438207);
        assertThat(response.marketComparison().status()).isEqualTo("UNAVAILABLE");
        assertThat(response.notice()).isNull();
        // 등록 직후에도 update()와 동일하게 위험 신호·전세가율 계산을 risk-analysis에 위임한다 - 이
        // 이벤트가 없으면 등록 직후 목록/상세에서 checkSignalCount/jeonseRatio가 계속 null로 남는다.
        verify(eventPublisher).publishEvent(any(PropertyUpdatedEvent.class));
    }

    @Test
    void 관리비를_입력하면_등록_응답에_반영된다() {
        PropertyRegisterRequest request = new PropertyRegisterRequest(
                "테스트 매물",
                "서울특별시 강남구 테헤란로 123",
                PropertyType.OFFICETEL,
                TransactionType.JEONSE,
                30_000_000L,
                null,
                23.5,
                150_000L,
                "역세권 오피스텔",
                null
        );

        when(kakaoAddressClient.resolve(anyString())).thenReturn(resolvedAddress());
        when(propertyRepository.existsByUserIdAndTransactionTypeAndStatusAndAddress_RoadAddress(
                eq(USER_ID), eq(TransactionType.JEONSE), eq(PropertyStatus.ACTIVE), anyString()
        )).thenReturn(false);
        when(propertyRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(marketComparisonService.compare(any())).thenReturn(MarketComparisonResponse.unavailable(MarketComparisonUnavailableReason.INSUFFICIENT_SAMPLE, "stub"));

        propertyService.register(USER_ID, request);

        org.mockito.ArgumentCaptor<Property> captor = org.mockito.ArgumentCaptor.forClass(Property.class);
        verify(propertyRepository).save(captor.capture());
        assertThat(captor.getValue().getMaintenanceFee()).isEqualTo(150_000L);
    }

    @Test
    void 주소_확인에_실패하면_예외가_발생한다() {
        PropertyRegisterRequest request = new PropertyRegisterRequest(
                "테스트 매물",
                "존재하지 않는 주소",
                PropertyType.OFFICETEL,
                TransactionType.JEONSE,
                30_000_000L,
                null,
                23.5,
                null,
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
                "테스트 매물",
                "서울특별시 강남구 테헤란로 123",
                PropertyType.OFFICETEL,
                TransactionType.JEONSE,
                30_000_000L,
                null,
                23.5,
                null,
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
    void 도로명주소가_없으면_지번주소로_중복_등록을_체크한다() {
        PropertyRegisterRequest request = new PropertyRegisterRequest(
                "테스트 매물",
                "서울특별시 종로구 충신동 1",
                PropertyType.DETACHED_HOUSE,
                TransactionType.JEONSE,
                200_000_000L,
                null,
                50.0,
                null,
                null,
                null
        );

        AddressResolutionResult jibunOnly = AddressResolutionResult.builder()
                .resolved(true)
                .roadAddress(null)
                .jibunAddress("서울특별시 종로구 충신동 1")
                .latitude(37.5735)
                .longitude(127.0083)
                .build();

        when(kakaoAddressClient.resolve(anyString())).thenReturn(jibunOnly);
        when(propertyRepository.existsByUserIdAndTransactionTypeAndStatusAndAddress_JibunAddress(
                eq(USER_ID), eq(TransactionType.JEONSE), eq(PropertyStatus.ACTIVE), eq("서울특별시 종로구 충신동 1")
        )).thenReturn(true);

        assertThatThrownBy(() -> propertyService.register(USER_ID, request))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 연립다세대이면서_도로명주소가_없으면_매칭정확도_안내문구가_포함된다() {
        PropertyRegisterRequest request = new PropertyRegisterRequest(
                "테스트 매물",
                "서울특별시 종로구 청운동 1",
                PropertyType.MULTI_FAMILY,
                TransactionType.JEONSE,
                200_000_000L,
                null,
                50.0,
                null,
                null,
                null
        );

        AddressResolutionResult jibunOnly = AddressResolutionResult.builder()
                .resolved(true)
                .roadAddress(null)
                .jibunAddress("서울특별시 종로구 청운동 1")
                .latitude(37.5865)
                .longitude(126.9689)
                .build();

        when(kakaoAddressClient.resolve(anyString())).thenReturn(jibunOnly);
        when(propertyRepository.existsByUserIdAndTransactionTypeAndStatusAndAddress_JibunAddress(
                eq(USER_ID), eq(TransactionType.JEONSE), eq(PropertyStatus.ACTIVE), anyString()
        )).thenReturn(false);
        when(propertyRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(marketComparisonService.compare(any())).thenReturn(MarketComparisonResponse.unavailable(MarketComparisonUnavailableReason.INSUFFICIENT_SAMPLE, "stub"));

        PropertyRegisterResponse response = propertyService.register(USER_ID, request);

        assertThat(response.notice()).contains("연립다세대");
    }

    @Test
    void 허용되지_않은_확장자의_이미지면_예외가_발생한다() {
        PropertyRegisterRequest request = new PropertyRegisterRequest(
                "테스트 매물",
                "서울특별시 강남구 테헤란로 123",
                PropertyType.OFFICETEL,
                TransactionType.JEONSE,
                30_000_000L,
                null,
                23.5,
                null,
                null,
                List.of(new PropertyImageRequest("https://cdn.algogyeyak.com/img/abc.bmp", null))
        );

        assertThatThrownBy(() -> propertyService.register(USER_ID, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PROPERTY_IMAGE_INVALID);
    }

    @Test
    void 이미지_URL이_http_https가_아니면_예외가_발생한다() {
        PropertyRegisterRequest request = new PropertyRegisterRequest(
                "테스트 매물",
                "서울특별시 강남구 테헤란로 123",
                PropertyType.OFFICETEL,
                TransactionType.JEONSE,
                30_000_000L,
                null,
                23.5,
                null,
                null,
                List.of(new PropertyImageRequest("ftp://cdn.algogyeyak.com/img/abc.jpg", null))
        );

        assertThatThrownBy(() -> propertyService.register(USER_ID, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PROPERTY_IMAGE_INVALID);
    }

    @Test
    void 이미지가_10장을_초과하면_예외가_발생한다() {
        List<PropertyImageRequest> tooManyImages = IntStream.range(0, 11)
                .mapToObj(i -> new PropertyImageRequest("https://cdn.algogyeyak.com/img/" + i + ".jpg", null))
                .toList();
        PropertyRegisterRequest request = new PropertyRegisterRequest(
                "테스트 매물",
                "서울특별시 강남구 테헤란로 123",
                PropertyType.OFFICETEL,
                TransactionType.JEONSE,
                30_000_000L,
                null,
                23.5,
                null,
                null,
                tooManyImages
        );

        assertThatThrownBy(() -> propertyService.register(USER_ID, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PROPERTY_IMAGE_INVALID);
    }

    @Test
    void 월세인데_월임대료가_없으면_예외가_발생한다() {
        PropertyRegisterRequest request = new PropertyRegisterRequest(
                "테스트 매물",
                "서울특별시 강남구 테헤란로 123",
                PropertyType.OFFICETEL,
                TransactionType.MONTHLY_RENT,
                5_000_000L,
                null,
                23.5,
                null,
                null,
                null
        );

        assertThatThrownBy(() -> propertyService.register(USER_ID, request))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 전세인데_월임대료를_입력하면_예외가_발생한다() {
        PropertyRegisterRequest request = new PropertyRegisterRequest(
                "테스트 매물",
                "서울특별시 강남구 테헤란로 123",
                PropertyType.OFFICETEL,
                TransactionType.JEONSE,
                30_000_000L,
                500_000L,
                23.5,
                null,
                null,
                null
        );

        assertThatThrownBy(() -> propertyService.register(USER_ID, request))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 매물_목록조회는_본인_소유_ACTIVE_매물만_최신순으로_반환한다() {
        Property property = Property.builder()
                .userId(USER_ID)
                .title("테스트 매물")
                .propertyType(PropertyType.OFFICETEL)
                .transactionType(TransactionType.JEONSE)
                .deposit(30_000_000L)
                .monthlyRent(null)
                .area(23.5)
                .maintenanceFee(50_000L)
                .description("역세권 오피스텔")
                .build();
        PropertyAddress address = PropertyAddress.builder()
                .roadAddress("서울특별시 강남구 테헤란로 123")
                .jibunAddress("서울특별시 강남구 역삼동 123-45")
                .latitude(37.4995539438207)
                .longitude(127.031393491745)
                .build();
        property.assignAddress(address);

        Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
        when(propertyRepository.search(
                eq(USER_ID), eq(PropertyStatus.ACTIVE),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                eq(pageable)
        )).thenReturn(new PageImpl<>(List.of(property), pageable, 1));

        PageResponse<PropertyListResponse> result =
                propertyService.getMyProperties(USER_ID, pageable, PropertySearchCondition.empty());

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).roadAddress()).isEqualTo("서울특별시 강남구 테헤란로 123");
        assertThat(result.content().get(0).maintenanceFee()).isEqualTo(50_000L);
        assertThat(result.content().get(0).transactionType()).isEqualTo("JEONSE");
        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.hasNext()).isFalse();
    }

    @Test
    void 매물_목록조회_응답에_매물별_시세비교_결과가_포함된다() {
        Property property = Property.builder()
                .userId(USER_ID)
                .title("테스트 매물")
                .propertyType(PropertyType.OFFICETEL)
                .transactionType(TransactionType.JEONSE)
                .deposit(30_000_000L)
                .monthlyRent(null)
                .area(23.5)
                .build();
        ReflectionTestUtils.setField(property, "id", 1L);

        Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
        when(propertyRepository.search(
                eq(USER_ID), eq(PropertyStatus.ACTIVE),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                eq(pageable)
        )).thenReturn(new PageImpl<>(List.of(property), pageable, 1));

        MarketComparisonResponse comparison = MarketComparisonResponse.available(
                28_000_000L, 0.07, 5, "2026-06-20", 300
        );
        when(marketComparisonService.compare(property)).thenReturn(comparison);

        PageResponse<PropertyListResponse> result =
                propertyService.getMyProperties(USER_ID, pageable, PropertySearchCondition.empty());

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).marketComparison()).isEqualTo(comparison);
    }

    @Test
    void 매물_목록조회_응답에_체크리스트_진행률이_매물별로_포함된다() {
        Property propertyWithChecklist = Property.builder()
                .userId(USER_ID)
                .title("테스트 매물")
                .propertyType(PropertyType.OFFICETEL)
                .transactionType(TransactionType.JEONSE)
                .deposit(30_000_000L)
                .monthlyRent(null)
                .area(23.5)
                .build();
        ReflectionTestUtils.setField(propertyWithChecklist, "id", 1L);

        Property propertyWithoutChecklist = Property.builder()
                .userId(USER_ID)
                .title("테스트 매물")
                .propertyType(PropertyType.OFFICETEL)
                .transactionType(TransactionType.JEONSE)
                .deposit(20_000_000L)
                .monthlyRent(null)
                .area(18.0)
                .build();
        ReflectionTestUtils.setField(propertyWithoutChecklist, "id", 2L);

        Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
        when(propertyRepository.search(
                eq(USER_ID), eq(PropertyStatus.ACTIVE),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                eq(pageable)
        )).thenReturn(new PageImpl<>(List.of(propertyWithChecklist, propertyWithoutChecklist), pageable, 2));

        // 문항 4개 중 3개 체크됨 -> 75%. propertyId 2번은 체크리스트가 아예 없어(집계 자체가 안 잡힘)
        // checklistProgress가 null이어야 한다.
        ChecklistProgressProjection progress = mock(ChecklistProgressProjection.class);
        when(progress.getPropertyId()).thenReturn(1L);
        when(progress.getTotalCount()).thenReturn(4L);
        when(progress.getCheckedCount()).thenReturn(3L);
        when(checklistItemRepository.findProgressByUserId(USER_ID)).thenReturn(List.of(progress));

        PageResponse<PropertyListResponse> result =
                propertyService.getMyProperties(USER_ID, pageable, PropertySearchCondition.empty());

        assertThat(result.content()).hasSize(2);
        assertThat(result.content().get(0).checklistProgress()).isEqualTo(75);
        assertThat(result.content().get(1).checklistProgress()).isNull();
    }

    @Test
    void 매물_목록조회_응답에_확인_필요_신호_개수와_요약이_매물별로_포함된다() {
        Property checkedWithRisks = Property.builder()
                .userId(USER_ID).title("리스크 있는 매물").propertyType(PropertyType.OFFICETEL)
                .transactionType(TransactionType.JEONSE).deposit(30_000_000L).area(23.5).build();
        ReflectionTestUtils.setField(checkedWithRisks, "id", 1L);

        Property checkedClean = Property.builder()
                .userId(USER_ID).title("이상 없는 매물").propertyType(PropertyType.OFFICETEL)
                .transactionType(TransactionType.JEONSE).deposit(20_000_000L).area(18.0).build();
        ReflectionTestUtils.setField(checkedClean, "id", 2L);

        Property neverChecked = Property.builder()
                .userId(USER_ID).title("미검사 매물").propertyType(PropertyType.OFFICETEL)
                .transactionType(TransactionType.JEONSE).deposit(15_000_000L).area(15.0).build();
        ReflectionTestUtils.setField(neverChecked, "id", 3L);

        Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
        when(propertyRepository.search(
                eq(USER_ID), eq(PropertyStatus.ACTIVE),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                eq(pageable)
        )).thenReturn(new PageImpl<>(List.of(checkedWithRisks, checkedClean, neverChecked), pageable, 3));

        // checkedWithRisks/checkedClean은 4종 신호 판정이 이미 돌았다는 것만 표시하면 되므로 신호 하나씩만 둔다.
        when(propertyRiskCheckRepository.findAllByProperty_UserId(USER_ID)).thenReturn(List.of(
                com.algogyeyak.riskanalysis.entity.PropertyRiskCheck.success(
                        checkedWithRisks, com.algogyeyak.riskanalysis.enums.RiskSignalType.PRICE_ANOMALY, "v1.0"),
                com.algogyeyak.riskanalysis.entity.PropertyRiskCheck.success(
                        checkedClean, com.algogyeyak.riskanalysis.enums.RiskSignalType.PRICE_ANOMALY, "v1.0")
        ));
        when(propertyRiskRepository.findAllByProperty_UserId(USER_ID)).thenReturn(List.of(
                com.algogyeyak.riskanalysis.entity.PropertyRisk.of(
                        checkedWithRisks, com.algogyeyak.riskanalysis.enums.RiskSignalType.PRICE_ANOMALY, "시세 대비 높은 가격"),
                com.algogyeyak.riskanalysis.entity.PropertyRisk.of(
                        checkedWithRisks, com.algogyeyak.riskanalysis.enums.RiskSignalType.DUPLICATE_LISTING, "유사 주소 중복")
        ));

        PageResponse<PropertyListResponse> result =
                propertyService.getMyProperties(USER_ID, pageable, PropertySearchCondition.empty());

        assertThat(result.content()).hasSize(3);
        assertThat(result.content().get(0).checkSignalCount()).isEqualTo(2);
        assertThat(result.content().get(0).signalSummary()).isEqualTo("시세 대비 높은 가격, 유사 주소 중복");
        assertThat(result.content().get(1).checkSignalCount()).isEqualTo(0);
        assertThat(result.content().get(1).signalSummary()).isNull();
        assertThat(result.content().get(2).checkSignalCount()).isNull();
        assertThat(result.content().get(2).signalSummary()).isNull();
    }

    @Test
    void 매물_목록조회_응답에_전세가율이_계산완료된_매물만_포함된다() {
        Property calculated = Property.builder()
                .userId(USER_ID).title("전세가율 계산된 매물").propertyType(PropertyType.OFFICETEL)
                .transactionType(TransactionType.JEONSE).deposit(30_000_000L).area(23.5).build();
        ReflectionTestUtils.setField(calculated, "id", 1L);

        Property unavailable = Property.builder()
                .userId(USER_ID).title("판정불가 매물").propertyType(PropertyType.OFFICETEL)
                .transactionType(TransactionType.MONTHLY_RENT).deposit(10_000_000L).monthlyRent(500_000L).area(18.0).build();
        ReflectionTestUtils.setField(unavailable, "id", 2L);

        Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
        when(propertyRepository.search(
                eq(USER_ID), eq(PropertyStatus.ACTIVE),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                eq(pageable)
        )).thenReturn(new PageImpl<>(List.of(calculated, unavailable), pageable, 2));

        when(depositSafetyCheckRepository.findAllByProperty_UserId(USER_ID)).thenReturn(List.of(
                com.algogyeyak.riskanalysis.entity.DepositSafetyCheck.calculated(
                        calculated, new java.math.BigDecimal("85.00"), null, null,
                        java.time.LocalDate.of(2026, 6, 20), "설명", 5, 300, "v1.0"),
                com.algogyeyak.riskanalysis.entity.DepositSafetyCheck.unavailable(
                        unavailable, null, null,
                        com.algogyeyak.riskanalysis.enums.DepositSafetyCheckReason.TRANSACTION_TYPE_UNSUPPORTED, "v1.0")
        ));

        PageResponse<PropertyListResponse> result =
                propertyService.getMyProperties(USER_ID, pageable, PropertySearchCondition.empty());

        assertThat(result.content()).hasSize(2);
        assertThat(result.content().get(0).jeonseRatio()).isEqualTo(85);
        assertThat(result.content().get(1).jeonseRatio()).isNull();
    }

    @Test
    void 허용되지_않은_정렬필드로_목록조회하면_예외가_발생한다() {
        Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "userId"));

        assertThatThrownBy(() -> propertyService.getMyProperties(USER_ID, pageable, PropertySearchCondition.empty()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_SORT_FIELD);
    }

    @Test
    void 페이지_크기가_최대치를_초과하면_예외가_발생한다() {
        Pageable pageable = PageRequest.of(0, 101, Sort.by(Sort.Direction.DESC, "createdAt"));

        assertThatThrownBy(() -> propertyService.getMyProperties(USER_ID, pageable, PropertySearchCondition.empty()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void 지역_검색어로_필터링하면_repository_search에_region이_전달된다() {
        Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
        PropertySearchCondition condition = new PropertySearchCondition(
                "역삼동", null, null, null, null, null, null, null, null
        );
        when(propertyRepository.search(
                eq(USER_ID), eq(PropertyStatus.ACTIVE),
                eq("역삼동"), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                eq(pageable)
        )).thenReturn(new PageImpl<>(List.of(), pageable, 0));

        PageResponse<PropertyListResponse> result = propertyService.getMyProperties(USER_ID, pageable, condition);

        assertThat(result.content()).isEmpty();
    }

    @Test
    void 면적_거래유형_매물유형_보증금_조건이_repository_search에_그대로_전달된다() {
        Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
        PropertySearchCondition condition = new PropertySearchCondition(
                null, 20.0, 30.0, TransactionType.JEONSE, PropertyType.OFFICETEL,
                10_000_000L, 50_000_000L, null, null
        );
        when(propertyRepository.search(
                eq(USER_ID), eq(PropertyStatus.ACTIVE),
                isNull(), eq(20.0), eq(30.0),
                eq(TransactionType.JEONSE), eq(PropertyType.OFFICETEL),
                eq(10_000_000L), eq(50_000_000L), isNull(), isNull(),
                eq(pageable)
        )).thenReturn(new PageImpl<>(List.of(), pageable, 0));

        PageResponse<PropertyListResponse> result = propertyService.getMyProperties(USER_ID, pageable, condition);

        assertThat(result.content()).isEmpty();
    }

    @Test
    void 월세_범위_조건이_repository_search에_그대로_전달된다() {
        Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
        PropertySearchCondition condition = new PropertySearchCondition(
                null, null, null, TransactionType.MONTHLY_RENT, null, null, null, 300_000L, 800_000L
        );
        when(propertyRepository.search(
                eq(USER_ID), eq(PropertyStatus.ACTIVE),
                isNull(), isNull(), isNull(),
                eq(TransactionType.MONTHLY_RENT), isNull(),
                isNull(), isNull(), eq(300_000L), eq(800_000L),
                eq(pageable)
        )).thenReturn(new PageImpl<>(List.of(), pageable, 0));

        PageResponse<PropertyListResponse> result = propertyService.getMyProperties(USER_ID, pageable, condition);

        assertThat(result.content()).isEmpty();
    }

    @Test
    void 면적_최소값이_최대값보다_크면_예외가_발생한다() {
        Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
        PropertySearchCondition condition = new PropertySearchCondition(
                null, 30.0, 20.0, null, null, null, null, null, null
        );

        assertThatThrownBy(() -> propertyService.getMyProperties(USER_ID, pageable, condition))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PROPERTY_INVALID_SEARCH_CONDITION);
    }

    @Test
    void 보증금_최소값이_최대값보다_크면_예외가_발생한다() {
        Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
        PropertySearchCondition condition = new PropertySearchCondition(
                null, null, null, null, null, 50_000_000L, 10_000_000L, null, null
        );

        assertThatThrownBy(() -> propertyService.getMyProperties(USER_ID, pageable, condition))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PROPERTY_INVALID_SEARCH_CONDITION);
    }

    @Test
    void 월세_최소값이_최대값보다_크면_예외가_발생한다() {
        Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
        PropertySearchCondition condition = new PropertySearchCondition(
                null, null, null, null, null, null, null, 800_000L, 300_000L
        );

        assertThatThrownBy(() -> propertyService.getMyProperties(USER_ID, pageable, condition))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PROPERTY_INVALID_SEARCH_CONDITION);
    }

    @Test
    void 매물_상세조회에_성공하면_설명과_주소가_포함된_응답을_반환한다() {
        Property property = Property.builder()
                .userId(USER_ID)
                .title("테스트 매물")
                .propertyType(PropertyType.OFFICETEL)
                .transactionType(TransactionType.JEONSE)
                .deposit(30_000_000L)
                .monthlyRent(null)
                .area(23.5)
                .maintenanceFee(80_000L)
                .description("역세권 오피스텔")
                .build();
        property.assignAddress(resolvedPropertyAddress());

        when(propertyRepository.findById(1L)).thenReturn(Optional.of(property));
        when(marketComparisonService.compare(any())).thenReturn(MarketComparisonResponse.unavailable(MarketComparisonUnavailableReason.INSUFFICIENT_SAMPLE, "stub"));
        when(checklistRepository.findByPropertyId(1L)).thenReturn(Optional.empty());
        when(propertyReportRepository.existsByPropertyIdAndReporterId(1L, USER_ID)).thenReturn(false);

        PropertyDetailResponse response = propertyService.getProperty(USER_ID, 1L);

        assertThat(response.description()).isEqualTo("역세권 오피스텔");
        assertThat(response.address().roadAddress()).isEqualTo("서울특별시 강남구 테헤란로 123");
        assertThat(response.marketComparison().status()).isEqualTo("UNAVAILABLE");
        assertThat(response.checklistCreated()).isFalse();
        assertThat(response.reported()).isFalse();
        assertThat(response.maintenanceFee()).isEqualTo(80_000L);
    }

    @Test
    void 매물_상세조회_응답에_체크리스트_생성여부와_신고여부가_반영된다() {
        Property property = Property.builder()
                .userId(USER_ID)
                .title("테스트 매물")
                .propertyType(PropertyType.OFFICETEL)
                .transactionType(TransactionType.JEONSE)
                .deposit(30_000_000L)
                .monthlyRent(null)
                .area(23.5)
                .description(null)
                .build();
        property.assignAddress(resolvedPropertyAddress());

        when(propertyRepository.findById(1L)).thenReturn(Optional.of(property));
        when(marketComparisonService.compare(any())).thenReturn(MarketComparisonResponse.unavailable(MarketComparisonUnavailableReason.INSUFFICIENT_SAMPLE, "stub"));
        when(checklistRepository.findByPropertyId(1L))
                .thenReturn(Optional.of(com.algogyeyak.checklist.entity.Checklist.builder().build()));
        when(propertyReportRepository.existsByPropertyIdAndReporterId(1L, USER_ID)).thenReturn(true);

        PropertyDetailResponse response = propertyService.getProperty(USER_ID, 1L);

        assertThat(response.checklistCreated()).isTrue();
        assertThat(response.reported()).isTrue();
    }

    @Test
    void 존재하지_않는_매물을_상세조회하면_예외가_발생한다() {
        when(propertyRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> propertyService.getProperty(USER_ID, 999L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 본인_소유가_아닌_매물을_상세조회하면_예외가_발생한다() {
        Long otherUserId = 999L;
        Property property = Property.builder()
                .userId(otherUserId)
                .title("테스트 매물")
                .propertyType(PropertyType.OFFICETEL)
                .transactionType(TransactionType.JEONSE)
                .deposit(30_000_000L)
                .monthlyRent(null)
                .area(23.5)
                .description(null)
                .build();
        property.assignAddress(resolvedPropertyAddress());

        when(propertyRepository.findById(1L)).thenReturn(Optional.of(property));

        assertThatThrownBy(() -> propertyService.getProperty(USER_ID, 1L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 매물_수정에_성공하면_변경된_가격과_설명이_반영된_응답을_반환한다() {
        Property property = Property.builder()
                .userId(USER_ID)
                .title("테스트 매물")
                .propertyType(PropertyType.OFFICETEL)
                .transactionType(TransactionType.JEONSE)
                .deposit(30_000_000L)
                .monthlyRent(null)
                .area(23.5)
                .description("역세권 오피스텔")
                .build();
        property.assignAddress(resolvedPropertyAddress());

        when(propertyRepository.findById(1L)).thenReturn(Optional.of(property));
        when(marketComparisonService.compare(any())).thenReturn(MarketComparisonResponse.unavailable(MarketComparisonUnavailableReason.INSUFFICIENT_SAMPLE, "stub"));

        PropertyUpdateRequest request = new PropertyUpdateRequest("테스트 매물", 35_000_000L, null, 25.0, null, "수정된 설명", null);

        PropertyDetailResponse response = propertyService.update(USER_ID, 1L, request);

        assertThat(response.deposit()).isEqualTo(35_000_000L);
        assertThat(response.area()).isEqualTo(25.0);
        assertThat(response.description()).isEqualTo("수정된 설명");
        // 가격/면적이 바뀌었으니 예전 시세비교 결과 캐시를 비우고 재계산해야 한다 - 안 비우면
        // 캐시 히트로 수정 전 결과가 그대로 반환된다.
        verify(marketComparisonService).evictCache(1L);
        // risk-analysis가 위험 신호·전세가율을 재계산할 수 있도록 이벤트를 발행한다 - property는
        // risk-analysis를 직접 참조하지 않고 이벤트로만 알린다(도메인 결합 방지).
        verify(eventPublisher).publishEvent(new PropertyUpdatedEvent(1L));
    }

    @Test
    void 관리비를_수정하면_변경된_값이_반영된_응답을_반환한다() {
        Property property = Property.builder()
                .userId(USER_ID)
                .title("테스트 매물")
                .propertyType(PropertyType.OFFICETEL)
                .transactionType(TransactionType.JEONSE)
                .deposit(30_000_000L)
                .monthlyRent(null)
                .area(23.5)
                .maintenanceFee(100_000L)
                .description("역세권 오피스텔")
                .build();
        property.assignAddress(resolvedPropertyAddress());

        when(propertyRepository.findById(1L)).thenReturn(Optional.of(property));
        when(marketComparisonService.compare(any())).thenReturn(MarketComparisonResponse.unavailable(MarketComparisonUnavailableReason.INSUFFICIENT_SAMPLE, "stub"));

        PropertyUpdateRequest request = new PropertyUpdateRequest("테스트 매물", 30_000_000L, null, 23.5, 200_000L, "역세권 오피스텔", null);

        PropertyDetailResponse response = propertyService.update(USER_ID, 1L, request);

        assertThat(response.maintenanceFee()).isEqualTo(200_000L);
    }

    @Test
    void 존재하지_않는_매물을_수정하면_예외가_발생한다() {
        when(propertyRepository.findById(999L)).thenReturn(Optional.empty());

        PropertyUpdateRequest request = new PropertyUpdateRequest("테스트 매물", 35_000_000L, null, 25.0, null, null, null);

        assertThatThrownBy(() -> propertyService.update(USER_ID, 999L, request))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 본인_소유가_아닌_매물을_수정하면_예외가_발생한다() {
        Long otherUserId = 999L;
        Property property = Property.builder()
                .userId(otherUserId)
                .title("테스트 매물")
                .propertyType(PropertyType.OFFICETEL)
                .transactionType(TransactionType.JEONSE)
                .deposit(30_000_000L)
                .monthlyRent(null)
                .area(23.5)
                .description(null)
                .build();
        property.assignAddress(resolvedPropertyAddress());

        when(propertyRepository.findById(1L)).thenReturn(Optional.of(property));

        PropertyUpdateRequest request = new PropertyUpdateRequest("테스트 매물", 35_000_000L, null, 25.0, null, null, null);

        assertThatThrownBy(() -> propertyService.update(USER_ID, 1L, request))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 전세_매물을_수정하면서_월임대료를_입력하면_예외가_발생한다() {
        Property property = Property.builder()
                .userId(USER_ID)
                .title("테스트 매물")
                .propertyType(PropertyType.OFFICETEL)
                .transactionType(TransactionType.JEONSE)
                .deposit(30_000_000L)
                .monthlyRent(null)
                .area(23.5)
                .description(null)
                .build();
        property.assignAddress(resolvedPropertyAddress());

        when(propertyRepository.findById(1L)).thenReturn(Optional.of(property));

        PropertyUpdateRequest request = new PropertyUpdateRequest("테스트 매물", 35_000_000L, 500_000L, 25.0, null, null, null);

        assertThatThrownBy(() -> propertyService.update(USER_ID, 1L, request))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 매물_삭제에_성공하면_상태가_DELETED로_바뀐다() {
        Property property = Property.builder()
                .userId(USER_ID)
                .title("테스트 매물")
                .propertyType(PropertyType.OFFICETEL)
                .transactionType(TransactionType.JEONSE)
                .deposit(30_000_000L)
                .monthlyRent(null)
                .area(23.5)
                .description(null)
                .build();
        property.assignAddress(resolvedPropertyAddress());

        when(propertyRepository.findById(1L)).thenReturn(Optional.of(property));

        propertyService.delete(USER_ID, 1L);

        assertThat(property.getStatus()).isEqualTo(PropertyStatus.DELETED);
    }

    @Test
    void 존재하지_않는_매물을_삭제하면_예외가_발생한다() {
        when(propertyRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> propertyService.delete(USER_ID, 999L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 본인_소유가_아닌_매물을_삭제하면_예외가_발생한다() {
        Long otherUserId = 999L;
        Property property = Property.builder()
                .userId(otherUserId)
                .title("테스트 매물")
                .propertyType(PropertyType.OFFICETEL)
                .transactionType(TransactionType.JEONSE)
                .deposit(30_000_000L)
                .monthlyRent(null)
                .area(23.5)
                .description(null)
                .build();
        property.assignAddress(resolvedPropertyAddress());

        when(propertyRepository.findById(1L)).thenReturn(Optional.of(property));

        assertThatThrownBy(() -> propertyService.delete(USER_ID, 1L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 이미_삭제된_매물을_다시_삭제하면_예외가_발생한다() {
        Property property = Property.builder()
                .userId(USER_ID)
                .title("테스트 매물")
                .propertyType(PropertyType.OFFICETEL)
                .transactionType(TransactionType.JEONSE)
                .deposit(30_000_000L)
                .monthlyRent(null)
                .area(23.5)
                .description(null)
                .build();
        property.assignAddress(resolvedPropertyAddress());
        property.delete();

        when(propertyRepository.findById(1L)).thenReturn(Optional.of(property));

        assertThatThrownBy(() -> propertyService.delete(USER_ID, 1L))
                .isInstanceOf(BusinessException.class);
    }

    private PropertyAddress resolvedPropertyAddress() {
        return PropertyAddress.builder()
                .roadAddress("서울특별시 강남구 테헤란로 123")
                .jibunAddress("서울특별시 강남구 역삼동 123-45")
                .latitude(37.4995539438207)
                .longitude(127.031393491745)
                .build();
    }
}
