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
}
