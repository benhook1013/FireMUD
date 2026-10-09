package net.firedevops.firemud.loggingadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslProvider;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedKeyManager;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.security.AuthTokenInterceptor;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.loggingadmin.service.impl.StartSessionReservationEvidenceGrpcService;
import net.firedevops.firemud.loggingadmin.v1.ReadCurrentClaimEvidenceRequest;
import net.firedevops.firemud.loggingadmin.v1.ReadCurrentClaimEvidenceResponse;
import net.firedevops.firemud.loggingadmin.v1.StartSessionReservationEvidencePurpose;
import net.firedevops.firemud.loggingadmin.v1.StartSessionReservationEvidenceServiceGrpc;
import net.firedevops.firemud.test.TestContainerImages;
import net.firedevops.firemud.test.TlsTestSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SuppressWarnings("resource")
class StartSessionReservationEvidenceGrpcMutualTlsIntegrationTest {
  private static final String PUBLIC_METHOD =
      "logging_admin.v1.StartSessionReservationEvidenceService/ReadCurrentClaimEvidence";
  private static final UUID TENANT_ID = UUID.fromString("f03e136b-522b-4ea1-b8a1-4a9f0811ea1d");
  private static final UUID ACTOR_ID = UUID.fromString("4cc8c9f1-45e6-4c9b-b22e-94c27e3dc91f");
  private static final String ACCOUNT_URI = "spiffe://firemud/ns/test/sa/account-service";
  private static final String WRONG_SERVICE_URI =
      "spiffe://firemud/ns/test/sa/game-session-service";
  private static final String WRONG_NAMESPACE_URI = "spiffe://firemud/ns/other/sa/account-service";

  @Test
  void socketMtlsCertificateChecksDoNotRequirePersistence(@TempDir Path temporaryDirectory)
      throws Exception {
    TestWorkloadPki pki = TestWorkloadPki.create(temporaryDirectory);
    assertThat(GrpcPeerIdentity.fromCertificate(readCertificate(pki.serverCertificate())))
        .get()
        .extracting(GrpcPeerIdentity::uri)
        .isEqualTo("spiffe://firemud/ns/test/sa/logging-admin-service");

    Server server =
        startTransport(pki, Mockito.mock(StartSessionPreAuthorizationReservationService.class));
    try {
      assertTlsHandshakeAccepted(server, pki, pki.accountClient());
      assertHandshakeRejected(
          server, pki, pki.clientWithoutCertificate(), "missing client certificate");
      assertHandshakeRejected(
          server, pki, pki.untrustedAccountClient(), "untrusted client certificate chain");
    } finally {
      server.shutdownNow();
    }
  }

  @Nested
  @Testcontainers(disabledWithoutDocker = true)
  @SuppressWarnings("resource")
  class DatabaseBackedReservationEvidenceTest {
    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>(TestContainerImages.postgres());

