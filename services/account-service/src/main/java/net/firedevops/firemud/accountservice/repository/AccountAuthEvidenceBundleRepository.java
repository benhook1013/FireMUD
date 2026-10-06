package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.accountservice.dto.AccountAuthEvidenceBundle;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.CompositeSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.CurrentSourceEvidence;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.IssuerAccountSourceSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.SourceCheckpoint;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Account-local persistence boundary for immutable, non-authorizing delegation evidence bundles.
 *
 * <p>Capture consumes the Account-owned, codec-verified issuer/account source snapshot in the same
 * transaction as the locked PENDING operation. It never accepts caller-supplied bundle bytes or
 * synthesizes an empty checkpoint. The source repository proves a zero checkpoint only from its
 * exact fresh-initialization provenance and exhaustive empty history; retained or ambiguous history
 * fails closed.
 */
@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected jOOQ and Account owner repositories remain internal collaborators.")
public class AccountAuthEvidenceBundleRepository {
  private static final String ISSUER_STREAM =
      "account:auth-authority:v1:issuer/" + GameSessionAccountDelegationProfile.ISSUER;
  private static final String OPERATION_TABLE = "account_gameplay_delegation_issuance_operations";
  private static final String BUNDLE_TABLE = "account_gameplay_delegation_auth_evidence_bundles";
  private static final Pattern V4_UUID =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern GAME_SESSION_WORKLOAD =
      Pattern.compile(
          "^spiffe://firemud/ns/[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?/sa/game-session-service$");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final DSLContext dsl;
  private final AccountAuthorityGenerationRepository authorityGenerations;
  private final AccountAuthoritySourceEvidenceRepository sourceEvidence;

  public AccountAuthEvidenceBundleRepository(
      DSLContext dsl,
      AccountAuthorityGenerationRepository authorityGenerations,
      AccountAuthorityOutboxRepository authorityOutbox) {
    this.dsl = Objects.requireNonNull(dsl, "DSLContext is required");
    this.authorityGenerations =
        Objects.requireNonNull(authorityGenerations, "Account authority repository is required");
    Objects.requireNonNull(authorityOutbox, "Account authority outbox is required");
    this.sourceEvidence =
        new AccountAuthoritySourceEvidenceRepository(dsl, authorityGenerations, authorityOutbox);
  }

