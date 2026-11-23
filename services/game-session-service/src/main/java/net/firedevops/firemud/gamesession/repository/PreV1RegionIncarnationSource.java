package net.firedevops.firemud.gamesession.repository;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Pre-v1 gate waiver (ADR 0185): stable region incarnation evidence for the Coordination Redis,
 * derived from the live Redis {@code run_id} on every read. The non-reset profile, inactive
 * recovery and no-unfinished-work qualifications are asserted, not observed; their operational
 * sources are owed before v1.
 */
final class PreV1RegionIncarnationSource implements CanonicalGameplayRegionIncarnationSource {
  static final String NON_RESET_PROFILE_ID = "pre-v1-compose-non-reset-profile-v1";

  private final Supplier<String> redisRunId;

  PreV1RegionIncarnationSource(Supplier<String> redisRunId) {
    this.redisRunId = Objects.requireNonNull(redisRunId, "redisRunId");
  }

  @Override
  public StableEvidence readCurrent(RegionScope scope) {
    Objects.requireNonNull(scope, "scope");
    var incarnation = PreV1CoordinationIncarnation.forRedisRunId(redisRunId.get());
    String runId = incarnation.redisRunId();
    return StableEvidence.fromCurrentSource(
        scope,
        PreV1CoordinationIncarnation.ATTESTOR_BINDING,
        PreV1CoordinationIncarnation.bareHex(incarnation.acceptedAttestationDigest()),
        incarnation.deployment(),
        PreV1CoordinationIncarnation.bareHex(incarnation.coordOpsRoAclDigest()),
        PreV1CoordinationIncarnation.bareHex(incarnation.coordRedisConfigObservedDigest()),
        true,
        NON_RESET_PROFILE_ID,
        waived("non-reset-profile", runId),
        true,
        waived("recovery-inactive", runId),
        true,
        waived("no-unfinished-prior-work", runId));
  }

  private static String waived(String label, String runId) {
    return PreV1CoordinationIncarnation.bareHex(
        PreV1CoordinationIncarnation.sha256("region-" + label, runId));
  }
}
