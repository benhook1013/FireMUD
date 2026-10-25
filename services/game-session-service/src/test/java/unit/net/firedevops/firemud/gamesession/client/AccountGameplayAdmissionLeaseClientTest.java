package net.firedevops.firemud.gamesession.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import io.grpc.Context;
import io.grpc.Deadline;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.TlsServerCredentials;
import io.grpc.stub.StreamObserver;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.account.v1.AbortGameplayAdmissionLeaseRequest;
import net.firedevops.firemud.account.v1.AccountGameplayAdmissionLeaseResponse;
import net.firedevops.firemud.account.v1.AccountGameplayAdmissionLeaseServiceGrpc;
import net.firedevops.firemud.account.v1.AcquireGameplayAdmissionLeaseRequest;
import net.firedevops.firemud.account.v1.FinalizeGameplayAdmissionLeaseRequest;
import net.firedevops.firemud.account.v1.ReadGameplayAdmissionLeaseRequest;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseGrpcCodec;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseGrpcCodec.AcquireRequest;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseGrpcCodec.OriginalLeaseRequest;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseGrpcCodec.State;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.Candidate;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.OriginKind;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.OwnerProofOutcome;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.Target;
import net.firedevops.firemud.common.grpc.BoundedGrpcServerFactory;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.grpc.GrpcRequestBytePolicy;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Controlled Account peer transport proof; the returned lease is codec evidence only. */
@Timeout(60)
class AccountGameplayAdmissionLeaseClientTest {
  private static final String NAMESPACE = "game-session-admission-test";
  private static final String GAME_SESSION_PEER =
      "spiffe://firemud/ns/" + NAMESPACE + "/sa/game-session-service";
  private static final String JWT = "header.transient-test-payload.signature";
  private static final UUID REQUEST_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID TENANT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID OTHER_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID VERSION_ID = UUID.fromString("99999999-9999-4999-8999-999999999999");
  private static final UUID HOLD_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final UUID HOLD_FENCE = UUID.fromString("66666666-6666-4666-8666-666666666666");
  private static final UUID DECISION_ID = UUID.fromString("77777777-7777-4777-8777-777777777777");
  private static final String HIGH = "9007199254740993";

  @TempDir static Path directory;
  private static LoopbackMtlsTestPki.Pki pki;

  @BeforeAll
  static void certificates() throws Exception {
    pki =
        LoopbackMtlsTestPki.create(
            directory,
            "Gameplay admission lease test CA",
            new LoopbackMtlsTestPki.IdentitySpec("client", GAME_SESSION_PEER, false),
            new LoopbackMtlsTestPki.IdentitySpec(
                "account-server", "spiffe://firemud/ns/" + NAMESPACE + "/sa/account-service", true),
            new LoopbackMtlsTestPki.IdentitySpec(
                "wrong-role",
                "spiffe://firemud/ns/" + NAMESPACE + "/sa/world-management-service",
                true),
            new LoopbackMtlsTestPki.IdentitySpec(
                "foreign-namespace", "spiffe://firemud/ns/other/sa/account-service", true));
  }

