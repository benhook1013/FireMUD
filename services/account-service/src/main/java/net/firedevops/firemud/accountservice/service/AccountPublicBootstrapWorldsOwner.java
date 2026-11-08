package net.firedevops.firemud.accountservice.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.accountservice.client.CanonicalGameplayAdmissionDecisionReadClient;
import net.firedevops.firemud.accountservice.dto.BootstrapWorldDto;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.accountservice.service.session.AccountPlayerBootstrapCallerVerifier;
import net.firedevops.firemud.accountservice.service.session.AccountPlayerBootstrapCallerVerifier.VerifiedCaller;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.Target;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.VisiblePublicProductionTargets;
import net.firedevops.firemud.common.security.SessionContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Manually composed, nonadmitting public bootstrap world discovery over canonical public targets.
 * This owner creates no scope, membership, JOIN, or gameplay-admission authority.
 */
public final class AccountPublicBootstrapWorldsOwner {
  private static final int ENTITLEMENT_CONCURRENCY = 8;
  private static final Semaphore ENTITLEMENT_PERMITS = new Semaphore(ENTITLEMENT_CONCURRENCY, true);
  private final AccountPlayerBootstrapCallerVerifier callerVerifier;
  private final CanonicalGameplayAdmissionDecisionReadClient targets;
  private final AccountPublicBootstrapEntitlementOwner entitlements;
  private final Clock clock;

  public AccountPublicBootstrapWorldsOwner(
      AccountPlayerBootstrapCallerVerifier callerVerifier,
      CanonicalGameplayAdmissionDecisionReadClient targets,
      AccountPublicBootstrapEntitlementOwner entitlements,
      Clock clock) {
    this.callerVerifier = Objects.requireNonNull(callerVerifier);
    this.targets = Objects.requireNonNull(targets);
    this.entitlements = Objects.requireNonNull(entitlements);
    this.clock = Objects.requireNonNull(clock);
  }

  /** Returns the complete deterministic public-world list for the verified bootstrap caller. */
  public List<BootstrapWorldDto> listWorlds(String bootstrapToken) {
    requireNoAmbientTransaction();
    requireWorkloadOnlySourceContext();

    VerifiedCaller originalCaller = verifyCaller(bootstrapToken);
    List<Target> initialTargets = readCompleteTargets();
    Map<UUID, AccountPublicBootstrapEntitlementOwner.Evaluation> evaluated =
        evaluateEachTenantOnce(initialTargets);
    List<Target> finalTargets = readCompleteTargets();

    Map<UUID, Target> initialByTenant = targetsByCanonicalTenant(initialTargets);
    Map<UUID, Target> finalByTenant = targetsByCanonicalTenant(finalTargets);
    if (!initialByTenant.equals(finalByTenant)) {
      throw admissionPointerUnavailable(
          new IllegalStateException("Canonical public target collection changed during discovery"));
    }

    requireSameCaller(originalCaller, verifyCaller(bootstrapToken));
    requireFreshEvaluations(initialByTenant, evaluated);

    List<BootstrapWorldDto> visible = new ArrayList<>();
    for (Target target : initialTargets) {
      AccountPublicBootstrapEntitlementOwner.Evaluation evaluation =
          evaluated.get(target.canonicalTenantId());
      if (evaluation.gameplayAvailable()) {
        visible.add(
            new BootstrapWorldDto(
                target.tenantSlug(), target.worldSlug(), target.worldDisplayName()));
      }
    }
    visible.sort(
        Comparator.comparing(BootstrapWorldDto::worldSlug)
            .thenComparing(BootstrapWorldDto::tenantSlug));
    return List.copyOf(visible);
  }

  private VerifiedCaller verifyCaller(String bootstrapToken) {
    try {
      return Objects.requireNonNull(callerVerifier.requireCaller(bootstrapToken));
    } catch (AuthenticationException failure) {
      throw failure;
    } catch (RuntimeException unavailable) {
      throw authUnavailable("Bootstrap caller authority is unavailable", unavailable);
    }
  }

  private List<Target> readCompleteTargets() {
    try {
      VisiblePublicProductionTargets result =
          Objects.requireNonNull(
              targets.listVisiblePublicProductionAdmissionTargets(UUID.randomUUID()));
      return List.copyOf(Objects.requireNonNull(result.targets()));
    } catch (RuntimeException failure) {
      if (AccountPublicDiscoverySourceFailure.isUnavailable(failure)) {
        throw authUnavailable("Game Session discovery authority is unavailable", failure);
      }
      throw admissionPointerUnavailable(failure);
    }
  }

