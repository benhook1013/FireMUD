package net.firedevops.firemud.gamesession.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.account.v1.AccountPlayerAdmissionEntitlementReadServiceGrpc;
import net.firedevops.firemud.account.v1.ReadPlayerAdmissionEntitlementRequest;
import net.firedevops.firemud.account.v1.ReadPlayerAdmissionEntitlementResponse;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.AuthorityTuple;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.TenantBillingCutoff;
import net.firedevops.firemud.common.account.authority.RuntimeMembershipAuthorityEvidenceValidator.Checkpoint;
import net.firedevops.firemud.common.account.authority.RuntimeMembershipAuthorityEvidenceValidator.Snapshot;
import net.firedevops.firemud.common.account.authority.RuntimeMembershipAuthorityEvidenceValidator.SourceEvidence;
import net.firedevops.firemud.common.account.authority.RuntimePlayerAdmissionEntitlementEvidence;
import net.firedevops.firemud.common.account.authority.RuntimePlayerAdmissionEntitlementGrpcCodec;
import net.firedevops.firemud.common.account.authority.TenantAuthorityEventV1Codec;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.gamesession.dto.CanonicalGameplayLoginResult;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContext;
import net.firedevops.firemud.gamesession.support.FirstPartyReceivingFixture;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Controlled Account peer proof for credential release and canonical response binding. */
@Timeout(90)
class AccountPlayerAdmissionEntitlementClientMtlsTest {
  private static final String NAMESPACE = "firemud-test";
  private static final String GS_PEER =
      "spiffe://firemud/ns/" + NAMESPACE + "/sa/game-session-service";
  private static final String ACCOUNT_PEER =
      "spiffe://firemud/ns/" + NAMESPACE + "/sa/account-service";
  private static final String JWT = "a.b.c";
  private static final UUID LOGIN_REQUEST = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID LOGIN_CONTEXT = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID LOGIN_JTI = UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final UUID READ_REQUEST = UUID.fromString("66666666-6666-4666-8666-666666666666");
  private static final Instant NOW = Instant.ofEpochMilli(FirstPartyReceivingFixture.NOW + 1_000L);
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  @TempDir static Path directory;
  private static LoopbackMtlsTestPki.Pki pki;

  @BeforeAll
  static void certificates() throws Exception {
    pki =
        LoopbackMtlsTestPki.create(
            directory,
            "Player admission entitlement client test CA",
            new LoopbackMtlsTestPki.IdentitySpec("client", GS_PEER, false),
            new LoopbackMtlsTestPki.IdentitySpec("account", ACCOUNT_PEER, true),
            new LoopbackMtlsTestPki.IdentitySpec(
                "wrong-service",
                "spiffe://firemud/ns/" + NAMESPACE + "/sa/world-management-service",
                true),
            new LoopbackMtlsTestPki.IdentitySpec(
                "wrong-namespace", "spiffe://firemud/ns/other/sa/account-service", true));
  }

  @Test
  void exactAccountPeerReceivesCurrentLoginCredentialAndReturnsBoundCanonicalEvidence()
      throws Exception {
    var context = context();
    var login = loginCustody();
    var calls = new AtomicInteger();
    var observedJwt = new AtomicReference<String>();
    var observedPeer = new AtomicReference<String>();
    Server server = server("account", calls, observedJwt, observedPeer, context, login.evidence());
    try (var client = client(server)) {
      client.init();
      var result = client.read(context, READ_REQUEST, login, () -> true);
      assertThat(result.fields())
          .containsEntry("variant", "PLAYER_ADMISSION")
          .containsEntry("sourceKind", "NON_PAID_DEMO")
          .containsEntry("membershipExists", false);
      assertThat(calls).hasValue(1);
      assertThat(observedJwt).hasValue(JWT);
      assertThat(observedPeer).hasValue(GS_PEER);
      assertThat(new String(result.canonicalBytes(), StandardCharsets.UTF_8)).doesNotContain(JWT);
    } finally {
      stop(server);
    }
  }

