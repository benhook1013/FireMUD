package net.firedevops.firemud.accountservice.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.StatusRuntimeException;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.client.CanonicalGameplayAdmissionDecisionReadClient;
import net.firedevops.firemud.accountservice.client.StripeHostingSubscriptionObserver;
import net.firedevops.firemud.accountservice.dto.AccountJoinDigest;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountCanonicalRuntimeMembershipSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository.CanonicalConnectScopeEvidence;
import net.firedevops.firemud.accountservice.repository.AccountDemoTenantEntitlementRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository.CommittedCandidateVerificationData;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionCurrentReceipt;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.TenantProvenanceKind;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountSubscriptionIntentRepository.FenceRejectedException;
import net.firedevops.firemud.accountservice.repository.AccountTenantAuthorityEventRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantAuthorityEventRepository.SourceSelection;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayAdmissionCallerVerifier;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayAdmissionCallerVerifier.VerifiedCaller;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.TenantBillingCutoff;
import net.firedevops.firemud.common.account.authority.RuntimeMembershipAuthorityEvidenceValidator;
import net.firedevops.firemud.common.account.authority.RuntimeMembershipAuthorityEvidenceValidator.Snapshot;
import net.firedevops.firemud.common.account.authority.RuntimePlayerAdmissionEntitlementEvidence;
import net.firedevops.firemud.common.account.authority.TenantAuthorityEventV1Codec;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.PublishedRealmEvidence;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.RetainedTargetSelector;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.common.security.SessionContext;
import org.jooq.DSLContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unregistered Account owner for PUBLIC_PRODUCTION SHARED PRESEEDED player entitlement evidence.
 * JWT/registry, Stripe and protected Game Session/World reads run outside SQL. A final
 * Account-first writable SERIALIZABLE transaction rereads caller issuance, membership (including
 * explicit absence), exact persisted scope and paid/demo currentness before constructing the
 * canonical value. No value escapes a failed commit. All repositories and the DSL must share the
 * transaction manager.
 *
 * <p>The protected target is a point-in-time source read, not a World lock through this transaction
 * or later mutation. This grants no capacity or admission. Request workload remains a trusted
 * method-authorized transport assertion; parsing it does not authenticate it. No bean, public RPC,
 * issuer or activation is supplied. Private/playtest and PLAYER_CREATED are unsupported.
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected collaborators are private Account owner-source components.")
public final class AccountPlayerAdmissionEntitlementOwner {
  private static final String ISSUER = GameSessionAccountDelegationProfile.ISSUER;
  private final DSLContext dsl;
  private final AccountRepository accounts;
  private final AccountGameplayAdmissionCallerVerifier callers;
  private final AccountGameplayDelegationIssuanceRepository issuances;
  private final AccountCanonicalRuntimeMembershipSnapshotRepository memberships;
  private final AccountConnectScopeRepository scopes;
  private final AccountHostingSubscriptionLifecycleRepository hosting;
  private final StripeHostingSubscriptionObserver observer;
  private final AccountDemoTenantEntitlementRepository demos;
  private final AccountTenantAuthorityEventRepository sources;
  private final CanonicalGameplayAdmissionDecisionReadClient targets;
  private final String namespace;
  private final TransactionTemplate transaction;
  private final Clock clock;
  private final String expectedWorkload;

