package net.firedevops.firemud.tcpproxy.telnet;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.handler.ssl.JdkSslContext;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import net.firedevops.firemud.common.grpc.TlsCertificateWatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Owns the single TLS-configured HTTP client used for Gateway gameplay and readiness traffic. */
@Component
public final class GatewayWebSocketClient implements AutoCloseable {
  private static final Logger logger = LoggerFactory.getLogger(GatewayWebSocketClient.class);
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
  private static final Duration WEBSOCKET_HANDSHAKE_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration READINESS_TIMEOUT = Duration.ofSeconds(2);
  private static final Duration RETIREMENT_EXECUTOR_SHUTDOWN_TIMEOUT = Duration.ofSeconds(1);
  // Publication precedes retirement, so one fresh-state retry covers a raced rotation.
  private static final int GENERATION_ACQUIRE_ATTEMPTS = 2;
  private static final String CLIENT_AUTH_EKU = "1.3.6.1.5.5.7.3.2";
  private static final String GAMEPLAY_WEBSOCKET_ROUTE = "/ws/game";
  private static final String BAD_HEADER_REASON = "bad_header";
  private static final List<String> LOCAL_PROFILES = List.of("dev", "local", "test");

  private final URI gatewayUri;
  private final URI readinessUri;
  private final Path clientCertPath;
  private final Path clientKeyPath;
  private final Path caCertPath;
  private final Path grpcCertPath;
  private final Path telnetCertPath;
  private final boolean sharedEnvironment;
  private final boolean telnetTlsEnabled;
  private final MeterRegistry meterRegistry;
  private final Set<ClientGeneration> generations = ConcurrentHashMap.newKeySet();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final ExecutorService retirementExecutor =
      Executors.newSingleThreadExecutor(
          Thread.ofVirtual().name("gateway-http-client-retirement", 0).factory());
  private volatile ClientState state;
  private volatile TlsCertificateWatcher certificateWatcher;

  @Autowired
  public GatewayWebSocketClient(
      @Value("${GATEWAY_WS_URL:ws://spring-cloud-gateway:8080/ws/game}") String gatewayWsUrl,
      @Value("${FIREMUD_GATEWAY_WS_CLIENT_CERT_CHAIN_PATH:certs/client.crt}") String clientCertPath,
      @Value("${FIREMUD_GATEWAY_WS_CLIENT_PRIVATE_KEY_PATH:certs/client.key}") String clientKeyPath,
      @Value("${FIREMUD_GATEWAY_WS_CA_CERT_PATH:certs/ca.crt}") String caCertPath,
      @Value("${FIREMUD_GRPC_CERT_CHAIN_PATH:certs/client.crt}") String grpcCertPath,
      @Value("${TCP_PROXY_TLS_ENABLED:false}") boolean telnetTlsEnabled,
      @Value("${TCP_PROXY_TLS_CERT:}") String telnetCertPath,
      Environment environment,
      MeterRegistry meterRegistry) {
    this(
        gatewayWsUrl,
        clientCertPath,
        clientKeyPath,
        caCertPath,
        grpcCertPath,
        telnetTlsEnabled,
        telnetCertPath,
        environment.getActiveProfiles(),
        meterRegistry,
        true);
  }

  GatewayWebSocketClient(
      String gatewayWsUrl,
      String clientCertPath,
      String clientKeyPath,
      String caCertPath,
      String grpcCertPath,
      boolean telnetTlsEnabled,
      String telnetCertPath,
      String[] activeProfiles,
      MeterRegistry meterRegistry,
      boolean watchForRotation) {
    this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
    try {
      this.gatewayUri = parseGatewayUri(gatewayWsUrl);
    } catch (IllegalStateException e) {
      recordConfigurationFailure("bad_url");
      throw e;
    }
    this.readinessUri = readinessUri(gatewayUri);
    this.clientCertPath = pathOf(clientCertPath, "client_cert_missing", "client certificate chain");
    this.clientKeyPath = pathOf(clientKeyPath, "client_cert_missing", "client private key");
    this.caCertPath = pathOf(caCertPath, "cert_validation", "Gateway CA bundle");
    this.grpcCertPath = pathOf(grpcCertPath, "client_cert_invalid", "gRPC server certificate");
    this.telnetTlsEnabled = telnetTlsEnabled;
    this.telnetCertPath = pathOf(telnetCertPath, "client_cert_invalid", "Telnet certificate");
    this.sharedEnvironment = isSharedEnvironment(activeProfiles);

    if ("ws".equals(gatewayUri.getScheme())) {
      if (sharedEnvironment) {
        throw configurationFailure(
            "bad_url", "plaintext GATEWAY_WS_URL is not allowed outside local/dev/test");
      }
      state = ClientState.available(newGeneration(newHttpClient(null)));
      return;
    }

    state = loadTlsClient();
    if (watchForRotation) {
      try {
        startCertificateWatcher();
      } catch (RuntimeException e) {
        closed.set(true);
        state = ClientState.unavailable("client_cert_invalid");
        for (ClientGeneration generation : List.copyOf(generations)) {
          generation.shutdownNow();
        }
        shutdownExecutor(retirementExecutor, RETIREMENT_EXECUTOR_SHUTDOWN_TIMEOUT);
        throw e;
      }
    }
  }

