package com.algogyeyak.contractanalysis.service;

import com.algogyeyak.contractanalysis.dto.ContractAnalysisMaskingRequest;
import com.algogyeyak.contractanalysis.dto.ContractAnalysisMaskingResponse;
import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ContractAnalysisMaskingService {

    // 주민등록번호: 뒷자리 성별 코드는 1~4만 유효(1900~2099년생 내국인 기준)
    private static final Pattern RRN_PATTERN = Pattern.compile("\\d{6}[-\\s]?[1-4]\\d{6}");
    private static final String RRN_MASK = "[주민등록번호]";

    private static final Pattern PHONE_PATTERN = Pattern.compile("(01[016789])[-.\\s]?(\\d{3,4})[-.\\s]?(\\d{4})");
    private static final String PHONE_MASK = "[전화번호]";

    // 계좌번호 자체는 은행마다 자릿수/구분자가 달라 숫자 패턴만으로 특정할 수 없어 라벨(계좌/입금/송금/은행)에 의존한다.
    private static final Pattern ACCOUNT_PATTERN = Pattern.compile(
            "(계좌|입금|송금|은행)\\s*[:(]?[^\\n]{0,15}?(\\d[\\d\\-\\s]{8,20}\\d)"
    );
    private static final int ACCOUNT_NUMBER_GROUP = 2;
    private static final String ACCOUNT_MASK = "[계좌번호]";

    // 일반 한글 이름은 라벨 없이는 다른 고유명사와 구분이 불가능해 임대인/임차인 라벨에 의존한다.
    // 라벨-이름 사이 필러는 lazy(0,10)?로 두어야 한다: greedy면 필러가 이름 앞부분까지 욕심껏 집어삼킨 뒤
    // 마지막 그룹의 최소 길이(2자)만 남기고 백트래킹을 멈춰, 3~4자 이름의 앞글자가 마스킹 없이 그대로 노출된다.
    private static final Pattern NAME_PATTERN = Pattern.compile(
            "(임대인|임차인)\\s*[:(]?[^\\d\\n]{0,10}?[:)]?\\s*([가-힣]{2,4})"
    );
    private static final int NAME_GROUP = 2;
    private static final String NAME_MASK = "[성명]";

    public ContractAnalysisMaskingResponse mask(ContractAnalysisMaskingRequest request) {
        if (!StringUtils.hasText(request.text())) {
            throw new BusinessException(ErrorCode.CONTRACT_ANALYSIS_INVALID_INPUT);
        }

        try {
            // 충돌 방지를 위해 반드시 이 순서로 적용: 주민등록번호 -> 전화번호 -> 계좌번호(라벨) -> 성명(라벨)
            MaskResult rrnResult = maskWholeMatch(request.text(), RRN_PATTERN, RRN_MASK);
            MaskResult phoneResult = maskWholeMatch(rrnResult.text(), PHONE_PATTERN, PHONE_MASK);
            MaskResult accountResult = maskGroup(phoneResult.text(), ACCOUNT_PATTERN, ACCOUNT_NUMBER_GROUP, ACCOUNT_MASK);
            MaskResult nameResult = maskGroup(accountResult.text(), NAME_PATTERN, NAME_GROUP, NAME_MASK);

            int totalCount = rrnResult.count() + phoneResult.count() + accountResult.count() + nameResult.count();
            return ContractAnalysisMaskingResponse.of(nameResult.text(), totalCount);
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.CONTRACT_ANALYSIS_MASKING_FAILED);
        }
    }

    private MaskResult maskWholeMatch(String text, Pattern pattern, String token) {
        return maskGroup(text, pattern, 0, token);
    }

    // groupToMask 이전 구간(라벨 등)은 그대로 두고, 지정된 그룹만 token으로 치환한다.
    private MaskResult maskGroup(String text, Pattern pattern, int groupToMask, String token) {
        Matcher matcher = pattern.matcher(text);
        StringBuilder result = new StringBuilder();
        int lastEnd = 0;
        int count = 0;

        while (matcher.find()) {
            int start = matcher.start(groupToMask);
            int end = matcher.end(groupToMask);
            result.append(text, lastEnd, start).append(token);
            lastEnd = end;
            count++;
        }
        result.append(text, lastEnd, text.length());

        return new MaskResult(result.toString(), count);
    }

    private record MaskResult(String text, int count) {
    }
}
