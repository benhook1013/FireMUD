package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.UnknownFieldSet;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigInteger;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.firedevops.firemud.account.v1.AccountPublicJwksReadServiceGrpc;
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import net.firedevops.firemud.account.v1.GetCurrentAccountPublicJwksRequest;
import net.firedevops.firemud.account.v1.GetProfileRequest;
import net.firedevops.firemud.account.v1.GetProfileResponse;
import net.firedevops.firemud.accountservice.config.AccountJwtJwksApiBinding;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding.Binding;
import net.firedevops.firemud.accountservice.config.AccountPublicJwksReadWorkloadGuard;
import net.firedevops.firemud.accountservice.service.impl.AccountPublicJwksReadGrpcService;
import net.firedevops.firemud.accountservice.testsupport.AccountStartSessionReservationProofPki;
import net.firedevops.firemud.accountservice.testsupport.AccountStartSessionReservationProofPki.IssuedIdentity;
import net.firedevops.firemud.accountservice.testsupport.AccountStartSessionReservationProofPki.TestCertificateAuthority;
import net.firedevops.firemud.common.config.CommonGrpcRequestBoundsAutoConfiguration;
import net.firedevops.firemud.common.config.CommonSecurityAutoConfiguration;
import net.firedevops.firemud.common.grpc.BoundedGrpcServerFactory;
import net.firedevops.firemud.common.grpc.CommonGrpcServerConfiguration;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import net.firedevops.firemud.common.security.AccountPublicJwksCache;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.PublicJwksSnapshot;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.SourceIdentity;
import net.firedevops.firemud.common.security.AccountPublicJwksReadCodec;
import net.firedevops.firemud.common.security.AuthTokenInterceptor;
import net.firedevops.firemud.common.security.GrpcAuthProperties;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.common.security.SessionContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.grpc.server.autoconfigure.GrpcServerAutoConfiguration;
import org.springframework.boot.grpc.server.autoconfigure.GrpcServerFactoryAutoConfiguration;
import org.springframework.boot.ssl.DefaultSslBundleRegistry;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.ssl.SslStoreBundle;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.FileSystemResource;
import org.springframework.grpc.server.GrpcServerFactory;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.grpc.server.service.GrpcService;

/**
 * Ordinary-listener registration proof: real Spring gRPC discovery, production global bearer and
 * TLS identity interceptors, genuine receiver/owner/guard, and physical loopback mutual TLS.
 * Protected provisioning is a controlled binding input, and only the trusted-publication source is
 * mocked. This does not prove the live Account database/Kubernetes publication source, production
 * PKI, signer custody, validator convergence, or gameplay LOGIN/admission.
 */
class AccountPublicJwksReadRegistrationMtlsIntegrationTest {
  private static final String NAMESPACE = "test";
  private static final String CLUSTER_UID = "11111111-1111-4111-8111-111111111111";
  private static final String NAMESPACE_UID = "22222222-2222-4222-8222-222222222222";
  private static final String CONFIG_MAP_UID = "33333333-3333-4333-8333-333333333333";
  private static final String METHOD =
      "account.v1.AccountPublicJwksReadService/GetCurrentPublicJwks";
  private static final String PROFILE_METHOD = "account.v1.AccountService/GetProfile";
  private static final String GAME_SESSION_URI =
      "spiffe://firemud/ns/" + NAMESPACE + "/sa/game-session-service";
  private static final String PASSWORD = "test-only-current-public-jwks-password";
  @TempDir static Path directory;
  private static TestCertificateAuthority authority;
  private static Identity server;
  private static Identity gameSession;
  private static List<Identity> rejectedPeers;
  private static byte[] publicJwks;