  private Map<UUID, AccountPublicBootstrapEntitlementOwner.Evaluation> evaluateEachTenantOnce(
      List<Target> canonicalTargets) {
    List<UUID> tenants =
        canonicalTargets.stream()
            .map(target -> Objects.requireNonNull(target.canonicalTenantId()))
            .distinct()
            .toList();
    if (tenants.isEmpty()) return Map.of();
    // Independent tenant owners retain their own transactions. No caller or SQL context is copied
    // to these bounded workers, and no observation is extended to hide slow discovery.
    try (var workers =
        Executors.newFixedThreadPool(
            ENTITLEMENT_CONCURRENCY,
            Thread.ofVirtual()
                .name("account-public-world-entitlement-", 0)
                .inheritInheritableThreadLocals(false)
                .factory())) {
      List<Callable<AccountPublicBootstrapEntitlementOwner.Evaluation>> reads =
          tenants.stream()
              .<Callable<AccountPublicBootstrapEntitlementOwner.Evaluation>>map(
                  tenantId ->
                      () -> {
                        requireNoAmbientTransaction();
                        requireWorkloadOnlySourceContext();
                        if (!ENTITLEMENT_PERMITS.tryAcquire(5, TimeUnit.SECONDS))
                          throw new IllegalStateException(
                              "Discovery entitlement capacity is unavailable");
                        try {
                          var evaluation = Objects.requireNonNull(entitlements.evaluate(tenantId));
                          if (!tenantId.equals(evaluation.canonicalTenantId()))
                            throw new IllegalStateException(
                                "Entitlement source returned another canonical tenant");
                          return evaluation;
                        } finally {
                          ENTITLEMENT_PERMITS.release();
                        }
                      })
              .toList();
      var completed = workers.invokeAll(reads);
      Map<UUID, AccountPublicBootstrapEntitlementOwner.Evaluation> evaluations =
          new LinkedHashMap<>();
      for (int index = 0; index < tenants.size(); index++)
        evaluations.put(tenants.get(index), completed.get(index).get());
      return Map.copyOf(evaluations);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw entitlementUnavailable(interrupted);
    } catch (ExecutionException unavailable) {
      throw entitlementUnavailable(unavailable.getCause());
    } catch (RuntimeException unavailable) {
      throw entitlementUnavailable(unavailable);
    }
  }

  private Map<UUID, Target> targetsByCanonicalTenant(List<Target> completeTargets) {
    Map<UUID, Target> byTenant = new HashMap<>();
    for (Target target : completeTargets) {
      if (target == null
          || target.canonicalTenantId() == null
          || byTenant.putIfAbsent(target.canonicalTenantId(), target) != null) {
        throw admissionPointerUnavailable(
            new IllegalStateException("Canonical public target collection is ambiguous"));
      }
    }
    return Map.copyOf(byTenant);
  }

  private void requireSameCaller(VerifiedCaller original, VerifiedCaller current) {
    if (original.accountId() != current.accountId()
        || !original.accountUuid().equals(current.accountUuid())) {
      throw new AuthenticationException(
          "CONNECT_CONTEXT_INVALID", "Bootstrap caller changed during world discovery");
    }
  }

  private void requireFreshEvaluations(
      Map<UUID, Target> currentTargets,
      Map<UUID, AccountPublicBootstrapEntitlementOwner.Evaluation> evaluations) {
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    for (UUID tenantId : currentTargets.keySet()) {
      AccountPublicBootstrapEntitlementOwner.Evaluation evaluation = evaluations.get(tenantId);
      if (evaluation == null
          || evaluation.evaluatedAt() == null
          || evaluation.validUntil() == null
          || now.isBefore(evaluation.evaluatedAt())
          || !now.isBefore(evaluation.validUntil())) {
        throw entitlementUnavailable(
            new IllegalStateException("Public discovery entitlement observation expired"));
      }
    }
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("Public bootstrap world discovery refuses ambient SQL");
    }
  }

  private static void requireWorkloadOnlySourceContext() {
    if (SessionContext.hasAuthenticatedCallerContext()
        || SessionContext.isInternalService()
        || SessionContext.getServiceName() != null
        || SessionContext.getServiceInstanceId() != null) {
      throw new IllegalStateException(
          "Public bootstrap discovery requires workload-only Game Session source context");
    }
  }

  private static AuthenticationException authUnavailable(String message, Throwable cause) {
    return new AuthenticationException("AUTH_UNAVAILABLE", message, cause);
  }

  private static AuthenticationException admissionPointerUnavailable(Throwable cause) {
    return new AuthenticationException(
        "ADMISSION_POINTER_UNAVAILABLE",
        "Canonical public-world discovery source is unavailable or changed",
        cause);
  }

  private static AuthenticationException entitlementUnavailable(Throwable cause) {
    return new AuthenticationException(
        "ENTITLEMENT_UNAVAILABLE", "Current public discovery entitlement is unavailable", cause);
  }
}
