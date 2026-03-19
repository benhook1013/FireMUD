package net.firedevops.firemud.common.grpc;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.ManagedChannel;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import org.slf4j.Logger;

@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Configuration and channel references remain internal to the client")
public abstract class AbstractReloadingBlockingGrpcClient<TStub> implements AutoCloseable {
  private final ServiceEndpointsProperties endpoints;
  private final CommonGrpcClientProperties tlsProps;
  private final GrpcChannelFactory channelFactory;
  private final Logger logger;

  private ManagedChannel channel;
  private TStub stub;
  private TlsCertificateWatcher watcher;

  protected AbstractReloadingBlockingGrpcClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory,
      Class<?> loggerClass) {
    this.endpoints = endpoints.copy();
    this.tlsProps = tlsProps.copy();
    this.channelFactory = channelFactory;
    this.logger = LoggingUtil.getLogger(loggerClass);
  }

  protected final void initReloadingClient() throws SSLException, IOException {
    reloadChannel();
    if (tlsProps.isPlaintext()) {
      return;
    }
    watcher =
        TlsCertificateWatcher.createAndStart(
            List.of(
                Path.of(tlsProps.getCertChain()),
                Path.of(tlsProps.getPrivateKey()),
                Path.of(tlsProps.getCaCert())),
            this::safeReload);
  }

  protected final synchronized void reloadChannel() throws SSLException {
    String target = configuredTarget(endpoints);
    if (target == null || target.isEmpty()) {
      target = defaultTarget();
    }
    ManagedChannel newChannel =
        channelFactory.buildChannel(target, defaultPort(), tlsProps, keepAliveEnabled());
    if (channel != null) {
      channel.shutdown();
    }
    channel = newChannel;
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

  protected Logger logger() {
    return logger;
  }

  protected abstract String configuredTarget(ServiceEndpointsProperties endpoints);

  protected abstract String defaultTarget();

  protected abstract TStub buildStub(ManagedChannel channel);

  @Override
  public void close() throws IOException {
    if (watcher != null) {
      watcher.close();
    }
    if (channel != null) {
      channel.shutdown();
    }
  }

  private void safeReload() {
    try {
      reloadChannel();
    } catch (SSLException e) {
      logger.error("Failed to reload gRPC channel", e);
    }
  }
}
