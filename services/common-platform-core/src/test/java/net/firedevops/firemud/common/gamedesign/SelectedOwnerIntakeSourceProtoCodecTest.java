package net.firedevops.firemud.common.gamedesign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSourceReadEvidence;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSourceReadScope;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSelectedSource;
import net.firedevops.firemud.gamedesign.v1.SelectedOwnerIntakeSourceRequest;
import net.firedevops.firemud.gamedesign.v1.SelectedOwnerIntakeSourceResponse;
import org.junit.jupiter.api.Test;

/** Synthetic codec fixtures cover integrity mapping only, not genuine source-owner proof. */
class SelectedOwnerIntakeSourceProtoCodecTest {
  private static final String NAMESPACE = "test";
  private static final UUID TENANT = id("11111111-1111-4111-8111-111111111111");
  private static final UUID VERSION = id("22222222-2222-4222-8222-222222222222");
  private static final UUID REQUEST_ID = id("33333333-3333-4333-8333-333333333333");
  private static final UUID COMMIT = id("44444444-4444-4444-8444-444444444444");
  private static final UUID REVISION = id("55555555-5555-4555-8555-555555555555");
  private static final UUID OPERATION = id("66666666-6666-4666-8666-666666666666");
  private static final UUID FENCE = id("77777777-7777-4777-8777-777777777777");
  private static final UUID INTAKE = id("88888888-8888-4888-8888-888888888888");
  private static final UUID ACTOR = id("99999999-9999-4999-8999-999999999999");
  private static final UUID GENESIS = id("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final List<String> FAMILIES =
      List.of("COMMAND", "REALM_POLICY", "ASSET", "GAMEPLAY_RULE", "BRANDING", "TEMPLATE_CONFIG");

  @Test
  void roundTripsBothOwnerRequestsAndCompleteSourceContent() {
    DraftCommitBinding selected =
        binding(COMMIT, SelectedOwnerIntakeSourceTestFixtures.selectedRevisionPayload());
    for (Owner owner : List.of(Owner.ENTITY_MANAGEMENT, Owner.AUTOMATION_SCRIPTING)) {
      var request = request(owner, selected);
      var wireRequest = SelectedOwnerIntakeSourceProtoCodec.toRequest(request);
      assertThat(SelectedOwnerIntakeSourceProtoCodec.fromRequest(wireRequest)).isEqualTo(request);

      var content = content(request.scope(), 0);
      var wireResponse = SelectedOwnerIntakeSourceProtoCodec.toResponse(request, content);
      assertThat(wireResponse.getRequest()).isEqualTo(wireRequest);
      var decoded = SelectedOwnerIntakeSourceProtoCodec.fromResponse(request, wireResponse);
      assertThat(decoded.scope()).isEqualTo(request.scope());
      assertThat(decoded.digest()).isEqualTo(content.digest());
      assertThat(decoded.canonicalBytes()).isEqualTo(content.canonicalBytes());
      assertThat(decoded.snapshotBytes("COMMAND")).isEqualTo(content.snapshotBytes("COMMAND"));
    }
  }

  @Test
  void rejectsChangedSchemaNamespaceCorrelationReaderPurposeScopeAndDigest() {
    var request =
        request(
            Owner.ENTITY_MANAGEMENT,
            binding(COMMIT, SelectedOwnerIntakeSourceTestFixtures.selectedRevisionPayload()));
    var wire = SelectedOwnerIntakeSourceProtoCodec.toRequest(request);

    assertInvalidRequest(wire.toBuilder().setSchemaVersion(2).build());
    assertInvalidRequest(wire.toBuilder().setTargetNamespace("other").build());
    assertInvalidRequest(wire.toBuilder().setReadRequestId("not-a-uuid").build());
    assertInvalidRequest(wire.toBuilder().setIntendedReader("spiffe://wrong").build());
    assertInvalidRequest(wire.toBuilder().setPurpose("PUBLICATION").build());
    assertInvalidRequest(wire.toBuilder().clearPreliminarySourceScope().build());
    assertInvalidRequest(
        wire.toBuilder().setPreliminaryScopeDigest("sha256:" + "0".repeat(64)).build());
  }

  @Test
  void requiresTheFullUnchangedRequestEchoBeforeInspectingContent() {
    var request =
        request(
            Owner.AUTOMATION_SCRIPTING,
            binding(COMMIT, SelectedOwnerIntakeSourceTestFixtures.selectedRevisionPayload()));
    var content = content(request.scope(), 0);
    var response = SelectedOwnerIntakeSourceProtoCodec.toResponse(request, content);
    List<UnaryOperator<SelectedOwnerIntakeSourceRequest.Builder>> substitutions =
        List.of(
            builder -> builder.setSchemaVersion(2),
            builder -> builder.setTargetNamespace("other"),
            builder -> builder.setReadRequestId(UUID.randomUUID().toString()),
            builder -> builder.setIntendedReader("spiffe://wrong"),
            builder -> builder.setPurpose("PUBLICATION"),
            builder -> builder.setPreliminarySourceScope(ByteString.copyFromUtf8("substituted")),
            builder -> builder.setPreliminaryScopeDigest("sha256:" + "0".repeat(64)));

    for (var substitution : substitutions) {
      var changedEcho = substitution.apply(response.getRequest().toBuilder()).build();
      assertThatThrownBy(
              () ->
                  SelectedOwnerIntakeSourceProtoCodec.fromResponse(
                      request, response.toBuilder().setRequest(changedEcho).build()))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(
            () ->
                SelectedOwnerIntakeSourceProtoCodec.fromResponse(
                    request, response.toBuilder().clearRequest().build()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsUnknownOuterAndNestedFields() {
    var request =
        request(
            Owner.ENTITY_MANAGEMENT,
            binding(COMMIT, SelectedOwnerIntakeSourceTestFixtures.selectedRevisionPayload()));
    var wireRequest = SelectedOwnerIntakeSourceProtoCodec.toRequest(request);
    var response =
        SelectedOwnerIntakeSourceProtoCodec.toResponse(request, content(request.scope(), 0));
    var unknown = unknownFields();

    assertThatThrownBy(
            () ->
                SelectedOwnerIntakeSourceProtoCodec.fromRequest(
                    wireRequest.toBuilder().setUnknownFields(unknown).build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                SelectedOwnerIntakeSourceProtoCodec.fromResponse(
                    request, response.toBuilder().setUnknownFields(unknown).build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                SelectedOwnerIntakeSourceProtoCodec.fromResponse(
                    request,
                    response.toBuilder()
                        .setRequest(response.getRequest().toBuilder().setUnknownFields(unknown))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsContentDigestAndScopeSubstitution() {
    var request =
        request(
            Owner.ENTITY_MANAGEMENT,
            binding(COMMIT, SelectedOwnerIntakeSourceTestFixtures.selectedRevisionPayload()));
    var original = content(request.scope(), 0);
    var response = SelectedOwnerIntakeSourceProtoCodec.toResponse(request, original);

    assertThatThrownBy(
            () ->
                SelectedOwnerIntakeSourceProtoCodec.fromResponse(
                    request,
                    response.toBuilder()
                        .setSelectedSourceDigest("sha256:" + "0".repeat(64))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                SelectedOwnerIntakeSourceProtoCodec.fromResponse(
                    request,
                    response.toBuilder()
                        .setSelectedSourceContent(ByteString.copyFromUtf8("changed"))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                SelectedOwnerIntakeSourceProtoCodec.fromResponse(
                    request, response.toBuilder().clearSelectedSourceContent().build()))
        .isInstanceOf(IllegalArgumentException.class);

    var substitutedScope = scope(Owner.AUTOMATION_SCRIPTING, request.scope().selected());
    var substitutedContent = content(substitutedScope, 0);
    var scopeSubstitution =
        response.toBuilder()
            .setSelectedSourceContent(ByteString.copyFrom(substitutedContent.canonicalBytes()))
            .setSelectedSourceDigest(substitutedContent.digest())
            .build();
    assertThatThrownBy(
            () -> SelectedOwnerIntakeSourceProtoCodec.fromResponse(request, scopeSubstitution))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void admitsContentAboveDefaultGrpcMessageSizeAndRejectsOversizedWireEnvelopes() {
    var request =
        request(
            Owner.AUTOMATION_SCRIPTING,
            binding(COMMIT, SelectedOwnerIntakeSourceTestFixtures.selectedRevisionPayload()));
    var largeContent = content(request.scope(), 6_500_000);
    var response = SelectedOwnerIntakeSourceProtoCodec.toResponse(request, largeContent);
    assertThat(response.getSerializedSize()).isGreaterThan(4 * 1024 * 1024);
    assertThat(response.getSerializedSize())
        .isLessThanOrEqualTo(SelectedOwnerIntakeSourceProtoCodec.MAX_WIRE_BYTES);
    assertThat(SelectedOwnerIntakeSourceProtoCodec.fromResponse(request, response).digest())
        .isEqualTo(largeContent.digest());

    var oversized =
        SelectedOwnerIntakeSourceResponse.newBuilder()
            .setRequest(SelectedOwnerIntakeSourceProtoCodec.toRequest(request))
            .setSelectedSourceContent(
                ByteString.copyFrom(new byte[SelectedOwnerIntakeSourceProtoCodec.MAX_WIRE_BYTES]))
            .setSelectedSourceDigest("sha256:" + "0".repeat(64))
            .build();
    assertThatThrownBy(() -> SelectedOwnerIntakeSourceProtoCodec.fromResponse(request, oversized))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static void assertInvalidRequest(SelectedOwnerIntakeSourceRequest wire) {
    assertThatThrownBy(() -> SelectedOwnerIntakeSourceProtoCodec.fromRequest(wire))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static SelectedOwnerIntakeSourceReadEvidence.Request request(
      Owner owner, DraftCommitBinding selected) {
    return SelectedOwnerIntakeSourceReadEvidence.Request.create(NAMESPACE, scope(owner, selected));
  }

  private static SelectedOwnerIntakeSourceReadScope scope(
      Owner owner, DraftCommitBinding selected) {
    return new SelectedOwnerIntakeSourceReadScope(
        owner, NAMESPACE, OPERATION, FENCE, INTAKE, ACTOR, selected);
  }

  private static SelectedOwnerIntakeSourceContent content(
      SelectedOwnerIntakeSourceReadScope scope, int largeCommandMarkerSize) {
    var selected = scope.selected();
    var gameplay =
        new GameplayRuleSelectedSource(
            GameplayRuleManifest.canonical(
                Map.of(
                    "schema", "game-design-gameplay-rule-source-snapshot/v1",
                    "bindingJson", selected.canonicalJson(),
                    "bindingDigest", selected.digest(),
                    "sourceEpoch", "0",
                    "inheritedCommitId", "",
                    "genesisReceiptId", GENESIS.toString(),
                    "manifestJson", GameplayRuleManifest.explicitEmpty().canonicalJson(),
                    "entries", List.of())));
    var template = new TemplateConfigSourceSnapshot(selected, "0", null, GENESIS, List.of());
    var out = new ByteArrayOutputStream();
    frame(out, SelectedOwnerIntakeSourceContent.DOMAIN);
    frame(out, scope.canonicalBytes());
    frame(out, scope.digest());
    for (String family : FAMILIES) {
      byte[] snapshot =
          switch (family) {
            case "GAMEPLAY_RULE" -> gameplay.canonicalBytes();
            case "TEMPLATE_CONFIG" -> template.canonicalBytes();
            default ->
                SelectedOwnerIntakeSourceTestFixtures.snapshot(
                    family, selected, family.equals("COMMAND") ? largeCommandMarkerSize : 0);
          };
      frame(out, family);
      frame(out, snapshot);
      frame(out, DraftAuthorizationFenceBinding.digest(snapshot));
    }
    byte[] encoded = out.toByteArray();
    return SelectedOwnerIntakeSourceContent.fromStored(
        encoded, scope, DraftAuthorizationFenceBinding.digest(encoded));
  }

  private static DraftCommitBinding binding(UUID commitId, String payload) {
    return DraftCommitBinding.create(
        new DraftCommitBinding.TargetProof(
            TENANT, VERSION, 23L, "tenant", 42L, "tenant", "NEW_GAME_ROW"),
        REQUEST_ID,
        commitId,
        "base-commit-0",
        List.of(
            new DraftCommitBinding.RevisionPayload(
                "0", REVISION, Owner.GAME_DESIGN_CONTROL_PLANE, payload)),
        List.of(
            new DraftCommitBinding.AffectedUnit(
                Owner.GAME_DESIGN_CONTROL_PLANE,
                "TEMPLATE_CONFIG",
                VERSION.toString(),
                "TEMPLATE_CONFIG",
                "ALL",
                "0")));
  }

  private static UnknownFieldSet unknownFields() {
    return UnknownFieldSet.newBuilder()
        .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
        .build();
  }

  private static void frame(ByteArrayOutputStream out, String value) {
    DraftAuthorizationFenceBinding.frame(out, value);
  }

  private static void frame(ByteArrayOutputStream out, byte[] value) {
    DraftAuthorizationFenceBinding.frame(out, value);
  }

  private static UUID id(String value) {
    return UUID.fromString(value);
  }
}
