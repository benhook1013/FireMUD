package net.firedevops.firemud.gamesession.data;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import net.firedevops.firemud.common.smoke.RunOwnedInitialAdmissionFixtureWireCodec;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.gamedesign.v1.LaunchDescriptor;
import net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle;
import net.firedevops.firemud.gamedesign.v1.VersionAssetArtifactState;
import net.firedevops.firemud.gamedesign.v1.VersionStateSnapshot;

/** Verified immutable Game Design projections for the run-owned initial-admission fixture only. */
public final class RunOwnedInitialAdmissionLaunchEvidence {
  public static final String SCHEMA = "firemud.run-owned-v1-launch-read-evidence.v1";

  private final Verification verification;
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
    this(
        UnverifiedProjection.INSTANCE,
        sourceEvidence,
        schema,
        gameTemplateId,
        publishedReleaseBundle,
        versionAssetArtifactState,
        versionState,
        launchDescriptor);
  }

  private RunOwnedInitialAdmissionLaunchEvidence(
      Verification verification,
      AuthoredWorldSourceEvidence sourceEvidence,
      String schema,
      long gameTemplateId,
      PublishedReleaseBundle publishedReleaseBundle,
      VersionAssetArtifactState versionAssetArtifactState,
      VersionStateSnapshot versionState,
      LaunchDescriptor launchDescriptor) {
    this.verification = Objects.requireNonNull(verification, "verification");
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
        new VerifiedFixture(capability, receipt),
        receipt.sourceEvidence(),
        readEvidence.schema(),
        readEvidence.gameTemplateId(),
        readEvidence.publishedReleaseBundle(),
        readEvidence.versionAssetArtifactState(),
        readEvidence.versionState(),
        readEvidence.launchDescriptor());
  }

  /** This check cannot be satisfied by the package's unchecked preflight-test constructor. */
  public void requireVerifiedFixture() {
    verified();
  }

  public RunOwnedInitialAdmissionFixtureWireCodec.Capability fixtureCapability() {
    return RunOwnedInitialAdmissionFixtureCapability.toWireCapability(verified().capability());
  }

  public FreshTenantCreationEvidence creationEvidence() {
    return verified().receipt().creationEvidence();
  }

  public RunOwnedInitialAdmissionFixtureWireCodec.FixturePolicySeal fixturePolicySeal() {
    return verified().receipt().fixturePolicySeal();
  }

  /**
   * Existing runtime identity columns retain bare SHA-256; the policy owner retains its prefixed
   * seal.
   */
  public String fixtureRuntimeRequestDigest() {
    return fixturePolicySeal().requestDigest().substring("sha256:".length());
  }

  public byte[] fixturePolicyRequestBytes() {
    return RunOwnedInitialAdmissionFixtureWireCodec.fixturePolicyRequestBytes(
        fixtureCapability(), creationEvidence(), sourceEvidence, wireLaunchReadEvidence());
  }

  public RunOwnedInitialAdmissionFixtureWireCodec.LaunchReadEvidence wireLaunchReadEvidence() {
    verified();
    return new RunOwnedInitialAdmissionFixtureWireCodec.LaunchReadEvidence(
        schema,
        gameTemplateId,
        publishedReleaseBundle,
        versionAssetArtifactState,
        versionState,
        launchDescriptor);
  }

  private VerifiedFixture verified() {
    if (!(verification instanceof VerifiedFixture fixture)) {
      throw new IllegalArgumentException("Verified run-owned fixture provenance is required");
    }
    RunOwnedInitialAdmissionFixtureCoordinator.requireExactSourceReceipt(
        fixture.capability(), fixture.receipt());
    String requestDigest =
        RunOwnedInitialAdmissionFixtureWireCodec.fixturePolicyRequestDigest(
            RunOwnedInitialAdmissionFixtureCapability.toWireCapability(fixture.capability()),
            fixture.receipt().creationEvidence(),
            sourceEvidence,
            new RunOwnedInitialAdmissionFixtureWireCodec.LaunchReadEvidence(
                schema,
                gameTemplateId,
                publishedReleaseBundle,
                versionAssetArtifactState,
                versionState,
                launchDescriptor));
    var seal = fixture.receipt().fixturePolicySeal();
    if (!"firemud.run-owned-fixture-policy-seal.v1".equals(seal.schema())
        || !"game-design-service".equals(seal.ownerService())
        || !java.util.UUID.fromString(fixture.capability().operationId()).equals(seal.operationId())
        || !requestDigest.equals(seal.requestDigest())
        || !RunOwnedInitialAdmissionFixtureWireCodec.fixturePolicyEvidenceDigest(
                seal.operationId(), requestDigest)
            .equals(seal.evidenceDigest())) {
      throw new IllegalArgumentException("Exact owner-issued fixture policy seal is required");
    }
    return fixture;
  }

  private sealed interface Verification permits UnverifiedProjection, VerifiedFixture {}

  private enum UnverifiedProjection implements Verification {
    INSTANCE
  }

  private record VerifiedFixture(
      RunOwnedInitialAdmissionFixtureCapability capability,
      RunOwnedInitialAdmissionFixtureCapability.SourceReceipt receipt)
      implements Verification {}

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
