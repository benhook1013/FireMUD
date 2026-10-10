package net.firedevops.firemud.common.account.sourceintake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.gamedesign.DraftSynchronizedVisibilityEvidence.AppliedEpoch;
import net.firedevops.firemud.common.gamedesign.SelectedOwnerIntakeSourceContent;
import net.firedevops.firemud.common.gamedesign.TemplateConfigSourceSnapshot;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSelectedSource;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding.PublishIntent;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding.VisibilityFence;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.common.publication.SelectedOwnerWorldInventoryReadEvidence;
import net.firedevops.firemud.common.publication.SelectedOwnerWorldInventoryReadGrpcCodec;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeEvidence;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeEvidence.Acknowledgement;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeEvidence.OwnerFreezePhase;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeGrpcCodec;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.ArtifactDecision;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.Checkpoint;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.FamilyCount;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.Freeze;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.InboundSourceClosureDeclaration;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.InboundSourceFamilyCount;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.PublicAccountOrder;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.PublicEvidence;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.PublicOwnerScope;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.RegionGeneratorInput;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.SelectedApplication;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.SourceModel;
import net.firedevops.firemud.worldmanagement.v1.ReadSelectedOwnerWorldInventoryRequest;
import org.junit.jupiter.api.Test;

class SelectedOwnerWorldInventoryRecipientFieldsTest {
  @Test
  void rejectsChangedOriginalRecipientAndDerivedClosurePurposeForBothOwners() {
    for (Owner owner : List.of(Owner.ENTITY_MANAGEMENT, Owner.AUTOMATION_SCRIPTING)) {
      var binding = SelectedOwnerIntakeAuthorizationReadEvidenceTest.binding(owner);
      String purpose =
          owner == Owner.ENTITY_MANAGEMENT
              ? "ENTITY_INTAKE_WORLD_CLOSURE_READ"
              : "AUTOMATION_INTAKE_WORLD_CLOSURE_READ";
      var base =
          ReadSelectedOwnerWorldInventoryRequest.newBuilder()
              .setSchemaVersion(1)
              .setTargetNamespace(binding.targetNamespace())
              .setReadRequestId("12121212-1212-4212-8212-121212121212")
              .setOriginalIntakeAuthorizationBinding(ByteString.copyFrom(binding.canonicalBytes()))
              .setIntakeAuthorizationDigest(binding.digest())
              .setIntendedReader(binding.intendedReader())
              .setClosureReadPurpose(purpose)
              .build();

      assertThatThrownBy(() -> SelectedOwnerWorldInventoryReadGrpcCodec.fromRequest(base))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("freeze evidence");
      assertThatThrownBy(
              () ->
                  SelectedOwnerWorldInventoryReadGrpcCodec.fromRequest(
                      base.toBuilder()
                          .setIntendedReader("spiffe://firemud/ns/test/sa/world-management-service")
                          .build()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("recipient differs");
      assertThatThrownBy(
              () ->
                  SelectedOwnerWorldInventoryReadGrpcCodec.fromRequest(
                      base.toBuilder().setClosureReadPurpose("ENTITY_INTAKE_RETENTION").build()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("closure read purpose differs");
    }
  }

  @Test
  void roundTripsCompleteOwnerRequestAndTypedPublicInventoryForBothOwners() {
    for (Owner owner : List.of(Owner.ENTITY_MANAGEMENT, Owner.AUTOMATION_SCRIPTING)) {
      var fixture = fixture(owner);
      var requestWire = SelectedOwnerWorldInventoryReadGrpcCodec.toRequest(fixture.request());
      var decodedRequest = SelectedOwnerWorldInventoryReadGrpcCodec.fromRequest(requestWire);

      assertThat(requestWire.getSerializedSize())
          .isLessThanOrEqualTo(SelectedOwnerWorldInventoryReadEvidence.MAX_WIRE_BYTES);
      assertThat(requestWire.getIntendedReader()).isEqualTo(fixture.binding().intendedReader());
      assertThat(requestWire.getClosureReadPurpose())
          .isEqualTo(fixture.request().closureReadPurpose());
      assertThat(decodedRequest.authorizationBinding().canonicalBytes())
          .containsExactly(fixture.binding().canonicalBytes());
      assertThat(decodedRequest.freezeEvidence().request())
          .isEqualTo(fixture.freezeEvidence().request());
      assertThat(decodedRequest.freezeEvidence().acknowledgement())
          .isEqualTo(fixture.freezeEvidence().acknowledgement());
      assertThat(decodedRequest.freezeEvidence())
          .isEqualTo(fixture.freezeEvidence())
          .hasSameHashCodeAs(fixture.freezeEvidence());

      var responseWire =
          SelectedOwnerWorldInventoryReadGrpcCodec.toResponse(decodedRequest, fixture.inventory());
      var decodedEvidence =
          SelectedOwnerWorldInventoryReadGrpcCodec.fromResponse(decodedRequest, responseWire);

      assertThat(fixture.inventory().canonicalBytes().length)
          .isLessThanOrEqualTo(SelectedOwnerWorldInventoryReadEvidence.MAX_PUBLIC_INVENTORY_BYTES);
      assertThat(responseWire.getSerializedSize())
          .isLessThanOrEqualTo(SelectedOwnerWorldInventoryReadEvidence.MAX_WIRE_BYTES);
      assertThat(responseWire.getRequest()).isEqualTo(requestWire);
      assertThat(decodedEvidence.request().authorizationBinding().canonicalBytes())
          .containsExactly(fixture.binding().canonicalBytes());
      assertThat(decodedEvidence.request().freezeEvidence().request())
          .isEqualTo(fixture.freezeEvidence().request());
      assertThat(decodedEvidence.inventory().canonicalBytes())
          .containsExactly(fixture.inventory().canonicalBytes());
      assertThat(decodedEvidence.inventory().digest()).isEqualTo(fixture.inventory().digest());
      assertThat(decodedEvidence.inventory().publicEvidence())
          .isEqualTo(fixture.inventory().publicEvidence());
    }
  }

  @Test
  void rejectsChangedCompleteRequestEchoAndInventoryDigest() {
    var fixture = fixture(Owner.ENTITY_MANAGEMENT);
    var requestWire = SelectedOwnerWorldInventoryReadGrpcCodec.toRequest(fixture.request());
    var response =
        SelectedOwnerWorldInventoryReadGrpcCodec.toResponse(fixture.request(), fixture.inventory());
    var changedEcho =
        response.toBuilder()
            .setRequest(
                requestWire.toBuilder().setReadRequestId("abababab-abab-4bab-8bab-abababababab"))
            .build();
    String zeroDigest = "sha256:" + "0".repeat(64);
    var changedDigest =
        response.toBuilder()
            .setPublicInventoryDigest(
                fixture.inventory().digest().equals(zeroDigest)
                    ? "sha256:" + "1".repeat(64)
                    : zeroDigest)
            .build();

    assertThatThrownBy(
            () ->
                SelectedOwnerWorldInventoryReadGrpcCodec.fromResponse(
                    fixture.request(), changedEcho))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact selected-owner inventory read request");
    assertThatThrownBy(
            () ->
                SelectedOwnerWorldInventoryReadGrpcCodec.fromResponse(
                    fixture.request(), changedDigest))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsInventoryBoundToDifferentFreezeAcknowledgement() {
    var fixture = fixture(Owner.ENTITY_MANAGEMENT);
    var requestWire = SelectedOwnerWorldInventoryReadGrpcCodec.toRequest(fixture.request());
    var changedAcknowledgement =
        requestWire.getFreezeAcknowledgement().toBuilder().setContentDigest("b".repeat(64)).build();
    var changedRequestWire =
        requestWire.toBuilder().setFreezeAcknowledgement(changedAcknowledgement).build();
    var changedRequest = SelectedOwnerWorldInventoryReadGrpcCodec.fromRequest(changedRequestWire);

    assertThat(changedRequest.freezeEvidence()).isNotEqualTo(fixture.freezeEvidence());
    assertThatThrownBy(
            () ->
                SelectedOwnerWorldInventoryReadGrpcCodec.toResponse(
                    changedRequest, fixture.inventory()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact selected-owner freeze");
  }

  private static Fixture fixture(Owner owner) {
    String namespace = "test";
    UUID tenant = id("11111111-1111-4111-8111-111111111111");
    UUID version = id("22222222-2222-4222-8222-222222222222");
    UUID actor = id("99999999-9999-4999-8999-999999999999");
    var target = new TargetProof(tenant, version, 23L, "tenant", 42L, "tenant", "NEW_GAME_ROW");
    var selected =
        DraftCommitBinding.create(
            target,
            id("33333333-3333-4333-8333-333333333333"),
            id("44444444-4444-4444-8444-444444444444"),
            "base-commit-0",
            List.of(
                new RevisionPayload(
                    "0", id("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaab"), Owner.WORLD_MANAGEMENT, "{}")),
            List.of(
                new DraftCommitBinding.AffectedUnit(
                    Owner.WORLD_MANAGEMENT, "REGION", "region-1", "AGGREGATE", "region-1", "0")));
    var sourceScope =
        new SelectedOwnerIntakeSourceReadScope(
            owner,
            namespace,
            id("55555555-5555-4555-8555-555555555555"),
            id("66666666-6666-4666-8666-666666666666"),
            id("77777777-7777-4777-8777-777777777777"),
            actor,
            selected);
    var authorization =
        new SelectedOwnerIntakeAuthorizationBinding(
            sourceContent(sourceScope),
            List.of(
                source(SourceKind.TENANT, tenant.toString(), "tenant"),
                source(SourceKind.ACCOUNT, actor.toString(), "account"),
                source(SourceKind.MEMBERSHIP, actor + "/" + tenant, "membership")));
    var selection =
        AuthoredDraftPublishSelectionBinding.capture(
            new PublishIntent(
                tenant,
                version,
                "publication-request",
                "9",
                "selected-owner codec fixture",
                selected.requestId(),
                selected.commitId(),
                selected.digest()),
            target,
            selected,
            new VisibilityFence(
                target,
                selected.requestId(),
                selected.commitId(),
                selected.digest(),
                "[]",
                OffsetDateTime.parse("2026-10-01T00:00:00Z")));
    var account =
        new AccountPublicationAuthorizationBinding(
            id("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
            id("ffffffff-ffff-4fff-8fff-ffffffffffff"),
            new AccountPublicationAuthorizationBinding.PreallocationInput(actor, selection),
            List.of(
                new SourceEvidence(
                    SourceKind.ACCOUNT, actor.toString(), "1", "1", null, null, new byte[] {1})));
    var freezeRequest =
        WorldSelectedDraftPublicationFreezeEvidence.Request.create(
            namespace,
            tenant,
            version,
            selection.intent().publishRequestId(),
            9,
            selection.digest().substring("sha256:".length()),
            account);
    var acknowledgement =
        new Acknowledgement(
            freezeRequest,
            id("12121212-1212-4212-8212-121212121212"),
            9,
            id("13131313-1313-4313-8313-131313131313"),
            OwnerFreezePhase.FROZEN,
            selected.commitId().toString(),
            "a".repeat(64),
            4);
    var freezeEvidence =
        WorldSelectedDraftPublicationFreezeGrpcCodec.fromResponse(
            freezeRequest,
            WorldSelectedDraftPublicationFreezeGrpcCodec.toResponse(acknowledgement));
    var request =
        SelectedOwnerWorldInventoryReadEvidence.create(namespace, authorization, freezeEvidence);
    var inventory =
        WorldSelectedPublicationArtifactInventoryEvidence.fromPublicEvidence(
            freezeEvidence, publicInventory(freezeEvidence, selected, account));
    return new Fixture(authorization, freezeEvidence, request, inventory);
  }

  private static SelectedOwnerIntakeSourceContent sourceContent(
      SelectedOwnerIntakeSourceReadScope scope) {
    var out = new java.io.ByteArrayOutputStream();
    frame(out, SelectedOwnerIntakeSourceContent.DOMAIN);
    frame(out, scope.canonicalBytes());
    frame(out, scope.digest());
    for (String family :
        List.of(
            "COMMAND", "REALM_POLICY", "ASSET", "GAMEPLAY_RULE", "BRANDING", "TEMPLATE_CONFIG")) {
      byte[] snapshot = sourceSnapshot(family, scope.selected());
      frame(out, family);
      frame(out, snapshot);
      frame(out, DraftAuthorizationFenceBinding.digest(snapshot));
    }
    byte[] bytes = out.toByteArray();
    return SelectedOwnerIntakeSourceContent.fromStored(
        bytes, scope, DraftAuthorizationFenceBinding.digest(bytes));
  }

  private static byte[] sourceSnapshot(String family, DraftCommitBinding selected) {
    if ("GAMEPLAY_RULE".equals(family)) {
      return new GameplayRuleSelectedSource(
              GameplayRuleManifest.canonical(
                  Map.of(
                      "schema",
                      "game-design-gameplay-rule-source-snapshot/v1",
                      "bindingJson",
                      selected.canonicalJson(),
                      "bindingDigest",
                      selected.digest(),
                      "sourceEpoch",
                      "0",
                      "inheritedCommitId",
                      "",
                      "genesisReceiptId",
                      "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                      "manifestJson",
                      GameplayRuleManifest.explicitEmpty().canonicalJson(),
                      "entries",
                      List.of())))
          .canonicalBytes();
    }
    if ("TEMPLATE_CONFIG".equals(family)) {
      return new TemplateConfigSourceSnapshot(
              selected, "0", null, id("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"), List.of())
          .canonicalBytes();
    }
    return GameplayRuleManifest.canonical(
            Map.of(
                "schema",
                "synthetic-test-only/v1",
                "bindingJson",
                selected.canonicalJson(),
                "bindingDigest",
                selected.digest(),
                "family",
                family))
        .getBytes(StandardCharsets.UTF_8);
  }

  private static PublicEvidence publicInventory(
      WorldSelectedDraftPublicationFreezeEvidence freeze,
      DraftCommitBinding selected,
      AccountPublicationAuthorizationBinding account) {
    var request = freeze.request();
    var acknowledgement = freeze.acknowledgement();
    var inboundClosure =
        new InboundSourceClosureDeclaration(
            1,
            List.of(
                new InboundSourceFamilyCount("WORLD_INBOUND_SOURCE_FAMILY_LOOT_REFERENCE_ROOT", 0),
                new InboundSourceFamilyCount(
                    "WORLD_INBOUND_SOURCE_FAMILY_LOOT_REFERENCE_ATTACHMENT", 0),
                new InboundSourceFamilyCount("WORLD_INBOUND_SOURCE_FAMILY_BEHAVIOR_SELECTION", 0),
                new InboundSourceFamilyCount("WORLD_INBOUND_SOURCE_FAMILY_BEHAVIOR_BINDING", 0),
                new InboundSourceFamilyCount("WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_HOOK", 0),
                new InboundSourceFamilyCount(
                    "WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_SCRIPT_REFERENCE", 0),
                new InboundSourceFamilyCount(
                    "WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_TARGET_BINDING", 0)));
    var sourceModel =
        new SourceModel(
            WorldSelectedPublicationArtifactInventoryEvidence.INBOUND_CLOSURE_SOURCE_MODEL,
            WorldSelectedPublicationArtifactInventoryEvidence.INBOUND_CLOSURE_GRAPH_SCHEMA_VERSION,
            "sha256:" + "4".repeat(64),
            "sha256:" + "5".repeat(64),
            List.of(
                new FamilyCount("REGION", 1),
                new FamilyCount("ZONE", 1),
                new FamilyCount("ROOM", 1),
                new FamilyCount("ROOM_EXIT", 0),
                new FamilyCount("GENERATION_RULE", 0),
                new FamilyCount("WORLD_ENTITY_SPAWN_BINDING", 0)),
            List.of(new RegionGeneratorInput(id("15151515-1515-4515-8515-151515151515"), "", "")),
            List.of("id", "name", "scopeType", "scopeId", "value"),
            List.of(
                "id",
                "shardId",
                "name",
                "weather",
                "generationSeed",
                "generatorType",
                "generatorParams",
                "spacingMultiplier"),
            List.of("id", "regionId", "name"),
            List.of(
                "id",
                "zoneId",
                "name",
                "description",
                "nameLocalizedVariantsJson",
                "descriptionLocalizedVariantsJson"),
            List.of("id", "fromRoomId", "toRoomId", "direction", "cost"),
            List.of(
                "id",
                "roomId",
                "entityTemplateType",
                "entityReference.kind",
                "entityReference.tenantId",
                "entityReference.versionId",
                "entityReference.templateId",
                "spawnCount",
                "respawnDelaySeconds"),
            List.of(),
            0,
            0,
            selected.affectedUnits(Owner.WORLD_MANAGEMENT).stream()
                .map(
                    unit ->
                        new AppliedEpoch(
                            unit.aggregateType(),
                            unit.aggregateId(),
                            unit.scopeType(),
                            unit.scopeId(),
                            unit.expectedEpoch(),
                            new BigInteger(unit.expectedEpoch()).add(BigInteger.ONE).toString()))
                .toList(),
            inboundClosure);
    var selection = account.input().selection();
    return new PublicEvidence(
        WorldSelectedPublicationArtifactInventoryEvidence.INBOUND_CLOSURE_SCHEMA,
        WorldSelectedPublicationArtifactInventoryEvidence.INBOUND_CLOSURE_SCHEMA_VERSION,
        "COMPLETE",
        new PublicOwnerScope(
            request.targetNamespace(),
            request.canonicalTenantId(),
            request.canonicalVersionId(),
            id("12121212-1212-4212-8212-121212121212"),
            acknowledgement.intakeRequestId(),
            id("13131313-1313-4313-8313-131313131313"),
            "sha256:" + "1".repeat(64),
            id("14141414-1414-4414-8414-141414141414"),
            "sha256:" + "2".repeat(64),
            "sha256:" + "3".repeat(64)),
        new SelectedApplication(
            id("16161616-1616-4616-8616-161616161616"),
            selected.requestId(),
            selected.commitId().toString(),
            selected.digest(),
            "sha256:" + "6".repeat(64)),
        new PublicAccountOrder(
            account.operationId(),
            account.fenceId(),
            account.input().actorAccountId(),
            selection.intent().publishRequestId(),
            selection.digest(),
            selected.commitId().toString(),
            DraftAuthorizationFenceBinding.digest(account.canonicalBytes())),
        new Freeze(
            acknowledgement.publicationFence(),
            request.publicationRequestId(),
            request.requestDigest(),
            Long.toString(acknowledgement.versionStateEpoch()),
            PublicationDigestRequestBinding.full(
                    request.canonicalTenantId().toString(),
                    Long.toString(selection.target().gameDesignVersionRowId()),
                    request.publicationRequestId())
                .derivedWorkflowIdentity()),
        new Checkpoint(
            acknowledgement.appliedCommitId(),
            acknowledgement.contentDigest(),
            acknowledgement.digestSchemaVersion()),
        sourceModel,
        List.of(
            new ArtifactDecision(
                "NAVMESH", "NOT_REQUIRED", "ROOM_LOGICAL_TOPOLOGY_HAS_NO_SPATIAL_NAVMESH_INPUT"),
            new ArtifactDecision(
                "PATH_GRAPH",
                "NOT_REQUIRED",
                "AUTHORED_ROOM_EXIT_EDGES_ARE_THE_SUPPORTED_TRAVERSAL_GRAPH")));
  }

  private static SourceEvidence source(SourceKind kind, String scope, String marker) {
    return new SourceEvidence(
        kind, scope, null, "1", null, null, marker.getBytes(StandardCharsets.UTF_8));
  }

  private static void frame(java.io.ByteArrayOutputStream out, String value) {
    DraftAuthorizationFenceBinding.frame(out, value);
  }

  private static void frame(java.io.ByteArrayOutputStream out, byte[] value) {
    DraftAuthorizationFenceBinding.frame(out, value);
  }

  private static UUID id(String value) {
    return UUID.fromString(value);
  }

  private record Fixture(
      SelectedOwnerIntakeAuthorizationBinding binding,
      WorldSelectedDraftPublicationFreezeEvidence freezeEvidence,
      SelectedOwnerWorldInventoryReadEvidence.Request request,
      WorldSelectedPublicationArtifactInventoryEvidence inventory) {}
}
