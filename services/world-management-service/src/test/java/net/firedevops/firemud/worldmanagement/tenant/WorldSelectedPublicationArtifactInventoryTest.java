package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorldSelectedPublicationArtifactInventoryTest {
  private static final UUID TENANT = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID VERSION = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");

  @Test
  void acceptsExplicitEmptyRegionInputsAndClosedCanonicalSpawnBinding() {
    var region =
        new WorldSelectedPublicationArtifactInventory.RegionGeneratorInput(
            UUID.randomUUID(), "", "");
    var spawn =
        new WorldSelectedPublicationArtifactInventory.SpawnBindingInput(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "NPC",
            "ENTITY_TEMPLATE_REFERENCE_TYPE_CANONICAL_UUID",
            TENANT,
            VERSION,
            UUID.randomUUID(),
            2,
            30);

    assertThatCode(
            () ->
                WorldSelectedPublicationArtifactInventory.requireSupportedRequiredness(
                    TENANT, VERSION, List.of(region), 0, List.of(spawn)))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsMissingOrNullRegionRequirednessInputsInsteadOfTreatingThemAsEmpty() {
    var missingType =
        new WorldSelectedPublicationArtifactInventory.RegionGeneratorInput(
            UUID.randomUUID(), null, "");
    var missingParams =
        new WorldSelectedPublicationArtifactInventory.RegionGeneratorInput(
            UUID.randomUUID(), "", null);

    assertReason(
        () ->
            WorldSelectedPublicationArtifactInventory.requireSupportedRequiredness(
                TENANT, VERSION, List.of(missingType), 0, List.of()),
        WorldSelectedPublicationArtifactInventory.UnrepresentableReason.UNKNOWN_GRAPH_FAMILY);
    assertReason(
        () ->
            WorldSelectedPublicationArtifactInventory.requireSupportedRequiredness(
                TENANT, VERSION, List.of(missingParams), 0, List.of()),
        WorldSelectedPublicationArtifactInventory.UnrepresentableReason.UNKNOWN_GRAPH_FAMILY);
  }

  @Test
  void rejectsOpaqueRegionAndGenerationRuleInputsAsUnrepresentable() {
    var opaqueGenerator =
        new WorldSelectedPublicationArtifactInventory.RegionGeneratorInput(
            UUID.randomUUID(), "noise-v1", "{\"seed\":9}");

    assertReason(
        () ->
            WorldSelectedPublicationArtifactInventory.requireSupportedRequiredness(
                TENANT, VERSION, List.of(opaqueGenerator), 0, List.of()),
        WorldSelectedPublicationArtifactInventory.UnrepresentableReason.REGION_GENERATOR_INPUT);
    assertReason(
        () ->
            WorldSelectedPublicationArtifactInventory.requireSupportedRequiredness(
                TENANT, VERSION, List.of(), 1, List.of()),
        WorldSelectedPublicationArtifactInventory.UnrepresentableReason.GENERATION_RULE_INPUT);
  }

  @Test
  void rejectsUnsupportedEntityReferenceScopeOrRepresentation() {
    var unsupportedReference =
        new WorldSelectedPublicationArtifactInventory.SpawnBindingInput(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "NPC",
            "ENTITY_TEMPLATE_REFERENCE_TYPE_RETAINED_TAG",
            TENANT,
            VERSION,
            UUID.randomUUID(),
            1,
            0);

    assertReason(
        () ->
            WorldSelectedPublicationArtifactInventory.requireSupportedRequiredness(
                TENANT, VERSION, List.of(), 0, List.of(unsupportedReference)),
        WorldSelectedPublicationArtifactInventory.UnrepresentableReason.SPAWN_BINDING_INPUT);
  }

  @Test
  void canonicalLongFieldsRoundTripAsExactDecimalStrings() {
    long beyondBinary64IntegerPrecision = 9_007_199_254_740_993L;
    long maximumLong = Long.MAX_VALUE;
    var envelope =
        new WorldSelectedPublicationArtifactInventory.Envelope(
            WorldSelectedPublicationArtifactInventory.SCHEMA,
            1,
            "COMPLETE",
            new WorldSelectedPublicationArtifactInventory.OwnerScope(
                "world",
                TENANT,
                VERSION,
                Long.toString(beyondBinary64IntegerPrecision),
                Long.toString(maximumLong),
                UUID.randomUUID(),
                Long.toString(maximumLong),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "sha256:" + "1".repeat(64),
                UUID.randomUUID(),
                "sha256:" + "2".repeat(64),
                "sha256:" + "3".repeat(64)),
            new WorldSelectedPublicationArtifactInventory.SourceIntake(
                "world", Long.toString(maximumLong), "source-tenant", "CANONICAL"),
            new WorldSelectedPublicationArtifactInventory.SelectedApplication(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID().toString(),
                "sha256:" + "4".repeat(64),
                "sha256:" + "5".repeat(64)),
            new WorldSelectedPublicationArtifactInventory.AccountOrder(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "publish",
                "sha256:" + "6".repeat(64),
                UUID.randomUUID().toString(),
                "sha256:" + "7".repeat(64),
                new byte[] {1}),
            new WorldSelectedPublicationArtifactInventory.Freeze(
                UUID.randomUUID(),
                "publish",
                "8".repeat(64),
                Long.toString(maximumLong),
                "workflow"),
            new WorldSelectedPublicationArtifactInventory.Checkpoint(
                UUID.randomUUID().toString(), "9".repeat(64), 3),
            new WorldSelectedPublicationArtifactInventory.SourceModel(
                "WORLD_LOGICAL_ROOM_EXIT_ADJACENCY_V1",
                2,
                "sha256:" + "a".repeat(64),
                "sha256:" + "b".repeat(64),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                0,
                0,
                List.of()),
            List.of());
    byte[] canonicalBytes = WorldSelectedPublicationArtifactInventory.encode(envelope);
    String canonicalJson = new String(canonicalBytes, StandardCharsets.UTF_8);

    assertThat(canonicalJson)
        .contains("\"localTenantKey\":\"9007199254740993\"")
        .contains("\"localVersionKey\":\"9223372036854775807\"")
        .contains("\"gameDesignVersionId\":\"9223372036854775807\"")
        .contains("\"sourceGameRowId\":\"9223372036854775807\"")
        .contains("\"versionStateEpoch\":\"9223372036854775807\"");

    var decoded = WorldSelectedPublicationArtifactInventory.decodeCanonicalEnvelope(canonicalBytes);
    var originalInventory = new WorldSelectedPublicationArtifactInventory(envelope, canonicalBytes);
    var decodedInventory = new WorldSelectedPublicationArtifactInventory(decoded, canonicalBytes);
    assertThat(decoded.ownerScope().localTenantKey())
        .isEqualTo(Long.toString(beyondBinary64IntegerPrecision));
    assertThat(decoded.ownerScope().localVersionKey()).isEqualTo(Long.toString(maximumLong));
    assertThat(decoded.sourceIntake().sourceGameRowId()).isEqualTo(Long.toString(maximumLong));
    assertThat(decoded.freeze().versionStateEpoch()).isEqualTo(Long.toString(maximumLong));
    assertThat(decodedInventory.publicEvidence().freeze().versionStateEpoch())
        .isEqualTo(Long.toString(maximumLong));
    assertThat(new String(decodedInventory.publicCanonicalBytes(), StandardCharsets.UTF_8))
        .contains("\"versionStateEpoch\":\"9223372036854775807\"");
    assertThat(decodedInventory.publicDigest()).isEqualTo(originalInventory.publicDigest());

    String zeroPaddedEpoch =
        canonicalJson.replace(
            "\"versionStateEpoch\":\"9223372036854775807\"",
            "\"versionStateEpoch\":\"09223372036854775807\"");
    assertThatThrownBy(
            () ->
                WorldSelectedPublicationArtifactInventory.decodeCanonicalEnvelope(
                    zeroPaddedEpoch.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(WorldDesignPublicationFenceRepository.ConflictException.class);
    String floatingEpoch =
        canonicalJson.replace(
            "\"versionStateEpoch\":\"9223372036854775807\"",
            "\"versionStateEpoch\":9223372036854775807.0");
    assertThatThrownBy(
            () ->
                WorldSelectedPublicationArtifactInventory.decodeCanonicalEnvelope(
                    floatingEpoch.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(WorldDesignPublicationFenceRepository.ConflictException.class);
  }

  private static void assertReason(
      Runnable action, WorldSelectedPublicationArtifactInventory.UnrepresentableReason expected) {
    Throwable failure = catchThrowable(action::run);
    assertThat(failure)
        .isInstanceOfSatisfying(
            WorldSelectedPublicationArtifactInventory.UnrepresentableRequirednessException.class,
            exception -> assertThat(exception.reason()).isEqualTo(expected));
  }
}
