package net.firedevops.firemud.accountservice.service.session;

import java.util.Objects;
import net.firedevops.firemud.account.v1.GetCurrentReadinessProbeOwnerRequest;
import net.firedevops.firemud.account.v1.GetCurrentReadinessProbeOwnerResponse;
import net.firedevops.firemud.accountservice.config.AccountJwtReadinessProbeOwnerWorkloadGuard;
import net.firedevops.firemud.accountservice.config.AccountJwtReadinessProbeOwnerWorkloadGuard.AuthenticatedCaller;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.TrustFence;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessReceiverInvocationPort.ReceiverUnavailableException;

/** Default-inactive read-only boundary for one exact current Game Session readiness probe. */
public final class AccountJwtReadinessProbeOwnerService {
  private static final String GAME_SESSION_VALIDATOR = "game-session-service";

  private final AccountJwtReadinessProbeService probeService;
  private final AccountJwtSignerMaterializerTrustBinding materializerTrustBinding;
  private final AccountJwtReadinessProbeOwnerWorkloadGuard workloadGuard;
  private final AccountJwtReadinessProbeOwnerProtoMapper protoMapper;

  public AccountJwtReadinessProbeOwnerService(
      AccountJwtReadinessProbeService probeService,
      AccountJwtSignerMaterializerTrustBinding materializerTrustBinding,
      AccountJwtReadinessProbeOwnerWorkloadGuard workloadGuard,
      AccountJwtReadinessProbeOwnerProtoMapper protoMapper) {
    this.probeService = Objects.requireNonNull(probeService);
    this.materializerTrustBinding = Objects.requireNonNull(materializerTrustBinding);
    this.workloadGuard = Objects.requireNonNull(workloadGuard);
    this.protoMapper = Objects.requireNonNull(protoMapper);
  }

  public GetCurrentReadinessProbeOwnerResponse getCurrentReadinessProbeOwner(
      GetCurrentReadinessProbeOwnerRequest request) {
    AuthenticatedCaller caller = workloadGuard.requireGameSessionOwnerReadCaller();
    AccountJwtReadinessProbeOwnerSelector selector = protoMapper.parse(request);
    if (!GAME_SESSION_VALIDATOR.equals(selector.validatorId())) {
      throw new AccountJwtReadinessProbeOwnerWorkloadGuard.OwnerReadDeniedException();
    }
    var protectedAccountBinding = caller.accountBinding();
    TrustFence trustFence =
        AccountJwtReadinessValidationService.trustFence(protectedAccountBinding);
    var evidence =
        probeService.readCurrentProbeOwner(
            protectedAccountBinding.accountBinding(),
            trustFence,
            selector,
            protectedAccountBinding.namespace());

    // Do not return an observation tied to a protected binding that changed during the read.
    var currentCaller = workloadGuard.requireGameSessionOwnerReadCaller();
    var latestBinding =
        materializerTrustBinding.current().orElseThrow(ReceiverUnavailableException::new);
    if (!protectedAccountBinding.equals(currentCaller.accountBinding())
        || !protectedAccountBinding.equals(latestBinding)) {
      throw new ReceiverUnavailableException();
    }
    if (!evidence.expectedPod().target().namespace().equals(protectedAccountBinding.namespace())) {
      throw new ReceiverUnavailableException();
    }
    return protoMapper.toResponse(evidence);
  }
}