  public CompletableFuture<WebSocket> connect(
      String clientIp,
      String proxyConnectionId,
      String gameInstanceId,
      String tenantId,
      String worldSlug,
      String realmSlug,
      String pointerVersion,
      WebSocket.Listener listener) {
    try {
      validateHeaderValue("X-Client-IP", clientIp);
      validateHeaderValue("X-Proxy-Connection-Id", proxyConnectionId);
      validateHeaderValue("X-Game-Instance-Id", gameInstanceId);
      validateHeaderValue("X-Tenant-Id", tenantId);
      validateHeaderValue("X-World-Slug", worldSlug);
      validateHeaderValue("X-Realm-Slug", realmSlug);
      validateHeaderValue("X-Pointer-Version", pointerVersion);
    } catch (TlsConfigurationException error) {
      return CompletableFuture.failedFuture(error);
    }

    ClientGeneration generation = acquireCurrentGeneration();
    if (generation == null) {
      String reason = state.failureReason();
      if (reason == null) {
        reason = "unknown";
      }
      recordFailure(reason);
      return CompletableFuture.failedFuture(
          new IllegalStateException(
              "Gateway WebSocket TLS client is unavailable; reason=" + reason));
    }

    ReleasingWebSocketListener releasingListener = null;
    try {
      releasingListener = new ReleasingWebSocketListener(listener, generation::release);
      WebSocket.Builder builder = generation.client().newWebSocketBuilder();
      builder.connectTimeout(WEBSOCKET_HANDSHAKE_TIMEOUT);
      addHeader(builder, "X-Client-IP", clientIp);
      addHeader(builder, "X-Proxy-Client-IP", clientIp);
      addHeader(builder, "X-Proxy-Connection-Id", proxyConnectionId);
      addHeader(builder, "X-Game-Instance-Id", gameInstanceId);
      addHeader(builder, "X-Proxy-Game-Instance-Id", gameInstanceId);
      addHeader(builder, "X-Tenant-Id", tenantId);
      addHeader(builder, "X-Proxy-Tenant-Id", tenantId);
      TelnetRoutingBundle routingBundle =
          TelnetRoutingBundle.normalize(worldSlug, realmSlug, pointerVersion);
      if (routingBundle != null) {
        addHeader(builder, "X-World-Slug", routingBundle.worldSlug());
        addHeader(builder, "X-Realm-Slug", routingBundle.realmSlug());
        addHeader(builder, "X-Pointer-Version", routingBundle.pointerVersion());
      }

      CompletableFuture<WebSocket> connection = builder.buildAsync(gatewayUri, releasingListener);
      connection.whenComplete(
          (ignored, error) -> {
            if (error != null && !connection.isCancelled()) {
              recordFailure(classifyFailure(error));
            }
          });
      return new ConnectionFuture(connection, releasingListener);
    } catch (RuntimeException error) {
      if (releasingListener == null) {
        generation.release();
      } else {
        releasingListener.release();
      }
      if (error instanceof TlsConfigurationException) {
        return CompletableFuture.failedFuture(error);
      }
      throw error;
    }
  }

