package net.firedevops.firemud.gamesession.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import java.util.Optional;
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
      long accountId = parseLong(claims.getSubject());
      long tenantId = parseLong(claims.get("tenantId"));
      long gameInstanceId = parseLong(claims.get("gameInstanceId"));
      return Optional.of(
          new FirstPartyConnectContext(
              accountId,
              tenantId,
              gameInstanceId,
              stringClaim(claims, "connectTokenJti"),
              stringClaim(claims, "gatewayRequestId")));
    } catch (IllegalArgumentException | JwtException ex) {
      logger.warn("Rejecting invalid first-party connect context", ex);
      return Optional.empty();
    }
  }

  private static long parseLong(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value instanceof String text && StringUtils.hasText(text)) {
      return Long.parseLong(text);
    }
    throw new IllegalArgumentException("Missing numeric claim");
  }

  private static String stringClaim(Claims claims, String key) {
    Object value = claims.get(key);
    return value == null ? null : value.toString();
  }
}
