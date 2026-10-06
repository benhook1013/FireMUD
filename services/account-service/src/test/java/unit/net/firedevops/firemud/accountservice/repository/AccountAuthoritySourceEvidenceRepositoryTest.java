package unit.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.IssuanceFence;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeKind;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.common.account.authority.AccountAuthoritySourceEventV1Codec;
import net.firedevops.firemud.common.account.authority.AccountAuthoritySourceEventV1Codec.IssuerEvent;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class AccountAuthoritySourceEvidenceRepositoryTest {
  @Test
  void closedPreparationRequiresWritableOwnerTransactionAndExactAccountScope() {
    var issuer =
        new AccountAuthoritySourceEvidenceRepository.CurrentSourceEvidence(
            AuthorityScope.issuer(ISSUER_ID),
            1L,
            1L,
            null,
            zeroCheckpoint("issuer/" + ISSUER_ID),
            Optional.empty(),
            "ISSUER_SCOPE_INSERT",
            null,
            null,
            7L,
            null);
    assertThatThrownBy(() -> repository.prepareClosedAccountAdvance(ACCOUNT_ID, issuer))
        .isInstanceOf(
            AccountAuthoritySourceEvidenceRepository.SourceEvidenceUnavailableException.class);
    org.springframework.transaction.support.TransactionSynchronizationManager
        .setActualTransactionActive(true);
    try {
      assertThatThrownBy(() -> repository.prepareClosedAccountAdvance(ACCOUNT_ID, issuer))
          .isInstanceOf(
              AccountAuthoritySourceEvidenceRepository.SourceEvidenceUnavailableException.class);
      assertThatThrownBy(() -> repository.prepareClosedAccountAdvance(new UUID(0L, 0L), null))
          .isInstanceOf(
              AccountAuthoritySourceEvidenceRepository.SourceEvidenceUnavailableException.class);
      org.springframework.transaction.support.TransactionSynchronizationManager
          .setCurrentTransactionReadOnly(true);
      assertThatThrownBy(
              () -> repository.prepareClosedAccountAdvance(ACCOUNT_ID, accountBaseline()))
          .isInstanceOf(
              AccountAuthoritySourceEvidenceRepository.SourceEvidenceUnavailableException.class);
      verifyNoInteractions(dsl);
    } finally {
      org.springframework.transaction.support.TransactionSynchronizationManager.clear();
    }
  }

  @Test
  void closedPreparationCannotAdvanceFromCallerBaselineWhenOwnedSourceIsMissing() {
    DSLContext ownerDsl = mock(DSLContext.class);
    org.mockito.Mockito.doAnswer(
            invocation -> {
              String sql = invocation.getArgument(0);
              if (sql.contains("FROM account_authority_generations"))
                return record("generation", 1L, "source_version", 1L);
              if (sql.contains("FROM account_authority_issuance_fences"))
                return record("issuance_fence", 1L, "source_version", 1L);
              return null;
            })
        .when(ownerDsl)
        .fetchOne(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(Object[].class));
    var sourceOwner =
        new AccountAuthoritySourceEvidenceRepository(
            ownerDsl,
            new AccountAuthorityGenerationRepository(ownerDsl),
            new AccountAuthorityOutboxRepository(ownerDsl));
    org.springframework.transaction.support.TransactionSynchronizationManager
        .setActualTransactionActive(true);
    try {
      assertThatThrownBy(
              () -> sourceOwner.prepareClosedAccountAdvance(ACCOUNT_ID, accountBaseline()))
          .isInstanceOf(
              AccountAuthoritySourceEvidenceRepository.SourceEvidenceUnavailableException.class);
      org.mockito.Mockito.verify(ownerDsl, org.mockito.Mockito.never())
          .fetchOne(
              org.mockito.ArgumentMatchers.startsWith("UPDATE"),
              org.mockito.ArgumentMatchers.any(Object[].class));
    } finally {
      org.springframework.transaction.support.TransactionSynchronizationManager.clear();
    }
  }

  private static AccountAuthoritySourceEvidenceRepository.CurrentSourceEvidence accountBaseline() {
    return new AccountAuthoritySourceEvidenceRepository.CurrentSourceEvidence(
        AuthorityScope.account(ACCOUNT_ID),
        1L,
        1L,
        new IssuanceFence(ACCOUNT_ID, 1L, 1L),
        zeroCheckpoint("account/" + ACCOUNT_ID),
        Optional.empty(),
        "ACCOUNT_REPOSITORY_INSERT",
        9L,
        "ACCOUNT_REPOSITORY_INSERT",
        7L,
        7L);
  }

  @Test
  void closedReceiptStorageAndSourceConstructorsDoNotRecursivelyMintOrReadEvidence() {
    DSLContext ownerDsl = mock(DSLContext.class);
    new net.firedevops.firemud.accountservice.repository.AccountSecurityStateOperationRepository(
        ownerDsl);
    new AccountAuthoritySourceEvidenceRepository(
        ownerDsl,
        new AccountAuthorityGenerationRepository(ownerDsl),
        new AccountAuthorityOutboxRepository(ownerDsl));
    verifyNoInteractions(ownerDsl);
  }

  @Test
  void canonicalIssuerReadRequiresActualWritableOwnerTransactionBeforeAccess() {
    assertThatThrownBy(() -> repository.readCurrentCanonicalIssuerSource(ISSUER_ID))
        .isInstanceOf(
            AccountAuthoritySourceEvidenceRepository.SourceEvidenceUnavailableException.class);
    verifyNoInteractions(dsl);
    org.springframework.transaction.support.TransactionSynchronizationManager
        .setActualTransactionActive(true);
    org.springframework.transaction.support.TransactionSynchronizationManager
        .setCurrentTransactionReadOnly(true);
    try {
      assertThatThrownBy(() -> repository.readCurrentCanonicalIssuerSource(ISSUER_ID))
          .isInstanceOf(
              AccountAuthoritySourceEvidenceRepository.SourceEvidenceUnavailableException.class);
      verifyNoInteractions(dsl);
    } finally {
      org.springframework.transaction.support.TransactionSynchronizationManager.clear();
    }
  }

  @Test
  void closedAppendActuallyReadsImmutableReceiptAndDeniesMissingOwnerEvidence() {
    DSLContext ownerDsl = mock(DSLContext.class);
    var ownerOutbox = mock(AccountAuthorityOutboxRepository.class);
    var source =
        record(
            "scope_kind",
            "ACCOUNT",
            "account_uuid",
            ACCOUNT_ID,
            "baseline_generation",
            1L,
            "baseline_source_version",
            1L,
            "baseline_issuance_fence",
            1L,
            "initialization_provenance",
            "ACCOUNT_REPOSITORY_INSERT",
            "account_source_numeric_id",
            9L,
            "account_uuid_provenance",
            "ACCOUNT_REPOSITORY_INSERT",
            "initialization_transaction_id",
            7L,
            "account_repository_insert_transaction_id",
            7L,
            "current_generation",
            1L,
            "current_source_version",
            1L,
            "current_issuance_fence",
            1L,
            "current_issuance_fence_source_version",
            1L,
            "last_outbox_sequence",
            0L);
    var account =
        record(
            "id",
            9L,
            "account_uuid",
            ACCOUNT_ID,
            "account_uuid_source_numeric_id",
            9L,
            "account_uuid_provenance",
            "ACCOUNT_REPOSITORY_INSERT",
            "account_repository_insert_transaction_id",
            7L,
            "password_hash",
            "retained-verifier");
    org.mockito.Mockito.doAnswer(
            invocation -> {
              String sql = invocation.getArgument(0);
              if (sql.contains("FROM account_authority_source_records")) return source;
              if (sql.contains("FROM account_authority_outbox_streams"))
                return record("last_sequence", 1L);
              if (sql.contains("count(*)")) return record("event_count", 1L);
              if (sql.contains("FROM accounts")) return account;
              return null;
            })
        .when(ownerDsl)
        .fetchOne(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(Object[].class));
    String stream = "account:auth-authority:v1:account/" + ACCOUNT_ID;
    String request = "account-password-reset-request-v1:" + "a".repeat(64);
    var wire =
        net.firedevops.firemud.common.account.authority.PasswordResetAuthorityEventV1Codec.seal(
            Map.ofEntries(
                Map.entry(
                    "schemaVersion",
                    net.firedevops.firemud.common.account.authority
                        .PasswordResetAuthorityEventV1Codec.SCHEMA_VERSION),
                Map.entry(
                    "eventType",
                    net.firedevops.firemud.common.account.authority
                        .PasswordResetAuthorityEventV1Codec.EVENT_TYPE),
                Map.entry("eventId", "account-password-reset-event-v1:" + "a".repeat(64)),
                Map.entry("requestId", request),
                Map.entry("accountId", ACCOUNT_ID.toString()),
                Map.entry("sourceScope", "account/" + ACCOUNT_ID),
                Map.entry("outboxStreamKey", stream),
                Map.entry("outboxSequence", "1"),
                Map.entry("accountAuthorityGeneration", "2"),
                Map.entry("sourceVersion", "2"),
                Map.entry(
                    "accountSecurityCutoff",
                    Map.of(
                        "accountAuthorityGeneration",
                        "2",
                        "outboxStreamKey",
                        stream,
                        "outboxSequence",
                        "1"))));
    var pending =
        new AccountAuthorityOutboxRepository.Event(
            stream, request, 1L, wire.eventId(), wire.eventDigest(), wire.canonicalJsonUtf8());
    org.mockito.Mockito.doReturn(Optional.of(pending)).when(ownerOutbox).findEvent(stream, 1L);
    org.mockito.Mockito.doReturn(Optional.of(pending)).when(ownerOutbox).findEvent(stream, request);
    org.mockito.Mockito.doReturn(
            Optional.of(
                new AccountAuthorityOutboxRepository.Checkpoint(
                    stream, 1L, pending.eventId(), pending.eventDigest())))
        .when(ownerOutbox)
        .readCheckpoint(stream);
    var sourceOwner =
        new AccountAuthoritySourceEvidenceRepository(
            ownerDsl, new AccountAuthorityGenerationRepository(ownerDsl), ownerOutbox);
    var expected =
        new AccountAuthorityGenerationRepository.ScopeState(
            AuthorityScope.account(ACCOUNT_ID), 1L, 1L, new IssuanceFence(ACCOUNT_ID, 1L, 1L));
    var advanced =
        new AccountAuthorityGenerationRepository.ScopeState(
            AuthorityScope.account(ACCOUNT_ID), 2L, 2L, new IssuanceFence(ACCOUNT_ID, 2L, 2L));
    org.springframework.transaction.support.TransactionSynchronizationManager
        .setActualTransactionActive(true);
    try {
      assertThatThrownBy(
              () -> sourceOwner.advanceClosedAccountHead(ACCOUNT_ID, expected, advanced, pending))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("no immutable operation receipt");
      org.mockito.Mockito.verify(ownerDsl)
          .fetchOne(
              org.mockito.ArgumentMatchers.contains(
                  "FROM account_password_reset_operation_receipts"),
              org.mockito.ArgumentMatchers.any(Object[].class));
      org.mockito.Mockito.verify(ownerDsl, org.mockito.Mockito.never())
          .fetchOne(
              org.mockito.ArgumentMatchers.startsWith("UPDATE"),
              org.mockito.ArgumentMatchers.any(Object[].class));
      org.mockito.Mockito.verify(ownerDsl, org.mockito.Mockito.never())
          .execute(
              org.mockito.ArgumentMatchers.anyString(),
              org.mockito.ArgumentMatchers.any(Object[].class));
    } finally {
      org.springframework.transaction.support.TransactionSynchronizationManager.clear();
    }
  }

  private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final String ISSUER_ID = "firemud-account-service";
  private static final String ISSUER_STREAM_KEY = "account:auth-authority:v1:issuer/" + ISSUER_ID;

  private final DSLContext dsl = mock(DSLContext.class);
  private final AccountAuthorityGenerationRepository generations =
      new AccountAuthorityGenerationRepository(dsl);
  private final AccountAuthorityOutboxRepository outbox = new AccountAuthorityOutboxRepository(dsl);
  private final AccountAuthoritySourceEvidenceRepository repository =
      new AccountAuthoritySourceEvidenceRepository(dsl, generations, outbox);

  @Test
  void sequenceZeroIsAnExplicitProvenanceBearingCheckpointWithoutSyntheticEventIdentity() {
    var checkpoint =
        new AccountAuthoritySourceEvidenceRepository.SourceCheckpoint(
            "account:auth-authority:v1:issuer/firemud-account-service",
            0L,
            Optional.empty(),
            Optional.empty());
    var issuer =
        new AccountAuthoritySourceEvidenceRepository.CurrentSourceEvidence(
            AuthorityScope.issuer("firemud-account-service"),
            1L,
            1L,
            null,
            checkpoint,
            Optional.empty(),
            "ISSUER_SCOPE_INSERT",
            null,
            null,
            1L,
            null);

    assertThat(issuer.checkpoint()).isEqualTo(checkpoint);
    assertThat(issuer.initializationProvenance()).isEqualTo("ISSUER_SCOPE_INSERT");
    assertThat(issuer.accountSecurityCutoff()).isEmpty();
  }

  @Test
  void rejectsPartialOrFabricatedSequenceZeroCheckpoints() {
    assertThatThrownBy(
            () ->
                new AccountAuthoritySourceEvidenceRepository.SourceCheckpoint(
                    "account:auth-authority:v1:issuer/firemud-account-service",
                    0L,
                    Optional.of("synthetic-event"),
                    Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AccountAuthoritySourceEvidenceRepository.SourceCheckpoint(
                    "account:auth-authority:v1:issuer/firemud-account-service",
                    1L,
                    Optional.empty(),
                    Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void accountSnapshotRequiresSameAccountFenceAndExactScopeKinds() {
    var fence = new IssuanceFence(ACCOUNT_ID, 1L, 1L);
    var issuer =
        new AccountAuthoritySourceEvidenceRepository.CurrentSourceEvidence(
            AuthorityScope.issuer("firemud-account-service"),
            1L,
            1L,
            null,
            zeroCheckpoint("issuer/firemud-account-service"),
            Optional.empty(),
            "ISSUER_SCOPE_INSERT",
            null,
            null,
            1L,
            null);
    var account =
        new AccountAuthoritySourceEvidenceRepository.CurrentSourceEvidence(
            AuthorityScope.account(ACCOUNT_ID),
            1L,
            1L,
            fence,
            zeroCheckpoint("account/" + ACCOUNT_ID),
            Optional.empty(),
            "ACCOUNT_REPOSITORY_INSERT",
            9L,
            "ACCOUNT_REPOSITORY_INSERT",
            1L,
            1L);

    assertThat(
            new AccountAuthoritySourceEvidenceRepository.IssuerAccountSourceSnapshot(
                issuer, account, fence))
        .satisfies(
            snapshot -> {
              assertThat(snapshot.account().issuanceFence()).isEqualTo(fence);
              assertThat(snapshot.canonicalAccountProjection().accountId())
                  .isEqualTo(ACCOUNT_ID.toString());
              assertThat(snapshot.canonicalAccountProjection().outboxSequence()).isEqualTo("0");
              assertThat(snapshot.canonicalAccountProjection().sourceEvent()).isEmpty();
              assertThat(snapshot.canonicalIssuerProjection().issuerId())
                  .isEqualTo("firemud-account-service");
              assertThat(snapshot.canonicalIssuerProjection().lastAppliedSourceOutboxSequence())
                  .isEqualTo("0");
              assertThat(snapshot.canonicalIssuerProjection().sourceEvent()).isEmpty();
            });
    UUID otherAccount = UUID.randomUUID();
    var mismatchedProjection =
        new net.firedevops.firemud.accountservice.service.AccountGenerationProjection(
            otherAccount.toString(),
            "1",
            "1",
            "account:auth-authority:v1:account/" + otherAccount,
            "0",
            Optional.empty());
    assertThatThrownBy(
            () ->
                new AccountAuthoritySourceEvidenceRepository.IssuerAccountSourceSnapshot(
                    issuer, account, fence, mismatchedProjection))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Canonical Account source projection differs from its snapshot");
    assertThatThrownBy(
            () ->
                new AccountAuthoritySourceEvidenceRepository.IssuerAccountSourceSnapshot(
                    account, account, fence))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AccountAuthoritySourceEvidenceRepository.IssuerAccountSourceSnapshot(
                    issuer, account, new IssuanceFence(UUID.randomUUID(), 1L, 1L)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void issuerEnrollmentRequiresValidIdentityAndMandatoryOwnerTransaction()
      throws ReflectiveOperationException {
    assertThatThrownBy(() -> repository.initializeIssuerIfAbsent(" "))
        .isInstanceOf(IllegalArgumentException.class);
    assertMandatory("initializeIssuerIfAbsent", String.class);
    assertMandatory("initializeFreshAccount", AccountRepository.FreshAccountInsert.class);
    assertMandatory("recordAccountUpdate", AccountRepository.AccountUpdateEvidence.class);

    verifyNoInteractions(dsl);
  }

  @Test
  void sourceEvidenceIsRestrictedToIssuerAndAccountScopes() {
    assertThat(ScopeKind.valueOf("ISSUER")).isEqualTo(ScopeKind.ISSUER);
    assertThat(ScopeKind.valueOf("ACCOUNT")).isEqualTo(ScopeKind.ACCOUNT);
    assertThatThrownBy(
            () ->
                new AccountAuthoritySourceEvidenceRepository.CurrentSourceEvidence(
                    AuthorityScope.account(ACCOUNT_ID),
                    0L,
                    1L,
                    new IssuanceFence(ACCOUNT_ID, 1L, 1L),
                    zeroCheckpoint("account/" + ACCOUNT_ID),
                    Optional.empty(),
                    "ACCOUNT_REPOSITORY_INSERT",
                    9L,
                    "ACCOUNT_REPOSITORY_INSERT",
                    1L,
                    1L))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void currentSourceReadVerifiesOnlyTheExactLatestEvent() {
    long sequence = 17L;
    var event = issuerEvent(sequence, "issuer-event-17", "issuer-request-17");
    var fixture =
        issuerReadFixture(
            sequence,
            sequence + 1L,
            sequence,
            issuerSourceRecord(sequence, event.eventId(), event.eventDigest()),
            Optional.of(stored(event)));

    var current = fixture.repository().initializeIssuerIfAbsent(ISSUER_ID);

    assertThat(current.checkpoint().sequence()).isEqualTo(sequence);
    assertThat(current.checkpoint().sourceEventId()).contains(event.eventId());
    assertThat(current.checkpoint().sourceEventDigest()).contains(event.eventDigest());
    org.mockito.Mockito.verify(fixture.outbox()).findEvent(ISSUER_STREAM_KEY, sequence);
    org.mockito.Mockito.verifyNoMoreInteractions(fixture.outbox());
    org.mockito.Mockito.verify(fixture.dsl(), org.mockito.Mockito.never())
        .fetchOne(
            org.mockito.ArgumentMatchers.contains("count(*)"),
            org.mockito.ArgumentMatchers.any(Object[].class));
  }

  @Test
  void currentSourceReadRejectsMalformedLatestEvent() {
    long sequence = 4L;
    var malformed =
        new AccountAuthorityOutboxRepository.Event(
            ISSUER_STREAM_KEY,
            "issuer-request-4",
            sequence,
            "issuer-event-4",
            "not-a-canonical-digest",
            "{".getBytes(StandardCharsets.UTF_8));
    var fixture =
        issuerReadFixture(
            sequence,
            sequence + 1L,
            sequence,
            issuerSourceRecord(sequence, malformed.eventId(), malformed.eventDigest()),
            Optional.of(malformed));

    assertThatThrownBy(() -> fixture.repository().initializeIssuerIfAbsent(ISSUER_ID))
        .isInstanceOf(
            AccountAuthoritySourceEvidenceRepository.SourceEvidenceUnavailableException.class);
    org.mockito.Mockito.verify(fixture.outbox()).findEvent(ISSUER_STREAM_KEY, sequence);
    org.mockito.Mockito.verifyNoMoreInteractions(fixture.outbox());
  }

  @Test
  void currentSourceReadRejectsMissingLatestEvent() {
    long sequence = 8L;
    var fixture =
        issuerReadFixture(
            sequence,
            sequence + 1L,
            sequence,
            issuerSourceRecord(sequence, "issuer-event-8", "sha256:" + "a".repeat(64)),
            Optional.empty());

    assertThatThrownBy(() -> fixture.repository().initializeIssuerIfAbsent(ISSUER_ID))
        .isInstanceOf(
            AccountAuthoritySourceEvidenceRepository.SourceEvidenceUnavailableException.class);
    org.mockito.Mockito.verify(fixture.outbox()).findEvent(ISSUER_STREAM_KEY, sequence);
    org.mockito.Mockito.verifyNoMoreInteractions(fixture.outbox());
  }

  @Test
  void currentSourceReadRejectsAuthorityCounterMismatchBeforeReadingAnEvent() {
    long sequence = 11L;
    var fixture =
        issuerReadFixture(
            sequence,
            sequence + 2L,
            sequence,
            issuerSourceRecord(sequence, "issuer-event-11", "sha256:" + "b".repeat(64)),
            Optional.empty());

    assertThatThrownBy(() -> fixture.repository().initializeIssuerIfAbsent(ISSUER_ID))
        .isInstanceOf(
            AccountAuthoritySourceEvidenceRepository.SourceEvidenceUnavailableException.class);
    verifyNoInteractions(fixture.outbox());
  }

  @Test
  void currentSourceReadAcceptsOnlyTheExplicitProvenanceBearingSequenceZeroBaseline() {
    var fixture =
        issuerReadFixture(0L, 1L, 0L, issuerSourceRecord(0L, null, null), Optional.empty());

    var current = fixture.repository().initializeIssuerIfAbsent(ISSUER_ID);

    assertThat(current.checkpoint().sequence()).isZero();
    assertThat(current.checkpoint().sourceEventId()).isEmpty();
    assertThat(current.checkpoint().sourceEventDigest()).isEmpty();
    assertThat(current.initializationProvenance()).isEqualTo("ISSUER_SCOPE_INSERT");
    verifyNoInteractions(fixture.outbox());
  }

  @Test
  void existingIssuerWithoutSourceBaselineFailsClosedWithoutRetainedBackfill() {
    var fixture = issuerReadFixture(0L, 1L, 0L, null, Optional.empty());

    assertThatThrownBy(() -> fixture.repository().initializeIssuerIfAbsent(ISSUER_ID))
        .isInstanceOf(
            AccountAuthoritySourceEvidenceRepository.SourceEvidenceUnavailableException.class);
    org.mockito.Mockito.verify(fixture.dsl(), org.mockito.Mockito.never())
        .execute(
            org.mockito.ArgumentMatchers.contains("INSERT INTO account_authority_outbox_streams"),
            org.mockito.ArgumentMatchers.any(Object[].class));
    verifyNoInteractions(fixture.outbox());
  }

  private static IssuerEvent issuerEvent(long sequence, String eventId, String requestId) {
    long currentVersion = sequence + 1L;
    return AccountAuthoritySourceEventV1Codec.sealIssuer(
        new AccountAuthoritySourceEventV1Codec.IssuerPreimage(
            eventId,
            requestId,
            ISSUER_STREAM_KEY,
            Long.toString(sequence),
            ISSUER_ID,
            Long.toString(currentVersion),
            Long.toString(currentVersion),
            "ISSUER_SECURITY_CHANGE"));
  }

  private static AccountAuthorityOutboxRepository.Event stored(IssuerEvent event) {
    return new AccountAuthorityOutboxRepository.Event(
        event.outboxStreamKey(),
        event.requestId(),
        Long.parseLong(event.outboxSequence()),
        event.eventId(),
        event.eventDigest(),
        event.canonicalJsonUtf8());
  }

  private static IssuerReadFixture issuerReadFixture(
      long sourceSequence,
      long authorityGeneration,
      long streamSequence,
      Record sourceRecord,
      Optional<AccountAuthorityOutboxRepository.Event> latestEvent) {
    DSLContext sourceDsl = mock(DSLContext.class);
    Record generationRecord =
        record("generation", authorityGeneration, "source_version", authorityGeneration);
    Record streamRecord = record("last_sequence", streamSequence);
    org.mockito.Mockito.doAnswer(
            invocation -> {
              String sql = invocation.getArgument(0);
              if (sql.contains("FROM account_authority_generations")) return generationRecord;
              if (sql.contains("FROM account_authority_source_records")) return sourceRecord;
              if (sql.contains("FROM account_authority_outbox_streams")) return streamRecord;
              return null;
            })
        .when(sourceDsl)
        .fetchOne(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(Object[].class));

    AccountAuthorityOutboxRepository sourceOutbox = mock(AccountAuthorityOutboxRepository.class);
    org.mockito.Mockito.doReturn(latestEvent)
        .when(sourceOutbox)
        .findEvent(ISSUER_STREAM_KEY, sourceSequence);
    var sourceRepository =
        new AccountAuthoritySourceEvidenceRepository(
            sourceDsl, new AccountAuthorityGenerationRepository(sourceDsl), sourceOutbox);
    return new IssuerReadFixture(sourceDsl, sourceOutbox, sourceRepository);
  }

  private static Record issuerSourceRecord(
      long sequence, String latestEventId, String latestEventDigest) {
    Map<String, Object> fields = new HashMap<>();
    fields.put("scope_kind", "ISSUER");
    fields.put("issuer_id", ISSUER_ID);
    fields.put("baseline_generation", 1L);
    fields.put("baseline_source_version", 1L);
    fields.put("initialization_provenance", "ISSUER_SCOPE_INSERT");
    fields.put("initialization_transaction_id", 1L);
    fields.put("current_generation", sequence + 1L);
    fields.put("current_source_version", sequence + 1L);
    fields.put("last_outbox_sequence", sequence);
    if (latestEventId != null) fields.put("last_event_id", latestEventId);
    if (latestEventDigest != null) fields.put("last_event_digest", latestEventDigest);
    return record(fields);
  }

  private static Record record(Object... entries) {
    Map<String, Object> fields = new HashMap<>();
    for (int index = 0; index < entries.length; index += 2) {
      fields.put((String) entries[index], entries[index + 1]);
    }
    return record(fields);
  }

  private static Record record(Map<String, Object> fields) {
    return mock(
        Record.class,
        invocation -> {
          if (invocation.getMethod().getName().equals("get")
              && invocation.getArguments().length > 0) {
            return fields.get(invocation.getArgument(0));
          }
          return null;
        });
  }

  private record IssuerReadFixture(
      DSLContext dsl,
      AccountAuthorityOutboxRepository outbox,
      AccountAuthoritySourceEvidenceRepository repository) {}

  private static AccountAuthoritySourceEvidenceRepository.SourceCheckpoint zeroCheckpoint(
      String suffix) {
    return new AccountAuthoritySourceEvidenceRepository.SourceCheckpoint(
        "account:auth-authority:v1:" + suffix, 0L, Optional.empty(), Optional.empty());
  }

  private static void assertMandatory(String methodName, Class<?> parameterType)
      throws ReflectiveOperationException {
    Transactional transactional =
        AccountAuthoritySourceEvidenceRepository.class
            .getMethod(methodName, parameterType)
            .getAnnotation(Transactional.class);
    assertThat(transactional).isNotNull();
    assertThat(transactional.propagation()).isEqualTo(Propagation.MANDATORY);
  }
}
