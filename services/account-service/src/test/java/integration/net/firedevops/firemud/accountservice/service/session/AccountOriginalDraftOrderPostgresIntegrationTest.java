package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.grpc.Context;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority;
import net.firedevops.firemud.common.authoring.AccountOriginalDraftOrderCredentials;
import net.firedevops.firemud.common.authoring.AccountOriginalDraftOrderGrpcCodec;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.test.TestContainerImages;
import org.jooq.ExecuteContext;
import org.jooq.impl.DefaultExecuteListener;
import org.jooq.impl.DefaultExecuteListenerProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Genuine Account issuance, PostgreSQL ordering and Coordination Redis; receiver contexts and
 * terminal owner evidence are explicit upstream test stipulations, not mTLS or terminal proof.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class AccountOriginalDraftOrderPostgresIntegrationTest {
  private static final Network NETWORK = Network.newNetwork();

  @Container
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  static final GenericContainer<?> redis =
      new GenericContainer<>(TestContainerImages.redis())
          .withNetwork(NETWORK)
          .withNetworkAliases("original-primary")
          .withExposedPorts(6379)
          .withCommand(
              "redis-server",
              "--bind",
              "0.0.0.0",
              "--protected-mode",
              "no",
              "--appendonly",
              "yes",
              "--appendfsync",
              "always");

  @Container
  static final GenericContainer<?> replica =
      new GenericContainer<>(TestContainerImages.redis())
          .withNetwork(NETWORK)
          .dependsOn(redis)
          .withCommand(
              "redis-server",
              "--bind",
              "0.0.0.0",
              "--protected-mode",
              "no",
              "--appendonly",
              "yes",
              "--appendfsync",
              "always",
              "--replicaof",
              "original-primary",
              "6379");

  @TempDir Path temporary;

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void originalCreatorExpiryAtFinalOwnerLockDeniesCurrentCallback(
      boolean signedCredential, @TempDir Path proofDirectory) throws Exception {
    assertOriginalCreatorTimeAtFinalOwnerLock(
        signedCredential, proofDirectory, Duration.ofMinutes(10));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void originalCreatorNotBeforeAtFinalOwnerLockDeniesCurrentCallback(
      boolean signedCredential, @TempDir Path proofDirectory) throws Exception {
    assertOriginalCreatorTimeAtFinalOwnerLock(
        signedCredential, proofDirectory, Duration.ofMinutes(-10));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void originalCreatorIssuedAtEqualityAtFinalOwnerLockAllowsCurrentCallback(
      boolean signedCredential, @TempDir Path proofDirectory) throws Exception {
    assertOriginalCreatorTimeAtFinalOwnerLock(signedCredential, proofDirectory, Duration.ZERO);
  }

  private void assertOriginalCreatorTimeAtFinalOwnerLock(
      boolean signedCredential, Path proofDirectory, Duration clockChange) throws Exception {
    var clock = new ActorClock();
    try (var fixture =
        new AccountControlUiOriginalOrderFixture(
            postgres.getJdbcUrl(),
            postgres.getUsername(),
            postgres.getPassword(),
            redis.getHost(),
            redis.getMappedPort(6379),
            proofDirectory,
            UUID.randomUUID(),
            clock)) {
      var issued = fixture.issueCreator();
      var f = issued.sources();
      String tokenHash =
          AccountControlUiCoordination.digest(
              "SHA-256", issued.compact().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
      var jti =
          f.tx(
              () ->
                  f.dsl
                      .fetchSingle(
                          "SELECT token_jti FROM account_control_ui_issuance_operations "
                              + "WHERE token_hash=? AND status='COMMITTED'",
                          tokenHash)
                      .get("token_jti", UUID.class));
      long issuedAt =
          java.util.Objects.requireNonNull(
              f.tx(
                  () ->
                      f.dsl
                          .fetchSingle(
                              "SELECT issued_at_epoch_second FROM account_control_ui_issuance_operations "
                                  + "WHERE token_jti=?",
                              jti)
                          .get("issued_at_epoch_second", Long.class)));
      var before =
          f.tx(
              () ->
                  f.dsl.fetchSingle(
                      "SELECT row_to_json(a)::TEXT AS payload, a.xmin::TEXT AS row_version "
                          + "FROM account_control_ui_issuance_operations a WHERE token_jti=?",
                      jti));
      var callback = new AtomicBoolean();
      var clockChanged = new AtomicBoolean();
      var originalListeners = f.dsl.configuration().executeListenerProviders();
      var timeChangeAtOwnerLock =
          new DefaultExecuteListenerProvider(
              new DefaultExecuteListener() {
                @Override
                public void executeEnd(ExecuteContext context) {
                  String sql = context.sql();
                  if (sql != null
                      && sql.contains("account_control_ui_issuance_operations")
                      && sql.contains("FOR UPDATE")
                      && clockChanged.compareAndSet(false, true)) {
                    // The real locking read has completed; simulate elapsed time at this precise
                    // boundary.
                    // This is deterministic temporal-boundary proof, not physical lock-contention
                    // proof.
                    if (clockChange.isZero()) {
                      clock.freezeAt(Instant.ofEpochSecond(issuedAt));
                    } else {
                      clock.advance(clockChange);
                    }
                  }
                }
              });
      var listeners = java.util.Arrays.copyOf(originalListeners, originalListeners.length + 1);
      listeners[originalListeners.length] = timeChangeAtOwnerLock;
      f.dsl.configuration().set(listeners);
      try {
        Throwable failure =
            catchThrowable(
                () -> {
                  if (signedCredential) {
                    issued
                        .actors()
                        .withCurrent(
                            issued.compact(),
                            f.tenant,
                            issued.environment(),
                            current -> {
                              callback.set(true);
                              return "callback invoked";
                            });
                  } else {
                    issued
                        .actors()
                        .withCurrentCommitted(
                            f.account.getAccountUuid(),
                            f.tenant,
                            jti,
                            issued.environment(),
                            current -> {
                              callback.set(true);
                              return "callback invoked";
                            });
                  }
                });
        assertThat(clockChanged).isTrue();
        assertThat(clock.instant().isBefore(Instant.ofEpochSecond(issuedAt)))
            .isEqualTo(clockChange.isNegative());
        assertThat(callback.get()).isEqualTo(clockChange.isZero());
        if (clockChange.isZero()) {
          assertThat(clock.instant()).isEqualTo(Instant.ofEpochSecond(issuedAt));
          assertThat(failure).isNull();
        } else {
          assertThat(failure).isInstanceOf(IllegalStateException.class);
        }
        assertThat(
                f.tx(
                    () ->
                        f.dsl.fetchSingle(
                            "SELECT row_to_json(a)::TEXT AS payload, a.xmin::TEXT AS row_version "
                                + "FROM account_control_ui_issuance_operations a WHERE token_jti=?",
                            jti)))
            .isEqualTo(before);
      } finally {
        f.dsl.configuration().set(originalListeners);
      }
    }
  }

  @ParameterizedTest
  @CsvSource({
    "true, ABSENT", "false, ABSENT", "true, REPLACED", "false, REPLACED",
    "true, RETAINED", "false, RETAINED", "true, UNTRUSTED", "false, UNTRUSTED",
    "true, SOURCE_FAILURE", "false, SOURCE_FAILURE", "true, SQL", "false, SQL",
    "true, SYNCHRONIZATION", "false, SYNCHRONIZATION"
  })
  void originalCreatorRequiresExactKeyInCurrentProtectedSnapshot(
      boolean signedCredential, String keyState, @TempDir Path proofDirectory) throws Exception {
    var clock = new ActorClock();
    try (var fixture =
        new AccountControlUiOriginalOrderFixture(
            postgres.getJdbcUrl(),
            postgres.getUsername(),
            postgres.getPassword(),
            redis.getHost(),
            redis.getMappedPort(6379),
            proofDirectory,
            UUID.randomUUID(),
            clock)) {
      var issued = fixture.issueCreator();
      var f = issued.sources();
      clock.freezeAt(clock.instant());
      var originalSnapshot = fixture.publicSource.load();
      var pin = fixture.publicSource.sourceIdentity();
      String tokenHash =
          AccountControlUiCoordination.digest(
              "SHA-256", issued.compact().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
      var original =
          f.tx(
              () ->
                  f.dsl.fetchSingle(
                      "SELECT token_jti, signer_receipt, row_to_json(a)::TEXT AS payload, "
                          + "a.xmin::TEXT AS row_version FROM account_control_ui_issuance_operations a "
                          + "WHERE token_hash=? AND status='COMMITTED'",
                      tokenHash));
      UUID jti = original.get("token_jti", UUID.class);
      var receipt =
          AccountControlUiIssuanceRepository.object(original.get("signer_receipt", byte[].class));
      String originalKid = (String) receipt.get("kid");
      assertThat(
              AccountJwtJwksTrustedSource.publicKeyFingerprint(originalSnapshot, pin, originalKid))
          .contains((String) receipt.get("publicKeyFingerprint"));
      byte[] changed = originalSnapshot.jwksBytes();
      if (java.util.Set.of("ABSENT", "REPLACED", "RETAINED").contains(keyState)) {
        var generator = java.security.KeyPairGenerator.getInstance("RSA");
        generator.initialize(3072);
        var replacement =
            AccountControlUiSignerFixture.publicJwk(
                "REPLACED".equals(keyState) ? originalKid : "other-accepted-key",
                (java.security.interfaces.RSAPublicKey) generator.generateKeyPair().getPublic());
        var keys = new java.util.ArrayList<Object>();
        keys.add(
            AccountControlUiIssuanceRepository.object(
                replacement.json().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        if ("RETAINED".equals(keyState)) {
          // Controlled retiring-key membership: another current key precedes the accepted original.
          // This is not a real signer-rotation or publication proof.
          var originalKeys =
              (List<?>)
                  AccountControlUiIssuanceRepository.object(originalSnapshot.jwksBytes())
                      .get("keys");
          keys.addAll(originalKeys);
        }
        changed = AccountControlUiAuthority.canonical(java.util.Map.of("keys", keys));
      }
      var observedPin =
          "UNTRUSTED".equals(keyState)
              ? new net.firedevops.firemud.common.security.AccountPublicJwksCache.SourceIdentity(
                  pin.environmentId(),
                  pin.clusterId(),
                  pin.clusterIncarnationUid(),
                  pin.namespace(),
                  pin.namespaceUid(),
                  UUID.randomUUID().toString(),
                  pin.bindingRevision(),
                  pin.apiServerOrigin(),
                  pin.servingCaSha256())
              : pin;
      var controlledCurrent =
          new net.firedevops.firemud.common.security.AccountPublicJwksCache.PublicJwksSnapshot(
              observedPin, changed);
      var protectedFailures = new java.util.concurrent.atomic.AtomicInteger();
      if ("SOURCE_FAILURE".equals(keyState)) {
        org.mockito.Mockito.doAnswer(
                invocation -> {
                  assertThat(
                          org.springframework.transaction.support.TransactionSynchronizationManager
                              .isActualTransactionActive())
                      .isFalse();
                  assertThat(
                          org.springframework.transaction.support.TransactionSynchronizationManager
                              .isSynchronizationActive())
                      .isFalse();
                  protectedFailures.incrementAndGet();
                  throw new IllegalStateException("controlled protected-source transport failure");
                })
            .when(fixture.signerFixture.client)
            .observe();
      }
      var reads = new java.util.concurrent.atomic.AtomicInteger();
      // Current snapshot membership is an explicit upstream stipulation here; this does not prove
      // a production key withdrawal or publication. Original issuance, SQL and Redis remain real.
      org.mockito.Mockito.doAnswer(
              invocation -> {
                assertThat(
                        org.springframework.transaction.support.TransactionSynchronizationManager
                            .isActualTransactionActive())
                    .isFalse();
                assertThat(
                        org.springframework.transaction.support.TransactionSynchronizationManager
                            .isSynchronizationActive())
                    .isFalse();
                reads.incrementAndGet();
                if ("SOURCE_FAILURE".equals(keyState)
                    || "SQL".equals(keyState)
                    || "SYNCHRONIZATION".equals(keyState)) {
                  return invocation.callRealMethod();
                }
                return controlledCurrent;
              })
          .when(fixture.publicSource)
          .load();
      var callback = new AtomicBoolean();
      java.util.function.Supplier<String> action =
          () -> {
            if (signedCredential) {
              return issued
                  .actors()
                  .withCurrent(
                      issued.compact(),
                      f.tenant,
                      issued.environment(),
                      current -> {
                        callback.set(true);
                        return "callback invoked";
                      });
            }
            return issued
                .actors()
                .withCurrentCommitted(
                    f.account.getAccountUuid(),
                    f.tenant,
                    jti,
                    issued.environment(),
                    current -> {
                      callback.set(true);
                      return "callback invoked";
                    });
          };
      Throwable failure =
          catchThrowable(
              () -> {
                if ("SQL".equals(keyState)) {
                  f.tx(action::get);
                } else if ("SYNCHRONIZATION".equals(keyState)) {
                  org.springframework.transaction.support.TransactionSynchronizationManager
                      .initSynchronization();
                  try {
                    action.get();
                  } finally {
                    org.springframework.transaction.support.TransactionSynchronizationManager
                        .clearSynchronization();
                  }
                } else {
                  action.get();
                }
              });
      var after =
          f.tx(
              () ->
                  f.dsl.fetchSingle(
                      "SELECT row_to_json(a)::TEXT AS payload, a.xmin::TEXT AS row_version "
                          + "FROM account_control_ui_issuance_operations a WHERE token_hash=?",
                      tokenHash));
      assertThat(after.get("payload", String.class))
          .isEqualTo(original.get("payload", String.class));
      assertThat(after.get("row_version", String.class))
          .isEqualTo(original.get("row_version", String.class));
      assertThat(callback.get()).isEqualTo("RETAINED".equals(keyState));
      if ("RETAINED".equals(keyState)) {
        assertThat(failure).isNull();
      } else {
        assertThat(failure).isInstanceOf(RuntimeException.class);
      }
      if ("SQL".equals(keyState) || "SYNCHRONIZATION".equals(keyState)) {
        assertThat(reads.get()).isZero();
      } else {
        assertThat(reads.get()).isGreaterThanOrEqualTo(1);
      }
      if ("SOURCE_FAILURE".equals(keyState)) {
        assertThat(protectedFailures.get()).isGreaterThanOrEqualTo(1);
      }
    }
  }

  @Test
  void lostOriginalOrderResponseRecoversAfterSignedCreatorExpiryButCannotCreateOrSubstitute()
      throws Exception {
    var clock = new ActorClock();
    try (var fixture =
        new AccountControlUiOriginalOrderFixture(
            postgres.getJdbcUrl(),
            postgres.getUsername(),
            postgres.getPassword(),
            redis.getHost(),
            redis.getMappedPort(6379),
            temporary,
            UUID.randomUUID(),
            clock)) {
      var issued = fixture.issueCreator();
      var f = issued.sources();
      var target =
          new DraftCommitBinding.TargetProof(
              f.tenant, UUID.randomUUID(), 1, "tenant-key", 2, "tenant-key", "NEW_GAME_ROW");
      var complete =
          DraftCommitBinding.create(
              target,
              UUID.randomUUID(),
              UUID.randomUUID(),
              "base",
              List.of(
                  new DraftCommitBinding.RevisionPayload(
                      "0",
                      UUID.randomUUID(),
                      DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                      "{}")),
              List.of(
                  new DraftCommitBinding.AffectedUnit(
                      DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                      "GAMEPLAY_RULES",
                      target.canonicalVersionId().toString(),
                      "GAMEPLAY_RULES",
                      "all",
                      "0")));
      var sources =
          f.tx(() -> f.authority.captureInitial(f.tenant, issued.environment())).sources();
      var original =
          new DraftAuthorizationFenceBinding(
                  UUID.randomUUID(),
                  complete.requestId(),
                  complete.commitId(),
                  UUID.randomUUID(),
                  f.account.getAccountUuid(),
                  f.tenant,
                  target.canonicalVersionId(),
                  complete.baseCommitId(),
                  "0",
                  complete.canonicalBytes(),
                  complete.canonicalBytes(),
                  complete.digest(),
                  sources)
              .withRequiredOwners();
      var request =
          AccountOriginalDraftOrderGrpcCodec.Request.create("test", original, issued.compact());
      var receiver =
          (AccountOriginalDraftOrderGrpcService) fixture.originalDraftOrderProducer("test");
      // The actual owner commits before the response is deliberately discarded by the caller.
      asPeer("test", "game-design-service", () -> invoke(receiver, request, true));
      fixture.assertOriginalDraftOrderPending(original);
      var before = f.tx(() -> f.fences.read(original));
      clock.advance(Duration.ofMinutes(10));
      assertThatThrownBy(() -> issued.actors().verifySigned(issued.compact()))
          .isInstanceOf(RuntimeException.class);

      asPeer("test", "game-design-service", () -> invoke(receiver, request));
      var owner = new AccountOriginalDraftOrderService(issued.actors(), "test");
      asPeer(
          "test",
          "game-design-service",
          () ->
              owner.claimWithEnvironmentCapture(
                  issued.compact(),
                  original,
                  () -> {
                    throw new AssertionError("Recovery recaptured environment");
                  }));
      assertThat(f.tx(() -> f.fences.read(original)).orderedAt()).isEqualTo(before.orderedAt());
      fixture.assertOriginalDraftOrderPending(original);

      var changed =
          new DraftAuthorizationFenceBinding(
                  original.operationId(),
                  original.requestId(),
                  original.commitId(),
                  original.fenceId(),
                  UUID.randomUUID(),
                  original.tenantId(),
                  original.versionId(),
                  original.baseCommitId(),
                  original.expectedDraftEpoch(),
                  original.gameDesignBinding(),
                  original.normalizedInput(),
                  original.inputDigest(),
                  original.sources())
              .withRequiredOwners();
      assertThatThrownBy(
              () ->
                  asPeer(
                      "test",
                      "game-design-service",
                      () -> owner.claim(issued.compact(), changed, issued.environment())))
          .isInstanceOf(RuntimeException.class);
      var absent =
          new DraftAuthorizationFenceBinding(
                  UUID.randomUUID(),
                  original.requestId(),
                  original.commitId(),
                  UUID.randomUUID(),
                  original.actorAccountId(),
                  original.tenantId(),
                  original.versionId(),
                  original.baseCommitId(),
                  original.expectedDraftEpoch(),
                  original.gameDesignBinding(),
                  original.normalizedInput(),
                  original.inputDigest(),
                  original.sources())
              .withRequiredOwners();
      assertThatThrownBy(
              () ->
                  asPeer(
                      "test",
                      "game-design-service",
                      () -> owner.claim(issued.compact(), absent, issued.environment())))
          .isInstanceOf(RuntimeException.class);
      assertThat(f.tx(() -> f.fences.readOriginalBinding(absent.operationId()))).isEmpty();
      for (String[] peer :
          List.of(
              new String[] {"other", "game-design-service"},
              new String[] {"test", "world-management-service"})) {
        assertThatThrownBy(
                () ->
                    asPeer(
                        peer[0],
                        peer[1],
                        () -> owner.claim(issued.compact(), original, issued.environment())))
            .isInstanceOf(io.grpc.StatusRuntimeException.class);
      }
      // Explicit upstream terminal stipulation tests settled denial; it never fabricates HELD.
      f.tx(
          () -> {
            f.fences.recordOwnerReadback(
                original,
                new DraftAuthorizationFenceBinding.OwnerReadback(
                    DraftAuthorizationFenceBinding.Owner.GAME_DESIGN,
                    DraftAuthorizationFenceBinding.Outcome.COMMITTED,
                    original.operationId(),
                    original.commitId(),
                    original.fenceId(),
                    original.inputDigest(),
                    original.canonicalBytes(),
                    new byte[] {1}));
            return null;
          });
      assertThatThrownBy(
              () ->
                  asPeer(
                      "test",
                      "game-design-service",
                      () -> owner.claim(issued.compact(), original, issued.environment())))
          .isInstanceOf(io.grpc.StatusRuntimeException.class);
      var reservedInput =
          DraftCommitBinding.create(
              target,
              UUID.randomUUID(),
              UUID.randomUUID(),
              complete.baseCommitId(),
              complete.revisions(),
              complete.affectedUnits());
      var reserved =
          new DraftAuthorizationFenceBinding(
                  UUID.randomUUID(),
                  reservedInput.requestId(),
                  reservedInput.commitId(),
                  UUID.randomUUID(),
                  original.actorAccountId(),
                  original.tenantId(),
                  original.versionId(),
                  original.baseCommitId(),
                  original.expectedDraftEpoch(),
                  reservedInput.canonicalBytes(),
                  reservedInput.canonicalBytes(),
                  reservedInput.digest(),
                  sources)
              .withRequiredOwners();
      f.tx(() -> f.fences.reserve(reserved));
      assertThatThrownBy(
              () ->
                  asPeer(
                      "test",
                      "game-design-service",
                      () -> owner.claim(issued.compact(), reserved, issued.environment())))
          .isInstanceOf(io.grpc.StatusRuntimeException.class);
      var change =
          new net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository
              .SourceChange(UUID.randomUUID(), sources, new byte[] {9});
      assertThat(f.tx(() -> f.fences.requestSourceChange(change))).isFalse();
      assertThat(f.tx(() -> f.fences.read(reserved)).ordering().name()).isEqualTo("REVOKE_ORDER");
      assertThatThrownBy(
              () ->
                  asPeer(
                      "test",
                      "game-design-service",
                      () -> owner.claim(issued.compact(), reserved, issued.environment())))
          .isInstanceOf(io.grpc.StatusRuntimeException.class);
    }
  }

  private static void invoke(
      AccountOriginalDraftOrderGrpcService receiver,
      AccountOriginalDraftOrderGrpcCodec.Request request) {
    invoke(receiver, request, false);
  }

  private static void invoke(
      AccountOriginalDraftOrderGrpcService receiver,
      AccountOriginalDraftOrderGrpcCodec.Request request,
      boolean discardResponse) {
    var context =
        Context.current()
            .withValue(
                AccountOriginalDraftOrderCredentials.CONTEXT_KEY,
                AccountOriginalDraftOrderCredentials.Credential.of(
                    request.originalCreatorCredential()));
    var prior = context.attach();
    try {
      var result =
          new AtomicReference<net.firedevops.firemud.account.v1.ClaimOriginalDraftResponse>();
      var failure = new AtomicReference<Throwable>();
      receiver.claimOriginalDraft(
          AccountOriginalDraftOrderGrpcCodec.toRequest(request),
          new io.grpc.stub.StreamObserver<>() {
            public void onNext(
                net.firedevops.firemud.account.v1.ClaimOriginalDraftResponse response) {
              if (!discardResponse) result.set(response);
            }

            public void onError(Throwable error) {
              failure.set(error);
            }

            public void onCompleted() {}
          });
      assertThat(failure.get()).isNull();
      if (discardResponse) assertThat(result.get()).isNull();
      else
        assertThat(
                AccountOriginalDraftOrderGrpcCodec.fromResponse(request, result.get())
                    .matches(request))
            .isTrue();
    } finally {
      context.detach(prior);
    }
  }

  private static void asPeer(String namespace, String workload, Runnable action) {
    var context =
        Context.current()
            .withValue(
                GrpcPeerIdentity.CONTEXT_KEY,
                GrpcPeerIdentity.parseUri("spiffe://firemud/ns/" + namespace + "/sa/" + workload)
                    .orElseThrow());
    var prior = context.attach();
    try {
      action.run();
    } finally {
      context.detach(prior);
    }
  }

  private static final class ActorClock extends Clock {
    private Duration offset = Duration.ZERO;
    private Instant frozen;

    void freezeAt(Instant value) {
      frozen = value;
    }

    void advance(Duration amount) {
      offset = offset.plus(amount);
    }

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return frozen == null ? Clock.offset(Clock.system(zone), offset) : Clock.fixed(frozen, zone);
    }

    public Instant instant() {
      return frozen == null ? Instant.now().plus(offset) : frozen;
    }
  }
}
