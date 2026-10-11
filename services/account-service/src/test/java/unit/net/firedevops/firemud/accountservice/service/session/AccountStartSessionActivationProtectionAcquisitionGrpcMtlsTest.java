package unit.net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.account.v1.AccountStartSessionActivationProtectionAcquisitionServiceGrpc;
import net.firedevops.firemud.account.v1.AcquireOriginalStartSessionActivationProtectionRequest;
import net.firedevops.firemud.account.v1.AcquireOriginalStartSessionActivationProtectionResponse;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionActivationProtectionAcquisitionGrpcService;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionActivationProtectionAcquisitionService;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionAcquisitionGrpcCodec;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionAcquisitionInput;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionEvidence;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionRequest;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionEvidence;
import net.firedevops.firemud.common.account.startsession.GrpcAccountStartSessionActivationProtectionAcquisitionClient;
import net.firedevops.firemud.common.account.startsession.StartSessionAccountRedemptionProjection;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.authoring.WorldDraftTerminalReadEvidence;
import net.firedevops.firemud.common.authoring.WorldDraftTerminalReadGrpcCodec;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence.CurrentGameInstanceStatus;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionLaunchReadEvidence;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.RoomTemplateRef;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceActivation;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.common.world.WorldDraftStartLocationEvidence;
import net.firedevops.firemud.common.world.WorldOriginalStartSessionActivationTerminalEvidence;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.test.TlsTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Physical loopback mTLS transport-only proof for the unregistered Account activation-protection
 * receiver. The mocked Account owner proves no Account authority, SQL, or World activation.
 */
class AccountStartSessionActivationProtectionAcquisitionGrpcMtlsTest {
  private static final String NAMESPACE = "world-runtime";
  private static final String WORLD_URI =
      "spiffe://firemud/ns/world-runtime/sa/world-management-service";
  private static final UUID PROTECTION_ID = uuid("6b763f1d-c5bc-4080-b499-d29debc0a7b8");
  private static final long PROTECTION_FENCE = 23L;

  @TempDir static Path temporaryDirectory;

  private static TestPki pki;

  @BeforeAll
  static void generateTrustedWorkloadCertificates() throws Exception {
    pki = TestPki.create(temporaryDirectory);
  }

  @AfterEach
  void clearThreadState() {
    SessionContext.clear();
    TransactionSynchronizationManager.clear();
  }

  @Test
  void exactSameNamespaceWorldPeerAcquiresTheExactFirstReceiptWithTheCommonClient()
      throws Exception {
    var fixture = Fixtures.create();
    var owner = mock(AccountStartSessionActivationProtectionAcquisitionService.class);
    when(owner.acquire(any())).thenReturn(fixture.accountEvidence());
    try (TransportServer server = startServer(owner, false);
        var client = newClient(server.port(), pki.worldClient())) {
      client.init();

      var actual = client.acquire(fixture.input());

      assertThat(actual.canonicalBytes())
          .containsExactly(fixture.accountEvidence().canonicalBytes());
      assertThat(actual.request().canonicalBytes())
          .containsExactly(fixture.accountEvidence().request().canonicalBytes());
      assertThat(server.handlerCalls()).hasValue(1);
      ArgumentCaptor<AccountStartSessionActivationProtectionAcquisitionService.AcquisitionRequest>
          captured =
              ArgumentCaptor.forClass(
                  AccountStartSessionActivationProtectionAcquisitionService.AcquisitionRequest
                      .class);
      verify(owner).acquire(captured.capture());
      assertThat(captured.getValue().originalPostAuthorizationTuple())
          .containsExactly(fixture.input().originalPostAuthorizationTuple());
      assertThat(captured.getValue().gameSessionOwnerMutationId())
          .isEqualTo(fixture.input().gameSessionOwnerMutationId());
      assertThat(captured.getValue().gameSessionOwnerAttemptId())
          .isEqualTo(fixture.input().gameSessionOwnerAttemptId());
      assertThat(captured.getValue().gameSessionOwnerFence())
          .isEqualTo(fixture.input().gameSessionOwnerFence());
      assertThat(captured.getValue().worldActivationRequest().canonicalRequestBytes())
          .containsExactly(fixture.input().worldActivationRequest().canonicalRequestBytes());
      assertThat(captured.getValue().worldActivationRequest().preparingEvidenceBytes())
          .containsExactly(fixture.input().worldActivationRequest().preparingEvidenceBytes());
    }
  }

  @Test
  void trustedWrongServiceAndNamespacePeersAreDeniedBeforeOwnerInteraction() throws Exception {
    var fixture = Fixtures.create();
    for (TestIdentity identity : List.of(pki.wrongServiceClient(), pki.otherNamespaceClient())) {
      var owner = mock(AccountStartSessionActivationProtectionAcquisitionService.class);
      try (TransportServer server = startServer(owner, false);
          var client = newClient(server.port(), identity)) {
        client.init();

        assertThatThrownBy(() -> client.acquire(fixture.input()))
            .isInstanceOf(StatusRuntimeException.class)
            .satisfies(
                failure ->
                    assertThat(Status.fromThrowable(failure).getCode())
                        .as(identity.alias())
                        .isEqualTo(Status.Code.PERMISSION_DENIED));

        assertThat(server.handlerCalls()).as(identity.alias()).hasValue(1);
        verifyNoInteractions(owner);
      }
    }
  }

