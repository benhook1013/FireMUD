package integration.net.firedevops.firemud.accountservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.SourceChange;
import net.firedevops.firemud.accountservice.dto.AccountSecurityStateMutationRequest;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountLoginAuthModes;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Event;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.EventEvidence;
import net.firedevops.firemud.accountservice.repository.AccountLogoutAllOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountPasswordResetOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountSecurityStateOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountSecurityStateOperationRepository.Capture;
import net.firedevops.firemud.accountservice.service.AccountAuthoritySourceEventReadback;
import net.firedevops.firemud.accountservice.service.AccountAuthoritySourceReader;
import net.firedevops.firemud.common.account.authority.AccountSecurityStateAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.AccountSecurityStateAuthorityEventV1Codec.AccountState;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Synthetic owner producer/correlation fixtures; these are not authenticated runtime mutations. */
@Testcontainers(disabledWithoutDocker = true)
class AccountSecurityStateOperationPostgresIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void pendingClaimAndExactRetryRetainOneOriginalCaptureWithoutSourceMutation() {
    Fixture fixture = fixture("79");
    Seed seed = seed(fixture);
    Pending pending = pending(fixture, seed, state(false), state(true), 0L, new byte[0]);
    var first = tx(fixture, () -> fixture.operations().claim(pending.request(), pending.capture()));
    var retry = tx(fixture, () -> fixture.operations().claim(pending.request(), pending.capture()));
    assertThat(first.claimed()).isTrue();
    assertThat(retry.claimed()).isFalse();
    assertThat(retry.operation().receipt()).isEmpty();
    assertThat(
            AccountSecurityStateOperationRepository.captureBytes(
                pending.request(), retry.operation().capture()))
        .isEqualTo(
            AccountSecurityStateOperationRepository.captureBytes(
                pending.request(), pending.capture()));
    assertThat(
            tx(
                fixture,
                () ->
                    fixture
                        .generations()
                        .read(AuthorityScope.account(seed.account().getAccountUuid()))))
        .isEqualTo(seed.source());
    assertThat(
            Objects.requireNonNull(
                    fixture
                        .dsl()
                        .fetchOne(
                            "SELECT email_verified FROM accounts WHERE id = ?",
                            seed.account().getId()),
                    "Expected persisted Account row")
                .get(0, Boolean.class))
        .isFalse();
    assertThat(fixture.dsl().fetchCount(DSL.table("account_authority_outbox_events"))).isZero();
  }

  @Test
  void originalCallerAndFamilyBindingsCannotBeChangedOnRetry() {
    Fixture fixture = fixture("79");
    Seed seed = seed(fixture);
    Pending pending = pending(fixture, seed, state(false), state(true), 0L, new byte[0]);
    tx(fixture, () -> fixture.operations().claim(pending.request(), pending.capture()));
    var request = pending.request();
    var changed =
        new AccountSecurityStateMutationRequest(
            request.requestId(),
            request.accountUuid(),
            correlation(UUID.randomUUID(), request.requestId()),
            request.expectedGeneration(),
            request.expectedSourceVersion(),
            request.mutationKinds(),
            request.desiredState());
    assertThatThrownBy(
            () -> tx(fixture, () -> fixture.operations().claim(changed, pending.capture())))
        .isInstanceOf(AccountSecurityStateOperationRepository.OperationConflictException.class);
    var wrongKinds =
        new AccountSecurityStateMutationRequest(
            UUID.randomUUID(),
            request.accountUuid(),
            request.callerProofBinding(),
            1L,
            1L,
            List.of("LOGIN_AUTH_MODES_CHANGED"),
            state(true));
    assertThatThrownBy(
            () -> tx(fixture, () -> fixture.operations().claim(wrongKinds, pending.capture())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("capture differs");
    assertThat(fixture.dsl().fetchCount(DSL.table("account_security_state_operations")))
        .isEqualTo(1);
  }

  @Test
  void concurrentExactClaimsSerializeToOneOriginalRequest() throws Exception {
    Fixture fixture = fixture("79");
    Seed seed = seed(fixture);
    Pending pending = pending(fixture, seed, state(false), state(true), 0L, new byte[0]);
    CountDownLatch ready = new CountDownLatch(2);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var tasks =
          List.of(1, 2).stream()
              .map(
                  ignored ->
                      executor.submit(
                          () ->
                              tx(
                                  fixture,
                                  () -> {
                                    ready.countDown();
                                    try {
                                      if (!ready.await(10, TimeUnit.SECONDS))
                                        throw new IllegalStateException(
                                            "Concurrent claims did not start");
                                    } catch (InterruptedException interrupted) {
                                      Thread.currentThread().interrupt();
                                      throw new IllegalStateException(interrupted);
                                    }
                                    return fixture
                                        .operations()
                                        .claim(pending.request(), pending.capture())
                                        .claimed();
                                  })))
              .toList();
      assertThat(
              List.of(
                  tasks.get(0).get(20, TimeUnit.SECONDS), tasks.get(1).get(20, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
    }
    assertThat(fixture.dsl().fetchCount(DSL.table("account_security_state_operations")))
        .isEqualTo(1);
  }

  @Test
  void exactExistingOutboxReceiptCompletesOnceAndHistoricalImagesSurviveLaterAdvance() {
    Fixture fixture = fixture("79");
    Seed seed = seed(fixture);
    Pending first = pending(fixture, seed, state(false), state(true), 0L, new byte[0]);
    tx(fixture, () -> fixture.operations().claim(first.request(), first.capture()));
    Event event = producerFixture(fixture, first, false);
    assertThat(sourceReader(fixture).readCurrent(seed.account().getAccountUuid()).latestEvent())
        .contains(event);
    tx(
        fixture,
        () -> {
          var latest =
              sourceReadback(fixture)
                  .requireCurrentLatest(
                      new AccountRepository(fixture.dsl())
                          .findById(seed.account().getId())
                          .orElseThrow(),
                      fixture.generations().read(seed.source().scope()));
          assertThat(latest.latestEvent()).contains(event);
          return null;
        });
    var retained =
        tx(
            fixture,
            () -> fixture.operations().findByRequestId(first.request().requestId()).orElseThrow());
    assertThat(retained.receipt().orElseThrow().event()).isEqualTo(event);
    ScopeState current =
        tx(
            fixture,
            () ->
                fixture
                    .generations()
                    .read(AuthorityScope.account(seed.account().getAccountUuid())));
    Seed newer = new Seed(seed.account(), current);
    AccountState after = new AccountState(true, List.of("PASSWORD"), List.of(), "ACTIVE");
    Pending second = pending(fixture, newer, state(true), after, 1L, event.payload());
    tx(fixture, () -> fixture.operations().claim(second.request(), second.capture()));
    producerFixture(fixture, second, false);
    assertThat(
            sourceReader(fixture)
                .readCommittedEvent(seed.account().getAccountUuid(), 1L)
                .requestedEvent())
        .isEqualTo(event);
    tx(
        fixture,
        () -> {
          var account =
              new AccountRepository(fixture.dsl()).findById(seed.account().getId()).orElseThrow();
          var source = fixture.generations().read(seed.source().scope());
          sourceReadback(fixture).requireRetainedEvent(account, event, source);
          assertThat(sourceReadback(fixture).requireCurrentLatest(account, source).outboxSequence())
              .isEqualTo(2L);
          return null;
        });
    var retry = tx(fixture, () -> fixture.operations().claim(first.request(), second.capture()));
    assertThat(retry.claimed()).isFalse();
    assertThat(retry.operation().receipt().orElseThrow().event().payload())
        .isEqualTo(event.payload());
    assertThat(retry.operation().capture().beforeState()).isEqualTo(state(false));
    assertThat(retry.operation().request().desiredState()).isEqualTo(state(true));
    assertThat(fixture.dsl().fetchCount(DSL.table("account_authority_outbox_events"))).isEqualTo(2);
  }

  @Test
  void lawfulPrivateFenceAdvanceKeepsLatestSourceEventAndOriginalReceiptUnchanged() {
    Fixture fixture = fixture("79");
    Seed seed = seed(fixture);
    Pending pending = pending(fixture, seed, state(false), state(true), 0L, new byte[0]);
    tx(fixture, () -> fixture.operations().claim(pending.request(), pending.capture()));
    Event event = producerFixture(fixture, pending, false);
    var original =
        tx(
            fixture,
            () ->
                fixture.operations().findByRequestId(pending.request().requestId()).orElseThrow());
    var originalRow =
        fixture
            .dsl()
            .fetchOne(
                "SELECT * FROM account_security_state_operations WHERE request_id = ?",
                pending.request().requestId());
    ScopeState advanced =
        tx(
            fixture,
            () -> {
              new AccountRepository(fixture.dsl())
                  .findByIdForUpdate(seed.account().getId())
                  .orElseThrow();
              var source = fixture.generations().read(seed.source().scope());
              // Fixture-only private-fence CAS follows the existing guarded counters, without a
              // source advance.
              assertThat(
                      fixture
                          .dsl()
                          .execute(
                              "UPDATE account_authority_issuance_fences "
                                  + "SET issuance_fence = issuance_fence + 1, source_version = source_version + 1, updated_at = CURRENT_TIMESTAMP "
                                  + "WHERE account_uuid = ? AND issuance_fence = ? AND source_version = ?",
                              seed.account().getAccountUuid(),
                              source.issuanceFence().value(),
                              source.issuanceFence().sourceVersion()))
                  .isEqualTo(1);
              return fixture.generations().read(seed.source().scope());
            });
    var snapshot = sourceReader(fixture).readCurrent(seed.account().getAccountUuid());
    assertThat(snapshot.latestEvent()).contains(event);
    assertThat(snapshot.sourceState()).isEqualTo(advanced);
    assertThat(advanced.generation())
        .isEqualTo(original.receipt().orElseThrow().sourceState().generation());
    assertThat(advanced.sourceVersion())
        .isEqualTo(original.receipt().orElseThrow().sourceState().sourceVersion());
    assertThat(advanced.issuanceFence().value())
        .isEqualTo(original.receipt().orElseThrow().sourceState().issuanceFence().value() + 1);
    var retained =
        tx(
            fixture,
            () ->
                fixture.operations().findByRequestId(pending.request().requestId()).orElseThrow());
    assertThat(retained.receipt()).isEqualTo(original.receipt());
    assertThat(
            fixture
                .dsl()
                .fetchOne(
                    "SELECT * FROM account_security_state_operations WHERE request_id = ?",
                    pending.request().requestId()))
        .isEqualTo(originalRow);
    assertThat(fixture.dsl().fetchCount(DSL.table("account_authority_outbox_events"))).isEqualTo(1);
  }

  @Test
  void lateReceiptFailureRollsBackEntireFixtureProducerButRetainsOriginalWaitingIntent() {
    Fixture fixture = fixture("79");
    Seed seed = seed(fixture);
    Pending pending = pending(fixture, seed, state(false), state(true), 0L, new byte[0]);
    tx(fixture, () -> fixture.operations().claim(pending.request(), pending.capture()));
    assertThatThrownBy(() -> producerFixture(fixture, pending, true))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            tx(
                    fixture,
                    () ->
                        fixture
                            .operations()
                            .findByRequestId(pending.request().requestId())
                            .orElseThrow())
                .receipt())
        .isEmpty();
    assertThat(
            tx(
                fixture,
                () ->
                    fixture
                        .generations()
                        .read(AuthorityScope.account(seed.account().getAccountUuid()))))
        .isEqualTo(seed.source());
    assertThat(fixture.dsl().fetchCount(DSL.table("account_authority_outbox_events"))).isZero();
    assertThat(
            Objects.requireNonNull(
                    fixture
                        .dsl()
                        .fetchOne(
                            "SELECT email_verified FROM accounts WHERE id = ?",
                            seed.account().getId()),
                    "Expected persisted Account row after rollback")
                .get(0, Boolean.class))
        .isFalse();
    assertThat(
            Objects.requireNonNull(
                    fixture
                        .dsl()
                        .fetchOne(
                            "SELECT status FROM account_draft_authorization_source_changes WHERE change_id = ?",
                            pending.capture().sourceChange().changeId()),
                    "Expected original V57 source-change row after rollback")
                .get(0, String.class))
        .isEqualTo("WAITING");
  }

  @Test
  void requestCaptureResultsDeletesAndTruncationRemainGuarded() {
    Fixture fixture = fixture("79");
    Seed seed = seed(fixture);
    Pending pending = pending(fixture, seed, state(false), state(true), 0L, new byte[0]);
    tx(fixture, () -> fixture.operations().claim(pending.request(), pending.capture()));
    for (String sql :
        List.of(
            "UPDATE account_security_state_operations SET request_digest = repeat('b',64)",
            "UPDATE account_security_state_operations SET expected_generation = 2",
            "DELETE FROM account_security_state_operations",
            "TRUNCATE account_security_state_operations")) {
      assertThatThrownBy(() -> fixture.dsl().execute(sql))
          .isInstanceOf(org.jooq.exception.DataAccessException.class);
    }
    producerFixture(fixture, pending, false);
    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .execute("UPDATE account_security_state_operations SET event_id = 'changed'"))
        .isInstanceOf(org.jooq.exception.DataAccessException.class);
    assertThat(fixture.dsl().fetchCount(DSL.table("account_security_state_operations")))
        .isEqualTo(1);
  }

  @Test
  void substitutedEventStateCannotBecomeTheOriginalReceipt() {
    Fixture fixture = fixture("79");
    Seed seed = seed(fixture);
    Pending pending = pending(fixture, seed, state(false), state(true), 0L, new byte[0]);
    tx(fixture, () -> fixture.operations().claim(pending.request(), pending.capture()));
    assertThatThrownBy(
            () ->
                tx(
                    fixture,
                    () -> {
                      var wrong =
                          AccountSecurityStateAuthorityEventV1Codec.seal(
                              AccountSecurityStateMutationRequest.eventPreimage(
                                  pending.request().requestId(),
                                  pending.request().accountUuid(),
                                  "2",
                                  "2",
                                  "1",
                                  pending.request().mutationKinds(),
                                  state(false)));
                      var event =
                          fixture
                              .outbox()
                              .append(
                                  stream(seed),
                                  pending.request().requestId().toString(),
                                  sequence ->
                                      new EventEvidence(
                                          wrong.eventId(),
                                          wrong.eventDigest(),
                                          wrong.canonicalJsonUtf8()));
                      fixture
                          .operations()
                          .complete(
                              pending.request(),
                              event,
                              new ScopeState(
                                  seed.source().scope(),
                                  2L,
                                  2L,
                                  new AccountAuthorityGenerationRepository.IssuanceFence(
                                      seed.account().getAccountUuid(), 2L, 2L)),
                              1L);
                      return null;
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("receipt differs");
    assertThat(fixture.dsl().fetchCount(DSL.table("account_authority_outbox_events"))).isZero();
  }

  @Test
  void corruptPositiveCheckpointMetadataAndPayloadDenyBeforeNewIntentOrSourceMutation() {
    for (String corruption :
        List.of(
            "event_id = 'substituted'",
            "request_id = 'substituted'",
            "event_digest = 'sha256:' || repeat('b',64)",
            "payload = convert_to('{}','UTF8')")) {
      Fixture fixture = fixture("79");
      Seed seed = seed(fixture);
      Pending first = pending(fixture, seed, state(false), state(true), 0L, new byte[0]);
      tx(fixture, () -> fixture.operations().claim(first.request(), first.capture()));
      Event event = producerFixture(fixture, first, false);
      ScopeState current =
          tx(
              fixture,
              () ->
                  fixture
                      .generations()
                      .read(AuthorityScope.account(seed.account().getAccountUuid())));
      Pending second =
          pending(
              fixture,
              new Seed(seed.account(), current),
              state(true),
              new AccountState(true, List.of("PASSWORD"), List.of(), "ACTIVE"),
              1L,
              event.payload());
      // Deliberately corrupt immutable storage as a superuser fixture, not a production operation.
      fixture.dsl().execute("ALTER TABLE account_authority_outbox_events DISABLE TRIGGER USER");
      fixture.dsl().execute("UPDATE account_authority_outbox_events SET " + corruption);
      fixture.dsl().execute("ALTER TABLE account_authority_outbox_events ENABLE TRIGGER USER");
      assertThatThrownBy(
              () ->
                  tx(fixture, () -> fixture.operations().claim(second.request(), second.capture())))
          .isInstanceOf(RuntimeException.class);
      assertThat(fixture.dsl().fetchCount(DSL.table("account_security_state_operations")))
          .isEqualTo(1);
      assertThat(
              tx(
                  fixture,
                  () ->
                      fixture
                          .generations()
                          .read(AuthorityScope.account(seed.account().getAccountUuid()))))
          .isEqualTo(current);
    }
  }

  @Test
  void retainedReceiptCannotLeadRegressedCurrentAuthorityOrCheckpoint() {
    for (String table :
        List.of("account_authority_generations", "account_authority_outbox_streams")) {
      Fixture fixture = fixture("79");
      Seed seed = seed(fixture);
      Pending pending = pending(fixture, seed, state(false), state(true), 0L, new byte[0]);
      tx(fixture, () -> fixture.operations().claim(pending.request(), pending.capture()));
      producerFixture(fixture, pending, false);
      fixture.dsl().execute("ALTER TABLE " + table + " DISABLE TRIGGER USER");
      fixture
          .dsl()
          .execute(
              table.equals("account_authority_generations")
                  ? "UPDATE account_authority_generations SET generation = 1, source_version = 1"
                  : "UPDATE account_authority_outbox_streams SET last_sequence = 0");
      fixture.dsl().execute("ALTER TABLE " + table + " ENABLE TRIGGER USER");
      assertThatThrownBy(
              () ->
                  tx(
                      fixture,
                      () -> fixture.operations().findByRequestId(pending.request().requestId())))
          .isInstanceOf(RuntimeException.class);
    }
  }

  @Test
  void actualReaderDeniesUncommittedOperationAndChangedCurrentPoststate() {
    Fixture missing = fixture("79");
    Seed seed = seed(missing);
    Pending pending = pending(missing, seed, state(false), state(true), 0L, new byte[0]);
    tx(missing, () -> missing.operations().claim(pending.request(), pending.capture()));
    Event event = producerFixture(missing, pending, false, false);
    assertThatThrownBy(
            () ->
                tx(
                    missing,
                    () ->
                        sourceReadback(missing)
                            .requireCurrentLatest(
                                new AccountRepository(missing.dsl())
                                    .findById(seed.account().getId())
                                    .orElseThrow(),
                                missing.generations().read(seed.source().scope()))))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                tx(
                    missing,
                    () -> {
                      sourceReadback(missing)
                          .requireRetainedEvent(
                              new AccountRepository(missing.dsl())
                                  .findById(seed.account().getId())
                                  .orElseThrow(),
                              event,
                              missing.generations().read(seed.source().scope()));
                      return null;
                    }))
        .isInstanceOf(IllegalStateException.class);

    Fixture changed = fixture("79");
    Seed other = seed(changed);
    Pending original = pending(changed, other, state(false), state(true), 0L, new byte[0]);
    tx(changed, () -> changed.operations().claim(original.request(), original.capture()));
    producerFixture(changed, original, false);
    // Deliberately change current state without its lawful producer to test fail-closed readback.
    changed
        .dsl()
        .execute(
            "UPDATE accounts SET email_verified = false WHERE id = ?", other.account().getId());
    assertThatThrownBy(
            () ->
                tx(
                    changed,
                    () ->
                        sourceReadback(changed)
                            .requireCurrentLatest(
                                new AccountRepository(changed.dsl())
                                    .findById(other.account().getId())
                                    .orElseThrow(),
                                changed.generations().read(other.source().scope()))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("poststate differs");
  }

  @Test
  void forwardMigrationRetainsOriginalAccountAuthorityAndRoleImagesWithoutBackfill() {
    Fixture fixture = fixture("64");
    Seed seed = seed(fixture);
    var before =
        fixture
            .dsl()
            .fetch(
                "SELECT account_uuid, account_uuid_source_numeric_id, account_uuid_provenance, email_verified, login_auth_modes, lifecycle_state, password_hash FROM accounts");
    var authority = fixture.dsl().fetch("SELECT * FROM account_authority_generations");
    var roles = fixture.dsl().fetch("SELECT * FROM account_global_role_sources");
    migrate(fixture.dataSource(), fixture.schema(), "65");
    assertThat(
            fixture
                .dsl()
                .fetch(
                    "SELECT account_uuid, account_uuid_source_numeric_id, account_uuid_provenance, email_verified, login_auth_modes, lifecycle_state, password_hash FROM accounts"))
        .isEqualTo(before);
    assertThat(fixture.dsl().fetch("SELECT * FROM account_authority_generations"))
        .isEqualTo(authority);
    assertThat(fixture.dsl().fetch("SELECT * FROM account_global_role_sources")).isEqualTo(roles);
    assertThat(fixture.dsl().fetchCount(DSL.table("account_security_state_operations"))).isZero();
    assertThat(seed.account().getAccountUuid()).isNotNull();
  }

  private Event producerFixture(Fixture fixture, Pending pending, boolean lateFailure) {
    return producerFixture(fixture, pending, lateFailure, true);
  }

  private Event producerFixture(
      Fixture fixture, Pending pending, boolean lateFailure, boolean retainReceipt) {
    return tx(
        fixture,
        () -> {
          // Fixture-only sole mutation writer. Storage itself never calls these operations.
          if (!fixture.fences().sourceMutationPermitted(pending.capture().sourceChange()))
            throw new IllegalStateException("Fixture source is not settled");
          fixture
              .dsl()
              .execute(
                  "UPDATE accounts SET email_verified = ?, login_auth_modes = ?, lifecycle_state = ? WHERE account_uuid = ?",
                  pending.request().desiredState().emailVerified(),
                  AccountLoginAuthModes.normalize(
                      String.join(",", pending.request().desiredState().loginAuthModes())),
                  pending
                      .request()
                      .desiredState()
                      .lifecycleState()
                      .toLowerCase(java.util.Locale.ROOT),
                  pending.request().accountUuid());
          ScopeState advanced =
              fixture
                  .generations()
                  .advance(
                      pending.capture().sourceState(),
                      pending.capture().sourceState().issuanceFence());
          Event event =
              fixture
                  .outbox()
                  .append(
                      stream(pending.request().accountUuid()),
                      pending.request().requestId().toString(),
                      sequence -> {
                        var sealed =
                            AccountSecurityStateAuthorityEventV1Codec.seal(
                                AccountSecurityStateMutationRequest.eventPreimage(
                                    pending.request().requestId(),
                                    pending.request().accountUuid(),
                                    Long.toString(advanced.generation()),
                                    Long.toString(advanced.sourceVersion()),
                                    Long.toString(sequence),
                                    pending.request().mutationKinds(),
                                    pending.request().desiredState()));
                        return new EventEvidence(
                            sealed.eventId(), sealed.eventDigest(), sealed.canonicalJsonUtf8());
                      });
          fixture.fences().markSourceCommitted(pending.capture().sourceChange());
          if (retainReceipt) fixture.operations().complete(pending.request(), event, advanced, 1L);
          if (lateFailure)
            throw new IllegalStateException("Late fixture failure after receipt completion");
          return event;
        });
  }

  private Pending pending(
      Fixture fixture,
      Seed seed,
      AccountState before,
      AccountState after,
      long checkpointSequence,
      byte[] checkpointPayload) {
    UUID requestId = UUID.randomUUID();
    UUID account = seed.account().getAccountUuid();
    var request =
        new AccountSecurityStateMutationRequest(
            requestId,
            account,
            correlation(account, requestId),
            seed.source().generation(),
            seed.source().sourceVersion(),
            AccountSecurityStateOperationRepository.detectedKinds(before, after),
            after);
    var draft =
        new Capture(
            seed.account().getId(),
            seed.account().getAccountUuidProvenance(),
            before,
            seed.source(),
            checkpointSequence,
            checkpointPayload,
            1L,
            null);
    byte[] captureBytes = AccountSecurityStateOperationRepository.captureBytes(request, draft);
    var source =
        new SourceEvidence(
            SourceKind.ACCOUNT,
            account.toString(),
            Long.toString(seed.source().generation()),
            Long.toString(seed.source().sourceVersion()),
            stream(account),
            Long.toString(checkpointSequence),
            captureBytes);
    var change = new SourceChange(UUID.randomUUID(), List.of(source), captureBytes);
    var capture =
        new Capture(
            draft.accountId(),
            draft.provenance(),
            before,
            seed.source(),
            checkpointSequence,
            checkpointPayload,
            1L,
            change);
    tx(
        fixture,
        () -> {
          fixture.fences().requestSourceChange(change);
          return null;
        });
    return new Pending(request, capture);
  }

  private AccountAuthoritySourceEventReadback sourceReadback(Fixture fixture) {
    return new AccountAuthoritySourceEventReadback(
        fixture.outbox(),
        new AccountPasswordResetOperationRepository(fixture.dsl()),
        new AccountLogoutAllOperationRepository(fixture.dsl()),
        fixture.operations());
  }

  private AccountAuthoritySourceReader sourceReader(Fixture fixture) {
    return new AccountAuthoritySourceReader(
        new AccountRepository(fixture.dsl()),
        fixture.generations(),
        fixture.outbox(),
        sourceReadback(fixture),
        fixture.transaction().getTransactionManager());
  }

  private Fixture fixture(String version) {
    String schema = "security_state_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setUrl(
        postgres.getJdbcUrl()
            + (postgres.getJdbcUrl().contains("?") ? "&" : "?")
            + "currentSchema="
            + schema);
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    migrate(dataSource, schema, version);
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    return new Fixture(
        schema,
        dataSource,
        dsl,
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
        new AccountAuthorityGenerationRepository(dsl),
        new AccountAuthorityOutboxRepository(dsl),
        new DraftAuthorizationFenceRepository(dsl),
        new AccountSecurityStateOperationRepository(dsl));
  }

  private Seed seed(Fixture fixture) {
    return tx(
        fixture,
        () -> {
          Account account = new Account();
          String id = UUID.randomUUID().toString().substring(0, 12);
          account.setUsername("security-" + id);
          account.setEmail(id + "@example.test");
          account.setPasswordHash("synthetic-verifier");
          account = new AccountRepository(fixture.dsl()).save(account);
          ScopeState source =
              fixture.generations().initialize(AuthorityScope.account(account.getAccountUuid()));
          return new Seed(account, source);
        });
  }

  private void migrate(DriverManagerDataSource dataSource, String schema, String version) {
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .target(version)
        .load()
        .migrate();
  }

  private static <T> T tx(Fixture fixture, Supplier<T> work) {
    return fixture.transaction().execute(status -> work.get());
  }

  private static AccountState state(boolean verified) {
    return new AccountState(verified, List.of("EMAIL_OTP", "PASSWORD"), List.of(), "ACTIVE");
  }

  private static String stream(Seed seed) {
    return stream(seed.account().getAccountUuid());
  }

  private static String stream(UUID account) {
    return "account:auth-authority:v1:account/" + account;
  }

  private static byte[] correlation(UUID actor, UUID operation) {
    return ("{\"actorAccountUuid\":\""
            + actor
            + "\",\"ownerEvidenceDigest\":\"sha256:"
            + "a".repeat(64)
            + "\",\"ownerOperationId\":\""
            + operation
            + "\",\"schemaVersion\":\"account-security-state-caller-correlation/v1\"}")
        .getBytes(StandardCharsets.UTF_8);
  }

  private record Fixture(
      String schema,
      DriverManagerDataSource dataSource,
      DSLContext dsl,
      TransactionTemplate transaction,
      AccountAuthorityGenerationRepository generations,
      AccountAuthorityOutboxRepository outbox,
      DraftAuthorizationFenceRepository fences,
      AccountSecurityStateOperationRepository operations) {}

  private record Seed(Account account, ScopeState source) {}

  private record Pending(AccountSecurityStateMutationRequest request, Capture capture) {}
}
