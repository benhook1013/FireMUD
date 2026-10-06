package net.firedevops.firemud.accountservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import net.firedevops.firemud.accountservice.config.AccountJwtJwksApiBinding.ApiCall;
import net.firedevops.firemud.accountservice.config.AccountJwtJwksApiBinding.ApiOperation;
import net.firedevops.firemud.accountservice.config.AccountJwtJwksApiBinding.ApiResponse;
import net.firedevops.firemud.accountservice.config.AccountJwtJwksApiBinding.BindingRejectedException;
import net.firedevops.firemud.accountservice.config.AccountJwtJwksApiBinding.ParsedBinding;
import net.firedevops.firemud.accountservice.config.AccountJwtValidatorInventoryBinding.ProtectedInventory;
import net.firedevops.firemud.accountservice.config.AccountJwtValidatorInventoryBinding.ValidatorExpectation;
import net.firedevops.firemud.accountservice.service.session.AccountJwtJwksConfigMapClient;
import net.firedevops.firemud.accountservice.service.session.AccountJwtJwksConfigMapClient.ConfigMapSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Physical loopback HTTPS proof for Account's production pinned-CA JWKS API transport only. */
class AccountJwtJwksApiTransportTest {
  private static final String NAMESPACE = "firemud-prod";
  private static final String API_USERNAME = "system:serviceaccount:firemud-prod:account-service";
  private static final String KUBE_SYSTEM_UID = "11111111-1111-4111-8111-111111111111";
  private static final String NAMESPACE_UID = "22222222-2222-4222-8222-222222222222";
  private static final String CONFIG_MAP_UID = "33333333-3333-4333-8333-333333333333";
  private static final String TEST_BEARER = "test-only-loopback-bearer";
  private static final char[] KEYSTORE_PASSWORD = "firemud-test".toCharArray();
  private static final Duration NORMAL_TIMEOUT = Duration.ofSeconds(3);
  private static final Duration STALL_TIMEOUT = Duration.ofMillis(350);
  private static final byte[] SELF_REVIEW =
      ("""
          {"apiVersion":"authentication.k8s.io/v1","kind":"SelfSubjectReview",
           "status":{"userInfo":{"username":"%s"}}}
          """
              .formatted(API_USERNAME))
          .getBytes(StandardCharsets.UTF_8);
  private static final byte[] KUBE_SYSTEM_NAMESPACE =
      namespaceJson("kube-system", KUBE_SYSTEM_UID).getBytes(StandardCharsets.UTF_8);
  private static final byte[] TARGET_NAMESPACE =
      namespaceJson(NAMESPACE, NAMESPACE_UID).getBytes(StandardCharsets.UTF_8);
  private static final byte[] CONFIG_MAP =
      ("""
          {"apiVersion":"v1","kind":"ConfigMap","metadata":{
            "name":"jwt-jwks","namespace":"%s","uid":"%s","resourceVersion":"7"},"data":{}}
          """
              .formatted(NAMESPACE, CONFIG_MAP_UID))
          .getBytes(StandardCharsets.UTF_8);

  @TempDir private static Path temporaryDirectory;

  private static TestPki pki;

  private final List<HttpsServer> servers = new ArrayList<>();

  @BeforeAll
  static void createEphemeralPki() throws Exception {
    Path trustedDirectory = Files.createDirectories(temporaryDirectory.resolve("trusted"));
    TestAuthority trustedAuthority = createAuthority(trustedDirectory, "account-jwks-test-ca");
    TestServerCertificate trustedServer =
        issueServerCertificate(
            trustedDirectory, trustedAuthority, "account-jwks-localhost", "localhost");

    Path untrustedDirectory = Files.createDirectories(temporaryDirectory.resolve("untrusted"));
    TestAuthority untrustedAuthority =
        createAuthority(untrustedDirectory, "account-jwks-untrusted-ca");

    TestServerCertificate wrongHostnameServer =
        issueServerCertificate(
            trustedDirectory, trustedAuthority, "account-jwks-wrong-host", "wrong.example");
    pki =
        new TestPki(
            Files.readAllBytes(trustedAuthority.certificate()),
            Files.readAllBytes(untrustedAuthority.certificate()),
            trustedServer,
            wrongHostnameServer);
  }

