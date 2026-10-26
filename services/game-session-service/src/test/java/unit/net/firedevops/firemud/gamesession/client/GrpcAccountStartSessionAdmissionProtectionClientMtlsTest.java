package net.firedevops.firemud.gamesession.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.stub.StreamObserver;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.account.v1.AccountStartSessionAdmissionProtectionServiceGrpc;
import net.firedevops.firemud.account.v1.AcquireStartSessionAdmissionProtectionRequest;
import net.firedevops.firemud.account.v1.AcquireStartSessionAdmissionProtectionResponse;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionEvidence;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionRequest;
import net.firedevops.firemud.common.account.startsession.StartSessionAccountRedemptionProjection;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
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
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** Socket mTLS proof for the Game Session client and complete Account-owned evidence response. */
class GrpcAccountStartSessionAdmissionProtectionClientMtlsTest {
  private static final String NAMESPACE = "world-runtime";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @TempDir Path directory;

  private Server server;
  private GrpcAccountStartSessionAdmissionProtectionClient client;
  private LoopbackMtlsTestPki.Pki pki;
  private AtomicInteger rpcCalls;
  private AtomicReference<GrpcPeerIdentity> receivedPeer;
  private AtomicReference<AcquireStartSessionAdmissionProtectionRequest> receivedRequest;

  @AfterEach
  void stopTransport() throws Exception {
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
  void receivesCanonicalAccountEvidenceAboveFourMiBOverExactSocketMtls() throws Exception {
    Fixture fixture = fixture(3_300_000);
    assertThat(fixture.evidence().canonicalBytes().length).isGreaterThan(4 * 1024 * 1024);
    startPkiAndServer(fixture, "account-server");
    client = newClient();
    client.init();

    AccountStartSessionAdmissionProtectionEvidence observed = client.acquire(fixture.request());

    assertThat(observed.canonicalBytes()).containsExactly(fixture.evidence().canonicalBytes());
    assertThat(observed.request().canonicalBytes())
        .containsExactly(fixture.request().canonicalBytes());
    assertThat(rpcCalls).hasValue(1);
    assertThat(receivedPeer.get())
        .isNotNull()
        .extracting(GrpcPeerIdentity::uri)
        .isEqualTo("spiffe://firemud/ns/" + NAMESPACE + "/sa/game-session-service");
    assertThat(receivedRequest.get())
        .isEqualTo(
            AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec.toRequest(
                fixture.request()));
  }

  @Test
  void rejectsSameCaServerWithWrongWorkloadBeforeSendingRequest() throws Exception {
    Fixture fixture = fixture(64);
    startPkiAndServer(fixture, "wrong-server");
    client = newClient();
    client.init();

    Throwable failure = catchThrowable(() -> client.acquire(fixture.request()));

    assertThat(failure)
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            thrown ->
                assertThat(((StatusRuntimeException) thrown).getStatus().getCode())
                    .isEqualTo(Status.Code.UNAUTHENTICATED));
    assertThat(rpcCalls).hasValue(0);
    assertThat(receivedRequest.get()).isNull();
  }

  @Test
  void refusesAValidOriginalRequestForAnotherNamespaceBeforeTransport() throws Exception {
    Fixture serverFixture = fixture(64);
    Fixture foreignFixture = fixture(64, "another");
    startPkiAndServer(serverFixture, "account-server");
    client = newClient();
    client.init();

    Throwable failure = catchThrowable(() -> client.acquire(foreignFixture.request()));

    assertThat(failure)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("outside this Game Session namespace");
    assertThat(rpcCalls).hasValue(0);
    assertThat(receivedRequest.get()).isNull();
  }

