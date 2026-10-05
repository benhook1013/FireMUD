package net.firedevops.firemud.gamesession.service;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.account.authority.IssuerProjectionReconciliationRequestDigestV1;
import net.firedevops.firemud.gamesession.client.AccountIssuerAuthorityClient;
import net.firedevops.firemud.gamesession.client.AccountIssuerAuthorityClient.ProjectionCaptureReceipt;
import net.firedevops.firemud.gamesession.client.AccountIssuerAuthorityClient.SourceReadback;
import net.firedevops.firemud.gamesession.client.AccountIssuerAuthorityClient.SourceSnapshot;
import net.firedevops.firemud.gamesession.service.RedisIssuerAuthorityProjectionStore.ApplyResult;
import net.firedevops.firemud.gamesession.service.RedisIssuerAuthorityProjectionStore.ProjectionSnapshot;

/** Explicit, unwired local capture-to-projection reconciliation operation for Game Session. */
public final class IssuerProjectionReconciliationInstaller {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private final AccountIssuerAuthorityClient accountClient;
  private final RedisIssuerAuthorityProjectionStore projectionStore;

  public IssuerProjectionReconciliationInstaller(
      AccountIssuerAuthorityClient accountClient,
      RedisIssuerAuthorityProjectionStore projectionStore) {
    this.accountClient = Objects.requireNonNull(accountClient, "Account issuer client is required");
    this.projectionStore =
        Objects.requireNonNull(projectionStore, "issuer projection store is required");
  }

  /**
   * Captures once, proves that capture is still current before local installation, and verifies
   * current Account authority again after the registered Redis store returns an exact readback.
   */
  public Result install(String requestId, String appliedAt) {
    if (!isCanonicalNonNilUuid(requestId)) {
      return result(Outcome.QUARANTINED, "INVALID_REQUEST_ID");
    }

    ProjectionCaptureReceipt capture = accountClient.captureProjection(requestId);
    SourceReadback currentBefore = accountClient.readCurrent(requestId);
    if (!currentMatchesCapture(capture, currentBefore)) {
      return result(Outcome.STALE_SOURCE, "CAPTURE_NOT_CURRENT_BEFORE_INSTALLATION");
    }

    ApplyResult installed = projectionStore.installCapture(capture, currentBefore, appliedAt);
    RedisIssuerAuthorityProjectionStore.Outcome storeOutcome = installed.outcome();
    if (storeOutcome == RedisIssuerAuthorityProjectionStore.Outcome.STALE) {
      return result(Outcome.QUARANTINED, installed.detail().orElse("STORE_CAS_STALE"));
    }
    if (storeOutcome == RedisIssuerAuthorityProjectionStore.Outcome.QUARANTINED) {
      return result(Outcome.QUARANTINED, installed.detail().orElse("STORE_QUARANTINED"));
    }
    if (storeOutcome != RedisIssuerAuthorityProjectionStore.Outcome.APPLIED
        && storeOutcome != RedisIssuerAuthorityProjectionStore.Outcome.REPLAYED
        && storeOutcome != RedisIssuerAuthorityProjectionStore.Outcome.NO_OP) {
      return result(Outcome.QUARANTINED, "STORE_RETURNED_UNSUPPORTED_OUTCOME");
    }

    ProjectionSnapshot projection = installed.snapshot().orElse(null);
    if (projection == null
        || !IssuerAuthorityProjectionRedisContract.keyForIssuer(capture.issuerId())
            .equals(projection.key())) {
      return result(Outcome.QUARANTINED, "STORE_READBACK_BINDING_MISSING");
    }

    SourceReadback currentAfter = accountClient.readCurrent(requestId);
    if (!currentMatchesCapture(capture, currentAfter)) {
      return result(Outcome.STALE_SOURCE, "CAPTURE_NOT_CURRENT_AFTER_INSTALLATION");
    }
    if (!projectionStore.verifiesCaptureSnapshot(capture, currentAfter, projection)) {
      return result(Outcome.QUARANTINED, "STORE_SNAPSHOT_DOES_NOT_MATCH_CAPTURE");
    }

    Outcome outcome =
        storeOutcome == RedisIssuerAuthorityProjectionStore.Outcome.APPLIED
            ? Outcome.INSTALLED
            : Outcome.REPLAYED;
    return new Result(
        outcome,
        Optional.of(
            new InstallationReceipt(
                capture.operationUUID(),
                capture.requestUUID(),
                capture.requestDigest(),
                capture.capturedSource(),
                projection)),
        Optional.empty());
  }

