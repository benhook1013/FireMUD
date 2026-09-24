package net.firedevops.firemud.springcloudgateway.config;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.ssl.ApplicationProtocolNegotiator;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.util.concurrent.GlobalEventExecutor;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSessionContext;
import net.firedevops.firemud.common.grpc.TlsCertificateWatcher;
import net.firedevops.firemud.springcloudgateway.filter.TcpProxyTrustPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.stereotype.Component;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/** Owns the internal-only TLS listener used by the TCP Proxy WebSocket bridge. */
@Component
public final class TcpProxyTlsListener implements SmartLifecycle {
  private static final Logger LOG = LoggerFactory.getLogger(TcpProxyTlsListener.class);
  private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);

  private final GatewayTcpProxyListenerProperties properties;
  private final TcpProxyTrustPolicy trustPolicy;
  private final HttpHandler httpHandler;
  private final Object reloadMonitor = new Object();
  private final AtomicReference<SslContext> activeSslContext = new AtomicReference<>();
  private volatile DisposableServer server;
  private volatile boolean running;
  private volatile ChannelGroup acceptedChannels;
  private volatile TlsCertificateWatcher certificateWatcher;
  private volatile boolean tlsMaterialReloadHealthy;
  private volatile ScheduledExecutorService expiryExecutor;
  private volatile ScheduledFuture<?> expiryTask;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "Injected configuration and policy are framework-owned singleton dependencies.")
  public TcpProxyTlsListener(
      GatewayTcpProxyListenerProperties properties,
      TcpProxyTrustPolicy trustPolicy,
      @Lazy @Qualifier("httpHandler") HttpHandler httpHandler) {
    this.properties = Objects.requireNonNull(properties);
    this.trustPolicy = Objects.requireNonNull(trustPolicy);
    this.httpHandler = Objects.requireNonNull(httpHandler);
  }

  @Override
  public synchronized void start() {
    if (!properties.isEnabled() || isRunning()) {
      return;
    }
    DisposableServer retainedServer = server;
    if (retainedServer != null && !retainedServer.isDisposed()) {
      LOG.warn("TCP Proxy internal TLS listener has a retained server cleanup handle");
      return;
    }
    try {
      tlsMaterialReloadHealthy = false;
      String bindAddress = requiredBindAddress(properties.getBindAddress());
      synchronized (reloadMonitor) {
        TlsCertificateWatcher watcher =
            new TlsCertificateWatcher(watchedCredentialPaths(), this::reloadSslContext);
        certificateWatcher = watcher;
        watcher.start();
        activeSslContext.set(buildSslContext());
        tlsMaterialReloadHealthy = true;
      }
      ChannelGroup channels =
          new DefaultChannelGroup("tcp-proxy-internal-tls", GlobalEventExecutor.INSTANCE, true);
      acceptedChannels = channels;
      ReactorHttpHandlerAdapter adapter =
          new ReactorHttpHandlerAdapter(new InternalOnlyHttpHandler(httpHandler));
      DisposableServer boundServer =
          HttpServer.create()
              .host(bindAddress)
              .port(properties.getPort())
              .secure(spec -> spec.sslContext(new ReloadingSslContext(activeSslContext)))
              .doOnConnection(connection -> channels.add(connection.channel()))
              .handle(adapter)
              .bindNow();
      server = boundServer;
      running = true;
      LOG.info(
          "TCP Proxy internal TLS listener started address={} port={} profile={}",
          bindAddress,
          boundServer.port(),
          trustPolicy.profileName());
      scheduleProfileExpiry();
    } catch (RuntimeException ex) {
      stopAfterStartupFailure(ex);
      throw ex;
    } catch (Exception ex) {
      stopAfterStartupFailure(ex);
      throw new IllegalStateException("Unable to start TCP Proxy internal TLS listener", ex);
    }
  }

  private void stopAfterStartupFailure(Throwable startupFailure) {
    try {
      stop();
    } catch (RuntimeException cleanupFailure) {
      startupFailure.addSuppressed(cleanupFailure);
    }
  }

  private void scheduleProfileExpiry() {
    Duration untilExpiry = trustPolicy.timeUntilProfileExpiry();
    if (untilExpiry == null) {
      return;
    }
    if (untilExpiry.isZero() || untilExpiry.isNegative()) {
      LOG.warn(
          "TCP Proxy internal TLS listener trust profile was already expired at listener startup; terminating listener and bridges profile={}",
          trustPolicy.profileName());
    }
    long delayMillis = Math.max(1L, untilExpiry.toMillis());
    expiryExecutor =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "tcp-proxy-trust-profile-expiry");
              thread.setDaemon(true);
              return thread;
            });
    expiryTask =
        expiryExecutor.schedule(
            () -> {
              LOG.warn(
                  "TCP Proxy internal TLS listener trust profile expired; terminating listener and bridges profile={}",
                  trustPolicy.profileName());
              stop();
            },
            delayMillis,
            TimeUnit.MILLISECONDS);
  }

  private SslContext buildSslContext() throws Exception {
    CredentialSnapshot snapshot =
        captureCredentialSnapshot(
            configuredPath(properties.getCertificateChainPath(), "certificate chain"),
            configuredPath(properties.getPrivateKeyPath(), "private key"),
            trustPolicy.requiresClientCertificate()
                ? configuredPath(properties.getTrustedClientCaPath(), "trusted client CA")
                : null);
    snapshot.requireConsistentCertificateKeyGeneration();
    File certificate =
        requiredFile(snapshot.certificate.readPath.toString(), "certificate chain");
    File privateKey = requiredFile(snapshot.privateKey.readPath.toString(), "private key");
    SslContextBuilder builder = SslContextBuilder.forServer(certificate, privateKey);
    if (trustPolicy.requiresClientCertificate()) {
      File clientCa = requiredFile(snapshot.clientCa.readPath.toString(), "trusted client CA");
      builder.trustManager(clientCa).clientAuth(ClientAuth.REQUIRE);
    } else {
      builder.clientAuth(ClientAuth.NONE);
    }
    SslContext context = builder.protocols("TLSv1.3", "TLSv1.2").build();
    if (!snapshot.isCurrent()) {
      throw new IOException(
          "TCP Proxy listener TLS material changed while its context was loading");
    }
    return context;
  }

  static CredentialSnapshot captureCredentialSnapshot(Path certificate, Path privateKey, Path ca)
      throws IOException {
    return new CredentialSnapshot(
        CredentialPathSnapshot.capture(certificate),
        CredentialPathSnapshot.capture(privateKey),
        ca == null ? null : CredentialPathSnapshot.capture(ca));
  }

  private List<Path> watchedCredentialPaths() {
    List<Path> paths =
        new ArrayList<>(
            List.of(
                configuredPath(properties.getCertificateChainPath(), "certificate chain"),
                configuredPath(properties.getPrivateKeyPath(), "private key")));
    if (trustPolicy.requiresClientCertificate()) {
      paths.add(configuredPath(properties.getTrustedClientCaPath(), "trusted client CA"));
    }
    return List.copyOf(paths);
  }

  private static Path configuredPath(String configuredPath, String label) {
    try {
      return Path.of(configuredPath);
    } catch (RuntimeException ex) {
      throw new IllegalStateException("Invalid TCP Proxy listener " + label + " path", ex);
    }
  }

  private void reloadSslContext() {
    synchronized (reloadMonitor) {
      try {
        SslContext replacement = buildSslContext();
        activeSslContext.set(replacement);
        tlsMaterialReloadHealthy = true;
        LOG.info(
            "TCP Proxy internal TLS listener accepted a validated credential update profile={}",
            trustPolicy.profileName());
      } catch (Exception ex) {
        tlsMaterialReloadHealthy = false;
        LOG.error(
            "TCP Proxy internal TLS listener rejected a credential update; keeping the last known-good context profile={}",
            trustPolicy.profileName(),
            ex);
        throw new IllegalStateException("Unable to reload TCP Proxy listener TLS credentials", ex);
      }
    }
  }

  private static File requiredFile(String configuredPath, String label) {
    Path path;
    try {
      path = Path.of(configuredPath).toAbsolutePath().normalize();
    } catch (RuntimeException ex) {
      throw new IllegalStateException("Invalid TCP Proxy listener " + label + " path", ex);
    }
    if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
      throw new IllegalStateException(
          "TCP Proxy listener " + label + " is missing or unreadable: " + path);
    }
    return path.toFile();
  }

  private static String requiredBindAddress(String configuredAddress) {
    if (configuredAddress == null || configuredAddress.isBlank()) {
      throw new IllegalStateException(
          "TCP Proxy listener bind address must be configured and non-blank");
    }
    return configuredAddress.trim();
  }

  static final class CredentialSnapshot {
    private final CredentialPathSnapshot certificate;
    private final CredentialPathSnapshot privateKey;
    private final CredentialPathSnapshot clientCa;

    private CredentialSnapshot(
        CredentialPathSnapshot certificate,
        CredentialPathSnapshot privateKey,
        CredentialPathSnapshot clientCa) {
      this.certificate = certificate;
      this.privateKey = privateKey;
      this.clientCa = clientCa;
    }

    void requireConsistentCertificateKeyGeneration() throws IOException {
      if (certificate.isProjected() != privateKey.isProjected()) {
        throw new IOException("TCP Proxy listener certificate and key use different projections");
      }
      if (certificate.isProjected()
          && (!certificate.projectionPointer.equals(privateKey.projectionPointer)
              || !certificate.projectionGeneration.equals(privateKey.projectionGeneration))) {
        throw new IOException("TCP Proxy listener certificate and key use different generations");
      }
    }

    boolean isCurrent() {
      return certificate.isCurrent()
          && privateKey.isCurrent()
          && (clientCa == null || clientCa.isCurrent());
    }
  }

  private static final class CredentialPathSnapshot {
    private static final String PROJECTED_DATA_DIRECTORY = "..data";

    private final Path configuredPath;
    private final Path readPath;
    private final Path projectionPointer;
    private final Path projectionGeneration;
    private final FileState fileState;

    private CredentialPathSnapshot(
        Path configuredPath,
        Path readPath,
        Path projectionPointer,
        Path projectionGeneration,
        FileState fileState) {
      this.configuredPath = configuredPath;
      this.readPath = readPath;
      this.projectionPointer = projectionPointer;
      this.projectionGeneration = projectionGeneration;
      this.fileState = fileState;
    }

    private static CredentialPathSnapshot capture(Path configuredPath) throws IOException {
      Path normalizedPath = configuredPath.toAbsolutePath().normalize();
      if (Files.isSymbolicLink(normalizedPath)) {
        Path fileTarget = Files.readSymbolicLink(normalizedPath);
        if (fileTarget.getNameCount() > 1
            && PROJECTED_DATA_DIRECTORY.equals(fileTarget.getName(0).toString())) {
          Path projectionPointer = normalizedPath.getParent().resolve(PROJECTED_DATA_DIRECTORY);
          if (!Files.isSymbolicLink(projectionPointer)) {
            throw new IOException("Projected TLS material has no stable ..data link");
          }
          Path projectionGeneration = Files.readSymbolicLink(projectionPointer);
          Path generationDirectory =
              projectionGeneration.isAbsolute()
                  ? projectionGeneration
                  : projectionPointer.getParent().resolve(projectionGeneration);
          Path relativeFile = fileTarget.subpath(1, fileTarget.getNameCount());
          Path readPath = generationDirectory.resolve(relativeFile).toAbsolutePath().normalize();
          return new CredentialPathSnapshot(
              normalizedPath,
              readPath,
              projectionPointer,
              projectionGeneration,
              FileState.read(readPath));
        }
      }

      Path readPath = normalizedPath.toRealPath();
      return new CredentialPathSnapshot(
          normalizedPath, readPath, null, null, FileState.read(readPath));
    }

    private boolean isProjected() {
      return projectionPointer != null;
    }

    private boolean isCurrent() {
      try {
        if (isProjected()) {
          return Files.isSymbolicLink(projectionPointer)
              && projectionGeneration.equals(Files.readSymbolicLink(projectionPointer))
              && fileState.matches(readPath);
        }
        return readPath.equals(configuredPath.toRealPath()) && fileState.matches(readPath);
      } catch (IOException ex) {
        return false;
      }
    }
  }

  private record FileState(Object fileKey, long size, FileTime modifiedTime) {
    private static FileState read(Path path) throws IOException {
      BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
      return new FileState(attributes.fileKey(), attributes.size(), attributes.lastModifiedTime());
    }

    private boolean matches(Path path) throws IOException {
      return equals(read(path));
    }
  }

  @Override
  public synchronized void stop() {
    running = false;
    TlsCertificateWatcher watcher = certificateWatcher;
    certificateWatcher = null;
    tlsMaterialReloadHealthy = false;
    if (watcher != null) {
      try {
        watcher.close();
      } catch (IOException ex) {
        LOG.error("TCP Proxy internal TLS listener failed to stop its certificate watcher", ex);
      }
    }
    ScheduledFuture<?> task = expiryTask;
    expiryTask = null;
    if (task != null) {
      task.cancel(false);
    }
    ScheduledExecutorService executor = expiryExecutor;
    expiryExecutor = null;
    if (executor != null) {
      executor.shutdown();
    }
    DisposableServer current = server;
    ChannelGroup channels = acceptedChannels;
    try {
      if (current != null) {
        current.disposeNow(SHUTDOWN_TIMEOUT);
        if (server == current) {
          server = null;
        }
      }
    } catch (RuntimeException ex) {
      LOG.error("TCP Proxy internal TLS listener failed during server shutdown", ex);
      throw ex;
    } finally {
      if (channels != null) {
        try {
          channels.close().awaitUninterruptibly(SHUTDOWN_TIMEOUT.toMillis());
        } finally {
          if (acceptedChannels == channels) {
            acceptedChannels = null;
          }
        }
      }
    }
  }

  @Override
  public boolean isRunning() {
    DisposableServer current = server;
    return running && current != null && !current.isDisposed();
  }

  @Override
  public boolean isAutoStartup() {
    return true;
  }

  @Override
  public int getPhase() {
    return Integer.MAX_VALUE - 100;
  }

  public int boundPort() {
    DisposableServer current = server;
    return running && current != null && !current.isDisposed() ? current.port() : -1;
  }

  /** Returns true only when the listener is serving the latest valid watched TLS material. */
  public boolean isTlsMaterialHealthy() {
    TlsCertificateWatcher watcher = certificateWatcher;
    return tlsMaterialReloadHealthy && watcher != null && watcher.isHealthy();
  }

  int acceptedConnectionCount() {
    ChannelGroup channels = acceptedChannels;
    return channels == null ? 0 : channels.size();
  }

  static final class InternalOnlyHttpHandler implements HttpHandler {
    private final HttpHandler delegate;

    InternalOnlyHttpHandler(HttpHandler delegate) {
      this.delegate = Objects.requireNonNull(delegate);
    }

    @Override
    public reactor.core.publisher.Mono<Void> handle(
        org.springframework.http.server.reactive.ServerHttpRequest request,
        org.springframework.http.server.reactive.ServerHttpResponse response) {
      URI requestUri = request.getURI();
      String decodedPath = requestUri.getPath();
      String canonicalPath = requestUri.normalize().getPath();
      if (decodedPath != null
          && !containsParentSegment(decodedPath)
          && canonicalPath != null
          && !containsDotSegment(canonicalPath)
          && (canonicalPath.equals("/ws/game")
              || canonicalPath.startsWith("/ws/game/")
              || canonicalPath.equals("/actuator/health/readiness")
              || canonicalPath.equals("/actuator/health/liveness"))) {
        return delegate.handle(request, response);
      }
      response.setStatusCode(HttpStatus.NOT_FOUND);
      return response.setComplete();
    }

    private static boolean containsParentSegment(String path) {
      return containsDotSegment(path, false);
    }

    private static boolean containsDotSegment(String path) {
      return containsDotSegment(path, true);
    }

    private static boolean containsDotSegment(String path, boolean includeCurrentDirectory) {
      int segmentStart = 0;
      while (segmentStart <= path.length()) {
        int segmentEnd = path.indexOf('/', segmentStart);
        if (segmentEnd < 0) {
          segmentEnd = path.length();
        }
        int parameterStart = path.indexOf(';', segmentStart);
        int valueEnd =
            parameterStart >= 0 && parameterStart < segmentEnd ? parameterStart : segmentEnd;
        int valueLength = valueEnd - segmentStart;
        if ((includeCurrentDirectory && valueLength == 1 && path.charAt(segmentStart) == '.')
            || (valueLength == 2
                && path.charAt(segmentStart) == '.'
                && path.charAt(segmentStart + 1) == '.')) {
          return true;
        }
        if (segmentEnd == path.length()) {
          return false;
        }
        segmentStart = segmentEnd + 1;
      }
      return false;
    }
  }

  /** Selects one complete TLS context for each new handshake without replacing the listener. */
  private static final class ReloadingSslContext extends SslContext {
    private final AtomicReference<SslContext> current;

    private ReloadingSslContext(AtomicReference<SslContext> current) {
      this.current = current;
    }

    private SslContext current() {
      return Objects.requireNonNull(current.get(), "TCP Proxy listener TLS context is unavailable");
    }

    @Override
    public boolean isClient() {
      return false;
    }

    @Override
    public List<String> cipherSuites() {
      return current().cipherSuites();
    }

    @Override
    public ApplicationProtocolNegotiator applicationProtocolNegotiator() {
      return current().applicationProtocolNegotiator();
    }

    @Override
    public SSLEngine newEngine(ByteBufAllocator allocator) {
      return current().newEngine(allocator);
    }

    @Override
    public SSLEngine newEngine(ByteBufAllocator allocator, String peerHost, int peerPort) {
      return current().newEngine(allocator, peerHost, peerPort);
    }

    @Override
    public SSLSessionContext sessionContext() {
      return current().sessionContext();
    }

    @Override
    public long sessionCacheSize() {
      return current().sessionCacheSize();
    }

    @Override
    public long sessionTimeout() {
      return current().sessionTimeout();
    }
  }
}
