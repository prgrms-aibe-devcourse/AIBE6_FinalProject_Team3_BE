package com.algogyeyak.checklist.service;

import com.algogyeyak.checklist.dto.ChecklistItemUpdateRequest;
import com.algogyeyak.checklist.dto.ChecklistOverviewResponse;
import com.algogyeyak.checklist.entity.Checklist;
import com.algogyeyak.checklist.entity.ChecklistItem;
import com.algogyeyak.checklist.entity.ChecklistItemTemplate;
import com.algogyeyak.checklist.entity.ChecklistResult;
import com.algogyeyak.checklist.repository.ChecklistItemRepository;
import com.algogyeyak.checklist.repository.ChecklistItemRepository.ChecklistProgressProjection;
import com.algogyeyak.checklist.repository.ChecklistItemTemplateRepository;
import com.algogyeyak.checklist.repository.ChecklistRepository;
import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.global.pagination.PageableUtils;
import com.algogyeyak.global.response.PageResponse;
import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.entity.PropertyStatus;
import com.algogyeyak.property.repository.PropertyRepository;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.repository.UserRepository;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class ChecklistService {

    private final ChecklistRepository checklistRepository;
    private final ChecklistItemTemplateRepository checklistItemTemplateRepository;
    private final ChecklistItemRepository checklistItemRepository;
    private final UserRepository userRepository;
    private final PropertyRepository propertyRepository;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public ChecklistService(
            ChecklistRepository checklistRepository,
            ChecklistItemTemplateRepository checklistItemTemplateRepository,
            ChecklistItemRepository checklistItemRepository,
            UserRepository userRepository,
            PropertyRepository propertyRepository,
            PlatformTransactionManager transactionManager) {
        this.checklistRepository = checklistRepository;
        this.checklistItemTemplateRepository = checklistItemTemplateRepository;
        this.checklistItemRepository = checklistItemRepository;
        this.userRepository = userRepository;
        this.propertyRepository = propertyRepository;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 유저-매물 조합에 이미 체크리스트가 있으면 그대로 반환하고(멱등), 없으면 현재 활성 템플릿으로
     * 새로 생성해 저장한다.
     */
    @Transactional
    public Checklist createOrGetChecklist(Long userId, Long propertyId) {
        return checklistRepository.findByUserIdAndPropertyId(userId, propertyId)
                .orElseGet(() -> createChecklist(userId, propertyId));
    }

    // 동일 유저-매물 조합에 대한 요청 두 개가(체크리스트 시작 버튼 더블클릭, 프론트 useEffect 중복
    // 호출 등으로) 정말 동시에 들어오면, 둘 다 findByUserIdAndPropertyId에서 "없음"을 보고 동시에
    // insert를 시도해 uk_checklist_user_property 유니크 제약을 위반할 수 있다(k6
    // 03-race-conditions.js의 checklistCreate 시나리오로 실제 재현 확인함). insert를 REQUIRES_NEW로
    // 격리해서, 위반이 나도 그 임시 트랜잭션의 세션만 버려지고 이 메서드가 속한 바깥
    // (createOrGetChecklist()) 트랜잭션의 세션은 정상 상태로 남게 한다 - 같은 세션에서 saveAndFlush가
    // 유니크 제약 위반으로 실패한 뒤 그 세션으로 쿼리를 이어가면 Hibernate가 예외를 던지는 문제를
    // 피하기 위함(LocalAuthService.signup(), FakeListingSignalService.upsertCheck()와 동일한 패턴).
    // 실패하면 그 사이 먼저 커밋된(경쟁에서 이긴) 체크리스트를 재조회해서 그대로 반환한다 - 이
    // 메서드는 멱등이어야 하므로(createOrGetChecklist()의 계약) 예외를 그대로 전파하지 않는다.
    //
    // DataIntegrityViolationException 외에 CannotAcquireLockException도 같이 잡는다 - 동시
    // insert 요청 수가 많아지면(k6로 30명 가까이 동시 요청 재현) InnoDB가 단순 유니크 제약 위반이
    // 아니라 데드락으로 감지하는 경우가 있는데, 이건 별도 예외 타입(TransientDataAccessException
    // 계열)이라 DataIntegrityViolationException만 잡던 코드는 이 경우를 놓쳐 500으로 새어나갔다.
    // 두 경우 모두 "누군가 이미 성공했거나 곧 성공할 것"이라는 전제는 같아 동일한 복구 로직을 쓴다.
    private Checklist createChecklist(Long userId, Long propertyId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "사용자를 찾을 수 없습니다."));

        Property property = propertyRepository.findById(propertyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PROPERTY_NOT_FOUND));
        if (property.isDeleted()) {
            throw new BusinessException(ErrorCode.PROPERTY_NOT_FOUND);
        }
        if (!property.isOwnedBy(userId)) {
            throw new BusinessException(ErrorCode.PROPERTY_ACCESS_DENIED);
        }

        List<ChecklistItemTemplate> activeTemplates = checklistItemTemplateRepository.findByActiveTrueOrderByDisplayOrderAsc();
        int templateVersion = activeTemplates.isEmpty() ? 0 : activeTemplates.get(0).getVersion();
        List<ChecklistItemTemplate> applicableTemplates = activeTemplates.stream()
                .filter(template -> template.isApplicableTo(property.getPropertyType()))
                .toList();

        Checklist checklist = Checklist.createFrom(user, property, templateVersion, applicableTemplates);

        try {
            requiresNewTransactionTemplate.executeWithoutResult(status -> checklistRepository.saveAndFlush(checklist));
            return checklist;
        } catch (DataIntegrityViolationException | CannotAcquireLockException e) {
            // 재조회도 REQUIRES_NEW(새 스냅샷)에서 한다 - 바깥 트랜잭션에서 그대로 재조회하면 MySQL
            // InnoDB의 기본 격리수준(REPEATABLE READ)에서는 이미 확보한 스냅샷에 갇혀 방금 경쟁에서
            // 이긴 다른 트랜잭션의 커밋이 안 보일 수 있다(LocalAuthService.signup()과 동일 이유).
            Checklist winner = requiresNewTransactionTemplate.execute(status ->
                    checklistRepository.findByUserIdAndPropertyId(userId, propertyId).orElse(null));
            if (winner == null) {
                throw e;
            }
            return winner;
        }
    }

    /**
     * 체크리스트 항목 하나를 갱신한다. checked/value/userNote 중 요청에 채워진 필드에 따라
     * 알맞은 엔티티 메서드로 위임하고, 갱신 후 체크리스트 전체 진행 상태를 재계산한다.
     */
    @Transactional
    public ChecklistItem updateChecklistItem(Long userId, Long checklistId, Long itemId, ChecklistItemUpdateRequest request) {
        Checklist checklist = checklistRepository.findById(checklistId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHECKLIST_NOT_FOUND));

        if (!checklist.getUser().getId().equals(userId)) {
            throw new BusinessException(ErrorCode.PROPERTY_ACCESS_DENIED, "본인의 체크리스트만 수정할 수 있습니다.");
        }

        if (checklist.getProperty().isDeleted()) {
            throw new BusinessException(ErrorCode.CHECKLIST_NOT_FOUND);
        }

        ChecklistItem item = checklist.getItems().stream()
                .filter(candidate -> candidate.getId().equals(itemId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.CHECKLIST_ITEM_NOT_FOUND));

        if (request.checked() != null) {
            item.check(request.checked());
        } else if (request.value() != null) {
            item.answer(request.value());
        } else if (request.userNote() != null) {
            item.markInsufficient(request.userNote());
        } else {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "변경할 값을 하나 이상 보내야 합니다.");
        }

        checklist.refreshStatus();

        return item;
    }

    /**
     * 매물의 체크리스트를 문항까지 포함해 조회한다. 없으면 생성하지 않고 CHECKLIST_NOT_FOUND를 던진다
     * (생성은 createOrGetChecklist가 담당). "생성" 흐름과 동일하게 매물을 먼저 조회(존재+삭제+소유권 확인)한
     * 뒤 체크리스트를 찾아, "매물 없음(PROPERTY_NOT_FOUND)"과 "체크리스트 없음(CHECKLIST_NOT_FOUND)"을
     * 명확히 구분한다.
     */
    public Checklist getChecklist(Long userId, Long propertyId) {
        Property property = propertyRepository.findById(propertyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PROPERTY_NOT_FOUND));
        if (property.isDeleted()) {
            throw new BusinessException(ErrorCode.PROPERTY_NOT_FOUND);
        }
        if (!property.isOwnedBy(userId)) {
            throw new BusinessException(ErrorCode.PROPERTY_ACCESS_DENIED);
        }

        return checklistRepository.findByPropertyId(propertyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHECKLIST_NOT_FOUND));
    }

    /**
     * 체크리스트 결과(확인 완료도, 필수 확인 누락 수, 주의 항목 수)를 조회한다.
     * 등급/점수는 없고, 아직 시작 전이면 시작 안내 메시지가 담긴 결과를 그대로 반환한다.
     */
    public ChecklistResult getChecklistResult(Long userId, Long checklistId) {
        Checklist checklist = checklistRepository.findById(checklistId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHECKLIST_NOT_FOUND));

        if (!checklist.getUser().getId().equals(userId)) {
            throw new BusinessException(ErrorCode.PROPERTY_ACCESS_DENIED, "본인의 체크리스트만 조회할 수 있습니다.");
        }

        if (checklist.getProperty().isDeleted()) {
            throw new BusinessException(ErrorCode.CHECKLIST_NOT_FOUND);
        }

        return checklist.computeResult();
    }

    /**
     * 로그인 유저의 매물 전체 + 매물별 체크리스트 현황을 페이지네이션으로 반환한다. 체크리스트를
     * 아직 시작 안 한 매물도 포함되며(checklistId=null, status=NOT_STARTED), 삭제된 매물은 제외한다.
     * 정렬(최종 점검일 최신순)은 항상 고정이라 사용자가 고를 수 없다 - pageable에 담긴 sort는
     * 무시하고 page/size만 뽑아 새 Pageable로 리포지토리에 넘긴다(그렇지 않으면 리포지토리 쿼리의
     * 고정 ORDER BY 뒤에 클라이언트가 지정한 정렬이 덧붙어 의도와 다른 결과가 나올 수 있음).
     */
    public PageResponse<ChecklistOverviewResponse> listMyChecklists(Long userId, Pageable pageable) {
        PageableUtils.validateMaxSize(pageable);
        Pageable fixedSortPageable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());

        Page<Object[]> page = checklistRepository.findOverviewByUserId(userId, PropertyStatus.ACTIVE, fixedSortPageable);

        // ChecklistItemRepository.findProgressByUserId()와 동일한 이유로(PropertyService의
        // checklistProgressByPropertyId 참고) 유저 전체 진행률을 쿼리 하나로 배치 조회한다 -
        // 이 응답도 매물별 진행 상태 목록이라 페이지 크기와 무관하게 N+1 없이 끝나야 한다.
        Map<Long, ChecklistProgressProjection> progressByPropertyId = checklistItemRepository.findProgressByUserId(userId)
                .stream()
                .collect(Collectors.toMap(ChecklistProgressProjection::getPropertyId, p -> p));

        return PageResponse.from(page.map(row -> {
            Property property = (Property) row[0];
            Checklist checklist = (Checklist) row[1];
            ChecklistProgressProjection progress = progressByPropertyId.get(property.getId());
            return ChecklistOverviewResponse.from(
                    property, checklist,
                    progress != null ? toProgressPercent(progress) : null,
                    progress != null ? (int) progress.getIssueCount() : null,
                    progress != null ? (int) progress.getGeneralMissingCount() : null,
                    progress != null ? (int) progress.getRequiredMissingCount() : null
            );
        }));
    }

    /**
     * totalCount가 0이면(이론상 생기지 않아야 하지만, 체크리스트는 생성 시점에 항상 템플릿 문항을
     * 함께 복사하므로) 0으로 나누는 대신 진행률을 0%로 취급한다. PropertyService.toProgressPercent()와
     * 동일한 로직 - 계산 자체는 사소해서 공유 유틸리티를 두기보다 각자 유지한다.
     */
    private static Integer toProgressPercent(ChecklistProgressProjection projection) {
        if (projection.getTotalCount() == 0) {
            return 0;
        }
        return (int) Math.round(projection.getCheckedCount() * 100.0 / projection.getTotalCount());
    }
}
