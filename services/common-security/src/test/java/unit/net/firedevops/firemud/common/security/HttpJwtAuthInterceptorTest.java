package unit.net.firedevops.firemud.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.firedevops.firemud.common.security.HttpAuthProperties;
import net.firedevops.firemud.common.security.HttpAuthRoleRequirement;
import net.firedevops.firemud.common.security.HttpJwtAuthInterceptor;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.common.security.SessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class HttpJwtAuthInterceptorTest {
  private static final String ACCOUNT_ID = "018f8f0a-1a6b-7b13-8d04-5f6e7d8c9b0a";
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
  void allowsConfiguredPublicPathWithoutToken() throws Exception {
    HttpAuthProperties props = authenticatedProperties();
    props.setPublicRoutes(
        List.of(
            publicRoute("POST", "/auth/player-bootstrap"),
            publicRoute("POST", "/accounts"),
            publicRoute("POST", "/accounts/")));
    HttpJwtAuthInterceptor interceptor = new HttpJwtAuthInterceptor(jwtUtil, props);
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/player-bootstrap");
    MockHttpServletResponse response = new MockHttpServletResponse();

    boolean result = interceptor.preHandle(request, response, new Object());

    assertTrue(result);
    assertEquals(HttpStatus.OK.value(), response.getStatus());
  }

  @Test
  void allowsWildcardConfiguredPublicPathWithoutToken() throws Exception {
    HttpAuthProperties props = authenticatedProperties();
    props.setPublicRoutes(List.of(publicRoute("GET", "/auth/bootstrap/**")));
    HttpJwtAuthInterceptor interceptor = new HttpJwtAuthInterceptor(jwtUtil, props);
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/auth/bootstrap/worlds/demo/realms");
    MockHttpServletResponse response = new MockHttpServletResponse();

    boolean result = interceptor.preHandle(request, response, new Object());

    assertTrue(result);
    assertEquals(HttpStatus.OK.value(), response.getStatus());
  }

  @Test
  void populatesSessionForAuthenticatedRequest() throws Exception {
    HttpJwtAuthInterceptor interceptor =
        new HttpJwtAuthInterceptor(jwtUtil, authenticatedProperties());
    String token =
        jwtUtil.generateToken(
            ACCOUNT_ID,
            Map.of("accountId", ACCOUNT_ID, "scopedRoles", Map.of("7", List.of("tenantAdmin"))));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    MockHttpServletResponse response = new MockHttpServletResponse();

    boolean result = interceptor.preHandle(request, response, new Object());

    assertTrue(result);
    assertEquals(ACCOUNT_ID, SessionContext.getAccountId());
    assertEquals(List.of("tenantAdmin"), SessionContext.getScopedRoles("7"));
    interceptor.afterCompletion(request, response, new Object(), null);
    assertTrue(SessionContext.getGlobalRoles().isEmpty());
  }

  @ParameterizedTest
  @MethodSource("invalidSignedAccountIdentities")
  void rejectsInvalidSignedAccountIdentityBeforeRoleAuthorization(String subject, Object accountId)
      throws Exception {
    HttpJwtAuthInterceptor interceptor =
        new HttpJwtAuthInterceptor(jwtUtil, privilegedProperties());
    String token =
        jwtUtil.generateToken(
            subject, Map.of("accountId", accountId, "globalRoles", List.of("platformAdmin")));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    MockHttpServletResponse response = new MockHttpServletResponse();

    assertFalse(interceptor.preHandle(request, response, new Object()));
    assertEquals(HttpStatus.UNAUTHORIZED.value(), response.getStatus());
    assertFalse(SessionContext.hasAuthenticatedCallerContext());
    assertNull(SessionContext.getAccountId());
  }

  private static Stream<Arguments> invalidSignedAccountIdentities() {
    return Stream.of(
        Arguments.of(ACCOUNT_ID, 42L),
        Arguments.of(ACCOUNT_ID, List.of(ACCOUNT_ID)),
        Arguments.of(ACCOUNT_ID, " " + ACCOUNT_ID),
        Arguments.of(ACCOUNT_ID, "00000000-0000-0000-0000-000000000000"),
        Arguments.of(ACCOUNT_ID, ACCOUNT_ID.toUpperCase(java.util.Locale.ROOT)),
        Arguments.of("018f8f0a-2b7c-7a24-9c15-6a9b8c7d6e5f", ACCOUNT_ID),
        Arguments.of("service:game-session-service", ACCOUNT_ID));
  }

  @Test
  void privilegedModeRejectsNonPrivilegedCaller() throws Exception {
    HttpJwtAuthInterceptor interceptor =
        new HttpJwtAuthInterceptor(jwtUtil, privilegedProperties());
    String token = jwtUtil.generateToken(ACCOUNT_ID, Map.of("accountId", ACCOUNT_ID));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    MockHttpServletResponse response = new MockHttpServletResponse();

    boolean result = interceptor.preHandle(request, response, new Object());

    assertFalse(result);
    assertEquals(HttpStatus.FORBIDDEN.value(), response.getStatus());
  }

  @Test
  void populatesSharedInternalServiceIdentity() throws Exception {
    HttpJwtAuthInterceptor interceptor =
        new HttpJwtAuthInterceptor(jwtUtil, authenticatedProperties());
    String token =
        jwtUtil.generateToken(
            "service:game-session-service",
            Map.of(
                "internalService",
                true,
                "serviceName",
                "game-session-service",
                "serviceInstanceId",
                "game-session-service-1"));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    MockHttpServletResponse response = new MockHttpServletResponse();

    boolean result = interceptor.preHandle(request, response, new Object());

    assertTrue(result);
    assertTrue(SessionContext.isInternalService());
    assertEquals("", SessionContext.getAccountId());
    assertEquals("game-session-service", SessionContext.getServiceName());
    assertEquals("game-session-service-1", SessionContext.getServiceInstanceId());
  }

  @Test
  void rejectsMalformedGlobalRolesClaimShape() throws Exception {
    HttpJwtAuthInterceptor interceptor =
        new HttpJwtAuthInterceptor(jwtUtil, authenticatedProperties());
    String token =
        jwtUtil.generateToken(
            ACCOUNT_ID,
            Map.of(
                "accountId",
                ACCOUNT_ID,
                "globalRoles",
                "platformAdmin",
                "scopedRoles",
                Map.of("7", List.of("tenantAdmin"))));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    MockHttpServletResponse response = new MockHttpServletResponse();

    boolean result = interceptor.preHandle(request, response, new Object());

    assertFalse(result);
    assertEquals(HttpStatus.UNAUTHORIZED.value(), response.getStatus());
  }

  @Test
  void methodMismatchStillRequiresTokenForConfiguredPublicPath() throws Exception {
    HttpAuthProperties props = authenticatedProperties();
    props.setPublicRoutes(List.of(publicRoute("POST", "/accounts")));
    HttpJwtAuthInterceptor interceptor = new HttpJwtAuthInterceptor(jwtUtil, props);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/accounts");
    MockHttpServletResponse response = new MockHttpServletResponse();

    boolean result = interceptor.preHandle(request, response, new Object());

    assertFalse(result);
    assertEquals(HttpStatus.UNAUTHORIZED.value(), response.getStatus());
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

  private HttpAuthProperties.HttpPublicRoute publicRoute(String method, String pathPattern) {
    HttpAuthProperties.HttpPublicRoute route = new HttpAuthProperties.HttpPublicRoute();
    route.setMethod(method);
    route.setPathPattern(pathPattern);
    return route;
  }
}
