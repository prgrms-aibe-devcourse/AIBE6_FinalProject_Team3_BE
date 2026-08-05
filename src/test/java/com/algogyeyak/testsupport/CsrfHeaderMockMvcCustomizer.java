package com.algogyeyak.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.test.web.servlet.setup.ConfigurableMockMvcBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * {@code CsrfHeaderFilter}가 상태 변경 요청(POST/PUT/PATCH/DELETE)에 {@code X-Requested-With}
 * 헤더를 요구하게 되면서, 그 헤더 없이 요청을 보내는 MockMvc 테스트가 전부 403으로 막힌다.
 * 테스트 메서드마다 헤더를 일일이 추가하는 대신, {@code @AutoConfigureMockMvc}가 이 커스터마이저를
 * 발견하면(빈으로 등록된 {@link MockMvcBuilderCustomizer}는 자동으로 적용됨) 모든 요청에 기본으로
 * 붙여준다 - 실제 브라우저 fetch(app/lib/api/http.ts)가 항상 이 헤더를 보내는 것과 동일한 조건을
 * 재현한다. 이 설정을 쓰려는 테스트 클래스는 {@code @Import(CsrfHeaderMockMvcCustomizer.class)}를 붙일 것.
 */
@TestConfiguration(proxyBeanMethods = false)
public class CsrfHeaderMockMvcCustomizer implements MockMvcBuilderCustomizer {

    @Override
    public void customize(ConfigurableMockMvcBuilder<?> builder) {
        builder.defaultRequest(get("/").header("X-Requested-With", "XMLHttpRequest"));
    }
}
