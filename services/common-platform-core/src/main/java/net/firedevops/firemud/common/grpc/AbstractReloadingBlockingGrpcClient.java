package net.firedevops.firemud.common.grpc;

import io.grpc.ManagedChannel;
import io.grpc.stub.AbstractStub;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import org.slf4j.Logger;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public abstract class AbstractReloadingBlockingGrpcClient<TStub extends AbstractStub<TStub>>
    implements AutoCloseable {
  private final ServiceEndpointsProperties endpoints;
  private final CommonGrpcClientProperties tlsProps;
  private final GrpcChannelFactory channelFactory;
  private final BlockingGrpcStubCustomizer stubCustomizer;
  private final Logger logger;

  private ManagedChannel channel;
  private volatile TStub stub;
  private TlsCertificateWatcher watcher;
  private boolean closed;
  private boolean explicitlyInitialized;

  protected AbstractReloadingBlockingGrpcClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory,
      Class<?> loggerClass) {
    this(endpoints, tlsProps, channelFactory, BlockingGrpcStubCustomizer.noop(), loggerClass);
  }

  protected AbstractReloadingBlockingGrpcClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory,
      BlockingGrpcStubCustomizer stubCustomizer,
      Class<?> loggerClass) {
    this.endpoints = endpoints.copy();
    this.tlsProps = tlsProps.copy();
    this.channelFactory = channelFactory;
    this.stubCustomizer = stubCustomizer;
    this.logger = LoggingUtil.getLogger(loggerClass);
  }

  protected static final void requireNoAmbientSql(String message) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(message);
    }
  }

  protected static final CommonGrpcClientProperties requireFileBackedMtls(
      CommonGrpcClientProperties tlsProperties, String errorContext) {
    if (tlsProperties == null || tlsProperties.isPlaintext()) {
      throw new IllegalArgumentException(errorContext + " requires workload mTLS");
    }
    for (String configuredPath :
        new String[] {
          tlsProperties.getCertChain(), tlsProperties.getPrivateKey(), tlsProperties.getCaCert()
        }) {
      if (configuredPath == null || configuredPath.isBlank()) {
        throw new IllegalArgumentException(
            errorContext + " requires readable file-backed TLS material");
      }
      String pathText = configuredPath;
      if (pathText.startsWith("classpath:")) {
        throw new IllegalArgumentException(
            errorContext + " requires readable file-backed TLS material");
      }
      Path path;
      try {
        path = Path.of(pathText);
      } catch (RuntimeException malformed) {
        throw new IllegalArgumentException(
            errorContext + " requires readable file-backed TLS material");
      }
      boolean readable;
      try {
        readable = Files.isRegularFile(path) && Files.isReadable(path);
      } catch (RuntimeException inaccessible) {
        throw new IllegalArgumentException(
            errorContext + " requires readable file-backed TLS material");
      }
      if (!readable) {
        throw new IllegalArgumentException(
            errorContext + " requires readable file-backed TLS material");
      }
    }
    return tlsProperties;
  }

  protected final void initReloadingClient() throws SSLException, IOException {
    if (tlsProps.isPlaintext() || !GrpcTlsReloadPolicy.isEnabled()) {
      reloadChannel();
      return;
    }

    try {
      watcher =
          new TlsCertificateWatcher(
              List.of(
                  Path.of(tlsProps.getCertChain()),
                  Path.of(tlsProps.getPrivateKey()),
                  Path.of(tlsProps.getCaCert())),
              this::safeReload);
      reloadChannel();
      watcher.start();
    } catch (IOException | RuntimeException e) {
      cleanupAfterInitialisationFailure(e);
      throw e;
    }
  }

  protected final synchronized void initOnce(String closedMessage, String unavailableMessage)
      throws SSLException, IOException {
    if (closed) {
      throw new IllegalStateException(closedMessage);
    }
    if (explicitlyInitialized) {
      return;
    }
    initReloadingClient();
    if (stub == null) {
      throw new IllegalStateException(unavailableMessage);
    }
    explicitlyInitialized = true;
  }

  protected final synchronized TStub requireInitializedStub(String unavailableMessage) {
    if (closed || !explicitlyInitialized || stub == null) {
      throw new IllegalStateException(unavailableMessage);
    }
    return stub;
  }

  protected final synchronized void reloadChannel() throws SSLException {
    if (closed) {
      return;
    }
    String target = configuredTarget(endpoints);
    if (target == null || target.isEmpty()) {
      target = defaultTarget();
    }
    ManagedChannel newChannel =
        channelFactory.buildChannel(target, defaultPort(), tlsProps, keepAliveEnabled());
    TStub newStub;
    try {
      newStub = buildStub(newChannel);
    } catch (RuntimeException | Error e) {
      newChannel.shutdown();
      throw e;
    }
    if (channel != null) {
      channel.shutdown();
    }
    channel = newChannel;
    stub = newStub;
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

  protected final TStub applyStubCustomizer(TStub stub) {
    return stubCustomizer.customize(stub);
  }

  protected abstract String configuredTarget(ServiceEndpointsProperties endpoints);

  protected abstract String defaultTarget();

  protected abstract TStub buildStub(ManagedChannel channel);

  @Override
  public void close() throws IOException {
    TlsCertificateWatcher watcherToClose;
    synchronized (this) {
      closed = true;
      explicitlyInitialized = false;
      watcherToClose = watcher;
      watcher = null;
    }
    try {
      if (watcherToClose != null) {
        watcherToClose.close();
      }
    } finally {
      ManagedChannel channelToShutdown;
      synchronized (this) {
        channelToShutdown = channel;
        channel = null;
      }
      if (channelToShutdown != null) {
        channelToShutdown.shutdown();
      }
    }
  }

  private void safeReload() {
    try {
      reloadChannel();
    } catch (SSLException e) {
      logger.error("Failed to reload gRPC channel", e);
      throw new IllegalStateException("Failed to reload gRPC channel", e);
    }
  }

  private void cleanupAfterInitialisationFailure(Throwable failure) {
    TlsCertificateWatcher initialWatcher = watcher;
    watcher = null;
    if (initialWatcher != null) {
      try {
        initialWatcher.close();
      } catch (IOException | RuntimeException cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
      }
    }

    ManagedChannel initialChannel;
    synchronized (this) {
      closed = true;
      initialChannel = channel;
      channel = null;
      stub = null;
    }
    if (initialChannel != null) {
      try {
        initialChannel.shutdown();
      } catch (RuntimeException cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
      }
    }
  }
}
