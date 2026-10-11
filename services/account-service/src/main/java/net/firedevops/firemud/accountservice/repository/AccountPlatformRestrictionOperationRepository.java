package net.firedevops.firemud.accountservice.repository;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountPlatformRestrictionBirthSource;
import net.firedevops.firemud.accountservice.dto.AccountPlatformRestrictionBirthSource.Category;
import net.firedevops.firemud.accountservice.dto.AccountPlatformRestrictionBirthSource.CategorySource;
import net.firedevops.firemud.accountservice.dto.AccountPlatformRestrictionMutationRequest;
import net.firedevops.firemud.accountservice.dto.AccountPlatformRestrictionMutationRequest.RestrictionState;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.IssuanceFence;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Event;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.EventEvidence;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.CurrentSourceEvidence;
import net.firedevops.firemud.common.account.authority.AccountSecurityStateAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.AccountSecurityStateAuthorityEventV1Codec.AccountRestrictionAuthorityEvent;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Account restriction receipt reader and closed persistence draft. This class is not registered as
 * a Spring bean and exposes no RPC or HTTP surface. Request fields are correlation only. The
 * mutation command remains mechanically denied until the exact Account policy/recovery or Logging &
 * Admin intent/authentication proof boundary is implemented and passed as typed evidence.
 */
public final class AccountPlatformRestrictionOperationRepository {
  private static final String OPERATIONS = "account_platform_restriction_operations";
  private static final String REVISIONS = "account_platform_restriction_revisions";
  private static final String CURRENT = "account_platform_restriction_current_projections";
  private static final String ACCOUNT_STREAM_PREFIX =
      AccountSecurityStateAuthorityEventV1Codec.EVENT_STREAM_PREFIX + "account/";

  private final DSLContext dsl;
  private final AccountAuthorityGenerationRepository generations;
  private final AccountAuthorityOutboxRepository outbox;
  private final AccountAuthoritySourceEvidenceRepository sourceEvidence;
  private final AccountPlatformRestrictionBirthRepository birthSource;

  public AccountPlatformRestrictionOperationRepository(
      DSLContext dsl,
      AccountAuthorityGenerationRepository generations,
      AccountAuthorityOutboxRepository outbox,
      AccountAuthoritySourceEvidenceRepository sourceEvidence,
      AccountPlatformRestrictionBirthRepository birthSource) {
    this.dsl = Objects.requireNonNull(dsl, "DSLContext is required");
    this.generations = Objects.requireNonNull(generations, "Account generations are required");
    this.outbox = Objects.requireNonNull(outbox, "Account authority outbox is required");
    this.sourceEvidence =
        Objects.requireNonNull(sourceEvidence, "Account source evidence is required");
    this.birthSource = Objects.requireNonNull(birthSource, "restriction birth source is required");
  }

  /**
   * No caller can authorize a restriction by supplying request/correlation fields. Keep the owner
   * command closed until its category-specific intent and authenticated owner proof receiver
   * exists; in particular, this denial does not treat a source digest as such proof.
   */
  public Operation commit(AccountPlatformRestrictionMutationRequest request) {
    throw new OwnerAuthorizationUnresolvedException();
  }

