package net.firedevops.firemud.common.grpc;

import io.grpc.ManagedChannel;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;

public abstract class AbstractBlockingGrpcClient<TStub> implements AutoCloseable {
  private final ServiceEndpointsProperties endpoints;
  private final CommonGrpcClientProperties tlsProps;
  private final GrpcChannelFactory channelFactory;

  private ManagedChannel channel;
  private TStub stub;

  protected AbstractBlockingGrpcClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory) {
    this.endpoints = endpoints.copy();
    this.tlsProps = tlsProps.copy();
    this.channelFactory = channelFactory;
  }

  protected final void initClient() throws SSLException {
    String target = configuredTarget(endpoints);
    if (target == null || target.isBlank()) {
      target = defaultTarget();
    }
    channel = channelFactory.buildChannel(target, defaultPort(), tlsProps, keepAliveEnabled());
    stub = buildStub(channel);
  }

  protected final TStub stub() {
    return stub;
  }

  protected int defaultPort() {
    return 6565;
  }

  protected boolean keepAliveEnabled() {
    return true;
  }

  protected abstract String configuredTarget(ServiceEndpointsProperties endpoints);

  protected abstract String defaultTarget();

  protected abstract TStub buildStub(ManagedChannel channel);

  @Override
  public void close() {
    if (channel != null) {
      channel.shutdown();
    }
  }
}
