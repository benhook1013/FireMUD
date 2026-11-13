package net.firedevops.firemud.accountservice.repository;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementEventV1Codec;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository.CommittedCandidateVerificationData;
import net.firedevops.firemud.accountservice.repository.AccountTenantAuthorityEventRepository.SourceKind;
import net.firedevops.firemud.accountservice.repository.AccountTenantAuthorityEventRepository.SourceSelection;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.AuthorityTuple;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.TenantBillingCutoff;
import net.firedevops.firemud.common.account.authority.RuntimeMembershipAuthorityEvidenceValidator.Snapshot;
import net.firedevops.firemud.common.account.authority.TenantAuthorityEventV1Codec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile.AccountSecurityCutoff;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unregistered Account-local current-source composition for canonical public-production NEW
 * admission.
 *
 * <p>The caller enlists the exact supplied DSLContext in its writable SERIALIZABLE transaction.
 * This component creates no transaction, performs no writes, and owns no lease. Its SQL phases read
 * current Account membership, tenant entitlement, and the active SQL token fence under the
 * canonical Account-first lock order; its prepared handle observes the provider only outside SQL.
 * The caller verifier remains responsible for JWT/JWKS and exact Redis currentness outside SQL;
 * this source cannot re-prove either remote registry state or a published gameplay target.
 *
 * <p>The returned membership snapshot is complete current Account evidence. The Current result can
 * produce the five unchanged Account source subtrees for a lease and compare them against an
 * original lease; it never creates, renews, or rewrites that lease.
 *
 * <p>Exact non-admitting recovery uses a separately named caller-only read. That path retains the
 * global issuer/account authority and ACTIVE token checks but deliberately does not require the
 * original tenant membership or entitlement baseline to remain unchanged.
 *
 * <p>Paid sources require an opaque preparation and actual provider observation from the concrete
 * hosting owner composition. Its receipt is revalidated on this exact SQL connection. A missing
 * paid composition or changed selected head never falls back to DEMO.
 */
public final class AccountGameplayAdmissionCurrentSources {
  private static final String ACCOUNT_ISSUER = GameSessionAccountDelegationProfile.ISSUER;
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Set<String> LEASE_SOURCE_FIELDS =
      Set.of(
          "authorityTuple",
          "issuanceFence",
          "membershipBaseline",
          "outboxCheckpoints",
          "tokenIdentityEvidence");

  private final DSLContext dsl;
  private final AccountCanonicalRuntimeMembershipSnapshotRepository membershipSnapshots;
  private final AccountTenantAuthorityEventRepository tenantAuthorityEvents;
  private final AccountTenantEntitlementOutboxRepository tenantEntitlementOutbox;
  private final AccountGameplayDelegationIssuanceRepository issuance;
  private final String workloadNamespace;

  private AccountGameplayAdmissionCurrentSources(
      DSLContext dsl,
      AccountCanonicalRuntimeMembershipSnapshotRepository membershipSnapshots,
      AccountTenantAuthorityEventRepository tenantAuthorityEvents,
      AccountTenantEntitlementOutboxRepository tenantEntitlementOutbox,
      AccountGameplayDelegationIssuanceRepository issuance,
      String workloadNamespace) {
    this.dsl = Objects.requireNonNull(dsl);
    this.membershipSnapshots = Objects.requireNonNull(membershipSnapshots);
    this.tenantAuthorityEvents = Objects.requireNonNull(tenantAuthorityEvents);
    this.tenantEntitlementOutbox = Objects.requireNonNull(tenantEntitlementOutbox);
    this.issuance = Objects.requireNonNull(issuance);
    this.workloadNamespace = workloadNamespace;
  }