  /**
   * Persistence sequence retained for integration once an authoritative typed proof receiver
   * exists. It is private and has no caller today; do not connect it using caller correlation.
   */
  private Operation persistWithoutOwnerProof(AccountPlatformRestrictionMutationRequest request) {
    requireWritableTransaction();
    Objects.requireNonNull(request, "restriction mutation request is required");
    AccountIdentity identity = lockAccount(request.accountUuid());

    Optional<Operation> prior = findByRequestId(dsl, request.requestId(), false);
    if (prior.isPresent()) {
      Operation committed = prior.orElseThrow();
      requireSameRequest(committed, request);
      requireIdentity(committed, identity);
      return committed;
    }

    AuthorityScope scope = AuthorityScope.account(request.accountUuid());
    ScopeState currentAuthority = generations.read(scope);
    CurrentSourceEvidence currentSource = sourceEvidence.readCurrentSource(scope, currentAuthority);
    AccountPlatformRestrictionBirthSource birth =
        birthSource.readCurrentBirthSource(request.accountUuid());
    if (identity.accountId() != birth.accountSourceNumericId()
        || !birth.currentAuthority().equals(currentAuthority)) {
      throw new RestrictionSourceUnavailableException();
    }
    requireExpectedAccountSource(request, currentAuthority, currentSource);

    Optional<CurrentProjection> currentProjection =
        readCurrentProjection(dsl, request.accountUuid(), request.category(), true);
    if (currentProjection.isPresent()) {
      requireExpectedCategory(request, currentProjection.orElseThrow());
    } else {
      // The birth result supplies only the historical predecessor identity and next revision.
      // Its NONRESTRICTED value is never returned as current state or used to authorize absence.
      CategorySource birthCategory = birthCategory(birth, request.category());
      if (request.expectedCategoryRevision() != birthCategory.revision()
          || request.expectedEnforcementEpoch() != birthCategory.enforcementEpoch()
          || !request.expectedResultId().equals(birthCategory.resultId())) {
        throw new StaleRestrictionOperationException();
      }
    }

    long revision = increment(request.expectedCategoryRevision());
    long enforcementEpoch = increment(request.expectedEnforcementEpoch());
    UUID resultId = UUID.randomUUID();
    IssuanceFence expectedFence = currentAuthority.issuanceFence();
    ScopeState advanced = generations.advanceForSourceEvidence(currentAuthority, expectedFence);
    long nextSequence = increment(currentSource.checkpoint().sequence());
    if (advanced.generation() != increment(request.expectedAccountGeneration())
        || advanced.sourceVersion() != increment(request.expectedAccountSourceVersion())
        || advanced.generation() != increment(nextSequence)
        || advanced.issuanceFence() == null
        || advanced.issuanceFence().value() != increment(currentAuthority.issuanceFence().value())
        || advanced.issuanceFence().sourceVersion()
            != increment(currentAuthority.issuanceFence().sourceVersion())) {
      throw new RestrictionSourceUnavailableException();
    }

    String stream = ACCOUNT_STREAM_PREFIX + request.accountUuid();
    AccountRestrictionAuthorityEvent[] candidate = new AccountRestrictionAuthorityEvent[1];
    Event appended =
        outbox.append(
            stream,
            request.requestId().toString(),
            sequence -> {
              if (sequence != nextSequence) throw new RestrictionSourceUnavailableException();
              AccountRestrictionAuthorityEvent event =
                  request.sealEvent(
                      Long.toString(advanced.generation()),
                      Long.toString(advanced.sourceVersion()),
                      Long.toString(sequence),
                      resultId,
                      revision,
                      enforcementEpoch);
              candidate[0] = event;
              return new EventEvidence(
                  event.eventId(), event.eventDigest(), event.canonicalJsonUtf8());
            });
    AccountRestrictionAuthorityEvent event =
        Objects.requireNonNull(candidate[0], "restriction source event was not produced");
    requireAppendedEvent(appended, event, stream, request.requestId().toString(), nextSequence);

    int storedOperation =
        dsl.execute(
            "INSERT INTO "
                + OPERATIONS
                + " (request_id, account_uuid, account_id, account_provenance, category, "
                + "request_payload, request_digest, expected_account_generation, "
                + "expected_account_source_version, expected_fence, expected_fence_source_version, "
                + "expected_category_revision, "
                + "expected_enforcement_epoch, expected_result_id, desired_state, source_kind, "
                + "source_request_id, source_digest, result_id, result_category_revision, "
                + "result_enforcement_epoch, result_state, result_generation, result_source_version, "
                + "result_fence, result_fence_source_version, outbox_stream_key, event_sequence, "
                + "event_id, event_digest, event_payload, status) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'COMMITTED')",
            request.requestId(),
            request.accountUuid(),
            identity.accountId(),
            identity.provenance().name(),
            request.category().storageValue(),
            request.canonicalRequestBytes(),
            request.requestDigest(),
            request.expectedAccountGeneration(),
            request.expectedAccountSourceVersion(),
            expectedFence.value(),
            expectedFence.sourceVersion(),
            request.expectedCategoryRevision(),
            request.expectedEnforcementEpoch(),
            request.expectedResultId(),
            request.desiredState().name(),
            request.sourceKind().name(),
            request.sourceRequestId(),
            request.sourceDigest(),
            resultId,
            revision,
            enforcementEpoch,
            request.desiredState().name(),
            advanced.generation(),
            advanced.sourceVersion(),
            advanced.issuanceFence().value(),
            advanced.issuanceFence().sourceVersion(),
            stream,
            appended.outboxSequence(),
            appended.eventId(),
            appended.eventDigest(),
            appended.payload());
    if (storedOperation != 1) throw new RestrictionSourceUnavailableException();

    int storedRevision =
        dsl.execute(
            "INSERT INTO "
                + REVISIONS
                + " (account_uuid, category, revision, enforcement_epoch, result_id, request_id, "
                + "restriction_state, source_kind, source_request_id, source_digest, request_digest, "
                + "result_generation, result_source_version, outbox_stream_key, outbox_sequence, "
                + "event_id, event_digest) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            request.accountUuid(),
            request.category().storageValue(),
            revision,
            enforcementEpoch,
            resultId,
            request.requestId(),
            request.desiredState().name(),
            request.sourceKind().name(),
            request.sourceRequestId(),
            request.sourceDigest(),
            request.requestDigest(),
            advanced.generation(),
            advanced.sourceVersion(),
            stream,
            appended.outboxSequence(),
            appended.eventId(),
            appended.eventDigest());
    if (storedRevision != 1) throw new RestrictionSourceUnavailableException();

    int projected;
    if (currentProjection.isPresent()) {
      projected =
          dsl.execute(
              "UPDATE "
                  + CURRENT
                  + " SET revision = ?, enforcement_epoch = ?, result_id = ?, restriction_state = ?, "
                  + "request_id = ? WHERE account_uuid = ? AND category = ? AND revision = ? "
                  + "AND enforcement_epoch = ? AND result_id = ?",
              revision,
              enforcementEpoch,
              resultId,
              request.desiredState().name(),
              request.requestId(),
              request.accountUuid(),
              request.category().storageValue(),
              request.expectedCategoryRevision(),
              request.expectedEnforcementEpoch(),
              request.expectedResultId());
    } else {
      projected =
          dsl.execute(
              "INSERT INTO "
                  + CURRENT
                  + " (account_uuid, category, revision, enforcement_epoch, result_id, restriction_state, request_id) "
                  + "VALUES (?, ?, ?, ?, ?, ?, ?)",
              request.accountUuid(),
              request.category().storageValue(),
              revision,
              enforcementEpoch,
              resultId,
              request.desiredState().name(),
              request.requestId());
    }
    if (projected != 1) throw new StaleRestrictionOperationException();

    sourceEvidence.advanceClosedAccountHead(
        request.accountUuid(), currentAuthority, advanced, appended);
    Operation committed = findByRequestId(dsl, request.requestId(), false).orElseThrow();
    requireSameRequest(committed, request);
    return committed;
  }