  /**
   * Captures and immutably persists source evidence for the exact Account-owned PENDING row. Caller
   * data supplies only the lookup identity; every value in the bundle is read from locked Account
   * operation, identity, authority, source, and retained-event rows.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public AccountAuthEvidenceBundle captureAndPersist(UUID requestId) {
    requireWritableAccountTransaction();
    Objects.requireNonNull(requestId, "Request ID is required");
    // This first read only discovers immutable row identity. It does not authorize capture or lock
    // the operation before the Account and composite authority rows.
    Record preliminary =
        dsl.fetchOne(
            "SELECT operation_id, request_id, account_uuid FROM "
                + OPERATION_TABLE
                + " WHERE request_id = ?",
            requestId);
    if (preliminary == null) throw new OperationNotFoundException();
    UUID preliminaryRequestId = preliminary.get("request_id", UUID.class);
    UUID preliminaryOperationId = preliminary.get("operation_id", UUID.class);
    UUID preliminaryAccountId = preliminary.get("account_uuid", UUID.class);
    if (!requestId.equals(preliminaryRequestId)
        || !canonicalV4(preliminaryOperationId)
        || preliminaryAccountId == null) {
      throw new OwnerEvidenceUnavailableException();
    }

    Record account =
        dsl.fetchOne(
            "SELECT id, account_uuid, account_uuid_provenance, account_uuid_source_numeric_id "
                + "FROM accounts WHERE account_uuid = ? FOR SHARE",
            preliminaryAccountId);
    if (account == null) throw new OwnerEvidenceUnavailableException();
    long rowId = positive(account.get("id", Long.class));
    long sourceId = positive(account.get("account_uuid_source_numeric_id", Long.class));
    AccountIdentityProvenance provenance;
    try {
      provenance =
          AccountIdentityProvenance.fromStorageValue(
              account.get("account_uuid_provenance", String.class));
    } catch (RuntimeException ex) {
      throw new OwnerEvidenceUnavailableException();
    }
    if (!preliminaryAccountId.equals(account.get("account_uuid", UUID.class))
        || rowId != sourceId
        || !AccountIdentityProvenance.isAccepted(provenance)) {
      throw new OwnerEvidenceUnavailableException();
    }

    CompositeSnapshot current =
        authorityGenerations.readCompositeSnapshot(
            GameSessionAccountDelegationProfile.ISSUER, preliminaryAccountId, List.of(), List.of());

    // Re-read and lock the operation only after the Account and authority snapshot are locked.
    // Every identity/status/authority field is checked again; the preliminary lookup is never
    // evidence for capture.
    Record operation =
        dsl.fetchOne(
            "SELECT * FROM " + OPERATION_TABLE + " WHERE request_id = ? FOR UPDATE", requestId);
    if (operation == null) throw new OwnerEvidenceUnavailableException();
    UUID storedRequestId = operation.get("request_id", UUID.class);
    UUID operationId = operation.get("operation_id", UUID.class);
    UUID accountId = operation.get("account_uuid", UUID.class);
    UUID callerContextId = operation.get("caller_context_id", UUID.class);
    UUID tokenJti = operation.get("token_jti", UUID.class);
    String requestDigest = operation.get("request_digest", String.class);
    String callerWorkload = operation.get("caller_workload", String.class);
    if (!requestId.equals(storedRequestId)
        || !requestId.equals(preliminaryRequestId)
        || !preliminaryOperationId.equals(operationId)
        || !preliminaryAccountId.equals(accountId)
        || !canonicalV4(operationId)
        || !canonicalV4(callerContextId)
        || !canonicalV4(tokenJti)
        || requestDigest == null
        || !SHA256.matcher(requestDigest).matches()
        || !"PENDING".equals(operation.get("status", String.class))
        || callerWorkload == null
        || !GAME_SESSION_WORKLOAD.matcher(callerWorkload).matches()) {
      throw new OwnerEvidenceUnavailableException();
    }
    requireExactPendingAuthority(operation, current, accountId);
    final IssuerAccountSourceSnapshot sources;
    try {
      sources =
          sourceEvidence.readCurrentIssuerAccountSources(
              GameSessionAccountDelegationProfile.ISSUER, accountId);
    } catch (RuntimeException ex) {
      throw new OwnerEvidenceUnavailableException();
    }
    requireExactSourceSnapshot(sources, current, operation, account, accountId);

    Record prior =
        dsl.fetchOne(
            "SELECT operation_id FROM " + BUNDLE_TABLE + " WHERE operation_id = ?", operationId);
    if (prior != null) {
      AccountAuthEvidenceBundle stored = readStoredNonAuthorizingValue(operationId);
      AccountAuthEvidenceBundle expected =
          makeBundle(operation, account, sources, storedBundleReference(stored));
      if (!MessageDigest.isEqual(expected.canonicalBytes(), stored.canonicalBytes())) {
        throw new OwnerEvidenceUnavailableException();
      }
      return stored;
    }

    String transactionId = currentTransactionId();
    String sourceFence = allocateSourceFence(operationId, transactionId);
    AccountAuthEvidenceBundle.BundleReference bundleReference =
        new AccountAuthEvidenceBundle.BundleReference(
            "1", transactionId, sourceFence, transactionId);
    AccountAuthEvidenceBundle candidate = makeBundle(operation, account, sources, bundleReference);
    int inserted =
        dsl.execute(
            "INSERT INTO "
                + BUNDLE_TABLE
                + " (operation_id, request_id, account_uuid, bundle_schema, bundle_version, "
                + "source_version, source_fence, issuance_fence, linearization, canonical_sha256, "
                + "canonical_bundle_bytes) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT DO NOTHING",
            operationId,
            requestId,
            accountId,
            AccountAuthEvidenceBundle.SCHEMA,
            bundleReference.bundleVersion(),
            bundleReference.sourceVersion(),
            bundleReference.sourceFence(),
            Long.toString(sources.issuanceFence().value()),
            bundleReference.linearization(),
            candidate.canonicalSha256(),
            candidate.canonicalBytes());
    AccountAuthEvidenceBundle readback = readStoredNonAuthorizingValue(operationId);
    if (inserted != 1
        || !MessageDigest.isEqual(candidate.canonicalBytes(), readback.canonicalBytes())) {
      throw new OwnerEvidenceUnavailableException();
    }
    return readback;
  }

  /**
   * Returns a strictly parsed immutable value for diagnostics/reconciliation only; this method does
   * not revalidate or grant source freshness, promotion, activation, or caller authority.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public AccountAuthEvidenceBundle readStoredNonAuthorizingValue(UUID operationId) {
    Objects.requireNonNull(operationId, "Operation ID is required");
    Record row =
        dsl.fetchOne(
            "SELECT operation_id, request_id, account_uuid, bundle_schema, canonical_sha256, "
                + "bundle_version, source_version, source_fence, issuance_fence, linearization, "
                + "canonical_bundle_bytes FROM "
                + BUNDLE_TABLE
                + " WHERE operation_id = ?",
            operationId);
    if (row == null) throw new StoredBundleUnavailableException();
    try {
      if (!operationId.equals(row.get("operation_id", UUID.class))) {
        throw new StoredBundleUnavailableException();
      }
      AccountAuthEvidenceBundle bundle =
          AccountAuthEvidenceBundle.parseCanonical(row.get("canonical_bundle_bytes", byte[].class));
      String digest = row.get("canonical_sha256", String.class);
      if (!MessageDigest.isEqual(
              bundle.canonicalSha256().getBytes(StandardCharsets.US_ASCII),
              Objects.toString(digest, "").getBytes(StandardCharsets.US_ASCII))
          || !AccountAuthEvidenceBundle.SCHEMA.equals(row.get("bundle_schema", String.class))
          || !Objects.equals(
              operationId.toString(), nestedText(bundle.fields(), "operation", "operationId"))
          || !Objects.equals(
              row.get("request_id", UUID.class).toString(),
              nestedText(bundle.fields(), "operation", "requestId"))
          || !Objects.equals(
              row.get("account_uuid", UUID.class).toString(),
              nestedText(bundle.fields(), "scope", "accountId"))
          || !Objects.equals(
              row.get("bundle_version", String.class),
              nestedText(bundle.fields(), "bundleRef", "bundleVersion"))
          || !Objects.equals(
              row.get("source_version", String.class),
              nestedText(bundle.fields(), "bundleRef", "sourceVersion"))
          || !Objects.equals(
              row.get("source_fence", String.class),
              nestedText(bundle.fields(), "bundleRef", "sourceFence"))
          || !Objects.equals(
              row.get("issuance_fence", String.class),
              Objects.toString(bundle.fields().get("issuanceFence"), null))
          || !Objects.equals(
              row.get("linearization", String.class),
              nestedText(bundle.fields(), "bundleRef", "linearization"))) {
        throw new StoredBundleUnavailableException();
      }
      return bundle;
    } catch (RuntimeException ex) {
      if (ex instanceof StoredBundleUnavailableException unavailable) throw unavailable;
      throw new StoredBundleUnavailableException();
    }
  }

  private static void requireExactPendingAuthority(
      Record operation, CompositeSnapshot snapshot, UUID accountId) {
    if (snapshot.account().scope().kind() != AccountAuthorityGenerationRepository.ScopeKind.ACCOUNT
        || !accountId.equals(snapshot.account().scope().accountId())
        || snapshot.account().scope().issuerId() != null
        || snapshot.account().scope().tenantId() != null
        || snapshot.issuer().scope().kind() != AccountAuthorityGenerationRepository.ScopeKind.ISSUER
        || !GameSessionAccountDelegationProfile.ISSUER.equals(snapshot.issuer().scope().issuerId())
        || snapshot.issuer().scope().accountId() != null
        || snapshot.issuer().scope().tenantId() != null
        || !snapshot.tenants().isEmpty()
        || !snapshot.memberships().isEmpty()
        || !Objects.equals(
            operation.get("authority_issuer_generation", Long.class),
            snapshot.issuer().generation())
        || !Objects.equals(
            operation.get("authority_issuer_source_version", Long.class),
            snapshot.issuer().sourceVersion())
        || !Objects.equals(
            operation.get("authority_account_generation", Long.class),
            snapshot.account().generation())
        || !Objects.equals(
            operation.get("authority_account_source_version", Long.class),
            snapshot.account().sourceVersion())
        || !Objects.equals(
            operation.get("issuance_fence", Long.class), snapshot.issuanceFence().value())
        || !Objects.equals(
            operation.get("issuance_fence_source_version", Long.class),
            snapshot.issuanceFence().sourceVersion())
        || !accountId.equals(snapshot.issuanceFence().accountId())) {
      throw new OwnerEvidenceUnavailableException();
    }
  }

  private static void requireExactSourceSnapshot(
      IssuerAccountSourceSnapshot sources,
      CompositeSnapshot current,
      Record operation,
      Record account,
      UUID accountId) {
    CurrentSourceEvidence issuer = sources.issuer();
    CurrentSourceEvidence accountSource = sources.account();
    String accountStream = "account:auth-authority:v1:account/" + accountId;
    if (issuer.scope().kind() != AccountAuthorityGenerationRepository.ScopeKind.ISSUER
        || !GameSessionAccountDelegationProfile.ISSUER.equals(issuer.scope().issuerId())
        || issuer.scope().accountId() != null
        || issuer.scope().tenantId() != null
        || accountSource.scope().kind() != AccountAuthorityGenerationRepository.ScopeKind.ACCOUNT
        || !accountId.equals(accountSource.scope().accountId())
        || accountSource.scope().issuerId() != null
        || accountSource.scope().tenantId() != null
        || !ISSUER_STREAM.equals(issuer.checkpoint().outboxStreamKey())
        || !accountStream.equals(accountSource.checkpoint().outboxStreamKey())
        || issuer.generation() != current.issuer().generation()
        || issuer.sourceVersion() != current.issuer().sourceVersion()
        || accountSource.generation() != current.account().generation()
        || accountSource.sourceVersion() != current.account().sourceVersion()
        || !Objects.equals(accountSource.issuanceFence(), sources.issuanceFence())
        || !Objects.equals(sources.issuanceFence(), current.issuanceFence())
        || !Objects.equals(
            operation.get("authority_issuer_generation", Long.class), issuer.generation())
        || !Objects.equals(
            operation.get("authority_issuer_source_version", Long.class), issuer.sourceVersion())
        || !Objects.equals(
            operation.get("authority_account_generation", Long.class), accountSource.generation())
        || !Objects.equals(
            operation.get("authority_account_source_version", Long.class),
            accountSource.sourceVersion())
        || !Objects.equals(
            operation.get("issuance_fence", Long.class), sources.issuanceFence().value())
        || !Objects.equals(
            operation.get("issuance_fence_source_version", Long.class),
            sources.issuanceFence().sourceVersion())
        || !"ISSUER_SCOPE_INSERT".equals(issuer.initializationProvenance())
        || !"ACCOUNT_REPOSITORY_INSERT".equals(accountSource.initializationProvenance())
        || !Objects.equals(
            positive(account.get("id", Long.class)), accountSource.accountSourceNumericId())
        || !Objects.equals(
            account.get("account_uuid_provenance", String.class),
            accountSource.accountUuidProvenance())) {
      throw new OwnerEvidenceUnavailableException();
    }
    Map<String, Object> tuple = authorityTuple(sources);
    if (!MessageDigest.isEqual(
            canonicalJson(tuple), requiredBytes(operation, "authority_tuple_canonical_bytes"))
        || !MessageDigest.isEqual(
            requiredBytes(operation, "membership_version_canonical_bytes"),
            "{}".getBytes(StandardCharsets.US_ASCII))) {
      // The exact current tuple, including an applicable Account security cutoff, must match the
      // immutable PENDING operation. A baseline-only or synthesized-empty tuple is not accepted.
      throw new OwnerEvidenceUnavailableException();
    }
  }

  private AccountAuthEvidenceBundle makeBundle(
      Record operation,
      Record account,
      IssuerAccountSourceSnapshot sources,
      AccountAuthEvidenceBundle.BundleReference bundleReference) {
    UUID accountId = requiredUuid(operation, "account_uuid");
    UUID operationId = requiredUuid(operation, "operation_id");
    UUID requestId = requiredUuid(operation, "request_id");
    UUID callerContextId = requiredUuid(operation, "caller_context_id");
    UUID tokenJti = requiredUuid(operation, "token_jti");
    String requestDigest = requiredText(operation, "request_digest");
    String callerWorkload = requiredText(operation, "caller_workload");
    long issuedAt = positive(operation.get("issued_at_epoch_second", Long.class));
    long notBefore = positive(operation.get("not_before_epoch_second", Long.class));
    long expiresAt = positive(operation.get("expires_at_epoch_second", Long.class));
    long tokenGeneration = positive(operation.get("token_generation", Long.class));
    Map<String, Object> tuple = authorityTuple(sources);
    Map<String, Object> sourceVersions = sourceVersions(sources);
    List<AccountAuthEvidenceBundle.OutboxCheckpoint> checkpoints =
        List.of(
            checkpoint(sources.account().checkpoint()), checkpoint(sources.issuer().checkpoint()));
    long sourceRowId = positive(account.get("id", Long.class));
    AccountIdentityProvenance provenance =
        AccountIdentityProvenance.fromStorageValue(
            requiredText(account, "account_uuid_provenance"));
    long sourceNumericId = positive(account.get("account_uuid_source_numeric_id", Long.class));
    AccountAuthEvidenceBundle.AccountIdentitySource identitySource =
        new AccountAuthEvidenceBundle.AccountIdentitySource(
            sourceRowId, provenance, sourceNumericId);
    String snapshotIdentity =
        sha256(
            canonicalJson(
                snapshotIdentityPreimage(
                    sources, accountId, identitySource, tuple, sourceVersions)));
    String evaluationIdentity =
        sha256(
            canonicalJson(
                evaluationIdentityPreimage(
                    snapshotIdentity,
                    bundleReference,
                    operationId,
                    requestId,
                    requestDigest,
                    callerWorkload,
                    callerContextId,
                    accountId,
                    tokenJti,
                    tokenGeneration,
                    issuedAt,
                    notBefore,
                    expiresAt)));
    return AccountAuthEvidenceBundle.fromOwnerEvaluation(
        new AccountAuthEvidenceBundle.OwnerEvaluation(
            new AccountAuthEvidenceBundle.BundleReference(
                bundleReference.bundleVersion(),
                bundleReference.sourceVersion(),
                bundleReference.sourceFence(),
                bundleReference.linearization()),
            snapshotIdentity,
            evaluationIdentity,
            accountId,
            new AccountAuthEvidenceBundle.OperationIdentity(
                operationId, requestId, requestDigest, callerWorkload, callerContextId, accountId),
            new AccountAuthEvidenceBundle.TokenIdentity(
                tokenJti, tokenGeneration, issuedAt, notBefore, expiresAt),
            tuple,
            sources.issuanceFence().value(),
            new AccountAuthEvidenceBundle.AuthoritySourceVersions(
                sources.issuer().sourceVersion(),
                sources.account().sourceVersion(),
                sources.issuanceFence().sourceVersion()),
            identitySource,
            checkpoints));
  }

  private static Map<String, Object> authorityTuple(IssuerAccountSourceSnapshot sources) {
    return GameSessionAccountDelegationProfile.authorityTuple(
        sources.issuer().generation(),
        sources.account().generation(),
        sources
            .account()
            .accountSecurityCutoff()
            .map(
                cutoff ->
                    new GameSessionAccountDelegationProfile.AccountSecurityCutoff(
                        cutoff.accountAuthorityGeneration(),
                        cutoff.outboxStreamKey(),
                        cutoff.outboxSequence())));
  }

  private static Map<String, Object> sourceVersions(IssuerAccountSourceSnapshot sources) {
    Map<String, Object> versions = new LinkedHashMap<>();
    versions.put("issuerSourceVersion", Long.toString(sources.issuer().sourceVersion()));
    versions.put("accountSourceVersion", Long.toString(sources.account().sourceVersion()));
    versions.put(
        "issuanceFenceSourceVersion", Long.toString(sources.issuanceFence().sourceVersion()));
    return versions;
  }

  private static AccountAuthEvidenceBundle.OutboxCheckpoint checkpoint(SourceCheckpoint source) {
    return new AccountAuthEvidenceBundle.OutboxCheckpoint(
        source.outboxStreamKey(),
        source.sequence(),
        source.sourceEventId().orElse(null),
        source.sourceEventDigest().orElse(null));
  }

  private static Map<String, Object> snapshotIdentityPreimage(
      IssuerAccountSourceSnapshot sources,
      UUID accountId,
      AccountAuthEvidenceBundle.AccountIdentitySource identitySource,
      Map<String, Object> tuple,
      Map<String, Object> sourceVersions) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("domain", "firemud/account-auth-evidence/snapshot/v1");
    snapshot.put("schema", AccountAuthEvidenceBundle.SCHEMA);
    snapshot.put("issuer", GameSessionAccountDelegationProfile.ISSUER);
    snapshot.put("profile", GameSessionAccountDelegationProfile.PROFILE);
    snapshot.put("accountId", accountId.toString());
    snapshot.put("authorityTuple", tuple);
    snapshot.put("issuanceFence", Long.toString(sources.issuanceFence().value()));
    snapshot.put("authoritySourceVersions", sourceVersions);
    snapshot.put("issuerCheckpoint", checkpointFields(sources.issuer().checkpoint()));
    snapshot.put("accountCheckpoint", checkpointFields(sources.account().checkpoint()));
    snapshot.put("issuerInitializationProvenance", sources.issuer().initializationProvenance());
    snapshot.put("accountInitializationProvenance", sources.account().initializationProvenance());
    snapshot.put("accountIdentitySource", identitySourceFields(identitySource));
    return snapshot;
  }

  private static Map<String, Object> evaluationIdentityPreimage(
      String snapshotIdentity,
      AccountAuthEvidenceBundle.BundleReference bundleReference,
      UUID operationId,
      UUID requestId,
      String requestDigest,
      String callerWorkload,
      UUID callerContextId,
      UUID accountId,
      UUID tokenJti,
      long tokenGeneration,
      long issuedAt,
      long notBefore,
      long expiresAt) {
    Map<String, Object> operation = new LinkedHashMap<>();
    operation.put("operationId", operationId.toString());
    operation.put("requestId", requestId.toString());
    operation.put("requestDigest", requestDigest);
    operation.put("callerWorkload", callerWorkload);
    operation.put("callerContextId", callerContextId.toString());
    operation.put("accountId", accountId.toString());
    Map<String, Object> token = new LinkedHashMap<>();
    token.put("jti", tokenJti.toString());
    token.put("tokenGeneration", Long.toString(tokenGeneration));
    token.put("iat", issuedAt);
    token.put("nbf", notBefore);
    token.put("exp", expiresAt);
    Map<String, Object> evaluation = new LinkedHashMap<>();
    evaluation.put("domain", "firemud/account-auth-evidence/evaluation/v1");
    evaluation.put("schema", AccountAuthEvidenceBundle.SCHEMA);
    evaluation.put("snapshotIdentity", snapshotIdentity);
    evaluation.put(
        "bundleRef",
        Map.of(
            "bundleVersion", bundleReference.bundleVersion(),
            "sourceVersion", bundleReference.sourceVersion(),
            "sourceFence", bundleReference.sourceFence(),
            "linearization", bundleReference.linearization()));
    evaluation.put("operation", operation);
    evaluation.put("tokenIdentity", token);
    evaluation.put("membershipVersion", Map.of());
    return evaluation;
  }

  private static Map<String, Object> checkpointFields(SourceCheckpoint checkpoint) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("outboxStreamKey", checkpoint.outboxStreamKey());
    fields.put("outboxSequence", Long.toString(checkpoint.sequence()));
    checkpoint.sourceEventId().ifPresent(value -> fields.put("sourceEventId", value));
    checkpoint.sourceEventDigest().ifPresent(value -> fields.put("sourceEventDigest", value));
    return fields;
  }

  private static Map<String, Object> identitySourceFields(
      AccountAuthEvidenceBundle.AccountIdentitySource identitySource) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("sourceRowId", Long.toString(identitySource.sourceRowId()));
    fields.put("provenance", identitySource.provenance().name());
    fields.put("sourceNumericId", Long.toString(identitySource.sourceNumericId()));
    return fields;
  }

  private String currentTransactionId() {
    Record transaction = dsl.fetchOne("SELECT pg_current_xact_id()::text AS transaction_id");
    String value = transaction == null ? null : transaction.get("transaction_id", String.class);
    if (value == null || !value.matches("[1-9][0-9]{0,19}")) {
      throw new OwnerEvidenceUnavailableException();
    }
    try {
      if (new java.math.BigInteger(value).bitLength() > 64) {
        throw new OwnerEvidenceUnavailableException();
      }
    } catch (NumberFormatException ex) {
      throw new OwnerEvidenceUnavailableException();
    }
    return value;
  }

  private static AccountAuthEvidenceBundle.BundleReference storedBundleReference(
      AccountAuthEvidenceBundle stored) {
    Object reference = stored.fields().get("bundleRef");
    if (!(reference instanceof Map<?, ?> fields)
        || !(fields.get("bundleVersion") instanceof String bundleVersion)
        || !(fields.get("sourceVersion") instanceof String sourceVersion)
        || !(fields.get("sourceFence") instanceof String sourceFence)
        || !(fields.get("linearization") instanceof String linearization)) {
      throw new StoredBundleUnavailableException();
    }
    try {
      return new AccountAuthEvidenceBundle.BundleReference(
          bundleVersion, sourceVersion, sourceFence, linearization);
    } catch (RuntimeException ex) {
      throw new StoredBundleUnavailableException();
    }
  }

  private String allocateSourceFence(UUID operationId, String transactionId) {
    Record allocation =
        dsl.fetchOne(
            "UPDATE account_gameplay_auth_evidence_source_fence "
                + "SET last_source_fence = last_source_fence + 1, last_operation_id = ?, "
                + "last_transaction_id = ? "
                + "WHERE allocator_id = TRUE AND last_source_fence < 9223372036854775807 "
                + "RETURNING last_source_fence",
            operationId,
            transactionId);
    if (allocation == null) throw new OwnerEvidenceUnavailableException();
    Long sourceFence = allocation.get("last_source_fence", Long.class);
    if (sourceFence == null || sourceFence <= 0L) {
      throw new OwnerEvidenceUnavailableException();
    }
    return Long.toString(sourceFence);
  }

  private static byte[] canonicalJson(Object value) {
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (IOException | RuntimeException ex) {
      throw new OwnerEvidenceUnavailableException();
    }
  }

  private static String sha256(byte[] value) {
    try {
      return java.util.HexFormat.of()
          .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value));
    } catch (java.security.NoSuchAlgorithmException ex) {
      throw new OwnerEvidenceUnavailableException();
    }
  }

  private static UUID requiredUuid(Record row, String field) {
    UUID value = row.get(field, UUID.class);
    if (value == null) throw new OwnerEvidenceUnavailableException();
    return value;
  }

  private static String requiredText(Record row, String field) {
    String value = row.get(field, String.class);
    if (value == null) throw new OwnerEvidenceUnavailableException();
    return value;
  }

  private static byte[] requiredBytes(Record row, String field) {
    byte[] value = row.get(field, byte[].class);
    if (value == null) throw new OwnerEvidenceUnavailableException();
    return value.clone();
  }

  private static long positive(Long value) {
    if (value == null || value <= 0L) throw new OwnerEvidenceUnavailableException();
    return value;
  }

  private static boolean canonicalV4(UUID value) {
    return value != null && value.version() == 4 && V4_UUID.matcher(value.toString()).matches();
  }

  private static String nestedText(Map<String, Object> root, String parent, String child) {
    Object value = root.get(parent);
    if (!(value instanceof Map<?, ?> object)) throw new StoredBundleUnavailableException();
    Object nested = object.get(child);
    if (!(nested instanceof String text)) throw new StoredBundleUnavailableException();
    return text;
  }

  private static void requireWritableAccountTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Account auth-evidence capture requires a writable Account transaction");
    }
  }

  public static final class OwnerEvidenceUnavailableException extends IllegalStateException {
    public OwnerEvidenceUnavailableException() {
      super("Account owner source and retained-history evidence is unavailable or ambiguous");
    }
  }

  public static final class OperationNotFoundException extends IllegalStateException {
    public OperationNotFoundException() {
      super("Account gameplay delegation operation is absent");
    }
  }

  public static final class StoredBundleUnavailableException extends IllegalStateException {
    public StoredBundleUnavailableException() {
      super("Stored Account auth-evidence bundle is unavailable or malformed");
    }
  }
}
