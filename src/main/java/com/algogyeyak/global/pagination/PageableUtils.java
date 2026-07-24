package com.algogyeyak.global.pagination;

import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import java.util.Set;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

public final class PageableUtils {

    private static final int MAX_PAGE_SIZE = 100;

    private PageableUtils() {
    }

    public static void validateSort(Pageable pageable, Set<String> allowedProperties) {
        for (Sort.Order order : pageable.getSort()) {
            if (!allowedProperties.contains(order.getProperty())) {
                throw new BusinessException(
                        ErrorCode.INVALID_SORT_FIELD,
                        "정렬할 수 없는 필드입니다: " + order.getProperty()
                );
            }
        }
    }

    public static void validateMaxSize(Pageable pageable) {
        if (pageable.getPageSize() > MAX_PAGE_SIZE) {
            throw new BusinessException(
                    ErrorCode.INVALID_INPUT,
                    "페이지 크기는 최대 " + MAX_PAGE_SIZE + "까지 허용됩니다."
            );
        }
    }
}