  /**
   * Reads explicit current state only; a missing projection is unavailable, never NONRESTRICTED.
   */
  public CurrentProjection readCurrent(UUID accountUuid, Category category) {
    requireTransaction();
    lockAccount(accountUuid);
    return readCurrentProjection(dsl, accountUuid, category, true)
        .orElseThrow(() -> new RestrictionProjectionUnavailableException());
  }

  /** Shared committed receipt read used by the existing Account source-event dispatcher. */
  public static Optional<Operation> findCommittedByRequestIdShared(DSLContext dsl, UUID requestId) {
    requireTransaction();
    return findByRequestId(dsl, requestId, true)
        .filter(operation -> operation.receipt().isPresent());
  }

  /**
   * Confirms that a latest Account restriction event is still the exact current category result.
   */
  public static void requireCurrentPostState(
      DSLContext dsl, Operation operation, ScopeState current) {
    Objects.requireNonNull(operation);
    Objects.requireNonNull(current);
    Receipt receipt =
        operation.receipt().orElseThrow(RestrictionProjectionUnavailableException::new);
    Category category = operation.request().category();
    ScopeState storedSource =
        new AccountAuthorityGenerationRepository(dsl)
            .read(AuthorityScope.account(operation.request().accountUuid()));
    var checkpoint =
        new AccountAuthorityOutboxRepository(dsl)
            .readCheckpoint(receipt.event().outboxStreamKey())
            .orElseThrow(RestrictionSourceUnavailableException::new);
    CurrentProjection projection =
        readCurrentProjection(dsl, operation.request().accountUuid(), category, false)
            .orElseThrow(RestrictionProjectionUnavailableException::new);
    Record latest =
        dsl.fetchOne(
            "SELECT max(revision) AS revision FROM "
                + REVISIONS
                + " WHERE account_uuid = ? AND category = ?",
            operation.request().accountUuid(),
            category.storageValue());
    if (!current.equals(storedSource)
        || !current.equals(receipt.sourceState())
        || checkpoint.outboxSequence() != receipt.event().outboxSequence()
        || !checkpoint.sourceEventId().equals(receipt.event().eventId())
        || !checkpoint.sourceEventDigest().equals(receipt.event().eventDigest())
        || projection.revision() != receipt.categoryRevision()
        || projection.enforcementEpoch() != receipt.enforcementEpoch()
        || !projection.resultId().equals(receipt.resultId())
        || projection.state() != receipt.state()
        || !projection.requestId().equals(operation.request().requestId())
        || latest == null
        || requiredPositive(latest, "revision") != projection.revision()) {
      throw new RestrictionProjectionUnavailableException();
    }
  }