    @Test
    void socketMtlsUsesTheVerifiedAccountPeerAndExactJwtExemption(@TempDir Path temporaryDirectory)
        throws Exception {
      DriverManagerDataSource dataSource =
          new DriverManagerDataSource(
              postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
      Flyway.configure()
          .dataSource(dataSource)
          .locations("classpath:db/migration")
          .placeholders(Map.of("serviceSchema", "public"))
          .load()
          .migrate();
      StartSessionPreAuthorizationReservationService reservationService =
          StartSessionReservationMutationTestFixtures.forRunOwnedPostgres(postgres).service();
      TestWorkloadPki pki = TestWorkloadPki.create(temporaryDirectory);
      assertThat(GrpcPeerIdentity.fromCertificate(readCertificate(pki.serverCertificate())))
          .get()
          .extracting(GrpcPeerIdentity::uri)
          .isEqualTo("spiffe://firemud/ns/test/sa/logging-admin-service");
      StartSessionPreAuthorizationReservationTuple tuple =
          tuple("grpc-mtls-reservation-evidence-01");
      var acquisition = reservationService.acquire(tuple);
      assertThat(acquisition.newlyAcquired()).isTrue();
      assertThat(
              reservationService
                  .markAuthorizationPending(acquisition.claim())
                  .mayDispatchAccountAuthorization())
          .isTrue();
      var claim = acquisition.claim().claimEvidence();
      ReadCurrentClaimEvidenceRequest request =
          ReadCurrentClaimEvidenceRequest.newBuilder()
              .setControlPlaneRequestId(tuple.controlPlaneRequestId())
              .setPreAuthorizationTupleJson(
                  ByteString.copyFrom(tuple.canonicalJson().getBytes(StandardCharsets.UTF_8)))
              .setReservationOwnerId(claim.reservationOwnerId().toString())
              .setReservationClaimFence(claim.reservationClaimFence())
              .setClaimOwnerId(claim.currentClaimOwnerId().toString())
              .setClaimFence(claim.currentClaimFence())
              .setPurpose(
                  StartSessionReservationEvidencePurpose
                      .START_SESSION_RESERVATION_EVIDENCE_PURPOSE_ISSUE)
              .build();

      Server server = startTransport(pki, reservationService);
      try {
        ReadCurrentClaimEvidenceResponse response = read(server, pki, pki.accountClient(), request);
        assertThat(response.getControlPlaneRequestId()).isEqualTo(tuple.controlPlaneRequestId());
        assertThat(response.getPreAuthorizationTupleJson().toByteArray())
            .containsExactly(tuple.canonicalJson().getBytes(StandardCharsets.UTF_8));
        assertThat(response.getMutationDigest()).isEqualTo(tuple.mutationDigest());
        assertThat(response.getReservationOwnerId())
            .isEqualTo(claim.reservationOwnerId().toString());
        assertThat(response.getReservationClaimFence()).isEqualTo(claim.reservationClaimFence());
        assertThat(response.getClaimOwnerId()).isEqualTo(claim.currentClaimOwnerId().toString());
        assertThat(response.getClaimFence()).isEqualTo(claim.currentClaimFence());
        assertThat(response.getPurpose())
            .isEqualTo(
                StartSessionReservationEvidencePurpose
                    .START_SESSION_RESERVATION_EVIDENCE_PURPOSE_ISSUE);
        assertThat(reservationService.findExact(tuple)).isPresent();

        assertTlsHandshakeAccepted(server, pki, pki.accountClient());
        assertHandshakeRejected(
            server, pki, pki.clientWithoutCertificate(), "missing client certificate");
        assertHandshakeRejected(
            server, pki, pki.untrustedAccountClient(), "untrusted client certificate chain");
        assertThat(readStatus(server, pki, pki.wrongServiceClient(), request))
            .isEqualTo(Status.Code.PERMISSION_DENIED);
        assertThat(readStatus(server, pki, pki.wrongNamespaceClient(), request))
            .isEqualTo(Status.Code.PERMISSION_DENIED);
      } finally {
        server.shutdownNow();
      }
    }
  }

  private static Server startTransport(
      TestWorkloadPki pki, StartSessionPreAuthorizationReservationService reservationService)
      throws Exception {
    var serverTls =
        GrpcSslContexts.configure(
                SslContextBuilder.forServer(
                    pki.serverCertificate().toFile(), pki.serverPrivateKey().toFile()),
                SslProvider.JDK)
            .trustManager(pki.trustedCaCertificate().toFile())
            .clientAuth(ClientAuth.REQUIRE)
            .protocols("TLSv1.3")
            .build();
    StartSessionReservationEvidenceGrpcService receiver =
        new StartSessionReservationEvidenceGrpcService(reservationService, "test");
    return NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
        .sslContext(serverTls)
        .addService(
            ServerInterceptors.intercept(
                receiver,
                new AuthTokenInterceptor(
                    new JwtUtil("a".repeat(64), 3_600_000L), Set.of(PUBLIC_METHOD)),
                new GrpcPeerIdentityInterceptor()))
        .build()
        .start();
  }

  private static ReadCurrentClaimEvidenceResponse read(
      Server server,
      TestWorkloadPki pki,
      TestWorkloadPki.ClientIdentity clientIdentity,
      ReadCurrentClaimEvidenceRequest request)
      throws Exception {
    ManagedChannel channel = clientChannel(server, pki, clientIdentity);
    try {
      return StartSessionReservationEvidenceServiceGrpc.newBlockingStub(channel)
          .withDeadlineAfter(10, TimeUnit.SECONDS)
          .readCurrentClaimEvidence(request);
    } finally {
      channel.shutdownNow();
    }
  }

