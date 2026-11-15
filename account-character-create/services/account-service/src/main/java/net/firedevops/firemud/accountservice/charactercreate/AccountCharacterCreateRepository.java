package net.firedevops.firemud.accountservice.charactercreate;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountCanonicalRuntimeMembershipSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.AccountDemoTenantEntitlementRepository;
import net.firedevops.firemud.common.account.authority.RuntimeMembershipAuthorityEvidenceValidator;
import net.firedevops.firemud.common.account.authority.RuntimeMembershipAuthorityEvidenceValidator.Snapshot;
import net.firedevops.firemud.entitymanagement.v1.EntityCharacterCreateTerminal;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unregistered Account owner storage. The source reads and protection insert share the actual
 * writable READ COMMITTED transaction and the existing Account source-writer locks.
 */
public final class AccountCharacterCreateRepository {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final DSLContext dsl;
  private final AccountCanonicalRuntimeMembershipSnapshotRepository memberships;
  private final AccountDemoTenantEntitlementRepository entitlements;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "Injected SQL collaborators are shared owner repositories and cannot be copied as values.")
  public AccountCharacterCreateRepository(
      DSLContext dsl,
      AccountCanonicalRuntimeMembershipSnapshotRepository memberships,
      AccountDemoTenantEntitlementRepository entitlements) {
    this.dsl = Objects.requireNonNull(dsl);
    this.memberships = Objects.requireNonNull(memberships);
    this.entitlements = Objects.requireNonNull(entitlements);
  }

  public Optional<AccountCharacterCreateIntent> findOriginal(
      String namespace, UUID accountId, UUID requestId) {
    requireTransaction();
    return Optional.ofNullable(
            dsl.fetchOne(
                "SELECT * FROM account_character_create_intents "
                    + "WHERE target_namespace = ? AND account_uuid = ? AND request_id = ?",
                namespace,
                accountId,
                requestId))
        .map(AccountCharacterCreateRepository::intent);
  }

  public Optional<AccountCharacterCreateIntent> findOperation(String namespace, UUID operationId) {
    requireTransaction();
    return Optional.ofNullable(
            dsl.fetchOne(
                "SELECT * FROM account_character_create_intents "
                    + "WHERE target_namespace = ? AND operation_id = ?",
                namespace,
                operationId))
        .map(AccountCharacterCreateRepository::intent);
  }

  /** Persists the full intent before any protection or delegation; never replaces its winner. */
  public AccountCharacterCreateIntent retain(AccountCharacterCreateIntent candidate) {
    requireTransaction();
    dsl.execute(
        "INSERT INTO account_character_create_intents "
            + "(operation_id, target_namespace, account_uuid, tenant_uuid, request_id, "
            + "connect_scope_id, purpose, action_bytes, mutation_digest, original_source_readback) "
            + "VALUES (?, ?, ?, ?, ?, ?, 'CHARACTER_CREATE', ?, ?, ?) "
            + "ON CONFLICT (target_namespace, account_uuid, request_id) DO NOTHING",
        candidate.operationId(),
        candidate.targetNamespace(),
        candidate.accountId(),
        candidate.tenantId(),
        candidate.createCharacterRequestId(),
        candidate.connectScopeId(),
        candidate.actionBytes(),
        candidate.mutationDigest(),
        candidate.originalSourceReadback());
    AccountCharacterCreateIntent retained =
        findOriginal(
                candidate.targetNamespace(),
                candidate.accountId(),
                candidate.createCharacterRequestId())
            .orElseThrow(AccountCharacterCreateRepository::unavailable);
    // Concurrent first producers may allocate distinct operation UUIDs; only the immutable winner
    // is returned, and all caller/source bindings must still match exactly.
    AccountCharacterCreateIntent boundToWinner =
        new AccountCharacterCreateIntent(
            retained.operationId(),
            candidate.createCharacterRequestId(),
            candidate.connectScopeId(),
            candidate.accountId(),
            candidate.actionBytes(),
            candidate.mutationDigest(),
            candidate.originalSourceReadback());
    if (!retained.sameBinding(boundToWinner)) throw conflict();
    return retained;
  }

  /**
   * Revalidates actual canonical membership/event and entitlement owners while their writers are
   * excluded. The initial supported source is the existing fresh-UUID NON_PAID_DEMO entitlement;
   * legacy numeric subscriptions, creator roles and private realm grants cannot substitute.
   */
  public Protection acquire(AccountCharacterCreateIntent original) {
    requireTransaction();
    requireRetained(original);
    Optional<Protection> previous = findProtection(original);
    if (previous.isPresent()) return previous.orElseThrow();

    dsl.fetch(
        "SELECT account_control_ui_hold_required_sources("
            + "account_character_create_source_keys(?, ?))",
        original.accountId(),
        original.tenantId());
    Snapshot membership = memberships.readCurrent(original.accountId(), original.tenantId());
    if (RuntimeMembershipAuthorityEvidenceValidator.validate(membership).isEmpty()
        || !membership.membershipExists()
        || !"ACTIVE".equals(membership.membershipLifecycleState())
        || !membership.gameplayAdmissionAllowed()
        || !original.accountId().toString().equals(membership.accountId())
        || !original.tenantId().toString().equals(membership.tenantId())) {
      throw unavailable();
    }
    var entitlement = entitlements.readCurrent(original.tenantId());
    if (!original.tenantId().equals(entitlement.canonicalTenantId())
        || !original.targetNamespace().equals(entitlement.sourceEvidence().targetNamespace())
        || !entitlement.gameplayAvailable()
        || !entitlement.allowNewGameplayBindings()
        || !"ACTIVE".equals(entitlement.status())) {
      throw unavailable();
    }
    byte[] membershipBytes = membershipBytes(membership);
    byte[] entitlementBytes = JSON.writeValueAsBytes(entitlement);
    dsl.execute(
        "INSERT INTO account_character_create_protections "
            + "(protection_id, operation_id, membership_snapshot, entitlement_snapshot) "
            + "VALUES (?, ?, ?, ?) ON CONFLICT (operation_id) DO NOTHING",
        UUID.randomUUID(),
        original.operationId(),
        membershipBytes,
        entitlementBytes);
    return findProtection(original).orElseThrow(AccountCharacterCreateRepository::unavailable);
  }

  /** Historical exact read: expiry never changes the original protection. */
  public Optional<Protection> findProtection(AccountCharacterCreateIntent original) {
    requireTransaction();
    requireRetained(original);
    Record row =
        dsl.fetchOne(
            "SELECT * FROM account_character_create_protections WHERE operation_id = ?",
            original.operationId());
    if (row == null) return Optional.empty();
    return Optional.of(
        new Protection(
            original,
            row.get("protection_id", UUID.class),
            row.get("protection_fence", Long.class),
            row.get("acquired_at", OffsetDateTime.class).toInstant(),
            row.get("membership_snapshot", byte[].class),
            row.get("entitlement_snapshot", byte[].class)));
  }

  public Optional<EntityCharacterCreateTerminal> findSettlement(Protection expected) {
    requireTransaction();
    requireRetained(expected.intent());
    var actual =
        findProtection(expected.intent())
            .orElseThrow(AccountCharacterCreateRepository::unavailable);
    if (!actual.sameBinding(expected)) throw conflict();
    Record row =
        dsl.fetchOne(
            "SELECT terminal_bytes FROM account_character_create_settlements WHERE protection_id = ?",
            expected.protectionId());
    if (row == null) return Optional.empty();
    try {
      var terminal = EntityCharacterCreateTerminal.parseFrom(row.get(0, byte[].class));
      requireOwnTerminal(expected, terminal);
      if (!Arrays.equals(row.get(0, byte[].class), terminal.toByteArray())) throw conflict();
      return Optional.of(terminal);
    } catch (com.google.protobuf.InvalidProtocolBufferException malformed) {
      throw unavailable();
    }
  }

  /** Only the exact authenticated Entity-client capability can supply a new settlement. */
  public EntityCharacterCreateTerminal settle(
      Protection expected, AccountCharacterCreateEntityClient.VerifiedTerminal verified) {
    requireTransaction();
    Objects.requireNonNull(verified);
    var account =
        AccountCharacterCreateDelegationBindings.account(
            expected.intent().targetNamespace(), expected.intent(), expected);
    if (!account.equals(verified.account())) throw conflict();
    var terminal = verified.terminal();
    requireOwnTerminal(expected, terminal);
    // Use the source writers' exact lock identities and ordering, without asking a held source
    // to authorize its own mutation. There is no RPC under these owner locks.
    dsl.fetch(
        "SELECT account_character_create_lock_settlement_sources(?, ?)",
        expected.intent().accountId(),
        expected.intent().tenantId());
    dsl.fetchOne(
        "SELECT protection_id FROM account_character_create_protections WHERE protection_id = ? FOR UPDATE",
        expected.protectionId());
    var actual =
        findProtection(expected.intent())
            .orElseThrow(AccountCharacterCreateRepository::unavailable);
    if (!actual.sameBinding(expected)) throw conflict();
    var original = findSettlement(expected);
    if (original.isPresent()) {
      if (!original.orElseThrow().equals(terminal)) throw conflict();
      return original.orElseThrow();
    }
    dsl.execute(
        "INSERT INTO account_character_create_settlements (protection_id, protection_fence, operation_id, "
            + "original_membership_snapshot, original_entitlement_snapshot, original_action, original_source_readback, "
            + "entity_terminal_id, entity_terminal_fence, outcome, terminal_bytes, terminal_digest) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        expected.protectionId(),
        expected.protectionFence(),
        expected.intent().operationId(),
        expected.membershipSnapshot(),
        expected.entitlementSnapshot(),
        expected.intent().actionBytes(),
        expected.intent().originalSourceReadback(),
        UUID.fromString(terminal.getTerminalId()),
        terminal.getTerminalFence(),
        terminal.getCharacterId().isEmpty() ? "ABORTED" : "COMMITTED",
        terminal.toByteArray(),
        "sha256:" + java.util.HexFormat.of().formatHex(sha256(terminal.toByteArray())));
    return findSettlement(expected).orElseThrow(AccountCharacterCreateRepository::unavailable);
  }

  private static void requireOwnTerminal(
      Protection expected, EntityCharacterCreateTerminal terminal) {
    AccountCharacterCreateDelegationBindings.exact(
        terminal, AccountCharacterCreateDelegationBindings.MAX_TERMINAL_BYTES);
    var account =
        AccountCharacterCreateDelegationBindings.account(
            expected.intent().targetNamespace(), expected.intent(), expected);
    if (!terminal.hasOriginalRequest()
        || !terminal.hasAccountOriginal()
        || !account.getOriginal().equals(terminal.getAccountOriginal())
        || !account.toByteString().equals(terminal.getOriginalAccountProtection())
        || !AccountCharacterCreateDelegationBindings.original(account)
            .equals(terminal.getOriginalRequest().getOriginal())
        || !expected
            .protectionId()
            .toString()
            .equals(terminal.getOriginalRequest().getAccountProtection().getProtectionId())
        || expected.protectionFence()
            != terminal.getOriginalRequest().getAccountProtection().getProtectionFence()
        || terminal.getTerminalFence() <= 0
        || !terminal.getEntitySourceReceiptDigest().matches("sha256:[0-9a-f]{64}"))
      throw conflict();
    AccountCharacterCreateDelegationBindings.id(terminal.getEntityIntakeRequestId());
    AccountCharacterCreateDelegationBindings.id(terminal.getTerminalId());
    AccountCharacterCreateDelegationBindings.instant(terminal.getCompletedAt());
    switch (terminal.getOutcome()) {
      case ENTITY_CHARACTER_CREATE_OUTCOME_COMMITTED -> {
        AccountCharacterCreateDelegationBindings.id(terminal.getCharacterId());
        if (!terminal.getAbortRequestId().isEmpty()) throw conflict();
      }
      case ENTITY_CHARACTER_CREATE_OUTCOME_ABORTED -> {
        AccountCharacterCreateDelegationBindings.id(terminal.getAbortRequestId());
        if (!terminal.getCharacterId().isEmpty()) throw conflict();
      }
      default -> throw conflict();
    }
  }

  private static byte[] sha256(byte[] bytes) {
    try {
      return java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private void requireRetained(AccountCharacterCreateIntent expected) {
    AccountCharacterCreateIntent retained =
        findOperation(expected.targetNamespace(), expected.operationId())
            .orElseThrow(AccountCharacterCreateRepository::unavailable);
    if (!retained.sameBinding(expected)) throw conflict();
  }

  private static AccountCharacterCreateIntent intent(Record row) {
    AccountCharacterCreateIntent intent =
        new AccountCharacterCreateIntent(
            row.get("operation_id", UUID.class),
            row.get("request_id", UUID.class),
            row.get("connect_scope_id", String.class),
            row.get("account_uuid", UUID.class),
            row.get("action_bytes", byte[].class),
            row.get("mutation_digest", String.class),
            row.get("original_source_readback", byte[].class));
    if (!intent.targetNamespace().equals(row.get("target_namespace", String.class))
        || !intent.tenantId().equals(row.get("tenant_uuid", UUID.class))
        || !AccountCharacterCreateIntent.PURPOSE.equals(row.get("purpose", String.class))) {
      throw unavailable();
    }
    return intent;
  }

  private static byte[] membershipBytes(Snapshot snapshot) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("issuerId", snapshot.issuerId());
    value.put("accountId", snapshot.accountId());
    value.put("tenantId", snapshot.tenantId());
    value.put("membershipExists", snapshot.membershipExists());
    value.put("membershipLifecycleState", snapshot.membershipLifecycleState());
    value.put("gameplayAdmissionAllowed", snapshot.gameplayAdmissionAllowed());
    value.put("membershipVersion", snapshot.membershipVersion());
    value.put("membershipAuthorityGeneration", snapshot.membershipAuthorityGeneration());
    value.put("roles", snapshot.roles());
    value.put("issuanceFence", snapshot.issuanceFence());
    value.put("checkpoints", snapshot.checkpoints());
    value.put("sourceEvidence", snapshot.sourceEvidence());
    var tuple = snapshot.authorityTuple();
    Map<String, Object> authority = new LinkedHashMap<>();
    authority.put("issuerAuthGeneration", tuple.issuerAuthGeneration());
    authority.put("accountAuthorityGeneration", tuple.accountAuthorityGeneration());
    authority.put("tenantAuthorityGeneration", tuple.tenantAuthorityGeneration());
    authority.put("membershipAuthorityGeneration", tuple.membershipAuthorityGeneration());
    authority.put("privateRealmGrantVersions", tuple.privateRealmGrantVersions());
    tuple
        .accountSecurityCutoff()
        .ifPresent(cutoff -> authority.put("accountSecurityCutoff", cutoff));
    tuple.tenantBillingCutoff().ifPresent(cutoff -> authority.put("tenantBillingCutoff", cutoff));
    value.put("authorityTuple", authority);
    value.put("realmGrantApplicability", "NOT_APPLICABLE_PUBLIC_PRODUCTION");
    return JSON.writeValueAsBytes(value);
  }

  private void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("Writable Account READ_COMMITTED transaction required");
    }
    dsl.connection(
        connection -> {
          if (connection.getAutoCommit()
              || connection.isReadOnly()
              || connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
            throw new IllegalStateException("Writable Account READ_COMMITTED transaction required");
          }
        });
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Exact Account CHARACTER_CREATE source unavailable");
  }

  private static IllegalStateException conflict() {
    return new IllegalStateException(
        "CHARACTER_CREATE conflicts with its original immutable intent");
  }

  public record Protection(
      AccountCharacterCreateIntent intent,
      UUID protectionId,
      long protectionFence,
      Instant acquiredAt,
      byte[] membershipSnapshot,
      byte[] entitlementSnapshot) {
    public Protection {
      Objects.requireNonNull(intent);
      AccountCharacterCreateIntent.requireId(protectionId);
      if (protectionFence <= 0L)
        throw new IllegalArgumentException("Positive Account fence required");
      Objects.requireNonNull(acquiredAt);
      membershipSnapshot = Objects.requireNonNull(membershipSnapshot).clone();
      entitlementSnapshot = Objects.requireNonNull(entitlementSnapshot).clone();
      if (membershipSnapshot.length == 0 || entitlementSnapshot.length == 0) throw unavailable();
    }

    @Override
    public byte[] membershipSnapshot() {
      return membershipSnapshot.clone();
    }

    @Override
    public byte[] entitlementSnapshot() {
      return entitlementSnapshot.clone();
    }

    public boolean sameBinding(Protection other) {
      return other != null
          && intent.sameBinding(other.intent)
          && protectionId.equals(other.protectionId)
          && protectionFence == other.protectionFence
          && acquiredAt.equals(other.acquiredAt)
          && Arrays.equals(membershipSnapshot, other.membershipSnapshot)
          && Arrays.equals(entitlementSnapshot, other.entitlementSnapshot);
    }
  }
}