  private static Optional<Operation> findByRequestId(
      DSLContext dsl, UUID requestId, boolean shared) {
    Objects.requireNonNull(dsl);
    Objects.requireNonNull(requestId);
    Record row =
        dsl.fetchOne(
            "SELECT * FROM "
                + OPERATIONS
                + " WHERE request_id = ?"
                + (shared ? " FOR SHARE" : " FOR UPDATE"),
            requestId);
    if (row == null) return Optional.empty();
    Category category = category(row.get("category", String.class));
    var request =
        new AccountPlatformRestrictionMutationRequest(
            requestId,
            row.get("account_uuid", UUID.class),
            category,
            requiredPositive(row, "expected_account_generation"),
            requiredPositive(row, "expected_account_source_version"),
            requiredPositive(row, "expected_category_revision"),
            requiredPositive(row, "expected_enforcement_epoch"),
            row.get("expected_result_id", UUID.class),
            RestrictionState.valueOf(row.get("desired_state", String.class)),
            AccountPlatformRestrictionMutationRequest.SourceKind.valueOf(
                row.get("source_kind", String.class)),
            row.get("source_request_id", UUID.class),
            row.get("source_digest", String.class));
    byte[] requestBytes = row.get("request_payload", byte[].class);
    if (!Arrays.equals(request.canonicalRequestBytes(), requestBytes)
        || !request.requestDigest().equals(row.get("request_digest", String.class))) {
      throw new RestrictionSourceUnavailableException();
    }
    String status = row.get("status", String.class);
    if (!"COMMITTED".equals(status)) throw new RestrictionSourceUnavailableException();
    AccountIdentityProvenance provenance =
        AccountIdentityProvenance.fromStorageValue(row.get("account_provenance", String.class));
    ScopeState result =
        new ScopeState(
            AuthorityScope.account(request.accountUuid()),
            requiredPositive(row, "result_generation"),
            requiredPositive(row, "result_source_version"),
            new IssuanceFence(
                request.accountUuid(),
                requiredPositive(row, "result_fence"),
                requiredPositive(row, "result_fence_source_version")));
    IssuanceFence expectedFence =
        new IssuanceFence(
            request.accountUuid(),
            requiredPositive(row, "expected_fence"),
            requiredPositive(row, "expected_fence_source_version"));
    Event event =
        new Event(
            row.get("outbox_stream_key", String.class),
            requestId.toString(),
            requiredPositive(row, "event_sequence"),
            row.get("event_id", String.class),
            row.get("event_digest", String.class),
            row.get("event_payload", byte[].class));
    Receipt receipt =
        new Receipt(
            event,
            result,
            requiredPositive(row, "result_category_revision"),
            requiredPositive(row, "result_enforcement_epoch"),
            row.get("result_id", UUID.class),
            RestrictionState.valueOf(row.get("result_state", String.class)));
    Operation operation =
        new Operation(
            request,
            requiredPositive(row, "account_id"),
            provenance,
            expectedFence,
            Optional.of(receipt));
    requireReceipt(dsl, operation);
    return Optional.of(operation);
  }

