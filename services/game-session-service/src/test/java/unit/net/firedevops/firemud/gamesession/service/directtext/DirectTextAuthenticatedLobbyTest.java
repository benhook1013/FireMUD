package unit.net.firedevops.firemud.gamesession.service.directtext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import net.firedevops.firemud.account.v1.IssueDirectTextConnectScopeResponse;
import net.firedevops.firemud.account.v1.JoinPublicProductionMembershipResponse;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.gamesession.client.AccountClient;
import net.firedevops.firemud.gamesession.client.DirectTextIssuedConnectScope;
import net.firedevops.firemud.gamesession.command.text.DirectTextWorldSelectionService;
import net.firedevops.firemud.gamesession.command.text.ObservedDirectTextTransport;
import net.firedevops.firemud.gamesession.dto.CanonicalGameplayLoginResult;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionOwnerProof.Outcome;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest.OriginKind;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginAttemptCoordinates;
import net.firedevops.firemud.gamesession.repository.DirectTextAuthenticatedLobbyRepository;
import net.firedevops.firemud.gamesession.service.InitialGameplayLoginContextState;
import net.firedevops.firemud.gamesession.service.directtext.DirectTextAuthenticatedLobbyCustody;
import net.firedevops.firemud.gamesession.service.directtext.DirectTextAuthenticatedLobbyJoin;
import net.firedevops.firemud.gamesession.service.directtext.DirectTextAuthenticatedLobbyJoin.State;
import net.firedevops.firemud.gamesession.service.directtext.DirectTextAuthenticatedLobbyOwner;
import net.firedevops.firemud.gamesession.service.directtext.DirectTextAuthenticatedLobbyScope;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.json.JsonMapper;

/**
 * Controlled Account-result/custody/repository fixtures, NOT authenticated ingress or SQL proof.
 */
