package com.algogyeyak.contractanalysis.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.algogyeyak.contractanalysis.entity.ContractRequest;
import com.algogyeyak.contractanalysis.entity.InputType;
import com.algogyeyak.contractanalysis.repository.ContractRequestRepository;
import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.property.repository.PropertyRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ContractAnalysisHistoryServiceTest {

    private static final Long OWNER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;
    private static final Long HISTORY_ID = 10L;

    private final ContractRequestRepository contractRequestRepository = mock(ContractRequestRepository.class);
    private final PropertyRepository propertyRepository = mock(PropertyRepository.class);
    private final ContractAnalysisHistoryService service =
            new ContractAnalysisHistoryService(contractRequestRepository, propertyRepository);

    private ContractRequest historyOwnedBy(Long userId) {
        ContractRequest contractRequest = ContractRequest.create(
                userId, null, InputType.TEXT, "요약", "고지문", "AI 생성 안내", List.of()
        );
        ReflectionTestUtils.setField(contractRequest, "id", HISTORY_ID);
        return contractRequest;
    }

    @Test
    void 본인_이력이면_삭제된다() {
        ContractRequest contractRequest = historyOwnedBy(OWNER_ID);
        when(contractRequestRepository.findById(HISTORY_ID)).thenReturn(Optional.of(contractRequest));

        service.deleteMyContractHistory(OWNER_ID, HISTORY_ID);

        verify(contractRequestRepository).delete(contractRequest);
    }

    @Test
    void 존재하지_않는_이력이면_NOT_FOUND() {
        when(contractRequestRepository.findById(HISTORY_ID)).thenReturn(Optional.empty());

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.deleteMyContractHistory(OWNER_ID, HISTORY_ID)
        );

        assertEquals(ErrorCode.CONTRACT_ANALYSIS_HISTORY_NOT_FOUND, exception.getErrorCode());
        verify(contractRequestRepository, never()).delete(any());
    }

    @Test
    void 다른_사용자의_이력이면_FORBIDDEN() {
        ContractRequest contractRequest = historyOwnedBy(OTHER_USER_ID);
        when(contractRequestRepository.findById(HISTORY_ID)).thenReturn(Optional.of(contractRequest));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.deleteMyContractHistory(OWNER_ID, HISTORY_ID)
        );

        assertEquals(ErrorCode.CONTRACT_ANALYSIS_FORBIDDEN, exception.getErrorCode());
        verify(contractRequestRepository, never()).delete(any());
    }
}
