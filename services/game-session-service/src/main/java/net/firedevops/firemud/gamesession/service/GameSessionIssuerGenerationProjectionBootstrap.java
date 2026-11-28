package net.firedevops.firemud.gamesession.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.firedevops.firemud.common.account.authority.AccountAuthoritySourceEventV1Codec;
import net.firedevops.firemud.common.account.authority.AccountIssuerAuthoritySourceClient;
import net.firedevops.firemud.common.account.authority.AccountIssuerSourceSnapshotEvidence;
import net.firedevops.firemud.common.account.authority.AccountIssuerSourceSnapshotGrpcCodec.ReadRequest;

/** Prepares a bootstrap candidate from a current Account issuer-source read. */
public final class GameSessionIssuerGenerationProjectionBootstrap {
  private static final String SCHEMA_VERSION = "game-session-auth-issuer-projection/v1";

  private final AccountIssuerAuthoritySourceClient sourceClient;

  public GameSessionIssuerGenerationProjectionBootstrap(
      AccountIssuerAuthoritySourceClient sourceClient) {
    this.sourceClient =
        Objects.requireNonNull(sourceClient, "Account issuer-source client is required");
  }

  /** A request with expected source evidence revalidates that exact checkpoint through Account. */
  public InstallationCandidate prepare(ReadRequest request) {
    AccountIssuerSourceSnapshotEvidence source = sourceClient.read(request);
    Map<String, Object> checkpoint = new HashMap<>();
    checkpoint.put("schemaVersion", SCHEMA_VERSION);
    checkpoint.put("issuerId", source.issuerId());
    checkpoint.put("lastAppliedIssuerGeneration", source.issuerAuthGeneration());
    checkpoint.put("sourceVersion", source.sourceVersion());
    checkpoint.put("outboxStreamKey", source.outboxStreamKey());
    checkpoint.put("lastAppliedSourceOutboxSequence", source.lastCommittedOutboxSequence());
    checkpoint.put("reconciliationOperationId", source.reconciliationOperationId().toString());

    List<Map<String, String>> appliedSourceEvidence = List.of();
    if (source.sourceEvent().isPresent()) {
      var decoded = AccountAuthoritySourceEventV1Codec.verify(source.sourceEvent().orElseThrow());
      if (!(decoded instanceof AccountAuthoritySourceEventV1Codec.IssuerEvent event)) {
        throw new IllegalStateException("Account checkpoint did not contain an issuer event");
      }
      checkpoint.put("lastAppliedSourceEventId", event.eventId());
      checkpoint.put("lastAppliedSourceEventDigest", event.eventDigest());
      appliedSourceEvidence =
          List.of(
              Map.of(
                  "outboxStreamKey", event.outboxStreamKey(),
                  "outboxSequence", event.outboxSequence(),
                  "eventId", event.eventId(),
                  "eventDigest", event.eventDigest()));
    }
    checkpoint.put("appliedSourceEvidence", appliedSourceEvidence);
    return new InstallationCandidate(source, checkpoint);
  }

  /** Immutable source and projection checkpoint for a later fenced installation and readback. */
  public static final class InstallationCandidate {
    private final AccountIssuerSourceSnapshotEvidence sourceCheckpoint;
    private final Map<String, Object> projectionCheckpoint;

    private InstallationCandidate(
        AccountIssuerSourceSnapshotEvidence sourceCheckpoint,
        Map<String, Object> projectionCheckpoint) {
      this.sourceCheckpoint = sourceCheckpoint;
      this.projectionCheckpoint = Map.copyOf(projectionCheckpoint);
    }

    public AccountIssuerSourceSnapshotEvidence sourceCheckpoint() {
      return sourceCheckpoint;
    }

    /** Returns the immutable checkpoint made from strings and immutable nested evidence. */
    public Map<String, Object> projectionCheckpoint() {
      return projectionCheckpoint;
    }
  }
}
