package net.firedevops.firemud.loggingadmin.service.impl;

import net.firedevops.firemud.common.security.RequestIdValidation;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class ControlPlaneResponseReaders {
  private ControlPlaneResponseReaders() {}

  static long parseLong(String value, String field) {
    try {
      return RequestIdValidation.requirePositiveLong(value, field);
    } catch (IllegalArgumentException ex) {
      throw invalidField(field, ex);
    }
  }

  static Long parseOptionalLong(String value, String field) {
    try {
      return RequestIdValidation.parseOptionalPositiveLong(value, field);
    } catch (IllegalArgumentException ex) {
      throw invalidField(field, ex);
    }
  }

  private static ResponseStatusException invalidField(String field, IllegalArgumentException ex) {
    return new ResponseStatusException(
        HttpStatus.INTERNAL_SERVER_ERROR,
        field + " was not a positive number in control-plane response",
        ex);
  }
}
