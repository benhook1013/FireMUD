package net.firedevops.firemud.common.grpc;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.spy;

import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class GrpcKeepAlivePolicyTest {
  @Test
  void configuresClientIntervalTimeoutAndIdlePings() {
    var builder = spy(NettyChannelBuilder.forAddress("127.0.0.1", 6565));

    GrpcKeepAlivePolicy.configureClient(builder);

    InOrder calls = inOrder(builder);
    calls.verify(builder).keepAliveTime(30, TimeUnit.SECONDS);
    calls.verify(builder).keepAliveTimeout(5, TimeUnit.SECONDS);
    calls.verify(builder).keepAliveWithoutCalls(true);
  }

  @Test
  void configuresServerIntervalTimeoutAndAllowedIdlePings() {
    var builder = spy(NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 6565)));

    GrpcKeepAlivePolicy.configureServer(builder);

    InOrder calls = inOrder(builder);
    calls.verify(builder).keepAliveTime(30, TimeUnit.SECONDS);
    calls.verify(builder).keepAliveTimeout(5, TimeUnit.SECONDS);
    calls.verify(builder).permitKeepAliveWithoutCalls(true);
    calls.verify(builder).permitKeepAliveTime(30, TimeUnit.SECONDS);
  }
}