  private static Status.Code readStatus(
      Server server,
      TestWorkloadPki pki,
      TestWorkloadPki.ClientIdentity clientIdentity,
      ReadCurrentClaimEvidenceRequest request)
      throws Exception {
    try {
      read(server, pki, clientIdentity, request);
      return Status.Code.OK;
    } catch (StatusRuntimeException ex) {
      return ex.getStatus().getCode();
    }
  }

  private static void assertHandshakeRejected(
      Server server,
      TestWorkloadPki pki,
      TestWorkloadPki.ClientIdentity clientIdentity,
      String description)
      throws Exception {
    RawClientTls clientTls = rawClientTls(pki, clientIdentity);
    Throwable failure =
        catchThrowable(
            () -> {
              try (SSLSocket socket = clientSocket(server, clientTls.context())) {
                socket.startHandshake();
                socket.getInputStream().read();
              }
            });
    assertThat(failure).as(description).isNotNull();
    assertThat(failure)
        .as("%s must surface a TLS handshake exception", description)
        .isInstanceOf(SSLHandshakeException.class);
    assertThat(TlsTestSupport.isTlsHandshakeRejection(failure))
        .as(
            "%s must fail during the TLS certificate handshake; failure chain: %s",
            description, throwableChain(failure))
        .isTrue();
    if (clientIdentity.certificate() != null) {
      clientTls.assertIdentitySelected(clientIdentity, ACCOUNT_URI);
      assertThat(clientTls.keyManager().requestedIssuers())
          .as("%s server issuer hints must exclude the selected account chain issuer", description)
          .isNotEmpty()
          .doesNotContain(clientTls.keyManager().selectedChain()[0].getIssuerX500Principal());
    }
  }

  private static String throwableChain(Throwable failure) {
    List<String> causes = new ArrayList<>();
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      causes.add(cause.getClass().getName() + ": " + cause.getMessage());
    }
    return String.join(" -> ", causes);
  }

  private static void assertTlsHandshakeAccepted(
      Server server, TestWorkloadPki pki, TestWorkloadPki.ClientIdentity clientIdentity)
      throws Exception {
    RawClientTls clientTls = rawClientTls(pki, clientIdentity);
    try (SSLSocket socket = clientSocket(server, clientTls.context())) {
      socket.startHandshake();
      assertThat(socket.getSession().getProtocol()).isEqualTo("TLSv1.3");
      assertThat(socket.getApplicationProtocol()).isEqualTo("h2");
    }
    clientTls.assertIdentitySelected(clientIdentity, ACCOUNT_URI);
  }

  private static SSLSocket clientSocket(Server server, SSLContext context) throws Exception {
    SSLSocket socket =
        (SSLSocket) context.getSocketFactory().createSocket("127.0.0.1", server.getPort());
    socket.setSoTimeout(10_000);
    SSLParameters parameters = socket.getSSLParameters();
    parameters.setEndpointIdentificationAlgorithm("HTTPS");
    parameters.setProtocols(new String[] {"TLSv1.3"});
    parameters.setApplicationProtocols(new String[] {"h2"});
    socket.setSSLParameters(parameters);
    return socket;
  }

