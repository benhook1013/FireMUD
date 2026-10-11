package net.firedevops.firemud.common.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.Timestamp;
import com.google.protobuf.UnknownFieldSet;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.account.v1.ReadRedeemedOperationProjectionRequest;
import net.firedevops.firemud.account.v1.ReadRedeemedOperationProjectionResponse;
import net.firedevops.firemud.account.v1.StartSessionOperatorAuthorizationServiceGrpc;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** Physical mTLS proof for Game Design's read-only Account redeemed-operation projection client. */
class StartSessionRedeemedOperationProjectionClientTest {
  private static final String NAMESPACE = "test";
  private static final String ACCOUNT_URI = "spiffe://firemud/ns/test/sa/account-service";
  private static final String GAME_DESIGN_URI = "spiffe://firemud/ns/test/sa/game-design-service";
  private static final String OPAQUE_REFERENCE = "opaque-reference-never-sent";
  private static final String FORWARDED_JWT = "header.payload.signature";
  private static final String STORE_PASSWORD = "test-only-store-password";
  private static final UUID TENANT_ID = UUID.fromString("12345678-1234-4234-8234-123456789abc");
  private static final UUID ACTOR_ID = UUID.fromString("22345678-1234-4234-8234-123456789abc");
  private static final UUID OWNER_ACCOUNT_ID =
      UUID.fromString("32345678-1234-4234-8234-123456789abc");
  private static final UUID RESERVATION_OWNER_ID =
      UUID.fromString("42345678-1234-4234-8234-123456789abc");
  private static final UUID OWNER_ATTEMPT_ID =
      UUID.fromString("52345678-1234-4234-8234-123456789abc");
  private static final UUID ISSUANCE_OPERATION_ID =
      UUID.fromString("62345678-1234-4234-8234-123456789abc");
  private static final UUID CONTROL_UI_JTI =
      UUID.fromString("72345678-1234-4234-8234-123456789abc");
  private static final long RESERVATION_FENCE = 4L;
  private static final long OWNER_FENCE = 9L;
  private static final long ISSUANCE_FENCE = 23L;
  private static final String FINGERPRINT = "arfp/v1/test-key/" + "b".repeat(64);
  private static final Instant CLOCK_NOW = Instant.parse("2035-01-01T00:00:00Z");
  private static final Clock FIXED_CLOCK = Clock.fixed(CLOCK_NOW, ZoneOffset.UTC);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @TempDir static Path tempDirectory;

  private static TestPki pki;
  private Server server;
  private StartSessionRedeemedOperationProjectionClient client;
  private AtomicInteger applicationCalls;
  private AtomicReference<GrpcPeerIdentity> receivedPeer;
  private AtomicReference<ReadRedeemedOperationProjectionRequest> receivedRequest;
  private CountDownLatch timeoutRelease;

  @BeforeAll
  static void createTrustedWorkloadCertificates() throws Exception {
    pki = TestPki.create(tempDirectory);
  }

