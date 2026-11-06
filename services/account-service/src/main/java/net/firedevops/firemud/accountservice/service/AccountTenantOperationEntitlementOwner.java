package net.firedevops.firemud.accountservice.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountDemoTenantEntitlementRepository;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionCurrentReceipt;
import net.firedevops.firemud.common.account.authority.RuntimeTenantOperationEntitlementEvidence;
import net.firedevops.firemud.common.account.authority.TenantAuthorityEventV1Codec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Manually composed Account source owner for TENANT_OPERATION evaluations only.
 *
 * <p>Held source boundary: this owner and its peer-qualified receiver are manually composed and
 * unregistered. The receiver must authenticate and method-authorize the exact World Management peer
 * before constructing Request; parsing a workload URI and supplying UUIDs never grants authority.
 * Capacity commitment, durable evaluation replay and player admission remain outside this boundary.
 * Paid and demo selection is explicit and never falls back on absence. Pending/provisioning without
 * a genuine current receipt remains unavailable. Every returned canonical value is captured before
 * its source transaction commits; a failed commit returns no value.
 */
public final class AccountTenantOperationEntitlementOwner {
  private final AccountHostingSubscriptionLifecycleService hosting;
  private final AccountDemoTenantEntitlementRepository demos;
  private final TransactionTemplate transaction;
  private final Clock clock;
  private final String expectedWorkload;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "Injected SDK/repository collaborators remain private; copying would break owner composition.")
  public AccountTenantOperationEntitlementOwner(
      AccountHostingSubscriptionLifecycleService hosting,
      AccountDemoTenantEntitlementRepository demos,
      PlatformTransactionManager transactions,
      Clock clock,
      String workloadNamespace) {
    this.hosting = Objects.requireNonNull(hosting);
    this.demos = Objects.requireNonNull(demos);
    this.clock = Objects.requireNonNull(clock);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Canonical World Management namespace is required");
    }
    expectedWorkload =
        GrpcPeerIdentity.parseUri(
                "spiffe://firemud/ns/" + workloadNamespace + "/sa/world-management-service")
            .orElseThrow()
            .uri();
    transaction = new TransactionTemplate(Objects.requireNonNull(transactions));
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public RuntimeTenantOperationEntitlementEvidence evaluatePaid(Request request) {
    requireRequest(request);
    return hosting.readCurrentAndCapture(request.tenantId(), receipt -> paid(request, receipt));
  }

  public RuntimeTenantOperationEntitlementEvidence evaluateDemo(Request request) {
    requireRequest(request);
    requireNoAmbientTransaction();
    return Objects.requireNonNull(
        transaction.execute(
            status -> {
              requireSourceTransaction();
              DemoTenantEntitlementSnapshot snapshot = demos.readCurrent(request.tenantId());
              if (!request.tenantId().equals(snapshot.canonicalTenantId())) {
                throw unavailable("Demo source targets another tenant");
              }
              Map<String, Object> facts =
                  facts(
                      request.tenantId(),
                      "NON_PAID_DEMO",
                      snapshot.subscriptionStatus(),
                      snapshot.gameplayAvailable(),
                      snapshot.allowPublicJoin(),
                      snapshot.allowNewGameplayBindings(),
                      snapshot.allowNewInstanceStarts(),
                      quotas(
                          snapshot.quotas().maxActiveSessions(),
                          snapshot.quotas().maxConcurrentGameInstances(),
                          snapshot.quotas().maxStorageBytes()),
                      snapshot.entitlementVersion(),
                      snapshot.tenantBillingSequence(),
                      snapshot.tenantAuthorityGeneration(),
                      snapshot.tenantAuthorityOutboxStreamKey(),
                      snapshot.tenantAuthorityOutboxSequence(),
                      snapshot.tenantAuthorityEventId(),
                      snapshot.tenantAuthorityEventDigest());
              Map<String, Object> source = new LinkedHashMap<>();
              source.put("sourceEvidenceDigest", snapshot.sourceEvidence().evidenceDigest());
              source.put(
                  "tenantAuthoritySourceVersion",
                  Long.toString(snapshot.tenantAuthoritySourceVersion()));
              source.put("billingStream", snapshot.outboxStreamKey());
              source.put("billingSequence", Long.toString(snapshot.tenantBillingSequence()));
              source.put("billingEventId", snapshot.eventId().toString());
              source.put("billingEventDigest", snapshot.eventDigest());
              return capture(request, facts, source, now());
            }));
  }

  private RuntimeTenantOperationEntitlementEvidence paid(
      Request request, AccountHostingSubscriptionCurrentReceipt receipt) {
    requireSourceTransaction();
    Objects.requireNonNull(receipt, "Current hosting receipt is required");
    Instant evaluatedAt = now();
    if (!request.tenantId().equals(receipt.current().tenantId())
        || receipt.evaluatedAt() == null
        || receipt.validUntil() == null
        || evaluatedAt.isBefore(receipt.evaluatedAt())
        || !evaluatedAt.isBefore(receipt.validUntil())) {
      throw unavailable("Current hosting receipt is not fresh for this tenant");
    }
    var event = receipt.tenantAuthorityEvent();
    if (!request.tenantId().equals(event.tenantId())
        || receipt.tenantBillingSequence() != event.tenantBillingSequence()
        || !receipt.billingEventId().equals(event.tenantBillingEventId())
        || !receipt.billingEventDigest().equals(event.tenantBillingEventDigest())) {
      throw unavailable("Hosting receipt and current tenant authority disagree");
    }
    var quote = receipt.current().planQuote();
    Map<String, Object> facts =
        facts(
            request.tenantId(),
            "PAID_HOSTING",
            receipt.subscriptionStatus().storageValue(),
            receipt.gameplayAvailable(),
            receipt.allowPublicJoin(),
            receipt.allowNewGameplayBindings(),
            receipt.allowNewInstanceStarts(),
            quotas(
                quote.maxActiveSessions(),
                quote.maxConcurrentGameInstances(),
                quote.maxStorageBytes()),
            receipt.entitlementVersion(),
            receipt.tenantBillingSequence(),
            event.tenantAuthorityGeneration(),
            event.outboxStreamKey(),
            event.outboxSequence(),
            event.eventId(),
            event.eventDigest());
    Map<String, Object> source = new LinkedHashMap<>();
    source.put("receiptRequestId", receipt.receiptRequestId().toString());
    source.put("subscriptionId", receipt.current().subscriptionId().toString());
    source.put("subscriptionRowVersion", Long.toString(receipt.current().rowVersion()));
    source.put("creationRequestId", receipt.current().creationRequestId().toString());
    source.put("retainedPlanQuoteDigest", quote.digest());
    source.put("sourceEvidenceDigest", event.sourceEvidence().evidenceDigest());
    source.put("tenantAuthoritySourceVersion", Long.toString(event.tenantAuthoritySourceVersion()));
    source.put("billingStream", event.tenantBillingStreamKey());
    source.put("billingSequence", Long.toString(receipt.tenantBillingSequence()));
    source.put("billingEventId", receipt.billingEventId().toString());
    source.put("billingEventDigest", receipt.billingEventDigest());
    return capture(request, facts, source, evaluatedAt);
  }

  private static Map<String, Object> facts(
      UUID tenantId,
      String sourceKind,
      String subscriptionStatus,
      boolean gameplayAvailable,
      boolean allowPublicJoin,
      boolean allowNewGameplayBindings,
      boolean allowNewInstanceStarts,
      Map<String, Object> quotas,
      long entitlementVersion,
      long billingSequence,
      long generation,
      String authorityStream,
      long authoritySequence,
      UUID eventId,
      String eventDigest) {
    if (!TenantAuthorityEventV1Codec.streamKey(tenantId).equals(authorityStream)) {
      throw unavailable("Tenant authority stream differs from requested tenant");
    }
    Map<String, Object> facts = new LinkedHashMap<>();
    facts.put("schema", RuntimeTenantOperationEntitlementEvidence.SCHEMA);
    facts.put("variant", RuntimeTenantOperationEntitlementEvidence.VARIANT);
    facts.put("authorityAvailability", "AVAILABLE");
    facts.put("bundleVersion", "1");
    facts.put("tenantId", tenantId.toString());
    facts.put("sourceKind", sourceKind);
    facts.put("subscriptionStatus", subscriptionStatus);
    facts.put("provisioningStatus", null);
    facts.put("gameplayAvailable", gameplayAvailable);
    facts.put("allowPublicJoin", allowPublicJoin);
    facts.put("allowNewGameplayBindings", allowNewGameplayBindings);
    facts.put("allowNewInstanceStarts", allowNewInstanceStarts);
    facts.put("quotas", quotas);
    facts.put("entitlementVersion", Long.toString(entitlementVersion));
    facts.put("tenantBillingSequence", Long.toString(billingSequence));
    facts.put("tenantAuthorityGeneration", Long.toString(generation));
    Map<String, Object> cutoff =
        Map.of(
            "tenantAuthorityGeneration",
            Long.toString(generation),
            "tenantBillingSequence",
            Long.toString(billingSequence),
            "outboxStreamKey",
            authorityStream,
            "outboxSequence",
            Long.toString(authoritySequence));
    facts.put(
        "authorityTuple",
        Map.of(
            "tenantAuthorityGeneration", Map.of(tenantId.toString(), Long.toString(generation)),
            "tenantBillingCutoff", Map.of(tenantId.toString(), cutoff)));
    facts.put(
        "outboxCheckpoints",
        List.of(
            Map.of(
                "outboxStreamKey",
                authorityStream,
                "outboxSequence",
                Long.toString(authoritySequence),
                "sourceEventId",
                eventId.toString(),
                "sourceEventDigest",
                eventDigest)));
    return facts;
  }

  private static RuntimeTenantOperationEntitlementEvidence capture(
      Request request, Map<String, Object> facts, Map<String, Object> source, Instant evaluatedAt) {
    requireSourceTransaction();
    String snapshot =
        RuntimeTenantOperationEntitlementEvidence.canonicalDigest(
            Map.of(
                "schema",
                "tenant-operation-entitlement-snapshot/v1",
                "committedFacts",
                new LinkedHashMap<>(facts),
                "committedSource",
                source));
    facts.put("requestId", request.requestId().toString());
    facts.put("callerWorkload", request.authenticatedCallerWorkload());
    facts.put("evaluatedAt", evaluatedAt.toString());
    facts.put("snapshotIdentity", snapshot);
    facts.put(
        "evaluationIdentity",
        RuntimeTenantOperationEntitlementEvidence.canonicalDigest(
            Map.of(
                "schema",
                "tenant-operation-entitlement-evaluation/v1",
                "snapshotIdentity",
                snapshot,
                "tenantId",
                request.tenantId().toString(),
                "requestId",
                request.requestId().toString(),
                "callerWorkload",
                request.authenticatedCallerWorkload(),
                "evaluatedAt",
                evaluatedAt.toString(),
                "evaluationNonce",
                UUID.randomUUID().toString())));
    return RuntimeTenantOperationEntitlementEvidence.fromOwnerEvaluation(facts);
  }

  private static Map<String, Object> quotas(long sessions, long instances, long storage) {
    return Map.of(
        "maxActiveSessions",
        Long.toString(sessions),
        "maxConcurrentGameInstances",
        Long.toString(instances),
        "maxStorageBytes",
        Long.toString(storage));
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  private void requireRequest(Request request) {
    Objects.requireNonNull(request, "Tenant operation request is required");
    if (!expectedWorkload.equals(request.authenticatedCallerWorkload())) {
      throw new IllegalArgumentException("Expected exact same-namespace World Management peer");
    }
  }

  private static void requireSourceTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw unavailable(
          "Complete runtime evidence requires its writable Account source transaction");
    }
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw unavailable("Demo evaluation refuses an ambient transaction or synchronization");
    }
  }

  private static SourceUnavailableException unavailable(String message) {
    return new SourceUnavailableException(message);
  }

  /** An evaluated source cannot supply current evidence; not a configuration or codec failure. */
  public static final class SourceUnavailableException extends IllegalStateException {
    public SourceUnavailableException(String message) {
      super("ENTITLEMENT_UNAVAILABLE: " + message);
    }
  }

  /** Trusted transport assertion only; construction itself does not authenticate the caller. */
  public record Request(UUID tenantId, UUID requestId, String authenticatedCallerWorkload) {
    public Request {
      if (tenantId == null
          || tenantId.equals(new UUID(0L, 0L))
          || requestId == null
          || requestId.equals(new UUID(0L, 0L))) {
        throw new IllegalArgumentException(
            "Canonical non-nil tenant and request UUIDs are required");
      }
      GrpcPeerIdentity peer =
          GrpcPeerIdentity.parseUri(authenticatedCallerWorkload)
              .filter(identity -> identity.uri().equals(authenticatedCallerWorkload))
              .orElseThrow(
                  () ->
                      new IllegalArgumentException("Canonical authenticated workload is required"));
      if (!"world-management-service".equals(peer.service())) {
        throw new IllegalArgumentException("Tenant operation requires World Management");
      }
    }
  }
}