  @AfterEach
  void stopLocalServers() {
    servers.forEach(server -> server.stop(0));
  }

  @Test
  void pinnedCaAndHostnameReachTheFixedKubernetesPathsOverRealHttps() throws Exception {
    List<String> requests = new CopyOnWriteArrayList<>();
    AtomicInteger authorizationHeaders = new AtomicInteger();
    HttpsServer server =
        startServer(
            pki.trustedServer(),
            exchange -> {
              requests.add(
                  exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath());
              if (("Bearer " + TEST_BEARER)
                  .equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                authorizationHeaders.incrementAndGet();
              }
              respondForKubernetesRequest(exchange);
            });
    AccountJwtJwksConfigMapClient client =
        new AccountJwtJwksConfigMapClient(
            transportBinding(server, pki.trustedCa(), NORMAL_TIMEOUT));

    ConfigMapSnapshot observed = client.observe();

    assertThat(observed.uid()).isEqualTo(CONFIG_MAP_UID);
    assertThat(observed.resourceVersion()).isEqualTo("7");
    assertThat(requests)
        .containsExactly(
            "POST /apis/authentication.k8s.io/v1/selfsubjectreviews",
            "GET /api/v1/namespaces/kube-system",
            "GET /api/v1/namespaces/" + NAMESPACE,
            "GET /api/v1/namespaces/" + NAMESPACE + "/configmaps/jwt-jwks");
    assertThat(authorizationHeaders).hasValue(4);
  }

  @Test
  void protectedInventoryOperationsUseOnlyFixedNamespaceGetPathsOverPinnedHttps() throws Exception {
    List<String> requests = new CopyOnWriteArrayList<>();
    AtomicInteger authorizationHeaders = new AtomicInteger();
    HttpsServer server =
        startServer(
            pki.trustedServer(),
            exchange -> {
              String query = exchange.getRequestURI().getRawQuery();
              requests.add(
                  exchange.getRequestMethod()
                      + " "
                      + exchange.getRequestURI().getRawPath()
                      + (query == null ? "" : "?" + query));
              if (("Bearer " + TEST_BEARER)
                  .equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                authorizationHeaders.incrementAndGet();
              }
              respond(exchange, 200, "{}".getBytes(StandardCharsets.UTF_8));
            });
    AccountJwtJwksApiBinding client = transportBinding(server, pki.trustedCa(), NORMAL_TIMEOUT);
    ProtectedInventory inventory = validatorInventoryForTransport();
    ValidatorExpectation validator = inventory.validators().get(0);

    try (ApiOperation operation = client.beginOperation()) {
      ApiResponse deployment = operation.readValidatorDeployment(inventory, validator);
      ApiResponse pods = operation.listValidatorPods(inventory, validator, "page/+token=");
      ApiResponse replicaSet =
          operation.readValidatorReplicaSet(inventory, validator, "player-service-abcde");
      assertThat(deployment.statusCode()).isEqualTo(200);
      assertThat(pods.statusCode()).isEqualTo(200);
      assertThat(replicaSet.statusCode()).isEqualTo(200);
    }

    assertThat(requests)
        .containsExactly(
            "GET /apis/apps/v1/namespaces/" + NAMESPACE + "/deployments/player-service",
            "GET /api/v1/namespaces/"
                + NAMESPACE
                + "/pods?labelSelector=app%3Dplayer-service%2Cfiremud.io%2Fcomponent%3Djwt-validator"
                + "&limit=250&continue=page%2F%2Btoken%3D",
            "GET /apis/apps/v1/namespaces/" + NAMESPACE + "/replicasets/player-service-abcde");
    assertThat(authorizationHeaders).hasValue(3);
  }