  @AfterEach
  void stopTransport() throws Exception {
    if (timeoutRelease != null) {
      timeoutRelease.countDown();
    }
    if (client != null) {
      client.close();
      client = null;
    }
    if (server != null) {
      server.shutdownNow();
      assertThat(server.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
      server = null;
    }
  }

  @Test
  void exactSameNamespaceWorkloadsReadTheOriginalProjectionOverPhysicalMtls() throws Exception {
    Fixture fixture = fixture(CLOCK_NOW.plusSeconds(3_600L));
    startServer(fixture, ResponseMode.EXACT);
    client = newClient(server);
    client.init();

    ReadRedeemedOperationProjectionResponse response =
        client.read(fixture.postTuple(), OWNER_ATTEMPT_ID, OWNER_FENCE);

    assertThat(response.getCanonicalPreAuthorizationTupleBytes().toByteArray())
        .containsExactly(
            fixture
                .postTuple()
                .preAuthorizationTuple()
                .canonicalJson()
                .getBytes(StandardCharsets.UTF_8));
    assertThat(response.getAuthorityEvidenceBundle().toByteArray())
        .containsExactly(fixture.postTuple().authorityEvidenceBundleBytes());
    assertThat(applicationCalls).hasValue(1);
    assertThat(receivedPeer.get())
        .isNotNull()
        .extracting(GrpcPeerIdentity::uri)
        .isEqualTo(GAME_DESIGN_URI);
    assertThat(receivedRequest.get().getAuthorizationReferenceFingerprint()).isEqualTo(FINGERPRINT);
    assertThat(receivedRequest.get().getCanonicalPreAuthorizationTupleBytes().toByteArray())
        .containsExactly(
            fixture
                .postTuple()
                .preAuthorizationTuple()
                .canonicalJson()
                .getBytes(StandardCharsets.UTF_8));
    assertThat(receivedRequest.get().getReservationOwnerId())
        .isEqualTo(RESERVATION_OWNER_ID.toString());
    assertThat(receivedRequest.get().getReservationClaimFence()).isEqualTo(RESERVATION_FENCE);
    assertThat(receivedRequest.get().getOwnerAttemptId()).isEqualTo(OWNER_ATTEMPT_ID.toString());
    assertThat(receivedRequest.get().getOwnerFence()).isEqualTo(OWNER_FENCE);
    assertThat(receivedRequest.get().toString())
        .doesNotContain(OPAQUE_REFERENCE)
        .doesNotContain(FORWARDED_JWT);
  }

  @Test
  void rejectsChangedOperationBindingAndUnknownResponseFields() throws Exception {
    Fixture fixture = fixture(CLOCK_NOW.plusSeconds(3_600L));
    startServer(fixture, ResponseMode.CHANGED_DIGEST);
    client = newClient(server);
    client.init();

    assertThatThrownBy(() -> client.read(fixture.postTuple(), OWNER_ATTEMPT_ID, OWNER_FENCE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("invalid redeemed StartSession operation projection");
    assertThat(applicationCalls).hasValue(1);

    stopTransport();
    startServer(fixture, ResponseMode.UNKNOWN_FIELD);
    client = newClient(server);
    client.init();
    assertThatThrownBy(() -> client.read(fixture.postTuple(), OWNER_ATTEMPT_ID, OWNER_FENCE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("invalid redeemed StartSession operation projection");
  }

  @Test
  void rejectsExpiredAccountReferenceEvenWhenResponseEchoesOriginalBundle() throws Exception {
    Fixture fixture = fixture(CLOCK_NOW.minusSeconds(60L));
    startServer(fixture, ResponseMode.EXACT);
    client = newClient(server);
    client.init();

    assertThatThrownBy(() -> client.read(fixture.postTuple(), OWNER_ATTEMPT_ID, OWNER_FENCE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("invalid redeemed StartSession operation projection");
    assertThat(applicationCalls).hasValue(1);
  }

  @Test
  void boundedDeadlineLeavesTransportUncertaintyAndDoesNotRetry() throws Exception {
    Fixture fixture = fixture(CLOCK_NOW.plusSeconds(3_600L));
    startServer(fixture, ResponseMode.BLOCK_UNTIL_RELEASED);
    client = newClient(server);
    client.init();

    assertThatThrownBy(() -> client.read(fixture.postTuple(), OWNER_ATTEMPT_ID, OWNER_FENCE))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure ->
                assertThat(((StatusRuntimeException) failure).getStatus().getCode())
                    .isEqualTo(Status.Code.DEADLINE_EXCEEDED));
    assertThat(applicationCalls).hasValue(1);
  }

  @Test
  void refusesReadOutsideConfiguredNamespaceAndBeforeExplicitInitialization() throws Exception {
    Fixture fixture = fixture(CLOCK_NOW.plusSeconds(3_600L));
    Fixture otherNamespaceFixture = fixture(CLOCK_NOW.plusSeconds(3_600L), "other");
    client = newClientPropertiesOnly();

    assertThatThrownBy(
            () -> client.read(otherNamespaceFixture.postTuple(), OWNER_ATTEMPT_ID, OWNER_FENCE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("configured workload namespace");
    assertThatThrownBy(() -> client.read(fixture.postTuple(), OWNER_ATTEMPT_ID, OWNER_FENCE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not initialized and available");
    assertThatThrownBy(() -> client.read(fixture.postTuple(), new UUID(0L, 0L), OWNER_FENCE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not be nil");
    assertThatThrownBy(() -> client.read(fixture.postTuple(), OWNER_ATTEMPT_ID, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be positive");
  }

  private void startServer(Fixture fixture, ResponseMode mode) throws Exception {
    applicationCalls = new AtomicInteger();
    receivedPeer = new AtomicReference<>();
    receivedRequest = new AtomicReference<>();
    timeoutRelease = new CountDownLatch(1);
    var service =
        new StartSessionOperatorAuthorizationServiceGrpc
            .StartSessionOperatorAuthorizationServiceImplBase() {
          @Override
          public void readRedeemedOperationProjection(
              ReadRedeemedOperationProjectionRequest request,
              StreamObserver<ReadRedeemedOperationProjectionResponse> observer) {
            applicationCalls.incrementAndGet();
            receivedPeer.set(GrpcPeerIdentity.current());
            receivedRequest.set(request);
            if (mode == ResponseMode.BLOCK_UNTIL_RELEASED) {
              try {
                if (!timeoutRelease.await(10, TimeUnit.SECONDS)) {
                  observer.onError(Status.DEADLINE_EXCEEDED.asRuntimeException());
                  return;
                }
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                observer.onError(Status.CANCELLED.asRuntimeException());
                return;
              }
            }

            ReadRedeemedOperationProjectionResponse response = response(fixture);
            if (mode == ResponseMode.CHANGED_DIGEST) {
              response = response.toBuilder().setMutationDigest("0".repeat(64)).build();
            } else if (mode == ResponseMode.UNKNOWN_FIELD) {
              response =
                  response.toBuilder()
                      .mergeUnknownFields(
                          UnknownFieldSet.newBuilder()
                              .addField(
                                  99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                              .build())
                      .build();
            }
            observer.onNext(response);
            observer.onCompleted();
          }
        };
    server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(
                            pki.accountServer().privateKey(), pki.accountServer().certificate()))
                    .trustManager(pki.caCertificate())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(ServerInterceptors.intercept(service, new GrpcPeerIdentityInterceptor()))
            .build()
            .start();
  }

  private StartSessionRedeemedOperationProjectionClient newClient(Server target)
      throws IOException, CertificateEncodingException {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setAccountService("localhost:" + target.getPort());
    return new StartSessionRedeemedOperationProjectionClient(
        endpoints,
        pki.clientProperties(tempDirectory),
        new GrpcChannelFactory(),
        NAMESPACE,
        FIXED_CLOCK);
  }

  private StartSessionRedeemedOperationProjectionClient newClientPropertiesOnly() throws Exception {
    return new StartSessionRedeemedOperationProjectionClient(
        new ServiceEndpointsProperties(),
        pki.clientProperties(tempDirectory),
        new GrpcChannelFactory(),
        NAMESPACE,
        FIXED_CLOCK);
  }

  private static ReadRedeemedOperationProjectionResponse response(Fixture fixture) {
    StartSessionPostAuthorizationExecutionTuple postTuple = fixture.postTuple();
    StartSessionAuthorityEvidenceBundle.BundleReference reference = postTuple.bundleReference();
    String targetNamespace = postTuple.preAuthorizationTuple().action().scope().targetNamespace();
    return ReadRedeemedOperationProjectionResponse.newBuilder()
        .setControlPlaneRequestId(postTuple.controlPlaneRequestId())
        .setCanonicalPreAuthorizationTupleBytes(
            com.google.protobuf.ByteString.copyFrom(
                postTuple.preAuthorizationTuple().canonicalJson().getBytes(StandardCharsets.UTF_8)))
        .setMutationDigest(postTuple.mutationDigest())
        .setAuthorizationReferenceFingerprint(postTuple.authorizationReferenceFingerprint())
        .setReservationOwnerId(postTuple.reservationOwnerId().toString())
        .setReservationClaimFence(postTuple.reservationClaimFence())
        .setAuthenticatedRedeemerWorkloadIdentity(
            "spiffe://firemud/ns/" + targetNamespace + "/sa/game-session-service")
        .setOwnerAttemptId(OWNER_ATTEMPT_ID.toString())
        .setOwnerFence(OWNER_FENCE)
        .setReferenceExpiresAt(timestamp(fixture.referenceExpiresAt()))
        .setRedeemedAt(timestamp(fixture.redeemedAt()))
        .setIssuanceOperationId(ISSUANCE_OPERATION_ID.toString())
        .setIssuanceFence(ISSUANCE_FENCE)
        .setBundleReference(
            net.firedevops.firemud.account.v1.AuthorityEvidenceBundleReference.newBuilder()
                .setBundleVersion(reference.bundleVersion())
                .setSourceVersion(reference.sourceVersion())
                .setSourceFence(reference.sourceFence())
                .setLinearization(reference.linearization())
                .build())
        .setAuthorityEvidenceBundle(
            com.google.protobuf.ByteString.copyFrom(postTuple.authorityEvidenceBundleBytes()))
        .build();
  }

  private static Fixture fixture(Instant expiresAt) {
    return fixture(expiresAt, NAMESPACE);
  }

  private static Fixture fixture(Instant expiresAt, String targetNamespace) {
    StartSessionOperatorAction action =
        new StartSessionOperatorAction(
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
            new StartSessionOperatorAction.Scope(TENANT_ID, targetNamespace),
            new StartSessionOperatorAction.Target(91L, OWNER_ACCOUNT_ID),
            StartSessionOperatorAction.ExpectedVersion.ABSENT,
            new StartSessionOperatorAction.Mutation(StartSessionOperatorAction.ClientIp.absent()),
            "Read original StartSession source projection");
    StartSessionPreAuthorizationReservationTuple preTuple =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            "redeemed-projection-request-β", ACTOR_ID, action);
    StartSessionAuthorityEvidenceBundle.BundleReference reference =
        new StartSessionAuthorityEvidenceBundle.BundleReference(
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "17",
            Long.toString(ISSUANCE_FENCE),
            "18446744073709551615");
    byte[] bundle = authorityEvidenceBundle(preTuple, expiresAt, targetNamespace);
    StartSessionPostAuthorizationExecutionTuple postTuple =
        StartSessionPostAuthorizationExecutionTuple.createHuman(
            preTuple,
            "spiffe://firemud/ns/" + targetNamespace + "/sa/logging-admin-service",
            FINGERPRINT,
            RESERVATION_OWNER_ID,
            RESERVATION_FENCE,
            bundle,
            reference);
    Instant redeemedAt =
        expiresAt.isAfter(CLOCK_NOW) ? CLOCK_NOW.minusSeconds(60L) : expiresAt.minusSeconds(60L);
    return new Fixture(postTuple, expiresAt, redeemedAt);
  }

  private static byte[] authorityEvidenceBundle(
      StartSessionPreAuthorizationReservationTuple tuple,
      Instant expiresAt,
      String targetNamespace) {
    String tenantId = TENANT_ID.toString();
    Map<String, Object> value =
        Map.of(
            "bundleVersion", StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "authorityScope",
                Map.of(
                    "scope", Map.of("tenantId", tenantId, "targetNamespace", targetNamespace),
                    "actionFamily", tuple.actionFamily(),
                    "applicableAccountId", ACTOR_ID.toString(),
                    "applicableTenantId", tenantId),
            "accountProjectionEvidence",
                Map.of(
                    "sourceType",
                    "ACCOUNT",
                    "sourceEvidenceId",
                    "sha256:" + "a".repeat(64),
                    "sourceEvidenceVersion",
                    "17",
                    "projectionStatus",
                    "CURRENT",
                    "evaluatedAt",
                    expiresAt.minusSeconds(600L).toString(),
                    "expiresAt",
                    expiresAt.toString()),
            "issuanceOperationIdentity",
                Map.of(
                    "issuanceOperationId", ISSUANCE_OPERATION_ID.toString(),
                    "controlPlaneRequestId", tuple.controlPlaneRequestId(),
                    "actionFamilyRequestIdentity",
                        Map.of(
                            "requestIdentityKind",
                            "controlPlaneRequestId",
                            "requestId",
                            tuple.controlPlaneRequestId()),
                    "mutationDigest", tuple.mutationDigest()),
            "issuanceKind", "human_operator",
            "authorityTuple",
                Map.of(
                    "issuerAuthGeneration", 1L,
                    "accountAuthorityGeneration", 2L,
                    "tenantAuthorityGeneration", Map.of(tenantId, 3L),
                    "membershipAuthorityGeneration", Map.of(tenantId, 4L),
                    "privateRealmGrantVersions", List.of()),
            "membershipVersion", Map.of(tenantId, 5L),
            "issuanceFence", Long.toString(ISSUANCE_FENCE),
            "issuanceEvidence",
                Map.of(
                    "evidenceType",
                    StartSessionAuthorityEvidenceBundle.HUMAN_EVIDENCE_TYPE,
                    "actorAccountId",
                    ACTOR_ID.toString(),
                    "controlUiTokenJti",
                    CONTROL_UI_JTI.toString(),
                    "role",
                    "tenantAdmin",
                    "accountGeneration",
                    "2",
                    "tenantGeneration",
                    "3"));
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (IOException malformed) {
      throw new IllegalStateException("could not encode test Account authority bundle", malformed);
    }
  }

  private static Timestamp timestamp(Instant value) {
    return Timestamp.newBuilder()
        .setSeconds(value.getEpochSecond())
        .setNanos(value.getNano())
        .build();
  }

  private enum ResponseMode {
    EXACT,
    CHANGED_DIGEST,
    UNKNOWN_FIELD,
    BLOCK_UNTIL_RELEASED
  }

  private record Fixture(
      StartSessionPostAuthorizationExecutionTuple postTuple,
      Instant referenceExpiresAt,
      Instant redeemedAt) {}

  private record TestIdentity(String alias, PrivateKey privateKey, X509Certificate certificate) {}

  private record TestPki(
      X509Certificate caCertificate, TestIdentity accountServer, TestIdentity gameDesignClient) {
    private static TestPki create(Path directory) throws Exception {
      Path caStore = directory.resolve("test-ca.p12");
      runKeytool(
          "-genkeypair",
          "-alias",
          "test-ca",
          "-keyalg",
          "RSA",
          "-keysize",
          "2048",
          "-dname",
          "CN=FireMUD Account projection test CA",
          "-validity",
          "30",
          "-ext",
          "BC=ca:true",
          "-ext",
          "KU=keyCertSign,cRLSign",
          "-storetype",
          "PKCS12",
          "-keystore",
          caStore.toString(),
          "-storepass",
          STORE_PASSWORD,
          "-keypass",
          STORE_PASSWORD);
      Path caFile = directory.resolve("test-ca.crt");
      runKeytool(
          "-exportcert",
          "-alias",
          "test-ca",
          "-keystore",
          caStore.toString(),
          "-storetype",
          "PKCS12",
          "-storepass",
          STORE_PASSWORD,
          "-file",
          caFile.toString(),
          "-rfc");
      X509Certificate caCertificate = readCertificate(caFile);
      return new TestPki(
          caCertificate,
          issueIdentity(directory, caStore, caFile, "account-server", ACCOUNT_URI, true),
          issueIdentity(directory, caStore, caFile, "game-design-client", GAME_DESIGN_URI, false));
    }

    private CommonGrpcClientProperties clientProperties(Path directory)
        throws IOException, CertificateEncodingException {
      Path clientCertificate =
          writePem(
              directory.resolve("game-design-client.crt"),
              "CERTIFICATE",
              gameDesignClient.certificate().getEncoded());
      Path clientPrivateKey =
          writePem(
              directory.resolve("game-design-client.key"),
              "PRIVATE KEY",
              gameDesignClient.privateKey().getEncoded());
      Path trustCa =
          writePem(
              directory.resolve("client-trust-ca.crt"), "CERTIFICATE", caCertificate.getEncoded());
      CommonGrpcClientProperties properties = new CommonGrpcClientProperties();
      properties.setCertChain(clientCertificate.toString());
      properties.setPrivateKey(clientPrivateKey.toString());
      properties.setCaCert(trustCa.toString());
      return properties;
    }

    private static TestIdentity issueIdentity(
        Path directory, Path caStore, Path caFile, String alias, String workloadUri, boolean server)
        throws Exception {
      Path store = directory.resolve(alias + ".p12");
      Path request = directory.resolve(alias + ".csr");
      Path certificate = directory.resolve(alias + ".crt");
      String san = "URI:" + workloadUri + ",DNS:localhost,IP:127.0.0.1";
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
          "EKU=" + (server ? "serverAuth" : "clientAuth"),
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
          "test-ca",
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
          "EKU=" + (server ? "serverAuth" : "clientAuth"),
          "-ext",
          "SAN=" + san);
      runKeytool(
          "-importcert",
          "-alias",
          "test-ca",
          "-keystore",
          store.toString(),
          "-storetype",
          "PKCS12",
          "-storepass",
          STORE_PASSWORD,
          "-file",
          caFile.toString(),
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
      return new TestIdentity(
          alias,
          (PrivateKey) keyStore.getKey(alias, STORE_PASSWORD.toCharArray()),
          (X509Certificate) keyStore.getCertificate(alias));
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
      Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
      String output;
      try (var stream = process.getInputStream()) {
        output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      }
      if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
        process.destroyForcibly();
        throw new IllegalStateException("keytool failed: " + output);
      }
    }

    private static X509Certificate readCertificate(Path path) throws Exception {
      try (var input = Files.newInputStream(path)) {
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
      }
    }

    private static Path writePem(Path path, String label, byte[] bytes) throws IOException {
      String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(bytes);
      return Files.writeString(
          path,
          "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n",
          StandardCharsets.US_ASCII);
    }
  }
}