  public AccountPlayerAdmissionEntitlementOwner(
      DSLContext dsl,
      AccountRepository accounts,
      AccountGameplayAdmissionCallerVerifier callers,
      AccountGameplayDelegationIssuanceRepository issuances,
      AccountCanonicalRuntimeMembershipSnapshotRepository memberships,
      AccountConnectScopeRepository scopes,
      AccountHostingSubscriptionLifecycleRepository hosting,
      StripeHostingSubscriptionObserver observer,
      AccountDemoTenantEntitlementRepository demos,
      AccountTenantAuthorityEventRepository sources,
      CanonicalGameplayAdmissionDecisionReadClient targets,
      PlatformTransactionManager transactions,
      Clock clock,
      String workloadNamespace) {
    this.dsl = Objects.requireNonNull(dsl);
    this.accounts = Objects.requireNonNull(accounts);
    this.callers = Objects.requireNonNull(callers);
    this.issuances = Objects.requireNonNull(issuances);
    this.memberships = Objects.requireNonNull(memberships);
    this.scopes = Objects.requireNonNull(scopes);
    this.hosting = Objects.requireNonNull(hosting);
    this.observer = Objects.requireNonNull(observer);
    this.demos = Objects.requireNonNull(demos);
    this.sources = Objects.requireNonNull(sources);
    this.targets = Objects.requireNonNull(targets);
    this.namespace = workloadNamespace;
    this.clock = Objects.requireNonNull(clock);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Canonical Game Session workload namespace is required");
    }
    expectedWorkload = "spiffe://firemud/ns/" + workloadNamespace + "/sa/game-session-service";
    transaction = new TransactionTemplate(Objects.requireNonNull(transactions));
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    transaction.setReadOnly(false);
    transaction.setTimeout(5);
  }

  /** Selects the sole current Account-owned billing source; callers cannot select a family. */
  public RuntimePlayerAdmissionEntitlementEvidence evaluate(
      Request request, String presentedInitialLoginJwt) {
    VerifiedCaller caller = verifyCaller(request, presentedInitialLoginJwt);
    Prepared prepared =
        inSourceTransaction(
            () -> {
              CurrentPlayer player = readCurrentPlayer(request, caller);
              return new Prepared(player, sources.readCurrentSourceSelection(request.tenantId()));
            });
    requireCurrentTimes(prepared.player(), now());
    RuntimePlayerAdmissionEntitlementEvidence evidence =
        switch (prepared.selection().kind()) {
          case PAID_HOSTING -> capturePaidSources(request, caller, prepared);
          case NON_PAID_DEMO -> captureDemoSources(request, caller, prepared);
        };
    try {
      evidence.requireCurrentAt(now());
    } catch (IllegalArgumentException expired) {
      // This final post-commit check only rejects an expired or future source observation.
      throw unavailable();
    }
    return evidence;
  }

  private RuntimePlayerAdmissionEntitlementEvidence capturePaidSources(
      Request request, VerifiedCaller caller, Prepared prepared) {
    var fence =
        inSourceTransaction(
            () -> {
              try {
                return hosting.prepareCurrentRead(request.tenantId());
              } catch (FenceRejectedException rejected) {
                throw unavailable();
              }
            });
    requireNoAmbientTransaction();
    var observation = observer.observe(fence.binding());
    PublishedRealmEvidence target = readProtectedTarget(request, prepared.player().scope());
    return inSourceTransaction(
        () -> {
          CurrentPlayer player = readCurrentPlayer(request, caller);
          if (!prepared.player().scope().equals(player.scope())) throw unavailable();
          requireSameHead(request, prepared.selection());
          final AccountHostingSubscriptionCurrentReceipt receipt;
          try {
            receipt = hosting.readCurrent(fence, observation);
          } catch (FenceRejectedException rejected) {
            throw unavailable();
          }
          Instant evaluatedAt = now();
          requireFreshReceipt(request, receipt, evaluatedAt);
          var event = receipt.tenantAuthorityEvent();
          if (!prepared.selection().matches(event)) throw unavailable();
          requireTenantSource(
              player.snapshot(),
              event.tenantId(),
              event.tenantAuthorityGeneration(),
              receipt.tenantBillingSequence(),
              event.outboxStreamKey(),
              event.outboxSequence(),
              event.eventId(),
              event.eventDigest());
          if (!receipt.billingEventId().equals(event.tenantBillingEventId())
              || !receipt.billingEventDigest().equals(event.tenantBillingEventDigest())
              || receipt.tenantBillingSequence() != event.tenantBillingSequence()) {
            throw unavailable();
          }
          requireCurrentTimes(player, evaluatedAt);
          var quote = receipt.current().planQuote();
          return capture(
              request,
              player,
              target,
              facts(
                  "PAID_HOSTING",
                  receipt.subscriptionStatus().storageValue(),
                  receipt.gameplayAvailable(),
                  receipt.allowPublicJoin(),
                  receipt.allowNewGameplayBindings(),
                  receipt.allowNewInstanceStarts(),
                  quote.maxActiveSessions(),
                  quote.maxConcurrentGameInstances(),
                  quote.maxStorageBytes(),
                  receipt.entitlementVersion(),
                  receipt.tenantBillingSequence(),
                  event.tenantAuthorityGeneration()),
              RuntimePlayerAdmissionEntitlementEvidence.digest(
                  Map.of(
                      "receiptRequestId", receipt.receiptRequestId().toString(),
                      "subscriptionId", receipt.current().subscriptionId().toString(),
                      "subscriptionRowVersion", Long.toString(receipt.current().rowVersion()),
                      "billingEventId", receipt.billingEventId().toString(),
                      "billingEventDigest", receipt.billingEventDigest(),
                      "tenantAuthorityEventDigest", event.eventDigest())),
              evaluatedAt,
              receipt.validUntil());
        });
  }

  private RuntimePlayerAdmissionEntitlementEvidence captureDemoSources(
      Request request, VerifiedCaller caller, Prepared prepared) {
    PublishedRealmEvidence target = readProtectedTarget(request, prepared.player().scope());
    return inSourceTransaction(
        () -> {
          CurrentPlayer player = readCurrentPlayer(request, caller);
          if (!prepared.player().scope().equals(player.scope())) throw unavailable();
          requireSameHead(request, prepared.selection());
          DemoTenantEntitlementSnapshot demo = demos.readCurrent(request.tenantId());
          var head = prepared.selection().checkpoint();
          if (!demo.tenantAuthorityEventId().equals(head.eventId())
              || !demo.tenantAuthorityEventDigest().equals(head.eventDigest())
              || !demo.canonicalTenantId().equals(head.tenantId())
              || demo.tenantAuthorityGeneration() != head.tenantAuthorityGeneration()
              || !demo.tenantAuthorityOutboxStreamKey().equals(head.outboxStreamKey())
              || demo.tenantAuthorityOutboxSequence() != head.outboxSequence()
              || demo.tenantBillingSequence() != head.tenantBillingSequence()
              || !demo.eventId().equals(head.tenantBillingEventId())
              || !demo.eventDigest().equals(head.tenantBillingEventDigest())
              || demo.tenantAuthoritySourceVersion() != head.tenantAuthoritySourceVersion()) {
            throw unavailable();
          }
          requireTenantSource(
              player.snapshot(),
              demo.canonicalTenantId(),
              demo.tenantAuthorityGeneration(),
              demo.tenantBillingSequence(),
              demo.tenantAuthorityOutboxStreamKey(),
              demo.tenantAuthorityOutboxSequence(),
              demo.tenantAuthorityEventId(),
              demo.tenantAuthorityEventDigest());
          Instant capturedAt = now();
          requireCurrentTimes(player, capturedAt);
          return capture(
              request,
              player,
              target,
              facts(
                  "NON_PAID_DEMO",
                  null,
                  demo.gameplayAvailable(),
                  demo.allowPublicJoin(),
                  demo.allowNewGameplayBindings(),
                  demo.allowNewInstanceStarts(),
                  demo.quotas().maxActiveSessions(),
                  demo.quotas().maxConcurrentGameInstances(),
                  demo.quotas().maxStorageBytes(),
                  demo.entitlementVersion(),
                  demo.tenantBillingSequence(),
                  demo.tenantAuthorityGeneration()),
              demo.snapshotIdentity(),
              capturedAt,
              null);
        });
  }

  private VerifiedCaller verifyCaller(Request request, String token) {
    Objects.requireNonNull(request, "Player entitlement request is required");
    requireNoAmbientTransaction();
    if (!expectedWorkload.equals(request.authenticatedCallerWorkload())
        || !namespace.equals(request.target().targetNamespace())
        || SessionContext.hasAuthenticatedCallerContext()
        || SessionContext.isInternalService()
        || SessionContext.getServiceName() != null
        || SessionContext.getServiceInstanceId() != null) throw unavailable();
    VerifiedCaller caller = callers.verifyPresentedCompactToken(token);
    if (!expectedWorkload.equals(caller.identity().callerWorkload())
        || !request.target().accountId().equals(caller.identity().accountId())) throw unavailable();
    return caller;
  }

  private CurrentPlayer readCurrentPlayer(Request request, VerifiedCaller caller) {
    UUID accountId = caller.identity().accountId();
    var account =
        accounts
            .findByAccountUuidForUpdate(accountId)
            .orElseThrow(AccountPlayerAdmissionEntitlementOwner::unavailable);
    if (!accountId.equals(account.getAccountUuid())) throw unavailable();
    CommittedCandidateVerificationData current =
        issuances
            .readCurrentCommittedCandidateByJti(caller.identity().tokenJti())
            .orElseThrow(AccountPlayerAdmissionEntitlementOwner::unavailable);
    if (!CommittedCandidateVerificationData.hasSameOwnerEvidence(
        caller.currentCandidate(), current)) {
      throw unavailable();
    }
    Snapshot snapshot = memberships.readCurrent(accountId, request.tenantId());
    RuntimeMembershipAuthorityEvidenceValidator.validate(snapshot);
    var authority = current.authoritySnapshot();
    var tuple = snapshot.authorityTuple();
    var expectedCutoff =
        authority
            .accountSecurityCutoff()
            .map(
                cutoff ->
                    Map.of(
                        "accountAuthorityGeneration",
                        cutoff.accountAuthorityGeneration(),
                        "outboxStreamKey",
                        cutoff.outboxStreamKey(),
                        "outboxSequence",
                        cutoff.outboxSequence()));
    var actualCutoff =
        tuple
            .accountSecurityCutoff()
            .map(
                cutoff ->
                    Map.of(
                        "accountAuthorityGeneration",
                        cutoff.accountAuthorityGeneration(),
                        "outboxStreamKey",
                        cutoff.outboxStreamKey(),
                        "outboxSequence",
                        cutoff.outboxSequence()));
    if (!ISSUER.equals(snapshot.issuerId())
        || !accountId.toString().equals(snapshot.accountId())
        || !request.tenantId().toString().equals(snapshot.tenantId())
        || !accountId.equals(authority.accountId())
        || !Long.toString(authority.issuerGeneration()).equals(tuple.issuerAuthGeneration())
        || !Long.toString(authority.accountGeneration()).equals(tuple.accountAuthorityGeneration())
        || !Long.toString(authority.issuanceFence()).equals(snapshot.issuanceFence())
        || !expectedCutoff.equals(actualCutoff)
        || !tuple.privateRealmGrantVersions().isEmpty()) {
      throw unavailable();
    }
    var verifiedScope =
        scopes
            .findCanonical(request.connectScopeId())
            .orElseThrow(AccountPlayerAdmissionEntitlementOwner::unavailable);
    CanonicalConnectScopeEvidence scope =
        scopes
            .findCanonicalEvidenceByTokenHash(AccountJoinDigest.tokenHash(request.connectScopeId()))
            .orElseThrow(AccountPlayerAdmissionEntitlementOwner::unavailable);
    if (!"PUBLIC_PRODUCTION".equals(scope.targetClass())
        || scope.tenantProvenance().kind() != TenantProvenanceKind.FRESH_GAME_DESIGN
        || scope.tenantProvenance().legacyTenantId() != null
        || !accountId.equals(scope.accountUuid())
        || !request.tenantId().equals(scope.tenantUuid())
        || !AccountJoinDigest.scopeV2(verifiedScope).equals(scope.scopeDigest())
        || !Objects.equals(account.getId(), scope.privateAccountId())) {
      throw unavailable();
    }
    if (!"SHARED".equals(scope.playableStateScope())) throw targetPrecondition();
    if (!request.target().equals(selector(request, scope))) throw targetPrecondition();
    return new CurrentPlayer(snapshot, scope, current);
  }

  private static void requireTenantSource(
      Snapshot snapshot,
      UUID tenantId,
      long generation,
      long billingSequence,
      String stream,
      long sequence,
      UUID eventId,
      String eventDigest) {
    String tenant = snapshot.tenantId();
    TenantBillingCutoff cutoff =
        new TenantBillingCutoff(
            Long.toString(generation),
            Long.toString(billingSequence),
            stream,
            Long.toString(sequence));
    if (!tenant.equals(tenantId.toString())
        || !TenantAuthorityEventV1Codec.streamKey(tenantId).equals(stream)
        || !snapshot
            .authorityTuple()
            .tenantAuthorityGeneration()
            .equals(Map.of(tenant, Long.toString(generation)))
        || !snapshot
            .authorityTuple()
            .tenantBillingCutoff()
            .filter(Map.of(tenant, cutoff)::equals)
            .isPresent()
        || snapshot.sourceEvidence().stream()
            .noneMatch(
                source ->
                    stream.equals(source.outboxStreamKey())
                        && Long.toString(sequence).equals(source.outboxSequence())
                        && eventId.toString().equals(source.eventId())
                        && eventDigest.equals(source.eventDigest()))) {
      throw unavailable();
    }
  }

  private static void requireFreshReceipt(
      Request request, AccountHostingSubscriptionCurrentReceipt receipt, Instant now) {
    if (!request.tenantId().equals(receipt.current().tenantId())
        || receipt.evaluatedAt() == null
        || receipt.validUntil() == null
        || now.isBefore(receipt.evaluatedAt())
        || !now.isBefore(receipt.validUntil())) {
      throw unavailable();
    }
  }

  private static void requireCurrentTimes(CurrentPlayer player, Instant evaluatedAt) {
    CanonicalConnectScopeEvidence scope = player.scope();
    var identity = player.issuance().identity();
    if (evaluatedAt.isBefore(Instant.parse(scope.evaluatedAt()))
        || !evaluatedAt.isBefore(Instant.parse(scope.connectScopeExpiresAt()))
        || evaluatedAt.getEpochSecond() < identity.notBeforeEpochSecond()
        || evaluatedAt.getEpochSecond() >= identity.expiresAtEpochSecond()) {
      throw unavailable();
    }
  }

  private <T> T inSourceTransaction(Supplier<T> action) {
    requireNoAmbientTransaction();
    return Objects.requireNonNull(
        transaction.execute(
            status -> {
              if (!TransactionSynchronizationManager.isActualTransactionActive()
                  || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
                throw unavailable();
              dsl.connection(
                  connection -> {
                    if (connection.getAutoCommit()
                        || connection.isReadOnly()
                        || connection.getTransactionIsolation()
                            != Connection.TRANSACTION_SERIALIZABLE) {
                      throw unavailable();
                    }
                  });
              return Objects.requireNonNull(action.get());
            }));
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) throw unavailable();
  }

  private static SourceUnavailableException unavailable() {
    return new SourceUnavailableException();
  }

  /** Current owner evidence cannot be supplied; not a configuration or codec failure. */
  public static final class SourceUnavailableException extends IllegalStateException {
    public SourceUnavailableException() {
      super("ENTITLEMENT_UNAVAILABLE: current player source evidence is unavailable");
    }
  }

  private static TargetPreconditionException targetPrecondition() {
    return new TargetPreconditionException();
  }

  /** The current target is unsupported or differs from the exact retained selectors. */
  public static final class TargetPreconditionException extends IllegalStateException {
    public TargetPreconditionException() {
      super(
          "ENTITLEMENT_TARGET_PRECONDITION: current target does not match the supported retained target");
    }
  }

  private void requireSameHead(Request request, SourceSelection selected) {
    SourceSelection current = sources.readCurrentSourceSelection(request.tenantId());
    if (selected.kind() != current.kind() || !selected.matches(current.checkpoint()))
      throw unavailable();
  }

  /** Trusted method-authorized workload assertion; construction does not authenticate a peer. */
  public record Request(
      RetainedTargetSelector target, String connectScopeId, String authenticatedCallerWorkload) {
    public Request {
      Objects.requireNonNull(target, "Exact resolved target selectors are required");
      if (connectScopeId == null || connectScopeId.isBlank() || connectScopeId.length() > 16384)
        throw new IllegalArgumentException("Bounded opaque connect scope is required");
      GrpcPeerIdentity.parseUri(authenticatedCallerWorkload)
          .filter(
              peer ->
                  peer.uri().equals(authenticatedCallerWorkload)
                      && "game-session-service".equals(peer.service()))
          .orElseThrow(
              () -> new IllegalArgumentException("Exact Game Session workload is required"));
    }

    public UUID tenantId() {
      return target.tenantId();
    }

    public UUID requestId() {
      return target.readRequestId();
    }

    @Override
    public String toString() {
      return "PlayerAdmissionEntitlementRequest[redacted]";
    }
  }

  private static RetainedTargetSelector selector(
      Request request, CanonicalConnectScopeEvidence scope) {
    return new RetainedTargetSelector(
        request.requestId(),
        scope.accountUuid(),
        request.target().targetNamespace(),
        scope.tenantUuid(),
        scope.tenantSlug(),
        scope.worldSlug(),
        scope.realmId(),
        scope.realmSlug(),
        scope.playableStateNamespaceUuid(),
        scope.playableStateScope(),
        scope.gameInstanceUuid(),
        scope.catalogRevision(),
        scope.pointerVersion());
  }

  private PublishedRealmEvidence readProtectedTarget(
      Request request, CanonicalConnectScopeEvidence scope) {
    requireNoAmbientTransaction();
    var selector = selector(request, scope);
    try {
      var resolved = Objects.requireNonNull(targets.resolvePublishedRealmAdmissionTarget(selector));
      if (!selector.equals(resolved.selector())) throw targetPrecondition();
      requireSupportedTarget(resolved.currentTarget());
      try {
        CanonicalGameplayAdmissionDecisionReadGrpcCodec.requireRetainedTargetMatches(
            selector, resolved.currentTarget());
      } catch (IllegalArgumentException changedTarget) {
        // Only this exact target comparison is a precondition, not arbitrary codec failures.
        throw targetPrecondition();
      }
      var result =
          Objects.requireNonNull(
              targets.readPublishedRealmAdmissionOwner(resolved.strictSelector()));
      if (!resolved.strictSelector().equals(result.selector())
          || !resolved.currentTarget().equals(result.currentTarget())) throw targetPrecondition();
      requireSupportedTarget(result.currentTarget());
      return result;
    } catch (CanonicalGameplayAdmissionDecisionReadClient.ClientUnavailableException failure) {
      throw unavailable();
    } catch (StatusRuntimeException failure) {
      switch (failure.getStatus().getCode()) {
        case UNAVAILABLE, DEADLINE_EXCEEDED, CANCELLED, ABORTED -> throw unavailable();
        case FAILED_PRECONDITION -> throw targetPrecondition();
        default -> throw failure;
      }
    }
  }

  private static void requireSupportedTarget(
      CanonicalGameplayAdmissionDecisionReadGrpcCodec.Target target) {
    if (!"SHARED".equals(target.playableStateScope())
        || !"PRESEEDED_ONLY".equals(target.characterCreationPolicy())) throw targetPrecondition();
  }

  private static RuntimePlayerAdmissionEntitlementEvidence capture(
      Request request,
      CurrentPlayer player,
      PublishedRealmEvidence target,
      Map<String, Object> facts,
      String entitlementSourceDigest,
      Instant time,
      Instant paidReceiptValidUntil) {
    var scope = player.scope();
    var current = target.currentTarget();
    requireSupportedTarget(current);
    Map<String, Object> binding = new LinkedHashMap<>();
    binding.put("targetClass", "PUBLIC_PRODUCTION");
    binding.put("characterCreationPolicy", current.characterCreationPolicy());
    binding.put("targetNamespace", current.targetNamespace());
    binding.put("tenantId", scope.tenantUuid().toString());
    binding.put("tenantSlug", scope.tenantSlug());
    binding.put("worldSlug", scope.worldSlug());
    binding.put("realmId", scope.realmId().toString());
    binding.put("realmSlug", scope.realmSlug());
    binding.put("playableStateNamespaceId", scope.playableStateNamespaceUuid().toString());
    binding.put("playableStateScope", scope.playableStateScope());
    binding.put("gameInstanceId", scope.gameInstanceUuid().toString());
    binding.put("canonicalVersionId", current.canonicalVersionId().toString());
    binding.put("catalogRevision", Long.toString(scope.catalogRevision()));
    binding.put("pointerVersion", Long.toString(scope.pointerVersion()));
    binding.put("activeWorldEpoch", Long.toString(current.activeWorldEpoch()));
    binding.put("evaluatedAt", scope.evaluatedAt());
    binding.put("connectScopeExpiresAt", scope.connectScopeExpiresAt());
    var caller = player.issuance();
    var world = target.worldLifecycleEvidence();
    Map<String, Object> sources = new LinkedHashMap<>();
    sources.put("callerTokenJti", caller.identity().tokenJti().toString());
    sources.put(
        "callerTokenExpiresAt",
        Instant.ofEpochSecond(caller.identity().expiresAtEpochSecond()).toString());
    sources.put(
        "paidReceiptValidUntil",
        paidReceiptValidUntil == null ? null : paidReceiptValidUntil.toString());
    sources.put("callerTokenSha256", caller.tokenSha256());
    sources.put("callerRegistryRecordSha256", caller.canonicalRegistryRecordSha256());
    sources.put("callerCommitProofSha256", caller.commitProofSha256());
    sources.put("callerContextId", caller.identity().callerContextId().toString());
    sources.put("callerOperationId", caller.identity().operationId().toString());
    sources.put("scopeDigest", scope.scopeDigest());
    sources.put("entitlementSourceDigest", entitlementSourceDigest);
    sources.put(
        "admissionPointerSnapshotDigest", target.sourceEvidence().admissionPointerSnapshotDigest());
    sources.put(
        "gameSessionOwnerProofDigest",
        target.sourceEvidence().gameSessionOwnerProof().proofDigest());
    sources.put(
        "publishedPolicySetDigest",
        target.sourceEvidence().publishedPolicySetEvidence().policySetDigest());
    sources.put(
        "worldLifecycleDigest",
        RuntimePlayerAdmissionEntitlementEvidence.digestBytes(world.canonicalBytes()));
    sources.put("worldCaptureId", world.captureId().toString());
    sources.put("worldRowVersion", Long.toString(world.rowVersion()));
    return RuntimePlayerAdmissionEntitlementEvidence.fromOwnerEvaluation(
        request.requestId(),
        request.authenticatedCallerWorkload(),
        scope.scopeTokenHash(),
        player.snapshot(),
        binding,
        facts,
        sources,
        time);
  }

  private static Map<String, Object> facts(
      String kind,
      String status,
      boolean gameplay,
      boolean join,
      boolean bindings,
      boolean starts,
      long sessions,
      long instances,
      long storage,
      long version,
      long sequence,
      long generation) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("sourceKind", kind);
    result.put("subscriptionStatus", status);
    result.put("provisioningStatus", null);
    result.put("gameplayAvailable", gameplay);
    result.put("allowPublicJoin", join);
    result.put("allowNewGameplayBindings", bindings);
    result.put("allowNewInstanceStarts", starts);
    result.put(
        "quotas",
        Map.of(
            "maxActiveSessions",
            Long.toString(sessions),
            "maxConcurrentGameInstances",
            Long.toString(instances),
            "maxStorageBytes",
            Long.toString(storage)));
    result.put("entitlementVersion", Long.toString(version));
    result.put("tenantBillingSequence", Long.toString(sequence));
    result.put("tenantAuthorityGeneration", Long.toString(generation));
    return result;
  }

  private record Prepared(CurrentPlayer player, SourceSelection selection) {}

  private record CurrentPlayer(
      Snapshot snapshot,
      CanonicalConnectScopeEvidence scope,
      CommittedCandidateVerificationData issuance) {}
}
