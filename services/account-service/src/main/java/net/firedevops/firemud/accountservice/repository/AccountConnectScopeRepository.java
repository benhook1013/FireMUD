package net.firedevops.firemud.accountservice.repository;

import static net.firedevops.firemud.accountservice.jooq.Tables.ACCOUNT_CONNECT_SCOPE_RECORDS;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.firedevops.firemud.accountservice.dto.AccountJoinDigest;
import net.firedevops.firemud.accountservice.dto.CanonicalJoinScopeV2;
import net.firedevops.firemud.accountservice.dto.VerifiedJoinScope;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.TenantProvenanceKind;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance;
import net.firedevops.firemud.accountservice.repository.ApprovedLegacyTenantAssociationRepository.ApprovedAssociation;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Durable, exact-byte scope evidence; its token hash is only a lookup key, never authority. */
@Repository
public class AccountConnectScopeRepository {
  private static final Pattern SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");
  private static final Pattern UTC_RFC3339 =
      Pattern.compile(
          "([0-9]{4})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2}):([0-9]{2})(?:\\.([0-9]+))?Z");

  private static final String DELETE_EXPIRED_UNREFERENCED_SQL =
      "DELETE FROM account_connect_scope_records scope "
          + "WHERE scope.ctid IN ("
          + "  SELECT candidate.ctid "
          + "  FROM account_connect_scope_records candidate "
          + "  WHERE CASE "
          + "    WHEN candidate.connect_scope_expires_at ~ "
          + "      '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,9})?Z$' "
          + "      AND pg_input_is_valid(candidate.connect_scope_expires_at, "
          + "          'timestamp with time zone') "
          + "    THEN candidate.connect_scope_expires_at::timestamptz <= CAST(? AS timestamptz) "
          + "    ELSE FALSE "
          + "  END "
          + "  AND candidate.scope_digest_version = 1 "
          + "  AND NOT EXISTS ("
          + "    SELECT 1 FROM account_join_operations operation "
          + "    WHERE operation.scope_token_hash = candidate.scope_token_hash"
          + "  ) "
          + "  ORDER BY candidate.account_id, candidate.scope_token_hash "
          + "  LIMIT ? "
          + "  FOR UPDATE OF candidate SKIP LOCKED"
          + ")";