  private static void requireReceipt(DSLContext dsl, Operation operation) {
    Receipt receipt =
        operation.receipt().orElseThrow(RestrictionProjectionUnavailableException::new);
    var request = operation.request();
    Event event = receipt.event();
    AccountRestrictionAuthorityEvent verified;
    try {
      verified =
          AccountSecurityStateAuthorityEventV1Codec.verifyRestriction(
              new String(event.payload(), StandardCharsets.UTF_8));
    } catch (IllegalArgumentException invalid) {
      throw new RestrictionSourceUnavailableException();
    }
    if (!Arrays.equals(event.payload(), verified.canonicalJsonUtf8())
        || !request.accountUuid().toString().equals(verified.accountId())
        || !request.requestId().toString().equals(verified.requestId())
        || !event.requestId().equals(request.requestId().toString())
        || !ACCOUNT_STREAM_PREFIX
            .concat(request.accountUuid().toString())
            .equals(event.outboxStreamKey())
        || !event.eventId().equals(verified.eventId())
        || !event.eventDigest().equals(verified.eventDigest())
        || !Long.toString(event.outboxSequence()).equals(verified.outboxSequence())
        || !request.category().storageValue().equals(verified.category())
        || !Long.toString(receipt.categoryRevision()).equals(verified.revision())
        || !Long.toString(receipt.enforcementEpoch()).equals(verified.enforcementEpoch())
        || !receipt.resultId().toString().equals(verified.resultId())
        || !receipt.state().name().equals(verified.state())
        || !request.requestDigest().equals(verified.requestDigest())
        || !request.sourceKind().name().equals(verified.sourceKind())
        || !request.sourceRequestId().toString().equals(verified.sourceRequestId())
        || !request.sourceDigest().equals(verified.sourceDigest())
        || !Long.toString(receipt.sourceState().generation())
            .equals(verified.accountAuthorityGeneration())
        || !Long.toString(receipt.sourceState().sourceVersion()).equals(verified.sourceVersion())
        || increment(operation.expectedFence().value())
            != receipt.sourceState().issuanceFence().value()
        || increment(operation.expectedFence().sourceVersion())
            != receipt.sourceState().issuanceFence().sourceVersion()
        || !event
            .eventId()
            .equals(
                AccountSecurityStateAuthorityEventV1Codec.RESTRICTION_EVENT_ID_PREFIX
                    + request.requestId())) {
      throw new RestrictionSourceUnavailableException();
    }
    Record eventRow =
        dsl.fetchOne(
            "SELECT request_id, event_id, event_digest, payload FROM account_authority_outbox_events "
                + "WHERE outbox_stream_key = ? AND outbox_sequence = ?",
            event.outboxStreamKey(),
            event.outboxSequence());
    if (eventRow == null
        || !request.requestId().toString().equals(eventRow.get("request_id", String.class))
        || !event.eventId().equals(eventRow.get("event_id", String.class))
        || !event.eventDigest().equals(eventRow.get("event_digest", String.class))
        || !Arrays.equals(event.payload(), eventRow.get("payload", byte[].class))) {
      throw new RestrictionSourceUnavailableException();
    }
    Record revision =
        dsl.fetchOne(
            "SELECT * FROM "
                + REVISIONS
                + " WHERE account_uuid = ? AND category = ? AND revision = ?",
            request.accountUuid(),
            request.category().storageValue(),
            receipt.categoryRevision());
    if (revision == null
        || !receipt.resultId().equals(revision.get("result_id", UUID.class))
        || !request.requestId().equals(revision.get("request_id", UUID.class))
        || !receipt.state().name().equals(revision.get("restriction_state", String.class))
        || !request.requestDigest().equals(revision.get("request_digest", String.class))
        || !event.eventId().equals(revision.get("event_id", String.class))
        || !event.eventDigest().equals(revision.get("event_digest", String.class))) {
      throw new RestrictionSourceUnavailableException();
    }
  }

