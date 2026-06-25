package net.firedevops.firemud.gamesession.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.gamesession.config.FirstPartyConnectContextProperties;
import org.junit.jupiter.api.Test;

class FirstPartyConnectContextServiceTest {

  @Test
  void parseReadsJwtClaimsThroughInjectedUtil() {
    FirstPartyConnectContextProperties properties = new FirstPartyConnectContextProperties();
    properties.setJwtSecret("secret");
    JwtUtil jwtUtil = mock(JwtUtil.class);
    @SuppressWarnings("unchecked")
    Jws<Claims> jws = mock(Jws.class);
    Claims claims = mock(Claims.class);
    when(jwtUtil.parseToken("token")).thenReturn(jws);
    when(jws.getPayload()).thenReturn(claims);
    when(claims.getSubject()).thenReturn("42");
    when(claims.get("tenantId")).thenReturn(7L);
    when(claims.get("worldSlug")).thenReturn("demo");
    when(claims.get("realmSlug")).thenReturn("production");
    when(claims.get("gameInstanceId")).thenReturn("9");
    when(claims.get("pointerVersion")).thenReturn(17L);
    when(claims.get("connectScopeId")).thenReturn("scope-1");
    when(claims.get("connectTokenJti")).thenReturn("jti");
    when(claims.get("connectRequestId")).thenReturn("request-connect-1");
    when(claims.get("gatewayRequestId")).thenReturn("request-1");

    FirstPartyConnectContextService service =
        new FirstPartyConnectContextService(properties, jwtUtil);

    assertTrue(service.parse("token").isPresent());
    FirstPartyConnectContext context = service.parse("token").orElseThrow();
    assertEquals(42L, context.accountId());
    assertEquals(7L, context.tenantId());
    assertEquals("demo", context.worldSlug());
    assertEquals("production", context.realmSlug());
    assertEquals(9L, context.gameInstanceId());
    assertEquals(17L, context.pointerVersion());
    assertEquals("scope-1", context.connectScopeId());
    assertEquals("jti", context.connectTokenJti());
    assertEquals("request-connect-1", context.connectRequestId());
    assertEquals("request-1", context.gatewayRequestId());
  }

  @Test
  void parseRejectsMissingWorldSlugClaim() {
    FirstPartyConnectContextProperties properties = new FirstPartyConnectContextProperties();
    properties.setJwtSecret("secret");
    JwtUtil jwtUtil = mock(JwtUtil.class);
    @SuppressWarnings("unchecked")
    Jws<Claims> jws = mock(Jws.class);
    Claims claims = mock(Claims.class);
    when(jwtUtil.parseToken("token")).thenReturn(jws);
    when(jws.getPayload()).thenReturn(claims);
    when(claims.getSubject()).thenReturn("42");
    when(claims.get("tenantId")).thenReturn(7L);
    when(claims.get("worldSlug")).thenReturn("");
    when(claims.get("realmSlug")).thenReturn("production");
    when(claims.get("gameInstanceId")).thenReturn("9");
    when(claims.get("pointerVersion")).thenReturn(17L);
    when(claims.get("connectScopeId")).thenReturn("scope-1");
    when(claims.get("connectTokenJti")).thenReturn("jti");
    when(claims.get("connectRequestId")).thenReturn("request-connect-1");
    when(claims.get("gatewayRequestId")).thenReturn("request-1");

    FirstPartyConnectContextService service =
        new FirstPartyConnectContextService(properties, jwtUtil);

    assertTrue(service.parse("token").isEmpty());
  }

  @Test
  void parseRejectsMissingRealmSlugClaim() {
    FirstPartyConnectContextProperties properties = new FirstPartyConnectContextProperties();
    properties.setJwtSecret("secret");
    JwtUtil jwtUtil = mock(JwtUtil.class);
    @SuppressWarnings("unchecked")
    Jws<Claims> jws = mock(Jws.class);
    Claims claims = mock(Claims.class);
    when(jwtUtil.parseToken("token")).thenReturn(jws);
    when(jws.getPayload()).thenReturn(claims);
    when(claims.getSubject()).thenReturn("42");
    when(claims.get("tenantId")).thenReturn(7L);
    when(claims.get("worldSlug")).thenReturn("demo");
    when(claims.get("realmSlug")).thenReturn("");
    when(claims.get("gameInstanceId")).thenReturn("9");
    when(claims.get("pointerVersion")).thenReturn(17L);
    when(claims.get("connectScopeId")).thenReturn("scope-1");
    when(claims.get("connectTokenJti")).thenReturn("jti");
    when(claims.get("connectRequestId")).thenReturn("request-connect-1");
    when(claims.get("gatewayRequestId")).thenReturn("request-1");

    FirstPartyConnectContextService service =
        new FirstPartyConnectContextService(properties, jwtUtil);

    assertTrue(service.parse("token").isEmpty());
  }