  /**
   * Builds the concrete read graph over one exact caller-enlisted DSLContext.
   *
   * <p>The workload namespace is passed only to Account's existing fresh-tenant source reader; it
   * is not interpreted as a caller or gameplay authority.
   */
  static AccountGameplayAdmissionCurrentSources bind(
      DSLContext exactDsl, String configuredWorkloadNamespace) {
    Objects.requireNonNull(exactDsl, "The caller-enlisted DSLContext is required");
    if (!GrpcPeerIdentity.isValidNamespace(configuredWorkloadNamespace)) {
      throw new IllegalArgumentException("Configured Account workload namespace is invalid");
    }

    AccountAuthorityGenerationRepository generations =
        new AccountAuthorityGenerationRepository(exactDsl);
    AccountAuthorityOutboxRepository authorityOutbox =
        new AccountAuthorityOutboxRepository(exactDsl);
    AccountAuthoritySourceEvidenceRepository sourceEvidence =
        new AccountAuthoritySourceEvidenceRepository(exactDsl, generations, authorityOutbox);
    FreshTenantIdentityAssociationRepository freshTenants =
        new FreshTenantIdentityAssociationRepository(exactDsl, configuredWorkloadNamespace);
    AccountTenantEntitlementOutboxRepository tenantEntitlementOutbox =
        new AccountTenantEntitlementOutboxRepository(exactDsl);
    AccountTenantAuthorityEventRepository tenantAuthorityEvents =
        new AccountTenantAuthorityEventRepository(
            exactDsl, authorityOutbox, tenantEntitlementOutbox, freshTenants, generations);
    AccountRepository accounts = new AccountRepository(exactDsl, sourceEvidence);
    AccountMembershipPairAuthorityRepository membershipPairs =
        new AccountMembershipPairAuthorityRepository(exactDsl);
    AccountTenantMembershipRepository memberships =
        new AccountTenantMembershipRepository(exactDsl, accounts, freshTenants, membershipPairs);
    AccountTenantMembershipRoleSnapshotRepository membershipRoles =
        new AccountTenantMembershipRoleSnapshotRepository(exactDsl);
    AccountCanonicalRuntimeMembershipSnapshotRepository membershipSnapshots =
        new AccountCanonicalRuntimeMembershipSnapshotRepository(
            accounts,
            generations,
            sourceEvidence,
            tenantAuthorityEvents,
            freshTenants,
            memberships,
            membershipPairs,
            membershipRoles,
            authorityOutbox);
    AccountAuthEvidenceBundleRepository evidenceBundles =
        new AccountAuthEvidenceBundleRepository(exactDsl, generations, authorityOutbox);
    AccountGameplayDelegationIssuanceRepository issuance =
        new AccountGameplayDelegationIssuanceRepository(exactDsl, sourceEvidence, evidenceBundles);

    return new AccountGameplayAdmissionCurrentSources(
        exactDsl,
        membershipSnapshots,
        tenantAuthorityEvents,
        tenantEntitlementOutbox,
        issuance,
        configuredWorkloadNamespace);
  }

  /** Captures only Account-owned selectors/fences; the provider read follows outside SQL. */
  Prepared prepareCurrentPublicProductionNew(
      UUID tenantId,
      UUID originalTokenJti,
      CommittedCandidateVerificationData candidate,
      AccountGameplayAdmissionPaidSource paidSource) {
    return prepareCurrentPublicProductionNew(
        tenantId, originalTokenJti, candidate, paidSource, false);
  }

  Prepared prepareCurrentPublicProductionNew(
      UUID tenantId,
      UUID originalTokenJti,
      CommittedCandidateVerificationData candidate,
      AccountGameplayAdmissionPaidSource paidSource,
      boolean requalifyPaidMembership) {
    UUID accountId = verifiedCallerAccountId(tenantId, originalTokenJti, candidate);
    requireWritableSerializableAccountTransaction();

    // The hosting lifecycle's lock order is Account -> customer -> instrument -> tenant owner ->
    // subscription -> tenant generation. Complete its SQL preparation before membership/source
    // reads lock tenant generations, avoiding a reverse edge when a joiner differs from the payer.
    AccountGameplayAdmissionPaidSource.Preparation paidPreparation = null;
    if (paidSource != null
        && paidSource.hasCurrentPaidSourceCandidate(dsl, workloadNamespace, tenantId)) {
      lockRequiredAccountsBeforeTenantSources(dsl, tenantId, accountId);
      paidPreparation = paidSource.prepare(dsl, workloadNamespace, tenantId);
      if (requalifyPaidMembership) {
        var selected = tenantAuthorityEvents.readCurrentSourceSelection(tenantId);
        if (selected.kind() != SourceKind.PAID_HOSTING) throw unavailable();
        readCommittedCandidate(originalTokenJti, candidate);
        return new Prepared(selected, paidSource, paidPreparation);
      }
    } else {
      lockExactAccountFirst(accountId);
    }
    CallerSources caller = readCallerSources(tenantId, originalTokenJti, candidate, accountId);
    SourceSelection selected = caller.selection();
    if (selected.kind() == SourceKind.PAID_HOSTING) {
      if (paidPreparation == null) throw unavailable();
    } else {
      if (paidPreparation != null) throw unavailable();
      requireCurrentDemo(caller.snapshot(), tenantId, selected.checkpoint());
    }
    return new Prepared(selected, paidSource, paidPreparation);
  }

