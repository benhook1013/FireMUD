package net.firedevops.firemud.common.redis.contracts;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Immutable declaration of one versioned, owner-executed Redis script contract. */
public record RedisScriptDescriptor(
    String scriptId,
    String resourcePath,
    String sha256,
    String owner,
    String principal,
    RedisRole role,
    List<KeySpec> keys,
    List<ArgumentSpec> arguments,
    ScriptCategory category,
    List<OutcomeSpec> outcomes,
    ResetSensitivity resetSensitivity,
    LossClass lossClass,
    String tailLossBehavior,
    CompatibilityLevel compatibilityLevel,
    List<SupportedCoexistence> supportedCoexistence,
    String legacyPayloadShape) {

  public static final String NO_PAYLOAD_VERSION = "none";
  public static final String LEGACY_PAYLOAD_VERSION = "legacy";

  private static final Pattern SCRIPT_ID_PATTERN =
      Pattern.compile("[a-z][a-z0-9-]*(?:\\.[a-z][a-z0-9-]*)*\\.v[1-9][0-9]*");
  private static final Pattern RESOURCE_PATH_PATTERN =
      Pattern.compile("redis/(?:[a-z0-9][a-z0-9_-]*/)*[a-z0-9][a-z0-9_-]*\\.lua");
  private static final Pattern OWNER_PATTERN = Pattern.compile("[a-z][a-z0-9-]*");
  private static final Pattern PRINCIPAL_PATTERN = Pattern.compile("[a-z][a-z0-9_]*");
  private static final Pattern PREFIX_PATTERN =
      Pattern.compile("[a-z][a-z0-9_-]*(?::[a-z][a-z0-9_-]*)*:");
  private static final Pattern DECLARATION_NAME_PATTERN = Pattern.compile("[a-z][A-Za-z0-9]*");
  private static final Pattern OUTCOME_CODE_PATTERN = Pattern.compile("[A-Z][A-Z0-9_]*");
  private static final Pattern VERSION_PATTERN = Pattern.compile("v[1-9][0-9]*");

  public RedisScriptDescriptor {
    requireMatch(scriptId, SCRIPT_ID_PATTERN, "scriptId must be a supported versioned identifier");
    requireMatch(
        resourcePath,
        RESOURCE_PATH_PATTERN,
        "resourcePath must be a relative redis/ classpath Lua resource path");
    requireMatch(sha256, Pattern.compile("[0-9a-f]{64}"), "sha256 must be 64 lowercase hex digits");
    requireMatch(owner, OWNER_PATTERN, "owner must be a lowercase service identifier");
    requireMatch(principal, PRINCIPAL_PATTERN, "principal must be a lowercase Redis principal");
    Objects.requireNonNull(role, "role is required");
    Objects.requireNonNull(category, "category is required");
    Objects.requireNonNull(resetSensitivity, "resetSensitivity is required");
    Objects.requireNonNull(lossClass, "lossClass is required");
    Objects.requireNonNull(compatibilityLevel, "compatibilityLevel is required");

    keys = immutableNonEmpty(keys, "KEYS declarations are required");
    arguments =
        immutable(arguments, "ARGV declarations are required, including an explicit empty list");
    outcomes = immutableNonEmpty(outcomes, "outcomes are required");
    supportedCoexistence =
        immutableNonEmpty(
            supportedCoexistence, "supported caller/payload coexistence metadata is required");
    tailLossBehavior = requireDescription(tailLossBehavior, "tailLossBehavior");
    legacyPayloadShape = optionalDescription(legacyPayloadShape, "legacyPayloadShape");

    Set<String> keyNames = new HashSet<>();
    for (KeySpec key : keys) {
      if (!keyNames.add(key.name())) {
        throw new IllegalArgumentException("duplicate KEYS declaration name: " + key.name());
      }
    }

    Set<String> argumentNames = new HashSet<>();
    for (ArgumentSpec argument : arguments) {
      if (!argumentNames.add(argument.name())) {
        throw new IllegalArgumentException("duplicate ARGV declaration name: " + argument.name());
      }
    }

    Set<String> outcomeCodes = new HashSet<>();
    for (OutcomeSpec outcome : outcomes) {
      if (!outcomeCodes.add(outcome.code())) {
        throw new IllegalArgumentException("duplicate outcome code: " + outcome.code());
      }
    }

    Set<SupportedCoexistence> combinations = new HashSet<>();
    boolean supportsLegacyPayload = false;
    for (SupportedCoexistence combination : supportedCoexistence) {
      if (!combinations.add(combination)) {
        throw new IllegalArgumentException("duplicate caller/payload coexistence declaration");
      }
      supportsLegacyPayload |= combination.payloadVersion().equals(LEGACY_PAYLOAD_VERSION);
    }
    if (supportsLegacyPayload && legacyPayloadShape == null) {
      throw new IllegalArgumentException(
          "legacyPayloadShape is required when a versionless payload is supported");
    }
    if (!supportsLegacyPayload && legacyPayloadShape != null) {
      throw new IllegalArgumentException(
          "legacyPayloadShape is only valid when a legacy payload is supported");
    }

    if (keys.size() > 1
        && keys.stream().anyMatch(key -> key.hashTagDeclaration() != HashTagDeclaration.REQUIRED)) {
      throw new IllegalArgumentException(
          "every KEYS declaration in a multi-key script must require a hash tag");
    }
  }

  public List<KeySpec> keys() {
    return List.copyOf(keys);
  }

  public List<ArgumentSpec> arguments() {
    return List.copyOf(arguments);
  }

  public List<OutcomeSpec> outcomes() {
    return List.copyOf(outcomes);
  }

  public List<SupportedCoexistence> supportedCoexistence() {
    return List.copyOf(supportedCoexistence);
  }

  public enum RedisRole {
    COORDINATION,
    CACHE_RATE_LIMIT
  }

  public enum HashTagDeclaration {
    NOT_REQUIRED,
    REQUIRED
  }

  public enum ScriptCategory {
    TICK_LOCK,
    TIMER_QUEUE,
    SESSION_CAS,
    SESSION_TO_REGION_BRIDGE,
    MAINTENANCE,
    CACHE,
    RATE_LIMIT
  }

  public enum OutcomeCategory {
    SUCCESS,
    REPLAY,
    STALE,
    VALIDATION_FAILURE,
    CONTENTION,
    DEFERRAL
  }

  public enum MutationEffect {
    MUTATING,
    NON_MUTATING
  }

  public enum ResetSensitivity {
    NONE,
    SESSION,
    REGION,
    TENANT,
    CLUSTER
  }

  /** Work classes from ADR 0058. */
  public enum LossClass {
    ACCEPTED_PLAYER_COMMAND,
    STAGED_EFFECT_OR_RETRY,
    CORRECTNESS_BEARING_TIMER,
    LOSSY_TIMER_OR_ADVISORY_HINT,
    SESSION_LEASE_CACHE_OR_WAKE_UP,
    PREMIUM_FINANCIAL_CROSS_TENANT_OR_UNIQUE_EXTERNAL_EFFECT,
    EMPTY_REDIS_COLD_START_OR_RESET
  }

  public enum CompatibilityLevel {
    COMPATIBLE,
    REQUIRES_REGION_RESET,
    REQUIRES_TENANT_RESET,
    REQUIRES_CLUSTER_RESET
  }

  public record KeySpec(String name, String ownedPrefix, HashTagDeclaration hashTagDeclaration) {
    public KeySpec {
      requireMatch(name, DECLARATION_NAME_PATTERN, "KEYS name must be a supported identifier");
      requireMatch(ownedPrefix, PREFIX_PATTERN, "ownedPrefix must be a colon-delimited key prefix");
      Objects.requireNonNull(hashTagDeclaration, "hashTagDeclaration is required");
    }
  }

  public record ArgumentSpec(String name) {
    public ArgumentSpec {
      requireMatch(name, DECLARATION_NAME_PATTERN, "ARGV name must be a supported identifier");
    }
  }

  public record OutcomeSpec(String code, OutcomeCategory category, MutationEffect effect) {
    public OutcomeSpec {
      requireMatch(code, OUTCOME_CODE_PATTERN, "outcome code must be uppercase and stable");
      Objects.requireNonNull(category, "outcome category is required");
      Objects.requireNonNull(effect, "outcome mutation effect is required");
      if (category != OutcomeCategory.SUCCESS && effect != MutationEffect.NON_MUTATING) {
        throw new IllegalArgumentException(
            "replay, stale, validation, contention, and deferral outcomes must be non-mutating");
      }
    }
  }

  /** A caller version and payload version that may coexist for this script version. */
  public record SupportedCoexistence(String callerVersion, String payloadVersion) {
    public SupportedCoexistence {
      requireMatch(callerVersion, VERSION_PATTERN, "callerVersion must use vN syntax");
      if (!NO_PAYLOAD_VERSION.equals(payloadVersion)
          && !LEGACY_PAYLOAD_VERSION.equals(payloadVersion)) {
        requireMatch(
            payloadVersion, VERSION_PATTERN, "payloadVersion must use vN, none, or legacy");
      }
    }
  }

  private static void requireMatch(String value, Pattern pattern, String message) {
    if (value == null || !pattern.matcher(value).matches()) {
      throw new IllegalArgumentException(message);
    }
  }

  private static String requireDescription(String value, String fieldName) {
    if (value == null || value.isBlank() || !value.equals(value.trim())) {
      throw new IllegalArgumentException(fieldName + " is required and must be trimmed");
    }
    return value;
  }

  private static String optionalDescription(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      return null;
    }
    if (!value.equals(value.trim())) {
      throw new IllegalArgumentException(fieldName + " must be trimmed");
    }
    return value;
  }

  private static <T> List<T> immutable(List<T> values, String message) {
    if (values == null) {
      throw new IllegalArgumentException(message);
    }
    return List.copyOf(values);
  }

  private static <T> List<T> immutableNonEmpty(List<T> values, String message) {
    List<T> copy = immutable(values, message);
    if (copy.isEmpty()) {
      throw new IllegalArgumentException(message);
    }
    return copy;
  }
}