  @Test
  void parseRejectsMissingConnectScopeIdClaim() {
    FirstPartyConnectContextProperties properties = new FirstPartyConnectContextProperties();
    properties.setJwtSecret("secret");
    JwtUtil jwtUtil = mock(JwtUtil.class);
    @SuppressWarnings("unchecked")
    Jws<Claims> jws = mock(Jws.class);
    Claims claims = mock(Claims.class);
    when(jwtUtil.parseToken("token")).thenReturn(jws);
    when(jws.getPayload()).thenReturn(claims);
    when(claims.getSubject()).thenReturn("42");
    when(claims.get("tenantId")).thenReturn(7L);
    when(claims.get("worldSlug")).thenReturn("demo");
    when(claims.get("realmSlug")).thenReturn("production");
    when(claims.get("gameInstanceId")).thenReturn("9");
    when(claims.get("pointerVersion")).thenReturn(17L);
    when(claims.get("connectScopeId")).thenReturn("   ");
    when(claims.get("connectTokenJti")).thenReturn("jti");
    when(claims.get("connectRequestId")).thenReturn("request-connect-1");
    when(claims.get("gatewayRequestId")).thenReturn("request-1");

    FirstPartyConnectContextService service =
        new FirstPartyConnectContextService(properties, jwtUtil);

    assertTrue(service.parse("token").isEmpty());
  }

  @Test
  void parseRejectsMissingConnectRequestIdClaim() {
    FirstPartyConnectContextProperties properties = new FirstPartyConnectContextProperties();
    properties.setJwtSecret("secret");
    JwtUtil jwtUtil = mock(JwtUtil.class);
    @SuppressWarnings("unchecked")
    Jws<Claims> jws = mock(Jws.class);
    Claims claims = mock(Claims.class);
    when(jwtUtil.parseToken("token")).thenReturn(jws);
    when(jws.getPayload()).thenReturn(claims);
    when(claims.getSubject()).thenReturn("42");
    when(claims.get("tenantId")).thenReturn(7L);
    when(claims.get("worldSlug")).thenReturn("demo");
    when(claims.get("realmSlug")).thenReturn("production");
    when(claims.get("gameInstanceId")).thenReturn("9");
    when(claims.get("pointerVersion")).thenReturn(17L);
    when(claims.get("connectScopeId")).thenReturn("scope-1");
    when(claims.get("connectTokenJti")).thenReturn("jti");
    when(claims.get("connectRequestId")).thenReturn("");
    when(claims.get("gatewayRequestId")).thenReturn("request-1");

    FirstPartyConnectContextService service =
        new FirstPartyConnectContextService(properties, jwtUtil);

    assertTrue(service.parse("token").isEmpty());
  }

  @Test
  void parseRejectsMissingPointerVersionClaim() {
    FirstPartyConnectContextProperties properties = new FirstPartyConnectContextProperties();
    properties.setJwtSecret("secret");
    JwtUtil jwtUtil = mock(JwtUtil.class);
    @SuppressWarnings("unchecked")
    Jws<Claims> jws = mock(Jws.class);
    Claims claims = mock(Claims.class);
    when(jwtUtil.parseToken("token")).thenReturn(jws);
    when(jws.getPayload()).thenReturn(claims);
    when(claims.getSubject()).thenReturn("42");
    when(claims.get("tenantId")).thenReturn(7L);
    when(claims.get("worldSlug")).thenReturn("demo");
    when(claims.get("realmSlug")).thenReturn("production");
    when(claims.get("gameInstanceId")).thenReturn("9");
    when(claims.get("pointerVersion")).thenReturn(0L);
    when(claims.get("connectScopeId")).thenReturn("scope-1");
    when(claims.get("connectTokenJti")).thenReturn("jti");
    when(claims.get("connectRequestId")).thenReturn("request-connect-1");
    when(claims.get("gatewayRequestId")).thenReturn("request-1");

    FirstPartyConnectContextService service =
        new FirstPartyConnectContextService(properties, jwtUtil);

    assertTrue(service.parse("token").isEmpty());
  }

  @Test
  void parseRejectsMissingPointerVersionClaimWhenNotPresent() {
    FirstPartyConnectContextProperties properties = new FirstPartyConnectContextProperties();
    properties.setJwtSecret("secret");
    JwtUtil jwtUtil = mock(JwtUtil.class);
    @SuppressWarnings("unchecked")
    Jws<Claims> jws = mock(Jws.class);
    Claims claims = mock(Claims.class);
    when(jwtUtil.parseToken("token")).thenReturn(jws);
    when(jws.getPayload()).thenReturn(claims);
    when(claims.getSubject()).thenReturn("42");
    when(claims.get("tenantId")).thenReturn(7L);
    when(claims.get("worldSlug")).thenReturn("demo");
    when(claims.get("realmSlug")).thenReturn("production");
    when(claims.get("gameInstanceId")).thenReturn("9");
    when(claims.get("pointerVersion")).thenReturn(null);
    when(claims.get("connectScopeId")).thenReturn("scope-1");
    when(claims.get("connectTokenJti")).thenReturn("jti");
    when(claims.get("connectRequestId")).thenReturn("request-connect-1");
    when(claims.get("gatewayRequestId")).thenReturn("request-1");

    FirstPartyConnectContextService service =
        new FirstPartyConnectContextService(properties, jwtUtil);

    assertTrue(service.parse("token").isEmpty());
  }
}
