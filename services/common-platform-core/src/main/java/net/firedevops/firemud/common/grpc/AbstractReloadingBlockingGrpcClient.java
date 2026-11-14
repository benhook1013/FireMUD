package net.firedevops.firemud.common.grpc;

import io.grpc.ManagedChannel;
import io.grpc.stub.AbstractStub;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
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

  // TLS channel construction uses the client monitor and must not delay call selection.
  private final Object generationLock = new Object();
  private ChannelGeneration generation;
  private volatile TStub stub;
  private TlsCertificateWatcher watcher;
  private volatile boolean closed;
  private volatile boolean explicitlyInitialized;

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
    return requirePublishedInitializedStub(unavailableMessage);
  }

  /** Takes the last published stub without waiting for a TLS reload. */
  protected final TStub requirePublishedInitializedStub(String unavailableMessage) {
    TStub current = stub;
    if (closed || !explicitlyInitialized || current == null) {
      throw new IllegalStateException(unavailableMessage);
    }
    return current;
  }

  /** Keeps the selected TLS channel available for one bounded blocking call. */
  protected final <R> R withPublishedInitializedStub(
      String unavailableMessage, Function<TStub, R> call) {
    ChannelGeneration selected;
    TStub selectedStub;
    synchronized (generationLock) {
      selectedStub = requirePublishedInitializedStub(unavailableMessage);
      selected = generation;
      if (selected == null) {
        throw new IllegalStateException(unavailableMessage);
      }
      selected.activeCalls++;
    }
    try {
      return call.apply(selectedStub);
    } finally {
      boolean shutdown;
      synchronized (generationLock) {
        selected.activeCalls--;
        shutdown = selected.retired && selected.activeCalls == 0;
      }
      if (shutdown) {
        selected.channel.shutdown();
      }
    }
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
    retire(publishGeneration(new ChannelGeneration(newChannel), newStub));
  }

  private ChannelGeneration publishGeneration(
      ChannelGeneration replacement, TStub replacementStub) {
    synchronized (generationLock) {
      ChannelGeneration previous = generation;
      generation = replacement;
      stub = replacementStub;
      return previous;
    }
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
    ChannelGeneration generationToRetire;
    synchronized (this) {
      closed = true;
      explicitlyInitialized = false;
      watcherToClose = watcher;
      watcher = null;
      generationToRetire = publishGeneration(null, stub);
    }
    try {
      if (watcherToClose != null) {
        watcherToClose.close();
      }
    } finally {
      retire(generationToRetire);
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

    ChannelGeneration initialGeneration;
    synchronized (this) {
      closed = true;
      initialGeneration = publishGeneration(null, null);
    }
    try {
      retire(initialGeneration);
    } catch (RuntimeException cleanupFailure) {
      failure.addSuppressed(cleanupFailure);
    }
  }

  private void retire(ChannelGeneration previous) {
    synchronized (generationLock) {
      if (previous == null || previous.retired) {
        return;
      }
      previous.retired = true;
      if (previous.activeCalls != 0) {
        return;
      }
    }
    previous.channel.shutdown();
  }

  private static final class ChannelGeneration {
    private final ManagedChannel channel;
    private int activeCalls;
    private boolean retired;

    private ChannelGeneration(ManagedChannel channel) {
      this.channel = channel;
    }
  }
}