  /**
   * Captures current Account-owned sources for one authenticated original token and exact tenant.
   *
   * <p>{@code expectedCommittedCandidate} is used only as the verified caller's correlation value:
   * its Account and JTI select current owner reads, and every immutable candidate field is compared
   * with the independent in-transaction SQL readback before it is returned.
   */
  Current readCurrentPublicProductionNew(
      UUID targetTenantId,
      UUID expectedOriginalTokenJti,
      CommittedCandidateVerificationData expectedCommittedCandidate) {
    return readCurrentPublicProductionNew(
        targetTenantId, expectedOriginalTokenJti, expectedCommittedCandidate, null);
  }

  Current readCurrentPublicProductionNew(
      UUID targetTenantId,
      UUID expectedOriginalTokenJti,
      CommittedCandidateVerificationData expectedCommittedCandidate,
      Observed observed) {
    return readCurrentPublicProductionNew(
        targetTenantId, expectedOriginalTokenJti, expectedCommittedCandidate, observed, false);
  }

  Current readCurrentPublicProductionNew(
      UUID targetTenantId,
      UUID expectedOriginalTokenJti,
      CommittedCandidateVerificationData expectedCommittedCandidate,
      Observed observed,
      boolean requalifyPaidMembership) {
    UUID accountId =
        verifiedCallerAccountId(
            targetTenantId, expectedOriginalTokenJti, expectedCommittedCandidate);
    requireWritableSerializableAccountTransaction();

    // Reacquire the complete hosting lifecycle lock set before taking membership/tenant source
    // generations. The post-lock source selector below still verifies that this receipt is the
    // exact current paid source for the requested tenant.
    AccountHostingSubscriptionCurrentReceipt paidReceipt = null;
    if (observed != null && observed.paidSource != null && observed.paidObservation != null) {
      lockRequiredAccountsBeforeTenantSources(dsl, targetTenantId, accountId);
      paidReceipt =
          observed.paidSource.readCurrent(dsl, workloadNamespace, observed.paidObservation);
    } else {
      lockExactAccountFirst(accountId);
    }
    SourceSelection selected = tenantAuthorityEvents.readCurrentSourceSelection(targetTenantId);
    if (observed != null
        && (selected.kind() != observed.selected.kind()
            || !observed.selected.matches(selected.checkpoint()))) throw unavailable();
    CommittedCandidateVerificationData candidate =
        readCommittedCandidate(expectedOriginalTokenJti, expectedCommittedCandidate);
    Snapshot snapshot;
    Long sourceValidUntilMs = null;
    if (selected.kind() == SourceKind.PAID_HOSTING) {
      if (paidReceipt == null) throw unavailable();
      var receipt = paidReceipt;
      TenantAuthorityEventV1Codec.Event event = receipt.tenantAuthorityEvent();
      if (!targetTenantId.equals(receipt.current().tenantId())
          || !selected.matches(event)
          || !receipt.receiptRequestId().equals(event.requestId())
          || !receipt.billingEventId().equals(event.tenantBillingEventId())
          || !receipt.billingEventDigest().equals(event.tenantBillingEventDigest())
          || receipt.tenantBillingSequence() != event.tenantBillingSequence()
          || !receipt.gameplayAvailable()
          || !receipt.allowNewGameplayBindings()) {
        throw unavailable();
      }
      snapshot =
          requalifyPaidMembership
              ? membershipSnapshots.requalifyPaidAcquisition(accountId, targetTenantId, receipt)
              : membershipSnapshots.readCurrent(accountId, targetTenantId);
      requireSupportedPublicTenantTuple(
          snapshot, targetTenantId, event, receipt.tenantBillingSequence());
      sourceValidUntilMs = receipt.validUntil().toEpochMilli();
    } else {
      if (paidReceipt != null) throw unavailable();
      snapshot = membershipSnapshots.readCurrent(accountId, targetTenantId);
      requireCurrentDemo(snapshot, targetTenantId, selected.checkpoint());
    }
    requireCurrentActiveMembership(snapshot, accountId, targetTenantId);
    requireCandidateAuthorityMatchesSnapshot(candidate, snapshot, accountId);
    return new Current(snapshot, candidate, sourceValidUntilMs);
  }

