package com.shop.domain.point.controller.api;

import com.shop.domain.point.entity.PointHistory;
import com.shop.domain.point.service.PointQueryService;
import com.shop.global.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * PointApiController 단위 테스트.
 *
 * <p>포인트 REST API의 1개 엔드포인트(이력 조회)를 검증한다.
 * 인증 필수 경로이므로 SecurityContextHolder에 인증 정보를 설정한다.</p>
 */
@ExtendWith(MockitoExtension.class)
class PointApiControllerUnitTest {

    private static final Long USER_ID = 1L;

    @Mock
    private PointQueryService pointQueryService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        PointApiController controller = new PointApiController(pointQueryService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        CustomUserPrincipal principal = new CustomUserPrincipal(
                USER_ID, "tester", "encoded", "테스터", "ROLE_USER",
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities())
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private PointHistory createPointHistory(Long historyId, String changeType, int amount, int balanceAfter) {
        return new PointHistory(USER_ID, changeType, amount, balanceAfter, "ORDER", 100L,
                changeType + " " + amount + "P");
    }

    // ── GET /api/v1/points/history — 포인트 이력 조회 ──────────

    @Test
    @DisplayName("포인트 이력이 있으면 페이징된 목록을 반환한다")
    void getMyPointHistory_withHistory_returnsPagedResponse() throws Exception {
        PointHistory earn = createPointHistory(1L, "EARN", 1500, 1500);
        PointHistory use = createPointHistory(2L, "USE", 500, 1000);
        Page<PointHistory> page = new PageImpl<>(List.of(earn, use), PageRequest.of(0, 10), 2);

        when(pointQueryService.getPointHistoriesByUser(eq(USER_ID), any(PageRequest.class)))
                .thenReturn(page);

        mockMvc.perform(get("/api/v1/points/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.content[0].changeType").value("EARN"))
                .andExpect(jsonPath("$.data.content[0].amount").value(1500))
                .andExpect(jsonPath("$.data.content[0].balanceAfter").value(1500))
                .andExpect(jsonPath("$.data.content[1].changeType").value("USE"))
                .andExpect(jsonPath("$.data.totalElements").value(2));
    }

    @Test
    @DisplayName("포인트 이력이 없으면 빈 목록을 반환한다")
    void getMyPointHistory_empty_returnsEmptyPage() throws Exception {
        Page<PointHistory> emptyPage = new PageImpl<>(
                Collections.emptyList(), PageRequest.of(0, 10), 0);

        when(pointQueryService.getPointHistoriesByUser(eq(USER_ID), any(PageRequest.class)))
                .thenReturn(emptyPage);

        mockMvc.perform(get("/api/v1/points/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content").isEmpty())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    @DisplayName("페이지 파라미터를 전달하면 정규화되어 서비스에 전달된다")
    void getMyPointHistory_withPageParam_normalizedAndPassedToService() throws Exception {
        Page<PointHistory> page = new PageImpl<>(
                Collections.emptyList(), PageRequest.of(2, 10), 0);

        when(pointQueryService.getPointHistoriesByUser(eq(USER_ID), eq(PageRequest.of(2, 10))))
                .thenReturn(page);

        mockMvc.perform(get("/api/v1/points/history").param("page", "2"))
                .andExpect(status().isOk());

        verify(pointQueryService).getPointHistoriesByUser(eq(USER_ID), eq(PageRequest.of(2, 10)));
    }

    @Test
    @DisplayName("인증되지 않은 사용자는 예외가 발생한다")
    void getMyPointHistory_unauthenticated_throwsException() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> mockMvc.perform(get("/api/v1/points/history")))
                .hasCauseInstanceOf(NoSuchElementException.class);
    }
}