  @Test
  void replicaSetReadRejectsCallerSuppliedPathCharacters() throws Exception {
    AccountJwtJwksApiBinding client =
        transportBinding(
            startServer(pki.trustedServer(), exchange -> respond(exchange, 200, new byte[0])),
            pki.trustedCa(),
            NORMAL_TIMEOUT);
    ProtectedInventory inventory = validatorInventoryForTransport();
    ValidatorExpectation validator = inventory.validators().get(0);

    try (ApiOperation operation = client.beginOperation()) {
      assertThatThrownBy(
              () -> operation.readValidatorReplicaSet(inventory, validator, "../secrets"))
          .isInstanceOf(BindingRejectedException.class)
          .hasNoCause();
    }
  }

  @Test
  void serverCertificateFromAnUnpinnedCaIsRejectedByTheProductionTransport() throws Exception {
    AtomicInteger handlerCalls = new AtomicInteger();
    HttpsServer server =
        startServer(
            pki.trustedServer(),
            exchange -> {
              handlerCalls.incrementAndGet();
              respond(exchange, 200, KUBE_SYSTEM_NAMESPACE);
            });
    AccountJwtJwksApiBinding binding = transportBinding(server, pki.untrustedCa(), NORMAL_TIMEOUT);

    assertTransportFailure(binding, ApiCall.READ_KUBE_SYSTEM_NAMESPACE);

    assertThat(handlerCalls).hasValue(0);
  }

  @Test
  void wrongHostnameIsRejectedEvenWhenTheServingCertificateChainsToThePinnedCa() throws Exception {
    AtomicInteger handlerCalls = new AtomicInteger();
    HttpsServer server =
        startServer(
            pki.wrongHostnameServer(),
            exchange -> {
              handlerCalls.incrementAndGet();
              respond(exchange, 200, KUBE_SYSTEM_NAMESPACE);
            });
    AccountJwtJwksApiBinding binding = transportBinding(server, pki.trustedCa(), NORMAL_TIMEOUT);

    assertTransportFailure(binding, ApiCall.READ_KUBE_SYSTEM_NAMESPACE);

    assertThat(handlerCalls).hasValue(0);
  }

  @Test
  void redirectIsReturnedButNeverFollowed() throws Exception {
    AtomicInteger redirectTargetCalls = new AtomicInteger();
    HttpsServer redirectTarget =
        startServer(
            pki.trustedServer(),
            exchange -> {
              redirectTargetCalls.incrementAndGet();
              respond(exchange, 200, KUBE_SYSTEM_NAMESPACE);
            });
    List<String> sourceRequests = new CopyOnWriteArrayList<>();
    HttpsServer source =
        startServer(
            pki.trustedServer(),
            exchange -> {
              sourceRequests.add(exchange.getRequestURI().getRawPath());
              Headers headers = exchange.getResponseHeaders();
              headers.set(
                  "Location",
                  "https://localhost:" + redirectTarget.getAddress().getPort() + "/target");
              respond(exchange, 302, new byte[0]);
            });
    AccountJwtJwksApiBinding binding = transportBinding(source, pki.trustedCa(), NORMAL_TIMEOUT);

    ApiResponse response = send(binding, ApiCall.READ_KUBE_SYSTEM_NAMESPACE);

    assertThat(response.statusCode()).isEqualTo(302);
    assertThat(sourceRequests).containsExactly("/api/v1/namespaces/kube-system");
    assertThat(redirectTargetCalls).hasValue(0);
  }

