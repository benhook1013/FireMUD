package net.firedevops.firemud.accountservice.service.session;

import java.time.Clock;
import java.util.Objects;
import net.firedevops.firemud.account.v1.ReceiveAccountJwtReadinessProbeRequest;
import net.firedevops.firemud.account.v1.ReceiveAccountJwtReadinessProbeResponse;
import net.firedevops.firemud.accountservice.config.AccountJwtReadinessPodReceiverWorkloadGuard;
import net.firedevops.firemud.accountservice.config.AccountJwtReadinessPodReceiverWorkloadGuard.AuthenticatedCaller;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding.Binding;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.OwnerProbeEvidence;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.ReadinessProbePlan;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.TrustFence;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessPodLocalIdentityProvider.LocalObservation;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessPodReceiverProtoMapper.ParsedRequest;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessReceiverInvocationPort.ReceiverUnavailableException;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource.ObservationContext;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier.VerifiedClaims;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;

/**
 * Account's actual per-Pod pending-key receiver. It verifies production JWT crypto and returns only
 * an immutable non-authorizing observation; it never calls the V1 receipt-writing harness.
 */
public final class AccountJwtReadinessPodReceiverEngine {
  private final AccountJwtReadinessProbeService probeService;
  private final AccountJwtReadinessPodReceiverWorkloadGuard workloadGuard;
  private final AccountJwtReadinessPodLocalIdentityProvider localIdentityProvider;
  private final AccountJwtReadinessPodReceiverProtoMapper protoMapper;
  private final AccountAsymmetricJwtVerifier verifier;
  private final Clock clock;
  private final int maxControlUiTenantScopes;

  public AccountJwtReadinessPodReceiverEngine(
      AccountJwtReadinessProbeService probeService,
      AccountJwtReadinessPodReceiverWorkloadGuard workloadGuard,
      AccountJwtReadinessPodLocalIdentityProvider localIdentityProvider,
      AccountJwtReadinessPodReceiverProtoMapper protoMapper,
      AccountAsymmetricJwtVerifier verifier,
      Clock clock,
      int maxControlUiTenantScopes) {
    this.probeService = Objects.requireNonNull(probeService);
    this.workloadGuard = Objects.requireNonNull(workloadGuard);
    this.localIdentityProvider = Objects.requireNonNull(localIdentityProvider);
    this.protoMapper = Objects.requireNonNull(protoMapper);
    this.verifier = Objects.requireNonNull(verifier);
    this.clock = Objects.requireNonNull(clock);
    if (maxControlUiTenantScopes <= 0
        || maxControlUiTenantScopes
            > GameSessionAccountDelegationProfile.MAX_AUTHORITY_TUPLE_BYTES) {
      throw new IllegalArgumentException("Account control-ui verifier scope bound is required");
    }
    this.maxControlUiTenantScopes = maxControlUiTenantScopes;
  }

  public ReceiveAccountJwtReadinessProbeResponse receiveReadinessProbe(
      ReceiveAccountJwtReadinessProbeRequest request) {
    AuthenticatedCaller caller = workloadGuard.requireAccountReceiverCaller();
    ParsedRequest parsed = protoMapper.parse(request);
    Binding accountBinding = caller.accountBinding();
    TrustFence trustFence = AccountJwtReadinessValidationService.trustFence(accountBinding);

    // The persisted plan supplies the matrix digest; this is a read only and does not create or
    // advance a plan, bootstrap state, a registry record, or any activation state.
    ReadinessProbePlan currentPlan =
        probeService.readCurrentPlan(
            accountBinding.accountBinding(), trustFence, parsed.operationId());
    requirePlanCoordinates(parsed, currentPlan);
    ObservationContext observationContext =
        probeService.readCurrentInventoryObservationContext(
            accountBinding.accountBinding(), trustFence, parsed.operationId());

    LocalObservation localBefore =
        localIdentityProvider.observe(currentPlan.applicabilityMatrixDigest(), observationContext);
    var selector = protoMapper.toOwnerSelector(parsed, localBefore.selectorIdentity());
    OwnerProbeEvidence ownerBefore =
        probeService.readCurrentProbeOwner(
            accountBinding.accountBinding(), trustFence, selector, accountBinding.namespace());

    // All Account profiles are applicable. Unsupported or mismatched audience/profile pairs are
    // rejected by the closed request mapper and production verifier; none becomes typed success.
    VerifiedClaims verified =
        AccountJwtReadinessProbeCrypto.verify(
            verifier,
            parsed.compactJwt(),
            AccountJwtReadinessProbeCrypto.expectedProbe(
                selector.probeKind(),
                selector.tokenProfile(),
                selector.audience(),
                selector.jti().toString(),
                ownerBefore.entry().targetKid(),
                ownerBefore.entry().plannedIssuedAtEpochSecond(),
                ownerBefore.entry().expiresAtEpochSecond()),
            maxControlUiTenantScopes);

    LocalObservation localAfter =
        localIdentityProvider.observe(currentPlan.applicabilityMatrixDigest(), observationContext);
    if (!localBefore.equals(localAfter)) {
      throw new ReceiverUnavailableException();
    }
    OwnerProbeEvidence ownerAfter =
        probeService.readCurrentProbeOwner(
            accountBinding.accountBinding(), trustFence, selector, accountBinding.namespace());
    if (!ownerBefore.equals(ownerAfter)) {
      throw new ReceiverUnavailableException();
    }
    workloadGuard.requireUnchanged(caller);

    long observedAt = nowEpochSecond();
    if (observedAt < ownerAfter.entry().plannedIssuedAtEpochSecond()
        || observedAt >= ownerAfter.entry().expiresAtEpochSecond()
        || !ownerAfter.entry().targetKid().equals(verified.keyId())) {
      throw new ReceiverUnavailableException();
    }
    return protoMapper.toResponse(parsed, verified.keyId(), localAfter, ownerAfter, observedAt);
  }

  private static void requirePlanCoordinates(ParsedRequest request, ReadinessProbePlan plan) {
    var coordinates = request.coordinates();
    if (plan == null
        || !plan.operationId().equals(request.operationId())
        || !plan.operationDigest().equals(coordinates.getOperationDigest())
        || !plan.planDigest().equals(coordinates.getPlanDigest())
        || plan.planVersion() != coordinates.getPlanVersion()
        || plan.expiresAtEpochSecond() != coordinates.getPlanExpiresAtEpochSeconds()
        || !plan.targetGeneration().equals(coordinates.getTargetGeneration())
        || !plan.targetKid().equals(coordinates.getTargetKid())
        || !plan.expectedFence().durableActive().equals(request.expectedActive())
        || !plan.expectedFence().publishedActive().equals(request.expectedActive())
        || !plan.validatorInventoryComplete()) {
      throw new ReceiverUnavailableException();
    }
  }

  private long nowEpochSecond() {
    long value = clock.instant().getEpochSecond();
    if (value <= 0L) {
      throw new ReceiverUnavailableException();
    }
    return value;
  }
}
