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
import com.algogyeyak.property.repository.PropertyReportRepository;
import com.algogyeyak.property.repository.PropertyRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
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

    // 목록 조회 정렬 허용 필드 - PageableUtils.validateSort가 이 밖의 필드는 INVALID_SORT_FIELD로 막는다.
    private static final Set<String> SORTABLE_PROPERTIES = Set.of("createdAt", "deposit", "area");

    // 이미지 URL 검증 - 확장자 화이트리스트 + 개수 상한. 실제 업로드 인프라(S3 등)가 아직 없어
    // URL을 그대로 받는 구조라 바이트 단위 파일 크기 검증은 여기서 할 수 없다 - 확장자/개수만 막는다.
    private static final Set<String> ALLOWED_IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp", "gif");
    private static final int MAX_IMAGE_COUNT = 10;

    @Transactional
    public PropertyRegisterResponse register(Long userId, PropertyRegisterRequest request) {
        validatePriceCombination(request.transactionType(), request.deposit(), request.monthlyRent());
        validateImageUrls(request.imageUrls());

        AddressResolutionResult addressResult = kakaoAddressClient.resolve(request.address());
        if (!addressResult.isResolved()) {
            throw new BusinessException(ErrorCode.PROPERTY_ADDRESS_RESOLUTION_FAILED);
        }

        if (isDuplicate(userId, request.transactionType(), addressResult)) {
            throw new BusinessException(ErrorCode.PROPERTY_DUPLICATE);
        }

        Property property = Property.builder()
                .userId(userId)
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

        List<String> imageUrls = request.imageUrls();
        if (imageUrls != null) {
            for (String imageUrl : imageUrls) {
                property.addImage(PropertyImage.builder().imageUrl(imageUrl).build());
            }
        }

        Property saved = propertyRepository.save(property);

        String notice = buildNoticeIfNeeded(request.propertyType(), addressResult);
        MarketComparisonResponse marketComparison = marketComparisonService.compare(saved);

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

        return PageResponse.from(
                properties,
                property -> PropertyListResponse.from(property, checklistProgressByPropertyId.get(property.getId()))
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

        property.updatePriceInfo(request.deposit(), request.monthlyRent());
        property.updateArea(request.area());
        property.updateDescription(request.description());

        // 가격/면적이 바뀌었으니 캐시된 예전 시세비교 결과를 비우고 재계산한다 - 안 비우면
        // compare()가 캐시 히트로 수정 전 결과를 그대로 반환해버린다.
        marketComparisonService.evictCache(propertyId);

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
     * 이미지 URL 검증. http(s) 프로토콜 + 허용된 확장자(jpg/jpeg/png/webp/gif)인지, 개수가
     * MAX_IMAGE_COUNT(10)를 넘지 않는지만 확인한다. 실제 업로드 인프라(S3 등)가 아직 없어
     * 클라이언트가 이미지 URL을 직접 넘기는 구조이므로 바이트 단위 파일 크기 검증은
     * 이 단계에서 불가능 - 업로드 인프라가 붙으면 그쪽에서 크기 제한을 걸어야 한다.
     */
    private void validateImageUrls(List<String> imageUrls) {
        if (imageUrls == null || imageUrls.isEmpty()) {
            return;
        }
        if (imageUrls.size() > MAX_IMAGE_COUNT) {
            throw new BusinessException(
                    ErrorCode.PROPERTY_IMAGE_INVALID, "이미지는 최대 " + MAX_IMAGE_COUNT + "장까지 등록할 수 있습니다."
            );
        }
        for (String imageUrl : imageUrls) {
            if (imageUrl == null || !hasValidImageExtension(imageUrl) || !hasHttpProtocol(imageUrl)) {
                throw new BusinessException(
                        ErrorCode.PROPERTY_IMAGE_INVALID, "지원하지 않는 이미지 형식입니다: " + imageUrl
                );
            }
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
