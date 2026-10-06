package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.SourceChange;
import net.firedevops.firemud.accountservice.dto.AccountSecurityStateMutationRequest;
import net.firedevops.firemud.accountservice.dto.AccountSecurityStateRequestDigest;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.entity.AccountLoginAuthModes;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Event;
import net.firedevops.firemud.accountservice.service.AccountAuthoritySourceReader.AccountSourceEventReadback;
import net.firedevops.firemud.accountservice.service.AccountAuthoritySourceReader.AccountSourceSnapshot;
import net.firedevops.firemud.common.account.authority.AccountSecurityStateAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.AccountSecurityStateAuthorityEventV1Codec.AccountState;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Account-local immutable operation storage. Caller bytes are correlation only. This class neither
 * authorizes a mutation nor updates Account/source/outbox or settles V57. Real owner evidence,
 * exact workload/caller authentication and commit currentness must be proved separately; a digest
 * cannot replace a retained owner receipt. Data access is not producer activation.
 */
@Repository
public class AccountSecurityStateOperationRepository {
  private static final String TABLE = "account_security_state_operations";
  private final DSLContext dsl;
  private final AccountAuthorityGenerationRepository generations;
  private final AccountAuthorityOutboxRepository outbox;

  @SuppressFBWarnings(
      value = {"EI_EXPOSE_REP2", "CT_CONSTRUCTOR_THROW"},
      justification =
          "Spring proxies require a non-final repository; constructor only validates internal DSL collaborators, acquires no resources and publishes no partial instance.")
  public AccountSecurityStateOperationRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl);
    generations = new AccountAuthorityGenerationRepository(dsl);
    outbox = new AccountAuthorityOutboxRepository(dsl);
  }

  /** Locks identity, then recovers the original request before comparing any new capture. */
  public Claim claim(AccountSecurityStateMutationRequest request, Capture capture) {
    requireTransaction();
    Objects.requireNonNull(request);
    Objects.requireNonNull(capture);
    lockAssociation(capture.accountId(), request.accountUuid(), capture.provenance());
    Optional<Operation> prior = readForUpdate(request.requestId());
    if (prior.isPresent()) {
      Operation original = prior.orElseThrow();
      requireRequest(original, request);
      if (capture.accountId() != original.capture().accountId()
          || capture.provenance() != original.capture().provenance()) {
        throw new OperationConflictException("Original Account association changed");
      }
      if (original.receipt().isEmpty()) requireSourceChange(original.capture(), false);
      return new Claim(false, original);
    }
    requireCapture(request, capture);
    increment(request.expectedGeneration());
    increment(request.expectedSourceVersion());
    increment(capture.sourceState().issuanceFence().value());
    increment(capture.sourceState().issuanceFence().sourceVersion());
    increment(capture.checkpointSequence());
    if (request.mutationKinds().contains("GLOBAL_ROLE_CHANGED"))
      increment(capture.globalRoleSourceVersion());
    ScopeState current = generations.read(AuthorityScope.account(request.accountUuid()));
    if (!current.equals(capture.sourceState())
        || !readCurrentState(request.accountUuid(), capture.globalRoleSourceVersion())
            .equals(capture.beforeState())) {
      throw new IllegalStateException("Original source/state capture is not current");
    }
    requireCheckpoint(capture, request.accountUuid());
    requireSourceChange(capture, false);
    int inserted =
        dsl.execute(
            "INSERT INTO "
                + TABLE
                + " (request_id, account_id, account_uuid, account_provenance, "
                + "caller_proof_binding, request_payload, request_digest, expected_generation, "
                + "expected_source_version, expected_fence, expected_fence_source_version, "
                + "checkpoint_sequence, checkpoint_payload, global_role_source_version, before_state, "
                + "after_state, mutation_kinds, source_change_id, source_change_binding, capture_payload, status) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'WAITING') "
                + "ON CONFLICT (request_id) DO NOTHING",
            request.requestId(),
            capture.accountId(),
            request.accountUuid(),
            capture.provenance().name(),
            request.callerProofBinding(),
            AccountSecurityStateRequestDigest.requestBytes(request),
            AccountSecurityStateRequestDigest.digest(request),
            request.expectedGeneration(),
            request.expectedSourceVersion(),
            capture.sourceState().issuanceFence().value(),
            capture.sourceState().issuanceFence().sourceVersion(),
            capture.checkpointSequence(),
            capture.checkpointPayload(),
            capture.globalRoleSourceVersion(),
            AccountSecurityStateMutationRequest.canonicalState(capture.beforeState()),
            request.canonicalDesiredState(),
            request.mutationKinds().toArray(String[]::new),
            capture.sourceChange().changeId(),
            capture.sourceChange().canonicalBytes(),
            captureBytes(request, capture));
    Operation stored = readForUpdate(request.requestId()).orElseThrow();
    requireRequest(stored, request);
    if (inserted == 1
        && !Arrays.equals(
            captureBytes(request, capture), captureBytes(request, stored.capture()))) {
      throw new IllegalStateException("Original operation capture readback differs");
    }
    return new Claim(inserted == 1, stored);
  }

  /** Returns immutable current or historical operation evidence without applying a mutation. */
  public Optional<Operation> findByRequestId(UUID requestId) {
    requireTransaction();
    Objects.requireNonNull(requestId);
    Record identity =
        dsl.fetchOne(
            "SELECT account_id, account_uuid, account_provenance FROM "
                + TABLE
                + " WHERE request_id = ?",
            requestId);
    if (identity == null) return Optional.empty();
    lockAssociation(
        identity.get("account_id", Long.class),
        identity.get("account_uuid", UUID.class),
        AccountIdentityProvenance.fromStorageValue(
            identity.get("account_provenance", String.class)));
    Optional<Operation> stored = readForUpdate(requestId);
    stored
        .filter(operation -> operation.receipt().isEmpty())
        .ifPresent(operation -> requireSourceChange(operation.capture(), false));
    return stored;
  }

  /** Proves actual current poststate, separately from immutable historical receipt validation. */
  public void requireCurrentPostState(Operation operation, ScopeState current) {
    requireTransaction();
    Objects.requireNonNull(operation);
    Receipt receipt =
        operation
            .receipt()
            .orElseThrow(
                () -> new IllegalStateException("Committed security-state receipt is required"));
    var request = operation.request();
    lockAssociation(
        operation.capture().accountId(), request.accountUuid(), operation.capture().provenance());
    Operation stored = findByRequestId(request.requestId()).orElseThrow();
    requireRequest(stored, request);
    if (!stored.receipt().orElseThrow().event().equals(receipt.event())
        || current == null
        || !receipt.sourceState().scope().equals(current.scope())
        || receipt.sourceState().generation() != current.generation()
        || receipt.sourceState().sourceVersion() != current.sourceVersion()
        || !generations.read(AuthorityScope.account(request.accountUuid())).equals(current)
        || !readCurrentState(request.accountUuid(), receipt.globalRoleSourceVersion())
            .equals(request.desiredState())) {
      throw new IllegalStateException(
          "Latest security-state actual Account/role/source poststate differs");
    }
    var checkpoint = outbox.readCheckpoint(streamKey(request.accountUuid())).orElseThrow();
    if (checkpoint.outboxSequence() != receipt.event().outboxSequence()
        || !checkpoint.sourceEventId().equals(receipt.event().eventId())
        || !checkpoint.sourceEventDigest().equals(receipt.event().eventDigest())) {
      throw new IllegalStateException("Latest security-state complete checkpoint differs");
    }
  }

  /** Records only an existing producer-committed event and V57 completion in this transaction. */
  public Operation complete(
      AccountSecurityStateMutationRequest request,
      Event event,
      ScopeState result,
      long resultGlobalRoleSourceVersion) {
    requireTransaction();
    Record identity =
        dsl.fetchOne(
            "SELECT account_id, account_provenance FROM " + TABLE + " WHERE request_id = ?",
            request.requestId());
    if (identity == null)
      throw new IllegalStateException("Original security-state request is missing");
    lockAssociation(
        identity.get("account_id", Long.class),
        request.accountUuid(),
        AccountIdentityProvenance.fromStorageValue(
            identity.get("account_provenance", String.class)));
    Operation original = readForUpdate(request.requestId()).orElseThrow();
    requireRequest(original, request);
    if (original.receipt().isPresent()) {
      Receipt committed = original.receipt().orElseThrow();
      if (!committed.event().equals(event)
          || !committed.sourceState().equals(result)
          || committed.globalRoleSourceVersion() != resultGlobalRoleSourceVersion) {
        throw new OperationConflictException("Committed receipt was supplied with changed results");
      }
      return original;
    }
    requireResult(original, event, result, resultGlobalRoleSourceVersion);
    requireSourceChange(original.capture(), true);
    if (!generations.read(AuthorityScope.account(request.accountUuid())).equals(result)
        || !readCurrentState(request.accountUuid(), resultGlobalRoleSourceVersion)
            .equals(request.desiredState())) {
      throw new IllegalStateException("Committed producer state readback differs");
    }
    var checkpoint = outbox.readCheckpoint(event.outboxStreamKey()).orElseThrow();
    if (checkpoint.outboxSequence() != event.outboxSequence()
        || !checkpoint.sourceEventId().equals(event.eventId())
        || !checkpoint.sourceEventDigest().equals(event.eventDigest())) {
      throw new IllegalStateException("Committed source checkpoint readback differs");
    }
    int updated =
        dsl.execute(
            "UPDATE "
                + TABLE
                + " SET status = 'COMMITTED', event_sequence = ?, event_id = ?, "
                + "event_digest = ?, event_payload = ?, result_generation = ?, result_source_version = ?, "
                + "result_fence = ?, result_fence_source_version = ?, result_global_role_source_version = ? "
                + "WHERE request_id = ? AND status = 'WAITING'",
            event.outboxSequence(),
            event.eventId(),
            event.eventDigest(),
            event.payload(),
            result.generation(),
            result.sourceVersion(),
            result.issuanceFence().value(),
            result.issuanceFence().sourceVersion(),
            resultGlobalRoleSourceVersion,
            request.requestId());
    if (updated != 1) throw new IllegalStateException("Operation did not commit exactly once");
    Operation stored = readForUpdate(request.requestId()).orElseThrow();
    requireRequest(stored, request);
    if (!stored.receipt().orElseThrow().event().equals(event)) {
      throw new IllegalStateException("Committed operation event readback differs");
    }
    return stored;
  }

  private Optional<Operation> readForUpdate(UUID requestId) {
    Record row =
        dsl.fetchOne("SELECT * FROM " + TABLE + " WHERE request_id = ? FOR UPDATE", requestId);
    if (row == null) return Optional.empty();
    UUID accountUuid = row.get("account_uuid", UUID.class);
    var request =
        new AccountSecurityStateMutationRequest(
            requestId,
            accountUuid,
            row.get("caller_proof_binding", byte[].class),
            row.get("expected_generation", Long.class),
            row.get("expected_source_version", Long.class),
            List.of(row.get("mutation_kinds", String[].class)),
            AccountSecurityStateMutationRequest.parseCanonicalState(
                row.get("after_state", byte[].class)));
    if (!Arrays.equals(
            AccountSecurityStateRequestDigest.requestBytes(request),
            row.get("request_payload", byte[].class))
        || !AccountSecurityStateRequestDigest.digest(request)
            .equals(row.get("request_digest", String.class))) {
      throw new IllegalStateException("Stored original request digest/bytes differ");
    }
    ScopeState expected =
        new ScopeState(
            AuthorityScope.account(accountUuid),
            request.expectedGeneration(),
            request.expectedSourceVersion(),
            new AccountAuthorityGenerationRepository.IssuanceFence(
                accountUuid,
                row.get("expected_fence", Long.class),
                row.get("expected_fence_source_version", Long.class)));
    Capture capture =
        new Capture(
            row.get("account_id", Long.class),
            AccountIdentityProvenance.fromStorageValue(row.get("account_provenance", String.class)),
            AccountSecurityStateMutationRequest.parseCanonicalState(
                row.get("before_state", byte[].class)),
            expected,
            row.get("checkpoint_sequence", Long.class),
            row.get("checkpoint_payload", byte[].class),
            row.get("global_role_source_version", Long.class),
            SourceChange.fromStored(row.get("source_change_binding", byte[].class)));
    requireCapture(request, capture);
    if (!capture.sourceChange().changeId().equals(row.get("source_change_id", UUID.class))
        || !Arrays.equals(
            captureBytes(request, capture), row.get("capture_payload", byte[].class))) {
      throw new IllegalStateException("Stored original source capture differs");
    }
    String status = row.get("status", String.class);
    Optional<Receipt> receipt = Optional.empty();
    if ("COMMITTED".equals(status)) {
      Event event =
          new Event(
              streamKey(accountUuid),
              requestId.toString(),
              row.get("event_sequence", Long.class),
              row.get("event_id", String.class),
              row.get("event_digest", String.class),
              row.get("event_payload", byte[].class));
      ScopeState result =
          new ScopeState(
              AuthorityScope.account(accountUuid),
              row.get("result_generation", Long.class),
              row.get("result_source_version", Long.class),
              new AccountAuthorityGenerationRepository.IssuanceFence(
                  accountUuid,
                  row.get("result_fence", Long.class),
                  row.get("result_fence_source_version", Long.class)));
      receipt =
          Optional.of(
              new Receipt(event, result, row.get("result_global_role_source_version", Long.class)));
    } else if (!"WAITING".equals(status)) {
      throw new IllegalStateException("Unsupported operation state");
    }
    Operation operation = new Operation(request, capture, receipt);
    if (receipt.isPresent()) {
      requireResult(
          operation,
          receipt.orElseThrow().event(),
          receipt.orElseThrow().sourceState(),
          receipt.orElseThrow().globalRoleSourceVersion());
      requireSourceChange(capture, true);
      requireReceiptNotAhead(operation);
    }
    return Optional.of(operation);
  }

  private void requireResult(
      Operation operation, Event event, ScopeState result, long roleSourceVersion) {
    if (event == null || result == null || result.issuanceFence() == null) {
      throw new IllegalStateException("Complete Account source receipt is required");
    }
    var request = operation.request();
    var capture = operation.capture();
    var verified =
        AccountSecurityStateAuthorityEventV1Codec.verify(
            new String(event.payload(), StandardCharsets.UTF_8));
    if (!Arrays.equals(event.payload(), verified.canonicalJsonUtf8())
        || !request.accountUuid().toString().equals(verified.accountId())
        || !request.requestId().toString().equals(verified.requestId())
        || !request.requestId().toString().equals(event.requestId())
        || !streamKey(request.accountUuid()).equals(event.outboxStreamKey())
        || !event.outboxStreamKey().equals(verified.outboxStreamKey())
        || !event.eventId().equals(verified.eventId())
        || !event.eventDigest().equals(verified.eventDigest())
        || !Long.toString(event.outboxSequence()).equals(verified.outboxSequence())
        || event.outboxSequence() != increment(capture.checkpointSequence())
        || !result.scope().equals(capture.sourceState().scope())
        || result.generation() != increment(request.expectedGeneration())
        || result.sourceVersion() != increment(request.expectedSourceVersion())
        || !Long.toString(result.generation()).equals(verified.accountAuthorityGeneration())
        || !Long.toString(result.sourceVersion()).equals(verified.sourceVersion())
        || !request.accountUuid().equals(result.issuanceFence().accountId())
        || result.issuanceFence().value()
            != increment(capture.sourceState().issuanceFence().value())
        || result.issuanceFence().sourceVersion()
            != increment(capture.sourceState().issuanceFence().sourceVersion())
        || roleSourceVersion
            != (request.mutationKinds().contains("GLOBAL_ROLE_CHANGED")
                ? increment(capture.globalRoleSourceVersion())
                : capture.globalRoleSourceVersion())
        || !verified.accountState().equals(request.desiredState())
        || !verified.mutationKinds().equals(request.mutationKinds())) {
      throw new IllegalStateException("Source receipt differs from the original operation/result");
    }
    Event byRequest = outbox.findEvent(event.outboxStreamKey(), event.requestId()).orElseThrow();
    Event bySequence =
        outbox.findEvent(event.outboxStreamKey(), event.outboxSequence()).orElseThrow();
    if (!event.equals(byRequest) || !event.equals(bySequence)) {
      throw new IllegalStateException("Canonical outbox receipt readback differs");
    }
  }

  private void requireCapture(AccountSecurityStateMutationRequest request, Capture capture) {
    Objects.requireNonNull(capture.sourceChange(), "Original V57 source change is required");
    if (!capture.sourceState().scope().equals(AuthorityScope.account(request.accountUuid()))
        || capture.sourceState().generation() != request.expectedGeneration()
        || capture.sourceState().sourceVersion() != request.expectedSourceVersion()
        || !request.accountUuid().equals(capture.sourceState().issuanceFence().accountId())
        || !detectedKinds(capture.beforeState(), request.desiredState())
            .equals(request.mutationKinds())) {
      throw new IllegalArgumentException(
          "Original state/source/family capture differs from request");
    }
    // Passwords are intentionally absent; their real owner operation is outside this journal.
    boolean accountSource = false;
    boolean roleSource = false;
    for (var source : capture.sourceChange().sources()) {
      if (!request.accountUuid().toString().equals(source.scopeId())) {
        throw new IllegalArgumentException("Source participation targets another Account");
      }
      if (source.kind() == SourceKind.ACCOUNT) {
        accountSource = true;
        if (!Long.toString(request.expectedGeneration()).equals(source.generation())
            || !Long.toString(request.expectedSourceVersion()).equals(source.sourceVersion())
            || !streamKey(request.accountUuid()).equals(source.checkpointStream())
            || !Long.toString(capture.checkpointSequence()).equals(source.checkpointSequence())
            || !Arrays.equals(captureBytes(request, capture), source.evidence())) {
          throw new IllegalArgumentException("Exact Account source participation is required");
        }
      } else if (source.kind() == SourceKind.GLOBAL_ROLES) {
        roleSource = true;
        if (!Long.toString(capture.globalRoleSourceVersion()).equals(source.sourceVersion())
            || source.generation() != null
            || source.checkpointStream() != null
            || !Arrays.equals(captureBytes(request, capture), source.evidence())) {
          throw new IllegalArgumentException("Independent role-source version differs");
        }
      } else {
        throw new IllegalArgumentException("Unrelated source participation is unsupported");
      }
    }
    if (!accountSource
        || roleSource != request.mutationKinds().contains("GLOBAL_ROLE_CHANGED")
        || !Arrays.equals(captureBytes(request, capture), capture.sourceChange().mutation())) {
      throw new IllegalArgumentException("Complete exact source-change binding is required");
    }
  }

  public static List<String> detectedKinds(AccountState before, AccountState after) {
    List<String> kinds = new ArrayList<>();
    if (before.emailVerified() != after.emailVerified())
      kinds.add("EMAIL_LOGIN_ELIGIBILITY_CHANGED");
    if (!before.globalRoles().equals(after.globalRoles())) kinds.add("GLOBAL_ROLE_CHANGED");
    if (!before.lifecycleState().equals(after.lifecycleState()))
      kinds.add("LIFECYCLE_STATE_CHANGED");
    if (!before.loginAuthModes().equals(after.loginAuthModes()))
      kinds.add("LOGIN_AUTH_MODES_CHANGED");
    return List.copyOf(kinds);
  }

  public static byte[] captureBytes(AccountSecurityStateMutationRequest request, Capture capture) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    AccountSecurityStateRequestDigest.frame(bytes, "firemud/account/security-state/capture/v1");
    AccountSecurityStateRequestDigest.frame(
        bytes, AccountSecurityStateRequestDigest.requestBytes(request));
    AccountSecurityStateRequestDigest.frame(bytes, Long.toString(capture.accountId()));
    AccountSecurityStateRequestDigest.frame(bytes, capture.provenance().name());
    AccountSecurityStateRequestDigest.frame(
        bytes, AccountSecurityStateMutationRequest.canonicalState(capture.beforeState()));
    AccountSecurityStateRequestDigest.frame(
        bytes, Long.toString(capture.sourceState().issuanceFence().value()));
    AccountSecurityStateRequestDigest.frame(
        bytes, Long.toString(capture.sourceState().issuanceFence().sourceVersion()));
    AccountSecurityStateRequestDigest.frame(bytes, Long.toString(capture.checkpointSequence()));
    AccountSecurityStateRequestDigest.frame(bytes, capture.checkpointPayload());
    AccountSecurityStateRequestDigest.frame(
        bytes, Long.toString(capture.globalRoleSourceVersion()));
    return bytes.toByteArray();
  }

  private void requireCheckpoint(Capture capture, UUID accountUuid) {
    var checkpoint = outbox.readCheckpoint(streamKey(accountUuid));
    if (capture.checkpointSequence() == 0) {
      Record history =
          dsl.fetchOne(
              "SELECT count(*)::integer AS count FROM account_authority_outbox_events WHERE outbox_stream_key = ?",
              streamKey(accountUuid));
      if (history == null) {
        throw new IllegalStateException("Original Account source history count is missing");
      }
      Integer count = history.get("count", Integer.class);
      if (capture.sourceState().generation() != 1
          || capture.sourceState().sourceVersion() != 1
          || checkpoint.isPresent()
          || count == null
          || count != 0
          || capture.checkpointPayload().length != 0) {
        throw new IllegalStateException("Original sequence-zero baseline is not proved");
      }
    } else {
      Event latest =
          outbox.findEvent(streamKey(accountUuid), capture.checkpointSequence()).orElseThrow();
      if (checkpoint.orElseThrow().outboxSequence() != capture.checkpointSequence()
          || !checkpoint.orElseThrow().sourceEventId().equals(latest.eventId())
          || !checkpoint.orElseThrow().sourceEventDigest().equals(latest.eventDigest())
          || !latest.equals(
              outbox.findEvent(streamKey(accountUuid), latest.requestId()).orElseThrow())
          || !Arrays.equals(capture.checkpointPayload(), latest.payload())) {
        throw new IllegalStateException("Original checkpoint capture differs");
      }
      // Structural closed-schema verification only; this does not prove caller authorization.
      new AccountSourceSnapshot(
          accountUuid,
          capture.sourceState(),
          streamKey(accountUuid),
          capture.checkpointSequence(),
          Optional.of(latest));
    }
  }

  private void requireReceiptNotAhead(Operation operation) {
    Receipt retained = operation.receipt().orElseThrow();
    UUID accountUuid = operation.request().accountUuid();
    ScopeState current = generations.read(AuthorityScope.account(accountUuid));
    var checkpoint = outbox.readCheckpoint(streamKey(accountUuid)).orElseThrow();
    Event latest =
        outbox.findEvent(streamKey(accountUuid), checkpoint.outboxSequence()).orElseThrow();
    AccountSourceSnapshot snapshot =
        new AccountSourceSnapshot(
            accountUuid,
            current,
            streamKey(accountUuid),
            checkpoint.outboxSequence(),
            Optional.of(latest));
    new AccountSourceEventReadback(snapshot, retained.event());
    Record role =
        dsl.fetchOne(
            "SELECT global_role_source_version FROM account_global_role_sources WHERE account_uuid = ? FOR SHARE",
            accountUuid);
    Long roleVersion = role == null ? null : role.get("global_role_source_version", Long.class);
    if (retained.sourceState().issuanceFence().value() > current.issuanceFence().value()
        || retained.sourceState().issuanceFence().sourceVersion()
            > current.issuanceFence().sourceVersion()
        || roleVersion == null
        || retained.globalRoleSourceVersion() > roleVersion) {
      throw new IllegalStateException("Retained receipt is ahead of present Account authority");
    }
  }

  private void requireSourceChange(Capture capture, boolean committed) {
    Record source =
        dsl.fetchOne(
            "SELECT binding, status FROM account_draft_authorization_source_changes WHERE change_id = ? FOR SHARE",
            capture.sourceChange().changeId());
    if (source == null
        || !Arrays.equals(
            capture.sourceChange().canonicalBytes(), source.get("binding", byte[].class))
        || !(committed ? "SOURCE_COMMITTED" : "WAITING")
            .equals(source.get("status", String.class))) {
      throw new IllegalStateException("Original V57 source-change evidence/status differs");
    }
  }

  private void lockAssociation(
      long numericId, UUID accountUuid, AccountIdentityProvenance provenance) {
    Record account =
        dsl.fetchOne(
            "SELECT id, account_uuid, account_uuid_source_numeric_id, account_uuid_provenance FROM accounts WHERE id = ? FOR UPDATE",
            numericId);
    if (account == null
        || !accountUuid.equals(account.get("account_uuid", UUID.class))
        || !Long.valueOf(numericId)
            .equals(account.get("account_uuid_source_numeric_id", Long.class))
        || !provenance.name().equals(account.get("account_uuid_provenance", String.class))) {
      throw new IllegalStateException("Persisted numeric/UUID/provenance association differs");
    }
  }

  private AccountState readCurrentState(UUID accountUuid, long roleVersion) {
    Record row =
        dsl.fetchOne(
            "SELECT a.email_verified, a.login_auth_modes, a.lifecycle_state, r.global_roles, r.global_role_source_version FROM accounts a JOIN account_global_role_sources r ON r.account_uuid = a.account_uuid WHERE a.account_uuid = ? FOR SHARE OF a, r",
            accountUuid);
    if (row == null
        || !Long.valueOf(roleVersion).equals(row.get("global_role_source_version", Long.class))) {
      throw new IllegalStateException(
          "Current independently versioned global-role source is unavailable");
    }
    var state =
        new AccountState(
            Boolean.TRUE.equals(row.get("email_verified", Boolean.class)),
            AccountLoginAuthModes.read(row.get("login_auth_modes", String.class)).stream()
                .map(Enum::name)
                .sorted()
                .toList(),
            Arrays.stream(row.get("global_roles", String[].class)).sorted().toList(),
            row.get("lifecycle_state", String.class).toUpperCase(java.util.Locale.ROOT));
    return AccountSecurityStateMutationRequest.parseCanonicalState(
        AccountSecurityStateMutationRequest.canonicalState(state));
  }

  private static void requireRequest(
      Operation original, AccountSecurityStateMutationRequest request) {
    if (!Arrays.equals(
        AccountSecurityStateRequestDigest.requestBytes(original.request()),
        AccountSecurityStateRequestDigest.requestBytes(request))) {
      throw new OperationConflictException(
          "Request ID was reused with changed original caller/state binding");
    }
  }

  private static long increment(long value) {
    try {
      return Math.addExact(value, 1L);
    } catch (ArithmeticException overflow) {
      throw new IllegalStateException("Account source counter overflow", overflow);
    }
  }

  private static String streamKey(UUID accountUuid) {
    return "account:auth-authority:v1:account/" + accountUuid;
  }

  private static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("Writable Account owner transaction is required");
    }
  }

  public record Capture(
      long accountId,
      AccountIdentityProvenance provenance,
      AccountState beforeState,
      ScopeState sourceState,
      long checkpointSequence,
      byte[] checkpointPayload,
      long globalRoleSourceVersion,
      SourceChange sourceChange) {
    public Capture {
      if (accountId <= 0
          || checkpointSequence < 0
          || globalRoleSourceVersion <= 0
          || provenance == null
          || beforeState == null
          || sourceState == null
          || sourceState.issuanceFence() == null
          || checkpointPayload == null)
        throw new IllegalArgumentException("Complete original capture is required");
      checkpointPayload = checkpointPayload.clone();
      beforeState =
          AccountSecurityStateMutationRequest.parseCanonicalState(
              AccountSecurityStateMutationRequest.canonicalState(beforeState));
      // A temporary null change is permitted solely while constructing its non-circular capture
      // bytes.
    }

    @Override
    public byte[] checkpointPayload() {
      return checkpointPayload.clone();
    }
  }

  public record Receipt(Event event, ScopeState sourceState, long globalRoleSourceVersion) {}

  public record Operation(
      AccountSecurityStateMutationRequest request, Capture capture, Optional<Receipt> receipt) {}

  public record Claim(boolean claimed, Operation operation) {}

  public static final class OperationConflictException extends IllegalStateException {
    public OperationConflictException(String message) {
      super(message);
    }
  }
}