  @Test
  void stalledPostHeaderBodyIsBoundedByTheWholeResponseDeadline() throws Exception {
    CountDownLatch bodyStarted = new CountDownLatch(1);
    CountDownLatch releaseBody = new CountDownLatch(1);
    HttpsServer server =
        startServer(
            pki.trustedServer(),
            exchange -> {
              exchange.getResponseHeaders().set("Content-Type", "application/json");
              exchange.sendResponseHeaders(200, 0);
              try (OutputStream body = exchange.getResponseBody()) {
                body.write('{');
                body.flush();
                bodyStarted.countDown();
                releaseBody.await(3, TimeUnit.SECONDS);
              } catch (IOException ignored) {
                // The client closes the response stream when its absolute deadline expires.
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              }
            });
    AccountJwtJwksApiBinding binding = transportBinding(server, pki.trustedCa(), STALL_TIMEOUT);
    long startedAt = System.nanoTime();

    try {
      assertTransportFailure(binding, ApiCall.READ_KUBE_SYSTEM_NAMESPACE);
      assertThat(bodyStarted.await(1, TimeUnit.SECONDS)).isTrue();
      assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(2));
    } finally {
      releaseBody.countDown();
    }
  }

  @Test
  void oversizedHttpsResponseIsRejectedWithoutAcceptingPartialBytes() throws Exception {
    AtomicInteger handlerCalls = new AtomicInteger();
    byte[] oversized = new byte[1024 * 1024 + 1];
    HttpsServer server =
        startServer(
            pki.trustedServer(),
            exchange -> {
              handlerCalls.incrementAndGet();
              respond(exchange, 200, oversized);
            });
    AccountJwtJwksApiBinding binding = transportBinding(server, pki.trustedCa(), NORMAL_TIMEOUT);

    assertTransportFailure(binding, ApiCall.READ_JWKS_CONFIG_MAP);

    assertThat(handlerCalls).hasValue(1);
  }

  @Test
  void rootOwnedReadOnlyProjectionAllowsRootWritableParentsAndSafeBearerRenewal() throws Exception {
    assumeRootCanCreateRootOwnedFixtures();
    ProtectedFiles fixture = protectedFiles("renewal");
    AccountJwtJwksApiBinding binding = fixture.binding();

    assertThat(binding.current().namespace()).isEqualTo(NAMESPACE);
    ApiOperation inFlight = binding.beginOperation();
    fixture.projectToken("..0002", "renewed-test-bearer", "token");

    assertThatThrownBy(() -> inFlight.send(ApiCall.READ_KUBE_SYSTEM_NAMESPACE, null))
        .isInstanceOf(AccountJwtJwksApiBinding.BindingRejectedException.class);
    inFlight.close();
    assertThat(binding.current().namespace()).isEqualTo(NAMESPACE);
  }

  @Test
  void credentialModeAndFrozenTrustFileIdentityDriftAreRejected() throws Exception {
    assumeRootCanCreateRootOwnedFixtures();
    ProtectedFiles fixture = protectedFiles("drift");
    AccountJwtJwksApiBinding binding = fixture.binding();
    binding.current();

    Files.setPosixFilePermissions(fixture.currentToken(), Set.of(PosixFilePermission.OWNER_READ));
    assertThatThrownBy(binding::current)
        .isInstanceOf(AccountJwtJwksApiBinding.BindingRejectedException.class);

    ProtectedFiles ownerFixture = protectedFiles("owner-drift");
    ownerFixture.binding().current();
    Files.setAttribute(ownerFixture.currentToken(), "unix:uid", 65534L, LinkOption.NOFOLLOW_LINKS);
    assertThatThrownBy(ownerFixture.binding()::current)
        .isInstanceOf(AccountJwtJwksApiBinding.BindingRejectedException.class);

    ProtectedFiles groupFixture = protectedFiles("group-drift");
    groupFixture.binding().current();
    Object gid =
        Files.getAttribute(groupFixture.currentToken(), "unix:gid", LinkOption.NOFOLLOW_LINKS);
    Files.setAttribute(
        groupFixture.currentToken(),
        "unix:gid",
        ((Number) gid).longValue() == 0L ? 1L : 0L,
        LinkOption.NOFOLLOW_LINKS);
    assertThatThrownBy(groupFixture.binding()::current)
        .isInstanceOf(AccountJwtJwksApiBinding.BindingRejectedException.class);

    ProtectedFiles trustFixture = protectedFiles("trust-drift");
    trustFixture.binding().current();
    Path replacementCa = trustFixture.caPath().resolveSibling("replacement-serving-ca.pem");
    Files.writeString(replacementCa, "test-only-public-serving-ca", StandardCharsets.UTF_8);
    Files.setPosixFilePermissions(
        replacementCa, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ));
    Files.move(replacementCa, trustFixture.caPath(), StandardCopyOption.REPLACE_EXISTING);
    assertThatThrownBy(trustFixture.binding()::current)
        .isInstanceOf(AccountJwtJwksApiBinding.BindingRejectedException.class);

    ProtectedFiles bindingFixture = protectedFiles("binding-drift");
    bindingFixture.binding().current();
    Path replacementBinding =
        bindingFixture.bindingPath().resolveSibling("replacement-binding.json");
    Files.write(replacementBinding, Files.readAllBytes(bindingFixture.bindingPath()));
    Files.setPosixFilePermissions(replacementBinding, PosixFilePermissions.fromString("r--r-----"));
    Files.move(
        replacementBinding, bindingFixture.bindingPath(), StandardCopyOption.REPLACE_EXISTING);
    assertThatThrownBy(bindingFixture.binding()::current)
        .isInstanceOf(AccountJwtJwksApiBinding.BindingRejectedException.class);
  }

  @Test
  void groupOrOtherWritableProtectedProjectionDirectoryIsRejected() throws Exception {
    assumeRootCanCreateRootOwnedFixtures();
    ProtectedFiles fixture = protectedFiles("writable-directory");
    Files.setPosixFilePermissions(
        fixture.trustRoot(),
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_EXECUTE));

    assertThatThrownBy(fixture.binding()::current)
        .isInstanceOf(AccountJwtJwksApiBinding.BindingRejectedException.class);
  }

  private static void assertTransportFailure(AccountJwtJwksApiBinding binding, ApiCall call) {
    assertThatThrownBy(() -> send(binding, call))
        .isInstanceOf(AccountJwtJwksApiBinding.ApiTransportException.class)
        .hasMessage("Account JWT JWKS Kubernetes API request failed");
  }

  private static ApiResponse send(AccountJwtJwksApiBinding binding, ApiCall call) {
    try (ApiOperation operation = binding.beginOperation()) {
      return operation.send(call, null);
    }
  }

  private static void assumeRootCanCreateRootOwnedFixtures() throws Exception {
    Object uid = Files.getAttribute(temporaryDirectory, "unix:uid", LinkOption.NOFOLLOW_LINKS);
    Assumptions.assumeTrue(uid instanceof Number number && number.longValue() == 0L);
  }

  private ProtectedFiles protectedFiles(String label) throws Exception {
    Path root = Files.createDirectories(temporaryDirectory.resolve(label));
    Path trustRoot = Files.createDirectory(root.resolve("account-jwt-api"));
    Path tokenRoot = Files.createDirectory(root.resolve("account-jwt-api-token"));
    Path bindingPath = trustRoot.resolve("binding.json");
    Path caPath = trustRoot.resolve("serving-ca.pem");
    Path tokenPath = tokenRoot.resolve("token");
    byte[] caBytes = "test-only-public-serving-ca".getBytes(StandardCharsets.UTF_8);
    Files.write(caPath, caBytes);
    Path firstVersion = Files.createDirectory(tokenRoot.resolve("..0001"));
    Path firstToken = firstVersion.resolve("token");
    Files.writeString(firstToken, "initial-test-bearer");
    Files.createSymbolicLink(tokenRoot.resolve("..data"), Path.of("..0001"));
    Files.createSymbolicLink(tokenPath, Path.of("..data/token"));
    applyPermissions(trustRoot, "rwxr-xr-x");
    applyPermissions(tokenRoot, "rwxr-xr-x");
    applyPermissions(firstVersion, "rwxr-xr-x");
    Files.createFile(bindingPath);
    applyPermissions(bindingPath, "r--r-----");
    applyPermissions(caPath, "r--r-----");
    applyPermissions(firstToken, "r--r-----");
    writeBinding(bindingPath, caPath, tokenPath, caBytes);
    return new ProtectedFiles(
        bindingPath,
        trustRoot,
        caPath,
        tokenRoot,
        tokenPath,
        firstToken,
        AccountJwtJwksApiBinding.forProtectedFilesTest(
            bindingPath, trustRoot, caPath, tokenRoot, tokenPath));
  }

  private static void writeBinding(Path bindingPath, Path caPath, Path tokenPath, byte[] caBytes)
      throws Exception {
    Map<String, String> values = new LinkedHashMap<>();
    values.put("version", "account-jwt-jwks-api-binding/v1");
    values.put("enabled", "true");
    values.put("configRevision", "physical-file-test-1");
    values.put("environmentId", "prod");
    values.put("clusterId", "loopback-cluster");
    values.put("apiServerUrl", "https://localhost:6443");
    values.put("tlsServerName", "localhost");
    values.put("servingCaPath", caPath.toString());
    values.put("servingCaSha256", sha256(caBytes));
    values.put("bearerTokenPath", tokenPath.toString());
    values.put("namespace", NAMESPACE);
    values.put("expectedClusterIncarnationUid", KUBE_SYSTEM_UID);
    values.put("expectedNamespaceUid", NAMESPACE_UID);
    values.put("expectedApiUsername", API_USERNAME);
    values.put("bindingDigest", "0".repeat(64));
    tools.jackson.databind.json.JsonMapper mapper =
        tools.jackson.databind.json.JsonMapper.builder().build();
    String placeholder = mapper.writeValueAsString(values);
    tools.jackson.databind.JsonNode binding = mapper.readTree(placeholder);
    values.put("bindingDigest", AccountJwtJwksApiBinding.computeBindingDigest(binding));
    Files.writeString(bindingPath, mapper.writeValueAsString(values), StandardCharsets.UTF_8);
  }

  private static void applyPermissions(Path path, String mode) throws IOException {
    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode));
  }

  private AccountJwtJwksApiBinding transportBinding(
      HttpsServer server, byte[] trustedCa, Duration timeout) throws Exception {
    ParsedBinding binding =
        new ParsedBinding(
            "transport-test-1",
            "prod",
            "loopback-cluster",
            java.net.URI.create("https://localhost:" + server.getAddress().getPort() + "/"),
            "localhost",
            Path.of("/not-read-by-transport-test/trusted-ca.pem"),
            sha256(trustedCa),
            Path.of("/not-read-by-transport-test/bearer-token"),
            NAMESPACE,
            KUBE_SYSTEM_UID,
            NAMESPACE_UID,
            API_USERNAME,
            "f".repeat(64));
    return AccountJwtJwksApiBinding.forTransportTest(binding, trustedCa, TEST_BEARER, timeout);
  }

  private static ProtectedInventory validatorInventoryForTransport() throws Exception {
    Map<String, Object> validator =
        Map.of(
            "validatorId",
            "player-service",
            "deploymentName",
            "player-service",
            "deploymentUid",
            "44444444-4444-4444-8444-444444444444",
            "selector",
            Map.of("app", "player-service", "firemud.io/component", "jwt-validator"),
            "replicas",
            1,
            "containerName",
            "player-service",
            "image",
            "registry.example/firemud/player@sha256:" + "a".repeat(64),
            "jwksUri",
            "https://account-api.test/.well-known/jwks.json",
            "maxCacheAgeSeconds",
            60,
            "profiles",
            List.of(
                Map.of(
                    "tokenProfile", "game-session-account-delegation",
                    "audience", "account-service")));
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("version", "account-jwt-validator-inventory-binding/v1");
    root.put("enabled", "true");
    root.put("configRevision", "inventory-r1");
    root.put("environmentId", "prod");
    root.put("clusterId", "loopback-cluster");
    root.put("namespace", NAMESPACE);
    root.put("expectedClusterIncarnationUid", KUBE_SYSTEM_UID);
    root.put("expectedNamespaceUid", NAMESPACE_UID);
    root.put("apiBindingRevision", "transport-test-1");
    root.put("apiBindingDigest", "f".repeat(64));
    root.put("validators", List.of(validator));
    root.put("bindingDigest", "0".repeat(64));
    tools.jackson.databind.json.JsonMapper mapper =
        tools.jackson.databind.json.JsonMapper.builder().build();
    var unsigned = mapper.readTree(mapper.writeValueAsBytes(root));
    root.put("bindingDigest", AccountJwtValidatorInventoryBinding.computeBindingDigest(unsigned));
    return AccountJwtValidatorInventoryBinding.parseProtectedBytes(mapper.writeValueAsBytes(root));
  }

  private HttpsServer startServer(TestServerCertificate certificate, HttpHandler handler)
      throws Exception {
    SSLContext sslContext = serverSslContext(certificate.pkcs12());
    InetAddress loopback = InetAddress.getByName("localhost");
    HttpsServer server = HttpsServer.create(new InetSocketAddress(loopback, 0), 0);
    server.setHttpsConfigurator(new HttpsConfigurator(sslContext));
    server.createContext("/", handler);
    server.start();
    servers.add(server);
    return server;
  }

  private static SSLContext serverSslContext(Path pkcs12Path) throws Exception {
    KeyStore keyStore = KeyStore.getInstance("PKCS12");
    try (InputStream input = Files.newInputStream(pkcs12Path)) {
      keyStore.load(input, KEYSTORE_PASSWORD);
    }
    KeyManagerFactory keyManagers =
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keyManagers.init(keyStore, KEYSTORE_PASSWORD);
    SSLContext context = SSLContext.getInstance("TLS");
    context.init(keyManagers.getKeyManagers(), null, null);
    return context;
  }

  private static void respondForKubernetesRequest(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getRawPath();
    if ("/apis/authentication.k8s.io/v1/selfsubjectreviews".equals(path)) {
      exchange.getRequestBody().readAllBytes();
      respond(exchange, 201, SELF_REVIEW);
    } else if ("/api/v1/namespaces/kube-system".equals(path)) {
      respond(exchange, 200, KUBE_SYSTEM_NAMESPACE);
    } else if (("/api/v1/namespaces/" + NAMESPACE).equals(path)) {
      respond(exchange, 200, TARGET_NAMESPACE);
    } else if (("/api/v1/namespaces/" + NAMESPACE + "/configmaps/jwt-jwks").equals(path)) {
      respond(exchange, 200, CONFIG_MAP);
    } else {
      respond(exchange, 404, new byte[0]);
    }
  }

  private static void respond(HttpExchange exchange, int statusCode, byte[] body)
      throws IOException {
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    if (body.length == 0) {
      exchange.sendResponseHeaders(statusCode, -1);
      exchange.close();
      return;
    }
    exchange.sendResponseHeaders(statusCode, body.length);
    try (OutputStream output = exchange.getResponseBody()) {
      output.write(body);
    }
  }

  private static TestAuthority createAuthority(Path directory, String commonName) throws Exception {
    Path key = directory.resolve(commonName + ".key");
    Path certificate = directory.resolve(commonName + ".crt");
    runOpenSsl(
        directory,
        "req",
        "-x509",
        "-newkey",
        "rsa:2048",
        "-nodes",
        "-keyout",
        key.toString(),
        "-out",
        certificate.toString(),
        "-subj",
        "/CN=" + commonName,
        "-days",
        "2",
        "-sha256",
        "-addext",
        "basicConstraints=critical,CA:TRUE",
        "-addext",
        "keyUsage=critical,keyCertSign,cRLSign");
    return new TestAuthority(key, certificate);
  }

  private static TestServerCertificate issueServerCertificate(
      Path directory, TestAuthority authority, String commonName, String dnsName) throws Exception {
    Path key = directory.resolve(commonName + ".key");
    Path request = directory.resolve(commonName + ".csr");
    Path certificate = directory.resolve(commonName + ".crt");
    Path extensions = directory.resolve(commonName + ".ext");
    Path pkcs12 = directory.resolve(commonName + ".p12");
    Files.writeString(
        extensions,
        "basicConstraints=critical,CA:FALSE\n"
            + "keyUsage=critical,digitalSignature,keyEncipherment\n"
            + "extendedKeyUsage=serverAuth\n"
            + "subjectAltName=DNS:"
            + dnsName
            + "\n");
    runOpenSsl(
        directory,
        "req",
        "-new",
        "-newkey",
        "rsa:2048",
        "-nodes",
        "-keyout",
        key.toString(),
        "-out",
        request.toString(),
        "-subj",
        "/CN=" + commonName);
    runOpenSsl(
        directory,
        "x509",
        "-req",
        "-in",
        request.toString(),
        "-CA",
        authority.certificate().toString(),
        "-CAkey",
        authority.privateKey().toString(),
        "-CAcreateserial",
        "-out",
        certificate.toString(),
        "-days",
        "2",
        "-sha256",
        "-extfile",
        extensions.toString());
    runOpenSsl(
        directory,
        "pkcs12",
        "-export",
        "-out",
        pkcs12.toString(),
        "-inkey",
        key.toString(),
        "-in",
        certificate.toString(),
        "-certfile",
        authority.certificate().toString(),
        "-passout",
        "pass:firemud-test");
    return new TestServerCertificate(pkcs12);
  }

  private static void runOpenSsl(Path workingDirectory, String... arguments) throws Exception {
    List<String> command = new ArrayList<>();
    command.add("openssl");
    command.addAll(List.of(arguments));
    Process process;
    try {
      process =
          new ProcessBuilder(command)
              .directory(workingDirectory.toFile())
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start();
    } catch (IOException failure) {
      throw new IllegalStateException("OpenSSL is required for this ephemeral HTTPS test", failure);
    }
    if (!process.waitFor(20, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      process.waitFor(2, TimeUnit.SECONDS);
      throw new IllegalStateException("Ephemeral HTTPS certificate generation timed out");
    }
    if (process.exitValue() != 0) {
      throw new IllegalStateException("Ephemeral HTTPS certificate generation failed");
    }
  }

  private static String namespaceJson(String name, String uid) {
    return """
        {"apiVersion":"v1","kind":"Namespace","metadata":{"name":"%s","uid":"%s"},
         "status":{"phase":"Active"}}
        """
        .formatted(name, uid);
  }

  private static String sha256(byte[] bytes) throws Exception {
    return java.util.HexFormat.of()
        .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private record ProtectedFiles(
      Path bindingPath,
      Path trustRoot,
      Path caPath,
      Path tokenRoot,
      Path tokenPath,
      Path currentToken,
      AccountJwtJwksApiBinding binding) {
    void projectToken(String version, String value, String fileName) throws IOException {
      Path versionDirectory = Files.createDirectory(tokenRoot.resolve(version));
      Path nextToken = versionDirectory.resolve(fileName);
      Files.writeString(nextToken, value, StandardCharsets.UTF_8);
      applyPermissions(versionDirectory, "rwxr-xr-x");
      applyPermissions(nextToken, "r--r-----");
      Path dataLink = tokenRoot.resolve("..data");
      Files.delete(dataLink);
      Files.createSymbolicLink(dataLink, Path.of(version));
    }
  }

  private record TestAuthority(Path privateKey, Path certificate) {}

  private record TestServerCertificate(Path pkcs12) {}

  private record TestPki(
      byte[] trustedCa,
      byte[] untrustedCa,
      TestServerCertificate trustedServer,
      TestServerCertificate wrongHostnameServer) {}
}
