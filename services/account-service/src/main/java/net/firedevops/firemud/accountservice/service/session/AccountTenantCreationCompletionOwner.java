package net.firedevops.firemud.accountservice.service.session;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.client.GameDesignOriginalTenantCreationOutcomeClient.VerifiedTerminal;
import net.firedevops.firemud.accountservice.creatorparty.IndividualCreatorPartyRepository;
import net.firedevops.firemud.accountservice.dto.AccountTenantCreationBootstrapResult;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService.CapturedEnvironmentBoundary;
import net.firedevops.firemud.accountservice.repository.AccountAuditOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountInitialTenantBillingOwnerRepository;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.accountservice.service.AccountTenantCreationBootstrapAuthorizationSource;
import net.firedevops.firemud.accountservice.service.AccountTenantCreationBootstrapService;
import net.firedevops.firemud.accountservice.service.session.AccountControlUiActorService.AcceptedSignerKey;
import net.firedevops.firemud.accountservice.service.session.AccountTenantCreationAdmissionRepository.Admitted;
import net.firedevops.firemud.accountservice.service.session.AccountTenantCreationIntentRepository.OriginalIntent;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorDigest;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationOriginalOutcomeEvidence;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Unregistered final Account owner. Only a privately verified exact CREATED terminal can enter. */
public final class AccountTenantCreationCompletionOwner {
  private static final String EVENT = "ACCOUNT_TENANT_CREATION_COMPLETED";
  private static final String TABLE = "account_tenant_creation_completions";
  private final DSLContext dsl;
  private final AccountTenantCreationIntentRepository intents;
  private final AccountTenantCreationAdmissionRepository admissions;
  private final AccountTenantCreationSourceOrderRepository orders;
  private final AccountControlUiIssuanceRepository issuances;
  private final AccountControlUiActorService actors;
  private final AccountTenantCreationOwnerSourceCapture capture;
  private final FreshTenantIdentityAssociationRepository identities;
  private final AccountAuthorityGenerationRepository generations;
  private final AccountTenantCreationBootstrapService bootstraps;
  private final IndividualCreatorPartyRepository parties;
  private final AccountInitialTenantBillingOwnerRepository owners;
  private final AccountRecentReauthenticationService reauthentication;
  private final AccountAuditOutboxRepository audits;
  private final TransactionTemplate transactions;

  AccountTenantCreationCompletionOwner(
      DSLContext dsl,
      AccountTenantCreationIntentRepository intents,
      AccountTenantCreationAdmissionRepository admissions,
      AccountTenantCreationSourceOrderRepository orders,
      AccountControlUiIssuanceRepository issuances,
      AccountControlUiActorService actors,
      AccountTenantCreationOwnerSourceCapture capture,
      FreshTenantIdentityAssociationRepository identities,
      AccountAuthorityGenerationRepository generations,
      AccountTenantCreationBootstrapService bootstraps,
      IndividualCreatorPartyRepository parties,
      AccountInitialTenantBillingOwnerRepository owners,
      AccountRecentReauthenticationService reauthentication,
      AccountAuditOutboxRepository audits,
      PlatformTransactionManager transactionManager) {
    this.dsl = Objects.requireNonNull(dsl);
    this.intents = Objects.requireNonNull(intents);
    this.admissions = Objects.requireNonNull(admissions);
    this.orders = Objects.requireNonNull(orders);
    this.issuances = Objects.requireNonNull(issuances);
    this.actors = Objects.requireNonNull(actors);
    this.capture = Objects.requireNonNull(capture);
    this.identities = Objects.requireNonNull(identities);
    this.generations = Objects.requireNonNull(generations);
    this.bootstraps = Objects.requireNonNull(bootstraps);
    this.parties = Objects.requireNonNull(parties);
    this.owners = Objects.requireNonNull(owners);
    this.reauthentication = Objects.requireNonNull(reauthentication);
    this.audits = Objects.requireNonNull(audits);
    transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  Result complete(
      UUID operationId, VerifiedTerminal terminal, CapturedEnvironmentBoundary environment,
      AccountRecentReauthenticationEvidence recent) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) throw denied();
    Objects.requireNonNull(operationId);
    Objects.requireNonNull(terminal);
    Objects.requireNonNull(environment);
    Objects.requireNonNull(recent);
    Initial first = Objects.requireNonNull(transactions.execute(status -> {
      Original original = readOriginal(operationId, terminal);
      return new Initial(original, readResult(original, terminal));
    }));
    if (first.result != null) return first.result;