  private static boolean currentMatchesCapture(
      ProjectionCaptureReceipt capture, SourceReadback readback) {
    if (capture == null
        || readback == null
        || capture.requestUUID() == null
        || !capture.requestUUID().toString().equals(readback.requestId())
        || readback.targetNamespace() == null
        || readback.targetNamespace().isBlank()
        || readback.sourceSnapshot() == null
        || readback.requestedEvent().isPresent()
        || capture.operationUUID() == null
        || capture.requestDigest() == null
        || capture.requestDigestVersion() != IssuerProjectionReconciliationRequestDigestV1.VERSION
        || capture.capturedSource() == null) {
      return false;
    }
    String expectedCaller =
        "spiffe://firemud/ns/" + readback.targetNamespace() + "/sa/game-session-service";
    final String expectedProjectionKey;
    try {
      expectedProjectionKey =
          IssuerAuthorityProjectionRedisContract.keyForIssuer(capture.issuerId());
    } catch (IllegalArgumentException malformedIssuer) {
      return false;
    }
    if (!expectedCaller.equals(capture.callerWorkloadIdentity())
        || !expectedProjectionKey.equals(capture.projectionKey())
        || !Objects.equals(capture.issuerId(), capture.capturedSource().issuerId())
        || !IssuerProjectionReconciliationRequestDigestV1.digest(
                capture.issuerId(),
                capture.callerWorkloadIdentity(),
                capture.projectionKey(),
                capture.requestUUID())
            .equals(capture.requestDigest())) {
      return false;
    }
    return sameSource(capture.capturedSource(), readback.sourceSnapshot());
  }

  private static boolean sameSource(SourceSnapshot captured, SourceSnapshot current) {
    return Objects.equals(captured.issuerId(), current.issuerId())
        && Objects.equals(captured.sourceScope(), current.sourceScope())
        && Objects.equals(captured.outboxStreamKey(), current.outboxStreamKey())
        && Objects.equals(captured.issuerAuthGeneration(), current.issuerAuthGeneration())
        && Objects.equals(captured.sourceVersion(), current.sourceVersion())
        && Objects.equals(captured.outboxSequence(), current.outboxSequence())
        && sameEvent(
            captured.latestEvent().map(event -> event.canonicalJson()).orElse(null),
            current.latestEvent().map(event -> event.canonicalJson()).orElse(null));
  }

  private static boolean sameEvent(String captured, String current) {
    return Objects.equals(captured, current);
  }

  private static boolean isCanonicalNonNilUuid(String value) {
    if (value == null) {
      return false;
    }
    try {
      UUID parsed = UUID.fromString(value);
      return !NIL_UUID.equals(parsed) && parsed.toString().equals(value);
    } catch (IllegalArgumentException malformed) {
      return false;
    }
  }

  private static Result result(Outcome outcome, String detail) {
    return new Result(outcome, Optional.empty(), Optional.of(detail));
  }

  public enum Outcome {
    INSTALLED,
    REPLAYED,
    STALE_SOURCE,
    QUARANTINED
  }

  public record Result(
      Outcome outcome, Optional<InstallationReceipt> receipt, Optional<String> detail) {
    public Result {
      Objects.requireNonNull(outcome, "installation outcome is required");
      receipt = Objects.requireNonNull(receipt, "installation receipt optional is required");
      detail = Objects.requireNonNull(detail, "installation detail optional is required");
      if ((outcome == Outcome.INSTALLED || outcome == Outcome.REPLAYED) != receipt.isPresent()) {
        throw new IllegalArgumentException("only verified local installations carry a receipt");
      }
    }
  }

  /** Historical local proof only; construction is restricted to this installer. */
  public static final class InstallationReceipt {
    private final UUID operationId;
    private final UUID requestId;
    private final String requestDigest;
    private final SourceSnapshot capturedSource;
    private final ProjectionSnapshot projectionSnapshot;

    private InstallationReceipt(
        UUID operationId,
        UUID requestId,
        String requestDigest,
        SourceSnapshot capturedSource,
        ProjectionSnapshot projectionSnapshot) {
      this.operationId = Objects.requireNonNull(operationId, "capture operation ID is required");
      this.requestId = Objects.requireNonNull(requestId, "capture request ID is required");
      this.requestDigest = Objects.requireNonNull(requestDigest, "request digest is required");
      this.capturedSource = Objects.requireNonNull(capturedSource, "captured source is required");
      this.projectionSnapshot =
          Objects.requireNonNull(projectionSnapshot, "projection snapshot is required");
    }

    public UUID operationId() {
      return operationId;
    }

    public UUID requestId() {
      return requestId;
    }

    public String requestDigest() {
      return requestDigest;
    }

    public SourceSnapshot capturedSource() {
      return capturedSource;
    }

    public ProjectionSnapshot projectionSnapshot() {
      return projectionSnapshot;
    }
  }
}
