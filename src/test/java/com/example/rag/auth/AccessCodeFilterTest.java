package com.example.rag.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccessCodeFilterTest {
    private final AccessCodeService accessCodes = mock(AccessCodeService.class);
    private final AccessCodeFilter filter = new AccessCodeFilter(accessCodes, new ObjectMapper());

    @Test
    void passesThroughNonUserPaths() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/status");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
    }

    @Test
    void rejectsRequestWithoutValidCode() throws Exception {
        when(accessCodes.isAllowed(any(), any())).thenReturn(false);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/v1/users/web-user-001/profile");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    void passesRequestWithValidCode() throws Exception {
        when(accessCodes.isAllowed("TEST-8F3K2", "web-user-001")).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/v1/users/web-user-001/profile");
        request.addHeader(AccessCodeFilter.HEADER, "TEST-8F3K2");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
    }

    // ---------- 审核端点必须在拦截范围内 ----------

    @Test
    void coversPendingReviewPath() throws Exception {
        // 审核端点挂在 /api/v1/users/{externalId}/reviews 下而不是新开 /api/v1/reviews 前缀，
        // 全部理由就是这条断言：USER_PATH 正则匹配得到它，所以它天生受访问码保护。
        // 一旦有人把审核挪到别的前缀，这条测试会失败，而失败的样子（401 变 200）正是
        // 一个公网匿名可改审核状态的接口。
        when(accessCodes.isAllowed(any(), any())).thenReturn(false);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/v1/users/REVIEWER-01/reviews/pending");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    void coversReviewDecisionPath() throws Exception {
        // 决定接口是写操作，比列表更不能被匿名碰到：它能改 review_status，
        // 而 review_status 直接决定一个策略对不对外服务。
        when(accessCodes.isAllowed(any(), any())).thenReturn(false);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/v1/users/REVIEWER-01/reviews/strategy-practice-testing/decision");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    void doesNotCoverPrefixesOutsideUserSpace() throws Exception {
        // 这条测试断言的是一个缺口，不是期望行为：任何不在 /api/v1/users/ 下的新前缀
        // 都会被直接放行，连访问码都不看。留着它是为了把这个缺口变成可执行的记录——
        // 新增管理端点时如果挂错了前缀，这里会提醒它需要自带鉴权。
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/reviews/pending");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
    }
}
