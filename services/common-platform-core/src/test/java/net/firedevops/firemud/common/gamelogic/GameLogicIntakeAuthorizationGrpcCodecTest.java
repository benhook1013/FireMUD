package net.firedevops.firemud.common.gamelogic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import org.junit.jupiter.api.Test;

class GameLogicIntakeAuthorizationGrpcCodecTest {
  private static final String CREDENTIAL = "unchanged.creator.secret";

  @Test
  void roundTripsExactSelectionAndAllRecoveryPhasesWithoutCredentialInWire() {
    var request = request();
    var wireRequest = GameLogicIntakeAuthorizationGrpcCodec.toRequest(request);
    assertThat(GameLogicIntakeAuthorizationGrpcCodec.fromRequest(wireRequest).selected())
        .isEqualTo(request.selected());
    var credentialBytes = CREDENTIAL.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    assertThat(containsSequence(wireRequest.toByteArray(), credentialBytes)).isFalse();

    var finalized = authorization(request);
    var finalizedResult =
        GameLogicIntakeAuthorizationEvidence.Result.authorized(request, finalized);
    var finalizedWire = GameLogicIntakeAuthorizationGrpcCodec.toResponse(finalizedResult);
    var decoded = GameLogicIntakeAuthorizationGrpcCodec.fromResponse(request, finalizedWire);
    assertThat(decoded.outcome()).isEqualTo(GameLogicIntakeAuthorizationEvidence.Outcome.FINALIZED);
    assertThat(decoded.authorization().orElseThrow().canonicalBytes())
        .containsExactly(finalized.canonicalBytes());
    assertThat(containsSequence(finalizedWire.toByteArray(), credentialBytes)).isFalse();

    var scope = scope(request);
    for (var outcome :
        List.of(
            GameLogicIntakeAuthorizationEvidence.Outcome.RESERVED,
            GameLogicIntakeAuthorizationEvidence.Outcome.ABORTED)) {
      var result =
          new GameLogicIntakeAuthorizationEvidence.Result(
              request, outcome, Optional.of(scope), Optional.empty());
      var response = GameLogicIntakeAuthorizationGrpcCodec.toResponse(result);
      assertThat(
              GameLogicIntakeAuthorizationGrpcCodec.fromResponse(request, response).sourceScope())
          .contains(scope);
    }
    var finalizedRecovery =
        new GameLogicIntakeAuthorizationEvidence.Result(
            request,
            GameLogicIntakeAuthorizationEvidence.Outcome.FINALIZED,
            Optional.of(scope),
            Optional.of(authorization(scope)));
    assertThat(
            GameLogicIntakeAuthorizationGrpcCodec.fromResponse(
                    request, GameLogicIntakeAuthorizationGrpcCodec.toResponse(finalizedRecovery))
                .sourceScope())
        .contains(scope);
  }