  public CompletableFuture<Boolean> isReadyAsync() {
    if (!hasUsableCertificateWatcher()) {
      return CompletableFuture.completedFuture(false);
    }
    ClientGeneration generation = acquireCurrentGeneration();
    if (generation == null) {
      return CompletableFuture.completedFuture(false);
    }
    HttpRequest request =
        HttpRequest.newBuilder(readinessUri).GET().timeout(READINESS_TIMEOUT).build();
    CompletableFuture<HttpResponse<Void>> responseFuture;
    try {
      responseFuture =
          generation.client().sendAsync(request, HttpResponse.BodyHandlers.discarding());
    } catch (RuntimeException e) {
      generation.release();
      recordFailure(classifyFailure(e));
      return CompletableFuture.completedFuture(false);
    }
    CompletableFuture<Boolean> readiness = new CompletableFuture<>();
    responseFuture.whenComplete(
        (response, error) -> {
          boolean ready = false;
          try {
            try {
              if (error == null) {
                ready =
                    response.statusCode() >= 200
                        && response.statusCode() < 300
                        && hasUsableCertificateWatcher();
              } else if (!readiness.isCancelled()) {
                recordFailure(classifyFailure(error));
              }
            } finally {
              readiness.complete(ready);
            }
          } finally {
            generation.release();
          }
        });
    readiness.whenComplete(
        (ignored, error) -> {
          if (readiness.isCancelled()) {
            responseFuture.cancel(true);
          }
        });
    return readiness;
  }

  public URI readinessUri() {
    return readinessUri;
  }

  public URI gatewayUri() {
    return gatewayUri;
  }

  synchronized boolean reloadNow() {
    if (closed.get()) {
      return false;
    }
    try {
      ClientState replacement = loadTlsClient();
      ClientState previous = state;
      state = replacement;
      retire(previous);
      logger.info("Reloaded Gateway WebSocket TLS client material");
      return true;
    } catch (RuntimeException e) {
      String reason = reasonFrom(e);
      ClientState previous = state;
      state = ClientState.unavailable(reason);
      retire(previous);
      logger.error("Gateway WebSocket TLS reload failed closed; reason={}", reason, e);
      return false;
    }
  }

  Object clientIdentity() {
    ClientGeneration generation = state.generation();
    return generation == null ? null : generation.client();
  }

  void installGenerationForTest(HttpClient client) {
    state = ClientState.available(newGeneration(Objects.requireNonNull(client)));
  }

  int generationCount() {
    return generations.size();
  }

  boolean isCertificateWatcherRunning() {
    TlsCertificateWatcher watcher = certificateWatcher;
    return watcher != null && watcher.isRunning();
  }

  boolean isCertificateWatcherHealthy() {
    TlsCertificateWatcher watcher = certificateWatcher;
    return watcher != null && watcher.hasAllRequiredRegistrations();
  }

  @Override
  public synchronized void close() throws IOException {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    if (certificateWatcher != null) {
      certificateWatcher.close();
      certificateWatcher = null;
    }
    state = ClientState.unavailable("unknown");
    for (ClientGeneration generation : List.copyOf(generations)) {
      generation.shutdownNow();
    }
    shutdownExecutor(retirementExecutor, RETIREMENT_EXECUTOR_SHUTDOWN_TIMEOUT);
  }

