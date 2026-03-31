package net.firedevops.firemud.accountservice.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.common.security.SessionContext;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class JwtAuthInterceptorTest {
  private final JwtUtil jwtUtil = new JwtUtil("testsecretkeytestsecretkeytest1234", 3600000L);
  private final JwtAuthInterceptor interceptor = new JwtAuthInterceptor(jwtUtil);

  @Test
  void rejectsRequestWithoutToken() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRequestURI("/profiles/me");
    MockHttpServletResponse response = new MockHttpServletResponse();

    boolean result = interceptor.preHandle(request, response, new Object());

    assertFalse(result);
    assertEquals(HttpStatus.UNAUTHORIZED.value(), response.getStatus());
  }

  @Test
  void allowsRequestWithValidRole() throws Exception {
    String token = jwtUtil.generateToken("user", Map.of("globalRoles", List.of("platformAdmin")));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRequestURI("/profiles/me");
    request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    MockHttpServletResponse response = new MockHttpServletResponse();

    boolean result = interceptor.preHandle(request, response, new Object());

    assertTrue(result);
    assertEquals(200, response.getStatus());
    assertEquals(List.of("platformAdmin"), SessionContext.getGlobalRoles());
    interceptor.afterCompletion(request, response, new Object(), null);
    assertTrue(SessionContext.getGlobalRoles().isEmpty());
  }

  @Test
  void allowsPublicCreateAccountRequestWithoutToken() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRequestURI("/accounts");
    MockHttpServletResponse response = new MockHttpServletResponse();

    boolean result = interceptor.preHandle(request, response, new Object());

    assertTrue(result);
    assertEquals(200, response.getStatus());
  }

  @Test
  void allowsPublicPlayerBootstrapRequestWithoutToken() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRequestURI("/auth/player-bootstrap");
    MockHttpServletResponse response = new MockHttpServletResponse();

    boolean result = interceptor.preHandle(request, response, new Object());

    assertTrue(result);
    assertEquals(200, response.getStatus());
  }

  @Test
  void allowsPublicConnectTokenRequestWithoutToken() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRequestURI("/auth/connect-token");
    MockHttpServletResponse response = new MockHttpServletResponse();

    boolean result = interceptor.preHandle(request, response, new Object());

    assertTrue(result);
    assertEquals(200, response.getStatus());
  }
}
