package net.firedevops.firemud.gamesession.data;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamedesign.v1.LaunchDescriptor;
import net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle;
import net.firedevops.firemud.gamedesign.v1.VersionAssetArtifactState;
import net.firedevops.firemud.gamedesign.v1.VersionStateSnapshot;

/** Verified immutable Game Design projections for the run-owned initial-admission fixture only. */
public final class RunOwnedInitialAdmissionLaunchEvidence {
  public static final String SCHEMA = "firemud.run-owned-v1-launch-read-evidence.v1";

  private final AuthoredWorldSourceEvidence sourceEvidence;
  private final String schema;
  private final long gameTemplateId;
  private final PublishedReleaseBundle publishedReleaseBundle;
  private final VersionAssetArtifactState versionAssetArtifactState;
  private final VersionStateSnapshot versionState;
  private final LaunchDescriptor launchDescriptor;

  RunOwnedInitialAdmissionLaunchEvidence(
      AuthoredWorldSourceEvidence sourceEvidence,
      String schema,
      long gameTemplateId,
      PublishedReleaseBundle publishedReleaseBundle,
      VersionAssetArtifactState versionAssetArtifactState,
      VersionStateSnapshot versionState,
      LaunchDescriptor launchDescriptor) {
    this.sourceEvidence = Objects.requireNonNull(sourceEvidence, "sourceEvidence");
    this.schema = Objects.requireNonNull(schema, "schema");
    if (gameTemplateId <= 0L) {
      throw new IllegalArgumentException("gameTemplateId must be positive");
    }
    this.gameTemplateId = gameTemplateId;
    this.publishedReleaseBundle =
        Objects.requireNonNull(publishedReleaseBundle, "publishedReleaseBundle");
    this.versionAssetArtifactState =
        Objects.requireNonNull(versionAssetArtifactState, "versionAssetArtifactState");
    this.versionState = Objects.requireNonNull(versionState, "versionState");
    this.launchDescriptor = Objects.requireNonNull(launchDescriptor, "launchDescriptor");
  }

  static RunOwnedInitialAdmissionLaunchEvidence fromVerifiedSourceReceipt(
      RunOwnedInitialAdmissionFixtureCapability capability,
      RunOwnedInitialAdmissionFixtureCapability.SourceReceipt receipt) {
    RunOwnedInitialAdmissionFixtureCoordinator.requireExactSourceReceipt(capability, receipt);
    RunOwnedInitialAdmissionFixtureCapability.LaunchReadEvidence readEvidence =
        receipt.launchReadEvidence();
    return new RunOwnedInitialAdmissionLaunchEvidence(
        receipt.sourceEvidence(),
        readEvidence.schema(),
        readEvidence.gameTemplateId(),
        readEvidence.publishedReleaseBundle(),
        readEvidence.versionAssetArtifactState(),
        readEvidence.versionState(),
        readEvidence.launchDescriptor());
  }

  public AuthoredWorldSourceEvidence sourceEvidence() {
    return sourceEvidence;
  }

  public String schema() {
    return schema;
  }

  public long gameTemplateId() {
    return gameTemplateId;
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "Generated protobuf messages are immutable snapshots.")
  public PublishedReleaseBundle publishedReleaseBundle() {
    return publishedReleaseBundle;
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "Generated protobuf messages are immutable snapshots.")
  public VersionAssetArtifactState versionAssetArtifactState() {
    return versionAssetArtifactState;
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "Generated protobuf messages are immutable snapshots.")
  public VersionStateSnapshot versionState() {
    return versionState;
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "Generated protobuf messages are immutable snapshots.")
  public LaunchDescriptor launchDescriptor() {
    return launchDescriptor;
  }
}