  private void requireCurrentDemo(
      Snapshot snapshot, UUID tenantId, TenantAuthorityEventV1Codec.Event authorityEvent) {
    DemoTenantEntitlementEventV1Codec.Event event =
        readExactCurrentTenantEntitlement(tenantId, authorityEvent);
    requireSupportedPublicTenantTuple(
        snapshot, tenantId, authorityEvent, event.tenantBillingSequence());
    if (!event.gameplayAvailable() || !event.allowNewGameplayBindings()) throw unavailable();
  }

  private CallerSources readCallerSources(
      UUID targetTenantId,
      UUID expectedOriginalTokenJti,
      CommittedCandidateVerificationData expectedCommittedCandidate,
      UUID accountId) {
    Snapshot snapshot = membershipSnapshots.readCurrent(accountId, targetTenantId);
    var selected = tenantAuthorityEvents.readCurrentSourceSelection(targetTenantId);
    requireCurrentActiveMembership(snapshot, accountId, targetTenantId);

    CommittedCandidateVerificationData currentCommittedCandidate =
        readCommittedCandidate(expectedOriginalTokenJti, expectedCommittedCandidate);
    requireCandidateAuthorityMatchesSnapshot(currentCommittedCandidate, snapshot, accountId);
    return new CallerSources(snapshot, selected, currentCommittedCandidate);
  }

  private CommittedCandidateVerificationData readCommittedCandidate(
      UUID expectedOriginalTokenJti,
      CommittedCandidateVerificationData expectedCommittedCandidate) {
    CommittedCandidateVerificationData currentCommittedCandidate =
        issuance
            .readCurrentCommittedCandidateByJti(expectedOriginalTokenJti)
            .orElseThrow(AccountGameplayAdmissionCurrentSources::unavailable);
    if (!CommittedCandidateVerificationData.hasSameOwnerEvidence(
        expectedCommittedCandidate, currentCommittedCandidate)) {
      throw unavailable();
    }
    return currentCommittedCandidate;
  }

  private UUID verifiedCallerAccountId(
      UUID tenantId,
      UUID expectedOriginalTokenJti,
      CommittedCandidateVerificationData expectedCommittedCandidate) {
    Objects.requireNonNull(expectedCommittedCandidate, "Verified caller candidate is required");
    requireNonNilUuid(tenantId, "Canonical target tenant UUID");
    requireV4Uuid(expectedOriginalTokenJti, "Expected original token JTI");

    var expectedIdentity = expectedCommittedCandidate.identity();
    UUID accountId = expectedIdentity.accountId();
    requireNonNilUuid(accountId, "Verified caller Account UUID");
    if (!expectedOriginalTokenJti.equals(expectedIdentity.tokenJti())
        || !accountId.equals(expectedCommittedCandidate.authoritySnapshot().accountId())) {
      throw unavailable();
    }
    return accountId;
  }

  /**
   * Locks the exact caller and current tenant billing-owner Account rows in deterministic order.
   *
   * <p>Call inside the caller's writable SERIALIZABLE transaction, before hosting-wallet or tenant
   * source locks. This shared lock step keeps paid admission readers on the same Account-first
   * order even when the caller is not the billing owner.
   */
  public static void lockRequiredAccountsBeforeTenantSources(
      DSLContext exactDsl, UUID tenantId, UUID callerAccountId) {
    Objects.requireNonNull(exactDsl, "The caller-enlisted DSLContext is required");
    requireNonNilUuid(tenantId, "Canonical tenant UUID");
    requireNonNilUuid(callerAccountId, "Verified caller Account UUID");
    requireWritableSerializableAccountTransaction(exactDsl);

    Record owner =
        exactDsl.fetchOne(
            "SELECT owner_account_uuid FROM account_tenant_billing_owners WHERE tenant_uuid = ?",
            tenantId);
    UUID billingOwnerAccountId = owner == null ? null : owner.get("owner_account_uuid", UUID.class);
    if (billingOwnerAccountId != null) {
      requireNonNilUuid(billingOwnerAccountId, "Tenant billing-owner Account UUID");
    }

    List<UUID> requiredAccounts = new ArrayList<>(2);
    requiredAccounts.add(callerAccountId);
    if (billingOwnerAccountId != null && !billingOwnerAccountId.equals(callerAccountId)) {
      requiredAccounts.add(billingOwnerAccountId);
    }
    requiredAccounts.sort(UUID::compareTo);
    for (UUID accountId : requiredAccounts) {
      lockExactAccountFirst(exactDsl, accountId);
    }
  }

