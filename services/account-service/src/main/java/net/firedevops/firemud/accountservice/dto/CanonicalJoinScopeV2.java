package net.firedevops.firemud.accountservice.dto;

import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Immutable, typed v2 JOIN scope preimage data.
 *
 * <p>This record is evidence data, not an authentication capability or an authorized producer.
 * Constructing it does not establish caller identity, scope ownership, authority, or freshness.
 * Those checks remain with the Account caller that resolves and verifies the source evidence.
 */
public record CanonicalJoinScopeV2(
    String connectScopeId,
    UUID accountId,
    UUID tenantId,
    UUID realmId,
    String tenantSlug,
    String worldSlug,
    String realmSlug,
    UUID playableStateNamespaceId,
    String playableStateScope,
    UUID gameInstanceId,
    long catalogRevision,
    long pointerVersion,
    String evaluatedAt,
    String connectScopeExpiresAt) {

  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Pattern UTC_RFC3339 =
      Pattern.compile(
          "([0-9]{4})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2}):([0-9]{2})(?:\\.([0-9]+))?Z");

  public CanonicalJoinScopeV2 {
    connectScopeId = requireNonEmptyUtf8("connectScopeId", connectScopeId);
    accountId = requireNonNilUuid("accountId", accountId);
    tenantId = requireNonNilUuid("tenantId", tenantId);
    realmId = requireNonNilUuid("realmId", realmId);
    tenantSlug = requireNonEmptyUtf8("tenantSlug", tenantSlug);
    worldSlug = requireNonEmptyUtf8("worldSlug", worldSlug);
    realmSlug = requireNonEmptyUtf8("realmSlug", realmSlug);
    playableStateNamespaceId =
        requireNonNilUuid("playableStateNamespaceId", playableStateNamespaceId);
    playableStateScope = requirePlayableStateScope(playableStateScope);
    gameInstanceId = requireNonNilUuid("gameInstanceId", gameInstanceId);
    if (catalogRevision <= 0L) {
      throw new IllegalArgumentException("catalogRevision must be a positive BIGINT");
    }
    if (pointerVersion <= 0L) {
      throw new IllegalArgumentException("pointerVersion must be a positive BIGINT");
    }
    evaluatedAt = requireUtcRfc3339("evaluatedAt", evaluatedAt);
    connectScopeExpiresAt = requireUtcRfc3339("connectScopeExpiresAt", connectScopeExpiresAt);
  }

  private static UUID requireNonNilUuid(String field, UUID value) {
    if (value == null || NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(field + " must be a canonical non-nil UUID");
    }
    return value;
  }

  private static String requirePlayableStateScope(String value) {
    String exact = requireNonEmptyUtf8("playableStateScope", value);
    if (!"SHARED".equals(exact) && !"ISOLATED".equals(exact)) {
      throw new IllegalArgumentException("playableStateScope must be SHARED or ISOLATED");
    }
    return exact;
  }

  private static String requireUtcRfc3339(String field, String value) {
    String exact = requireNonEmptyUtf8(field, value);
    Matcher matcher = UTC_RFC3339.matcher(exact);
    if (!matcher.matches()) {
      throw new IllegalArgumentException(field + " must be exact UTC RFC3339 with a Z designator");
    }
    try {
      LocalDate.of(
          Integer.parseInt(matcher.group(1)),
          Integer.parseInt(matcher.group(2)),
          Integer.parseInt(matcher.group(3)));
    } catch (DateTimeException | NumberFormatException exception) {
      throw new IllegalArgumentException(
          field + " must contain a valid RFC3339 calendar date", exception);
    }
    int hour = Integer.parseInt(matcher.group(4));
    int minute = Integer.parseInt(matcher.group(5));
    int second = Integer.parseInt(matcher.group(6));
    if (hour > 23 || minute > 59 || second > 60) {
      throw new IllegalArgumentException(field + " must contain a valid RFC3339 UTC time");
    }
    return exact;
  }

  private static String requireNonEmptyUtf8(String field, String value) {
    if (value == null || value.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    try {
      StandardCharsets.UTF_8
          .newEncoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .encode(CharBuffer.wrap(value));
    } catch (CharacterCodingException exception) {
      throw new IllegalArgumentException(
          field + " must contain well-formed Unicode scalar values", exception);
    }
    return value;
  }
}