  @BeforeAll
  static void createControlledCertificates() throws Exception {
    authority =
        AccountStartSessionReservationProofPki.createCertificateAuthority(
            directory, "ca.p12", "ca.crt", "ca", "CN=Account public JWKS listener CA", PASSWORD);
    server = issue("account", "spiffe://firemud/ns/test/sa/account-service", true);
    gameSession = issue("game-session", GAME_SESSION_URI, false);
    Identity wrongService =
        issue("wrong-service", "spiffe://firemud/ns/test/sa/account-service", false);
    Identity crossNamespace =
        issue("other-namespace", "spiffe://firemud/ns/other/sa/game-session-service", false);
    Identity multipleUris =
        issue(
            "multiple-uris",
            GAME_SESSION_URI + ",URI:spiffe://firemud/ns/test/sa/account-service",
            false);
    Identity noUri = issue("no-uri", GAME_SESSION_URI, false);
    // Re-sign the fixture CSR without SAN extensions. keytool does not honor requested extensions
    // unless explicitly selected, so this trusted client certificate contains no workload URI.
    AccountStartSessionReservationProofPki.runKeytool(
        directory,
        "-gencert",
        "-alias",
        authority.alias(),
        "-keystore",
        authority.keyStore().toString(),
        "-storetype",
        "PKCS12",
        "-storepass",
        PASSWORD,
        "-infile",
        directory.resolve("no-uri.csr").toString(),
        "-outfile",
        noUri.certificate().toString(),
        "-validity",
        "30",
        "-rfc",
        "-ext",
        "BC=ca:false",
        "-ext",
        "KU=digitalSignature,keyEncipherment",
        "-ext",
        "EKU=clientAuth");
    try (var input = Files.newInputStream(noUri.certificate())) {
      var certificate =
          (java.security.cert.X509Certificate)
              java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(input);
      assertThat(certificate.getSubjectAlternativeNames()).isNull();
    }
    rejectedPeers = List.of(wrongService, crossNamespace, noUri, multipleUris);
    var rsa = (RSAPublicKey) server.issued().identity().certificate().getPublicKey();
    publicJwks =
        ("{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"listener-proof\",\"use\":\"sig\","
                + "\"alg\":\"RS256\",\"n\":\""
                + unsigned(rsa.getModulus())
                + "\",\"e\":\""
                + unsigned(rsa.getPublicExponent())
                + "\"}]}")
            .getBytes(StandardCharsets.UTF_8);
  }

