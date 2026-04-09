package net.firedevops.firemud.common.grpc;

import io.grpc.ManagedChannel;
import io.grpc.stub.AbstractStub;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;

public abstract class AbstractBlockingGrpcClient<TStub extends AbstractStub<TStub>>
    implements AutoCloseable {
  private final ServiceEndpointsProperties endpoints;
  private final CommonGrpcClientProperties tlsProps;
  private final GrpcChannelFactory channelFactory;
  private final BlockingGrpcStubCustomizer stubCustomizer;

  private ManagedChannel channel;
  private TStub stub;

  protected AbstractBlockingGrpcClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory) {
    this(endpoints, tlsProps, channelFactory, BlockingGrpcStubCustomizer.noop());
  }

  protected AbstractBlockingGrpcClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory,
      BlockingGrpcStubCustomizer stubCustomizer) {
    this.endpoints = endpoints.copy();
    this.tlsProps = tlsProps.copy();
    this.channelFactory = channelFactory;
    this.stubCustomizer = stubCustomizer;
  }

  protected final synchronized void initClient() throws SSLException {
    String target = configuredTarget(endpoints);
    if (target == null || target.isBlank()) {
      target = defaultTarget();
    }
    ManagedChannel newChannel =
        channelFactory.buildChannel(target, defaultPort(), tlsProps, keepAliveEnabled());
    ManagedChannel previousChannel = channel;
    channel = newChannel;
    stub = buildStub(channel);
    if (previousChannel != null) {
      previousChannel.shutdown();
    }
  }

  protected final TStub stub() {
    return stub;
  }

  protected final TStub applyStubCustomizer(TStub stub) {
    return stubCustomizer.customize(stub);
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