    String kid = (String) AccountControlUiIssuanceRepository.object(first.original.issuance.signerReceipt).get("kid");
    AcceptedSignerKey accepted = actors.readAcceptedSignerKey(kid);
    AccountControlUiActorService.requireAcceptedSignerKey(first.original.issuance, accepted);
    Result committed = Objects.requireNonNull(transactions.execute(status -> {
      Original original = readOriginal(operationId, terminal);
      requireSame(first.original, original);
      Result replay = readResult(original, terminal);
      if (replay != null) return replay;
      // Account-first capture serializes concurrent completion before any Account creator writes.
      current(original, environment, recent, accepted);
      if (dsl.fetchOne(
          "SELECT request_id FROM account_tenant_creation_bootstrap_operations WHERE request_id = ? OR tenant_uuid = ? FOR UPDATE",
          operationId, terminal.evidence().receipt().orElseThrow().canonicalTenantId()) != null) {
        throw denied();
      }
      var receipt = terminal.evidence().receipt().orElseThrow(AccountTenantCreationCompletionOwner::denied);
      var creator = creator(original, receipt);
      if (!identities.importVerified(receipt).equals(receipt)
          || !identities.read(receipt.canonicalTenantId()).filter(receipt::equals).isPresent()) throw denied();
      var tenant = generations.initializeTenantIfAbsent(receipt.canonicalTenantId());
      if (tenant.generation() != 1L || tenant.sourceVersion() != 1L) throw denied();
      var source = new CurrentSource(this, original, environment, recent, accepted, creator, transactionId());
      var bootstrap = bootstraps.bootstrapForCompletion(creator, source);
      var party = parties.readIndividualSource(original.intent.creatorPartyId(), original.intent.initiatingAccountId());
      var associated = parties.associateFresh(operationId, creator, party);
      if (!associated.equals(parties.readInitialAssociation(operationId, creator, party))) throw denied();
      var owner = owners.establishInitial(new AccountInitialTenantBillingOwnerRepository.InitialRequest(
          original.intent.requestId(), creator, original.intent.consentOperationId(),
          original.intent.consentStatementVersion(), original.intent.affirmativeConsent()));
      // Fresh capture immediately before consumption; all captured source locks remain held to commit.
      Current current = current(original, environment, recent, accepted);
      var consumed = reauthentication.validateAndConsumeLocked(recent,
          original.intent.initiatingAccountId(), AccountRecentReauthenticationPolicy.PURPOSE, operationId);
      if (consumed.disposition() != AccountRecentReauthenticationService.Disposition.CONSUMED) throw denied();
      UUID auditId = UUID.nameUUIDFromBytes(("account-tenant-creation-completion/v1:" + operationId).getBytes(StandardCharsets.UTF_8));
      byte[] payload = net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority.canonical(
          Map.ofEntries(
              Map.entry("schema", "account-tenant-creation-completion/v1"),
              Map.entry("operationId", operationId.toString()),
              Map.entry("intentDigest", original.intent.requestDigest()),
              Map.entry("authorizationDigest", original.holder.authorizationDigest()),
              Map.entry("terminalDigest", terminal.evidence().evidenceDigest()),
              Map.entry("tenantId", receipt.canonicalTenantId().toString()),
              Map.entry("accountId", original.intent.initiatingAccountId().toString()),
              Map.entry("creatorPartyId", original.intent.creatorPartyId().toString()),
              Map.entry("creatorEvidenceDigest", creator.evidenceDigest()),
              Map.entry("bootstrapResultDigest", bootstrap.resultDigest()),
              Map.entry("initialOwnerResultDigest", owner.resultDigest()),
              Map.entry("ownerGeneration", owner.ownerGeneration().toString()),
              Map.entry("ownerVersion", owner.ownerVersion()),
              Map.entry("consentOperationId", original.intent.consentOperationId().toString()),
              Map.entry("consentStatementVersion", original.intent.consentStatementVersion()),
              Map.entry("reauthenticationEvidenceId", recent.evidenceId().toString()),
              Map.entry("auditEventId", auditId.toString())));
      String digest = digest(payload);
      var audit = audits.appendCanonicalTenant(auditId, receipt.canonicalTenantId().toString(), EVENT,
          new String(payload, StandardCharsets.UTF_8));
      if (!digest.equals(audit.payloadDigest())) throw denied();
      orders.settleLocked(original.holder, terminal);
      Instant validBefore = validBefore(original, current.capture);
      dsl.execute("INSERT INTO " + TABLE
          + " (creation_operation_id, intent_request_id, tenant_uuid, terminal_payload, terminal_digest,"
          + " result_payload, result_digest, audit_event_id, valid_before) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz)",
          operationId, original.intent.requestId(), receipt.canonicalTenantId(), terminal.evidence().canonicalBytes(),
          terminal.evidence().evidenceDigest(), payload, digest, auditId, OffsetDateTime.ofInstant(validBefore, ZoneOffset.UTC));
      return Objects.requireNonNull(readResult(original, terminal));
    }));
    return Objects.requireNonNull(transactions.execute(status -> {
      Original original = readOriginal(operationId, terminal);
      requireSame(first.original, original);
      Result readback = Objects.requireNonNull(readResult(original, terminal));
      if (!Arrays.equals(committed.payload, readback.payload) || !committed.digest.equals(readback.digest)) throw denied();
      return readback;
    }));
  }

  private Current current(Original original, CapturedEnvironmentBoundary environment,
      AccountRecentReauthenticationEvidence recent, AcceptedSignerKey accepted) {
    var actual = capture.captureLocked(original.intent, original.admitted, environment, recent);
    AccountControlUiActorService.requireAcceptedSignerKey(original.issuance, accepted);
    if (!Arrays.equals(actual.originalClaims(), original.issuance.claims)
        || !Arrays.equals(actual.originalSignerReceipt(), original.issuance.signerReceipt)
        || !original.issuance.operationId.equals(actual.issuanceOperationId())
        || !original.issuance.jti.equals(actual.tokenJti())
        || !original.issuance.tokenHash.equals(actual.tokenHash())) throw denied();
    var held = orders.lockOriginalForCompletionLocked(original.intent, original.admitted, actual.sources());
    if (!Arrays.equals(held.canonicalBytes(), original.holder.canonicalBytes())) throw denied();
    return new Current(actual);
  }

  private Original readOriginal(UUID operationId, VerifiedTerminal terminal) {
    requireOwnerTransaction();
    var intent = intents.readOriginal(operationId).orElseThrow(AccountTenantCreationCompletionOwner::denied);
    var admitted = admissions.readAdmitted(operationId).orElseThrow(AccountTenantCreationCompletionOwner::denied);
    var holder = orders.readOriginal(operationId).orElseThrow(AccountTenantCreationCompletionOwner::denied);
    if (!operationId.equals(intent.creationOperationId())
        || !operationId.equals(admitted.binding().creationOperationId())
        || !Arrays.equals(intent.requestPayload(), holder.intentPayload())
        || !intent.requestDigest().equals(holder.intentDigest())
        || !Arrays.equals(admitted.receiptPayload(), holder.admissionPayload())
        || !admitted.receiptDigest().equals(holder.admissionDigest())
        || !holder.outcomeBinding().equals(terminal.binding())
        || terminal.evidence().outcome() != GameTenantCreationOriginalOutcomeEvidence.Outcome.CREATED) throw denied();
    var receipt = terminal.evidence().receipt().orElseThrow(AccountTenantCreationCompletionOwner::denied);
    if (!intent.sourceGameTenantKey().equals(receipt.sourceGameTenantKey())
        || !"NEW_GAME_ROW".equals(receipt.provenanceKind())) throw denied();
    Record row = dsl.fetchOne("SELECT * FROM account_control_ui_issuance_operations WHERE operation_id = ?",
        admitted.binding().issuanceOperationId());
    if (row == null) throw denied();
    var issuance = issuances.decodeStored(row);
    if (!admitted.binding().issuanceOperationId().equals(issuance.operationId)
        || !intent.initiatingAccountId().equals(issuance.accountId)
        || !admitted.binding().tokenHash().equals(issuance.tokenHash)) throw denied();
    return new Original(intent, admitted, holder, issuance);
  }

  private Result readResult(Original original, VerifiedTerminal terminal) {
    Record row = dsl.fetchOne("SELECT * FROM " + TABLE + " WHERE creation_operation_id = ?", original.intent.creationOperationId());
    if (row == null) return null;
    byte[] payload = Objects.requireNonNull(row.get("result_payload", byte[].class));
    String resultDigest = row.get("result_digest", String.class);
    if (!original.intent.requestId().equals(row.get("intent_request_id", UUID.class))
        || !terminal.evidence().receipt().orElseThrow().canonicalTenantId().equals(row.get("tenant_uuid", UUID.class))
        || !Arrays.equals(terminal.evidence().canonicalBytes(), row.get("terminal_payload", byte[].class))
        || !terminal.evidence().evidenceDigest().equals(row.get("terminal_digest", String.class))
        || !digest(payload).equals(resultDigest)) throw denied();
    var settlement = orders.readSettlement(original.intent.creationOperationId()).orElseThrow(AccountTenantCreationCompletionOwner::denied);
    if (!Arrays.equals(settlement.canonicalBytes(), terminal.evidence().canonicalBytes())) throw denied();
    return new Result(payload, resultDigest);
  }

  private static FreshTenantCreatorEvidence creator(Original original,
      net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence receipt) {
    return new FreshTenantCreatorEvidence(1, receipt, original.intent.initiatingAccountId(),
        original.holder.creationOperationId(), original.holder.authorizationDigest(),
        FreshTenantCreatorDigest.evidenceDigest(1, receipt, original.intent.initiatingAccountId(),
            original.holder.creationOperationId(), original.holder.authorizationDigest()));
  }

  private static Instant validBefore(Original original, AccountTenantCreationOwnerSourceCapture.Captured captured) {
    Instant deadline = Instant.ofEpochMilli(original.admitted.binding().operationDeadlineEpochMillis());
    var recent = captured.reauthentication();
    Instant recentDeadline = recent.issuedAt().plusSeconds(recent.recentAgeSeconds() - recent.maxClockSkewSeconds());
    if (recentDeadline.isBefore(deadline)) deadline = recentDeadline;
    Instant termsDeadline = captured.currentness().terms().validUntil();
    if (termsDeadline != null && termsDeadline.isBefore(deadline)) deadline = termsDeadline;
    return deadline;
  }

  private String transactionId() {
    return Objects.requireNonNull(dsl.fetchOne("SELECT txid_current()::text AS owner_transaction"))
        .get("owner_transaction", String.class);
  }

  private void requireOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) throw denied();
    dsl.connection(connection -> {
      if (connection.getAutoCommit() || connection.isReadOnly()
          || connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) throw denied();
    });
  }

  private static void requireSame(Original expected, Original actual) {
    if (!Arrays.equals(expected.holder.canonicalBytes(), actual.holder.canonicalBytes())
        || !Arrays.equals(expected.issuance.claims, actual.issuance.claims)
        || !Arrays.equals(expected.issuance.signerReceipt, actual.issuance.signerReceipt)) throw denied();
  }

  private static String digest(byte[] bytes) {
    return "sha256:" + AccountControlUiIssuanceRepository.hash(bytes);
  }

  private static IllegalStateException denied() {
    return new IllegalStateException("Exact current Account tenant-creation completion is unavailable");
  }

  private record Original(OriginalIntent intent, Admitted admitted,
      AccountTenantCreationSourceOrderBinding holder, AccountControlUiIssuanceRepository.Stored issuance) {}
  private record Initial(Original original, Result result) {}
  private record Current(AccountTenantCreationOwnerSourceCapture.Captured capture) {}

  /** Concrete scoped participant; every invocation revalidates actual persisted owner authority. */
  public static final class CurrentSource implements AccountTenantCreationBootstrapAuthorizationSource {
    private final AccountTenantCreationCompletionOwner owner;
    private final Original original;
    private final CapturedEnvironmentBoundary environment;
    private final AccountRecentReauthenticationEvidence recent;
    private final AcceptedSignerKey accepted;
    private final FreshTenantCreatorEvidence creator;
    private final String transaction;

    private CurrentSource(AccountTenantCreationCompletionOwner owner, Original original,
        CapturedEnvironmentBoundary environment, AccountRecentReauthenticationEvidence recent,
        AcceptedSignerKey accepted, FreshTenantCreatorEvidence creator, String transaction) {
      this.owner = owner;
      this.original = original;
      this.environment = environment;
      this.recent = recent;
      this.accepted = accepted;
      this.creator = creator;
      this.transaction = Objects.requireNonNull(transaction);
    }

    @Override
    public AccountTenantCreationBootstrapResult withCurrentAuthorizationAndSourceParticipation(
        FreshTenantCreatorEvidence evidence, Supplier<AccountTenantCreationBootstrapResult> mutationWhileCurrent) {
      owner.requireOwnerTransaction();
      if (!creator.equals(evidence) || !transaction.equals(owner.transactionId())) throw denied();
      owner.current(original, environment, recent, accepted);
      var result = Objects.requireNonNull(mutationWhileCurrent.get());
      owner.current(original, environment, recent, accepted);
      return result;
    }
  }

  /** Historical data only, replayed without reconsumption or new creator writes. */
  static final class Result {
    private final byte[] payload;
    private final String digest;
    private Result(byte[] payload, String digest) { this.payload = payload.clone(); this.digest = digest; }
    byte[] payload() { return payload.clone(); }
    String digest() { return digest; }
  }
}
