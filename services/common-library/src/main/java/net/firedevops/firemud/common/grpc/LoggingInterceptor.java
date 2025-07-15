package net.firedevops.firemud.common.grpc;

import io.grpc.*;
import io.opentelemetry.api.trace.Span;
import java.util.UUID;
import net.firedevops.firemud.common.LoggingUtil;
import org.slf4j.Logger;
import org.slf4j.MDC;

/** Logs gRPC method calls and duration. */
public class LoggingInterceptor implements ServerInterceptor {
  private static final Logger logger = LoggingUtil.getLogger(LoggingInterceptor.class);

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    String method = call.getMethodDescriptor().getFullMethodName();
    long start = System.currentTimeMillis();

    String correlationId = MDC.get("correlationId");
    boolean addedCorrelationId = false;
    if (correlationId == null) {
      correlationId = UUID.randomUUID().toString();
      MDC.put("correlationId", correlationId);
      addedCorrelationId = true;
    }
    String traceId = Span.current().getSpanContext().getTraceId();
    MDC.put("traceId", traceId);

    final String cid = correlationId;
    final String tid = traceId;
    final boolean removeCorrelation = addedCorrelationId;

    logger.info("gRPC call: {} correlationId={} traceId={}", method, cid, tid);

    ServerCall.Listener<ReqT> listener = next.startCall(call, headers);
    return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(listener) {
      @Override
      public void onComplete() {
        long duration = System.currentTimeMillis() - start;
        logger.info(
            "gRPC call completed: {} ({} ms) correlationId={} traceId={}",
            method,
            duration,
            cid,
            tid);
        MDC.remove("traceId");
        if (removeCorrelation) {
          MDC.remove("correlationId");
        }
        super.onComplete();
      }

      @Override
      public void onCancel() {
        long duration = System.currentTimeMillis() - start;
        logger.warn(
            "gRPC call cancelled: {} ({} ms) correlationId={} traceId={}",
            method,
            duration,
            cid,
            tid);
        MDC.remove("traceId");
        if (removeCorrelation) {
          MDC.remove("correlationId");
        }
        super.onCancel();
      }
    };
  }
}
