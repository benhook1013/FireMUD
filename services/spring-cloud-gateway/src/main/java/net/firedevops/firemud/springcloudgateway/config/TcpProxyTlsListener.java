package net.firedevops.firemud.springcloudgateway.config;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.util.concurrent.GlobalEventExecutor;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
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
  private volatile DisposableServer server;
  private volatile ChannelGroup acceptedChannels;
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
    try {
      SslContext sslContext = buildSslContext();
      ChannelGroup channels =
          new DefaultChannelGroup("tcp-proxy-internal-tls", GlobalEventExecutor.INSTANCE, true);
      acceptedChannels = channels;
      ReactorHttpHandlerAdapter adapter =
          new ReactorHttpHandlerAdapter(new InternalOnlyHttpHandler(httpHandler));
      server =
          HttpServer.create()
              .channelGroup(channels)
              .host(properties.getBindAddress().trim())
              .port(properties.getPort())
              .secure(spec -> spec.sslContext(sslContext))
              .handle(adapter)
              .bindNow();
      LOG.info(
          "TCP Proxy internal TLS listener started address={} port={} profile={}",
          properties.getBindAddress(),
          server.port(),
          trustPolicy.profileName());
      scheduleProfileExpiry();
    } catch (RuntimeException ex) {
      stop();
      throw ex;
    } catch (Exception ex) {
      stop();
      throw new IllegalStateException("Unable to start TCP Proxy internal TLS listener", ex);
    }
  }

  private void scheduleProfileExpiry() {
    Duration untilExpiry = trustPolicy.timeUntilProfileExpiry();
    if (untilExpiry == null) {
      return;
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
    File certificate = requiredFile(properties.getCertificateChainPath(), "certificate chain");
    File privateKey = requiredFile(properties.getPrivateKeyPath(), "private key");
    SslContextBuilder builder = SslContextBuilder.forServer(certificate, privateKey);
    if (trustPolicy.requiresClientCertificate()) {
      File clientCa = requiredFile(properties.getTrustedClientCaPath(), "trusted client CA");
      builder.trustManager(clientCa).clientAuth(ClientAuth.REQUIRE);
    } else {
      builder.clientAuth(ClientAuth.NONE);
    }
    return builder.protocols("TLSv1.3", "TLSv1.2").build();
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

  @Override
  public synchronized void stop() {
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
    server = null;
    if (current != null) {
      current.disposeNow(SHUTDOWN_TIMEOUT);
    }
    ChannelGroup channels = acceptedChannels;
    acceptedChannels = null;
    if (channels != null) {
      channels.close().awaitUninterruptibly(SHUTDOWN_TIMEOUT.toMillis());
    }
  }

  @Override
  public boolean isRunning() {
    DisposableServer current = server;
    return current != null && !current.isDisposed();
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
    return current == null ? -1 : current.port();
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
}
