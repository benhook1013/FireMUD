package net.firedevops.firemud.common.publication;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence.Outcome;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence.Participant;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence.ReleaseContent;
import net.firedevops.firemud.common.world.PublishedRealmEntryPolicySetEvidenceTest;

/** Isolated stipulated operation and terminal values for World consumer transport tests. */
final class WorldPublicationTerminalTestFixtures {
  private static final String DIGEST = "sha256:" + "a".repeat(64);
  private static final AtomicLong NEXT_VERSION_STATE_EPOCH = new AtomicLong(5L);

  private WorldPublicationTerminalTestFixtures() {}

  static GameDesignPublicationOperationBinding operation() throws Exception {
    return PublishedRealmEntryPolicySetEvidenceTest.operation(
        NEXT_VERSION_STATE_EPOCH.getAndIncrement());
  }

  static GameDesignPublicationTerminalEvidence terminal(
      GameDesignPublicationOperationBinding operation, Outcome outcome) {
    if (outcome == Outcome.NO_PUBLICATION) {
      return new GameDesignPublicationTerminalEvidence(
          operation.canonicalBytes(), outcome, null, null);
    }

    var request = operation.world().request();
    var participants =
        AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder().stream()
            .map(
                owner ->
                    new Participant(
                        owner,
                        Long.toString(
                            operation
                                .account()
                                .input()
                                .selection()
                                .target()
                                .gameDesignVersionRowId()),
                        null,
                        request.appliedCommitId(),
                        owner.equals("WORLD_MANAGEMENT") ? request.contentDigest() : "c".repeat(64),
                        AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                            owner, AuthoredWorldReleaseAttestationEvidence.SELECTOR_SCHEMA_VERSION),
                        owner.equals("GAME_LOGIC") ? DIGEST : null,
                        null,
                        null))
            .toList();
    var release =
        new ReleaseContent(
            request.canonicalTenantId(),
            request.canonicalVersionId(),
            "bundle:exact",
            12,
            "v2",
            request.publishWorkflowId(),
            DIGEST,
            1,
            List.of(
                new AuthoredWorldReleaseAttestationEvidence.Artifact(
                    "asset-é",
                    "FILE",
                    "artifacts/sha256/" + "a".repeat(64),
                    DIGEST,
                    "text/plain",
                    1)),
            List.of("asset-é"),
            participants,
            List.of("LOOK"),
            "generation-test",
            operation.world());
    return new GameDesignPublicationTerminalEvidence(
        operation.canonicalBytes(),
        outcome,
        release,
        Math.addExact(request.versionStateEpoch(), 1L));
  }
}
