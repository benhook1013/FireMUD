package net.firedevops.firemud.gamesession.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import net.firedevops.firemud.account.v1.AccountPlayerAdmissionEntitlementReadServiceGrpc;
import net.firedevops.firemud.account.v1.IssueDirectTextConnectScopeResponse;
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
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.gamesession.command.text.ObservedDirectTextTransport;
import net.firedevops.firemud.gamesession.dto.CanonicalGameplayLoginResult;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginAttemptCoordinates;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContext;
import net.firedevops.firemud.gamesession.service.InitialGameplayLoginContextState;
import net.firedevops.firemud.gamesession.service.directtext.DirectTextAuthenticatedLobbyScope;
import net.firedevops.firemud.gamesession.support.FirstPartyReceivingFixture;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
  private static final InitialGameplayLoginAttemptCoordinates ORIGINAL =
      new InitialGameplayLoginAttemptCoordinates(LOGIN_REQUEST, LOGIN_CONTEXT, 1);
  private static final ObservedDirectTextTransport TRANSPORT =
      new ObservedDirectTextTransport(
          "00042", UUID.fromString("99999999-9999-4999-8999-999999999999"));
  private static final Instant DIRECT_SCOPE_EXPIRY = NOW.plusSeconds(25);
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
  void exactAccountPeerReceivesUncompressedCurrentLoginCredentialAndReturnsBoundCanonicalEvidence()
      throws Exception {
    var context = context();
    var login = loginCustody();
    var calls = new AtomicInteger();
    var observedJwt = new AtomicReference<String>();
    var observedPeer = new AtomicReference<String>();
    var sentResponse = new AtomicReference<ReadPlayerAdmissionEntitlementResponse>();
    Server server =
        server(
            "account",
            calls,
            observedJwt,
            observedPeer,
            context.resolvedTarget(),
            Instant.ofEpochMilli(context.claims().expiresAtMillis()),
            login.evidence(),
            (value, sources) -> {},
            () -> {},
            (request, response) -> {
              sentResponse.set(response);
              return response;
            });
    try (var client = client(server)) {
      client.init();
      assertThat(client.buildStub(mock(ManagedChannel.class)).getCallOptions().getCompressor())
          .isNull();
      var result = client.read(context, READ_REQUEST, login, () -> true);
      assertThat(result.fields())
          .containsEntry("variant", "PLAYER_ADMISSION")
          .containsEntry("sourceKind", "NON_PAID_DEMO")
          .containsEntry("membershipExists", false);
      assertThat(calls).hasValue(1);
      assertThat(observedJwt).hasValue(JWT);
      assertThat(observedPeer).hasValue(GS_PEER);
      assertThat(result.verifiedConnectScopeTokenHash())
          .isEqualTo(
              RuntimePlayerAdmissionEntitlementEvidence.connectScopeTokenHash(
                  context.connectScopeId()));
      assertThat(sentResponse.get().toByteString().toStringUtf8())
          .doesNotContain(JWT, context.connectScopeId());
    } finally {
      stop(server);
    }
  }

  @Test
  void firstPartyPlayerCreatedTargetRefusesBeforeRpcOrCredentialDisclosure() throws Exception {
    var context = context();
    doReturn("PLAYER_CREATED").when(context.resolvedTarget()).characterCreationPolicy();
    var login = loginCustody();
    var calls = new AtomicInteger();
    var observedJwt = new AtomicReference<String>();
    var observedPeer = new AtomicReference<String>();
    Server server = server("account", calls, observedJwt, observedPeer, context, login.evidence());
    try (var client = client(server)) {
      client.init();
      assertThatThrownBy(() -> client.read(context, READ_REQUEST, login, () -> true))
          .isExactlyInstanceOf(IllegalArgumentException.class)
          .hasMessage("Exact live LOGIN custody and verified player-admission scope required");
      assertThat(calls).hasValue(0);
      assertThat(observedJwt).hasValue(null);
      assertThat(observedPeer).hasValue(null);
    } finally {
      stop(server);
    }
  }

  @Test
  void independentReceiverClockMayPrecedeAccountEvaluationWithoutExtendingExpiry()
      throws Exception {
    var context = context();
    var login = loginCustody();
    var calls = new AtomicInteger();
    var observedJwt = new AtomicReference<String>();
    var observedPeer = new AtomicReference<String>();
    Server server = server("account", calls, observedJwt, observedPeer, context, login.evidence());
    Clock receiverClock = Clock.fixed(NOW.minusMillis(25), ZoneOffset.UTC);
    try (var client = client(server, receiverClock)) {
      client.init();
      var result = client.read(context, READ_REQUEST, login, () -> true);
      assertThat(result.evaluatedAt()).isEqualTo(NOW).isAfter(receiverClock.instant());
      assertThat(result.validUntil())
          .isEqualTo(Instant.ofEpochMilli(context.claims().expiresAtMillis()));
      assertThat(calls).hasValue(1);
    } finally {
      stop(server);
    }
  }

  @ParameterizedTest
  @ValueSource(longs = {-1, 0, 1})
  void exactPaidReceiptExpiryHasNoReceiverSkewAllowance(long offsetMillis) throws Exception {
    var context = context();
    var login = loginCustody();
    Instant receiptExpiry = NOW.plusSeconds(1);
    var calls = new AtomicInteger();
    var observedJwt = new AtomicReference<String>();
    var observedPeer = new AtomicReference<String>();
    Server server =
        server(
            "account",
            calls,
            observedJwt,
            observedPeer,
            context.resolvedTarget(),
            Instant.ofEpochMilli(context.claims().expiresAtMillis()),
            login.evidence(),
            (target, sources) -> sources.put("paidReceiptValidUntil", receiptExpiry.toString()),
            () -> {});
    try (var client =
        client(server, Clock.fixed(receiptExpiry.plusMillis(offsetMillis), ZoneOffset.UTC))) {
      client.init();
      if (offsetMillis < 0) {
        assertThat(client.read(context, READ_REQUEST, login, () -> true).validUntil())
            .isEqualTo(receiptExpiry);
      } else {
        assertThatThrownBy(() -> client.read(context, READ_REQUEST, login, () -> true))
            .isInstanceOfSatisfying(
                StatusRuntimeException.class,
                failure ->
                    assertThat(failure.getStatus().getCode())
                        .isEqualTo(Status.Code.DEADLINE_EXCEEDED));
      }
      assertThat(calls).hasValue(1);
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

  @Test
  void directTextOwnedScopeUsesExactAccountExpiryAndCurrentLoginWithoutFirstPartyContext()
      throws Exception {
    var target = context().resolvedTarget();
    var borrowed = new ArrayList<byte[]>();
    var login = loginCustody(borrowed);
    var scope = directTextScope(target, login, DIRECT_SCOPE_EXPIRY);
    var calls = new AtomicInteger();
    var observedJwt = new AtomicReference<String>();
    var observedPeer = new AtomicReference<String>();
    Server server =
        server(
            "account",
            calls,
            observedJwt,
            observedPeer,
            target,
            DIRECT_SCOPE_EXPIRY,
            login.evidence(),
            (value, sources) -> {},
            () -> {});
    try (var client = client(server)) {
      client.init();
      var result = client.read(scope, ORIGINAL, TRANSPORT, READ_REQUEST, login, () -> true);
      assertThat(result.verifiedConnectScopeTokenHash())
          .isEqualTo(
              RuntimePlayerAdmissionEntitlementEvidence.connectScopeTokenHash(
                  "direct-text-owned-scope"));
      assertThat(((Map<?, ?>) result.fields().get("target")).get("connectScopeExpiresAt"))
          .isEqualTo(DIRECT_SCOPE_EXPIRY.toString());
      assertThat(calls).hasValue(1);
      assertThat(observedJwt).hasValue(JWT);
      assertThat(observedPeer).hasValue(GS_PEER);
      assertThat(new String(result.canonicalBytes(), StandardCharsets.UTF_8))
          .doesNotContain(JWT, "direct-text-owned-scope");
      assertThat(borrowed)
          .isNotEmpty()
          .allSatisfy(bytes -> assertThat(bytes).containsOnly((byte) 0));
    } finally {
      stop(server);
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "original-request",
        "original-context",
        "attempt-fence",
        "transport-session",
        "transport-proxy",
        "stale-custody",
        "throwing-custody",
        "null-custody",
        "custody-lost-before-rpc",
        "closed-login",
        "expired-login",
        "expired-scope",
        "wrong-namespace",
        "private-scope",
        "created-policy",
        "durable-abort",
        "read-correlation"
      })
  void directTextInvalidOrStaleInputsDiscloseNoCredential(String change) throws Exception {
    var target = context().resolvedTarget();
    switch (change) {
      case "wrong-namespace" -> doReturn("other").when(target).targetNamespace();
      case "private-scope" -> doReturn("ISOLATED").when(target).playableStateScope();
      case "created-policy" -> doReturn("PLAYER_CREATED").when(target).characterCreationPolicy();
      case "durable-abort" -> doReturn(true).when(target).positiveDurableAbort();
      default -> {}
    }
    var login = loginCustody();
    var expiry = "expired-login".equals(change) ? NOW.plusSeconds(90) : DIRECT_SCOPE_EXPIRY;
    var scope = directTextScope(target, login, expiry);
    var original =
        switch (change) {
          case "original-request" ->
              new InitialGameplayLoginAttemptCoordinates(UUID.randomUUID(), LOGIN_CONTEXT, 1);
          case "original-context" ->
              new InitialGameplayLoginAttemptCoordinates(LOGIN_REQUEST, UUID.randomUUID(), 1);
          case "attempt-fence" ->
              new InitialGameplayLoginAttemptCoordinates(LOGIN_REQUEST, LOGIN_CONTEXT, 2);
          default -> ORIGINAL;
        };
    var transport =
        switch (change) {
          case "transport-session" ->
              new ObservedDirectTextTransport("42", TRANSPORT.proxyConnectionId());
          case "transport-proxy" -> new ObservedDirectTextTransport("00042", UUID.randomUUID());
          default -> TRANSPORT;
        };
    var checks = new AtomicInteger();
    BooleanSupplier current =
        switch (change) {
          case "stale-custody" -> () -> false;
          case "throwing-custody" ->
              () -> {
                throw new IllegalStateException("Controlled custody lost");
              };
          case "null-custody" -> null;
          case "custody-lost-before-rpc" -> () -> checks.incrementAndGet() < 3;
          default -> () -> true;
        };
    if ("closed-login".equals(change)) {
      when(login.compactJwtBytes()).thenThrow(new IllegalStateException("Controlled closed LOGIN"));
    }
    Clock clientClock =
        switch (change) {
          case "expired-login" -> Clock.fixed(NOW.plusSeconds(60), ZoneOffset.UTC);
          case "expired-scope" -> Clock.fixed(DIRECT_SCOPE_EXPIRY, ZoneOffset.UTC);
          default -> CLOCK;
        };
    UUID selectedReadId = "read-correlation".equals(change) ? new UUID(0, 0) : READ_REQUEST;
    var calls = new AtomicInteger();
    var observedJwt = new AtomicReference<String>();
    var observedPeer = new AtomicReference<String>();
    Server server =
        server("account", calls, observedJwt, observedPeer, context(), login.evidence());
    try (var client = client(server, clientClock)) {
      client.init();
      var refusal =
          assertThatThrownBy(
                  () -> client.read(scope, original, transport, selectedReadId, login, current))
              .as(change);
      switch (change) {
        case "stale-custody", "throwing-custody", "null-custody", "custody-lost-before-rpc" ->
            refusal
                .isExactlyInstanceOf(StatusRuntimeException.class)
                .hasMessage("CANCELLED: Account player-admission entitlement read did not complete")
                .satisfies(
                    failure ->
                        assertThat(Status.fromThrowable(failure).getCode())
                            .isEqualTo(Status.Code.CANCELLED));
        case "original-request",
            "original-context",
            "attempt-fence",
            "transport-session",
            "transport-proxy",
            "expired-login",
            "expired-scope" ->
            refusal
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("Exact authenticated lobby evidence required");
        case "closed-login" ->
            refusal
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("Controlled closed LOGIN");
        case "wrong-namespace",
            "private-scope",
            "created-policy",
            "durable-abort",
            "read-correlation" ->
            refusal
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                    "Exact live LOGIN custody and verified player-admission scope required");
        default -> throw new IllegalArgumentException(change);
      }
      assertThat(calls).hasValue(0);
      assertThat(observedJwt).hasValue(null);
      assertThat(observedPeer).hasValue(null);
    } finally {
      stop(server);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"wrong-service", "wrong-namespace"})
  void directTextWrongAccountPeerReceivesNoCredential(String identity) throws Exception {
    var context = context();
    var login = loginCustody();
    var scope = directTextScope(context.resolvedTarget(), login, DIRECT_SCOPE_EXPIRY);
    var calls = new AtomicInteger();
    var observedJwt = new AtomicReference<String>();
    var observedPeer = new AtomicReference<String>();
    Server server = server(identity, calls, observedJwt, observedPeer, context, login.evidence());
    try (var client = client(server)) {
      client.init();
      assertThatThrownBy(
              () -> client.read(scope, ORIGINAL, TRANSPORT, READ_REQUEST, login, () -> true))
          .isInstanceOfSatisfying(
              StatusRuntimeException.class,
              failure ->
                  assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
      assertThat(calls).hasValue(0);
      assertThat(observedJwt).hasValue(null);
    } finally {
      stop(server);
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "version",
        "owner-proof",
        "pointer-digest",
        "scope-expiry",
        "login-jti",
        "login-hash",
        "login-context"
      })
  void directTextRejectsAuthenticatedResponseBindingDrift(String change) throws Exception {
    var target = context().resolvedTarget();
    var login = loginCustody();
    var scope = directTextScope(target, login, DIRECT_SCOPE_EXPIRY);
    var calls = new AtomicInteger();
    var observedJwt = new AtomicReference<String>();
    var observedPeer = new AtomicReference<String>();
    BiConsumer<Map<String, Object>, Map<String, Object>> drift =
        (value, sources) -> {
          switch (change) {
            case "version" -> value.put("canonicalVersionId", UUID.randomUUID().toString());
            case "owner-proof" ->
                sources.put("gameSessionOwnerProofDigest", "sha256:" + "9".repeat(64));
            case "pointer-digest" -> sources.put("admissionPointerSnapshotDigest", "9".repeat(64));
            case "scope-expiry" ->
                value.put("connectScopeExpiresAt", DIRECT_SCOPE_EXPIRY.plusSeconds(1).toString());
            case "login-jti" -> sources.put("callerTokenJti", UUID.randomUUID().toString());
            case "login-hash" -> sources.put("callerTokenSha256", "9".repeat(64));
            case "login-context" -> sources.put("callerContextId", UUID.randomUUID().toString());
            default -> throw new IllegalArgumentException(change);
          }
        };
    Server server =
        server(
            "account",
            calls,
            observedJwt,
            observedPeer,
            target,
            DIRECT_SCOPE_EXPIRY,
            login.evidence(),
            drift,
            () -> {});
    try (var client = client(server)) {
      client.init();
      assertThatThrownBy(
              () -> client.read(scope, ORIGINAL, TRANSPORT, READ_REQUEST, login, () -> true))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(calls).hasValue(1);
      assertThat(observedJwt).hasValue(JWT);
    } finally {
      stop(server);
    }
  }

  @Test
  void directTextRejectsAuthenticatedCanonicalEvidenceForDifferentScope() throws Exception {
    var target = context().resolvedTarget();
    var login = loginCustody();
    var scope = directTextScope(target, login, DIRECT_SCOPE_EXPIRY);
    var calls = new AtomicInteger();
    var observedJwt = new AtomicReference<String>();
    var observedPeer = new AtomicReference<String>();
    var staleRequest = new AtomicReference<RuntimePlayerAdmissionEntitlementGrpcCodec.Request>();
    var sentResponse = new AtomicReference<ReadPlayerAdmissionEntitlementResponse>();
    Server server =
        server(
            "account",
            calls,
            observedJwt,
            observedPeer,
            target,
            DIRECT_SCOPE_EXPIRY,
            login.evidence(),
            (value, sources) -> {},
            () -> {},
            (request, response) -> {
              var stale =
                  new RuntimePlayerAdmissionEntitlementGrpcCodec.Request(
                      request.target(), "stale-scope");
              var staleEvidence =
                  evidence(
                      stale, target, DIRECT_SCOPE_EXPIRY, login.evidence(), (value, sources) -> {});
              var encoded =
                  response.toBuilder()
                      .setCanonicalEvidence(0, ByteString.copyFrom(staleEvidence.canonicalBytes()))
                      .build();
              staleRequest.set(stale);
              sentResponse.set(encoded);
              return encoded;
            });
    try (var client = client(server)) {
      client.init();
      assertThatThrownBy(
              () -> client.read(scope, ORIGINAL, TRANSPORT, READ_REQUEST, login, () -> true))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(calls).hasValue(1);
      assertThat(observedJwt).hasValue(JWT);
      assertThat(observedPeer).hasValue(GS_PEER);
      var wire = sentResponse.get();
      var parsed =
          RuntimePlayerAdmissionEntitlementEvidence.parseCanonical(
              wire.getCanonicalEvidence(0).toByteArray());
      assertThat(parsed.verifiedConnectScopeTokenHash())
          .isEqualTo(RuntimePlayerAdmissionEntitlementEvidence.connectScopeTokenHash("stale-scope"))
          .isNotEqualTo(
              RuntimePlayerAdmissionEntitlementEvidence.connectScopeTokenHash(
                  scope.issued().connectScopeId()));
      assertThat(
              RuntimePlayerAdmissionEntitlementGrpcCodec.requireResponse(staleRequest.get(), wire))
          .isEqualTo(parsed);
    } finally {
      stop(server);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"custody", "scope-expiry", "login-expiry", "closed-login"})
  void directTextRechecksLiveInputsAfterRpc(String change) throws Exception {
    var target = context().resolvedTarget();
    var login = loginCustody();
    var expiry = "login-expiry".equals(change) ? NOW.plusSeconds(90) : DIRECT_SCOPE_EXPIRY;
    var scope = directTextScope(target, login, expiry);
    var current = new AtomicBoolean(true);
    var clockTime = new AtomicReference<>(NOW);
    Clock movingClock = mock(Clock.class);
    when(movingClock.instant()).thenAnswer(invocation -> clockTime.get());
    var calls = new AtomicInteger();
    var observedJwt = new AtomicReference<String>();
    var observedPeer = new AtomicReference<String>();
    var successfulResponses = new AtomicInteger();
    Runnable afterRead =
        () -> {
          switch (change) {
            case "custody" -> current.set(false);
            case "scope-expiry" -> clockTime.set(DIRECT_SCOPE_EXPIRY);
            case "login-expiry" -> clockTime.set(NOW.plusSeconds(60));
            case "closed-login" ->
                when(login.compactJwtBytes()).thenThrow(new IllegalStateException("Closed LOGIN"));
            default -> throw new IllegalArgumentException(change);
          }
          successfulResponses.incrementAndGet();
        };
    Server server =
        server(
            "account",
            calls,
            observedJwt,
            observedPeer,
            target,
            expiry,
            login.evidence(),
            (value, sources) -> {},
            afterRead);
    try (var client = client(server, movingClock)) {
      client.init();
      var refusal =
          assertThatThrownBy(
              () -> client.read(scope, ORIGINAL, TRANSPORT, READ_REQUEST, login, current::get));
      switch (change) {
        case "custody" ->
            refusal
                .isExactlyInstanceOf(StatusRuntimeException.class)
                .satisfies(
                    failure ->
                        assertThat(Status.fromThrowable(failure).getCode())
                            .isEqualTo(Status.Code.CANCELLED));
        case "scope-expiry", "login-expiry" ->
            refusal
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("Exact authenticated lobby evidence required");
        case "closed-login" ->
            refusal.isExactlyInstanceOf(IllegalStateException.class).hasMessage("Closed LOGIN");
        default -> throw new IllegalArgumentException(change);
      }
      assertThat(calls).hasValue(1);
      assertThat(successfulResponses).hasValue(1);
      assertThat(observedJwt).hasValue(JWT);
      assertThat(observedPeer).hasValue(GS_PEER);
    } finally {
      stop(server);
    }
  }

  private static DirectTextAuthenticatedLobbyScope directTextScope(
      CanonicalPlayableTarget target, CanonicalGameplayLoginResult login, Instant expiry) {
    return new DirectTextAuthenticatedLobbyScope(
        UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
        InitialGameplayLoginContextState.installed(ORIGINAL, login),
        TRANSPORT,
        target,
        DirectTextIssuedConnectScope.fromAccountResponse(
            FirstPartyReceivingFixture.ACCOUNT.toString(),
            DirectTextAuthenticatedLobbyScope.targetFor(target),
            IssueDirectTextConnectScopeResponse.newBuilder()
                .setConnectScopeId("direct-text-owned-scope")
                .setEvaluatedAt(NOW.minusSeconds(10).toString())
                .setConnectScopeExpiresAt(expiry.toString())
                .build(),
            CLOCK));
  }

  private static AccountPlayerAdmissionEntitlementClient client(Server server) throws Exception {
    return client(server, CLOCK);
  }

  private static AccountPlayerAdmissionEntitlementClient client(Server server, Clock clock)
      throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setAccountService("localhost:" + server.getPort());
    return new AccountPlayerAdmissionEntitlementClient(
        endpoints,
        pki.clientProperties(directory, pki.identity("client")),
        new GrpcChannelFactory(),
        NAMESPACE,
        clock);
  }

  private static Server server(
      String identity,
      AtomicInteger calls,
      AtomicReference<String> observedJwt,
      AtomicReference<String> observedPeer,
      FirstPartyConnectContext context,
      CanonicalGameplayLoginResult.NonSecretEvidence login)
      throws Exception {
    return server(
        identity,
        calls,
        observedJwt,
        observedPeer,
        context.resolvedTarget(),
        Instant.ofEpochMilli(context.claims().expiresAtMillis()),
        login,
        (value, sources) -> {},
        () -> {});
  }

  private static Server server(
      String identity,
      AtomicInteger calls,
      AtomicReference<String> observedJwt,
      AtomicReference<String> observedPeer,
      CanonicalPlayableTarget target,
      Instant scopeExpiry,
      CanonicalGameplayLoginResult.NonSecretEvidence login,
      BiConsumer<Map<String, Object>, Map<String, Object>> drift,
      Runnable afterRead)
      throws Exception {
    return server(
        identity,
        calls,
        observedJwt,
        observedPeer,
        target,
        scopeExpiry,
        login,
        drift,
        afterRead,
        (request, response) -> response);
  }

  @FunctionalInterface
  private interface ResponseTransform {
    ReadPlayerAdmissionEntitlementResponse apply(
        RuntimePlayerAdmissionEntitlementGrpcCodec.Request request,
        ReadPlayerAdmissionEntitlementResponse response)
        throws Exception;
  }

  private static Server server(
      String identity,
      AtomicInteger calls,
      AtomicReference<String> observedJwt,
      AtomicReference<String> observedPeer,
      CanonicalPlayableTarget target,
      Instant scopeExpiry,
      CanonicalGameplayLoginResult.NonSecretEvidence login,
      BiConsumer<Map<String, Object>, Map<String, Object>> drift,
      Runnable afterRead,
      ResponseTransform responseTransform)
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
              var evidence = evidence(presented.request(), target, scopeExpiry, login, drift);
              // Live-input changes happen only after the successful response is fully encoded.
              var response =
                  RuntimePlayerAdmissionEntitlementGrpcCodec.toResponse(
                      presented.request(), evidence);
              afterRead.run();
              observer.onNext(responseTransform.apply(presented.request(), response));
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
    return loginCustody(new ArrayList<>());
  }

  private static CanonicalGameplayLoginResult loginCustody(List<byte[]> borrowed) throws Exception {
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
            canonical(GameSessionAccountDelegationProfile.authorityTuple(1, 1)),
            canonical(
                List.of(
                    Map.of(
                        "outboxStreamKey",
                        "account:auth-authority:v1:account/" + FirstPartyReceivingFixture.ACCOUNT,
                        "outboxSequence",
                        "0"),
                    Map.of(
                        "outboxStreamKey",
                        "account:auth-authority:v1:issuer/firemud-account-service",
                        "outboxSequence",
                        "0"))),
            canonical(List.of()));
    when(login.evidence()).thenReturn(evidence);
    when(login.compactJwtBytes())
        .thenAnswer(
            invocation -> {
              byte[] bytes = JWT.getBytes(StandardCharsets.US_ASCII);
              borrowed.add(bytes);
              return bytes;
            });
    return login;
  }

  private static ByteString canonical(Object value) throws Exception {
    return ByteString.copyFrom(
        Rfc8785CanonicalJson.canonicalizeUtf8(
            tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(value)));
  }

  private static RuntimePlayerAdmissionEntitlementEvidence evidence(
      RuntimePlayerAdmissionEntitlementGrpcCodec.Request request,
      CanonicalPlayableTarget retained,
      Instant scopeExpiry,
      CanonicalGameplayLoginResult.NonSecretEvidence login,
      BiConsumer<Map<String, Object>, Map<String, Object>> drift)
      throws Exception {
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
    drift.accept(target, sources);
    if (sources.get("paidReceiptValidUntil") != null) {
      facts.put("sourceKind", "PAID_HOSTING");
      facts.put("subscriptionStatus", "active");
    }

    return RuntimePlayerAdmissionEntitlementEvidence.fromOwnerEvaluation(
        request.target().readRequestId(),
        request.expectedGameSessionWorkload(),
        RuntimePlayerAdmissionEntitlementEvidence.connectScopeTokenHash(request.connectScopeId()),
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
