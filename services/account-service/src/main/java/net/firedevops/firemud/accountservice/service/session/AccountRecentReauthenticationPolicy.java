package net.firedevops.firemud.accountservice.service.session;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;

/** Account-owned versioned platform/deployment policy for tenantless game creation reauth. */
public final class AccountRecentReauthenticationPolicy {
  public static final String PURPOSE = "SELF_SERVICE_GAME_CREATION";
  public static final long PLATFORM_RECENT_AGE_MAX_SECONDS = 300L;
  public static final long PLATFORM_CLOCK_SKEW_MAX_SECONDS = 30L;

  private AccountRecentReauthenticationPolicy() {}

  public static Effective resolve(PlatformValue platform, DeploymentValue deployment) {
    Objects.requireNonNull(platform, "Current platform policy is required");
    Objects.requireNonNull(deployment, "Current deployment policy is required");
    requireBounded(platform.version(), platform.recentAgeSeconds(), platform.maxClockSkewSeconds());
    requireBounded(
        deployment.version(), deployment.recentAgeSeconds(), deployment.maxClockSkewSeconds());
    if (deployment.recentAgeSeconds() > platform.recentAgeSeconds()
        || deployment.maxClockSkewSeconds() > platform.maxClockSkewSeconds()) {
      throw unavailable();
    }
    long effectiveAge = Math.min(platform.recentAgeSeconds(), deployment.recentAgeSeconds());
    long effectiveSkew = Math.min(platform.maxClockSkewSeconds(), deployment.maxClockSkewSeconds());
    if (effectiveSkew >= effectiveAge) {
      throw unavailable();
    }
    String version =
        "sha256:"
            + HexFormat.of()
                .formatHex(
                    sha256(
                        canonicalPolicy(platform, deployment, effectiveAge, effectiveSkew)
                            .getBytes(StandardCharsets.UTF_8)));
    return new Effective(
        platform.version(), deployment.version(), effectiveAge, effectiveSkew, version);
  }

  static List<SourceEvidence> sourceEvidence(PlatformValue platform, DeploymentValue deployment) {
    Objects.requireNonNull(platform, "Current platform policy is required");
    Objects.requireNonNull(deployment, "Current deployment policy is required");
    return List.of(
            sourceEvidence(
                "PLATFORM",
                platform.version(),
                platform.recentAgeSeconds(),
                platform.maxClockSkewSeconds()),
            sourceEvidence(
                "DEPLOYMENT",
                deployment.version(),
                deployment.recentAgeSeconds(),
                deployment.maxClockSkewSeconds()))
        .stream()
        .sorted(Comparator.comparing(SourceEvidence::key))
        .toList();
  }

  private static SourceEvidence sourceEvidence(
      String policyScope, long version, long recentAgeSeconds, long maxClockSkewSeconds) {
    String scope = PURPOSE + "/" + policyScope;
    String value =
        "account-recent-reauthentication-policy-source/v1\n"
            + "owner=ACCOUNT\n"
            + "purpose="
            + PURPOSE
            + "\nscope="
            + scope
            + "\nversion="
            + version
            + "\nrecentAgeSeconds="
            + recentAgeSeconds
            + "\nmaxClockSkewSeconds="
            + maxClockSkewSeconds
            + "\n";
    return new SourceEvidence(
        SourceKind.RECENT_REAUTH_POLICY,
        scope,
        null,
        Long.toString(version),
        null,
        null,
        value.getBytes(StandardCharsets.UTF_8));
  }

  private static void requireBounded(long version, long ageSeconds, long skewSeconds) {
    if (version <= 0L
        || ageSeconds <= 0L
        || ageSeconds > PLATFORM_RECENT_AGE_MAX_SECONDS
        || skewSeconds < 0L
        || skewSeconds > PLATFORM_CLOCK_SKEW_MAX_SECONDS
        || skewSeconds >= ageSeconds) {
      throw unavailable();
    }
  }

  private static String canonicalPolicy(
      PlatformValue platform,
      DeploymentValue deployment,
      long effectiveAgeSeconds,
      long effectiveSkewSeconds) {
    return "account-recent-reauthentication-policy/v1\n"
        + "purpose="
        + PURPOSE
        + "\nplatformVersion="
        + platform.version()
        + "\nplatformRecentAgeSeconds="
        + platform.recentAgeSeconds()
        + "\nplatformMaxClockSkewSeconds="
        + platform.maxClockSkewSeconds()
        + "\ndeploymentVersion="
        + deployment.version()
        + "\ndeploymentRecentAgeSeconds="
        + deployment.recentAgeSeconds()
        + "\ndeploymentMaxClockSkewSeconds="
        + deployment.maxClockSkewSeconds()
        + "\neffectiveRecentAgeSeconds="
        + effectiveAgeSeconds
        + "\neffectiveMaxClockSkewSeconds="
        + effectiveSkewSeconds
        + "\n";
  }

  private static byte[] sha256(byte[] input) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(input);
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException(
          "SHA-256 is unavailable for Account security policy", unavailable);
    }
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Current Account reauthentication policy is unavailable");
  }

  /** Immutable policy-source value read from the exact current platform policy row. */
  public record PlatformValue(long version, long recentAgeSeconds, long maxClockSkewSeconds) {}

  /** Immutable policy-source value read from the exact current deployment policy row. */
  public record DeploymentValue(long version, long recentAgeSeconds, long maxClockSkewSeconds) {}

  /**
   * Resolved exact values; constructed only by {@link #resolve(PlatformValue, DeploymentValue)}.
   */
  public static final class Effective {
    private final long platformVersion;
    private final long deploymentVersion;
    private final long recentAgeSeconds;
    private final long maxClockSkewSeconds;
    private final String effectivePolicyVersion;

    private Effective(
        long platformVersion,
        long deploymentVersion,
        long recentAgeSeconds,
        long maxClockSkewSeconds,
        String effectivePolicyVersion) {
      this.platformVersion = platformVersion;
      this.deploymentVersion = deploymentVersion;
      this.recentAgeSeconds = recentAgeSeconds;
      this.maxClockSkewSeconds = maxClockSkewSeconds;
      this.effectivePolicyVersion = Objects.requireNonNull(effectivePolicyVersion);
    }

    public long platformVersion() {
      return platformVersion;
    }

    public long deploymentVersion() {
      return deploymentVersion;
    }

    public long recentAgeSeconds() {
      return recentAgeSeconds;
    }

    public long maxClockSkewSeconds() {
      return maxClockSkewSeconds;
    }

    public String effectivePolicyVersion() {
      return effectivePolicyVersion;
    }

    /** ADR 0028 conservative-age/deadline predicate for this exact effective policy. */
    public boolean isRecent(Instant evaluatedAt, Instant localNow) {
      if (evaluatedAt == null || localNow == null || maxClockSkewSeconds >= recentAgeSeconds) {
        return false;
      }
      try {
        if (evaluatedAt.isAfter(localNow.plusSeconds(maxClockSkewSeconds))) {
          return false;
        }
        Instant deadline =
            evaluatedAt.plusSeconds(recentAgeSeconds).minusSeconds(maxClockSkewSeconds);
        return localNow.isBefore(deadline);
      } catch (ArithmeticException | DateTimeException invalidTimestamp) {
        return false;
      }
    }

    @Override
    public String toString() {
      return "AccountRecentReauthenticationPolicy.Effective[redacted]";
    }
  }
}
