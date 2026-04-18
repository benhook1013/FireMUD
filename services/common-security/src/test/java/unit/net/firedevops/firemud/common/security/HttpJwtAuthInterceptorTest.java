package unit.net.firedevops.firemud.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.security.HttpAuthProperties;
import net.firedevops.firemud.common.security.HttpAuthRoleRequirement;
import net.firedevops.firemud.common.security.HttpJwtAuthInterceptor;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.common.security.SessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class HttpJwtAuthInterceptorTest {
  private final JwtUtil jwtUtil = new JwtUtil("testsecretkeytestsecretkeytest1234", 3600000L);

  @AfterEach
  void clear() {
    SessionContext.clear();
  }

  @Test
  void rejectsRequestWithoutToken() throws Exception {
    HttpJwtAuthInterceptor interceptor =
        new HttpJwtAuthInterceptor(jwtUtil, authenticatedProperties());
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();

    boolean result = interceptor.preHandle(request, response, new Object());

    assertFalse(result);
    assertEquals(HttpStatus.UNAUTHORIZED.value(), response.getStatus());
  }

  @Test
  void populatesSessionForAuthenticatedRequest() throws Exception {
    HttpJwtAuthInterceptor interceptor =
        new HttpJwtAuthInterceptor(jwtUtil, authenticatedProperties());
    String token =
        jwtUtil.generateToken(
            "user", Map.of("accountId", "user", "scopedRoles", Map.of("7", List.of("admin"))));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    MockHttpServletResponse response = new MockHttpServletResponse();

    boolean result = interceptor.preHandle(request, response, new Object());

    assertTrue(result);
    assertEquals("user", SessionContext.getAccountId());
    assertEquals(List.of("admin"), SessionContext.getScopedRoles("7"));
    interceptor.afterCompletion(request, response, new Object(), null);
    assertTrue(SessionContext.getGlobalRoles().isEmpty());
  }

  @Test
  void privilegedModeRejectsNonPrivilegedCaller() throws Exception {
    HttpJwtAuthInterceptor interceptor =
        new HttpJwtAuthInterceptor(jwtUtil, privilegedProperties());
    String token = jwtUtil.generateToken("user", Map.of("accountId", "user"));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    MockHttpServletResponse response = new MockHttpServletResponse();

    boolean result = interceptor.preHandle(request, response, new Object());

    assertFalse(result);
    assertEquals(HttpStatus.FORBIDDEN.value(), response.getStatus());
  }

  private HttpAuthProperties authenticatedProperties() {
    HttpAuthProperties props = new HttpAuthProperties();
    props.setEnabled(true);
    props.setRoleRequirement(HttpAuthRoleRequirement.AUTHENTICATED);
    return props;
  }

  private HttpAuthProperties privilegedProperties() {
    HttpAuthProperties props = authenticatedProperties();
    props.setRoleRequirement(HttpAuthRoleRequirement.PRIVILEGED);
    return props;
  }
}
