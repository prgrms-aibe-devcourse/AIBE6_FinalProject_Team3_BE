package com.algogyeyak.admin.dto;

import java.util.List;

// 유저 상태 일괄변경/신고 일괄검토는 원자적 전체성공-전체실패가 아니라, 항목별로 이미 존재하는
// 가드(자기 자신 변경 금지, 마지막 관리자 보호, 본인 신고 셀프 검토 금지 등)에 따라 성공/실패가
// 갈릴 수 있다. 그래서 한 트랜잭션 안에서 항목별로 개별 시도하고, 어떤 id가 왜 실패했는지를
// 그대로 클라이언트에 내려준다 - 실패를 삼키고 "성공한 것처럼" 응답하면 관리자가 실제로 뭐가
// 적용됐는지 알 수 없게 된다.
public record AdminBulkActionResponse(List<Long> succeededIds, List<Failure> failures) {

    public record Failure(Long id, String message) {
    }
}