class DirectTextAuthenticatedLobbyTest {
  private static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final UUID ACCOUNT = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void codecPreservesWholeIdentityTargetAndOriginalAccountTimesWithoutPrivateBytes() {
    var f = new Fixture();
    byte[] bytes = f.scope.encode();
    var restored = DirectTextAuthenticatedLobbyScope.decode(bytes);
    assertThat(restored.encode()).containsExactly(bytes);
    assertThat(restored.evidence()).isEqualTo(f.result.evidence());
    assertThat(restored.publicTarget()).isEqualTo(f.scope.publicTarget());
    assertThat(restored.transport()).isEqualTo(f.transport);
    assertThat(restored.issued().evaluatedAt()).isEqualTo(NOW.minusSeconds(1).toString());
    assertThat(restored.issued().connectScopeExpiresAt()).isEqualTo(NOW.plusSeconds(60).toString());
    assertThat(new String(bytes, StandardCharsets.UTF_8))
        .doesNotContain("fixture-private-token", "password", "authToken", "compactJwt");
    // Historical decode after expiry is still possible, but use cannot renew its deadline.
    assertThatThrownBy(
            () ->
                restored.requireCurrent(
                    f.original,
                    f.result,
                    f.transport,
                    Clock.fixed(NOW.plusSeconds(60), ZoneOffset.UTC)))
        .isInstanceOf(IllegalArgumentException.class);
    verify(f.result, never()).close();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"duplicate", "unknown", "trailing", "coerced", "missing", "malformed", "oversize"})
  void codecRefusesNoncanonicalOrUnboundedState(String change) {
    var f = new Fixture();
    String text = new String(f.scope.encode(), StandardCharsets.UTF_8);
    byte[] bytes =
        switch (change) {
          case "duplicate" ->
              text.replace("\"schema\":", "\"schema\":\"duplicate\",\"schema\":")
                  .getBytes(StandardCharsets.UTF_8);
          case "unknown" ->
              text.replaceFirst("\\{", "{\"credential\":\"untrusted\",")
                  .getBytes(StandardCharsets.UTF_8);
          case "trailing" -> (text + "{}").getBytes(StandardCharsets.UTF_8);
          case "coerced" ->
              text.replace("\"gameInstanceId\":202", "\"gameInstanceId\":\"202\"")
                  .getBytes(StandardCharsets.UTF_8);
          case "missing" ->
              text.replace("\"expectedPriorPointerVersion\":null,", "")
                  .getBytes(StandardCharsets.UTF_8);
          case "oversize" -> new byte[DirectTextAuthenticatedLobbyScope.MAX_BYTES + 1];
          default -> new byte[] {(byte) 0xc3, (byte) 0x28};
        };
    assertThatThrownBy(() -> DirectTextAuthenticatedLobbyScope.decode(bytes))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-1", "01", "+1", "9223372036854775808"})
  void positiveReplyRejectsNoncanonicalOrOverflowingAccountMembershipIds(String membershipId) {
    var f = new Fixture();
    var response = positive().toBuilder().setMembershipId(membershipId).build();
    assertThatThrownBy(
            () -> DirectTextAuthenticatedLobbyJoin.decodeReply(f.scope, response.toByteArray()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void positiveReplyPreservesTheFullPositiveAccountBigintBoundary() {
    var f = new Fixture();
    var response = positive().toBuilder().setMembershipId(Long.toString(Long.MAX_VALUE)).build();
    assertThat(DirectTextAuthenticatedLobbyJoin.decodeReply(f.scope, response.toByteArray()))
        .isEqualTo(response);
  }

  @Test
  void frozenRequestKeepsTransportCorrelationSeparateFromProxyOriginalContextAndJoinIds() {
    var f = new Fixture();
    var context = f.frozen.request().getPlayerContext();
    assertThat(context.getSessionId())
        .isEqualTo(f.transport.transportSessionId())
        .isEqualTo("00042");
    assertThat(context.getRequestId()).isEqualTo(f.frozen.request().getRequestId());
    assertThat(
            List.of(
                context.getSessionId(),
                f.original.callerContextId().toString(),
                f.transport.proxyConnectionId().toString(),
                f.original.requestId().toString(),
                f.scope.recordId().toString(),
                context.getRequestId()))
        .doesNotHaveDuplicates();
  }

  @Test
  void maximumNonAsciiOpaqueScopeIdRoundTripsExactlyInFrozenRequest() {
    var f = new Fixture();
    String opaqueId = "\u4e16".repeat(2048);
    assertThat(opaqueId).hasSize(2048);
    assertThat(opaqueId.getBytes(StandardCharsets.UTF_8)).hasSize(6144);
    var response = scopeResponse().toBuilder().setConnectScopeId(opaqueId).build();
    var scope =
        new DirectTextAuthenticatedLobbyScope(
            UUID.randomUUID(),
            InitialGameplayLoginContextState.installed(f.original, f.result),
            f.transport,
            f.scope.publicTarget(),
            DirectTextIssuedConnectScope.fromAccountResponse(
                ACCOUNT.toString(), f.scope.issued().target(), response, CLOCK));
    var retainedScope = DirectTextAuthenticatedLobbyScope.decode(scope.encode());
    var frozen =
        new DirectTextAuthenticatedLobbyJoin(
            retainedScope,
            DirectTextAuthenticatedLobbyJoin.requestFor(retainedScope, UUID.randomUUID()),
            State.NEVER_DISPATCHED,
            Optional.empty());
    byte[] bytes = frozen.request().toByteArray();
    assertThat(bytes.length)
        .isGreaterThan(4096)
        .isLessThanOrEqualTo(DirectTextAuthenticatedLobbyJoin.MAX_REQUEST_BYTES);
    var restoredRequest = DirectTextAuthenticatedLobbyJoin.decodeRequest(bytes);
    var restored =
        new DirectTextAuthenticatedLobbyJoin(
            retainedScope, restoredRequest, State.NEVER_DISPATCHED, Optional.empty());
    assertThat(restored.request()).isEqualTo(frozen.request());
    assertThat(restored.request().toByteArray()).containsExactly(bytes);
    assertThat(retainedScope.issued().connectScopeId()).isEqualTo(opaqueId);
    assertThat(restored.request().getConnectScopeId().getBytes(StandardCharsets.UTF_8))
        .containsExactly(opaqueId.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void scopeEncodingRefusesOverBoundEnvelopeAfterSuccessfulConstruction() {
    var f = new Fixture();
    // Only display text grows; routing selectors and every constructor-validated identity stay
    // valid.
    // The display has fewer UTF16 code units than MAX_BYTES but exceeds it as UTF8 before headers.
    String display = "\u4e16".repeat(DirectTextAuthenticatedLobbyScope.MAX_BYTES / 3 + 1);
    var target = target(display);
    var oversized =
        new DirectTextAuthenticatedLobbyScope(
            UUID.randomUUID(),
            InitialGameplayLoginContextState.installed(f.original, f.result),
            f.transport,
            target,
            DirectTextIssuedConnectScope.fromAccountResponse(
                ACCOUNT.toString(),
                DirectTextAuthenticatedLobbyScope.targetFor(target),
                scopeResponse(),
                CLOCK));
    assertThat(oversized.publicTarget().worldDisplayName()).isEqualTo(display);
    assertThatThrownBy(oversized::encode).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsUnknownHiddenAndDuplicateProtobufData() {
    var f = new Fixture();
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build();
    var request = f.frozen.request();
    assertThatThrownBy(
            () ->
                DirectTextAuthenticatedLobbyJoin.decodeRequest(
                    request.toBuilder().setUnknownFields(unknown).build().toByteArray()))
        .isInstanceOf(IllegalArgumentException.class);
    var hidden =
        request.toBuilder()
            .setPlayerContext(
                request.getPlayerContext().toBuilder().setCharacterId(UUID.randomUUID().toString()))
            .build();
    assertThatThrownBy(
            () ->
                new DirectTextAuthenticatedLobbyJoin(
                    f.scope, hidden, State.NEVER_DISPATCHED, Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class);
    byte[] duplicate = new byte[request.getSerializedSize() * 2];
    System.arraycopy(request.toByteArray(), 0, duplicate, 0, request.getSerializedSize());
    System.arraycopy(
        request.toByteArray(),
        0,
        duplicate,
        request.getSerializedSize(),
        request.getSerializedSize());
    assertThatThrownBy(() -> DirectTextAuthenticatedLobbyJoin.decodeRequest(duplicate))
        .isInstanceOf(IllegalArgumentException.class);
    for (UUID reused :
        List.of(
            f.original.requestId(),
            f.original.callerContextId(),
            f.scope.recordId(),
            f.transport.proxyConnectionId(),
            f.result.evidence().tokenJti())) {
      assertThatThrownBy(
              () ->
                  new DirectTextAuthenticatedLobbyJoin(
                      f.scope,
                      DirectTextAuthenticatedLobbyJoin.requestFor(f.scope, reused),
                      State.NEVER_DISPATCHED,
                      Optional.empty()))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void onlyGenuineSelectionCanIssueScopeAndAnUnknownIssuanceIsNeverRetried() {
    var f = new Fixture();
    when(f.selection.resolve(null, "private")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> f.owner.realms(f.original, f.result, f.custody, null, "private"))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(f.account);
    when(f.selection.resolve(null, "public")).thenReturn(Optional.of(f.scope.publicTarget()));
    when(f.account.issueDirectTextConnectScope(any(), any()))
        .thenReturn(
            IssueDirectTextConnectScopeResponse.newBuilder()
                .setError(ErrorDetail.newBuilder().setCode("AUTH_UNAVAILABLE"))
                .build());
    assertThatThrownBy(() -> f.owner.realms(f.original, f.result, f.custody, null, "public"))
        .isInstanceOf(IllegalArgumentException.class);
    verify(f.account, times(1)).issueDirectTextConnectScope(any(), any());
    verify(f.repository, never()).retainScope(any(), any(), any());
  }

  @Test
  void freshScopeIsRetainedAndIndependentlyReadAfterCommit() {
    var f = new Fixture();
    when(f.selection.resolve(null, "public")).thenReturn(Optional.of(f.scope.publicTarget()));
    when(f.account.issueDirectTextConnectScope(any(), any())).thenReturn(scopeResponse());
    when(f.repository.readCommittedScope(any()))
        .thenAnswer(
            call -> {
              assertThat(f.events.getLast()).isEqualTo("COMMIT");
              return call.getArgument(0);
            });
    var retained = f.owner.realms(f.original, f.result, f.custody, null, "public");
    assertThat(retained.issued().evaluatedAt()).isEqualTo(scopeResponse().getEvaluatedAt());
    assertThat(retained.issued().connectScopeExpiresAt())
        .isEqualTo(scopeResponse().getConnectScopeExpiresAt());
    verify(f.repository).retainScope(retained, f.result, CLOCK);
  }

  @Test
  void accountEvaluationAheadOfGameSessionClockIsRetainedExactlyWhileLoginRemainsLive() {
    Clock gameSessionClock = Clock.fixed(NOW.minusMillis(30), ZoneOffset.UTC);
    var f = new Fixture(gameSessionClock);
    var accountScope = scopeResponse().toBuilder().setEvaluatedAt(NOW.toString()).build();
    when(f.selection.resolve(null, "public")).thenReturn(Optional.of(f.scope.publicTarget()));
    when(f.account.issueDirectTextConnectScope(any(), any())).thenReturn(accountScope);
    when(f.repository.readCommittedScope(any())).thenAnswer(call -> call.getArgument(0));

    var retained = f.owner.realms(f.original, f.result, f.custody, null, "public");

    assertThat(retained.issued().evaluatedAt()).isEqualTo(accountScope.getEvaluatedAt());
    assertThat(retained.issued().connectScopeExpiresAt())
        .isEqualTo(accountScope.getConnectScopeExpiresAt());
    verify(f.repository).retainScope(retained, f.result, gameSessionClock);
  }

  @ParameterizedTest
  @ValueSource(strings = {"issuedAt", "notBefore"})
  void futureLoginTimestampDeniesRealmPreparationBeforeEffects(String futureClaim) {
    var f = new Fixture();
    long future = NOW.plusSeconds(1).getEpochSecond();
    long issuedAt = futureClaim.equals("issuedAt") ? future : NOW.minusSeconds(2).getEpochSecond();
    long notBefore =
        futureClaim.equals("notBefore") ? future : NOW.minusSeconds(2).getEpochSecond();
    when(f.result.evidence()).thenReturn(evidence(f.original, issuedAt, notBefore));

    assertThatThrownBy(() -> f.owner.realms(f.original, f.result, f.custody, null, "public"))
        .isInstanceOf(IllegalArgumentException.class);

    verifyNoInteractions(f.repository, f.account, f.selection);
  }

  @Test
  void committedExactRequestAndPossibleStatePrecedeAccountAndReplyPersistence() {
    var f = new Fixture();
    when(f.account.joinPublicProductionMembership(any(), any(), any(), any()))
        .thenAnswer(
            call -> {
              assertThat(f.events)
                  .containsSubsequence("FREEZE", "COMMIT", "READ", "CLAIM", "COMMIT", "READ");
              assertThat(
                      call.getArgument(
                          0, net.firedevops.firemud.shared.v1.PlayerExecutionContext.class))
                  .isEqualTo(f.frozen.request().getPlayerContext());
              assertThat(call.getArgument(2, String.class))
                  .isEqualTo(f.frozen.request().getRequestId());
              return positive();
            });
    var observation = f.owner.join(f.original, f.result, f.custody);
    assertThat(observation.status())
        .isEqualTo(DirectTextAuthenticatedLobbyOwner.Status.COMPLETED_CURRENT_NON_ADMITTING);
    assertThat(observation.currentReply()).contains(positive());
    verify(f.account, times(1)).joinPublicProductionMembership(any(), any(), any(), any());
    verify(f.result, never()).close();
    verify(f.account, never()).authenticate(any(), any());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "AUTH_UNAVAILABLE",
        "CONNECT_SCOPE_INVALID",
        "JOIN_FAILED",
        "INVALID_ARGUMENT",
        "IDEMPOTENCY_CONFLICT"
      })
  void genericErrorsNeverFabricateTerminalCompletionOrRearm(String code) {
    var f = new Fixture();
    when(f.account.joinPublicProductionMembership(any(), any(), any(), any()))
        .thenReturn(
            JoinPublicProductionMembershipResponse.newBuilder()
                .setOutcomeCode(code)
                .setError(ErrorDetail.newBuilder().setCode(code))
                .build());
    var first = f.owner.join(f.original, f.result, f.custody);
    var continuation = f.owner.join(f.original, f.result, f.custody);
    assertThat(first.status())
        .isEqualTo(DirectTextAuthenticatedLobbyOwner.Status.RETAINED_UNRESOLVED);
    assertThat(continuation.requestId()).isEqualTo(first.requestId());
    verify(f.repository, never()).recordPositiveReply(any(), any(), any());
    verify(f.account, times(1)).joinPublicProductionMembership(any(), any(), any(), any());
  }

  @Test
  void positiveReplyAfterCustodyLossIsRetainedButNeverAppliedToReplacementTransport() {
    var f = new Fixture();
    when(f.account.joinPublicProductionMembership(any(), any(), any(), any()))
        .thenAnswer(
            call -> {
              f.resetAfterCallback.set(
                  true); // Controlled reset at callback exit, not real receiving proof.
              return positive();
            });
    var observation = f.owner.join(f.original, f.result, f.custody);
    assertThat(observation.status())
        .isEqualTo(DirectTextAuthenticatedLobbyOwner.Status.HISTORICAL_ONLY);
    assertThat(observation.currentReply()).isEmpty();
    assertThat(f.retained.get().reply()).contains(positive());
  }

  @Test
  void lostClaimCommitCannotDispatchOrAllocateACompetitor() {
    var f = new Fixture();
    f.failClaimCommit.set(true);
    assertThatThrownBy(() -> f.owner.join(f.original, f.result, f.custody))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(f.account);
    assertThat(f.retained.get().state()).isEqualTo(State.DISPATCH_POSSIBLE);
    f.failClaimCommit.set(false);
    var continuation = f.owner.join(f.original, f.result, f.custody);
    assertThat(continuation.requestId()).isEqualTo(f.frozen.requestId());
    verifyNoInteractions(f.account);
  }

  @Test
  void resetExpiredPrivateResultAndChangedTransportDenyBeforeEffects() {
    var f = new Fixture();
    f.live.set(false);
    assertThatThrownBy(() -> f.owner.join(f.original, f.result, f.custody))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(f.repository, f.account);
    f.live.set(true);
    assertThatThrownBy(
            () ->
                f.scope.requireCurrent(
                    f.original,
                    f.result,
                    new ObservedDirectTextTransport("42", f.transport.proxyConnectionId()),
                    CLOCK))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                f.scope.requireCurrent(
                    f.original,
                    f.result,
                    new ObservedDirectTextTransport("00042", UUID.randomUUID()),
                    CLOCK))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                f.scope.requireCurrent(
                    f.original,
                    f.result,
                    f.transport,
                    Clock.fixed(NOW.plusSeconds(120), ZoneOffset.UTC)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static final class Fixture {
    final InitialGameplayLoginAttemptCoordinates original =
        new InitialGameplayLoginAttemptCoordinates(UUID.randomUUID(), UUID.randomUUID(), 7);
    final ObservedDirectTextTransport transport =
        new ObservedDirectTextTransport("00042", UUID.randomUUID());
    final CanonicalGameplayLoginResult result = mock(CanonicalGameplayLoginResult.class);
    final DirectTextAuthenticatedLobbyRepository repository =
        mock(DirectTextAuthenticatedLobbyRepository.class);
    final DirectTextWorldSelectionService selection = mock(DirectTextWorldSelectionService.class);
    final AccountClient account = mock(AccountClient.class);
    final AtomicBoolean live = new AtomicBoolean(true);
    final AtomicBoolean resetAfterCallback = new AtomicBoolean();
    final AtomicBoolean failClaimCommit = new AtomicBoolean();
    final List<String> events = new ArrayList<>();
    final DirectTextAuthenticatedLobbyScope scope;
    final DirectTextAuthenticatedLobbyJoin frozen;
    final AtomicReference<DirectTextAuthenticatedLobbyJoin> retained = new AtomicReference<>();
    final DirectTextAuthenticatedLobbyOwner owner;
    final DirectTextAuthenticatedLobbyCustody custody =
        new DirectTextAuthenticatedLobbyCustody() {
          @Override
          public <T> T withCurrent(
              InitialGameplayLoginAttemptCoordinates coordinates,
              CanonicalGameplayLoginResult borrowed,
              Function<ObservedDirectTextTransport, T> action) {
            if (!live.get() || !original.equals(coordinates) || borrowed != result)
              throw new IllegalStateException("Controlled custody unavailable");
            try {
              return action.apply(transport);
            } finally {
              if (resetAfterCallback.get()) live.set(false);
            }
          }
        };

    Fixture() {
      this(CLOCK);
    }

    Fixture(Clock ownerClock) {
      when(result.evidence()).thenReturn(evidence(original));
      when(result.compactJwtBytes())
          .thenAnswer(call -> "fixture-private-token".getBytes(StandardCharsets.US_ASCII));
      var target = target("Forest");
      scope =
          new DirectTextAuthenticatedLobbyScope(
              UUID.randomUUID(),
              InitialGameplayLoginContextState.installed(original, result),
              transport,
              target,
              DirectTextIssuedConnectScope.fromAccountResponse(
                  ACCOUNT.toString(),
                  DirectTextAuthenticatedLobbyScope.targetFor(target),
                  scopeResponse(),
                  CLOCK));
      frozen =
          new DirectTextAuthenticatedLobbyJoin(
              scope,
              DirectTextAuthenticatedLobbyJoin.requestFor(scope, UUID.randomUUID()),
              State.NEVER_DISPATCHED,
              Optional.empty());
      retained.set(frozen);
      when(repository.current(any(), any(), any(), any())).thenReturn(scope);
      when(repository.freezeCurrentJoin(any(), any(), any(), any()))
          .thenAnswer(
              call -> {
                events.add("FREEZE");
                return retained.get();
              });
      when(repository.readCommittedJoin(any()))
          .thenAnswer(
              call -> {
                events.add("READ");
                return retained.get();
              });
      when(repository.claimDispatch(any(), any(), any(), any()))
          .thenAnswer(
              call -> {
                events.add("CLAIM");
                if (retained.get().state() != State.NEVER_DISPATCHED) return false;
                retained.set(
                    new DirectTextAuthenticatedLobbyJoin(
                        scope, frozen.request(), State.DISPATCH_POSSIBLE, Optional.empty()));
                return true;
              });
      doAnswer(
              call -> {
                retained.set(
                    new DirectTextAuthenticatedLobbyJoin(
                        scope,
                        frozen.request(),
                        State.COMPLETED,
                        Optional.of(call.getArgument(2))));
                return null;
              })
          .when(repository)
          .recordPositiveReply(any(), any(), any());
      var transactions =
          new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
              return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
              events.add("COMMIT");
              if (failClaimCommit.get() && retained.get().state() == State.DISPATCH_POSSIBLE)
                throw new IllegalStateException("Lost commit reply");
            }

            @Override
            public void rollback(TransactionStatus status) {
              events.add("ROLLBACK");
            }
          };
      owner =
          new DirectTextAuthenticatedLobbyOwner(
              repository, selection, account, transactions, ownerClock);
    }
  }

  private static CanonicalGameplayLoginResult.NonSecretEvidence evidence(
      InitialGameplayLoginAttemptCoordinates original) {
    return evidence(
        original, NOW.minusSeconds(2).getEpochSecond(), NOW.minusSeconds(2).getEpochSecond());
  }

  private static CanonicalGameplayLoginResult.NonSecretEvidence evidence(
      InitialGameplayLoginAttemptCoordinates original, long issuedAt, long notBefore) {
    try {
      return new CanonicalGameplayLoginResult.NonSecretEvidence(
          ACCOUNT,
          original.requestId(),
          original.callerContextId(),
          UUID.randomUUID(),
          "a".repeat(64),
          GameSessionAccountDelegationProfile.PROFILE,
          issuedAt,
          notBefore,
          NOW.plusSeconds(120).getEpochSecond(),
          "1",
          "5",
          canonical(GameSessionAccountDelegationProfile.authorityTuple(1, 1)),
          canonical(
              List.of(
                  Map.of(
                      "outboxStreamKey",
                      "account:auth-authority:v1:account/" + ACCOUNT,
                      "outboxSequence",
                      "0"),
                  Map.of(
                      "outboxStreamKey",
                      "account:auth-authority:v1:issuer/firemud-account-service",
                      "outboxSequence",
                      "0"))),
          canonical(List.of()));
    } catch (Exception failure) {
      throw new IllegalStateException(failure);
    }
  }

  private static ByteString canonical(Object value) throws Exception {
    return ByteString.copyFrom(
        Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value)));
  }

  private static IssueDirectTextConnectScopeResponse scopeResponse() {
    return IssueDirectTextConnectScopeResponse.newBuilder()
        .setConnectScopeId("opaque-account-scope")
        .setEvaluatedAt(NOW.minusSeconds(1).toString())
        .setConnectScopeExpiresAt(NOW.plusSeconds(60).toString())
        .build();
  }

  private static JoinPublicProductionMembershipResponse positive() {
    return JoinPublicProductionMembershipResponse.newBuilder()
        .setSuccess(true)
        .setOutcomeCode("JOINED")
        .setAccountId(ACCOUNT.toString())
        .setTenantId("101")
        .setMembershipId("90")
        .setMembershipVersion(3)
        .setMembershipAuthorityGeneration(1)
        .build();
  }

  private static CanonicalPlayableTarget target(String display) {
    return new CanonicalPlayableTarget(
        "gameplay",
        "studio",
        UUID.randomUUID(),
        "forest",
        display,
        UUID.randomUUID(),
        "public",
        "Live",
        101,
        202,
        UUID.randomUUID(),
        "SHARED",
        UUID.randomUUID(),
        UUID.randomUUID(),
        303,
        1,
        7,
        "d".repeat(64),
        82,
        "original-open",
        "a".repeat(64),
        OriginKind.NO_PRIOR_POINTER,
        null,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "sha256:" + "c".repeat(64),
        27,
        "sha256:" + "f".repeat(64),
        Outcome.COMMITTED,
        false,
        NOW.minusSeconds(1),
        "ALLOW_NEW");
  }
}
