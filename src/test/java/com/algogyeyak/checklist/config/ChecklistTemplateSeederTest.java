package com.algogyeyak.checklist.config;

import com.algogyeyak.checklist.repository.ChecklistItemTemplateRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ChecklistTemplateSeeder")
class ChecklistTemplateSeederTest {

    private final ChecklistItemTemplateRepository repository = mock(ChecklistItemTemplateRepository.class);
    private final ChecklistTemplateSeeder seeder = new ChecklistTemplateSeeder(repository);

    @Test
    @DisplayName("템플릿이 비어있으면 초기 시드 데이터를 저장한다")
    void seedsInitialTemplatesWhenRepositoryIsEmpty() throws Exception {
        when(repository.count()).thenReturn(0L);

        seeder.run(new DefaultApplicationArguments());

        verify(repository).saveAll(anyList());
    }

    @Test
    @DisplayName("이미 템플릿이 있으면 다시 저장하지 않는다")
    void doesNotReseedWhenTemplatesAlreadyExist() throws Exception {
        when(repository.count()).thenReturn(23L);

        seeder.run(new DefaultApplicationArguments());

        verify(repository, never()).saveAll(anyList());
    }
}