  /**
   * Revalidates only the current authenticated Account caller for non-admitting exact recovery.
   *
   * <p>This intentionally omits tenant membership and entitlement reads: a retained lease read or
   * orphan-cleanup transition does not create a new binding. It still locks Account first and
   * independently resolves the exact current committed issuance, global/account authority,
   * immutable commit proof, and ACTIVE SQL token fence on this caller-enlisted transaction.
   */
  void requireCurrentAuthenticatedCallerOnly(
      CommittedCandidateVerificationData expectedCommittedCandidate) {
    Objects.requireNonNull(expectedCommittedCandidate, "Verified caller candidate is required");
    var expectedIdentity = expectedCommittedCandidate.identity();
    UUID accountId = expectedIdentity.accountId();
    UUID tokenJti = expectedIdentity.tokenJti();
    requireNonNilUuid(accountId, "Verified caller Account UUID");
    requireV4Uuid(tokenJti, "Verified caller token JTI");
    requireWritableSerializableAccountTransaction();

    // The verifier's private candidate supplies only the account lock and JTI lookup selectors.
    // The issuance owner independently reads and compares every immutable owner value below.
    lockExactAccountFirst(accountId);
    CommittedCandidateVerificationData currentCommittedCandidate =
        issuance
            .readCurrentCommittedCandidateByJti(tokenJti)
            .orElseThrow(AccountGameplayAdmissionCurrentSources::unavailable);
    if (!CommittedCandidateVerificationData.hasSameOwnerEvidence(
            expectedCommittedCandidate, currentCommittedCandidate)
        || !accountId.equals(currentCommittedCandidate.identity().accountId())
        || !tokenJti.equals(currentCommittedCandidate.identity().tokenJti())) {
      throw unavailable();
    }
  }

  private DemoTenantEntitlementEventV1Codec.Event readExactCurrentTenantEntitlement(
      UUID tenantId, TenantAuthorityEventV1Codec.Event authorityEvent) {
    DemoTenantEntitlementEventV1Codec.Event entitlement =
        tenantEntitlementOutbox
            .readEvent(tenantId, authorityEvent.tenantBillingSequence())
            .orElseThrow(AccountGameplayAdmissionCurrentSources::unavailable);
    if (!tenantId.equals(authorityEvent.tenantId())
        || !tenantId.equals(entitlement.canonicalTenantId())
        || !authorityEvent.requestId().equals(entitlement.requestId())
        || !authorityEvent.requestDigest().equals(entitlement.requestDigest())
        || authorityEvent.tenantBillingSequence() != entitlement.tenantBillingSequence()
        || !authorityEvent.tenantBillingStreamKey().equals(entitlement.outboxStreamKey())
        || !authorityEvent.tenantBillingEventId().equals(entitlement.eventId())
        || !authorityEvent.tenantBillingEventDigest().equals(entitlement.eventDigest())
        || authorityEvent.tenantAuthorityGeneration() != entitlement.tenantAuthorityGeneration()
        || authorityEvent.tenantAuthoritySourceVersion()
            != entitlement.tenantAuthoritySourceVersion()
        || !authorityEvent.sourceEvidence().equals(entitlement.sourceEvidence())) {
      throw unavailable();
    }
    return entitlement;
  }

  private static void requireCurrentActiveMembership(
      Snapshot snapshot, UUID accountId, UUID tenantId) {
    if (!ACCOUNT_ISSUER.equals(snapshot.issuerId())
        || !accountId.toString().equals(snapshot.accountId())
        || !tenantId.toString().equals(snapshot.tenantId())
        || !snapshot.membershipExists()
        || !"ACTIVE".equals(snapshot.membershipLifecycleState())
        || !snapshot.gameplayAdmissionAllowed()) {
      throw unavailable();
    }
  }

  private static Map<String, Object> currentLeaseSourceFields(
      Snapshot snapshot, CommittedCandidateVerificationData currentCommittedCandidate) {
    Map<String, Object> membershipBaseline =
        Map.of(
            "membershipLifecycleState", snapshot.membershipLifecycleState(),
            "membershipVersion", snapshot.membershipVersion(),
            "membershipAuthorityGeneration", snapshot.membershipAuthorityGeneration());
    List<Map<String, String>> outboxCheckpoints =
        snapshot.checkpoints().stream()
            .map(
                checkpoint ->
                    Map.of(
                        "outboxStreamKey",
                        checkpoint.outboxStreamKey(),
                        "outboxSequence",
                        checkpoint.outboxSequence()))
            .toList();

    return Map.of(
        "authorityTuple", currentAuthorityTuple(snapshot),
        "issuanceFence", snapshot.issuanceFence(),
        "membershipBaseline", membershipBaseline,
        "outboxCheckpoints", outboxCheckpoints,
        "tokenIdentityEvidence", currentTokenIdentityEvidence(snapshot, currentCommittedCandidate));
  }