  private final DSLContext dsl;
  private final AccountRepository accounts;
  private final AccountTenantIdentityResolver retainedTenantIdentities;
  private final FreshTenantIdentityAssociationRepository freshTenantIdentities;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "The constructor validates injected internal collaborators; it neither publishes this "
              + "nor invokes overridable methods.")
  public AccountConnectScopeRepository(
      DSLContext dsl,
      AccountRepository accounts,
      AccountTenantIdentityResolver retainedTenantIdentities,
      FreshTenantIdentityAssociationRepository freshTenantIdentities) {
    this.dsl = Objects.requireNonNull(dsl, "DSLContext is required");
    this.accounts = Objects.requireNonNull(accounts, "AccountRepository is required");
    this.retainedTenantIdentities =
        Objects.requireNonNull(
            retainedTenantIdentities, "Retained tenant identity reader is required");
    this.freshTenantIdentities =
        Objects.requireNonNull(freshTenantIdentities, "Fresh tenant identity reader is required");
  }

  /** Retains the exact legacy numeric representation without rewriting its v1 digest. */
  public void insert(VerifiedJoinScope scope) {
    dsl.insertInto(ACCOUNT_CONNECT_SCOPE_RECORDS)
        .set(
            ACCOUNT_CONNECT_SCOPE_RECORDS.SCOPE_TOKEN_HASH,
            AccountJoinDigest.tokenHash(scope.connectScopeId()))
        .set(ACCOUNT_CONNECT_SCOPE_RECORDS.ACCOUNT_ID, scope.accountId())
        .set(ACCOUNT_CONNECT_SCOPE_RECORDS.TARGET_CLASS, "PUBLIC_PRODUCTION")
        .set(ACCOUNT_CONNECT_SCOPE_RECORDS.TENANT_ID, scope.tenantId())
        .set(ACCOUNT_CONNECT_SCOPE_RECORDS.REALM_ID, scope.realmId())
        .set(ACCOUNT_CONNECT_SCOPE_RECORDS.WORLD_SLUG, scope.worldSlug())
        .set(ACCOUNT_CONNECT_SCOPE_RECORDS.REALM_SLUG, scope.realmSlug())
        .set(
            ACCOUNT_CONNECT_SCOPE_RECORDS.PLAYABLE_STATE_NAMESPACE_ID,
            scope.playableStateNamespaceId())
        .set(ACCOUNT_CONNECT_SCOPE_RECORDS.PLAYABLE_STATE_SCOPE, scope.playableStateScope())
        .set(ACCOUNT_CONNECT_SCOPE_RECORDS.GAME_INSTANCE_ID, scope.gameInstanceId())
        .set(ACCOUNT_CONNECT_SCOPE_RECORDS.CATALOG_REVISION, scope.catalogRevision())
        .set(ACCOUNT_CONNECT_SCOPE_RECORDS.POINTER_VERSION, scope.pointerVersion())
        .set(ACCOUNT_CONNECT_SCOPE_RECORDS.EVALUATED_AT, scope.evaluatedAt())
        .set(ACCOUNT_CONNECT_SCOPE_RECORDS.CONNECT_SCOPE_EXPIRES_AT, scope.connectScopeExpiresAt())
        .set(ACCOUNT_CONNECT_SCOPE_RECORDS.SNAPSHOT_DIGEST, scope.snapshotDigest())
        .execute();
  }

  public Optional<VerifiedJoinScope> find(String connectScopeId) {
    return dsl.selectFrom(ACCOUNT_CONNECT_SCOPE_RECORDS)
        .where(
            ACCOUNT_CONNECT_SCOPE_RECORDS.SCOPE_TOKEN_HASH.eq(
                AccountJoinDigest.tokenHash(connectScopeId)))
        .fetchOptional(record -> toScope(record, connectScopeId));
  }

  /**
   * Reads retained target evidence by its one-way token hash. This record cannot be used as a
   * connect-scope bearer and deliberately does not reconstruct or expose the plaintext token.
   */
  public Optional<ConnectScopeEvidence> findEvidenceByTokenHash(String scopeTokenHash) {
    if (scopeTokenHash == null || scopeTokenHash.isBlank()) {
      throw new IllegalArgumentException("JOIN scope token hash is required");
    }
    return dsl.selectFrom(ACCOUNT_CONNECT_SCOPE_RECORDS)
        .where(ACCOUNT_CONNECT_SCOPE_RECORDS.SCOPE_TOKEN_HASH.eq(scopeTokenHash))
        .fetchOptional(
            record -> {
              requireRetainedV1(record);
              return toEvidence(record);
            });
  }

  /**
   * Persists canonical UUID scope evidence beside retained v1 scope rows in the shared table.
   * Caller identity, current target routing, and entitlement authorization remain owner-service
   * responsibilities; this method proves only the supplied persisted Account and tenant sources.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void insertCanonical(
      long privateAccountId,
      CanonicalJoinScopeV2 scope,
      VerifiedTenantProvenance tenantProvenance) {
    requireOwnerTransaction();
    Objects.requireNonNull(scope, "Canonical JOIN scope is required");
    Objects.requireNonNull(tenantProvenance, "Verified tenant provenance is required");
    if (privateAccountId <= 0L) {
      throw new IllegalArgumentException("A positive private Account row key is required");
    }
    requireStoredScopeLengths(scope);
    requireOrderedScopeTimes(scope);
    verifyAccountIdentity(privateAccountId, scope.accountId());
    verifyTenantIdentity(scope.tenantId(), tenantProvenance);

    String tokenHash = AccountJoinDigest.tokenHash(scope.connectScopeId());
    String scopeDigest = AccountJoinDigest.scopeV2(scope);
    CanonicalConnectScopeEvidence expected =
        new CanonicalConnectScopeEvidence(
            tokenHash,
            privateAccountId,
            "PUBLIC_PRODUCTION",
            scope.accountId(),
            scope.tenantId(),
            scope.realmId(),
            scope.tenantSlug(),
            scope.worldSlug(),
            scope.realmSlug(),
            scope.playableStateNamespaceId(),
            scope.playableStateScope(),
            scope.gameInstanceId(),
            scope.catalogRevision(),
            scope.pointerVersion(),
            scope.evaluatedAt(),
            scope.connectScopeExpiresAt(),
            2,
            scopeDigest,
            tenantProvenance);

    int inserted =
        dsl.execute(
            "INSERT INTO account_connect_scope_records "
                + "(scope_token_hash, account_id, target_class, tenant_id, realm_id, world_slug, "
                + "realm_slug, playable_state_namespace_id, playable_state_scope, game_instance_id, "
                + "catalog_revision, pointer_version, evaluated_at, connect_scope_expires_at, "
                + "snapshot_digest, scope_digest_version, account_uuid, tenant_uuid, tenant_slug, "
                + "playable_state_namespace_uuid, game_instance_uuid, tenant_provenance_kind, "
                + "tenant_provenance_legacy_tenant_id, tenant_source_operation_id, "
                + "tenant_provenance_digest) "
                + "VALUES (?, ?, ?, NULL, ?, ?, ?, NULL, ?, NULL, ?, ?, ?, ?, ?, 2, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (scope_token_hash) DO NOTHING",
            expected.scopeTokenHash(),
            expected.privateAccountId(),
            expected.targetClass(),
            expected.realmId(),
            expected.worldSlug(),
            expected.realmSlug(),
            expected.playableStateScope(),
            expected.catalogRevision(),
            expected.pointerVersion(),
            expected.evaluatedAt(),
            expected.connectScopeExpiresAt(),
            expected.scopeDigest(),
            expected.accountUuid(),
            expected.tenantUuid(),
            expected.tenantSlug(),
            expected.playableStateNamespaceUuid(),
            expected.gameInstanceUuid(),
            expected.tenantProvenance().kind().name(),
            expected.tenantProvenance().legacyTenantId(),
            expected.tenantProvenance().sourceOperationId(),
            expected.tenantProvenance().digest());
    if (inserted < 0 || inserted > 1) {
      throw new IllegalStateException("Canonical Account connect scope insert was ambiguous");
    }

    CanonicalConnectScopeEvidence committed =
        readCanonicalEvidenceByTokenHash(tokenHash)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Canonical Account connect scope insert readback is absent"));
    if (!committed.equals(expected)) {
      throw new CanonicalScopeConflictException(
          "Canonical Account connect scope conflicts with immutable token-hash evidence");
    }
  }

  /**
   * Resolves a V2 scope only from the caller-supplied bearer and exact persisted owner evidence.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<CanonicalJoinScopeV2> findCanonical(String connectScopeId) {
    requireOwnerTransaction();
    Objects.requireNonNull(connectScopeId, "Connect scope bearer is required");
    if (connectScopeId.isBlank()) {
      throw new IllegalArgumentException("Connect scope bearer is required");
    }
    String tokenHash = AccountJoinDigest.tokenHash(connectScopeId);
    return readCanonicalEvidenceByTokenHash(tokenHash)
        .map(
            evidence -> {
              CanonicalJoinScopeV2 scope = toCanonicalScope(evidence, connectScopeId);
              requireStoredScopeLengths(scope);
              requireOrderedScopeTimes(scope);
              if (!evidence.scopeDigest().equals(AccountJoinDigest.scopeV2(scope))) {
                throw new IllegalStateException("Canonical JOIN scope digest mismatch");
              }
              return scope;
            });
  }

  /**
   * Reads canonical V2 recovery evidence by its one-way hash. It does not reconstruct the bearer or
   * recompute the token-dependent scope digest; callers must use owner operation evidence and exact
   * immutable cross-readback for recovery.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<CanonicalConnectScopeEvidence> findCanonicalEvidenceByTokenHash(
      String scopeTokenHash) {
    requireOwnerTransaction();
    requireSha256(scopeTokenHash, "JOIN scope token hash");
    return readCanonicalEvidenceByTokenHash(scopeTokenHash);
  }

  private Optional<CanonicalConnectScopeEvidence> readCanonicalEvidenceByTokenHash(
      String scopeTokenHash) {
    Record record =
        dsl.fetchOne(
            "SELECT scope_token_hash, account_id, target_class, tenant_id, realm_id, world_slug, "
                + "realm_slug, playable_state_namespace_id, playable_state_scope, game_instance_id, "
                + "catalog_revision, pointer_version, evaluated_at, connect_scope_expires_at, "
                + "snapshot_digest, scope_digest_version, account_uuid, tenant_uuid, tenant_slug, "
                + "playable_state_namespace_uuid, game_instance_uuid, tenant_provenance_kind, "
                + "tenant_provenance_legacy_tenant_id, tenant_source_operation_id, "
                + "tenant_provenance_digest FROM account_connect_scope_records "
                + "WHERE scope_token_hash = ?",
            scopeTokenHash);
    if (record == null) {
      return Optional.empty();
    }

    CanonicalConnectScopeEvidence evidence = toCanonicalEvidence(record);
    verifyAccountIdentity(evidence.privateAccountId(), evidence.accountUuid());
    verifyTenantIdentity(evidence.tenantUuid(), evidence.tenantProvenance());
    return Optional.of(evidence);
  }

  private void verifyAccountIdentity(long privateAccountId, UUID expectedAccountUuid) {
    Account account =
        accounts
            .findByIdForUpdate(privateAccountId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Canonical JOIN scope Account storage source is absent"));
    if (account.getId() == null
        || account.getId() != privateAccountId
        || !expectedAccountUuid.equals(account.getAccountUuid())
        || !Objects.equals(account.getAccountUuidSourceNumericId(), privateAccountId)
        || account.getAccountUuidProvenance() == null) {
      throw new IllegalStateException(
          "Canonical JOIN scope does not match the locked persisted Account identity");
    }
  }

  private void verifyTenantIdentity(
      UUID expectedTenantUuid, VerifiedTenantProvenance expectedProvenance) {
    if (expectedProvenance.kind() == TenantProvenanceKind.APPROVED_RETAINED) {
      ApprovedAssociation association = retainedTenantIdentities.resolve(expectedTenantUuid);
      if (!expectedTenantUuid.equals(association.canonicalTenantId())
          || !Objects.equals(expectedProvenance.legacyTenantId(), association.legacyTenantId())
          || !expectedProvenance.sourceOperationId().equals(association.operationId())
          || !expectedProvenance.digest().equals(association.manifestDigest())) {
        throw new IllegalStateException(
            "Canonical JOIN scope retained tenant provenance differs from owner readback");
      }
      return;
    }
    if (expectedProvenance.kind() == TenantProvenanceKind.FRESH_GAME_DESIGN) {
      FreshTenantCreationEvidence association =
          freshTenantIdentities
              .read(expectedTenantUuid)
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "Canonical JOIN scope fresh tenant source is absent"));
      if (!expectedTenantUuid.equals(association.canonicalTenantId())
          || expectedProvenance.legacyTenantId() != null
          || !expectedProvenance.sourceOperationId().equals(association.operationId())
          || !expectedProvenance.digest().equals(association.evidenceDigest())) {
        throw new IllegalStateException(
            "Canonical JOIN scope fresh tenant provenance differs from owner readback");
      }
      return;
    }
    throw new IllegalStateException("Canonical JOIN scope tenant provenance kind is unsupported");
  }

  private static CanonicalJoinScopeV2 toCanonicalScope(
      CanonicalConnectScopeEvidence evidence, String connectScopeId) {
    return new CanonicalJoinScopeV2(
        connectScopeId,
        evidence.accountUuid(),
        evidence.tenantUuid(),
        evidence.realmId(),
        evidence.tenantSlug(),
        evidence.worldSlug(),
        evidence.realmSlug(),
        evidence.playableStateNamespaceUuid(),
        evidence.playableStateScope(),
        evidence.gameInstanceUuid(),
        evidence.catalogRevision(),
        evidence.pointerVersion(),
        evidence.evaluatedAt(),
        evidence.connectScopeExpiresAt());
  }

  private static CanonicalConnectScopeEvidence toCanonicalEvidence(Record record) {
    Integer digestVersion = record.get("scope_digest_version", Integer.class);
    if (!Integer.valueOf(2).equals(digestVersion)) {
      throw new IllegalStateException("JOIN scope is not canonical digest version 2");
    }
    if (record.get("tenant_id", Long.class) != null
        || record.get("game_instance_id", Long.class) != null
        || record.get("playable_state_namespace_id", String.class) != null) {
      throw new IllegalStateException("Canonical JOIN scope contains a numeric identity alias");
    }

    String sourceKind = required(record.get("tenant_provenance_kind", String.class), "tenant kind");
    TenantProvenanceKind tenantKind;
    try {
      tenantKind = TenantProvenanceKind.valueOf(sourceKind);
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException(
          "Canonical JOIN scope tenant provenance is invalid", exception);
    }
    VerifiedTenantProvenance tenantProvenance =
        new VerifiedTenantProvenance(
            record.get("tenant_provenance_legacy_tenant_id", Long.class),
            tenantKind,
            required(
                record.get("tenant_source_operation_id", UUID.class), "tenant source operation"),
            required(record.get("tenant_provenance_digest", String.class), "tenant source digest"));
    return new CanonicalConnectScopeEvidence(
        required(record.get("scope_token_hash", String.class), "scope token hash"),
        required(record.get("account_id", Long.class), "private Account row key"),
        required(record.get("target_class", String.class), "scope target"),
        required(record.get("account_uuid", UUID.class), "Account UUID"),
        required(record.get("tenant_uuid", UUID.class), "tenant UUID"),
        required(record.get("realm_id", UUID.class), "realm UUID"),
        required(record.get("tenant_slug", String.class), "tenant slug"),
        required(record.get("world_slug", String.class), "world slug"),
        required(record.get("realm_slug", String.class), "realm slug"),
        required(
            record.get("playable_state_namespace_uuid", UUID.class), "playable namespace UUID"),
        required(record.get("playable_state_scope", String.class), "playable state scope"),
        required(record.get("game_instance_uuid", UUID.class), "game instance UUID"),
        required(record.get("catalog_revision", Long.class), "catalog revision"),
        required(record.get("pointer_version", Long.class), "pointer version"),
        required(record.get("evaluated_at", String.class), "scope evaluation time"),
        required(record.get("connect_scope_expires_at", String.class), "scope expiry time"),
        digestVersion,
        required(record.get("snapshot_digest", String.class), "scope digest"),
        tenantProvenance);
  }

  private static <T> T required(T value, String field) {
    if (value == null) {
      throw new IllegalStateException("Canonical JOIN scope is missing " + field);
    }
    return value;
  }

  private static void requireSha256(String value, String field) {
    if (value == null || !SHA256.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " must be a lowercase SHA-256 digest");
    }
  }

  private static void requireOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Canonical Account connect scope access requires an active owner transaction");
    }
  }

  private static void requireOrderedScopeTimes(CanonicalJoinScopeV2 scope) {
    requireOrderedScopeTimes(scope.evaluatedAt(), scope.connectScopeExpiresAt());
  }

  private static void requireOrderedScopeTimes(String evaluatedAtText, String expiresAtText) {
    BigDecimal evaluatedAt = epochSeconds(evaluatedAtText);
    BigDecimal expiresAt = epochSeconds(expiresAtText);
    if (expiresAt.compareTo(evaluatedAt) <= 0) {
      throw new IllegalArgumentException(
          "Canonical JOIN scope expiry must be after its exact evaluation time");
    }
  }

  private static void requireStoredScopeLengths(CanonicalJoinScopeV2 scope) {
    requireDatabaseCharacterLimit("tenantSlug", scope.tenantSlug(), 128);
    requireDatabaseCharacterLimit("worldSlug", scope.worldSlug(), 128);
    requireDatabaseCharacterLimit("realmSlug", scope.realmSlug(), 128);
    requireDatabaseCharacterLimit("evaluatedAt", scope.evaluatedAt(), 40);
    requireDatabaseCharacterLimit("connectScopeExpiresAt", scope.connectScopeExpiresAt(), 40);
  }

  private static void requireDatabaseCharacterLimit(String field, String value, int limit) {
    if (value.codePointCount(0, value.length()) > limit) {
      throw new IllegalArgumentException(
          "Canonical JOIN scope " + field + " exceeds its durable storage limit");
    }
  }

  /** Compares exact RFC3339 seconds without changing or truncating their digest bytes. */
  private static BigDecimal epochSeconds(String timestamp) {
    Matcher matcher = UTC_RFC3339.matcher(timestamp);
    if (!matcher.matches()) {
      throw new IllegalArgumentException("Canonical JOIN scope time is not exact UTC RFC3339");
    }
    LocalDate date =
        LocalDate.of(
            Integer.parseInt(matcher.group(1)),
            Integer.parseInt(matcher.group(2)),
            Integer.parseInt(matcher.group(3)));
    int hour = Integer.parseInt(matcher.group(4));
    int minute = Integer.parseInt(matcher.group(5));
    int second = Integer.parseInt(matcher.group(6));
    if (hour > 23 || minute > 59 || second > 60) {
      throw new IllegalArgumentException("Canonical JOIN scope time is not valid UTC RFC3339");
    }
    long wholeSeconds =
        date.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
            + (hour * 60L + minute) * 60L
            + Math.min(second, 59);
    if (second == 60) {
      wholeSeconds++;
    }
    BigDecimal exactTime = BigDecimal.valueOf(wholeSeconds);
    String fraction = matcher.group(7);
    if (fraction != null) {
      exactTime = exactTime.add(new BigDecimal("0." + fraction));
    }
    return exactTime;
  }

  private static ConnectScopeEvidence toEvidence(Record record) {
    return new ConnectScopeEvidence(
        record.get(ACCOUNT_CONNECT_SCOPE_RECORDS.SCOPE_TOKEN_HASH),
        record.get(ACCOUNT_CONNECT_SCOPE_RECORDS.ACCOUNT_ID),
        record.get(ACCOUNT_CONNECT_SCOPE_RECORDS.TARGET_CLASS),
        record.get(ACCOUNT_CONNECT_SCOPE_RECORDS.TENANT_ID),
        record.get(ACCOUNT_CONNECT_SCOPE_RECORDS.REALM_ID),
        record.get(ACCOUNT_CONNECT_SCOPE_RECORDS.WORLD_SLUG),
        record.get(ACCOUNT_CONNECT_SCOPE_RECORDS.REALM_SLUG),
        record.get(ACCOUNT_CONNECT_SCOPE_RECORDS.PLAYABLE_STATE_NAMESPACE_ID),
        record.get(ACCOUNT_CONNECT_SCOPE_RECORDS.PLAYABLE_STATE_SCOPE),
        record.get(ACCOUNT_CONNECT_SCOPE_RECORDS.GAME_INSTANCE_ID),
        record.get(ACCOUNT_CONNECT_SCOPE_RECORDS.CATALOG_REVISION),
        record.get(ACCOUNT_CONNECT_SCOPE_RECORDS.POINTER_VERSION),
        record.get(ACCOUNT_CONNECT_SCOPE_RECORDS.EVALUATED_AT),
        record.get(ACCOUNT_CONNECT_SCOPE_RECORDS.CONNECT_SCOPE_EXPIRES_AT),
        record.get(ACCOUNT_CONNECT_SCOPE_RECORDS.SNAPSHOT_DIGEST));
  }

  /**
   * Deletes a bounded batch of expired scopes only when no JOIN receipt references them. Malformed
   * expiry values are retained. The database FK from JOIN receipts is the concurrent insert fence
   * for the anti-join predicate.
   */
  public int deleteExpiredUnreferenced(Instant capturedNow, int batchSize) {
    if (capturedNow == null) {
      throw new IllegalArgumentException("Captured cleanup time is required");
    }
    if (batchSize <= 0) {
      throw new IllegalArgumentException("Cleanup batch size must be positive");
    }
    return dsl.execute(
        DELETE_EXPIRED_UNREFERENCED_SQL,
        OffsetDateTime.ofInstant(capturedNow, ZoneOffset.UTC),
        batchSize);
  }

  private VerifiedJoinScope toScope(Record record, String connectScopeId) {
    requireRetainedV1(record);
    if (!"PUBLIC_PRODUCTION".equals(record.get(ACCOUNT_CONNECT_SCOPE_RECORDS.TARGET_CLASS))) {
      throw new IllegalStateException("JOIN scope is not public production");
    }
    VerifiedJoinScope scope =
        new VerifiedJoinScope(
            connectScopeId,
            record.get(ACCOUNT_CONNECT_SCOPE_RECORDS.ACCOUNT_ID),
            record.get(ACCOUNT_CONNECT_SCOPE_RECORDS.TENANT_ID),
            record.get(ACCOUNT_CONNECT_SCOPE_RECORDS.REALM_ID),
            record.get(ACCOUNT_CONNECT_SCOPE_RECORDS.WORLD_SLUG),
            record.get(ACCOUNT_CONNECT_SCOPE_RECORDS.REALM_SLUG),
            record.get(ACCOUNT_CONNECT_SCOPE_RECORDS.PLAYABLE_STATE_NAMESPACE_ID),
            record.get(ACCOUNT_CONNECT_SCOPE_RECORDS.PLAYABLE_STATE_SCOPE),
            record.get(ACCOUNT_CONNECT_SCOPE_RECORDS.GAME_INSTANCE_ID),
            record.get(ACCOUNT_CONNECT_SCOPE_RECORDS.CATALOG_REVISION),
            record.get(ACCOUNT_CONNECT_SCOPE_RECORDS.POINTER_VERSION),
            record.get(ACCOUNT_CONNECT_SCOPE_RECORDS.EVALUATED_AT),
            record.get(ACCOUNT_CONNECT_SCOPE_RECORDS.CONNECT_SCOPE_EXPIRES_AT),
            record.get(ACCOUNT_CONNECT_SCOPE_RECORDS.SNAPSHOT_DIGEST));
    if (!scope.snapshotDigest().equals(AccountJoinDigest.scope(scope))) {
      throw new IllegalStateException("JOIN scope digest mismatch");
    }
    return scope;
  }

  private static void requireRetainedV1(Record record) {
    Integer version = record.get("scope_digest_version", Integer.class);
    if (!Integer.valueOf(1).equals(version)) {
      throw new IllegalStateException("JOIN scope is not retained digest version 1");
    }
  }

  /** Hash-only V2 recovery evidence; intentionally contains no connectScopeId bearer. */
  public record CanonicalConnectScopeEvidence(
      String scopeTokenHash,
      long privateAccountId,
      String targetClass,
      UUID accountUuid,
      UUID tenantUuid,
      UUID realmId,
      String tenantSlug,
      String worldSlug,
      String realmSlug,
      UUID playableStateNamespaceUuid,
      String playableStateScope,
      UUID gameInstanceUuid,
      long catalogRevision,
      long pointerVersion,
      String evaluatedAt,
      String connectScopeExpiresAt,
      int scopeDigestVersion,
      String scopeDigest,
      VerifiedTenantProvenance tenantProvenance) {
    public CanonicalConnectScopeEvidence {
      requireSha256(scopeTokenHash, "JOIN scope token hash");
      if (privateAccountId <= 0L
          || !"PUBLIC_PRODUCTION".equals(targetClass)
          || accountUuid == null
          || accountUuid.equals(new UUID(0L, 0L))
          || tenantUuid == null
          || tenantUuid.equals(new UUID(0L, 0L))
          || realmId == null
          || realmId.equals(new UUID(0L, 0L))
          || tenantSlug == null
          || tenantSlug.isEmpty()
          || worldSlug == null
          || worldSlug.isEmpty()
          || realmSlug == null
          || realmSlug.isEmpty()
          || playableStateNamespaceUuid == null
          || playableStateNamespaceUuid.equals(new UUID(0L, 0L))
          || playableStateScope == null
          || (!"SHARED".equals(playableStateScope) && !"ISOLATED".equals(playableStateScope))
          || gameInstanceUuid == null
          || gameInstanceUuid.equals(new UUID(0L, 0L))
          || catalogRevision <= 0L
          || pointerVersion <= 0L
          || evaluatedAt == null
          || connectScopeExpiresAt == null
          || scopeDigestVersion != 2
          || tenantProvenance == null) {
        throw new IllegalArgumentException("Canonical JOIN scope evidence is incomplete");
      }
      requireDatabaseCharacterLimit("tenantSlug", tenantSlug, 128);
      requireDatabaseCharacterLimit("worldSlug", worldSlug, 128);
      requireDatabaseCharacterLimit("realmSlug", realmSlug, 128);
      requireDatabaseCharacterLimit("evaluatedAt", evaluatedAt, 40);
      requireDatabaseCharacterLimit("connectScopeExpiresAt", connectScopeExpiresAt, 40);
      requireOrderedScopeTimes(evaluatedAt, connectScopeExpiresAt);
      requireSha256(scopeDigest, "Canonical JOIN scope digest");
    }
  }

  /** Conflicting reuse of one scope-token hash cannot replace the original scope evidence. */
  public static final class CanonicalScopeConflictException extends IllegalStateException {
    public CanonicalScopeConflictException(String message) {
      super(message);
    }
  }

  public record ConnectScopeEvidence(
      String scopeTokenHash,
      long accountId,
      String targetClass,
      long tenantId,
      UUID realmId,
      String worldSlug,
      String realmSlug,
      String playableStateNamespaceId,
      String playableStateScope,
      long gameInstanceId,
      long catalogRevision,
      long pointerVersion,
      String evaluatedAt,
      String connectScopeExpiresAt,
      String snapshotDigest) {}
}
