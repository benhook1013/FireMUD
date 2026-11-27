package net.firedevops.firemud.gamesession.repository;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.Supplier;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;

/**
 * Pre-v1 gate waiver (ADR 0185): the current Account issuer checkpoint and Coordination incarnation
 * for the canonical gameplay owners.
 *
 * <p>Game Session has no read of Account's issuer authority source. The checkpoint is the
 * sequence-zero initial generation 1, which carries no source event: Account initializes the issuer
 * scope at generation 1 and has no reachable path that advances it ({@code
 * AccountAuthoritySourceEvidenceRepository.appendIssuerAuthorityChange} has no caller). A later
 * generation needs the sealed Account source event and is not invented here; owners then fail
 * closed on the generation mismatch. The incarnation is re-derived from the live Redis {@code
 * run_id} on every read.
 */
final class PreV1CurrentIssuerStateSource
    implements CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerStateSource {
  static final String ISSUER = GameSessionAccountDelegationProfile.ISSUER;
  static final BigInteger INITIAL_GENERATION = BigInteger.ONE;

  private final Supplier<String> redisRunId;
  private final byte[] checkpointBytes =
      ("pre-v1-initial-issuer-checkpoint/v1|" + ISSUER + "|" + INITIAL_GENERATION)
          .getBytes(StandardCharsets.UTF_8);

  PreV1CurrentIssuerStateSource(Supplier<String> redisRunId) {
    this.redisRunId = Objects.requireNonNull(redisRunId, "redisRunId");
  }

  CanonicalGameplayIssuerLayoutBootstrapRepository.TrustedAccountIssuerCheckpoint
      trustedCheckpoint() {
    return new CanonicalGameplayIssuerLayoutBootstrapRepository.TrustedAccountIssuerCheckpoint(
        ISSUER, INITIAL_GENERATION, checkpointBytes, null);
  }

  CanonicalGameplayIssuerLayoutBootstrapIntent.AccountIssuerSourceCheckpoint checkpoint() {
    return new CanonicalGameplayIssuerLayoutBootstrapIntent.AccountIssuerSourceCheckpoint(
        ISSUER, INITIAL_GENERATION, checkpointBytes, sha256(checkpointBytes), null, null);
  }

  @Override
  public CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState
      readFreshCurrentState() {
    return new CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState(
        checkpoint(),
        PreV1CoordinationIncarnation.forRedisRunId(redisRunId.get()).issuerIncarnation());
  }

  private static String sha256(byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("JVM does not provide SHA-256", impossible);
    }
  }
}
