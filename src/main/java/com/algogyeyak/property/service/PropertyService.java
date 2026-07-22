package com.algogyeyak.property.service;

import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.property.client.AddressResolutionResult;
import com.algogyeyak.property.client.KakaoAddressClient;
import com.algogyeyak.property.dto.PropertyRegisterRequest;
import com.algogyeyak.property.dto.PropertyRegisterResponse;
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
