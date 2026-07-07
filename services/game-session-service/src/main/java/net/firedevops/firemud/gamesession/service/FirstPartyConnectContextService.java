package net.firedevops.firemud.gamesession.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import java.util.Optional;
import net.firedevops.firemud.common.security.JwtClaims;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.gamesession.config.FirstPartyConnectContextProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected configuration properties are framework-managed singletons")
public class FirstPartyConnectContextService {
  private static final Logger logger =
      LoggerFactory.getLogger(FirstPartyConnectContextService.class);

  private final FirstPartyConnectContextProperties properties;
  private final JwtUtil jwtUtil;

  public FirstPartyConnectContextService(
      FirstPartyConnectContextProperties properties,
      @Qualifier("firstPartyConnectContextJwtUtil") JwtUtil jwtUtil) {
    this.properties = properties;
    this.jwtUtil = jwtUtil;
  }

  public Optional<FirstPartyConnectContext> parse(String token) {
    if (!StringUtils.hasText(token) || !StringUtils.hasText(properties.getJwtSecret())) {
      return Optional.empty();
    }
    try {
      Claims claims = jwtUtil.parseToken(token).getPayload();
      long accountId = requireAccountId(claims);
      long tenantId = parseLong(claims.get("tenantId"), "tenantId");
      long gameInstanceId = parseLong(claims.get("gameInstanceId"), "gameInstanceId");
      long pointerVersion = parseLong(claims.get("pointerVersion"), "pointerVersion");
      return Optional.of(
          new FirstPartyConnectContext(
              accountId,
              tenantId,
              requiredTextClaim(claims, "worldSlug"),
              requiredTextClaim(claims, "realmSlug"),
              gameInstanceId,
              pointerVersion,
              requiredTextClaim(claims, "connectScopeId"),
              stringClaim(claims, "connectTokenJti"),
              requiredTextClaim(claims, "connectRequestId"),
              stringClaim(claims, "gatewayRequestId")));
    } catch (IllegalArgumentException | JwtException ex) {
      logger.warn("Rejecting invalid first-party connect context", ex);
      return Optional.empty();
    }
  }

  private static long requireAccountId(Claims claims) {
    return JwtClaims.requireSignedActorAccountId(
        claims, "first-party connect context account subject mismatch");
  }

  private static long parseLong(Object value, String claimName) {
    return JwtClaims.requireLong(value, claimName, false);
  }

  private static String stringClaim(Claims claims, String key) {
    Object value = claims.get(key);
    return value == null ? null : value.toString();
  }

  private static String requiredTextClaim(Claims claims, String key) {
    return JwtClaims.requireText(claims.get(key), key);
  }
}