  static Map<String, Object> currentAuthorityTuple(Snapshot snapshot) {
    AuthorityTuple current = snapshot.authorityTuple();
    Optional<AccountSecurityCutoff> accountCutoff =
        current
            .accountSecurityCutoff()
            .map(
                cutoff ->
                    new AccountSecurityCutoff(
                        cutoff.accountAuthorityGeneration(),
                        cutoff.outboxStreamKey(),
                        cutoff.outboxSequence()));
    Map<String, Object> authorityTuple =
        new LinkedHashMap<>(
            GameSessionAccountDelegationProfile.authorityTuple(
                Long.parseLong(current.issuerAuthGeneration()),
                Long.parseLong(current.accountAuthorityGeneration()),
                accountCutoff));
    authorityTuple.put("tenantAuthorityGeneration", current.tenantAuthorityGeneration());
    authorityTuple.put("membershipAuthorityGeneration", current.membershipAuthorityGeneration());
    authorityTuple.put("privateRealmGrantVersions", current.privateRealmGrantVersions());
    current
        .tenantBillingCutoff()
        .ifPresent(
            cutoffs ->
                authorityTuple.put("tenantBillingCutoff", currentTenantBillingCutoff(cutoffs)));
    return Map.copyOf(authorityTuple);
  }

  private static Map<String, Object> currentTenantBillingCutoff(
      Map<String, TenantBillingCutoff> currentCutoffs) {
    Map<String, Object> cutoffs = new LinkedHashMap<>();
    currentCutoffs.forEach(
        (tenantId, cutoff) ->
            cutoffs.put(
                tenantId,
                Map.of(
                    "tenantAuthorityGeneration", cutoff.tenantAuthorityGeneration(),
                    "tenantBillingSequence", cutoff.tenantBillingSequence(),
                    "outboxStreamKey", cutoff.outboxStreamKey(),
                    "outboxSequence", cutoff.outboxSequence())));
    return Map.copyOf(cutoffs);
  }

  private static Map<String, Object> currentTokenIdentityEvidence(
      Snapshot snapshot, CommittedCandidateVerificationData currentCommittedCandidate) {
    var identity = currentCommittedCandidate.identity();
    return Map.ofEntries(
        Map.entry("accountId", identity.accountId().toString()),
        Map.entry("operationId", identity.operationId().toString()),
        Map.entry("issuanceRequestId", identity.requestId().toString()),
        Map.entry("tokenJti", identity.tokenJti().toString()),
        Map.entry("tokenSHA256", currentCommittedCandidate.tokenSha256()),
        Map.entry("tokenGeneration", "1"),
        Map.entry("issuanceFence", snapshot.issuanceFence()),
        // The SQL candidate reader returns only after requiring the exact ACTIVE fence at 1.
        Map.entry("tokenIdentityFence", "1"),
        Map.entry("tokenProfile", GameSessionAccountDelegationProfile.PROFILE),
        Map.entry("issuedAt", Long.toString(identity.issuedAtEpochSecond())),
        Map.entry("notBefore", Long.toString(identity.notBeforeEpochSecond())),
        Map.entry("expiresAt", Long.toString(identity.expiresAtEpochSecond())));
  }

