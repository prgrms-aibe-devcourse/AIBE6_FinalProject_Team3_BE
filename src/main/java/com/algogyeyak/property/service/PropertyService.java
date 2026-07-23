package com.algogyeyak.property.service;

import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.property.client.AddressResolutionResult;
import com.algogyeyak.property.client.KakaoAddressClient;
import com.algogyeyak.property.dto.PropertyDetailResponse;
import com.algogyeyak.property.dto.PropertyListResponse;
import com.algogyeyak.property.dto.PropertyRegisterRequest;
import com.algogyeyak.property.dto.PropertyRegisterResponse;
import com.algogyeyak.property.dto.PropertyUpdateRequest;
import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.entity.PropertyAddress;
import com.algogyeyak.property.entity.PropertyImage;
import com.algogyeyak.property.entity.PropertyStatus;
import com.algogyeyak.property.entity.PropertyType;
import com.algogyeyak.property.entity.TransactionType;
import com.algogyeyak.property.repository.PropertyRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PropertyService {

    private final PropertyRepository propertyRepository;
    private final KakaoAddressClient kakaoAddressClient;

    @Transactional
    public PropertyRegisterResponse register(Long userId, PropertyRegisterRequest request) {
        validatePriceCombination(request.transactionType(), request.deposit(), request.monthlyRent());

        AddressResolutionResult addressResult = kakaoAddressClient.resolve(request.address());
        if (!addressResult.isResolved()) {
            throw new BusinessException(ErrorCode.PROPERTY_ADDRESS_RESOLUTION_FAILED);
        }

        if (addressResult.getRoadAddress() != null && propertyRepository
                .existsByUserIdAndTransactionTypeAndStatusAndAddress_RoadAddress(
                        userId, request.transactionType(), PropertyStatus.ACTIVE, addressResult.getRoadAddress()
                )) {
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

        return PropertyRegisterResponse.of(saved, notice);
    }

    /**
     * 본인이 등록한 매물 목록 조회. 개인 분석 도구 성격상 마켓플레이스식 전체조회가 아니라
     * 요청자 본인 소유 + ACTIVE 상태 매물만 최신순으로 반환한다.
     */
    public List<PropertyListResponse> getMyProperties(Long userId) {
        return propertyRepository.findAllByUserIdAndStatusOrderByCreatedAtDesc(userId, PropertyStatus.ACTIVE)
                .stream()
                .map(PropertyListResponse::from)
                .toList();
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

        return PropertyDetailResponse.from(property);
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

        return PropertyDetailResponse.from(property);
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

    private String buildNoticeIfNeeded(PropertyType propertyType, AddressResolutionResult addressResult) {
        if (propertyType == PropertyType.DETACHED_HOUSE && addressResult.getRoadAddress() == null) {
            return "단독/다가구는 지번 일부 비공개로 매칭 정확도가 낮을 수 있습니다.";
        }
        return null;
    }
}