  @ParameterizedTest
  @ValueSource(strings = {"application.yml", "application-prod.yml"})
  void actualRegistrationUsesExactTlsWorkloadRatherThanBearerOrRequestClaims(String resource)
      throws Exception {
    var protectedBinding = new ControlledProtectedBinding();
    var source = mock(AccountJwtJwksTrustedSource.class);
    var snapshot = new PublicJwksSnapshot(sourceIdentity(), publicJwks);
    when(source.sourceIdentity()).thenReturn(snapshot.sourceIdentity());
    when(source.load())
        .thenAnswer(
            ignored -> {
              assertThat(GrpcPeerIdentity.current().uri()).isEqualTo(GAME_SESSION_URI);
              assertEmptyBearerContext();
              return snapshot;
            });
    int port = unusedLoopbackPort();
    runner(resource, port, source, protectedBinding)
        .withPropertyValues("firemud.account.public-jwks-read.enabled=true")
        .run(
            context -> {
              assertThat(context)
                  .hasNotFailed()
                  .hasSingleBean(AccountPublicJwksReadGrpcService.class);
              assertThat(context).hasSingleBean(AccountPublicJwksReadService.class);
              assertThat(context).hasSingleBean(AuthTokenInterceptor.class);
              assertThat(context).hasSingleBean(GrpcPeerIdentityInterceptor.class);
              assertThat(context.getBean(GrpcServerFactory.class))
                  .isExactlyInstanceOf(BoundedGrpcServerFactory.class);
              assertThat(context.getBean(GrpcServerLifecycle.class).isRunning()).isTrue();
              assertThat(context.getBean(GrpcAuthProperties.class).getPublicMethods())
                  .contains(METHOD)
                  .doesNotContain(PROFILE_METHOD, "account.v1.AccountPublicJwksReadService/*");
              String accountId = "44444444-4444-4444-8444-444444444444";
              String token =
                  context
                      .getBean(JwtUtil.class)
                      .generateToken(
                          accountId,
                          Map.of("accountId", accountId, "globalRoles", List.of("platformAdmin")));
              try (var client = new Client(port, gameSession)) {
                var response =
                    client.stub().getCurrentPublicJwks(AccountPublicJwksReadCodec.request());
                assertThat(response).isEqualTo(AccountPublicJwksReadCodec.response(snapshot));
                assertThat(AccountPublicJwksReadCodec.snapshot(response).jwksBytes())
                    .isEqualTo(publicJwks);
                assertThat(response.getSourceIdentity().getAllFields()).hasSize(9);
                // A valid privileged bearer is ignored for this exact method. The actual guard
                // still sees empty SessionContext in source.load(), not the bearer claims.
                assertThat(
                        client
                            .withBearer(token)
                            .getCurrentPublicJwks(AccountPublicJwksReadCodec.request()))
                    .isEqualTo(response);
                var adjacent = context.getBean(AdjacentAccountProfileService.class);
                assertCode(
                    Status.Code.UNAUTHENTICATED,
                    () ->
                        AccountServiceGrpc.newBlockingStub(client.channel)
                            .getProfile(GetProfileRequest.getDefaultInstance()));
                assertThat(adjacent.calls).hasValue(0);
                assertThat(
                        AccountServiceGrpc.newBlockingStub(client.channel)
                            .withInterceptors(
                                MetadataUtils.newAttachHeadersInterceptor(headers(token)))
                            .getProfile(GetProfileRequest.getDefaultInstance())
                            .getProfileJson())
                    .isEqualTo("registered non-exempt handler");
                assertThat(adjacent.calls).hasValue(1);
                // The authenticated adjacent call cannot contaminate the following exempt read.
                assertThat(client.stub().getCurrentPublicJwks(AccountPublicJwksReadCodec.request()))
                    .isEqualTo(response);
              }
              clearInvocations(source);
              for (Identity peer : rejectedPeers) {
                try (var client = new Client(port, peer)) {
                  assertCode(
                      Status.Code.PERMISSION_DENIED,
                      () ->
                          client.stub().getCurrentPublicJwks(AccountPublicJwksReadCodec.request()));
                  assertCode(
                      Status.Code.PERMISSION_DENIED,
                      () ->
                          client
                              .withBearer(token)
                              .getCurrentPublicJwks(AccountPublicJwksReadCodec.request()));
                }
                verifyNoInteractions(source);
              }
              try (var client = new Client(port, null)) {
                assertCode(
                    Status.Code.UNAVAILABLE,
                    () ->
                        client
                            .withBearer(token)
                            .getCurrentPublicJwks(AccountPublicJwksReadCodec.request()));
              }
              verifyNoInteractions(source);
              try (var client = new Client(port, gameSession)) {
                for (var malformed :
                    List.of(
                        GetCurrentAccountPublicJwksRequest.getDefaultInstance(),
                        AccountPublicJwksReadCodec.request().toBuilder()
                            .setSchemaVersion(2)
                            .build(),
                        AccountPublicJwksReadCodec.request().toBuilder()
                            .setUnknownFields(
                                UnknownFieldSet.newBuilder()
                                    .addField(
                                        99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
                                    .build())
                            .build())) {
                  assertCode(
                      Status.Code.INVALID_ARGUMENT,
                      () -> client.stub().getCurrentPublicJwks(malformed));
                  verifyNoInteractions(source);
                }
                doThrow(new IllegalStateException("private internal handler fault"))
                    .when(source)
                    .load();
                assertCode(
                    Status.Code.UNAVAILABLE,
                    () -> client.stub().getCurrentPublicJwks(AccountPublicJwksReadCodec.request()));
                doReturn(
                        new PublicJwksSnapshot(
                            sourceIdentity(), new byte[AccountPublicJwksCache.MAX_JWKS_BYTES + 1]))
                    .when(source)
                    .load();
                assertCode(
                    Status.Code.FAILED_PRECONDITION,
                    () -> client.stub().getCurrentPublicJwks(AccountPublicJwksReadCodec.request()));
                doReturn(snapshot).when(source).load();
                assertThat(client.stub().getCurrentPublicJwks(AccountPublicJwksReadCodec.request()))
                    .isEqualTo(AccountPublicJwksReadCodec.response(snapshot));
                doThrow(new AccountPublicJwksCache.InvalidJwksException()).when(source).load();
                assertCode(
                    Status.Code.FAILED_PRECONDITION,
                    () -> client.stub().getCurrentPublicJwks(AccountPublicJwksReadCodec.request()));
                doThrow(new AccountPublicJwksCache.SourceUnavailableException())
                    .when(source)
                    .load();
                assertCode(
                    Status.Code.UNAVAILABLE,
                    () -> client.stub().getCurrentPublicJwks(AccountPublicJwksReadCodec.request()));
                doReturn(
                        new PublicJwksSnapshot(
                            new SourceIdentity(
                                "test",
                                "cluster-a",
                                CLUSTER_UID,
                                NAMESPACE,
                                NAMESPACE_UID,
                                "55555555-5555-4555-8555-555555555555",
                                "api-r1",
                                "https://kubernetes.test:6443",
                                "b".repeat(64)),
                            publicJwks))
                    .when(source)
                    .load();
                assertCode(
                    Status.Code.FAILED_PRECONDITION,
                    () -> client.stub().getCurrentPublicJwks(AccountPublicJwksReadCodec.request()));
                doAnswer(
                        ignored -> {
                          protectedBinding.trust.set(Optional.empty());
                          return snapshot;
                        })
                    .when(source)
                    .load();
                assertCode(
                    Status.Code.PERMISSION_DENIED,
                    () -> client.stub().getCurrentPublicJwks(AccountPublicJwksReadCodec.request()));
                clearInvocations(source);
                assertCode(
                    Status.Code.PERMISSION_DENIED,
                    () -> client.stub().getCurrentPublicJwks(AccountPublicJwksReadCodec.request()));
                verifyNoInteractions(source);
              }
            });
  }

  @ParameterizedTest
  @ValueSource(strings = {"application.yml", "application-prod.yml"})
  void publicReadReceiverIsAbsentWithoutItsSeparateExplicitOptIn(String resource) throws Exception {
    var source = mock(AccountJwtJwksTrustedSource.class);
    int port = unusedLoopbackPort();
    runner(resource, port, source, new ControlledProtectedBinding())
        .run(
            context -> {
              assertThat(context)
                  .hasNotFailed()
                  .doesNotHaveBean(AccountPublicJwksReadGrpcService.class);
              assertThat(context.getBean(GrpcServerLifecycle.class).isRunning()).isTrue();
              try (var client = new Client(port, gameSession)) {
                assertCode(
                    Status.Code.UNIMPLEMENTED,
                    () -> client.stub().getCurrentPublicJwks(AccountPublicJwksReadCodec.request()));
              }
              verifyNoInteractions(source);
            });
  }

  private static ApplicationContextRunner runner(
      String resource,
      int port,
      AccountJwtJwksTrustedSource source,
      ControlledProtectedBinding protectedBinding)
      throws Exception {
    SslBundles bundles = sslBundles();
    var yaml =
        new YamlPropertySourceLoader()
            .load(
                "actual-account-" + resource,
                new FileSystemResource(Path.of("src/main/resources").resolve(resource)));
    return new ApplicationContextRunner()
        .withInitializer(
            context ->
                yaml.forEach(
                    propertySource ->
                        context.getEnvironment().getPropertySources().addLast(propertySource)))
        .withConfiguration(
            AutoConfigurations.of(
                ConfigurationPropertiesAutoConfiguration.class,
                CommonSecurityAutoConfiguration.class,
                CommonGrpcRequestBoundsAutoConfiguration.class,
                GrpcServerFactoryAutoConfiguration.class,
                GrpcServerAutoConfiguration.class))
        .withUserConfiguration(
            CommonGrpcServerConfiguration.class,
            AccountPublicJwksReadGrpcService.class,
            AccountPublicJwksReadService.class,
            AdjacentAccountProfileService.class)
        .withBean(AccountJwtJwksTrustedSource.class, () -> source)
        .withBean(AccountPublicJwksReadWorkloadGuard.class, protectedBinding::guard)
        .withBean(SslBundles.class, () -> bundles)
        .withBean(io.micrometer.core.instrument.MeterRegistry.class, SimpleMeterRegistry::new)
        .withBean(
            RuntimeIdentity.class,
            () ->
                new RuntimeIdentity(
                    "account-service",
                    "public-jwks-listener-test",
                    "localhost",
                    Instant.EPOCH,
                    "test",
                    "test",
                    "test"))
        .withPropertyValues(
            "spring.grpc.server.address=127.0.0.1:" + port,
            "spring.grpc.server.port=" + port,
            "spring.grpc.server.shutdown-grace-period=0s",
            "spring.grpc.server.ssl.enabled=true",
            "firemud.auth.jwt-secret=test-only-public-jwks-listener-secret-32-bytes",
            "firemud.account.gameplay-login.enabled=false");
  }

  /**
   * Supplies no owner business behavior; this generated non-exempt descriptor tests global auth.
   */
  @GrpcService
  static class AdjacentAccountProfileService extends AccountServiceGrpc.AccountServiceImplBase {
    final AtomicInteger calls = new AtomicInteger();

    @Override
    public void getProfile(GetProfileRequest request, StreamObserver<GetProfileResponse> observer) {
      calls.incrementAndGet();
      observer.onNext(
          GetProfileResponse.newBuilder().setProfileJson("registered non-exempt handler").build());
      observer.onCompleted();
    }
  }

  /** Controlled independent protected inputs, not a production file-provisioning proof. */
  private static final class ControlledProtectedBinding {
    final AtomicReference<Optional<Binding>> trust = new AtomicReference<>(Optional.of(binding()));
    final AtomicReference<AccountJwtJwksApiBinding.ParsedBinding> api =
        new AtomicReference<>(
            new AccountJwtJwksApiBinding.ParsedBinding(
                "api-r1",
                "test",
                "cluster-a",
                URI.create("https://kubernetes.test:6443"),
                "kubernetes.test",
                directory.resolve("stipulated-serving-ca.pem"),
                "b".repeat(64),
                directory.resolve("stipulated-api-token"),
                NAMESPACE,
                CLUSTER_UID,
                NAMESPACE_UID,
                "system:serviceaccount:test:account-service",
                "c".repeat(64)));

    AccountPublicJwksReadWorkloadGuard guard() {
      try {
        // Use the production guard's existing controlled-input seam without mocking its decisions
        // or changing public access to that seam merely for a differently packaged proof.
        var constructor =
            AccountPublicJwksReadWorkloadGuard.class.getDeclaredConstructor(
                Supplier.class, Supplier.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(
            (Supplier<Optional<Binding>>) trust::get,
            (Supplier<AccountJwtJwksApiBinding.ParsedBinding>) api::get,
            NAMESPACE);
      } catch (ReflectiveOperationException failure) {
        throw new IllegalStateException(
            "Could not construct controlled production workload guard", failure);
      }
    }
  }

  private static Binding binding() {
    String peer = "spiffe://firemud/ns/test/sa/jwt-signer-materializer";
    List<String> pins = List.of("a".repeat(64));
    String digest =
        AccountJwtSignerMaterializerTrustBinding.computeBindingDigest(
            "materializer-r1",
            "test",
            "cluster-a",
            NAMESPACE,
            CLUSTER_UID,
            NAMESPACE_UID,
            peer,
            pins);
    return new Binding(
        "test",
        "cluster-a",
        NAMESPACE,
        CLUSTER_UID,
        NAMESPACE_UID,
        peer,
        pins,
        "materializer-r1",
        digest);
  }

  private static SourceIdentity sourceIdentity() {
    return new SourceIdentity(
        "test",
        "cluster-a",
        CLUSTER_UID,
        NAMESPACE,
        NAMESPACE_UID,
        CONFIG_MAP_UID,
        "api-r1",
        "https://kubernetes.test:6443",
        "b".repeat(64));
  }

  private static void assertEmptyBearerContext() {
    assertThat(SessionContext.getAccountId()).isNull();
    assertThat(SessionContext.getGlobalRoles()).isEmpty();
    assertThat(SessionContext.getScopedRolesMap()).isEmpty();
    assertThat(SessionContext.isInternalService()).isFalse();
    assertThat(SessionContext.getServiceName()).isNull();
    assertThat(SessionContext.getServiceInstanceId()).isNull();
  }

  private static Metadata headers(String bearer) {
    var headers = new Metadata();
    headers.put(
        Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + bearer);
    headers.put(
        Metadata.Key.of("x-firemud-peer-uri", Metadata.ASCII_STRING_MARSHALLER), GAME_SESSION_URI);
    return headers;
  }

  private static void assertCode(Status.Code expected, Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure -> assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(expected));
  }

  private static int unusedLoopbackPort() throws Exception {
    try (var socket = new ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))) {
      return socket.getLocalPort();
    }
  }

  private static SslBundles sslBundles() throws Exception {
    KeyStore keys = KeyStore.getInstance("PKCS12");
    keys.load(null, null);
    keys.setKeyEntry(
        "account",
        server.issued().identity().privateKey(),
        PASSWORD.toCharArray(),
        new java.security.cert.Certificate[] {
          server.issued().identity().certificate(), authority.certificate()
        });
    KeyStore trust = KeyStore.getInstance("PKCS12");
    trust.load(null, null);
    trust.setCertificateEntry("ca", authority.certificate());
    var bundles = new DefaultSslBundleRegistry();
    bundles.registerBundle("firemud-grpc", SslBundle.of(SslStoreBundle.of(keys, PASSWORD, trust)));
    return bundles;
  }

  private static Identity issue(String alias, String workloadUri, boolean isServer)
      throws Exception {
    var issued =
        AccountStartSessionReservationProofPki.issueIdentity(
            directory,
            authority,
            alias,
            alias + ".p12",
            alias + ".csr",
            alias + ".crt",
            workloadUri,
            isServer,
            true,
            false);
    Path key = directory.resolve(alias + ".key");
    byte[] encoded = issued.identity().privateKey().getEncoded();
    try {
      Files.writeString(
          key,
          "-----BEGIN PRIVATE KEY-----\n"
              + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(encoded)
              + "\n-----END PRIVATE KEY-----\n",
          StandardCharsets.US_ASCII);
    } finally {
      java.util.Arrays.fill(encoded, (byte) 0);
    }
    return new Identity(issued, issued.certificateFile(), key);
  }

  private static String unsigned(BigInteger value) {
    byte[] bytes = value.toByteArray();
    if (bytes[0] == 0) bytes = java.util.Arrays.copyOfRange(bytes, 1, bytes.length);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private record Identity(IssuedIdentity issued, Path certificate, Path key) {}

  private static final class Client implements AutoCloseable {
    final ManagedChannel channel;

    Client(int port, Identity identity) throws Exception {
      var tls = GrpcSslContexts.forClient().trustManager(authority.certificateFile().toFile());
      if (identity != null)
        tls.keyManager(identity.certificate().toFile(), identity.key().toFile());
      channel =
          NettyChannelBuilder.forAddress("127.0.0.1", port)
              .sslContext(tls.build())
              .maxInboundMessageSize(AccountPublicJwksReadCodec.MAX_RESPONSE_BYTES)
              .build();
    }

    AccountPublicJwksReadServiceGrpc.AccountPublicJwksReadServiceBlockingStub stub() {
      return AccountPublicJwksReadServiceGrpc.newBlockingStub(channel)
          .withDeadlineAfter(10, TimeUnit.SECONDS);
    }

    AccountPublicJwksReadServiceGrpc.AccountPublicJwksReadServiceBlockingStub withBearer(
        String bearer) {
      return stub().withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers(bearer)));
    }

    @Override
    public void close() throws Exception {
      channel.shutdownNow();
      assertThat(channel.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }
}