  private static void requireSupportedPublicTenantTuple(
      Snapshot snapshot,
      UUID tenantId,
      TenantAuthorityEventV1Codec.Event authorityEvent,
      long tenantBillingSequence) {
    String tenantKey = tenantId.toString();
    AuthorityTuple tuple = snapshot.authorityTuple();
    Map<String, String> expectedTenantGeneration =
        Map.of(tenantKey, Long.toString(authorityEvent.tenantAuthorityGeneration()));
    Map<String, String> expectedMembershipGeneration =
        Map.of(tenantKey, snapshot.membershipAuthorityGeneration());
    TenantBillingCutoff expectedBillingCutoff =
        new TenantBillingCutoff(
            Long.toString(authorityEvent.tenantAuthorityGeneration()),
            Long.toString(tenantBillingSequence),
            authorityEvent.outboxStreamKey(),
            Long.toString(authorityEvent.outboxSequence()));
    Map<String, TenantBillingCutoff> expectedBillingCutoffs =
        Map.of(tenantKey, expectedBillingCutoff);
    String membershipVersion = snapshot.membershipVersion().get(tenantKey);

    boolean tenantCheckpointPresent =
        snapshot.checkpoints().stream()
            .anyMatch(
                checkpoint ->
                    authorityEvent.outboxStreamKey().equals(checkpoint.outboxStreamKey())
                        && Long.toString(authorityEvent.outboxSequence())
                            .equals(checkpoint.outboxSequence()));
    boolean tenantSourceEventPresent =
        snapshot.sourceEvidence().stream()
            .anyMatch(
                evidence ->
                    authorityEvent.outboxStreamKey().equals(evidence.outboxStreamKey())
                        && Long.toString(authorityEvent.outboxSequence())
                            .equals(evidence.outboxSequence())
                        && authorityEvent.eventId().toString().equals(evidence.eventId())
                        && authorityEvent.eventDigest().equals(evidence.eventDigest()));

    if (snapshot.membershipVersion().size() != 1
        || !snapshot.membershipVersion().keySet().equals(Set.of(tenantKey))
        || !isPositiveDecimal(membershipVersion)
        || !isPositiveDecimal(snapshot.membershipAuthorityGeneration())
        || !isPositiveDecimal(snapshot.issuanceFence())
        || !isPositiveDecimal(tuple.issuerAuthGeneration())
        || !isPositiveDecimal(tuple.accountAuthorityGeneration())
        || !tuple.tenantAuthorityGeneration().equals(expectedTenantGeneration)
        || !tuple.membershipAuthorityGeneration().equals(expectedMembershipGeneration)
        || !tuple.privateRealmGrantVersions().isEmpty()
        || tuple.tenantBillingCutoff().isEmpty()
        || !tuple.tenantBillingCutoff().orElseThrow().equals(expectedBillingCutoffs)
        || !tenantCheckpointPresent
        || !tenantSourceEventPresent) {
      throw unavailable();
    }
  }

  private static void requireCandidateAuthorityMatchesSnapshot(
      CommittedCandidateVerificationData candidate, Snapshot snapshot, UUID accountId) {
    var authority = candidate.authoritySnapshot();
    AuthorityTuple tuple = snapshot.authorityTuple();
    if (!accountId.equals(authority.accountId())
        || !Long.toString(authority.issuerGeneration()).equals(tuple.issuerAuthGeneration())
        || !Long.toString(authority.accountGeneration()).equals(tuple.accountAuthorityGeneration())
        || !Long.toString(authority.issuanceFence()).equals(snapshot.issuanceFence())
        || !sameAccountSecurityCutoff(
            authority.accountSecurityCutoff(), tuple.accountSecurityCutoff())) {
      throw unavailable();
    }
  }

  private static boolean sameAccountSecurityCutoff(
      Optional<AccountSecurityCutoff> issuanceCutoff,
      Optional<MembershipAuthorityEventV1Codec.AccountSecurityCutoff> membershipCutoff) {
    if (issuanceCutoff.isEmpty() || membershipCutoff.isEmpty()) {
      return issuanceCutoff.isEmpty() && membershipCutoff.isEmpty();
    }
    AccountSecurityCutoff left = issuanceCutoff.orElseThrow();
    MembershipAuthorityEventV1Codec.AccountSecurityCutoff right = membershipCutoff.orElseThrow();
    return left.accountAuthorityGeneration().equals(right.accountAuthorityGeneration())
        && left.outboxStreamKey().equals(right.outboxStreamKey())
        && left.outboxSequence().equals(right.outboxSequence());
  }

  private void requireWritableSerializableAccountTransaction() {
    requireWritableSerializableAccountTransaction(dsl);
  }