  static void shutdownExecutor(ExecutorService executor, Duration timeout) {
    Objects.requireNonNull(executor, "executor").shutdownNow();
    try {
      if (!executor.awaitTermination(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
        logger.warn(
            "Gateway WebSocket client retirement executor did not terminate within {}", timeout);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      logger.warn(
          "Interrupted while waiting for Gateway WebSocket client retirement executor to terminate",
          e);
    }
  }

  private ClientState loadTlsClient() {
    requireReadable(clientCertPath, "client_cert_missing", "client certificate chain");
    requireReadable(clientKeyPath, "client_cert_missing", "client private key");
    requireReadable(caCertPath, "cert_validation", "Gateway CA bundle");

    X509Certificate clientCertificate = readLeafCertificate(clientCertPath, "client_cert_invalid");
    validateClientCertificate(clientCertificate);
    validateCaBundle(caCertPath);
    if (sharedEnvironment) {
      validateDistinctIdentity(clientCertificate, grpcCertPath, "gRPC server");
      if (telnetTlsEnabled) {
        validateDistinctIdentity(clientCertificate, telnetCertPath, "Telnet server");
      }
    }

    try {
      SslContext nettyContext =
          SslContextBuilder.forClient()
              .sslProvider(SslProvider.JDK)
              .keyManager(clientCertPath.toFile(), clientKeyPath.toFile())
              .trustManager(caCertPath.toFile())
              .build();
      if (!(nettyContext instanceof JdkSslContext jdkContext)) {
        throw configurationFailure("client_cert_invalid", "JDK TLS context was not available");
      }
      return ClientState.available(newGeneration(newHttpClient(jdkContext.context())));
    } catch (SSLException | IllegalArgumentException e) {
      throw configurationFailure(
          "client_cert_invalid", "Gateway WebSocket client certificate or key is invalid", e);
    }
  }

  private boolean hasUsableCertificateWatcher() {
    return !closed.get() && ("ws".equals(gatewayUri.getScheme()) || isCertificateWatcherHealthy());
  }

  private HttpClient newHttpClient(javax.net.ssl.SSLContext sslContext) {
    HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT);
    if (sslContext != null) {
      SSLParameters sslParameters = new SSLParameters();
      sslParameters.setEndpointIdentificationAlgorithm("HTTPS");
      builder.sslContext(sslContext).sslParameters(sslParameters);
    }
    return builder.build();
  }

  private void startCertificateWatcher() {
    List<Path> watchedPaths = new ArrayList<>(List.of(clientCertPath, clientKeyPath, caCertPath));
    if (sharedEnvironment) {
      watchedPaths.add(grpcCertPath);
      if (telnetTlsEnabled) {
        watchedPaths.add(telnetCertPath);
      }
    }
    try {
      // The constructor publishes the initial client state before starting this watcher, so a
      // reloadNow callback can only replace a fully initialized generation and can retire it
      // safely.
      certificateWatcher = TlsCertificateWatcher.createAndStart(watchedPaths, this::reloadNow);
    } catch (IOException e) {
      throw configurationFailure(
          "client_cert_invalid", "Gateway WebSocket TLS files could not be watched", e);
    }
  }

  private ClientGeneration newGeneration(HttpClient client) {
    ClientGeneration generation = new ClientGeneration(client);
    generations.add(generation);
    return generation;
  }

  private ClientGeneration acquireCurrentGeneration() {
    ClientGeneration previous = null;
    for (int attempt = 0; attempt < GENERATION_ACQUIRE_ATTEMPTS && !closed.get(); attempt++) {
      ClientGeneration generation = state.generation();
      if (generation == null || generation == previous) {
        return null;
      }
      if (generation.acquire()) {
        return generation;
      }
      previous = generation;
    }
    return null;
  }

  private void retire(ClientState clientState) {
    if (clientState != null && clientState.generation() != null) {
      clientState.generation().retire();
    }
  }

  private void validateClientCertificate(X509Certificate certificate) {
    try {
      certificate.checkValidity();
      List<String> extendedKeyUsage = certificate.getExtendedKeyUsage();
      if (extendedKeyUsage == null || !extendedKeyUsage.contains(CLIENT_AUTH_EKU)) {
        throw configurationFailure(
            "client_cert_invalid", "Gateway WebSocket client certificate lacks clientAuth EKU");
      }
    } catch (CertificateException e) {
      throw configurationFailure(
          "client_cert_invalid", "Gateway WebSocket client certificate is not currently valid", e);
    }
  }

  private void validateCaBundle(Path path) {
    try (InputStream input = Files.newInputStream(path)) {
      boolean containsCa =
          CertificateFactory.getInstance("X.509").generateCertificates(input).stream()
              .filter(X509Certificate.class::isInstance)
              .map(X509Certificate.class::cast)
              .anyMatch(certificate -> certificate.getBasicConstraints() >= 0);
      if (!containsCa) {
        throw configurationFailure(
            "cert_validation", "Gateway CA bundle does not contain a CA certificate");
      }
    } catch (IOException | CertificateException e) {
      throw configurationFailure("cert_validation", "Gateway CA bundle is invalid", e);
    }
  }

  private void validateDistinctIdentity(
      X509Certificate clientCertificate, Path otherCertificatePath, String surface) {
    requireReadable(otherCertificatePath, "client_cert_invalid", surface + " certificate");
    X509Certificate otherCertificate =
        readLeafCertificate(otherCertificatePath, "client_cert_invalid");
    if (samePublicKey(clientCertificate, otherCertificate)) {
      throw configurationFailure(
          "client_cert_invalid",
          "Gateway WebSocket client identity must be distinct from the " + surface + " identity");
    }
  }

  private X509Certificate readLeafCertificate(Path path, String reason) {
    try (InputStream input = Files.newInputStream(path)) {
      return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
    } catch (IOException | CertificateException | ClassCastException e) {
      throw configurationFailure(reason, "TLS certificate is invalid: " + path, e);
    }
  }

  private void requireReadable(Path path, String reason, String description) {
    if (path == null || !Files.isRegularFile(path) || !Files.isReadable(path)) {
      throw configurationFailure(reason, "Missing or unreadable " + description);
    }
  }

  private TlsConfigurationException configurationFailure(String reason, String message) {
    return configurationFailure(reason, message, null);
  }

  private TlsConfigurationException configurationFailure(
      String reason, String message, Throwable cause) {
    recordConfigurationFailure(reason);
    String diagnostic = message + "; reason=" + reason;
    logger.error(diagnostic, cause);
    return new TlsConfigurationException(reason, diagnostic, cause);
  }

  private void recordFailure(String reason) {
    meterRegistry.counter("tcpproxy.gateway.handshake.failures", "reason", reason).increment();
  }

  private void recordConfigurationFailure(String reason) {
    meterRegistry.counter("tcpproxy.tls.misconfig").increment();
    recordFailure(reason);
  }

  private void validateHeaderValue(String headerName, String value) {
    try {
      TelnetRoutingBundle.validateHeaderValue(headerName, value);
    } catch (IllegalArgumentException e) {
      recordFailure(BAD_HEADER_REASON);
      String diagnostic = e.getMessage() + "; reason=" + BAD_HEADER_REASON;
      logger.warn(diagnostic, e);
      throw new TlsConfigurationException(BAD_HEADER_REASON, diagnostic, e);
    }
  }

  private static URI parseGatewayUri(String value) {
    try {
      if (value == null) {
        throw new IllegalArgumentException("missing Gateway WebSocket URI");
      }
      URI uri = URI.create(value);
      String rawPath = uri.getRawPath();
      if (!("ws".equals(uri.getScheme()) || "wss".equals(uri.getScheme()))
          || uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getQuery() != null
          || uri.getFragment() != null
          || !(GAMEPLAY_WEBSOCKET_ROUTE.equals(rawPath)
              || (rawPath != null && rawPath.startsWith(GAMEPLAY_WEBSOCKET_ROUTE + "/")))) {
        throw new IllegalArgumentException("unsupported Gateway WebSocket URI");
      }
      return uri;
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("Invalid GATEWAY_WS_URL; reason=bad_url", e);
    }
  }

  private static URI readinessUri(URI websocketUri) {
    try {
      return new URI(
          "wss".equals(websocketUri.getScheme()) ? "https" : "http",
          null,
          websocketUri.getHost(),
          websocketUri.getPort(),
          "/actuator/health/readiness",
          null,
          null);
    } catch (URISyntaxException e) {
      throw new IllegalStateException("Invalid GATEWAY_WS_URL; reason=bad_url", e);
    }
  }

  private Path pathOf(String value, String reason, String description) {
    try {
      return value == null || value.isBlank() ? null : Path.of(value).toAbsolutePath().normalize();
    } catch (java.nio.file.InvalidPathException e) {
      throw configurationFailure(reason, "Invalid path for " + description, e);
    }
  }

  private static boolean isSharedEnvironment(String[] activeProfiles) {
    return activeProfiles == null
        || activeProfiles.length == 0
        || Arrays.stream(activeProfiles)
            .map(profile -> profile.toLowerCase(Locale.ROOT))
            .anyMatch(profile -> !LOCAL_PROFILES.contains(profile));
  }

  private void addHeader(WebSocket.Builder builder, String name, String value) {
    if (value != null) {
      validateHeaderValue(name, value);
    }
    if (value != null && !value.isBlank()) {
      builder.header(name, value);
    }
  }

  static boolean samePublicKey(X509Certificate first, X509Certificate second) {
    return MessageDigest.isEqual(
        first.getPublicKey().getEncoded(), second.getPublicKey().getEncoded());
  }

  static String classifyFailure(Throwable error) {
    Throwable cause = unwrap(error);
    if (cause instanceof TlsConfigurationException tlsFailure) {
      return tlsFailure.reason();
    }
    if (cause instanceof UnknownHostException) {
      return "dns";
    }
    if (cause instanceof HttpConnectTimeoutException || cause instanceof HttpTimeoutException) {
      return "timeout";
    }
    if (cause instanceof ConnectException) {
      return "connect_refused";
    }
    if (cause instanceof SSLException) {
      String message = String.valueOf(cause.getMessage()).toLowerCase(Locale.ROOT);
      if (message.contains("bad_certificate")
          || message.contains("certificate_required")
          || message.contains("client certificate")) {
        return "client_cert_invalid";
      }
      if (message.contains("pkix")
          || message.contains("certpath")
          || message.contains("certificate_unknown")
          || message.contains("unable to find valid certification path")
          || message.contains("subject alternative")
          || message.contains("no name matching")) {
        return "cert_validation";
      }
      return "handshake_protocol";
    }
    if (cause instanceof WebSocketHandshakeException) {
      return "handshake_protocol";
    }
    return "unknown";
  }

  private static Throwable unwrap(Throwable error) {
    Throwable current = error;
    while ((current instanceof CompletionException
            || current instanceof java.util.concurrent.ExecutionException)
        && current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }

  static String reasonFrom(RuntimeException exception) {
    return exception instanceof TlsConfigurationException tlsFailure
        ? tlsFailure.reason()
        : "unknown";
  }

  private static final class TlsConfigurationException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    private final String reason;

    private TlsConfigurationException(String reason, String message, Throwable cause) {
      super(message, cause);
      this.reason = reason;
    }

    private String reason() {
      return reason;
    }
  }

  private final class ClientGeneration {
    private final HttpClient client;
    private final AtomicInteger activeOperations = new AtomicInteger();
    private boolean retired;
    private boolean closing;
    private boolean terminated;

    private ClientGeneration(HttpClient client) {
      this.client = client;
    }

    private HttpClient client() {
      return client;
    }

    private synchronized boolean acquire() {
      if (retired || terminated) {
        return false;
      }
      activeOperations.incrementAndGet();
      return true;
    }

    private synchronized void release() {
      int remaining = activeOperations.decrementAndGet();
      if (remaining < 0) {
        activeOperations.set(0);
        throw new IllegalStateException("Gateway WebSocket client generation released twice");
      }
      if (retired && remaining == 0) {
        closeRetired();
      }
    }

    private synchronized void retire() {
      retired = true;
      if (activeOperations.get() == 0) {
        closeRetired();
      }
    }

    private synchronized void closeRetired() {
      if (closing || terminated) {
        return;
      }
      closing = true;
      retirementExecutor.execute(
          () -> {
            try {
              client.close();
            } finally {
              synchronized (this) {
                terminated = true;
                generations.remove(this);
              }
            }
          });
    }

    private synchronized void shutdownNow() {
      if (terminated) {
        return;
      }
      retired = true;
      terminated = true;
      client.shutdownNow();
      generations.remove(this);
    }
  }

  static final class ReleasingWebSocketListener implements WebSocket.Listener {
    private final WebSocket.Listener delegate;
    private final Runnable releaseAction;
    private final AtomicBoolean released = new AtomicBoolean();
    private volatile GenerationReleasingWebSocket releasingWebSocket;

    ReleasingWebSocketListener(WebSocket.Listener delegate, Runnable releaseAction) {
      this.delegate = Objects.requireNonNull(delegate, "listener");
      this.releaseAction = releaseAction;
    }

    @Override
    public void onOpen(WebSocket webSocket) {
      try {
        delegate.onOpen(wrap(webSocket));
      } catch (RuntimeException e) {
        release();
        throw e;
      }
    }

    @Override
    public java.util.concurrent.CompletionStage<?> onText(
        WebSocket webSocket, CharSequence data, boolean last) {
      return delegate.onText(wrap(webSocket), data, last);
    }

    @Override
    public java.util.concurrent.CompletionStage<?> onBinary(
        WebSocket webSocket, java.nio.ByteBuffer data, boolean last) {
      return delegate.onBinary(wrap(webSocket), data, last);
    }

    @Override
    public java.util.concurrent.CompletionStage<?> onPing(
        WebSocket webSocket, java.nio.ByteBuffer message) {
      return delegate.onPing(wrap(webSocket), message);
    }

    @Override
    public java.util.concurrent.CompletionStage<?> onPong(
        WebSocket webSocket, java.nio.ByteBuffer message) {
      return delegate.onPong(wrap(webSocket), message);
    }

    @Override
    public java.util.concurrent.CompletionStage<?> onClose(
        WebSocket webSocket, int statusCode, String reason) {
      try {
        return delegate.onClose(wrap(webSocket), statusCode, reason);
      } finally {
        release();
      }
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
      try {
        delegate.onError(wrap(webSocket), error);
      } finally {
        release();
      }
    }

    WebSocket wrap(WebSocket webSocket) {
      GenerationReleasingWebSocket current = releasingWebSocket;
      if (current != null) {
        return current;
      }
      synchronized (this) {
        if (releasingWebSocket == null) {
          releasingWebSocket = new GenerationReleasingWebSocket(webSocket, this::release);
        }
        return releasingWebSocket;
      }
    }

    void release() {
      if (released.compareAndSet(false, true)) {
        releaseAction.run();
      }
    }
  }

  static final class ConnectionFuture extends CompletableFuture<WebSocket> {
    private final CompletableFuture<WebSocket> handshake;
    private final ReleasingWebSocketListener releasingListener;

    ConnectionFuture(
        CompletableFuture<WebSocket> handshake, ReleasingWebSocketListener releasingListener) {
      this.handshake = Objects.requireNonNull(handshake, "handshake");
      this.releasingListener = Objects.requireNonNull(releasingListener, "releasingListener");
      handshake.whenComplete(
          (webSocket, error) -> {
            if (error != null) {
              releasingListener.release();
              completeExceptionally(error);
              return;
            }
            WebSocket wrapped = releasingListener.wrap(webSocket);
            if (!complete(wrapped)) {
              wrapped.abort();
            }
          });
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      if (!super.cancel(mayInterruptIfRunning)) {
        return false;
      }
      handshake.cancel(mayInterruptIfRunning);
      releasingListener.release();
      return true;
    }
  }

  private static final class GenerationReleasingWebSocket implements WebSocket {
    private final WebSocket delegate;
    private final Runnable releaseAction;

    private GenerationReleasingWebSocket(WebSocket delegate, Runnable releaseAction) {
      this.delegate = Objects.requireNonNull(delegate, "webSocket");
      this.releaseAction = releaseAction;
    }

    @Override
    public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
      return delegate.sendText(data, last).thenApply(ignored -> this);
    }

    @Override
    public CompletableFuture<WebSocket> sendBinary(java.nio.ByteBuffer data, boolean last) {
      return delegate.sendBinary(data, last).thenApply(ignored -> this);
    }

    @Override
    public CompletableFuture<WebSocket> sendPing(java.nio.ByteBuffer message) {
      return delegate.sendPing(message).thenApply(ignored -> this);
    }

    @Override
    public CompletableFuture<WebSocket> sendPong(java.nio.ByteBuffer message) {
      return delegate.sendPong(message).thenApply(ignored -> this);
    }

    @Override
    public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
      CompletableFuture<WebSocket> close;
      try {
        close = delegate.sendClose(statusCode, reason);
      } catch (RuntimeException error) {
        releaseAction.run();
        throw error;
      }
      return close.whenComplete((ignored, error) -> releaseAction.run()).thenApply(ignored -> this);
    }

    @Override
    public void request(long n) {
      delegate.request(n);
    }

    @Override
    public String getSubprotocol() {
      return delegate.getSubprotocol();
    }

    @Override
    public boolean isOutputClosed() {
      return delegate.isOutputClosed();
    }

    @Override
    public boolean isInputClosed() {
      return delegate.isInputClosed();
    }

    @Override
    public void abort() {
      try {
        delegate.abort();
      } finally {
        releaseAction.run();
      }
    }
  }

  private record ClientState(ClientGeneration generation, String failureReason) {
    private static ClientState available(ClientGeneration generation) {
      return new ClientState(generation, null);
    }

    private static ClientState unavailable(String reason) {
      return new ClientState(null, reason);
    }
  }
}
