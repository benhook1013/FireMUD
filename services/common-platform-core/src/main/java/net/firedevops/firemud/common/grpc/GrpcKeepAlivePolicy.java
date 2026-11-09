package net.firedevops.firemud.common.grpc;

import io.grpc.ManagedChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.util.concurrent.TimeUnit;

/** Shared client and server gRPC keepalive settings. */
public final class GrpcKeepAlivePolicy {
  private static final long KEEP_ALIVE_INTERVAL_SECONDS = 30;
  private static final long KEEP_ALIVE_TIMEOUT_SECONDS = 5;
  private static final long SERVER_MINIMUM_PERMIT_INTERVAL_SECONDS = 30;

  private GrpcKeepAlivePolicy() {}

  public static void configureClient(ManagedChannelBuilder<?> builder) {
    builder
        .keepAliveTime(KEEP_ALIVE_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .keepAliveTimeout(KEEP_ALIVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .keepAliveWithoutCalls(true);
  }

  public static void configureServer(NettyServerBuilder builder) {
    builder
        .keepAliveTime(KEEP_ALIVE_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .keepAliveTimeout(KEEP_ALIVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .permitKeepAliveWithoutCalls(true)
        .permitKeepAliveTime(SERVER_MINIMUM_PERMIT_INTERVAL_SECONDS, TimeUnit.SECONDS);
  }
}