  private static Optional<CurrentProjection> readCurrentProjection(
      DSLContext dsl, UUID accountUuid, Category category, boolean lock) {
    Record row =
        dsl.fetchOne(
            "SELECT revision, enforcement_epoch, result_id, restriction_state, request_id FROM "
                + CURRENT
                + " WHERE account_uuid = ? AND category = ?"
                + (lock ? " FOR UPDATE" : ""),
            accountUuid,
            category.storageValue());
    if (row == null) return Optional.empty();
    UUID requestId = row.get("request_id", UUID.class);
    Operation operation =
        findByRequestId(dsl, requestId, !lock)
            .orElseThrow(RestrictionProjectionUnavailableException::new);
    Receipt receipt =
        operation.receipt().orElseThrow(RestrictionProjectionUnavailableException::new);
    long revision = requiredPositive(row, "revision");
    long epoch = requiredPositive(row, "enforcement_epoch");
    UUID resultId = row.get("result_id", UUID.class);
    RestrictionState state = RestrictionState.valueOf(row.get("restriction_state", String.class));
    if (operation.request().category() != category
        || receipt.categoryRevision() != revision
        || receipt.enforcementEpoch() != epoch
        || !receipt.resultId().equals(resultId)
        || receipt.state() != state) {
      throw new RestrictionProjectionUnavailableException();
    }
    CurrentProjection projection =
        new CurrentProjection(accountUuid, category, revision, epoch, resultId, state, requestId);
    requireLifecycleProjection(dsl, projection);
    return Optional.of(projection);
  }

  private static void requireLifecycleProjection(DSLContext dsl, CurrentProjection projection) {
    if (projection.category() != Category.ACCOUNT_SECURITY_LOCK) return;
    Record account =
        dsl.fetchOne(
            "SELECT lifecycle_state FROM accounts WHERE account_uuid = ? FOR SHARE",
            projection.accountUuid());
    String expectedLifecycle =
        projection.state() == RestrictionState.RESTRICTED ? "security_locked" : "active";
    if (account == null
        || !expectedLifecycle.equals(account.get("lifecycle_state", String.class))) {
      throw new RestrictionProjectionUnavailableException();
    }
  }

  private static void requireExpectedAccountSource(
      AccountPlatformRestrictionMutationRequest request,
      ScopeState current,
      CurrentSourceEvidence source) {
    if (!AuthorityScope.account(request.accountUuid()).equals(current.scope())
        || current.generation() != request.expectedAccountGeneration()
        || current.sourceVersion() != request.expectedAccountSourceVersion()
        || current.issuanceFence() == null
        || !request.accountUuid().equals(current.issuanceFence().accountId())
        || source.checkpoint().sequence() != current.generation() - 1L
        || current.sourceVersion() != current.generation()) {
      throw new StaleRestrictionOperationException();
    }
  }

  private static void requireExpectedCategory(
      AccountPlatformRestrictionMutationRequest request, CurrentProjection current) {
    if (request.expectedCategoryRevision() != current.revision()
        || request.expectedEnforcementEpoch() != current.enforcementEpoch()
        || !request.expectedResultId().equals(current.resultId())) {
      throw new StaleRestrictionOperationException();
    }
  }

  private AccountIdentity lockAccount(UUID accountUuid) {
    Record row =
        dsl.fetchOne(
            "SELECT id, account_uuid, account_uuid_provenance, account_uuid_source_numeric_id "
                + "FROM accounts WHERE account_uuid = ? FOR UPDATE",
            accountUuid);
    if (row == null
        || !accountUuid.equals(row.get("account_uuid", UUID.class))
        || requiredPositive(row, "id") != requiredPositive(row, "account_uuid_source_numeric_id")) {
      throw new RestrictionSourceUnavailableException();
    }
    AccountIdentityProvenance provenance =
        AccountIdentityProvenance.fromStorageValue(
            row.get("account_uuid_provenance", String.class));
    return new AccountIdentity(requiredPositive(row, "id"), provenance);
  }

  private static CategorySource birthCategory(
      AccountPlatformRestrictionBirthSource birth, Category category) {
    return switch (category) {
      case ACCOUNT_SECURITY_LOCK -> birth.accountSecurityLock();
      case PLATFORM_ACCESS_BAN -> birth.platformAccessBan();
    };
  }

  private static void requireAppendedEvent(
      Event event,
      AccountRestrictionAuthorityEvent candidate,
      String stream,
      String requestId,
      long sequence) {
    if (!stream.equals(event.outboxStreamKey())
        || !requestId.equals(event.requestId())
        || event.outboxSequence() != sequence
        || !candidate.eventId().equals(event.eventId())
        || !candidate.eventDigest().equals(event.eventDigest())
        || !Arrays.equals(candidate.canonicalJsonUtf8(), event.payload())) {
      throw new RestrictionSourceUnavailableException();
    }
  }

