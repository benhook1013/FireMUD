package net.firedevops.firemud.automationscripting.service.impl;

import java.util.Locale;
import net.firedevops.firemud.automationscripting.service.ScriptGameplayCommandHandoffService.HandoffResult;

/** Canonical stage, outcome, and reason mapping for automation command handoff. */
final class ScriptHandoffOutcomeSupport {
  static final String STAGE_TICK_HANDOFF = "TICK_HANDOFF";
  static final String OUTCOME_CANCELED = "canceled";
  static final String OUTCOME_INFRASTRUCTURE_ERROR = "infrastructure_error";
  static final String REASON_ROLLBACK_EPOCH_ADVANCED = "rollback_epoch_advanced";
  static final String REASON_RUNTIME_SCOPE_CHANGED = "runtime_scope_changed";
  static final String REASON_AUTHORITY_UNAVAILABLE = "authority_unavailable";
  static final String REASON_INVALID_ARGUMENT = "invalid_argument";
  static final String REASON_REMOTE_RESPONSE_INVALID = "remote_response_invalid";
  static final String REASON_RUNTIME_PAUSED = "runtime_paused";
  static final String REASON_RUNTIME_REGION_SCOPE_ADVANCED = "runtime_region_scope_advanced";
  static final String REASON_IDEMPOTENCY_CONFLICT = "idempotency_conflict";
  static final String ERROR_REMOTE_RESPONSE_INVALID = "REMOTE_RESPONSE_INVALID";

  private ScriptHandoffOutcomeSupport() {}

  static boolean isRuntimeScopeFence(HandoffResult result) {
    String code = normalizeToken(result.errorCode());
    String outcome = normalizeToken(result.outcome());
    return "STALE_TIMELINE".equals(code)
        || "RUNTIME_SCOPE_CHANGED".equals(code)
        || "RUNTIME_REGION_SCOPE_ADVANCED".equals(code)
        || "STALE_TIMELINE".equals(outcome)
        || "RUNTIME_SCOPE_CHANGED".equals(outcome)
        || "RUNTIME_REGION_SCOPE_ADVANCED".equals(outcome);
  }

  static boolean isAdmissionPause(HandoffResult result) {
    return "RUNTIME_PAUSED".equals(normalizeToken(result.errorCode()))
        || "RUNTIME_PAUSED".equals(normalizeToken(result.outcome()));
  }

  static boolean isRollbackFence(HandoffResult result) {
    return REASON_ROLLBACK_EPOCH_ADVANCED.equalsIgnoreCase(normalizeToken(result.errorCode()))
        || REASON_ROLLBACK_EPOCH_ADVANCED.equalsIgnoreCase(normalizeToken(result.outcome()));
  }

  static boolean isRetryable(HandoffResult result) {
    String code = normalizeToken(result.errorCode());
    String outcome = normalizeToken(result.outcome());
    if (isRuntimeScopeFence(result)
        || isAdmissionPause(result)
        || "ROLLBACK_EPOCH_ADVANCED".equals(code)
        || "ROLLBACK_EPOCH_ADVANCED".equals(outcome)) {
      return false;
    }
    return "RETRY_QUEUED".equals(outcome)
        || switch (code) {
          case "AUTH_UNAVAILABLE",
              "AUTHORITY_UNAVAILABLE",
              "GAME_SESSION_UNAVAILABLE",
              "UNAVAILABLE",
              "QUEUE_UNAVAILABLE" ->
              true;
          default -> false;
        };
  }

  static String canonicalInfrastructureReason(HandoffResult result) {
    String code = normalizeToken(result.errorCode());
    if (code.isBlank()) {
      code = normalizeToken(result.outcome());
    }
    return switch (code) {
      case "GAME_SESSION_UNAVAILABLE", "UNAVAILABLE", "AUTHORITY_UNAVAILABLE", "REMOTE_REJECTED" ->
          REASON_AUTHORITY_UNAVAILABLE;
      case "IDEMPOTENCY_CONFLICT" -> REASON_IDEMPOTENCY_CONFLICT;
      case "ROLLBACK_EPOCH_ADVANCED" -> REASON_ROLLBACK_EPOCH_ADVANCED;
      case "STALE_TIMELINE", "RUNTIME_SCOPE_CHANGED" -> REASON_RUNTIME_SCOPE_CHANGED;
      case "RUNTIME_REGION_SCOPE_ADVANCED" -> REASON_RUNTIME_SCOPE_CHANGED;
      case "INVALID_ARGUMENT" -> REASON_INVALID_ARGUMENT;
      case "REMOTE_RESPONSE_INVALID" -> REASON_REMOTE_RESPONSE_INVALID;
      case "RUNTIME_PAUSED" -> REASON_RUNTIME_PAUSED;
      case "QUEUE_UNAVAILABLE" -> REASON_AUTHORITY_UNAVAILABLE;
      default -> REASON_AUTHORITY_UNAVAILABLE;
    };
  }

  static String canonicalHandoffReason(String reason, String outcome) {
    String normalizedReason = normalizeToken(reason);
    String normalizedOutcome = normalizeToken(outcome);
    String canonicalToken = normalizedReason.isBlank() ? normalizedOutcome : normalizedReason;
    return switch (canonicalToken) {
      case "RUNTIME_PAUSED" -> REASON_RUNTIME_PAUSED;
      case "ROLLBACK_EPOCH_ADVANCED" -> REASON_ROLLBACK_EPOCH_ADVANCED;
      case "RUNTIME_SCOPE_CHANGED", "RUNTIME_REGION_SCOPE_ADVANCED", "STALE_TIMELINE" ->
          REASON_RUNTIME_SCOPE_CHANGED;
      default ->
          canonicalInfrastructureReason(
              new HandoffResult(false, normalizedOutcome, "", "", "", normalizedReason));
    };
  }

  private static String normalize(String value) {
    return value == null ? "" : value;
  }

  private static String normalizeToken(String value) {
    return normalize(value).trim().replace('-', '_').toUpperCase(Locale.ROOT);
  }
}
