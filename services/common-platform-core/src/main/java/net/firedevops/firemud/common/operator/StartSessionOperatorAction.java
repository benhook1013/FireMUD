package net.firedevops.firemud.common.operator;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Immutable typed action covered by the StartSession operator schema. */
public record StartSessionOperatorAction(
    String actionFamilySchemaId,
    String actionFamilySchemaVersion,
    Scope scope,
    Target target,
    ExpectedVersion expectedVersion,
    Mutation mutation,
    String auditReason) {

  public static final String ACTION_FAMILY_SCHEMA_ID = "firemud.game-session.start-session";
  public static final String ACTION_FAMILY_SCHEMA_VERSION = "1";
  public static final String ACTION_FAMILY = "StartSession";
  public static final String OWNER_SERVICE = "game-session-service";

  private static final Pattern WORKLOAD_NAMESPACE =
      Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  public StartSessionOperatorAction {
    if (!ACTION_FAMILY_SCHEMA_ID.equals(actionFamilySchemaId)) {
      throw new IllegalArgumentException("unsupported actionFamilySchemaId");
    }
    if (!ACTION_FAMILY_SCHEMA_VERSION.equals(actionFamilySchemaVersion)) {
      throw new IllegalArgumentException("unsupported actionFamilySchemaVersion");
    }
    Objects.requireNonNull(scope, "scope is required");
    Objects.requireNonNull(target, "target is required");
    Objects.requireNonNull(expectedVersion, "expectedVersion is required");
    if (expectedVersion != ExpectedVersion.ABSENT) {
      throw new IllegalArgumentException(
          "expectedVersion must be absent for StartSession creation");
    }
    Objects.requireNonNull(mutation, "mutation is required");
    auditReason = normalizedString(auditReason, "auditReason", 1_000);
    if (isUnicodeBlank(auditReason)) {
      throw new IllegalArgumentException("auditReason must not be blank");
    }
  }

  public enum ExpectedVersion {
    ABSENT
  }

  public String actionFamily() {
    return ACTION_FAMILY;
  }

  /** Returns the immutable ADR 0048 target owner, which is compared outside the digest tuple. */
  public String targetOwner() {
    return OWNER_SERVICE;
  }

  public record Scope(UUID tenantId, String targetNamespace) {
    public Scope {
      Objects.requireNonNull(tenantId, "tenantId is required");
      if (NIL_UUID.equals(tenantId)) {
        throw new IllegalArgumentException("tenantId must not be nil");
      }
      Objects.requireNonNull(targetNamespace, "targetNamespace is required");
      if (targetNamespace.length() > 63 || !WORKLOAD_NAMESPACE.matcher(targetNamespace).matches()) {
        throw new IllegalArgumentException("targetNamespace must be a valid workload namespace");
      }
    }
  }

  public record Target(long gameTemplateId, UUID ownerAccountId) {
    public Target {
      if (gameTemplateId <= 0) {
        throw new IllegalArgumentException("gameTemplateId must be positive");
      }
      Objects.requireNonNull(ownerAccountId, "ownerAccountId is required");
      if (NIL_UUID.equals(ownerAccountId)) {
        throw new IllegalArgumentException("ownerAccountId must not be nil");
      }
    }
  }

  public record Mutation(ClientIp clientIp) {
    public Mutation {
      Objects.requireNonNull(clientIp, "clientIp presence is required");
    }
  }

  /** The action schema accepts either an absent clientIp member or a string value. */
  public sealed interface ClientIp permits AbsentClientIp, StringClientIp {
    static ClientIp absent() {
      return new AbsentClientIp();
    }

    static ClientIp of(String value) {
      return new StringClientIp(value);
    }
  }

  public record AbsentClientIp() implements ClientIp {}

  public record StringClientIp(String value) implements ClientIp {
    public StringClientIp {
      value = normalizedString(value, "clientIp", 128);
    }
  }

  private static String normalizedString(String value, String fieldName, int byteLimit) {
    Objects.requireNonNull(value, fieldName + " is required");
    requireUnicodeScalars(value, fieldName);
    String normalized = Normalizer.normalize(value, Normalizer.Form.NFC);
    requireUnicodeScalars(normalized, fieldName);
    if (normalized.getBytes(StandardCharsets.UTF_8).length > byteLimit) {
      throw new IllegalArgumentException(fieldName + " exceeds its normalized UTF-8 byte limit");
    }
    return normalized;
  }

  private static void requireUnicodeScalars(String value, String fieldName) {
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      if (Character.isHighSurrogate(current)) {
        if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
          throw new IllegalArgumentException(fieldName + " contains malformed Unicode");
        }
        index++;
      } else if (Character.isLowSurrogate(current)) {
        throw new IllegalArgumentException(fieldName + " contains malformed Unicode");
      }
    }
  }

  private static boolean isUnicodeBlank(String value) {
    return value
        .codePoints()
        .allMatch(
            codePoint -> Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint));
  }
}