  @Test
  void allFourOperationsUsePinnedMtlsPeerAndPreserveOriginalLeaseBytes() throws Exception {
    var observed = new CopyOnWriteArrayList<String>();
    var acquireRequests = new CopyOnWriteArrayList<AcquireGameplayAdmissionLeaseRequest>();
    var finalizeRequests = new CopyOnWriteArrayList<FinalizeGameplayAdmissionLeaseRequest>();
    var readRequests = new CopyOnWriteArrayList<ReadGameplayAdmissionLeaseRequest>();
    var abortRequests = new CopyOnWriteArrayList<AbortGameplayAdmissionLeaseRequest>();
    var deadlines = new CopyOnWriteArrayList<Deadline>();
    Server server =
        server(
            "account-server",
            new AccountGameplayAdmissionLeaseServiceGrpc
                .AccountGameplayAdmissionLeaseServiceImplBase() {
              @Override
              public void acquireGameplayAdmissionLease(
                  AcquireGameplayAdmissionLeaseRequest request,
                  StreamObserver<AccountGameplayAdmissionLeaseResponse> observer) {
                capture(observed, deadlines);
                acquireRequests.add(request);
                try {
                  AcquireRequest parsed =
                      AccountGameplayAdmissionLeaseGrpcCodec.parseAcquireRequest(request);
                  observer.onNext(
                      AccountGameplayAdmissionLeaseGrpcCodec.toAcquireResponse(
                          parsed, lease(), State.PENDING, null, null, null));
                  observer.onCompleted();
                } catch (RuntimeException invalid) {
                  observer.onError(
                      Status.INVALID_ARGUMENT
                          .withDescription("controlled fixture rejected")
                          .asRuntimeException());
                }
              }

              @Override
              public void finalizeGameplayAdmissionLease(
                  FinalizeGameplayAdmissionLeaseRequest request,
                  StreamObserver<AccountGameplayAdmissionLeaseResponse> observer) {
                capture(observed, deadlines);
                finalizeRequests.add(request);
                OriginalLeaseRequest parsed =
                    AccountGameplayAdmissionLeaseGrpcCodec.parseFinalizeRequest(request);
                observer.onNext(
                    AccountGameplayAdmissionLeaseGrpcCodec.toFinalizeResponse(
                        parsed, parsed.originalLeaseEvidence(), State.PENDING, null, null, null));
                observer.onCompleted();
              }

              @Override
              public void readGameplayAdmissionLease(
                  ReadGameplayAdmissionLeaseRequest request,
                  StreamObserver<AccountGameplayAdmissionLeaseResponse> observer) {
                capture(observed, deadlines);
                readRequests.add(request);
                OriginalLeaseRequest parsed =
                    AccountGameplayAdmissionLeaseGrpcCodec.parseReadRequest(request);
                observer.onNext(
                    AccountGameplayAdmissionLeaseGrpcCodec.toReadResponse(
                        parsed, parsed.originalLeaseEvidence(), State.PENDING, null, null, null));
                observer.onCompleted();
              }

              @Override
              public void abortGameplayAdmissionLease(
                  AbortGameplayAdmissionLeaseRequest request,
                  StreamObserver<AccountGameplayAdmissionLeaseResponse> observer) {
                capture(observed, deadlines);
                abortRequests.add(request);
                OriginalLeaseRequest parsed =
                    AccountGameplayAdmissionLeaseGrpcCodec.parseAbortRequest(request);
                observer.onNext(
                    AccountGameplayAdmissionLeaseGrpcCodec.toAbortResponse(
                        parsed, parsed.originalLeaseEvidence(), State.PENDING, null, null, null));
                observer.onCompleted();
              }
            });
    try (var client = client(server)) {
      client.init();
      var acquired = client.acquire(REQUEST_ID, JWT, candidate(), target(), new BigInteger(HIGH));
      byte[] originalBytes =
          acquired.originalLeaseEvidence().canonicalJson().getBytes(StandardCharsets.UTF_8);
      client.finalizeLease(JWT, originalBytes, DECISION_ID);
      client.read(JWT, originalBytes);
      client.abort(JWT, originalBytes, null);

      assertThat(observed)
          .containsExactly(
              GAME_SESSION_PEER, GAME_SESSION_PEER, GAME_SESSION_PEER, GAME_SESSION_PEER);
      assertThat(acquireRequests).hasSize(1);
      assertThat(acquireRequests.getFirst().getPresentedInitialGsaCompactJwt(0)).isEqualTo(JWT);
      assertThat(finalizeRequests)
          .singleElement()
          .satisfies(
              request -> {
                assertThat(request.getOriginalLeaseEvidenceUtf8(0).toByteArray())
                    .containsExactly(originalBytes);
                assertThat(request.getBindingDecisionUuid(0)).isEqualTo(DECISION_ID.toString());
              });
      assertThat(readRequests)
          .singleElement()
          .satisfies(
              request ->
                  assertThat(request.getOriginalLeaseEvidenceUtf8(0).toByteArray())
                      .containsExactly(originalBytes));
      assertThat(abortRequests)
          .singleElement()
          .satisfies(
              request -> {
                assertThat(request.getOriginalLeaseEvidenceUtf8(0).toByteArray())
                    .containsExactly(originalBytes);
                assertThat(request.getBindingDecisionUuidCount()).isZero();
              });
      assertThat(deadlines)
          .hasSize(4)
          .allSatisfy(
              deadline -> {
                long remainingMillis = deadline.timeRemaining(TimeUnit.MILLISECONDS);
                assertThat(remainingMillis).isPositive().isLessThanOrEqualTo(5_000L);
              });
      assertThat(acquired.toString()).doesNotContain(JWT);
    } finally {
      stop(server);
    }
  }