  private static RawClientTls rawClientTls(
      TestWorkloadPki pki, TestWorkloadPki.ClientIdentity clientIdentity) throws Exception {
    KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
    trustStore.load(null, null);
    trustStore.setCertificateEntry(
        "logging-admin-test-ca", readCertificate(pki.trustedCaCertificate()));
    TrustManagerFactory trustManagers =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trustManagers.init(trustStore);

    ExplicitClientIdentityKeyManager explicitClientIdentity = null;
    KeyManager[] keyManagers = null;
    if (clientIdentity.certificate() != null) {
      KeyStore clientKeyStore = KeyStore.getInstance(KeyStore.getDefaultType());
      char[] password = TestWorkloadPki.STORE_PASSWORD.toCharArray();
      clientKeyStore.load(null, password);
      clientKeyStore.setKeyEntry(
          "logging-admin-test-client",
          readPrivateKey(clientIdentity.privateKey()),
          password,
          new Certificate[] {readCertificate(clientIdentity.certificate())});
      KeyManagerFactory clientKeyManagers =
          KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      clientKeyManagers.init(clientKeyStore, password);
      explicitClientIdentity =
          new ExplicitClientIdentityKeyManager(
              (X509ExtendedKeyManager) clientKeyManagers.getKeyManagers()[0],
              "logging-admin-test-client");
      keyManagers = new KeyManager[] {explicitClientIdentity};
    }

    SSLContext context = SSLContext.getInstance("TLS");
    context.init(keyManagers, trustManagers.getTrustManagers(), null);
    return new RawClientTls(context, explicitClientIdentity);
  }

  private record RawClientTls(SSLContext context, ExplicitClientIdentityKeyManager keyManager) {
    void assertIdentitySelected(TestWorkloadPki.ClientIdentity clientIdentity, String expectedUri)
        throws Exception {
      assertThat(keyManager)
          .as("raw TLS probe must have a client identity key manager")
          .isNotNull();
      X509Certificate expectedCertificate = readCertificate(clientIdentity.certificate());
      assertThat(keyManager.selectedAlias()).isEqualTo("logging-admin-test-client");
      assertThat(keyManager.selectedChain()).containsExactly(expectedCertificate);
      assertThat(GrpcPeerIdentity.fromCertificate(keyManager.selectedChain()[0]))
          .get()
          .extracting(GrpcPeerIdentity::uri)
          .isEqualTo(expectedUri);
    }
  }

  /**
   * Test-only key manager that selects its one explicit identity even when the server's issuer hint
   * excludes that identity's issuer; this makes the untrusted-chain probe exercise rejection of the
   * presented certificate rather than omission of a client certificate.
   */
  private static final class ExplicitClientIdentityKeyManager extends X509ExtendedKeyManager {
    private final X509ExtendedKeyManager delegate;
    private final String explicitAlias;
    private String selectedAlias;
    private X509Certificate[] selectedChain;
    private List<Principal> requestedIssuers = List.of();

    private ExplicitClientIdentityKeyManager(
        X509ExtendedKeyManager delegate, String explicitAlias) {
      this.delegate = delegate;
      this.explicitAlias = explicitAlias;
    }

    @Override
    public String[] getClientAliases(String keyType, Principal[] issuers) {
      return supports(keyType) ? new String[] {explicitAlias} : null;
    }

    @Override
    public String chooseClientAlias(
        String[] keyTypes, Principal[] issuers, java.net.Socket socket) {
      captureIssuers(issuers);
      return supports(keyTypes) ? selectExplicitAlias() : null;
    }

    @Override
    public String[] getServerAliases(String keyType, Principal[] issuers) {
      return delegate.getServerAliases(keyType, issuers);
    }

    @Override
    public String chooseServerAlias(String keyType, Principal[] issuers, java.net.Socket socket) {
      return delegate.chooseServerAlias(keyType, issuers, socket);
    }

    @Override
    public X509Certificate[] getCertificateChain(String alias) {
      X509Certificate[] chain = delegate.getCertificateChain(alias);
      if (explicitAlias.equals(alias)) {
        selectedChain = chain == null ? null : chain.clone();
      }
      return chain;
    }

    @Override
    public PrivateKey getPrivateKey(String alias) {
      return delegate.getPrivateKey(alias);
    }

    @Override
    public String chooseEngineClientAlias(
        String[] keyTypes, Principal[] issuers, SSLEngine engine) {
      captureIssuers(issuers);
      return supports(keyTypes) ? selectExplicitAlias() : null;
    }

    @Override
    public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
      return delegate.chooseEngineServerAlias(keyType, issuers, engine);
    }

    private boolean supports(String keyType) {
      PrivateKey key = delegate.getPrivateKey(explicitAlias);
      return key != null && key.getAlgorithm().equalsIgnoreCase(keyType);
    }

