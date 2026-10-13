package net.firedevops.firemud.common.gamelogic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.gamelogic.v1.GetDraftDesignDigestRequest;
import net.firedevops.firemud.gamelogic.v1.GetDraftDesignDigestResponse;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.junit.jupiter.api.Test;

class GameLogicPublicationSourceReadGrpcCodecTest {
  private static final UUID TENANT = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID VERSION = uuid("22222222-2222-4222-8222-222222222222");
  private static final UUID ACTOR = uuid("33333333-3333-4333-8333-333333333333");
  private static final UUID OPERATION = uuid("44444444-4444-4444-8444-444444444444");
  private static final UUID FENCE = uuid("55555555-5555-4555-8555-555555555555");
  private static final UUID INTAKE_REQUEST = uuid("66666666-6666-4666-8666-666666666666");
  private static final UUID COMMIT = uuid("77777777-7777-4777-8777-777777777777");
  private static final String NAMESPACE = "test";

  @Test
  void roundTripsExactFullVersionRequestAndRetainedResponse() {
    Fixture fixture = fixture(OPERATION, FENCE, COMMIT, TENANT, 41, NAMESPACE);

    GetDraftDesignDigestRequest request =
        GameLogicPublicationSourceReadGrpcCodec.toRequest(fixture.binding());
    assertThat(request.getTenantId()).isEqualTo(TENANT.toString());
    assertThat(request.getVersionId()).isEqualTo("41");
    assertThat(request.getBaseVersionId()).isEmpty();
    assertThat(request.getPublishRequestId()).isEqualTo("publication-request-1");
    assertThat(request.getDerivedWorkflowIdentity())
        .isEqualTo(fixture.binding().publicationRequest().derivedWorkflowIdentity());
    assertThat(request.getRequestDigest())
        .isEqualTo(fixture.binding().publicationRequest().requestDigest());
    assertThat(request.getSourceReadBinding().toByteArray())
        .containsExactly(fixture.binding().canonicalBytes());
    assertThat(request.getSourceReadBindingDigest()).isEqualTo(fixture.binding().digest());
    assertThat(GameLogicPublicationSourceReadGrpcCodec.fromRequest(request).canonicalBytes())
        .containsExactly(fixture.binding().canonicalBytes());

    GetDraftDesignDigestResponse response =
        GameLogicPublicationSourceReadGrpcCodec.toResponse(fixture.binding(), fixture.terminal());
    var evidence =
        GameLogicPublicationSourceReadGrpcCodec.fromResponse(
            fixture.binding(), response, NAMESPACE);
    assertThat(evidence.binding().canonicalBytes())
        .containsExactly(fixture.binding().canonicalBytes());
    assertThat(evidence.terminal().canonicalBytes())
        .containsExactly(fixture.terminal().canonicalBytes());
    assertThat(evidence.manifestDigest()).isEqualTo(response.getContentDigest());
    assertThat(evidence.abilitySchemaDigest()).isEqualTo(response.getAbilitySchemaDigest());
    assertThat(evidence.digestSchemaVersion()).isEqualTo(1);
    assertThat(evidence.canonicalization()).isEqualTo("RFC8785");

    assertThat(
            GameLogicPublicationSourceReadGrpcCodec.fromResponse(
                    fixture.binding(),
                    response.toBuilder().setError(ErrorDetail.getDefaultInstance()).build())
                .terminal()
                .canonicalBytes())
        .containsExactly(fixture.terminal().canonicalBytes());
  }

