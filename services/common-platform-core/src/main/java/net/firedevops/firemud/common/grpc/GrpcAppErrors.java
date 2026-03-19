package net.firedevops.firemud.common.grpc;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.slf4j.Logger;
import org.springframework.util.StringUtils;

/** Shared helpers for gRPC application-level error details and metrics. */
public final class GrpcAppErrors {
  public static final String OK_CODE = "OK";
  private static final String APP_ERROR_METRIC = "grpc.app_error";
  private static final String CODE_TAG = "code";
  private static final String UNKNOWN_CODE = "UNKNOWN";
  private static final String APP_ERROR_SPAN_ATTRIBUTE = "grpc.app_error";
  private static final String APP_ERROR_CODE_SPAN_ATTRIBUTE = "grpc.app_error.code";
  private static final String APP_ERROR_MESSAGE_SPAN_ATTRIBUTE = "grpc.app_error.message";

  private GrpcAppErrors() {}

  public static ErrorDetail ok(String message) {
    return ErrorDetail.newBuilder().setCode(OK_CODE).setMessage(message).build();
  }

  public static ErrorDetail error(MeterRegistry meterRegistry, String code, String message) {
    String normalizedCode = normalizeCode(code);
    meterRegistry.counter(APP_ERROR_METRIC, CODE_TAG, normalizedCode).increment();
    ErrorDetail detail =
        ErrorDetail.newBuilder()
            .setCode(normalizedCode)
            .setMessage(defaultMessage(message, normalizedCode))
            .build();
    tagCurrentSpan(detail);
    return detail;
  }

  public static ErrorDetail error(
      MeterRegistry meterRegistry, Logger logger, String operation, String code, String message) {
    ErrorDetail detail = error(meterRegistry, code, message);
    logAppError(logger, operation, detail, null);
    return detail;
  }

  public static ErrorDetail internal(
      MeterRegistry meterRegistry, Logger logger, String operation, Throwable cause) {
    ErrorDetail detail = error(meterRegistry, "INTERNAL", "Internal error");
    logAppError(logger, operation, detail, cause);
    return detail;
  }

  public static void countIfError(MeterRegistry meterRegistry, ErrorDetail detail) {
    if (detail == null || OK_CODE.equals(detail.getCode())) {
      return;
    }
    meterRegistry.counter(APP_ERROR_METRIC, CODE_TAG, normalizeCode(detail.getCode())).increment();
    tagCurrentSpan(detail);
  }

  public static ErrorDetail normalize(
      ErrorDetail detail, String defaultCode, String defaultMessage) {
    if (detail == null) {
      return ErrorDetail.newBuilder()
          .setCode(normalizeCode(defaultCode))
          .setMessage(defaultMessage(defaultMessage, defaultCode))
          .build();
    }
    return ErrorDetail.newBuilder(detail)
        .setCode(normalizeCode(detail.getCode()))
        .setMessage(defaultMessage(detail.getMessage(), defaultMessage))
        .build();
  }

  public static void logIfError(Logger logger, String operation, ErrorDetail detail) {
    if (detail == null || OK_CODE.equals(detail.getCode())) {
      return;
    }
    logAppError(logger, operation, detail, null);
  }

  private static String normalizeCode(String code) {
    return code == null || code.isBlank() ? UNKNOWN_CODE : code;
  }

  private static String defaultMessage(String message, String fallbackCode) {
    return StringUtils.hasText(message) ? message : normalizeCode(fallbackCode);
  }

  private static void tagCurrentSpan(ErrorDetail detail) {
    Span currentSpan = Span.current();
    if (!currentSpan.getSpanContext().isValid()) {
      return;
    }
    currentSpan.setAttribute(APP_ERROR_SPAN_ATTRIBUTE, true);
    currentSpan.setAttribute(APP_ERROR_CODE_SPAN_ATTRIBUTE, normalizeCode(detail.getCode()));
    currentSpan.setAttribute(APP_ERROR_MESSAGE_SPAN_ATTRIBUTE, detail.getMessage());
  }

  private static void logAppError(
      Logger logger, String operation, ErrorDetail detail, Throwable cause) {
    if (logger == null || detail == null) {
      return;
    }
    if (cause == null) {
      logger.warn("{} returned app error {}: {}", operation, detail.getCode(), detail.getMessage());
      return;
    }
    logger.warn(
        "{} returned app error {}: {}", operation, detail.getCode(), detail.getMessage(), cause);
  }
}
