package net.firedevops.firemud.gamesession.service;

import java.time.Clock;
import java.util.Objects;
import net.firedevops.firemud.account.v1.GetCurrentReadinessProbeOwnerResponse;
import net.firedevops.firemud.account.v1.ReadinessReceiverLocalIdentity;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier;
import net.firedevops.firemud.gamesession.config.GameSessionJwtReadinessProbeOwnerWorkloadGuard;
import net.firedevops.firemud.gamesession.config.GameSessionJwtReadinessProbeOwnerWorkloadGuard.AuthenticatedCaller;
import net.firedevops.firemud.gamesession.service.GameSessionJwtReadinessLocalIdentityProvider.LocalObservation;
import net.firedevops.firemud.gamesession.service.GameSessionJwtReadinessReceiverProtoMapper.OwnerEvidence;
import net.firedevops.firemud.gamesession.service.GameSessionJwtReadinessReceiverProtoMapper.ParsedRequest;
import net.firedevops.firemud.gamesession.v1.ReceiveReadinessProbeRequest;
import net.firedevops.firemud.gamesession.v1.ReceiveReadinessProbeResponse;

/** Exact Account owner-read, production verification, and immutable Pod readback sequence. */
public final class GameSessionJwtReadinessReceiverEngine {
  public static final String FULL_METHOD_NAME =
      "game_session.v1.GameSessionJwtReadinessReceiverService/ReceiveReadinessProbe";

  private final GameSessionJwtReadinessProbeOwnerWorkloadGuard workloadGuard;
  private final GameSessionJwtReadinessLocalIdentityProvider localIdentityProvider;
  private final GameSessionJwtReadinessProbeOwnerReadPort ownerReadPort;
  private final GameSessionJwtReadinessReceiverProtoMapper protoMapper;
  private final GameSessionJwtReadinessProbeCrypto crypto;
  private final Clock clock;

  public GameSessionJwtReadinessReceiverEngine(
      GameSessionJwtReadinessProbeOwnerWorkloadGuard workloadGuard,
      GameSessionJwtReadinessLocalIdentityProvider localIdentityProvider,
      GameSessionJwtReadinessProbeOwnerReadPort ownerReadPort,
      GameSessionJwtReadinessReceiverProtoMapper protoMapper,
      GameSessionJwtReadinessProbeCrypto crypto,
      Clock clock) {
    this.workloadGuard = Objects.requireNonNull(workloadGuard);
    this.localIdentityProvider = Objects.requireNonNull(localIdentityProvider);
    this.ownerReadPort = Objects.requireNonNull(ownerReadPort);
    this.protoMapper = Objects.requireNonNull(protoMapper);
    this.crypto = Objects.requireNonNull(crypto);
    this.clock = Objects.requireNonNull(clock);
  }

  public ReceiveReadinessProbeResponse receiveReadinessProbe(ReceiveReadinessProbeRequest request) {
    AuthenticatedCaller caller = workloadGuard.requireAccountReceiverCaller(FULL_METHOD_NAME);
    ParsedRequest parsed = protoMapper.parse(request);

    // The compact token is hashed but not decoded or verified until the exact Account owner read.
    LocalObservation localBefore = observeLocalIdentity(caller.peer().namespace());
    ReadinessReceiverLocalIdentity protectedIdentity = localBefore.wireIdentity();
    var ownerRequest = protoMapper.ownerReadRequest(parsed, protectedIdentity);
    GetCurrentReadinessProbeOwnerResponse ownerResponseBefore =
        ownerReadPort.readCurrent(ownerRequest);
    OwnerEvidence ownerBefore =
        protoMapper.requireCurrentOwner(parsed, protectedIdentity, ownerResponseBefore);

    GameSessionJwtReadinessProbeCrypto.VerifiedProbe verified = crypto.verify(parsed);
    ReceiveReadinessProbeResponse.ObservationOutcome expectedObservation =
        parsed.expectedOutcome()
                == net.firedevops.firemud.account.v1.ReadinessProbeCoordinates.ExpectedOutcome
                    .ACCEPT
            ? ReceiveReadinessProbeResponse.ObservationOutcome.VERIFIED
            : ReceiveReadinessProbeResponse.ObservationOutcome.INAPPLICABLE_REJECT;
    if (verified.outcome() != expectedObservation) {
      throw new AccountAsymmetricJwtVerifier.VerificationException();
    }

    LocalObservation localAfter = observeLocalIdentity(caller.peer().namespace());
    GetCurrentReadinessProbeOwnerResponse ownerResponseAfter =
        ownerReadPort.readCurrent(ownerRequest);
    OwnerEvidence ownerAfter =
        protoMapper.requireCurrentOwner(parsed, localAfter.wireIdentity(), ownerResponseAfter);
    if (!localBefore.equals(localAfter)
        || !ownerResponseBefore.equals(ownerResponseAfter)
        || !ownerBefore.equals(ownerAfter)) {
      throw new OwnerReadbackChangedException();
    }
    workloadGuard.requireUnchanged(caller, FULL_METHOD_NAME);

    long observedAt = nowEpochSecond();
    return protoMapper.response(
        parsed,
        verified.keyId(),
        localAfter.wireIdentity(),
        ownerAfter,
        observedAt,
        verified.outcome());
  }

  private LocalObservation observeLocalIdentity(String authenticatedNamespace) {
    try {
      LocalObservation observation = Objects.requireNonNull(localIdentityProvider.observe());
      ReadinessReceiverLocalIdentity identity = observation.wireIdentity();
      if (!GameSessionJwtReadinessReceiverProtoMapper.GAME_SESSION_VALIDATOR.equals(
              identity.getValidatorId())
          || !identity.getAccountJwksSourceIdentity().getNamespace().equals(authenticatedNamespace)
          || !identity
              .getCanonicalServiceUri()
              .equals(
                  "spiffe://firemud/ns/"
                      + identity.getAccountJwksSourceIdentity().getNamespace()
                      + "/sa/game-session-service")) {
        throw new IllegalStateException();
      }
      return observation;
    } catch (RuntimeException unavailable) {
      throw new LocalIdentityUnavailableException();
    }
  }

  private long nowEpochSecond() {
    long now = clock.instant().getEpochSecond();
    return now > 0L ? now : throwUnavailable();
  }

  private static long throwUnavailable() {
    throw new LocalIdentityUnavailableException();
  }

  public static final class OwnerReadbackChangedException extends RuntimeException {
    public OwnerReadbackChangedException() {
      super("Current readiness owner evidence changed during verification");
    }
  }

  public static final class LocalIdentityUnavailableException extends RuntimeException {
    public LocalIdentityUnavailableException() {
      super("Protected Game Session readiness identity is unavailable");
    }
  }
}