  private void startPkiAndServer(Fixture fixture, String serverAlias) throws Exception {
    pki =
        LoopbackMtlsTestPki.create(
            directory,
            "Admission protection acquisition client test CA",
            new LoopbackMtlsTestPki.IdentitySpec(
                "account-server", uri(NAMESPACE, "account-service"), true),
            new LoopbackMtlsTestPki.IdentitySpec(
                "wrong-server", uri(NAMESPACE, "world-management-service"), true),
            new LoopbackMtlsTestPki.IdentitySpec(
                "game-session", uri(NAMESPACE, "game-session-service"), false),
            new LoopbackMtlsTestPki.IdentitySpec(
                "other-namespace", uri("another", "game-session-service"), false));
    rpcCalls = new AtomicInteger();
    receivedPeer = new AtomicReference<>();
    receivedRequest = new AtomicReference<>();
    var service =
        new AccountStartSessionAdmissionProtectionServiceGrpc
            .AccountStartSessionAdmissionProtectionServiceImplBase() {
          @Override
          public void acquireStartSessionAdmissionProtection(
              AcquireStartSessionAdmissionProtectionRequest request,
              StreamObserver<AcquireStartSessionAdmissionProtectionResponse> observer) {
            rpcCalls.incrementAndGet();
            receivedPeer.set(GrpcPeerIdentity.current());
            receivedRequest.set(request);
            observer.onNext(
                AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec.toResponse(
                    fixture.request(), fixture.evidence()));
            observer.onCompleted();
          }
        };
    var identity = pki.identity(serverAlias);
    server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .maxInboundMessageSize(
                AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec.MAX_REQUEST_WIRE_BYTES)
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(identity.privateKey(), identity.certificate()))
                    .trustManager(pki.caCertificate())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(ServerInterceptors.intercept(service, new GrpcPeerIdentityInterceptor()))
            .build()
            .start();
  }

  private GrpcAccountStartSessionAdmissionProtectionClient newClient() throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setAccountService("localhost:" + server.getPort());
    CommonGrpcClientProperties tls = pki.clientProperties(directory, pki.identity("game-session"));
    return new GrpcAccountStartSessionAdmissionProtectionClient(
        endpoints, tls, new GrpcChannelFactory(), NAMESPACE);
  }

  private static Fixture fixture(int sourceEvidenceBytes) throws Exception {
    return fixture(sourceEvidenceBytes, NAMESPACE);
  }

  private static Fixture fixture(int sourceEvidenceBytes, String namespace) throws Exception {
    UUID tenant = uuid("9f8f06b4-36e5-4d11-9c2a-5adfd7f41531");
    UUID actor = uuid("a4f5f4eb-8243-4d42-903a-33495456a622");
    var pre =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            "mtls-large-response",
            actor,
            new StartSessionOperatorAction(
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
                new StartSessionOperatorAction.Scope(tenant, namespace),
                new StartSessionOperatorAction.Target(
                    91L, uuid("36aa9ce5-0ebc-4c14-9f6b-d160edc6059a")),
                StartSessionOperatorAction.ExpectedVersion.ABSENT,
                new StartSessionOperatorAction.Mutation(
                    StartSessionOperatorAction.ClientIp.absent()),
                "canonical original StartSession admission attempt"));
    var tuple =
        StartSessionPostAuthorizationExecutionTuple.createHuman(
            pre,
            "spiffe://firemud/ns/" + namespace + "/sa/logging-admin-service",
            "arfp/v1/test-key/" + "b".repeat(64),
            uuid("7c005b65-fcb1-4ac9-a714-f3d0f449edcf"),
            19L,
            authorityBundle(tenant, actor, namespace, pre),
            new StartSessionAuthorityEvidenceBundle.BundleReference(
                StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
                "17",
                "23",
                "18446744073709551615"));
    var hold =
        new WorldCanonicalInitialAdmissionHold.HoldIdentity(
            new WorldCanonicalInitialAdmissionHold.Request(
                namespace,
                tenant,
                "earth",
                uuid("3916f423-2870-426a-a8aa-5e3f97412613"),
                uuid("54e6094e-11bb-4f4c-93ee-a52f715b530b"),
                "SHARED",
                uuid("a55b2e10-9a24-4adb-adb8-6fc66fe3b8e9"),
                uuid("d7280ec0-5979-4b62-8418-e9f139415184"),
                3L,
                tuple.controlPlaneRequestId(),
                "c".repeat(64),
                WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER,
                12L,
                null),
            uuid("0db7344a-1e67-4b95-905a-83dc9c472f0c"),
            uuid("52a14272-f9e4-4f67-97c9-62247b5fbcc1"));
    var request =
        AccountStartSessionAdmissionProtectionRequest.create(
            tuple.canonicalBytes(),
            StartSessionAccountRedemptionProjection.fromOriginalTuple(tuple),
            uuid("f1a3ab1e-9147-4667-b6c4-6eb5119e8a31"),
            uuid("ec13cc04-ec15-4eb8-a018-c2c5e8da65f8"),
            7L,
            Instant.parse("2026-10-09T10:20:30.456789Z"),
            uuid("47b3be7f-a32f-4e19-8916-8c3b8da07a82"),
            11L,
            hold);
    byte[] capture =
        canonical(
            Map.of(
                "schema",
                AccountStartSessionAdmissionProtectionEvidence.SOURCE_CAPTURE_REFERENCE_SCHEMA,
                "controlPlaneRequestId",
                tuple.controlPlaneRequestId(),
                "capturedAt",
                "2026-10-09T10:11:12.123Z",
                "bundleReference",
                Map.of(
                    "bundleVersion",
                    StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
                    "sourceVersion",
                    "17",
                    "sourceFence",
                    "23",
                    "linearization",
                    "18446744073709551615"),
                "snapshotSha256",
                "d".repeat(64)));
    byte[] largeSourceEvidence = new byte[sourceEvidenceBytes];
    for (int index = 0; index < largeSourceEvidence.length; index++) {
      largeSourceEvidence[index] = (byte) (index * 31 + 7);
    }
    var evidence =
        AccountStartSessionAdmissionProtectionEvidence.create(
            request,
            uuid("6b763f1d-c5bc-4080-b499-d29debc0a7b8"),
            13L,
            capture,
            sha256(capture),
            List.of(
                new SourceEvidence(
                    SourceKind.ACCOUNT,
                    tenant.toString(),
                    "2",
                    "17",
                    null,
                    null,
                    "account-source".getBytes(StandardCharsets.UTF_8)),
                new SourceEvidence(
                    SourceKind.ISSUER,
                    "https://issuer.example",
                    null,
                    "9",
                    null,
                    null,
                    largeSourceEvidence)));
    return new Fixture(request, evidence);
  }

  private static byte[] authorityBundle(
      UUID tenantId,
      UUID actor,
      String namespace,
      StartSessionPreAuthorizationReservationTuple tuple)
      throws Exception {
    String tenant = tenantId.toString();
    Map<String, Object> projection =
        Map.of(
            "sourceType", "ACCOUNT",
            "sourceEvidenceId", "sha256:" + "a".repeat(64),
            "sourceEvidenceVersion", "17",
            "projectionStatus", "CURRENT",
            "evaluatedAt", "2026-10-09T00:00:00Z",
            "expiresAt", "2026-10-09T00:05:00Z");
    Map<String, Object> operation =
        Map.of(
            "issuanceOperationId", "f5d044bd-7e5f-4e2d-9859-9025cbdcc60f",
            "controlPlaneRequestId", tuple.controlPlaneRequestId(),
            "actionFamilyRequestIdentity",
                Map.of(
                    "requestIdentityKind",
                    "controlPlaneRequestId",
                    "requestId",
                    tuple.controlPlaneRequestId()),
            "mutationDigest", tuple.mutationDigest());
    Map<String, Object> authority =
        Map.of(
            "issuerAuthGeneration", 1L,
            "accountAuthorityGeneration", 2L,
            "tenantAuthorityGeneration", Map.of(tenant, 3L),
            "membershipAuthorityGeneration", Map.of(tenant, 4L),
            "privateRealmGrantVersions", List.of());
    Map<String, Object> issuance =
        Map.of(
            "evidenceType", StartSessionAuthorityEvidenceBundle.HUMAN_EVIDENCE_TYPE,
            "actorAccountId", actor.toString(),
            "controlUiTokenJti", "a681bba7-c215-4cf1-a35b-14348912cbdc",
            "role", "tenantAdmin",
            "accountGeneration", "2",
            "tenantGeneration", "3");
    Map<String, Object> bundle =
        Map.of(
            "bundleVersion",
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "authorityScope",
            Map.of(
                "scope", Map.of("tenantId", tenant, "targetNamespace", namespace),
                "actionFamily", tuple.actionFamily(),
                "applicableAccountId", actor.toString(),
                "applicableTenantId", tenant),
            "accountProjectionEvidence",
            projection,
            "issuanceOperationIdentity",
            operation,
            "issuanceKind",
            "human_operator",
            "authorityTuple",
            authority,
            "membershipVersion",
            Map.of(tenant, 5L),
            "issuanceFence",
            "23",
            "issuanceEvidence",
            issuance);
    return canonical(bundle);
  }

  private static byte[] canonical(Object value) throws Exception {
    return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
  }

  private static String sha256(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private static String uri(String namespace, String service) {
    return "spiffe://firemud/ns/" + namespace + "/sa/" + service;
  }

  private record Fixture(
      AccountStartSessionAdmissionProtectionRequest request,
      AccountStartSessionAdmissionProtectionEvidence evidence) {}
}
