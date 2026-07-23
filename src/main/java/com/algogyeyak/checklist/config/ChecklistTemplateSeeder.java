package com.algogyeyak.checklist.config;

import com.algogyeyak.checklist.repository.ChecklistItemTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 앱이 시작될 때 체크리스트 템플릿이 비어있으면 초기 시드 데이터를 채워 넣는다.
 * 마이그레이션 도구(Flyway 등) 도입 전까지 쓰는 임시 방식이다.
 */
@Component
@RequiredArgsConstructor
public class ChecklistTemplateSeeder implements ApplicationRunner {

    private final ChecklistItemTemplateRepository checklistItemTemplateRepository;

    @Override
    public void run(ApplicationArguments args) {
        if (checklistItemTemplateRepository.count() > 0) {
            return;
        }

        checklistItemTemplateRepository.saveAll(ChecklistTemplateSeedData.initialTemplates());
    }
}
