package net.firedevops.firemud.automationscripting.service.impl;

import java.util.Locale;
import net.firedevops.firemud.automationscripting.v1.AutomationAdmissionMode;
import net.firedevops.firemud.automationscripting.v1.TriggerMode;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.shared.v1.ErrorDetail;

final class AutomationControlPlaneSupport {
  private AutomationControlPlaneSupport() {}

  static ErrorDetail authorizationError(AdminAuthorizationException ex) {
    return ErrorDetail.newBuilder()
        .setCode("PERMISSION_DENIED")
        .setMessage(ex.getMessage())
        .build();
  }

  static ErrorDetail invalidArgument(String message) {
    return ErrorDetail.newBuilder().setCode("INVALID_ARGUMENT").setMessage(message).build();
  }

  static ErrorDetail notFound(String method, String reason) {
    return ErrorDetail.newBuilder()
        .setCode("NOT_FOUND")
        .setMessage(method + " failed: " + reason)
        .build();
  }

  static AutomationAdmissionMode toProtoMode(String mode) {
    return switch (mode) {
      case "NORMAL" -> AutomationAdmissionMode.AUTOMATION_ADMISSION_MODE_NORMAL;
      case "PAUSED_FOR_ROLLBACK" ->
          AutomationAdmissionMode.AUTOMATION_ADMISSION_MODE_PAUSED_FOR_ROLLBACK;
      default -> AutomationAdmissionMode.AUTOMATION_ADMISSION_MODE_UNSPECIFIED;
    };
  }

  static TriggerMode toTriggerMode(String triggerMode) {
    return switch (triggerMode) {
      case "TRIGGER_MODE_CATCH_UP" -> TriggerMode.TRIGGER_MODE_CATCH_UP;
      case "TRIGGER_MODE_NORMAL" -> TriggerMode.TRIGGER_MODE_NORMAL;
      default -> TriggerMode.TRIGGER_MODE_UNSPECIFIED;
    };
  }

  static String requireMode(AutomationAdmissionMode mode) {
    return switch (mode) {
      case AUTOMATION_ADMISSION_MODE_NORMAL -> "NORMAL";
      case AUTOMATION_ADMISSION_MODE_PAUSED_FOR_ROLLBACK -> "PAUSED_FOR_ROLLBACK";
      case UNRECOGNIZED, AUTOMATION_ADMISSION_MODE_UNSPECIFIED ->
          throw new IllegalArgumentException("mode is required");
    };
  }

  static PlayableStateScope toPlayableStateScope(String playableStateScope) {
    return switch (normalize(playableStateScope)) {
      case "SHARED" -> PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED;
      case "ISOLATED" -> PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED;
      default -> PlayableStateScope.PLAYABLE_STATE_SCOPE_UNSPECIFIED;
    };
  }

  static String normalizePlayableStateScope(PlayableStateScope playableStateScope) {
    return switch (playableStateScope) {
      case PLAYABLE_STATE_SCOPE_SHARED -> "SHARED";
      case PLAYABLE_STATE_SCOPE_ISOLATED -> "ISOLATED";
      default -> "";
    };
  }

  static String normalize(String value) {
    return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
  }

  static String emptyIfNull(String value) {
    return value == null ? "" : value;
  }
}