  private static void requireSameRequest(
      Operation operation, AccountPlatformRestrictionMutationRequest request) {
    if (!Arrays.equals(operation.request().canonicalRequestBytes(), request.canonicalRequestBytes())
        || !operation.request().requestDigest().equals(request.requestDigest())) {
      throw new AccountAuthorityOutboxRepository.IdempotencyConflictException(
          "Restriction request identity was reused with different source/result evidence");
    }
  }

  private static void requireIdentity(Operation operation, AccountIdentity identity) {
    if (operation.accountId() != identity.accountId()
        || operation.provenance() != identity.provenance()) {
      throw new RestrictionSourceUnavailableException();
    }
  }

  private static Category category(String storageValue) {
    for (Category candidate : Category.values()) {
      if (candidate.storageValue().equals(storageValue)) return candidate;
    }
    throw new RestrictionSourceUnavailableException();
  }

  private static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Account restriction read requires an owner transaction");
    }
  }

  private static void requireWritableTransaction() {
    requireTransaction();
    if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Account restriction mutation requires a writable transaction");
    }
  }

  private static long requiredPositive(Record row, String field) {
    Long value = row.get(field, Long.class);
    if (value == null || value <= 0L) throw new RestrictionSourceUnavailableException();
    return value;
  }

  private static long increment(long value) {
    try {
      return Math.addExact(value, 1L);
    } catch (ArithmeticException overflow) {
      throw new RestrictionSourceUnavailableException();
    }
  }

  public static final class OwnerAuthorizationUnresolvedException extends IllegalStateException {
    public OwnerAuthorizationUnresolvedException() {
      super(
          "Account restriction mutation is unavailable until exact owner intent and authentication proof are verified");
    }
  }

  public record Operation(
      AccountPlatformRestrictionMutationRequest request,
      long accountId,
      AccountIdentityProvenance provenance,
      IssuanceFence expectedFence,
      Optional<Receipt> receipt) {
    public Operation {
      Objects.requireNonNull(request);
      Objects.requireNonNull(provenance);
      Objects.requireNonNull(expectedFence);
      receipt = Objects.requireNonNull(receipt);
      if (accountId <= 0L || !request.accountUuid().equals(expectedFence.accountId())) {
        throw new IllegalArgumentException(
            "Positive Account identity and matching predecessor fence are required");
      }
      receipt.ifPresent(
          committed -> {
            if (committed.event().outboxSequence() != committed.sourceState().generation() - 1L) {
              throw new RestrictionSourceUnavailableException();
            }
          });
    }
  }

  public record Receipt(
      Event event,
      ScopeState sourceState,
      long categoryRevision,
      long enforcementEpoch,
      UUID resultId,
      RestrictionState state) {
    public Receipt {
      Objects.requireNonNull(event);
      Objects.requireNonNull(sourceState);
      Objects.requireNonNull(resultId);
      Objects.requireNonNull(state);
      if (categoryRevision <= 0L || enforcementEpoch <= 0L) {
        throw new IllegalArgumentException("Positive restriction result revisions are required");
      }
    }
  }

  public record CurrentProjection(
      UUID accountUuid,
      Category category,
      long revision,
      long enforcementEpoch,
      UUID resultId,
      RestrictionState state,
      UUID requestId) {
    public CurrentProjection {
      Objects.requireNonNull(accountUuid);
      Objects.requireNonNull(category);
      Objects.requireNonNull(resultId);
      Objects.requireNonNull(state);
      Objects.requireNonNull(requestId);
      if (revision <= 0L || enforcementEpoch <= 0L) {
        throw new IllegalArgumentException("Positive current restriction revisions are required");
      }
    }
  }

  private record AccountIdentity(long accountId, AccountIdentityProvenance provenance) {}

  public static final class RestrictionSourceUnavailableException extends IllegalStateException {
    public RestrictionSourceUnavailableException() {
      super("Account restriction source evidence is missing or inconsistent");
    }
  }

  public static final class RestrictionProjectionUnavailableException
      extends IllegalStateException {
    public RestrictionProjectionUnavailableException() {
      super("Current Account restriction projection is missing or unreadable");
    }
  }

  public static final class StaleRestrictionOperationException extends IllegalStateException {
    public StaleRestrictionOperationException() {
      super("Account or category restriction revision is stale");
    }
  }
}