  @Test
  void rejectsUnknownChangedAndNoncanonicalEvidenceAndCredentialInputs() {
    var request = request();
    var wireRequest = GameLogicIntakeAuthorizationGrpcCodec.toRequest(request);
    var response =
        GameLogicIntakeAuthorizationGrpcCodec.toResponse(
            GameLogicIntakeAuthorizationEvidence.Result.authorized(
                request, authorization(request)));
    assertThatThrownBy(
            () ->
                GameLogicIntakeAuthorizationGrpcCodec.fromRequest(
                    wireRequest.toBuilder()
                        .setUnknownFields(
                            UnknownFieldSet.newBuilder()
                                .addField(
                                    99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
                                .build())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                GameLogicIntakeAuthorizationGrpcCodec.fromResponse(
                    request,
                    response.toBuilder()
                        .setRequest(
                            wireRequest.toBuilder()
                                .setTransportRequestId(UUID.randomUUID().toString())
                                .build())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                GameLogicIntakeAuthorizationGrpcCodec.fromResponse(
                    request,
                    response.toBuilder()
                        .setRequest(
                            wireRequest.toBuilder()
                                .setSelectedDraftBinding(ByteString.copyFromUtf8("{}"))
                                .build())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                GameLogicIntakeAuthorizationGrpcCodec.fromResponse(
                    request, response.toBuilder().setOutcomeValue(99).build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                GameLogicIntakeAuthorizationGrpcCodec.fromRequest(
                    wireRequest.toBuilder()
                        .setSelectedDraftBinding(
                            ByteString.copyFrom(
                                new byte
                                    [GameLogicIntakeAuthorizationEvidence.MAX_SELECTION_BYTES + 1]))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountGameLogicIntakeAuthorizationCredentials.Credential.of("contains whitespace"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new GameLogicIntakeAuthorizationEvidence.Result(
                    request,
                    GameLogicIntakeAuthorizationEvidence.Outcome.FINALIZED,
                    Optional.of(scope(request)),
                    Optional.of(authorization(request))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsResponsesOverTheExplicitTwentyFourMiBWireBudget() {
    var request = request();
    var response =
        GameLogicIntakeAuthorizationGrpcCodec.toResponse(
            GameLogicIntakeAuthorizationEvidence.Result.authorized(
                request, authorization(request)));
    var oversized =
        response.toBuilder()
            .setRequest(
                GameLogicIntakeAuthorizationGrpcCodec.toRequest(request).toBuilder()
                    .setSelectedDraftBinding(
                        ByteString.copyFrom(
                            new byte[GameLogicIntakeAuthorizationGrpcCodec.MAX_WIRE_BYTES]))
                    .build())
            .build();
    assertThatThrownBy(() -> GameLogicIntakeAuthorizationGrpcCodec.fromResponse(request, oversized))
        .isInstanceOf(IllegalArgumentException.class);
  }

  static GameLogicIntakeAuthorizationEvidence.Request request() {
    return GameLogicIntakeAuthorizationEvidence.Request.create(
        "test", UUID.randomUUID(), selected());
  }

  static DraftCommitBinding selected() {
    UUID tenant = UUID.randomUUID(), version = UUID.randomUUID();
    var target =
        new DraftCommitBinding.TargetProof(
            tenant, version, 1, "private", 2, "private", "NEW_GAME_ROW");
    return DraftCommitBinding.create(
        target,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "base-1",
        List.of(
            new DraftCommitBinding.RevisionPayload(
                "0", UUID.randomUUID(), DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE, "{}")),
        List.of(
            new DraftCommitBinding.AffectedUnit(
                DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                "GAMEPLAY_RULE_SET",
                version.toString(),
                "GAMEPLAY_RULE_SET",
                "effective",
                "0")));
  }

  static GameLogicIntakeAuthorizationBinding authorization(
      GameLogicIntakeAuthorizationEvidence.Request request) {
    return authorization(scope(request));
  }

  static GameLogicIntakeAuthorizationBinding authorization(GameLogicIntakeSourceReadScope scope) {
    var selected = scope.selected();
    UUID actor = scope.actorAccountId();
    var source =
        new GameplayRuleSelectedSource(
            GameplayRuleManifest.canonical(
                java.util.Map.of(
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
                    UUID.randomUUID().toString(),
                    "manifestJson",
                    GameplayRuleManifest.explicitEmpty().canonicalJson(),
                    "entries",
                    List.of())));
    return new GameLogicIntakeAuthorizationBinding(
        scope.operationId(),
        scope.fenceId(),
        scope.intakeRequestId(),
        actor,
        source,
        List.of(
            new DraftAuthorizationFenceBinding.SourceEvidence(
                DraftAuthorizationFenceBinding.SourceKind.ACCOUNT,
                actor.toString(),
                "1",
                "1",
                null,
                null,
                new byte[] {1})));
  }

  static GameLogicIntakeSourceReadScope scope(
      GameLogicIntakeAuthorizationEvidence.Request request) {
    return new GameLogicIntakeSourceReadScope(
        request.targetNamespace(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        request.intakeRequestId(),
        UUID.randomUUID(),
        request.selected(),
        "spiffe://firemud/ns/" + request.targetNamespace() + "/sa/account-service",
        GameLogicIntakeSourceReadScope.PURPOSE);
  }

  private static boolean containsSequence(byte[] bytes, byte[] sequence) {
    for (int start = 0; start <= bytes.length - sequence.length; start++) {
      int index = 0;
      while (index < sequence.length && bytes[start + index] == sequence[index]) index++;
      if (index == sequence.length) return true;
    }
    return false;
  }
}
