package net.firedevops.firemud.common.grpc;

import io.grpc.*;
import net.firedevops.firemud.common.LoggingUtil;
import org.slf4j.Logger;

/** Logs gRPC method calls and duration. */
public class LoggingInterceptor implements ServerInterceptor {
  private static final Logger logger = LoggingUtil.getLogger(LoggingInterceptor.class);

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    String method = call.getMethodDescriptor().getFullMethodName();
    long start = System.currentTimeMillis();
    logger.info("gRPC call: {}", method);
    return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(
        next.startCall(call, headers)) {
      @Override
      public void onComplete() {
        long duration = System.currentTimeMillis() - start;
        logger.info("gRPC call completed: {} ({} ms)", method, duration);
        super.onComplete();
      }

      @Override
      public void onCancel() {
        long duration = System.currentTimeMillis() - start;
        logger.warn("gRPC call cancelled: {} ({} ms)", method, duration);
        super.onCancel();
      }
    };
  }
}