  @Test
  void wrongAccountRoleAndNamespaceAreRejectedBeforeServerHandlerReceivesJwt() throws Exception {
    for (String identity : List.of("wrong-role", "foreign-namespace")) {
      var calls = new AtomicInteger();
      Server server =
          server(
              identity,
              new AccountGameplayAdmissionLeaseServiceGrpc
                  .AccountGameplayAdmissionLeaseServiceImplBase() {
                @Override
                public void acquireGameplayAdmissionLease(
                    AcquireGameplayAdmissionLeaseRequest request,
                    StreamObserver<AccountGameplayAdmissionLeaseResponse> observer) {
                  calls.incrementAndGet();
                  observer.onError(Status.UNAVAILABLE.asRuntimeException());
                }
              });
      try (var client = client(server)) {
        client.init();
        assertThatThrownBy(
                () -> client.acquire(REQUEST_ID, JWT, candidate(), target(), new BigInteger(HIGH)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Account gameplay admission lease acquisition failed")
            .hasMessageNotContaining(JWT);
        assertThat(calls).hasValue(0);
      } finally {
        stop(server);
      }
    }
  }

  @Test
  void foreignNamespaceLeaseWithMatchingCallerIsRejectedBeforeOriginalLeaseRpc() throws Exception {
    var calls = new AtomicInteger();
    Server server =
        server(
            "account-server",
            new AccountGameplayAdmissionLeaseServiceGrpc
                .AccountGameplayAdmissionLeaseServiceImplBase() {
              @Override
              public void finalizeGameplayAdmissionLease(
                  FinalizeGameplayAdmissionLeaseRequest request,
                  StreamObserver<AccountGameplayAdmissionLeaseResponse> observer) {
                calls.incrementAndGet();
                observer.onError(Status.UNAVAILABLE.asRuntimeException());
              }

              @Override
              public void readGameplayAdmissionLease(
                  ReadGameplayAdmissionLeaseRequest request,
                  StreamObserver<AccountGameplayAdmissionLeaseResponse> observer) {
                calls.incrementAndGet();
                observer.onError(Status.UNAVAILABLE.asRuntimeException());
              }

              @Override
              public void abortGameplayAdmissionLease(
                  AbortGameplayAdmissionLeaseRequest request,
                  StreamObserver<AccountGameplayAdmissionLeaseResponse> observer) {
                calls.incrementAndGet();
                observer.onError(Status.UNAVAILABLE.asRuntimeException());
              }
            });
    try (var client = client(server)) {
      client.init();
      Map<String, Object> foreignCarrier = new LinkedHashMap<>(lease().carrier());
      foreignCarrier.put("targetNamespace", "other");
      foreignCarrier.put("callerWorkload", "spiffe://firemud/ns/other/sa/game-session-service");
      byte[] foreignLeaseBytes =
          AccountGameplayAdmissionLeaseEvidence.fromCarrier(foreignCarrier)
              .canonicalJson()
              .getBytes(StandardCharsets.UTF_8);

      assertThatThrownBy(() -> client.finalizeLease(JWT, foreignLeaseBytes, DECISION_ID))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("does not correlate");
      assertThatThrownBy(() -> client.read(JWT, foreignLeaseBytes))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("does not correlate");
      assertThatThrownBy(() -> client.abort(JWT, foreignLeaseBytes, null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("does not correlate");
      assertThat(calls).hasValue(0);
    } finally {
      stop(server);
    }
  }

  @Test
  void responseWireLimitRejectsOversizedAccountReplyWithRedactedError() throws Exception {
    var calls = new AtomicInteger();
    Server server =
        server(
            "account-server",
            new AccountGameplayAdmissionLeaseServiceGrpc
                .AccountGameplayAdmissionLeaseServiceImplBase() {
              @Override
              public void acquireGameplayAdmissionLease(
                  AcquireGameplayAdmissionLeaseRequest request,
                  StreamObserver<AccountGameplayAdmissionLeaseResponse> observer) {
                calls.incrementAndGet();
                observer.onNext(
                    AccountGameplayAdmissionLeaseResponse.newBuilder()
                        .addSchemaVersion(1)
                        .addOriginalLeaseEvidenceUtf8(
                            ByteString.copyFrom(
                                new byte
                                    [AccountGameplayAdmissionLeaseGrpcCodec.MAX_RESPONSE_WIRE_BYTES
                                        + 1]))
                        .build());
                observer.onCompleted();
              }
            });
    try (var client = client(server)) {
      client.init();
      assertThatThrownBy(
              () -> client.acquire(REQUEST_ID, JWT, candidate(), target(), new BigInteger(HIGH)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Account gameplay admission lease acquisition failed")
          .hasMessageNotContaining(JWT);
      assertThat(calls).hasValue(1);
    } finally {
      stop(server);
    }
  }

  @Test
  void rejectsPlaintextNamespaceAndAmbientSqlOrSynchronizationBeforeRpc() throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    var plaintext = new CommonGrpcClientProperties();
    plaintext.setPlaintext(true);
    assertThatThrownBy(
            () ->
                new AccountGameplayAdmissionLeaseClient(
                    endpoints, plaintext, new GrpcChannelFactory(), NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("requires workload mTLS");
    assertThatThrownBy(
            () ->
                new AccountGameplayAdmissionLeaseClient(
                    endpoints,
                    pki.clientProperties(directory, pki.identity("client")),
                    new GrpcChannelFactory(),
                    "bad namespace"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("namespace");

    Server server =
        server(
            "account-server",
            new AccountGameplayAdmissionLeaseServiceGrpc
                .AccountGameplayAdmissionLeaseServiceImplBase() {});
    try (var client = client(server)) {
      TransactionSynchronizationManager.initSynchronization();
      try {
        assertThatThrownBy(client::init)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("initialization requires no ambient SQL");
      } finally {
        TransactionSynchronizationManager.clearSynchronization();
      }
      client.init();
      TransactionSynchronizationManager.setActualTransactionActive(true);
      try {
        assertThatThrownBy(() -> client.acquire(null, null, null, null, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("acquisition requires no ambient SQL");
        assertThatThrownBy(() -> client.finalizeLease(null, null, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("finalization requires no ambient SQL");
        assertThatThrownBy(() -> client.read(null, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("read requires no ambient SQL");
        assertThatThrownBy(() -> client.abort(null, null, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("abort requires no ambient SQL");
      } finally {
        TransactionSynchronizationManager.setActualTransactionActive(false);
      }
    } finally {
      stop(server);
    }
  }

  private static AccountGameplayAdmissionLeaseClient client(Server server) throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setAccountService("localhost:" + server.getPort());
    return new AccountGameplayAdmissionLeaseClient(
        endpoints,
        pki.clientProperties(directory, pki.identity("client")),
        new GrpcChannelFactory(),
        NAMESPACE);
  }

  private static Server server(
      String identity,
      AccountGameplayAdmissionLeaseServiceGrpc.AccountGameplayAdmissionLeaseServiceImplBase service)
      throws Exception {
    var serverIdentity = pki.identity(identity);
    var factory =
        new BoundedGrpcServerFactory(
            "127.0.0.1:0",
            List.of(),
            pki.keyManagerFactory(serverIdentity),
            pki.trustManagerFactory(),
            TlsServerCredentials.ClientAuth.REQUIRE,
            GrpcRequestBytePolicy.ordinary(),
            GrpcRequestBytePolicy.ORDINARY_MAXIMUM_BYTES);
    factory.addService(ServerInterceptors.intercept(service, new GrpcPeerIdentityInterceptor()));
    return factory.createServer().start();
  }

  private static void capture(List<String> peers, List<Deadline> deadlines) {
    var peer = GrpcPeerIdentity.current();
    peers.add(peer == null ? null : peer.uri());
    deadlines.add(Context.current().getDeadline());
  }

  private static void stop(Server server) throws InterruptedException {
    server.shutdownNow();
    assertThat(server.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
  }

  private static Candidate candidate() {
    return new Candidate(
        ACCOUNT_ID,
        TENANT_ID,
        OTHER_ID,
        OTHER_ID,
        "SHARED",
        OTHER_ID,
        71,
        OTHER_ID,
        OTHER_ID,
        new BigInteger(HIGH),
        GameSessionAccountDelegationProfile.ISSUER,
        BigInteger.ONE,
        new BigInteger(HIGH),
        new BigInteger(HIGH),
        new BigInteger(HIGH));
  }

  private static Target target() {
    var ownerRequest =
        new WorldCanonicalInitialAdmissionHold.Request(
            NAMESPACE,
            TENANT_ID,
            "world",
            OTHER_ID,
            OTHER_ID,
            "SHARED",
            OTHER_ID,
            VERSION_ID,
            new BigInteger(HIGH).longValueExact(),
            REQUEST_ID.toString(),
            "c".repeat(64),
            WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER,
            new BigInteger(HIGH).longValueExact(),
            null);
    var hold =
        new WorldCanonicalInitialAdmissionHold.HoldIdentity(ownerRequest, HOLD_ID, HOLD_FENCE);
    return new Target(
        NAMESPACE,
        "tenant",
        TENANT_ID,
        "world",
        "World",
        OTHER_ID,
        "realm",
        "Realm",
        11,
        71,
        OTHER_ID,
        "SHARED",
        OTHER_ID,
        VERSION_ID,
        99,
        new BigInteger(HIGH).longValueExact(),
        new BigInteger(HIGH).longValueExact(),
        "d".repeat(64),
        new BigInteger(HIGH).longValueExact(),
        REQUEST_ID.toString(),
        "c".repeat(64),
        OriginKind.NO_PRIOR_POINTER,
        null,
        HOLD_ID,
        HOLD_FENCE,
        hold.holdBindingDigest(),
        101,
        "sha256:" + "a".repeat(64),
        OwnerProofOutcome.COMMITTED,
        false,
        Instant.parse("2026-10-25T00:00:00Z"),
        "PRESEEDED_ONLY");
  }

  private static AccountGameplayAdmissionLeaseEvidence lease() {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("schema", AccountGameplayAdmissionLeaseEvidence.SCHEMA);
    value.put("schemaVersion", "1");
    value.put("mode", "PUBLIC_PRODUCTION");
    value.put("targetNamespace", NAMESPACE);
    value.put("callerWorkload", GAME_SESSION_PEER);
    value.put("requestId", REQUEST_ID.toString());
    value.put("leaseId", TENANT_ID.toString());
    value.put("leaseFence", HIGH);
    value.put("leaseKind", "NEW_BINDING");
    Map<String, Object> scope = new LinkedHashMap<>();
    scope.put("accountId", ACCOUNT_ID.toString());
    scope.put("tenantId", TENANT_ID.toString());
    scope.put("realmId", OTHER_ID.toString());
    scope.put("worldSlug", "world");
    scope.put("realmSlug", "realm");
    scope.put("playableStateNamespaceId", OTHER_ID.toString());
    scope.put("playableStateScope", "SHARED");
    scope.put("gameInstanceId", OTHER_ID.toString());
    scope.put("characterId", OTHER_ID.toString());
    scope.put("sessionId", OTHER_ID.toString());
    scope.put("bindingGeneration", HIGH);
    scope.put("catalogRevision", HIGH);
    scope.put("pointerVersion", HIGH);
    scope.put("regionId", OTHER_ID.toString());
    scope.put("regionEpoch", HIGH);
    value.put("bindingScope", scope);
    value.put(
        "authorityTuple",
        Map.of(
            "issuerAuthGeneration", "1",
            "accountAuthorityGeneration", "1",
            "tenantAuthorityGeneration", Map.of(TENANT_ID.toString(), HIGH),
            "membershipAuthorityGeneration", Map.of(TENANT_ID.toString(), HIGH),
            "privateRealmGrantVersions", List.of()));
    value.put("issuanceFence", HIGH);
    value.put(
        "membershipBaseline",
        Map.of(
            "membershipLifecycleState",
            "ACTIVE",
            "membershipVersion",
            Map.of(TENANT_ID.toString(), HIGH),
            "membershipAuthorityGeneration",
            HIGH));
    List<Map<String, Object>> checkpoints = new ArrayList<>();
    for (String suffix :
        List.of(
            "account/" + ACCOUNT_ID,
            "issuer/firemud-account-service",
            "membership/" + ACCOUNT_ID + "/" + TENANT_ID,
            "tenant/" + TENANT_ID)) {
      checkpoints.add(
          Map.of(
              "outboxStreamKey",
              "account:auth-authority:v1:" + suffix,
              "outboxSequence",
              suffix.startsWith("account/") || suffix.startsWith("issuer/") ? "0" : HIGH));
    }
    value.put("outboxCheckpoints", checkpoints);
    value.put(
        "tokenIdentityEvidence",
        Map.ofEntries(
            Map.entry("accountId", ACCOUNT_ID.toString()),
            Map.entry("operationId", OTHER_ID.toString()),
            Map.entry("issuanceRequestId", OTHER_ID.toString()),
            Map.entry("tokenJti", OTHER_ID.toString()),
            Map.entry("tokenSHA256", jwtSha256(JWT)),
            Map.entry("tokenGeneration", HIGH),
            Map.entry("issuanceFence", HIGH),
            Map.entry("tokenIdentityFence", HIGH),
            Map.entry("tokenProfile", "game-session-account-delegation"),
            Map.entry("issuedAt", "999"),
            Map.entry("notBefore", "999"),
            Map.entry("expiresAt", "1300")));
    value.put("evaluatedAt", "1000000");
    value.put("expiresAt", "1015000");
    return AccountGameplayAdmissionLeaseEvidence.fromCarrier(value);
  }

  private static String jwtSha256(String jwt) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(jwt.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception unavailable) {
      throw new IllegalStateException("SHA-256 unavailable");
    }
  }
}
