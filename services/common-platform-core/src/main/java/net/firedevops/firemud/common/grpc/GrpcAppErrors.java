package net.firedevops.firemud.common.grpc;

import io.micrometer.core.instrument.MeterRegistry;
import net.firedevops.firemud.shared.v1.ErrorDetail;

/** Shared helpers for gRPC application-level error details and metrics. */
public final class GrpcAppErrors {
  public static final String OK_CODE = "OK";
  private static final String APP_ERROR_METRIC = "grpc.app_error";
  private static final String CODE_TAG = "code";
  private static final String UNKNOWN_CODE = "UNKNOWN";

  private GrpcAppErrors() {}

  public static ErrorDetail ok(String message) {
    return ErrorDetail.newBuilder().setCode(OK_CODE).setMessage(message).build();
  }

  public static ErrorDetail error(MeterRegistry meterRegistry, String code, String message) {
    String normalizedCode = normalizeCode(code);
    meterRegistry.counter(APP_ERROR_METRIC, CODE_TAG, normalizedCode).increment();
    return ErrorDetail.newBuilder().setCode(normalizedCode).setMessage(message).build();
  }

  public static void countIfError(MeterRegistry meterRegistry, ErrorDetail detail) {
    if (detail == null || OK_CODE.equals(detail.getCode())) {
      return;
    }
    meterRegistry.counter(APP_ERROR_METRIC, CODE_TAG, normalizeCode(detail.getCode())).increment();
  }

  private static String normalizeCode(String code) {
    return code == null || code.isBlank() ? UNKNOWN_CODE : code;
  }
}
