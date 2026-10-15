package net.firedevops.firemud.worldmanagement.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Supplier;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorGrpcCodec;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerCertificateEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeDigest;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeReceipt;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredVersionIdentityReceipt;
import org.junit.jupiter.api.Test;

class RunOwnedWorldFixedPresetResourcesTest {
  @Test
  void runtimePresetMatchesTheOriginalMigrationLiterals() throws Exception {
    String digest = "sha256:f817af2770072bab0384c30c19b8cd30fce7a5a33f7587faed0024aa7c0909bc";
    String[] templates = {
      "9affa11b-02f5-3cc9-a50e-6760083c71e9",
      "936c4d65-c460-3b73-9f4e-6ee6295bca24",
      "c6058518-f9e0-3f26-9b05-2bc786095089",
      "e74cca03-3b53-3edc-84f5-890651f5fea8",
      "18c4b7a2-6498-3865-a464-814b56e4d4c2",
      "ab46ef8f-e74f-3197-be62-9785ef63242e"
    };
    var definition = RunOwnedWorldFixedPresetResources.definition();
    assertThat(definition.expectedContentDigest()).isEqualTo(digest);
    assertThat(
            java.util.stream.Stream.concat(
                    java.util.stream.Stream.of(
                        definition.region().templateId(), definition.zone().templateId()),
                    java.util.stream.Stream.concat(
                        definition.rooms().stream().map(room -> room.templateId()),
                        definition.exits().stream().map(exit -> exit.templateId())))
                .map(UUID::toString)
                .toList())
        .containsExactly(templates);
    try (var migration =
        RunOwnedWorldFixedPresetResourcesTest.class.getResourceAsStream(
            "/db/migration/V1041__world_run_owned_fixed_preset_preparation.sql")) {
      assertThat(migration).isNotNull();
      String sql = new String(migration.readAllBytes(), StandardCharsets.UTF_8);
      assertThat(sql).contains("'" + digest + "'");
      for (String template : templates) assertThat(sql).contains("'" + template + "'");
    }
  }

  @Test
  void rejectsResolvedScriptPatchOverrideAtInitialPlanBeforeMaterialization() {
    assertOverrideRejected(true, false);
  }

  @Test
  void rejectsRemapSetOverrideAtInitialPlanBeforeMaterialization() {
    assertOverrideRejected(false, true);
  }

  private static void assertOverrideRejected(
      boolean scriptPatchVersionPresent, boolean remapSetIdPresent) {
    RunOwnedCanonicalWorldLaunchEvidence evidence =
        RunOwnedCanonicalWorldLaunchEvidenceTestFixtures.createWithDescriptorOverrides(
            scriptPatchVersionPresent, remapSetIdPresent);
    evidence.launchDescriptor().requireValid();
    assertThat(evidence.launchDescriptor().scriptPatchVersionPresent())
        .isEqualTo(scriptPatchVersionPresent);
    assertThat(evidence.launchDescriptor().remapSetIdPresent()).isEqualTo(remapSetIdPresent);
    assertThat(evidence.sourceReceipt().launchReadEvidence().launchDescriptor())
        .isEqualTo(
            AuthoredWorldLaunchDescriptorGrpcCodec.toLaunchDescriptorMessage(
                evidence.launchDescriptor()));
    WorldAuthoredSourceIntakeReceipt sourceReceipt = sourceReceipt(evidence);
    WorldAuthoredVersionIdentityReceipt versionReceipt = versionReceipt(evidence, sourceReceipt);

    assertThatThrownBy(
            () ->
                withFixturePeer(
                    evidence,
                    () ->
                        RunOwnedWorldFixedPresetResources.plan(
                            evidence, sourceReceipt, versionReceipt)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith("RUN_OWNED_WORLD_FIXED_PRESET_OVERRIDES_UNSUPPORTED:");
  }

  private static WorldAuthoredSourceIntakeReceipt sourceReceipt(
      RunOwnedCanonicalWorldLaunchEvidence evidence) {
    var source = evidence.sourceReceipt().sourceEvidence();
    UUID intakeRequestId = UUID.fromString("11111111-1111-4111-8111-111111111111");
    UUID operationId = UUID.fromString("22222222-2222-4222-8222-222222222222");
    long localTenantKey = 9001L;
    String requestDigest =
        WorldAuthoredSourceIntakeDigest.requestDigest(
            source.targetNamespace(), intakeRequestId, source);
    return new WorldAuthoredSourceIntakeReceipt(
        1,
        source.targetNamespace(),
        intakeRequestId,
        operationId,
        source.canonicalTenantId(),
        source.worldSlug(),
        source.operationId(),
        source.evidenceDigest(),
        requestDigest,
        WorldAuthoredSourceIntakeDigest.receiptDigest(
            source.targetNamespace(), operationId, requestDigest, source, localTenantKey),
        localTenantKey,
        source);
  }

  private static WorldAuthoredVersionIdentityReceipt versionReceipt(
      RunOwnedCanonicalWorldLaunchEvidence evidence, WorldAuthoredSourceIntakeReceipt sourceReceipt) {
    var source = sourceReceipt.source();
    var snapshot = evidence.sourceReceipt().launchReadEvidence().versionState();
    var request =
        new AuthoredWorldVersionStateEvidence.Request(
            1,
            source.targetNamespace(),
            UUID.fromString("33333333-3333-4333-8333-333333333333"),
            source.canonicalTenantId(),
            source.worldSlug(),
            source.operationId(),
            source.evidenceDigest(),
            snapshot.getVersionId());
    var stateEvidence =
        AuthoredWorldVersionStateEvidence.create(
            request,
            source,
            evidence.gameSessionAssociation().canonicalVersionId(),
            VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED,
            snapshot.getVersionStateEpoch());
    return new WorldAuthoredVersionIdentityReceipt(
        1,
        UUID.fromString("44444444-4444-4444-8444-444444444444"),
        9003L,
        sourceReceipt,
        stateEvidence);
  }

  private static <T> T withFixturePeer(RunOwnedCanonicalWorldLaunchEvidence evidence, Supplier<T> action) {
    GrpcPeerIdentity peer =
        new GrpcPeerIdentity(
            "spiffe://firemud/ns/dev/sa/game-session-service", "dev", "game-session-service");
    GrpcPeerCertificateEvidence certificate = mock(GrpcPeerCertificateEvidence.class);
    when(certificate.leafSha256()).thenReturn(evidence.capability().gameSessionLeafSha256());
    Context context =
        Context.ROOT
            .withValue(GrpcPeerIdentity.CONTEXT_KEY, peer)
            .withValue(GrpcPeerCertificateEvidence.CONTEXT_KEY, certificate);
    Context previous = context.attach();
    try {
      return action.get();
    } finally {
      context.detach(previous);
    }
  }
}
