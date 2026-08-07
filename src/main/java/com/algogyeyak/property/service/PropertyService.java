package com.algogyeyak.property.service;

import com.algogyeyak.checklist.repository.ChecklistItemRepository;
import com.algogyeyak.checklist.repository.ChecklistItemRepository.ChecklistProgressProjection;
import com.algogyeyak.checklist.repository.ChecklistRepository;
import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.global.pagination.PageableUtils;
import com.algogyeyak.global.response.PageResponse;
import com.algogyeyak.marketdata.dto.MarketComparisonResponse;
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
import com.algogyeyak.property.entity.PropertyImage;
import com.algogyeyak.property.entity.PropertyStatus;
import com.algogyeyak.property.entity.PropertyType;
import com.algogyeyak.property.entity.TransactionType;
import com.algogyeyak.property.event.PropertyUpdatedEvent;
import com.algogyeyak.property.repository.PropertyReportRepository;
import com.algogyeyak.property.repository.PropertyRepository;
import com.algogyeyak.riskanalysis.entity.PropertyRisk;
import com.algogyeyak.riskanalysis.enums.DepositSafetyStatus;
import com.algogyeyak.riskanalysis.repository.DepositSafetyCheckRepository;
import com.algogyeyak.riskanalysis.repository.PropertyRiskCheckRepository;
import com.algogyeyak.riskanalysis.repository.PropertyRiskRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PropertyService {

    private final PropertyRepository propertyRepository;
    private final KakaoAddressClient kakaoAddressClient;
    private final MarketComparisonService marketComparisonService;
    private final ChecklistRepository checklistRepository;
    private final ChecklistItemRepository checklistItemRepository;
    private final PropertyReportRepository propertyReportRepository;
    private final PropertyRiskRepository propertyRiskRepository;
    private final PropertyRiskCheckRepository propertyRiskCheckRepository;
    private final DepositSafetyCheckRepository depositSafetyCheckRepository;
    private final ApplicationEventPublisher eventPublisher;

    // 목록 조회 정렬 허용 필드 - PageableUtils.validateSort가 이 밖의 필드는 INVALID_SORT_FIELD로 막는다.
    private static final Set<String> SORTABLE_PROPERTIES = Set.of("createdAt", "deposit", "area");

    // 이미지 검증 - 확장자 화이트리스트 + 개수 상한. 파일 크기/컨텐츠타입은 업로드 API
    // (PropertyImageUploadController → S3PresignService)에서 S3ImagePurpose.PROPERTY 기준으로
    // 이미 검증되므로, 여기서는 최종 imageUrl 형식과 개수만 방어적으로 한 번 더 확인한다.
    private static final Set<String> ALLOWED_IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp", "gif");
    private static final int MAX_IMAGE_COUNT = 10;

    @Transactional
    public PropertyRegisterResponse register(Long userId, PropertyRegisterRequest request) {
        validatePriceCombination(request.transactionType(), request.deposit(), request.monthlyRent());
        validateImages(request.images());

        AddressResolutionResult addressResult = kakaoAddressClient.resolve(request.address());
        if (!addressResult.isResolved()) {
            throw new BusinessException(ErrorCode.PROPERTY_ADDRESS_RESOLUTION_FAILED);
        }

        if (isDuplicate(userId, request.transactionType(), addressResult)) {
            throw new BusinessException(ErrorCode.PROPERTY_DUPLICATE);
        }

        Property property = Property.builder()
                .userId(userId)
                .title(request.title())
                .propertyType(request.propertyType())
                .transactionType(request.transactionType())
                .deposit(request.deposit())
                .monthlyRent(request.monthlyRent())
                .area(request.area())
                .description(request.description())
                .build();

        PropertyAddress address = PropertyAddress.builder()
                .roadAddress(addressResult.getRoadAddress())
                .jibunAddress(addressResult.getJibunAddress())
                .latitude(addressResult.getLatitude())
                .longitude(addressResult.getLongitude())
                .build();
        property.assignAddress(address);

        applyImages(property, request.images());

        Property saved = propertyRepository.save(property);

        String notice = buildNoticeIfNeeded(request.propertyType(), addressResult);
        MarketComparisonResponse marketComparison = marketComparisonService.compare(saved);

        // update()와 동일하게 위험 신호·전세가율 계산을 risk-analysis에 위임한다 - 이 이벤트가 없으면
        // 등록 직후엔 checkSignalCount/jeonseRatio가 계속 null로 남아있다가(목록에 "준비 중"만 표시됨)
        // 사용자가 상세/위험분석 페이지를 한 번 열어야만(그쪽에서 별도로 POST /risk-analysis를 트리거함)
        // 값이 채워지는 문제가 있었다.
        eventPublisher.publishEvent(new PropertyUpdatedEvent(saved.getId()));

        return PropertyRegisterResponse.of(saved, notice, marketComparison);
    }

    /**
     * 본인이 등록한 매물 목록 조회. 개인 분석 도구 성격상 마켓플레이스식 전체조회가 아니라
     * 요청자 본인 소유 + ACTIVE 상태 매물만 페이지네이션해서 반환한다 (기본 정렬: 최신순).
     * page/size는 Controller의 @PageableDefault가 기본값을 채워주므로 항상 값이 있다고 가정한다.
     * condition의 필드는 전부 선택값 - null인 조건은 필터링하지 않는다.
     */
    public PageResponse<PropertyListResponse> getMyProperties(
            Long userId, Pageable pageable, PropertySearchCondition condition
    ) {
        if (pageable.getPageSize() <= 0) {
            throw new BusinessException(ErrorCode.PROPERTY_INVALID_SEARCH_CONDITION, "size는 1 이상이어야 합니다.");
        }
        PageableUtils.validateSort(pageable, SORTABLE_PROPERTIES);
        PageableUtils.validateMaxSize(pageable);
        validateSearchCondition(condition);

        Page<Property> properties = propertyRepository.search(
                userId,
                PropertyStatus.ACTIVE,
                condition.region(),
                condition.minArea(),
                condition.maxArea(),
                condition.transactionType(),
                condition.propertyType(),
                condition.minDeposit(),
                condition.maxDeposit(),
                condition.minMonthlyRent(),
                condition.maxMonthlyRent(),
                pageable
        );

        Map<Long, Integer> checklistProgressByPropertyId = checklistItemRepository.findProgressByUserId(userId)
                .stream()
                .collect(Collectors.toMap(
                        ChecklistProgressProjection::getPropertyId,
                        PropertyService::toProgressPercent
                ));

        // risk-analysis를 한 번도 안 돌린 매물(checkSignalCount=null)과 돌렸는데 신호가 0건인
        // 매물(checkSignalCount=0)을 구분하기 위해, "실행 여부"는 PropertyRiskCheck 쪽으로 판단하고
        // "실제 발견된 신호"는 PropertyRisk 쪽으로 집계한다 (FakeListingSignalService 참고).
        Set<Long> riskCheckedPropertyIds = propertyRiskCheckRepository.findAllByProperty_UserId(userId)
                .stream()
                .map(check -> check.getProperty().getId())
                .collect(Collectors.toSet());

        Map<Long, List<PropertyRisk>> risksByPropertyId = propertyRiskRepository.findAllByProperty_UserId(userId)
                .stream()
                .collect(Collectors.groupingBy(risk -> risk.getProperty().getId()));

        Map<Long, Integer> jeonseRatioByPropertyId = depositSafetyCheckRepository.findAllByProperty_UserId(userId)
                .stream()
                .filter(check -> check.getStatus() == DepositSafetyStatus.CALCULATED)
                .collect(Collectors.toMap(
                        check -> check.getProperty().getId(),
                        check -> check.getJeonseRatio().intValue()
                ));

        return PageResponse.from(
                properties,
                property -> {
                    Long propertyId = property.getId();
                    Integer checkSignalCount = riskCheckedPropertyIds.contains(propertyId)
                            ? risksByPropertyId.getOrDefault(propertyId, List.of()).size()
                            : null;
                    String signalSummary = checkSignalCount != null && checkSignalCount > 0
                            ? risksByPropertyId.get(propertyId).stream()
                                    .map(PropertyRisk::getDescription)
                                    .collect(Collectors.joining(", "))
                            : null;

                    return PropertyListResponse.from(
                            property,
                            checklistProgressByPropertyId.get(propertyId),
                            marketComparisonService.compare(property),
                            checkSignalCount,
                            signalSummary,
                            jeonseRatioByPropertyId.get(propertyId)
                    );
                }
        );
    }

    /**
     * totalCount가 0이면(이론상 생기지 않아야 하지만, 체크리스트는 생성 시점에 항상 템플릿 문항을
     * 함께 복사하므로) 0으로 나누는 대신 진행률을 0%로 취급한다.
     */
    private static Integer toProgressPercent(ChecklistProgressProjection projection) {
        if (projection.getTotalCount() == 0) {
            return 0;
        }
        return (int) Math.round(projection.getCheckedCount() * 100.0 / projection.getTotalCount());
    }

    /**
     * 면적/보증금/월세 범위의 최소값이 최대값보다 크면 결과가 항상 0건이 되는데, 이건 사용자 실수(값을
     * 반대로 입력)일 가능성이 높아 조용히 빈 목록을 주기보다 명확히 400으로 알려준다.
     */
    private void validateSearchCondition(PropertySearchCondition condition) {
        if (condition.minArea() != null && condition.maxArea() != null
                && condition.minArea() > condition.maxArea()) {
            throw new BusinessException(
                    ErrorCode.PROPERTY_INVALID_SEARCH_CONDITION, "면적 최소값이 최대값보다 클 수 없습니다."
            );
        }
        if (condition.minDeposit() != null && condition.maxDeposit() != null
                && condition.minDeposit() > condition.maxDeposit()) {
            throw new BusinessException(
                    ErrorCode.PROPERTY_INVALID_SEARCH_CONDITION, "보증금 최소값이 최대값보다 클 수 없습니다."
            );
        }
        if (condition.minMonthlyRent() != null && condition.maxMonthlyRent() != null
                && condition.minMonthlyRent() > condition.maxMonthlyRent()) {
            throw new BusinessException(
                    ErrorCode.PROPERTY_INVALID_SEARCH_CONDITION, "월세 최소값이 최대값보다 클 수 없습니다."
            );
        }
    }

    /**
     * 매물 상세조회. 존재하지 않거나 이미 삭제된 매물은 PROPERTY_NOT_FOUND,
     * 존재하지만 본인 소유가 아니면 PROPERTY_ACCESS_DENIED로 구분한다.
     */
    public PropertyDetailResponse getProperty(Long userId, Long propertyId) {
        Property property = findActiveProperty(propertyId);

        if (!property.isOwnedBy(userId)) {
            throw new BusinessException(ErrorCode.PROPERTY_ACCESS_DENIED);
        }

        return PropertyDetailResponse.from(
                property,
                marketComparisonService.compare(property),
                checklistRepository.findByPropertyId(propertyId).isPresent(),
                propertyReportRepository.existsByPropertyIdAndReporterId(propertyId, userId)
        );
    }

    /**
     * 매물 수정. 주소/매물유형/거래유형은 등록 시 확정된 값이라 수정 대상이 아니고
     * 가격(보증금/월임대료)/면적/설명만 변경한다. 거래유형은 기존 값 그대로 유지되므로
     * 가격 조합 검증은 등록 때와 동일한 규칙(validatePriceCombination)을 그대로 적용한다.
     */
    @Transactional
    public PropertyDetailResponse update(Long userId, Long propertyId, PropertyUpdateRequest request) {
        Property property = findActiveProperty(propertyId);

        if (!property.isOwnedBy(userId)) {
            throw new BusinessException(ErrorCode.PROPERTY_ACCESS_DENIED);
        }

        validatePriceCombination(property.getTransactionType(), request.deposit(), request.monthlyRent());
        validateImages(request.images());

        property.updateTitle(request.title());
        property.updatePriceInfo(request.deposit(), request.monthlyRent());
        property.updateArea(request.area());
        property.updateDescription(request.description());

        // images가 null이면 "이미지 변경 없음"(기존 유지) - null이 아니면(빈 리스트 포함) 통째로 교체.
        // 부분 추가/삭제 API가 없으므로 매번 전체 목록을 다시 제출해야 한다.
        if (request.images() != null) {
            property.clearImages();
            applyImages(property, request.images());
        }

        // 가격/면적이 바뀌었으니 캐시된 예전 시세비교 결과를 비우고 재계산한다 - 안 비우면
        // compare()가 캐시 히트로 수정 전 결과를 그대로 반환해버린다.
        marketComparisonService.evictCache(propertyId);

        // risk-analysis가 위험 신호·전세가율을 재계산할 수 있도록 이벤트만 발행한다 - 이 도메인은
        // risk-analysis를 몰라도 된다(누가 구독하는지, 구독자가 있는지조차 관심 없음).
        eventPublisher.publishEvent(new PropertyUpdatedEvent(propertyId));

        return PropertyDetailResponse.from(
                property,
                marketComparisonService.compare(property),
                checklistRepository.findByPropertyId(propertyId).isPresent(),
                propertyReportRepository.existsByPropertyIdAndReporterId(propertyId, userId)
        );
    }

    private Property findActiveProperty(Long propertyId) {
        Property property = propertyRepository.findById(propertyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PROPERTY_NOT_FOUND));

        if (property.isDeleted()) {
            throw new BusinessException(ErrorCode.PROPERTY_NOT_FOUND);
        }

        return property;
    }

    /**
     * 매물 삭제(soft delete). 조회/수정과 달리 이미 삭제된 매물은 PROPERTY_NOT_FOUND가 아니라
     * PROPERTY_ALREADY_DELETED(409)로 구분한다 - 삭제 요청 자체는 대상 id가 유효했던 리소스라
     * "없는 매물"과는 다른 의미이기 때문.
     */
    @Transactional
    public void delete(Long userId, Long propertyId) {
        Property property = propertyRepository.findById(propertyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PROPERTY_NOT_FOUND));

        if (!property.isOwnedBy(userId)) {
            throw new BusinessException(ErrorCode.PROPERTY_ACCESS_DENIED);
        }

        if (property.isDeleted()) {
            throw new BusinessException(ErrorCode.PROPERTY_ALREADY_DELETED);
        }

        property.delete();
    }

    private void validatePriceCombination(TransactionType transactionType, Long deposit, Long monthlyRent) {
        if (transactionType == TransactionType.MONTHLY_RENT) {
            if (monthlyRent == null || monthlyRent <= 0) {
                throw new BusinessException(
                        ErrorCode.PROPERTY_INVALID_PRICE, "월세는 월 임대료(monthlyRent)가 필요합니다."
                );
            }
        } else if (transactionType == TransactionType.JEONSE) {
            if (monthlyRent != null) {
                throw new BusinessException(
                        ErrorCode.PROPERTY_INVALID_PRICE, "전세는 월 임대료(monthlyRent)를 입력할 수 없습니다."
                );
            }
        }
    }

    /**
     * 이미지 검증. http(s) 프로토콜 + 허용된 확장자(jpg/jpeg/png/webp/gif)인지, 개수가
     * MAX_IMAGE_COUNT(10)를 넘지 않는지 확인한다. imageUrl은 업로드 API(POST /properties/images/*)를
     * 거쳐 이미 S3ImagePurpose.PROPERTY 기준(확장자/컨텐츠타입/용량)으로 검증된 값이 들어오는 게
     * 정상이지만, 여기서도 형식/개수만큼은 한 번 더 방어적으로 확인한다 - roomType은 선택값이라
     * 별도 검증 없음(enum 자체가 잘못된 값이면 역직렬화 단계에서 400).
     */
    private void validateImages(List<PropertyImageRequest> images) {
        if (images == null || images.isEmpty()) {
            return;
        }
        if (images.size() > MAX_IMAGE_COUNT) {
            throw new BusinessException(
                    ErrorCode.PROPERTY_IMAGE_INVALID, "이미지는 최대 " + MAX_IMAGE_COUNT + "장까지 등록할 수 있습니다."
            );
        }
        for (PropertyImageRequest image : images) {
            String imageUrl = image.imageUrl();
            if (imageUrl == null || !hasValidImageExtension(imageUrl) || !hasHttpProtocol(imageUrl)) {
                throw new BusinessException(
                        ErrorCode.PROPERTY_IMAGE_INVALID, "지원하지 않는 이미지 형식입니다: " + imageUrl
                );
            }
        }
    }

    private void applyImages(Property property, List<PropertyImageRequest> images) {
        if (images == null) {
            return;
        }
        for (PropertyImageRequest image : images) {
            property.addImage(PropertyImage.builder()
                    .imageUrl(image.imageUrl())
                    .roomType(image.roomType())
                    .build());
        }
    }

    private boolean hasHttpProtocol(String imageUrl) {
        String lower = imageUrl.toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private boolean hasValidImageExtension(String imageUrl) {
        String withoutQuery = imageUrl.split("[?#]", 2)[0];
        int dotIndex = withoutQuery.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == withoutQuery.length() - 1) {
            return false;
        }
        String extension = withoutQuery.substring(dotIndex + 1).toLowerCase();
        return ALLOWED_IMAGE_EXTENSIONS.contains(extension);
    }

    private String buildNoticeIfNeeded(PropertyType propertyType, AddressResolutionResult addressResult) {
        boolean noticeApplicableType = propertyType == PropertyType.DETACHED_HOUSE
                || propertyType == PropertyType.MULTI_FAMILY;
        if (noticeApplicableType && addressResult.getRoadAddress() == null) {
            return "단독/다가구, 연립다세대는 지번 일부 비공개로 매칭 정확도가 낮을 수 있습니다.";
        }
        return null;
    }

    /**
     * 동일 사용자·동일 거래유형의 중복 등록 여부. 도로명주소가 있으면 그걸 기준으로,
     * 없으면(단독/다가구 등) 지번주소로 폴백해서 체크한다 - 이전에는 도로명주소가 없으면
     * 중복 체크 자체가 완전히 스킵됐다.
     */
    private boolean isDuplicate(Long userId, TransactionType transactionType, AddressResolutionResult addressResult) {
        if (addressResult.getRoadAddress() != null) {
            return propertyRepository.existsByUserIdAndTransactionTypeAndStatusAndAddress_RoadAddress(
                    userId, transactionType, PropertyStatus.ACTIVE, addressResult.getRoadAddress()
            );
        }
        if (addressResult.getJibunAddress() != null) {
            return propertyRepository.existsByUserIdAndTransactionTypeAndStatusAndAddress_JibunAddress(
                    userId, transactionType, PropertyStatus.ACTIVE, addressResult.getJibunAddress()
            );
        }
        return false;
    }
}