  @Test
  void sameCaWrongServiceAndNamespaceReceiveNoRequestCredential() throws Exception {
    for (String identity : List.of("wrong-service", "wrong-namespace")) {
      var context = context();
      var login = loginCustody();
      var calls = new AtomicInteger();
      var observedJwt = new AtomicReference<String>();
      var observedPeer = new AtomicReference<String>();
      Server server = server(identity, calls, observedJwt, observedPeer, context, login.evidence());
      try (var client = client(server)) {
        client.init();
        assertThatThrownBy(() -> client.read(context, READ_REQUEST, login, () -> true))
            .isInstanceOfSatisfying(
                StatusRuntimeException.class,
                failure ->
                    assertThat(failure.getStatus().getCode())
                        .isEqualTo(Status.Code.UNAUTHENTICATED));
        assertThat(calls).hasValue(0);
        assertThat(observedJwt).hasValue(null);
        assertThat(observedPeer).hasValue(null);
      } finally {
        stop(server);
      }
    }
  }

  @Test
  void staleLoginCustodyDoesNotStartRpc() throws Exception {
    var context = context();
    var login = loginCustody();
    var calls = new AtomicInteger();
    var observedJwt = new AtomicReference<String>();
    var observedPeer = new AtomicReference<String>();
    Server server = server("account", calls, observedJwt, observedPeer, context, login.evidence());
    try (var client = client(server)) {
      client.init();
      assertThatThrownBy(() -> client.read(context, READ_REQUEST, login, () -> false))
          .isInstanceOfSatisfying(
              StatusRuntimeException.class,
              failure ->
                  assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.CANCELLED));
      assertThat(calls).hasValue(0);
      assertThat(observedJwt).hasValue(null);
    } finally {
      stop(server);
    }
  }

  private static AccountPlayerAdmissionEntitlementClient client(Server server) throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setAccountService("localhost:" + server.getPort());
    return new AccountPlayerAdmissionEntitlementClient(
        endpoints,
        pki.clientProperties(directory, pki.identity("client")),
        new GrpcChannelFactory(),
        NAMESPACE,
        CLOCK);
  }

  private static Server server(
      String identity,
      AtomicInteger calls,
      AtomicReference<String> observedJwt,
      AtomicReference<String> observedPeer,
      FirstPartyConnectContext context,
      CanonicalGameplayLoginResult.NonSecretEvidence login)
      throws Exception {
    var service =
        new AccountPlayerAdmissionEntitlementReadServiceGrpc
            .AccountPlayerAdmissionEntitlementReadServiceImplBase() {
          @Override
          public void readPlayerAdmissionEntitlement(
              ReadPlayerAdmissionEntitlementRequest request,
              StreamObserver<ReadPlayerAdmissionEntitlementResponse> observer) {
            calls.incrementAndGet();
            var peer = GrpcPeerIdentity.current();
            observedPeer.set(peer == null ? null : peer.uri());
            try {
              var presented = RuntimePlayerAdmissionEntitlementGrpcCodec.parseRequest(request);
              observedJwt.set(presented.initialLoginJwt());
              var evidence = evidence(presented.request(), context, login);
              observer.onNext(
                  RuntimePlayerAdmissionEntitlementGrpcCodec.toResponse(
                      presented.request(), evidence));
              observer.onCompleted();
            } catch (Exception malformed) {
              observer.onError(
                  Status.INVALID_ARGUMENT
                      .withDescription("Controlled Account fixture rejected request")
                      .asRuntimeException());
            }
          }
        };
    var serverIdentity = pki.identity(identity);
    return NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
        .sslContext(
            GrpcSslContexts.configure(
                    SslContextBuilder.forServer(
                        serverIdentity.privateKey(), serverIdentity.certificate()))
                .trustManager(pki.caCertificate())
                .clientAuth(ClientAuth.REQUIRE)
                .build())
        .addService(ServerInterceptors.intercept(service, new GrpcPeerIdentityInterceptor()))
        .build()
        .start();
  }

  private static void stop(Server server) throws InterruptedException {
    server.shutdownNow();
    assertThat(server.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
  }

  private static FirstPartyConnectContext context() {
    var fixture = new FirstPartyReceivingFixture();
    CanonicalPlayableTarget target = org.mockito.Mockito.spy(fixture.target());
    doReturn("PRESEEDED_ONLY").when(target).characterCreationPolicy();
    when(fixture.routes.readCurrentTarget(FirstPartyReceivingFixture.TENANT)).thenReturn(target);
    return fixture.service.parse(fixture.token()).orElseThrow();
  }

  private static CanonicalGameplayLoginResult loginCustody() throws Exception {
    var login = mock(CanonicalGameplayLoginResult.class);
    String jwtSha256 =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(JWT.getBytes(StandardCharsets.US_ASCII)));
    var evidence =
        new CanonicalGameplayLoginResult.NonSecretEvidence(
            FirstPartyReceivingFixture.ACCOUNT,
            LOGIN_REQUEST,
            LOGIN_CONTEXT,
            LOGIN_JTI,
            jwtSha256,
            GameSessionAccountDelegationProfile.PROFILE,
            NOW.minusSeconds(10).getEpochSecond(),
            NOW.minusSeconds(10).getEpochSecond(),
            NOW.plusSeconds(60).getEpochSecond(),
            "1",
            "1",
            ByteString.EMPTY,
            ByteString.EMPTY,
            ByteString.EMPTY);
    when(login.evidence()).thenReturn(evidence);
    when(login.compactJwtBytes()).thenAnswer(invocation -> JWT.getBytes(StandardCharsets.US_ASCII));
    return login;
  }

  private static RuntimePlayerAdmissionEntitlementEvidence evidence(
      RuntimePlayerAdmissionEntitlementGrpcCodec.Request request,
      FirstPartyConnectContext context,
      CanonicalGameplayLoginResult.NonSecretEvidence login)
      throws Exception {
    CanonicalPlayableTarget retained = context.resolvedTarget();
    Instant scopeExpiry = Instant.ofEpochMilli(context.claims().expiresAtMillis());
    Instant scopeStarted = NOW.minusSeconds(10);
    Map<String, Object> target = new LinkedHashMap<>();
    target.put("targetClass", "PUBLIC_PRODUCTION");
    target.put("characterCreationPolicy", "PRESEEDED_ONLY");
    target.put("targetNamespace", retained.targetNamespace());
    target.put("tenantId", retained.canonicalTenantId().toString());
    target.put("tenantSlug", retained.tenantSlug());
    target.put("worldSlug", retained.worldSlug());
    target.put("realmId", retained.realmId().toString());
    target.put("realmSlug", retained.realmSlug());
    target.put("playableStateNamespaceId", retained.playableStateNamespaceId().toString());
    target.put("playableStateScope", retained.playableStateScope());
    target.put("gameInstanceId", retained.canonicalGameInstanceId().toString());
    target.put("canonicalVersionId", retained.canonicalVersionId().toString());
    target.put("catalogRevision", Long.toString(retained.catalogRevision()));
    target.put("pointerVersion", Long.toString(retained.pointerVersion()));
    target.put("activeWorldEpoch", Long.toString(retained.activeWorldEpoch()));
    target.put("evaluatedAt", scopeStarted.toString());
    target.put("connectScopeExpiresAt", scopeExpiry.toString());

    Map<String, Object> facts = new LinkedHashMap<>();
    facts.put("sourceKind", "NON_PAID_DEMO");
    facts.put("subscriptionStatus", null);
    facts.put("provisioningStatus", null);
    for (String field :
        List.of(
            "gameplayAvailable",
            "allowPublicJoin",
            "allowNewGameplayBindings",
            "allowNewInstanceStarts")) {
      facts.put(field, true);
    }
    facts.put(
        "quotas",
        Map.of(
            "maxActiveSessions", "3",
            "maxConcurrentGameInstances", "2",
            "maxStorageBytes", "4096"));
    facts.put("entitlementVersion", "1");
    facts.put("tenantBillingSequence", "1");
    facts.put("tenantAuthorityGeneration", "2");

    Map<String, Object> sources = new LinkedHashMap<>();
    sources.put("callerTokenJti", login.tokenJti().toString());
    sources.put("callerTokenSha256", login.tokenSha256());
    sources.put("callerRegistryRecordSha256", "a".repeat(64));
    sources.put("callerCommitProofSha256", "b".repeat(64));
    sources.put("callerContextId", login.callerContextId().toString());
    sources.put("callerOperationId", LOGIN_REQUEST.toString());
    sources.put(
        "callerTokenExpiresAt", Instant.ofEpochSecond(login.expiresAtEpochSecond()).toString());
    sources.put("paidReceiptValidUntil", null);
    sources.put("scopeDigest", "sha256:" + "c".repeat(64));
    sources.put("entitlementSourceDigest", "sha256:" + "d".repeat(64));
    sources.put("admissionPointerSnapshotDigest", retained.admissionPointerSnapshotDigest());
    sources.put("gameSessionOwnerProofDigest", retained.ownerProofDigest());
    sources.put("publishedPolicySetDigest", "sha256:" + "e".repeat(64));
    sources.put("worldLifecycleDigest", "sha256:" + "f".repeat(64));
    sources.put("worldCaptureId", "77777777-7777-4777-8777-777777777777");
    sources.put("worldRowVersion", "0");

    return RuntimePlayerAdmissionEntitlementEvidence.fromOwnerEvaluation(
        request.target().readRequestId(),
        request.expectedGameSessionWorkload(),
        request.connectScopeId(),
        membership(request.target().accountId(), request.target().tenantId(), retained),
        target,
        facts,
        sources,
        NOW);
  }

  private static Snapshot membership(UUID account, UUID tenant, CanonicalPlayableTarget target)
      throws Exception {
    UUID sourceRequest = UUID.fromString("88888888-8888-4888-8888-888888888888");
    String hash = "a".repeat(64);
    String requestDigest =
        GameTenantCreationDigest.requestDigest(
            target.targetNamespace(), sourceRequest, target.worldSlug(), "World", null);
    var freshTenant =
        new FreshTenantCreationEvidence(
            1,
            target.targetNamespace(),
            sourceRequest,
            sourceRequest,
            requestDigest,
            tenant,
            17,
            target.worldSlug(),
            "NEW_GAME_ROW",
            GameTenantCreationDigest.evidenceDigest(
                target.targetNamespace(),
                sourceRequest,
                sourceRequest,
                requestDigest,
                tenant,
                17,
                target.worldSlug(),
                "NEW_GAME_ROW"));
    var authorityRequest =
        new TenantAuthorityEventV1Codec.AuthorityRequest(
            sourceRequest,
            "sha256:" + hash,
            tenant,
            freshTenant.creationRequestId(),
            freshTenant.requestDigest());
    var billing =
        new TenantAuthorityEventV1Codec.BillingReceipt(
            tenant,
            sourceRequest,
            "sha256:" + hash,
            TenantAuthorityEventV1Codec.tenantBillingStreamKey(tenant),
            1,
            TenantAuthorityEventV1Codec.tenantBillingEventId(sourceRequest),
            "sha256:" + hash);
    var event = TenantAuthorityEventV1Codec.seal(authorityRequest, freshTenant, 2, 2, 1, billing);
    String prefix = MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX;
    var tuple =
        new AuthorityTuple(
            "1",
            "1",
            Map.of(tenant.toString(), "2"),
            Map.of(tenant.toString(), "1"),
            List.of(),
            Optional.empty(),
            Optional.of(
                Map.of(
                    tenant.toString(),
                    new TenantBillingCutoff("2", "1", event.outboxStreamKey(), "1"))));
    return new Snapshot(
        "firemud-account-service",
        account.toString(),
        tenant.toString(),
        false,
        "MISSING",
        false,
        Map.of(tenant.toString(), "1"),
        "1",
        List.of(),
        tuple,
        "1",
        List.of(
            new Checkpoint(prefix + "account/" + account, "0"),
            new Checkpoint(prefix + "issuer/firemud-account-service", "0"),
            new Checkpoint(prefix + "membership/" + account + "/" + tenant, "0"),
            new Checkpoint(event.outboxStreamKey(), "1")),
        List.of(
            new SourceEvidence(
                event.outboxStreamKey(),
                "1",
                event.eventId().toString(),
                event.eventDigest(),
                new String(event.payload(), StandardCharsets.UTF_8))));
  }
}