  @Test
  void missingClientCertificateIsRejectedByTlsBeforeHandlerOrOwner() throws Exception {
    var owner = mock(AccountStartSessionActivationProtectionAcquisitionService.class);
    var fixture = Fixtures.create();
    try (TransportServer server = startServer(owner, false);
        ClientTransport client = newClientWithoutIdentity(server.port())) {
      var request =
          AccountStartSessionActivationProtectionAcquisitionGrpcCodec.toRequest(
              fixture.input(), NAMESPACE);
      assertThatThrownBy(() -> client.acquire(request))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(TlsTestSupport.isTlsHandshakeRejection(failure))
                      .as(
                          "missing client certificate must fail during TLS negotiation: %s",
                          failure)
                      .isTrue());

      assertThat(server.handlerCalls()).hasValue(0);
      verifyNoInteractions(owner);
    }
  }

  @Test
  void authenticatedEndUserContextIsDeniedAtHandlerBeforeOwnerInteraction() throws Exception {
    var owner = mock(AccountStartSessionActivationProtectionAcquisitionService.class);
    var fixture = Fixtures.create();
    try (TransportServer server = startServer(owner, true);
        var client = newClient(server.port(), pki.worldClient())) {
      client.init();

      assertThatThrownBy(() -> client.acquire(fixture.input()))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.PERMISSION_DENIED));

      assertThat(server.handlerCalls()).hasValue(1);
      verifyNoInteractions(owner);
    }
  }

  private static TransportServer startServer(
      AccountStartSessionActivationProtectionAcquisitionService owner, boolean injectEndUserContext)
      throws Exception {
    AtomicInteger handlerCalls = new AtomicInteger();
    var service =
        new AccountStartSessionActivationProtectionAcquisitionGrpcService(owner, NAMESPACE);
    ServerInterceptor countHandlerCalls =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call,
              Metadata metadata,
              ServerCallHandler<ReqT, RespT> next) {
            handlerCalls.incrementAndGet();
            return next.startCall(call, metadata);
          }
        };
    ServerInterceptor endUserContext =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call,
              Metadata metadata,
              ServerCallHandler<ReqT, RespT> next) {
            var delegate = next.startCall(call, metadata);
            if (!injectEndUserContext) return delegate;
            return new io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener<>(
                delegate) {
              @Override
              public void onHalfClose() {
                SessionContext.setContext("123", List.of(), Map.of());
                try {
                  super.onHalfClose();
                } finally {
                  SessionContext.clear();
                }
              }
            };
          }
        };
    Server server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(
                            pki.accountServer().privateKey(), pki.accountServer().certificate()))
                    .trustManager(pki.caCertificate())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(
                ServerInterceptors.intercept(
                    service,
                    countHandlerCalls,
                    new net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor(),
                    endUserContext))
            .build()
            .start();
    return new TransportServer(server, handlerCalls);
  }

  private static GrpcAccountStartSessionActivationProtectionAcquisitionClient newClient(
      int port, TestIdentity identity) throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setAccountService("127.0.0.1:" + port);
    return new GrpcAccountStartSessionActivationProtectionAcquisitionClient(
        endpoints,
        pki.clientProperties(temporaryDirectory, identity),
        new GrpcChannelFactory(),
        NAMESPACE);
  }

  private static ClientTransport newClientWithoutIdentity(int port) throws Exception {
    var tls = new CommonGrpcClientProperties();
    tls.setPlaintext(false);
    tls.setCaCert(pki.caCertificatePem().toString());
    ManagedChannel channel =
        new GrpcChannelFactory().buildChannel("127.0.0.1:" + port, 6565, tls, false);
    return new ClientTransport(channel);
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private record TransportServer(Server server, AtomicInteger handlerCalls)
      implements AutoCloseable {
    private int port() {
      return server.getPort();
    }

    @Override
    public void close() throws InterruptedException {
      server.shutdownNow();
      if (!server.awaitTermination(2, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Loopback Account gRPC server did not terminate");
      }
    }
  }

  private record ClientTransport(ManagedChannel channel) implements AutoCloseable {
    private AcquireOriginalStartSessionActivationProtectionResponse acquire(
        AcquireOriginalStartSessionActivationProtectionRequest request) {
      return AccountStartSessionActivationProtectionAcquisitionServiceGrpc.newBlockingStub(channel)
          .withDeadlineAfter(3, TimeUnit.SECONDS)
          .acquireOriginalStartSessionActivationProtection(request);
    }

    @Override
    public void close() throws InterruptedException {
      if (!channel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Loopback Account gRPC client did not terminate");
      }
    }
  }

  /** Canonical structured values for transport testing; owners and authorities remain mocked. */
  static final class Fixtures {
    private static final UUID TENANT = uuid("9f8f06b4-36e5-4d11-9c2a-5adfd7f41531");
    private static final UUID ACTOR = uuid("a4f5f4eb-8243-4d42-903a-33495456a622");
    private static final UUID TARGET_OWNER = uuid("36aa9ce5-0ebc-4c14-9f6b-d160edc6059a");
    private static final UUID GAME_INSTANCE = uuid("12121212-1212-4212-8212-121212121212");
    private static final UUID PLAYABLE_NAMESPACE = uuid("23232323-2323-4232-8232-232323232323");
    private static final UUID REALM = uuid("34343434-3434-4343-8343-343434343434");
    private static final UUID VERSION = uuid("34343434-3434-4343-8343-343434343435");
    private static final UUID SOURCE_OPERATION = uuid("34343434-3434-4343-8343-343434343436");
    private static final UUID ROOM_TEMPLATE = uuid("34343434-3434-4343-8343-343434343437");
    private static final UUID PARTICIPATION = uuid("47b3be7f-a32f-4e19-8916-8c3b8da07a82");
    private static final UUID MUTATION = uuid("f1a3ab1e-9147-4667-b6c4-6eb5119e8a31");
    private static final UUID ATTEMPT = uuid("ec13cc04-ec15-4eb8-a018-c2c5e8da65f8");
    private static final UUID ACTIVATION = uuid("c0f491b5-7acb-4eb4-943b-29e387401a55");
    private static final UUID GAME_SESSION_READ = uuid("7c005b65-fcb1-4ac9-a714-f3d0f449edcf");
    private static final UUID WORLD_READ = uuid("b20d71ee-0c8e-4672-8abf-ff08b3d7153e");
    private static final UUID START_LOCATION_READ = uuid("0c69ec92-9e37-4f1a-8fc2-956d1093e2b2");
    private static final UUID SELECTOR_OPERATION_ID = uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID SELECTOR_REQUEST_ID = uuid("44444444-4444-4444-8444-444444444444");
    private static final UUID SELECTOR_COMMIT_ID = uuid("55555555-5555-4555-8555-555555555555");
    private static final UUID SELECTOR_FENCE_ID = uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final UUID SELECTOR_ACTOR_ID = uuid("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
    private static final UUID SELECTOR_READ_ID = uuid("33333333-3333-4333-8333-333333333333");
    private static final UUID SELECTOR_VERSION_IDENTITY_OPERATION =
        uuid("dddddddd-dddd-4ddd-8ddd-dddddddddddd");
    private static final UUID SELECTOR_INTAKE_ID = SELECTOR_FENCE_ID;
    private static final UUID SELECTOR_INTAKE_OPERATION_ID = SELECTOR_ACTOR_ID;
    private static final UUID SELECTOR_PUBLICATION_FENCE =
        uuid("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee");
    private static final UUID SELECTOR_REGION_ID = uuid("88888888-8888-4888-8888-888888888888");
    private static final UUID SELECTOR_ZONE_ID = uuid("99999999-9999-4999-8999-999999999999");
    private static final UUID SELECTOR_REGION_REVISION_ID =
        uuid("12345678-1234-4234-8234-123456789001");
    private static final UUID SELECTOR_ZONE_REVISION_ID =
        uuid("12345678-1234-4234-8234-123456789002");
    private static final UUID SELECTOR_ROOM_REVISION_ID =
        uuid("12345678-1234-4234-8234-123456789003");
    private static final String CONTROL_REQUEST = "activation-transport-original-request";
    private static final String WORLD_SLUG = "activation-transport-world";
    private static final Instant ORIGINAL_LEASE_EXPIRY =
        Instant.parse("2026-10-09T10:20:30.456789Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private Fixtures() {}

    static TransportFixture create() {
      var world = worldFixture();
      var tuple = originalTuple(world);
      var input =
          new AccountStartSessionActivationProtectionAcquisitionInput(
              tuple.canonicalBytes(), MUTATION, ATTEMPT, 21L, world.activation());
      var gameSessionReadRequest =
          new CanonicalGameInstanceLaunchAssociationReadEvidence.Request(
              GAME_SESSION_READ,
              NAMESPACE,
              TENANT,
              WORLD_SLUG,
              GAME_INSTANCE,
              CONTROL_REQUEST,
              world.descriptor().launchDescriptorId(),
              world.descriptor().requestDigest(),
              world.descriptor().resultDigest(),
              world.release().evidenceDigest());
      var gameSessionReadResult =
          new CanonicalGameInstanceLaunchAssociationReadEvidence.Result(
              gameSessionReadRequest,
              PLAYABLE_NAMESPACE,
              RealmEntryPolicy.StateScope.SHARED,
              true,
              REALM,
              17L,
              CurrentGameInstanceStatus.STARTING,
              7L,
              world.binding());
      var launchReadRequest =
          new OriginalStartSessionLaunchReadEvidence.Request(
              gameSessionReadRequest, tuple.canonicalBytes());
      var launchReadResult =
          new OriginalStartSessionLaunchReadEvidence.Result(
              launchReadRequest, gameSessionReadResult, tuple.canonicalBytes());
      var accountRequest =
          AccountStartSessionActivationProtectionRequest.create(
              tuple.canonicalBytes(),
              StartSessionAccountRedemptionProjection.fromOriginalTuple(tuple),
              MUTATION,
              ATTEMPT,
              21L,
              ORIGINAL_LEASE_EXPIRY,
              PARTICIPATION,
              22L,
              launchReadResult,
              world.activation());
      byte[] capture = captureReference(tuple);
      var accountEvidence =
          AccountStartSessionActivationProtectionEvidence.create(
              accountRequest,
              PROTECTION_ID,
              PROTECTION_FENCE,
              Instant.parse("2026-10-09T10:11:12.123000Z"),
              capture,
              sha256(capture),
              List.of(
                  new SourceEvidence(
                      SourceKind.ACCOUNT,
                      TENANT.toString(),
                      "2",
                      "17",
                      null,
                      null,
                      "original Account source evidence".getBytes(StandardCharsets.UTF_8))));
      var active =
          new WorldCanonicalInstanceLifecycleEvidence(
              world.preparing().request(),
              world.preparing().launchBinding(),
              world.preparing().startLocation(),
              world.preparing().runtimeRoomInstanceId(),
              "ACTIVE",
              world.preparing().lifecycleEpoch() + 1L,
              world.preparing().rowVersion() + 1L,
              world.preparing().captureId(),
              world.preparing().graphSha256(),
              world.preparing().preparationInputDigest(),
              world.preparing().operationalRegionAssignments());
      var result =
          new WorldCanonicalInstanceActivation.Result(
              world.activation(), WorldCanonicalInstanceActivation.Outcome.COMMITTED, null, active);
      var terminal =
          WorldOriginalStartSessionActivationTerminalEvidence.create(accountEvidence, result, 29L);
      return new TransportFixture(input, accountEvidence, terminal);
    }

    private static WorldFixture worldFixture() {
      WorldPublishedStartLocationEvidence selected = selectedWorldStartLocation();
      var selectedRequest = selected.request();
      var descriptorRequest =
          new AuthoredWorldLaunchDescriptorEvidence.Request(
              NAMESPACE,
              CONTROL_REQUEST,
              TENANT,
              WORLD_SLUG,
              SOURCE_OPERATION,
              "sha256:" + "1".repeat(64),
              91L,
              false,
              null,
              false,
              null,
              false,
              null,
              false,
              null);
      var descriptor =
          AuthoredWorldLaunchDescriptorEvidence.create(
              descriptorRequest,
              "activation-transport-descriptor",
              42L,
              false,
              null,
              "{}",
              "generation-revision",
              9L,
              7L,
              "activation-transport-release",
              false,
              null);
      var participants =
          AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder().stream()
              .map(
                  owner ->
                      new AuthoredWorldReleaseAttestationEvidence.Participant(
                          owner,
                          Long.toString(descriptor.versionId()),
                          false,
                          null,
                          selectedRequest.appliedCommitId(),
                          selectedRequest.contentDigest(),
                          AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                              owner,
                              AuthoredWorldReleaseAttestationEvidence.SELECTOR_SCHEMA_VERSION),
                          "GAME_LOGIC".equals(owner),
                          "GAME_LOGIC".equals(owner) ? "sha256:" + "2".repeat(64) : null))
              .toList();
      var release =
          AuthoredWorldReleaseAttestationEvidence.create(
              NAMESPACE,
              descriptor.resultDigest(),
              TENANT,
              selectedRequest.canonicalVersionId(),
              WORLD_SLUG,
              SOURCE_OPERATION,
              "sha256:" + "1".repeat(64),
              descriptor.launchDescriptorId(),
              descriptor.publishedReleaseBundleRef(),
              descriptor.versionStateEpoch(),
              selectedRequest.publishWorkflowId(),
              selectedRequest.appliedCommitId(),
              participants,
              "sha256:" + "3".repeat(64),
              1,
              List.of(),
              List.of(),
              List.of(),
              descriptor.generationConfigRevision(),
              selected);
      var binding = new CompleteLaunchBindingEvidence(descriptor, release);
      var selectorReceipt =
          WorldDraftStartLocationEvidence.fromStored(selected.selectorReceiptBytes());
      var startLocation = selectorReceipt.startLocation();
      var lifecycleRequest =
          new WorldCanonicalInstanceLifecycleEvidence.Request(
              WorldCanonicalInstanceLifecycleEvidence.Request.SCHEMA_VERSION,
              START_LOCATION_READ,
              NAMESPACE,
              TENANT,
              WORLD_SLUG,
              GAME_INSTANCE,
              PLAYABLE_NAMESPACE,
              "SHARED",
              true,
              CONTROL_REQUEST,
              VERSION,
              descriptor.requestDigest(),
              descriptor.resultDigest(),
              release.evidenceDigest());
      var preparing =
          new WorldCanonicalInstanceLifecycleEvidence(
              lifecycleRequest,
              binding,
              startLocation,
              1042L,
              "PREPARING",
              3L,
              0L,
              uuid("bcbcbcbc-abab-4bcb-8bcb-bcbcbcbcbcbc"),
              selectorReceipt.graphDigest().substring("sha256:".length()),
              "sha256:" + "5".repeat(64),
              Map.of(
                  uuid("11111111-1111-4111-8111-111111111111"),
                  uuid("22222222-2222-4222-8222-222222222222")));
      var activation = new WorldCanonicalInstanceActivation.Request(ACTIVATION, preparing);
      return new WorldFixture(descriptor, release, binding, preparing, activation);
    }

    /** Complete Account-bound World APPLIED-v2 selection carrier with the canonical ROOM row. */
    private static WorldPublishedStartLocationEvidence selectedWorldStartLocation() {
      try {
        DraftCommitBinding draft = selectedWorldDraft();
        byte[] accountBytes = selectedAccountBinding(draft);
        var terminalRequest =
            new WorldDraftTerminalReadEvidence.Request(
                WorldDraftTerminalReadEvidence.Request.SCHEMA_VERSION,
                NAMESPACE,
                SELECTOR_READ_ID,
                accountBytes);
        var readback = selectedWorldReadback(terminalRequest, draft);
        List<WorldPublishedStartLocationEvidence.OwnedAffectedTuple> tuples =
            draft.affectedUnits(DraftCommitBinding.Owner.WORLD_MANAGEMENT).stream()
                .map(
                    unit ->
                        new WorldPublishedStartLocationEvidence.OwnedAffectedTuple(
                            unit.owner().name(),
                            unit.aggregateType(),
                            unit.aggregateId(),
                            unit.scopeType(),
                            unit.scopeId(),
                            unit.expectedEpoch()))
                .toList();
        var request =
            new WorldPublishedStartLocationEvidence.Request(
                NAMESPACE,
                TENANT,
                VERSION,
                SELECTOR_INTAKE_ID,
                SELECTOR_PUBLICATION_FENCE,
                "activation-transport-publication-request",
                "a".repeat(64),
                5L,
                "activation-transport-publish-workflow",
                draft.commitId().toString(),
                "b".repeat(64),
                3,
                tuples);
        var applied = JSON.readTree(readback.result());
        byte[] receipt =
            Base64.getDecoder().decode(applied.get("startLocationReceiptBase64").textValue());
        return new WorldPublishedStartLocationEvidence(
            request, receipt, readback.fullBinding(), readback.result());
      } catch (Exception invalidFixture) {
        throw new IllegalStateException(
            "Canonical selected World transport fixture is invalid", invalidFixture);
      }
    }

    private static DraftCommitBinding selectedWorldDraft() throws Exception {
      String declaration =
          JSON.writeValueAsString(
              Map.of(
                  "tenantId",
                  TENANT.toString(),
                  "versionId",
                  VERSION.toString(),
                  "startLocation",
                  Map.of(
                      "tenantId", TENANT.toString(),
                      "versionId", VERSION.toString(),
                      "roomTemplateId", ROOM_TEMPLATE.toString()),
                  "familyCounts",
                  List.of(
                      Map.of("family", "WORLD_DESIGN_AGGREGATE_TYPE_REGION", "count", 1),
                      Map.of("family", "WORLD_DESIGN_AGGREGATE_TYPE_ZONE", "count", 1),
                      Map.of("family", "WORLD_DESIGN_AGGREGATE_TYPE_ROOM", "count", 1),
                      Map.of("family", "WORLD_DESIGN_AGGREGATE_TYPE_ROOM_EXIT", "count", 0),
                      Map.of("family", "WORLD_DESIGN_AGGREGATE_TYPE_GENERATION_RULE", "count", 0),
                      Map.of(
                          "family",
                          "WORLD_DESIGN_AGGREGATE_TYPE_WORLD_ENTITY_SPAWN_BINDING",
                          "count",
                          0))));
      return DraftCommitBinding.create(
          new TargetProof(TENANT, VERSION, 19L, "tenant-key", 42L, "tenant-key", "NEW_GAME_ROW"),
          SELECTOR_REQUEST_ID,
          SELECTOR_COMMIT_ID,
          "base-1",
          List.of(
              new RevisionPayload(
                  "0",
                  SELECTOR_REGION_REVISION_ID,
                  DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                  worldRevisionPayload(
                      SELECTOR_REGION_REVISION_ID,
                      "WORLD_DESIGN_AGGREGATE_TYPE_REGION",
                      SELECTOR_REGION_ID,
                      declaration)),
              new RevisionPayload(
                  "1",
                  SELECTOR_ZONE_REVISION_ID,
                  DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                  worldRevisionPayload(
                      SELECTOR_ZONE_REVISION_ID,
                      "WORLD_DESIGN_AGGREGATE_TYPE_ZONE",
                      SELECTOR_ZONE_ID,
                      null)),
              new RevisionPayload(
                  "2",
                  SELECTOR_ROOM_REVISION_ID,
                  DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                  worldRevisionPayload(
                      SELECTOR_ROOM_REVISION_ID,
                      "WORLD_DESIGN_AGGREGATE_TYPE_ROOM",
                      ROOM_TEMPLATE,
                      null))),
          List.of(
                  affected("REGION", SELECTOR_REGION_ID, "REGION_SUBTREE", SELECTOR_REGION_ID),
                  affected("ZONE", SELECTOR_ZONE_ID, "REGION_SUBTREE", SELECTOR_REGION_ID),
                  affected("ROOM", ROOM_TEMPLATE, "ZONE_SUBTREE", SELECTOR_ZONE_ID))
              .stream()
              .flatMap(List::stream)
              .toList());
    }

    private static String worldRevisionPayload(
        UUID revisionId, String family, UUID templateId, String declaration) throws Exception {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("logicalRevisionId", revisionId.toString());
      payload.put("commitId", SELECTOR_COMMIT_ID.toString());
      payload.put("aggregateType", family);
      payload.put("aggregateId", templateId.toString());
      if (declaration != null) payload.put("freshGraphDeclaration", JSON.readTree(declaration));
      return JSON.writeValueAsString(payload);
    }

    private static List<AffectedUnit> affected(
        String family, UUID templateId, String scopeType, UUID scopeId) {
      return List.of(
          new AffectedUnit(
              DraftCommitBinding.Owner.WORLD_MANAGEMENT,
              family,
              templateId.toString(),
              "AGGREGATE",
              templateId.toString(),
              "0"),
          new AffectedUnit(
              DraftCommitBinding.Owner.WORLD_MANAGEMENT,
              family,
              templateId.toString(),
              scopeType,
              scopeId.toString(),
              "0"));
    }

    private static byte[] selectedAccountBinding(DraftCommitBinding draft) {
      byte[] draftBytes = draft.canonicalBytes();
      return new DraftAuthorizationFenceBinding(
              SELECTOR_OPERATION_ID,
              SELECTOR_REQUEST_ID,
              SELECTOR_COMMIT_ID,
              SELECTOR_FENCE_ID,
              SELECTOR_ACTOR_ID,
              TENANT,
              VERSION,
              "base-1",
              "0",
              draftBytes,
              draftBytes,
              draft.digest(),
              List.of(
                  new SourceEvidence(
                      SourceKind.GLOBAL_ROLES,
                      SELECTOR_ACTOR_ID.toString(),
                      null,
                      "1",
                      null,
                      null,
                      new byte[] {4, 5})))
          .canonicalBytes();
    }

    private static DraftAuthorizationFenceBinding.OwnerReadback selectedWorldReadback(
        WorldDraftTerminalReadEvidence.Request request, DraftCommitBinding draft) throws Exception {
      var account = request.accountBinding();
      var operation = new ByteArrayOutputStream();
      var frames = new DataOutputStream(operation);
      frame(frames, "world-draft-terminal-operation/v1");
      for (UUID id :
          List.of(
              account.operationId(),
              account.requestId(),
              account.commitId(),
              account.fenceId(),
              account.tenantId(),
              account.versionId())) frame(frames, id.toString());
      frame(frames, draft.canonicalBytes());
      for (String value :
          List.of(
              request.targetNamespace(),
              account.tenantId().toString(),
              account.versionId().toString(),
              SELECTOR_VERSION_IDENTITY_OPERATION.toString(),
              Long.toString(draft.target().gameDesignVersionRowId()),
              SELECTOR_INTAKE_ID.toString(),
              SELECTOR_INTAKE_OPERATION_ID.toString(),
              "a".repeat(64),
              SOURCE_OPERATION.toString(),
              "b".repeat(64),
              "c".repeat(64))) frame(frames, value);
      frame(frames, "sha256:" + sha256(request.originalAccountBinding()));
      frame(frames, request.originalAccountBinding());

      byte[] graph = selectedGraphBytes(request, draft);
      String graphDigest = "sha256:" + sha256(graph);
      var receipt =
          WorldDraftStartLocationEvidence.create(
              request.targetNamespace(),
              account.operationId(),
              account.requestId(),
              account.commitId(),
              account.fenceId(),
              "sha256:" + sha256(request.originalAccountBinding()),
              draft.digest(),
              new RoomTemplateRef(TENANT, VERSION, ROOM_TEMPLATE),
              graphDigest);
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("schema", "world-draft-graph-applied/v2");
      result.put("status", "APPLIED");
      result.put(
          "operationBytesBase64", Base64.getEncoder().encodeToString(operation.toByteArray()));
      result.put("graphBytesBase64", Base64.getEncoder().encodeToString(graph));
      result.put("graphDigest", graphDigest);
      result.put(
          "startLocationReceiptBase64",
          Base64.getEncoder().encodeToString(receipt.canonicalBytes()));
      result.put("startLocationReceiptDigest", receipt.receiptDigest());
      result.put(
          "appliedEpochs",
          draft.affectedUnits(DraftCommitBinding.Owner.WORLD_MANAGEMENT).stream()
              .map(
                  unit ->
                      Map.of(
                          "aggregateType",
                          unit.aggregateType(),
                          "aggregateId",
                          unit.aggregateId(),
                          "scopeType",
                          unit.scopeType(),
                          "scopeId",
                          unit.scopeId(),
                          "expectedEpoch",
                          unit.expectedEpoch(),
                          "resultingEpoch",
                          "1"))
              .toList());
      var readback =
          new DraftAuthorizationFenceBinding.OwnerReadback(
              DraftAuthorizationFenceBinding.Owner.WORLD,
              DraftAuthorizationFenceBinding.Outcome.COMMITTED,
              account.operationId(),
              account.commitId(),
              account.fenceId(),
              account.inputDigest(),
              request.originalAccountBinding(),
              canonical(result));
      var response = WorldDraftTerminalReadGrpcCodec.toResponse(request, Optional.of(readback));
      return WorldDraftTerminalReadGrpcCodec.fromResponse(request, response)
          .ownerReadback()
          .orElseThrow();
    }

    private static byte[] selectedGraphBytes(
        WorldDraftTerminalReadEvidence.Request request, DraftCommitBinding draft) throws Exception {
      Map<String, Object> root = new LinkedHashMap<>();
      root.put("schemaVersion", "2");
      root.put("canonicalTenantId", draft.target().canonicalTenantId().toString());
      root.put("canonicalVersionId", draft.target().canonicalVersionId().toString());
      List<Map<String, Object>> rows = new ArrayList<>();
      int mappingId = 1;
      long privateRowKey = 101;
      for (RevisionPayload revision : draft.revisions()) {
        if (revision.owner() != DraftCommitBinding.Owner.WORLD_MANAGEMENT) continue;
        var mutation = JSON.readTree(revision.payload());
        var mapping = new LinkedHashMap<String, Object>();
        mapping.put("id", mappingId++);
        mapping.put("target_namespace", request.targetNamespace());
        mapping.put("canonical_tenant_id", draft.target().canonicalTenantId().toString());
        mapping.put("canonical_version_id", draft.target().canonicalVersionId().toString());
        mapping.put(
            "family",
            mutation.get("aggregateType").textValue().replace("WORLD_DESIGN_AGGREGATE_TYPE_", ""));
        mapping.put("template_id", mutation.get("aggregateId").textValue());
        mapping.put("private_row_key", privateRowKey++);
        mapping.put("tenant_id", 11);
        mapping.put("version_id", 19);
        mapping.put(
            "version_identity_operation_id", SELECTOR_VERSION_IDENTITY_OPERATION.toString());
        mapping.put("request_id", draft.requestId().toString());
        mapping.put("commit_id", draft.commitId().toString());
        mapping.put("revision_id", revision.revisionId().toString());
        mapping.put("revision_order", revision.revisionOrder());
        rows.add(Map.of("mapping", mapping, "content", Map.of()));
      }
      root.put("rows", rows);
      return JSON.writeValueAsBytes(root);
    }

    private static void frame(DataOutputStream output, String value) throws IOException {
      frame(output, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void frame(DataOutputStream output, byte[] value) throws IOException {
      output.writeInt(value.length);
      output.write(value);
    }

    private static StartSessionPostAuthorizationExecutionTuple originalTuple(WorldFixture world) {
      var preTuple =
          StartSessionPreAuthorizationReservationTuple.createHuman(
              CONTROL_REQUEST,
              ACTOR,
              new StartSessionOperatorAction(
                  StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
                  StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
                  new StartSessionOperatorAction.Scope(TENANT, NAMESPACE),
                  new StartSessionOperatorAction.Target(91L, TARGET_OWNER),
                  StartSessionOperatorAction.ExpectedVersion.ABSENT,
                  new StartSessionOperatorAction.Mutation(
                      StartSessionOperatorAction.ClientIp.absent()),
                  "canonical original StartSession activation transport attempt"));
      return StartSessionPostAuthorizationExecutionTuple.createHuman(
          preTuple,
          "spiffe://firemud/ns/" + NAMESPACE + "/sa/logging-admin-service",
          "arfp/v1/test-key/" + "b".repeat(64),
          uuid("efefefef-efef-4fef-8fef-efefefefefef"),
          19L,
          authorityBundle(preTuple),
          new StartSessionAuthorityEvidenceBundle.BundleReference(
              StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
              "17",
              "23",
              "18446744073709551615"));
    }

    private static byte[] authorityBundle(StartSessionPreAuthorizationReservationTuple tuple) {
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
              "tenantAuthorityGeneration", Map.of(TENANT.toString(), 3L),
              "membershipAuthorityGeneration", Map.of(TENANT.toString(), 4L),
              "privateRealmGrantVersions", List.of());
      Map<String, Object> issuanceEvidence =
          Map.of(
              "evidenceType", StartSessionAuthorityEvidenceBundle.HUMAN_EVIDENCE_TYPE,
              "actorAccountId", ACTOR.toString(),
              "controlUiTokenJti", "a681bba7-c215-4cf1-a35b-14348912cbdc",
              "role", "tenantAdmin",
              "accountGeneration", "2",
              "tenantGeneration", "3");
      return canonical(
          Map.of(
              "bundleVersion",
              StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
              "authorityScope",
              Map.of(
                  "scope", Map.of("tenantId", TENANT.toString(), "targetNamespace", NAMESPACE),
                  "actionFamily", tuple.actionFamily(),
                  "applicableAccountId", ACTOR.toString(),
                  "applicableTenantId", TENANT.toString()),
              "accountProjectionEvidence",
              projection,
              "issuanceOperationIdentity",
              operation,
              "issuanceKind",
              "human_operator",
              "authorityTuple",
              authority,
              "membershipVersion",
              Map.of(TENANT.toString(), 5L),
              "issuanceFence",
              "23",
              "issuanceEvidence",
              issuanceEvidence));
    }

    private static byte[] captureReference(StartSessionPostAuthorizationExecutionTuple tuple) {
      return canonical(
          Map.of(
              "schema",
              AccountStartSessionAdmissionProtectionEvidence.SOURCE_CAPTURE_REFERENCE_SCHEMA,
              "controlPlaneRequestId",
              tuple.controlPlaneRequestId(),
              "capturedAt",
              "2026-10-09T10:11:12.123Z",
              "bundleReference",
              Map.of(
                  "bundleVersion", StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
                  "sourceVersion", "17",
                  "sourceFence", "23",
                  "linearization", "18446744073709551615"),
              "snapshotSha256",
              "d".repeat(64)));
    }

    private static byte[] canonical(Object value) {
      try {
        return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
      } catch (Exception impossible) {
        throw new IllegalStateException(impossible);
      }
    }

    private static String sha256(byte[] value) {
      try {
        return java.util.HexFormat.of()
            .formatHex(MessageDigest.getInstance("SHA-256").digest(value));
      } catch (java.security.NoSuchAlgorithmException impossible) {
        throw new IllegalStateException(impossible);
      }
    }
  }

  record TransportFixture(
      AccountStartSessionActivationProtectionAcquisitionInput input,
      AccountStartSessionActivationProtectionEvidence accountEvidence,
      WorldOriginalStartSessionActivationTerminalEvidence terminal) {}

  private record WorldFixture(
      AuthoredWorldLaunchDescriptorEvidence descriptor,
      AuthoredWorldReleaseAttestationEvidence release,
      CompleteLaunchBindingEvidence binding,
      WorldCanonicalInstanceLifecycleEvidence preparing,
      WorldCanonicalInstanceActivation.Request activation) {}

  record TestIdentity(String alias, PrivateKey privateKey, X509Certificate certificate) {}

  record TestPki(
      X509Certificate caCertificate,
      Path caCertificatePem,
      TestIdentity accountServer,
      TestIdentity worldClient,
      TestIdentity wrongServiceClient,
      TestIdentity otherNamespaceClient) {
    private static final String STORE_PASSWORD = "test-only-store-password";
    private static final String ACCOUNT_URI =
        "spiffe://firemud/ns/world-runtime/sa/account-service";
    private static final String WRONG_SERVICE_URI =
        "spiffe://firemud/ns/world-runtime/sa/game-session-service";
    private static final String OTHER_NAMESPACE_URI =
        "spiffe://firemud/ns/other-runtime/sa/world-management-service";

    static TestPki create(Path directory) throws Exception {
      Path caStore = directory.resolve("activation-test-ca.p12");
      runKeytool(
          "-genkeypair",
          "-alias",
          "test-ca",
          "-keyalg",
          "RSA",
          "-keysize",
          "2048",
          "-dname",
          "CN=FireMUD Account activation test CA",
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
      Path caFile = directory.resolve("activation-test-ca.crt");
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
          caFile,
          issueIdentity(directory, caStore, caFile, "account-server", ACCOUNT_URI, true),
          issueIdentity(directory, caStore, caFile, "world-management-client", WORLD_URI, false),
          issueIdentity(
              directory, caStore, caFile, "wrong-service-client", WRONG_SERVICE_URI, false),
          issueIdentity(
              directory, caStore, caFile, "other-namespace-client", OTHER_NAMESPACE_URI, false));
    }

    CommonGrpcClientProperties clientProperties(Path directory, TestIdentity identity)
        throws Exception {
      Path certificate =
          writePem(
              directory.resolve(identity.alias() + ".crt"),
              "CERTIFICATE",
              identity.certificate().getEncoded());
      Path privateKey =
          writePem(
              directory.resolve(identity.alias() + ".key"),
              "PRIVATE KEY",
              identity.privateKey().getEncoded());
      CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
      tls.setCertChain(certificate.toString());
      tls.setPrivateKey(privateKey.toString());
      tls.setCaCert(caCertificatePem.toString());
      tls.setPlaintext(false);
      return tls;
    }

    private static TestIdentity issueIdentity(
        Path directory, Path caStore, Path caFile, String alias, String workloadUri, boolean server)
        throws Exception {
      Path store = directory.resolve(alias + ".p12");
      Path request = directory.resolve(alias + ".csr");
      Path certificate = directory.resolve(alias + ".crt");
      String san = "URI:" + workloadUri + ",DNS:localhost,IP:127.0.0.1";
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
          "EKU=" + extendedKeyUsage,
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
      ArrayList<String> command = new ArrayList<>();
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
          path, "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n");
    }
  }
}