  @Test
  void rejectsChangedPublicationRequestIdentityScopeAndUnknownFields() {
    Fixture fixture = fixture(OPERATION, FENCE, COMMIT, TENANT, 41, NAMESPACE);
    GetDraftDesignDigestRequest request =
        GameLogicPublicationSourceReadGrpcCodec.toRequest(fixture.binding());

    assertThatThrownBy(
            () ->
                GameLogicPublicationSourceReadGrpcCodec.fromRequest(
                    request.toBuilder().setDerivedWorkflowIdentity("publish:wrong").build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                GameLogicPublicationSourceReadGrpcCodec.fromRequest(
                    request.toBuilder().setRequestDigest("0".repeat(64)).build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                GameLogicPublicationSourceReadGrpcCodec.fromRequest(
                    request.toBuilder().setScriptPatchVersion("patch-1").build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                GameLogicPublicationSourceReadGrpcCodec.fromRequest(
                    request.toBuilder().setSourceReadBindingDigest("sha256:changed").build()))
        .isInstanceOf(IllegalArgumentException.class);
    byte[] substituted = request.getSourceReadBinding().toByteArray();
    substituted[0] ^= 1;
    assertThatThrownBy(
            () ->
                GameLogicPublicationSourceReadGrpcCodec.fromRequest(
                    request.toBuilder()
                        .setSourceReadBinding(ByteString.copyFrom(substituted))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);

    GetDraftDesignDigestRequest requestWithUnknown =
        request.toBuilder().setUnknownFields(unknownField()).build();
    assertThatThrownBy(
            () -> GameLogicPublicationSourceReadGrpcCodec.fromRequest(requestWithUnknown))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsChangedResponseTenantVersionCommitAndBindingCorrelation() {
    Fixture fixture = fixture(OPERATION, FENCE, COMMIT, TENANT, 41, NAMESPACE);
    GetDraftDesignDigestResponse response =
        GameLogicPublicationSourceReadGrpcCodec.toResponse(fixture.binding(), fixture.terminal());

    for (GetDraftDesignDigestResponse changed :
        List.of(
            response.toBuilder().setTenantId("88888888-8888-4888-8888-888888888888").build(),
            response.toBuilder().setVersionId("42").build(),
            response.toBuilder().setAppliedCommitId("88888888-8888-4888-8888-888888888888").build(),
            response.toBuilder().setBaseVersionId("42").build(),
            response.toBuilder().setScriptPatchVersion("patch-1").build(),
            response.toBuilder().setSourceReadBindingDigest("sha256:changed").build())) {
      assertThatThrownBy(
              () ->
                  GameLogicPublicationSourceReadGrpcCodec.fromResponse(fixture.binding(), changed))
          .isInstanceOf(IllegalArgumentException.class);
    }

    byte[] substitutedBinding = response.getSourceReadBinding().toByteArray();
    substitutedBinding[substitutedBinding.length - 1] ^= 1;
    assertThatThrownBy(
            () ->
                GameLogicPublicationSourceReadGrpcCodec.fromResponse(
                    fixture.binding(),
                    response.toBuilder()
                        .setSourceReadBinding(ByteString.copyFrom(substitutedBinding))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(
            () ->
                GameLogicPublicationSourceReadGrpcCodec.fromResponse(
                    fixture.binding(),
                    response.toBuilder().setUnknownFields(unknownField()).build()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsChangedTerminalDigestOperationFenceCommitAndNamespace() {
    Fixture fixture = fixture(OPERATION, FENCE, COMMIT, TENANT, 41, NAMESPACE);
    GetDraftDesignDigestResponse response =
        GameLogicPublicationSourceReadGrpcCodec.toResponse(fixture.binding(), fixture.terminal());

    assertThatThrownBy(
            () ->
                GameLogicPublicationSourceReadGrpcCodec.fromResponse(
                    fixture.binding(),
                    response.toBuilder().setRetainedIntakeTerminalDigest("sha256:changed").build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                GameLogicPublicationSourceReadGrpcCodec.fromResponse(
                    fixture.binding(), response, "other"))
        .isInstanceOf(IllegalArgumentException.class);

    List<Fixture> substitutedBindings =
        List.of(
            fixture(
                uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"), FENCE, COMMIT, TENANT, 41, NAMESPACE),
            fixture(
                OPERATION,
                uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
                COMMIT,
                TENANT,
                41,
                NAMESPACE),
            fixture(
                OPERATION,
                FENCE,
                uuid("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
                TENANT,
                41,
                NAMESPACE));
    for (Fixture substituted : substitutedBindings) {
      GetDraftDesignDigestResponse changed =
          GameLogicPublicationSourceReadGrpcCodec.toResponse(
              substituted.binding(), substituted.terminal());
      assertThatThrownBy(
              () ->
                  GameLogicPublicationSourceReadGrpcCodec.fromResponse(fixture.binding(), changed))
          .isInstanceOf(IllegalArgumentException.class);
    }

    for (Fixture substitutedTerminal : substitutedBindings) {
      GetDraftDesignDigestResponse changedTerminal =
          response.toBuilder()
              .setRetainedIntakeTerminal(
                  ByteString.copyFrom(substitutedTerminal.terminal().canonicalBytes()))
              .setRetainedIntakeTerminalDigest(substitutedTerminal.terminal().digest())
              .build();
      assertThatThrownBy(
              () ->
                  GameLogicPublicationSourceReadGrpcCodec.fromResponse(
                      fixture.binding(), changedTerminal))
          .isInstanceOf(IllegalArgumentException.class);
    }

    Fixture wrongNamespace = fixture(OPERATION, FENCE, COMMIT, TENANT, 41, "other");
    GetDraftDesignDigestResponse wrongNamespaceResponse =
        GameLogicPublicationSourceReadGrpcCodec.toResponse(
            wrongNamespace.binding(), wrongNamespace.terminal());
    assertThatThrownBy(
            () ->
                GameLogicPublicationSourceReadGrpcCodec.fromResponse(
                    wrongNamespace.binding(), wrongNamespaceResponse, NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsMissingAbortedMalformedOrOversizedTerminalAndNonemptyErrors() {
    Fixture fixture = fixture(OPERATION, FENCE, COMMIT, TENANT, 41, NAMESPACE);
    GetDraftDesignDigestResponse response =
        GameLogicPublicationSourceReadGrpcCodec.toResponse(fixture.binding(), fixture.terminal());

    assertThatThrownBy(
            () ->
                GameLogicPublicationSourceReadGrpcCodec.fromResponse(
                    fixture.binding(), response.toBuilder().clearRetainedIntakeTerminal().build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                GameLogicPublicationSourceReadGrpcCodec.fromResponse(
                    fixture.binding(),
                    response.toBuilder()
                        .setRetainedIntakeTerminal(ByteString.copyFrom(new byte[] {1, 2, 3}))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                GameLogicPublicationSourceReadGrpcCodec.fromResponse(
                    fixture.binding(),
                    response.toBuilder()
                        .setRetainedIntakeTerminal(
                            ByteString.copyFrom(
                                new byte
                                    [GameLogicPublicationSourceReadGrpcCodec.MAX_TERMINAL_BYTES
                                        + 1]))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                GameLogicPublicationSourceReadGrpcCodec.fromResponse(
                    fixture.binding(),
                    response.toBuilder()
                        .setError(ErrorDetail.newBuilder().setCode("FAILED_PRECONDITION").build())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                GameLogicPublicationSourceReadGrpcCodec.toResponse(
                    fixture.binding(),
                    GameLogicGameplayRuleIntakeTerminal.aborted(
                        new GameLogicGameplayRuleIntakeOperation(
                            NAMESPACE, fixture.binding().authorization()))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsChangedManifestAbilitySchemaVersionAndCanonicalization() {
    Fixture fixture = fixture(OPERATION, FENCE, COMMIT, TENANT, 41, NAMESPACE);
    GetDraftDesignDigestResponse response =
        GameLogicPublicationSourceReadGrpcCodec.toResponse(fixture.binding(), fixture.terminal());

    for (GetDraftDesignDigestResponse changed :
        List.of(
            response.toBuilder().setContentDigest("sha256:changed").build(),
            response.toBuilder().setAbilitySchemaDigest("sha256:changed").build(),
            response.toBuilder().setDigestSchemaVersion(2).build(),
            response.toBuilder().setCanonicalization("OTHER").build())) {
      assertThatThrownBy(
              () ->
                  GameLogicPublicationSourceReadGrpcCodec.fromResponse(fixture.binding(), changed))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void enforcesBindingByteLimitBeforeDecodingAndRejectsOversizedWireMessages() {
    Fixture fixture = fixture(OPERATION, FENCE, COMMIT, TENANT, 41, NAMESPACE);
    GetDraftDesignDigestRequest oversizedBinding =
        GameLogicPublicationSourceReadGrpcCodec.toRequest(fixture.binding()).toBuilder()
            .setSourceReadBinding(
                ByteString.copyFrom(
                    new byte[GameLogicPublicationSourceReadGrpcCodec.MAX_BINDING_BYTES + 1]))
            .build();
    assertThatThrownBy(() -> GameLogicPublicationSourceReadGrpcCodec.fromRequest(oversizedBinding))
        .isInstanceOf(IllegalArgumentException.class);

    GetDraftDesignDigestRequest oversizedWire =
        GetDraftDesignDigestRequest.newBuilder()
            .setTenantId("x".repeat(GameLogicPublicationSourceReadGrpcCodec.MAX_WIRE_BYTES))
            .build();
    assertThatThrownBy(() -> GameLogicPublicationSourceReadGrpcCodec.fromRequest(oversizedWire))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static Fixture fixture(
      UUID operationId,
      UUID fenceId,
      UUID commitId,
      UUID tenantId,
      long versionRowId,
      String namespace) {
    var publication =
        PublicationDigestRequestBinding.full(
            tenantId.toString(), Long.toString(versionRowId), "publication-request-1");
    var authorization = authorization(operationId, fenceId, commitId, tenantId, versionRowId);
    var binding = new GameLogicPublicationSourceReadBinding(publication, authorization);
    var terminal =
        GameLogicGameplayRuleIntakeTerminal.retained(
            new GameLogicGameplayRuleIntakeOperation(namespace, authorization),
            authorization.source().canonicalBytes(),
            authorization
                .source()
                .manifest()
                .canonicalJson()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return new Fixture(binding, terminal);
  }

  private static GameLogicIntakeAuthorizationBinding authorization(
      UUID operation, UUID fence, UUID commit, UUID tenant, long versionRowId) {
    DraftCommitBinding selected = selectedBinding(tenant, commit, versionRowId);
    var source =
        new GameplayRuleSelectedSource(
            GameplayRuleManifest.canonical(
                Map.of(
                    "schema",
                    "game-design-gameplay-rule-source-snapshot/v1",
                    "bindingJson",
                    selected.canonicalJson(),
                    "bindingDigest",
                    selected.digest(),
                    "sourceEpoch",
                    "1",
                    "inheritedCommitId",
                    "",
                    "genesisReceiptId",
                    "99999999-9999-4999-8999-999999999999",
                    "manifestJson",
                    GameplayRuleManifest.explicitEmpty().canonicalJson(),
                    "entries",
                    List.of())));
    var evidence =
        new DraftAuthorizationFenceBinding.SourceEvidence(
            DraftAuthorizationFenceBinding.SourceKind.ACCOUNT,
            ACTOR.toString(),
            "1",
            "1",
            null,
            null,
            new byte[] {1, 2, 3});
    return new GameLogicIntakeAuthorizationBinding(
        operation, fence, INTAKE_REQUEST, ACTOR, source, List.of(evidence));
  }

  private static DraftCommitBinding selectedBinding(UUID tenant, UUID commit, long versionRowId) {
    var target =
        new DraftCommitBinding.TargetProof(
            tenant, VERSION, versionRowId, "private-tenant", 7, "private-tenant", "NEW_GAME_ROW");
    return DraftCommitBinding.create(
        target,
        uuid("dddddddd-dddd-4ddd-8ddd-dddddddddddd"),
        commit,
        "genesis",
        List.of(
            new DraftCommitBinding.RevisionPayload(
                "0",
                uuid("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
                DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                "{}")),
        List.of(
            new DraftCommitBinding.AffectedUnit(
                DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                "GAMEPLAY_RULE_SET",
                VERSION.toString(),
                "GAMEPLAY_RULE_SET",
                "effective",
                "0")));
  }

  private static UnknownFieldSet unknownField() {
    return UnknownFieldSet.newBuilder()
        .addField(100, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
        .build();
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private record Fixture(
      GameLogicPublicationSourceReadBinding binding,
      GameLogicGameplayRuleIntakeTerminal terminal) {}
}