  private static void requireWritableSerializableAccountTransaction(DSLContext dsl) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw unavailable();
    }
    Integer declaredIsolation =
        TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
    if (!Integer.valueOf(Connection.TRANSACTION_SERIALIZABLE).equals(declaredIsolation)) {
      throw unavailable();
    }
    dsl.connection(
        connection -> {
          if (connection.getAutoCommit()
              || connection.isReadOnly()
              || connection.getTransactionIsolation() != Connection.TRANSACTION_SERIALIZABLE) {
            throw unavailable();
          }
        });
  }

  private void lockExactAccountFirst(UUID accountId) {
    lockExactAccountFirst(dsl, accountId);
  }

  private static void lockExactAccountFirst(DSLContext dsl, UUID accountId) {
    Record locked =
        dsl.fetchOne(
            "SELECT account_uuid FROM accounts WHERE account_uuid = ? FOR UPDATE", accountId);
    if (locked == null || !accountId.equals(locked.get("account_uuid", UUID.class))) {
      throw unavailable();
    }
  }

  private static void requireNonNilUuid(UUID value, String label) {
    if (value == null || NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(label + " must be a non-nil UUID");
    }
  }

  private static void requireV4Uuid(UUID value, String label) {
    requireNonNilUuid(value, label);
    if (value.version() != 4 || value.variant() != 2) {
      throw new IllegalArgumentException(label + " must be a canonical UUIDv4");
    }
  }

  private static boolean isPositiveDecimal(String value) {
    return value != null && value.matches("[1-9][0-9]*");
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Account gameplay admission current sources are unavailable");
  }

  /** Complete, immutable source result; it contains neither a JWT nor registry bytes. */
  record Current(
      Snapshot snapshot,
      CommittedCandidateVerificationData currentCommittedCandidate,
      Long sourceValidUntilMs) {
    Current {
      Objects.requireNonNull(snapshot);
      Objects.requireNonNull(currentCommittedCandidate);
      if (sourceValidUntilMs != null && sourceValidUntilMs <= 0L) throw unavailable();
    }

    /** Returns exactly the five immutable, current-source subtrees used by lease capture. */
    Map<String, Object> leaseSourceFields() {
      return currentLeaseSourceFields(snapshot, currentCommittedCandidate);
    }

    /** Exact-compares current source evidence with an original NEW lease without rewriting it. */
    void requireMatchesOriginalLease(AccountGameplayAdmissionLeaseEvidence originalLease) {
      if (originalLease == null
          || originalLease.leaseKind()
              != AccountGameplayAdmissionLeaseEvidence.LeaseKind.NEW_BINDING) {
        throw unavailable();
      }
      if (sourceValidUntilMs != null
          && Long.parseLong((String) originalLease.carrier().get("expiresAt")) > sourceValidUntilMs)
        throw unavailable();
      Map<String, Object> carrier = originalLease.carrier();
      Object scopeValue = carrier.get("bindingScope");
      if (!(scopeValue instanceof Map<?, ?> bindingScope)
          || !snapshot.accountId().equals(bindingScope.get("accountId"))
          || !snapshot.tenantId().equals(bindingScope.get("tenantId"))) {
        throw unavailable();
      }

      Map<String, Object> expected = leaseSourceFields();
      for (String field : LEASE_SOURCE_FIELDS) {
        if (!Objects.equals(expected.get(field), carrier.get(field))) throw unavailable();
      }
    }
  }

  private record CallerSources(
      Snapshot snapshot, SourceSelection selection, CommittedCandidateVerificationData candidate) {}

  static final class Prepared {
    private final SourceSelection selected;
    private final AccountGameplayAdmissionPaidSource paidSource;
    private final AccountGameplayAdmissionPaidSource.Preparation paidPreparation;

    private Prepared(
        SourceSelection selected,
        AccountGameplayAdmissionPaidSource paidSource,
        AccountGameplayAdmissionPaidSource.Preparation paidPreparation) {
      this.selected = selected;
      this.paidSource = paidSource;
      this.paidPreparation = paidPreparation;
    }

    Observed observeOutsideSql() {
      if (TransactionSynchronizationManager.isActualTransactionActive()
          || TransactionSynchronizationManager.isSynchronizationActive()) throw unavailable();
      return new Observed(
          selected,
          paidSource,
          paidPreparation == null ? null : paidSource.observe(paidPreparation));
    }
  }

  static final class Observed {
    private final SourceSelection selected;
    private final AccountGameplayAdmissionPaidSource paidSource;
    private final AccountGameplayAdmissionPaidSource.Observation paidObservation;

    private Observed(
        SourceSelection selected,
        AccountGameplayAdmissionPaidSource paidSource,
        AccountGameplayAdmissionPaidSource.Observation paidObservation) {
      this.selected = selected;
      this.paidSource = paidSource;
      this.paidObservation = paidObservation;
    }
  }
}
