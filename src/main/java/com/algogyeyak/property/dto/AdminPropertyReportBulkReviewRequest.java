package com.algogyeyak.property.dto;

import com.algogyeyak.property.entity.PropertyReportStatus;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

// status는 RESOLVED/REJECTED만 허용한다(AdminPropertyReportService에서 검증). memo는 선택된
// 신고 전체에 동일하게 적용되는 공용 메모다 - 건별로 다른 메모가 필요하면 상세 화면에서 단건 처리로
// 진행해야 한다. 원소 자체의 @NotNull은 리스트 검증(@NotEmpty/@Size)과 별개다 - 이게 없으면
// [null]처럼 null이 섞인 배열이 서비스까지 그대로 들어가 findById(null)에서
// IllegalArgumentException(500)으로 죽는다.
public record AdminPropertyReportBulkReviewRequest(
        @NotEmpty @Size(max = 100) List<@NotNull Long> reportIds,
        @NotNull PropertyReportStatus status,
        @Size(max = 500, message = "메모는 500자를 넘을 수 없습니다.") String memo
) {
}