    private boolean supports(String[] keyTypes) {
      return keyTypes != null && Arrays.stream(keyTypes).anyMatch(this::supports);
    }

    private String selectExplicitAlias() {
      selectedAlias = explicitAlias;
      return explicitAlias;
    }

    private void captureIssuers(Principal[] issuers) {
      requestedIssuers = issuers == null ? List.of() : List.copyOf(Arrays.asList(issuers.clone()));
    }

    private String selectedAlias() {
      return selectedAlias;
    }

    private X509Certificate[] selectedChain() {
      return selectedChain == null ? new X509Certificate[0] : selectedChain.clone();
    }

    private List<Principal> requestedIssuers() {
      return requestedIssuers;
    }
  }

  private static PrivateKey readPrivateKey(Path privateKey) throws Exception {
    String encoded =
        Files.readString(privateKey)
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s", "");
    return KeyFactory.getInstance("RSA")
        .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded)));
  }

  private static ManagedChannel clientChannel(
      Server server, TestWorkloadPki pki, TestWorkloadPki.ClientIdentity clientIdentity)
      throws Exception {
    var clientTls =
        GrpcSslContexts.configure(GrpcSslContexts.forClient(), SslProvider.JDK)
            .trustManager(pki.trustedCaCertificate().toFile())
            .protocols("TLSv1.3");
    if (clientIdentity.certificate() != null) {
      clientTls.keyManager(
          clientIdentity.certificate().toFile(), clientIdentity.privateKey().toFile());
    }
    return NettyChannelBuilder.forAddress("127.0.0.1", server.getPort())
        .overrideAuthority("localhost")
        .sslContext(clientTls.build())
        .build();
  }

  private static X509Certificate readCertificate(Path certificate) throws Exception {
    try (var input = Files.newInputStream(certificate)) {
      return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
    }
  }

  private static StartSessionPreAuthorizationReservationTuple tuple(String requestId) {
    StartSessionOperatorAction action =
        new StartSessionOperatorAction(
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
            new StartSessionOperatorAction.Scope(TENANT_ID, "world-runtime"),
            new StartSessionOperatorAction.Target(
                29L, UUID.fromString("4ecbb112-6962-4df5-87d0-9271f982a614")),
            StartSessionOperatorAction.ExpectedVersion.ABSENT,
            new StartSessionOperatorAction.Mutation(StartSessionOperatorAction.ClientIp.absent()),
            "socket mTLS Account evidence integration test");
    SessionContext.setContext(
        ACTOR_ID.toString(),
        List.of(),
        Map.of(TENANT_ID.toString(), List.of("tenantAdmin")),
        false,
        null,
        null);
    try {
      return StartSessionPreAuthorizationReservationTuple.fromCurrentTenantAdmin(requestId, action);
    } finally {
      SessionContext.clear();
    }
  }

  /**
   * Test-only keytool material, issued into a temporary directory and never shared or activated.
   */
  private static final class TestWorkloadPki {
    private static final String STORE_PASSWORD = "logging-reservation-test-only";

    private final Path trustedCaCertificate;
    private final Path serverCertificate;
    private final Path serverPrivateKey;
    private final ClientIdentity accountClient;
    private final ClientIdentity wrongServiceClient;
    private final ClientIdentity wrongNamespaceClient;
    private final ClientIdentity untrustedAccountClient;

    private TestWorkloadPki(
        Path trustedCaCertificate,
        Path serverCertificate,
        Path serverPrivateKey,
        ClientIdentity accountClient,
        ClientIdentity wrongServiceClient,
        ClientIdentity wrongNamespaceClient,
        ClientIdentity untrustedAccountClient) {
      this.trustedCaCertificate = trustedCaCertificate;
      this.serverCertificate = serverCertificate;
      this.serverPrivateKey = serverPrivateKey;
      this.accountClient = accountClient;
      this.wrongServiceClient = wrongServiceClient;
      this.wrongNamespaceClient = wrongNamespaceClient;
      this.untrustedAccountClient = untrustedAccountClient;
    }

    static TestWorkloadPki create(Path directory) throws Exception {
      Files.createDirectories(directory);
      Path trustedCaStore = createCa(directory, "trusted-ca");
      Path trustedCaCertificate = exportCa(directory, trustedCaStore, "trusted-ca");
      Path untrustedCaStore = createCa(directory, "untrusted-ca");
      Path untrustedCaCertificate = exportCa(directory, untrustedCaStore, "untrusted-ca");
      Identity server =
          issueIdentity(
              directory,
              trustedCaStore,
              trustedCaCertificate,
              "logging-admin-server",
              "spiffe://firemud/ns/test/sa/logging-admin-service",
              true);
      ClientIdentity account =
          asClient(
              issueIdentity(
                  directory,
                  trustedCaStore,
                  trustedCaCertificate,
                  "account-service",
                  ACCOUNT_URI,
                  false));
      ClientIdentity wrongService =
          asClient(
              issueIdentity(
                  directory,
                  trustedCaStore,
                  trustedCaCertificate,
                  "wrong-service",
                  WRONG_SERVICE_URI,
                  false));
      ClientIdentity wrongNamespace =
          asClient(
              issueIdentity(
                  directory,
                  trustedCaStore,
                  trustedCaCertificate,
                  "wrong-namespace",
                  WRONG_NAMESPACE_URI,
                  false));
      ClientIdentity untrustedAccount =
          asClient(
              issueIdentity(
                  directory,
                  untrustedCaStore,
                  untrustedCaCertificate,
                  "untrusted-account",
                  ACCOUNT_URI,
                  false));
      return new TestWorkloadPki(
          trustedCaCertificate,
          server.certificate(),
          server.privateKey(),
          account,
          wrongService,
          wrongNamespace,
          untrustedAccount);
    }

    Path trustedCaCertificate() {
      return trustedCaCertificate;
    }

    Path serverCertificate() {
      return serverCertificate;
    }

    Path serverPrivateKey() {
      return serverPrivateKey;
    }

    ClientIdentity accountClient() {
      return accountClient;
    }

    ClientIdentity wrongServiceClient() {
      return wrongServiceClient;
    }

    ClientIdentity wrongNamespaceClient() {
      return wrongNamespaceClient;
    }

    ClientIdentity untrustedAccountClient() {
      return untrustedAccountClient;
    }

    ClientIdentity clientWithoutCertificate() {
      return new ClientIdentity(null, null);
    }

    private static Path createCa(Path directory, String alias) throws Exception {
      Path store = directory.resolve(alias + ".p12");
      runKeytool(
          "-genkeypair",
          "-alias",
          alias,
          "-keyalg",
          "RSA",
          "-keysize",
          "2048",
          "-dname",
          "CN=FireMUD Logging reservation " + alias + " test CA",
          "-validity",
          "30",
          "-ext",
          "BC=ca:true",
          "-ext",
          "KU=keyCertSign,cRLSign",
          "-storetype",
          "PKCS12",
          "-keystore",
          store.toString(),
          "-storepass",
          STORE_PASSWORD,
          "-keypass",
          STORE_PASSWORD);
      return store;
    }

    private static Path exportCa(Path directory, Path store, String alias) throws Exception {
      Path certificate = directory.resolve(alias + ".crt");
      runKeytool(
          "-exportcert",
          "-alias",
          alias,
          "-keystore",
          store.toString(),
          "-storetype",
          "PKCS12",
          "-storepass",
          STORE_PASSWORD,
          "-file",
          certificate.toString(),
          "-rfc");
      return certificate;
    }

    private static Identity issueIdentity(
        Path directory,
        Path caStore,
        Path caCertificate,
        String alias,
        String workloadUri,
        boolean server)
        throws Exception {
      Path store = directory.resolve(alias + ".p12");
      Path request = directory.resolve(alias + ".csr");
      Path certificate = directory.resolve(alias + ".crt");
      Path privateKey = directory.resolve(alias + ".key");
      String san = "URI:" + workloadUri + (server ? ",DNS:localhost,IP:127.0.0.1" : "");
      String extendedKeyUsage = server ? "serverAuth" : "clientAuth";
      runKeytool(
          "-genkeypair",
          "-alias",
          alias,
          "-keyalg",
          "RSA",
          "-keysize",
          "2048",
          "-dname",
          "CN=" + alias,
          "-validity",
          "30",
          "-ext",
          "KU=digitalSignature,keyEncipherment",
          "-ext",
          "EKU=" + extendedKeyUsage,
          "-ext",
          "SAN=" + san,
          "-storetype",
          "PKCS12",
          "-keystore",
          store.toString(),
          "-storepass",
          STORE_PASSWORD,
          "-keypass",
          STORE_PASSWORD);
      runKeytool(
          "-certreq",
          "-alias",
          alias,
          "-keystore",
          store.toString(),
          "-storetype",
          "PKCS12",
          "-storepass",
          STORE_PASSWORD,
          "-file",
          request.toString(),
          "-ext",
          "SAN=" + san);
      runKeytool(
          "-gencert",
          "-alias",
          caStoreAlias(caStore),
          "-keystore",
          caStore.toString(),
          "-storetype",
          "PKCS12",
          "-storepass",
          STORE_PASSWORD,
          "-infile",
          request.toString(),
          "-outfile",
          certificate.toString(),
          "-validity",
          "30",
          "-rfc",
          "-ext",
          "BC=ca:false",
          "-ext",
          "KU=digitalSignature,keyEncipherment",
          "-ext",
          "EKU=" + extendedKeyUsage,
          "-ext",
          "SAN=" + san);
      runKeytool(
          "-importcert",
          "-alias",
          caStoreAlias(caStore),
          "-keystore",
          store.toString(),
          "-storetype",
          "PKCS12",
          "-storepass",
          STORE_PASSWORD,
          "-file",
          caCertificate.toString(),
          "-noprompt");
      runKeytool(
          "-importcert",
          "-alias",
          alias,
          "-keystore",
          store.toString(),
          "-storetype",
          "PKCS12",
          "-storepass",
          STORE_PASSWORD,
          "-file",
          certificate.toString(),
          "-noprompt");

      KeyStore keyStore = KeyStore.getInstance("PKCS12");
      try (var input = Files.newInputStream(store)) {
        keyStore.load(input, STORE_PASSWORD.toCharArray());
      }
      var key = (java.security.PrivateKey) keyStore.getKey(alias, STORE_PASSWORD.toCharArray());
      byte[] certBytes = keyStore.getCertificate(alias).getEncoded();
      writePem(certificate, "CERTIFICATE", certBytes);
      writePem(privateKey, "PRIVATE KEY", key.getEncoded());
      return new Identity(certificate, privateKey);
    }

    private static ClientIdentity asClient(Identity identity) {
      return new ClientIdentity(identity.certificate(), identity.privateKey());
    }

    private static String caStoreAlias(Path caStore) {
      Objects.requireNonNull(
          caStore.getParent(), "CA store must have a test-owned parent directory");
      Path fileName = Objects.requireNonNull(caStore.getFileName(), "CA store must name a file");
      return fileName.toString().replace(".p12", "");
    }

    private static void runKeytool(String... arguments) throws Exception {
      Path keytool =
          Path.of(
              System.getProperty("java.home"),
              "bin",
              System.getProperty("os.name").toLowerCase().contains("windows")
                  ? "keytool.exe"
                  : "keytool");
      List<String> command = new ArrayList<>();
      command.add(keytool.toString());
      command.addAll(List.of(arguments));
      Path outputFile = Files.createTempFile("firemud-logging-reservation-keytool-", ".log");
      try {
        Process process =
            new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(outputFile.toFile())
                .start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
          process.destroyForcibly();
          process.waitFor(5, TimeUnit.SECONDS);
          throw new IllegalStateException("keytool exceeded its 30-second test-fixture timeout");
        }
        String output = Files.readString(outputFile, StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
          throw new IllegalStateException("keytool failed: " + output);
        }
      } finally {
        Files.deleteIfExists(outputFile);
      }
    }

    private static Path writePem(Path path, String label, byte[] bytes) throws IOException {
      String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(bytes);
      return Files.writeString(
          path,
          "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n",
          StandardCharsets.US_ASCII);
    }

    private record Identity(Path certificate, Path privateKey) {}

    private record ClientIdentity(Path certificate, Path privateKey) {}
  }
}
