package net.firedevops.firemud.common.gamelogic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.gamelogic.v1.RetainGameplayRuleIntakeRequest;
import net.firedevops.firemud.gamelogic.v1.RetainGameplayRuleIntakeResponse;
import org.junit.jupiter.api.Test;

class GameLogicIntakeRetainProtoCodecTest {
  private static final String NAMESPACE = "test";

  @Test
  void roundTripsExactAuthorizationAndCompleteRetainedTerminal() {
    var authorization = authorization();
    var request = GameLogicIntakeRetainEvidence.Request.create(NAMESPACE, authorization);
    var decodedRequest =
        GameLogicIntakeRetainProtoCodec.fromRequest(
            GameLogicIntakeRetainProtoCodec.toRequest(request));
    assertThat(decodedRequest).isEqualTo(request);
    assertThat(decodedRequest.hashCode()).isEqualTo(request.hashCode());
    assertThat(decodedRequest.originalAuthorizationBytes())
        .containsExactly(authorization.canonicalBytes());

    var source = authorization.source();
    var terminal =
        GameLogicGameplayRuleIntakeTerminal.retained(
            new GameLogicGameplayRuleIntakeOperation(NAMESPACE, authorization),
            source.canonicalBytes(),
            source.manifest().canonicalJson().getBytes(StandardCharsets.UTF_8));
    var evidence = new GameLogicIntakeRetainEvidence(request, terminal);
    var response = GameLogicIntakeRetainProtoCodec.toResponse(evidence);
    var decoded = GameLogicIntakeRetainProtoCodec.fromResponse(request, response);
    assertThat(response.getRequest()).isEqualTo(GameLogicIntakeRetainProtoCodec.toRequest(request));
    assertThat(decoded.terminal().canonicalBytes()).containsExactly(terminal.canonicalBytes());
    assertThat(response.getSerializedSize())
        .isLessThanOrEqualTo(GameLogicIntakeRetainProtoCodec.MAX_WIRE_BYTES);
    assertThat(GameLogicGameplayRuleIntakeTerminal.MAX_CANONICAL_BYTES).isEqualTo(16 * 1024 * 1024);
    assertThat(GameLogicIntakeRetainProtoCodec.MAX_WIRE_BYTES).isEqualTo(24 * 1024 * 1024);
  }

  @Test
  void rejectsUnknownFieldsChangedRequestEchoChangedBindingAndTerminalEvidence() {
    var authorization = authorization();
    var request = GameLogicIntakeRetainEvidence.Request.create(NAMESPACE, authorization);
    var terminal =
        GameLogicGameplayRuleIntakeTerminal.aborted(
            new GameLogicGameplayRuleIntakeOperation(NAMESPACE, authorization));
    var response =
        GameLogicIntakeRetainProtoCodec.toResponse(
            new GameLogicIntakeRetainEvidence(request, terminal));

    var requestWithUnknown =
        GameLogicIntakeRetainProtoCodec.toRequest(request).toBuilder()
            .setUnknownFields(unknownFields())
            .build();
    assertThatThrownBy(() -> GameLogicIntakeRetainProtoCodec.fromRequest(requestWithUnknown))
        .isInstanceOf(IllegalArgumentException.class);

    var changedAuthorization =
        new GameLogicIntakeAuthorizationBinding(
            authorization.operationId(),
            UUID.randomUUID(),
            authorization.intakeRequestId(),
            authorization.actorAccountId(),
            authorization.source(),
            authorization.sources());
    var changedRequest =
        new GameLogicIntakeRetainEvidence.Request(
            1, NAMESPACE, request.correlationId(), changedAuthorization);
    assertThatThrownBy(() -> GameLogicIntakeRetainProtoCodec.fromResponse(changedRequest, response))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new GameLogicIntakeRetainEvidence(request, terminalFor(changedAuthorization)))
        .isInstanceOf(IllegalArgumentException.class);

    var badTerminalDigest =
        response.toBuilder().setTerminalDigest("sha256:" + "0".repeat(64)).build();
    assertThatThrownBy(
            () -> GameLogicIntakeRetainProtoCodec.fromResponse(request, badTerminalDigest))
        .isInstanceOf(IllegalArgumentException.class);

    byte[] changedBytes = response.getOriginalTerminal().toByteArray();
    changedBytes[changedBytes.length - 1] ^= 1;
    var changedTerminal =
        response.toBuilder().setOriginalTerminal(ByteString.copyFrom(changedBytes)).build();
    assertThatThrownBy(() -> GameLogicIntakeRetainProtoCodec.fromResponse(request, changedTerminal))
        .isInstanceOf(IllegalArgumentException.class);

    var responseWithUnknown = response.toBuilder().setUnknownFields(unknownFields()).build();
    assertThatThrownBy(
            () -> GameLogicIntakeRetainProtoCodec.fromResponse(request, responseWithUnknown))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsTerminalForAnotherNamespaceEvenWhenAuthorizationBytesMatch() {
    var authorization = authorization();
    var request = GameLogicIntakeRetainEvidence.Request.create("other", authorization);
    var terminalForWrongNamespace =
        GameLogicGameplayRuleIntakeTerminal.aborted(
            new GameLogicGameplayRuleIntakeOperation(NAMESPACE, authorization));

    assertThatThrownBy(() -> new GameLogicIntakeRetainEvidence(request, terminalForWrongNamespace))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("namespace");
  }

  @Test
  void rejectsOversizedAuthorizationAndTerminalAtTheirCanonicalLimits() {
    var authorization = authorization();
    var request = GameLogicIntakeRetainEvidence.Request.create(NAMESPACE, authorization);
    var oversizedAuthorization =
        RetainGameplayRuleIntakeRequest.newBuilder()
            .setSchemaVersion(1)
            .setTargetNamespace(NAMESPACE)
            .setCorrelationId(UUID.randomUUID().toString())
            .setOriginalIntakeAuthorization(
                ByteString.copyFrom(
                    new byte[GameLogicIntakeRetainEvidence.MAX_AUTHORIZATION_BYTES + 1]))
            .setIntakeAuthorizationDigest(authorization.digest())
            .build();
    assertThatThrownBy(() -> GameLogicIntakeRetainProtoCodec.fromRequest(oversizedAuthorization))
        .isInstanceOf(IllegalArgumentException.class);

    var oversizedTerminal =
        RetainGameplayRuleIntakeResponse.newBuilder()
            .setRequest(GameLogicIntakeRetainProtoCodec.toRequest(request))
            .setOriginalTerminal(
                ByteString.copyFrom(
                    new byte[GameLogicGameplayRuleIntakeTerminal.MAX_CANONICAL_BYTES + 1]))
            .setTerminalDigest("sha256:" + "0".repeat(64))
            .build();
    assertThat(oversizedTerminal.getSerializedSize())
        .isLessThanOrEqualTo(GameLogicIntakeRetainProtoCodec.MAX_WIRE_BYTES);
    assertThatThrownBy(
            () -> GameLogicIntakeRetainProtoCodec.fromResponse(request, oversizedTerminal))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void acceptsTerminalAboveDefaultGrpcMessageLimitWithinDeclaredWireLimit() {
    var authorization = largeAuthorization(1800);
    var request = GameLogicIntakeRetainEvidence.Request.create(NAMESPACE, authorization);
    var source = authorization.source();
    var terminal =
        GameLogicGameplayRuleIntakeTerminal.retained(
            new GameLogicGameplayRuleIntakeOperation(NAMESPACE, authorization),
            source.canonicalBytes(),
            source.manifest().canonicalJson().getBytes(StandardCharsets.UTF_8));
    var response =
        GameLogicIntakeRetainProtoCodec.toResponse(
            new GameLogicIntakeRetainEvidence(request, terminal));

    assertThat(terminal.canonicalBytes().length).isGreaterThan(4 * 1024 * 1024);
    assertThat(response.getSerializedSize())
        .isLessThanOrEqualTo(GameLogicGameplayRuleIntakeTerminal.MAX_WIRE_BYTES);
    assertThat(
            GameLogicIntakeRetainProtoCodec.fromResponse(request, response)
                .terminal()
                .canonicalBytes())
        .containsExactly(terminal.canonicalBytes());
  }

  private static UnknownFieldSet unknownFields() {
    return UnknownFieldSet.newBuilder()
        .addField(100, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
        .build();
  }

  private static GameLogicGameplayRuleIntakeTerminal terminalFor(
      GameLogicIntakeAuthorizationBinding authorization) {
    return GameLogicGameplayRuleIntakeTerminal.aborted(
        new GameLogicGameplayRuleIntakeOperation(NAMESPACE, authorization));
  }

  private static GameLogicIntakeAuthorizationBinding authorization() {
    UUID actor = UUID.randomUUID();
    var target =
        new DraftCommitBinding.TargetProof(
            UUID.randomUUID(), UUID.randomUUID(), 1, "private", 2, "private", "NEW_GAME_ROW");
    var selectedBinding =
        DraftCommitBinding.create(
            target,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "genesis",
            List.of(
                new DraftCommitBinding.RevisionPayload(
                    "0",
                    UUID.randomUUID(),
                    DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                    "{}")),
            List.of(
                new DraftCommitBinding.AffectedUnit(
                    DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                    "GAMEPLAY_RULE_SET",
                    target.canonicalVersionId().toString(),
                    "GAMEPLAY_RULE_SET",
                    "effective",
                    "0")));
    var selectedSource =
        new GameplayRuleSelectedSource(
            GameplayRuleManifest.canonical(
                Map.of(
                    "schema",
                    "game-design-gameplay-rule-source-snapshot/v1",
                    "bindingJson",
                    selectedBinding.canonicalJson(),
                    "bindingDigest",
                    selectedBinding.digest(),
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
    var accountSource =
        new DraftAuthorizationFenceBinding.SourceEvidence(
            DraftAuthorizationFenceBinding.SourceKind.ACCOUNT,
            actor.toString(),
            "1",
            "1",
            null,
            null,
            new byte[] {1, 2, 3});
    return new GameLogicIntakeAuthorizationBinding(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        actor,
        selectedSource,
        List.of(accountSource));
  }

  static GameLogicIntakeAuthorizationBinding largeAuthorization(int entryCount) {
    UUID actor = UUID.randomUUID();
    UUID tenant = UUID.randomUUID();
    UUID version = UUID.randomUUID();
    var target =
        new DraftCommitBinding.TargetProof(
            tenant, version, 1, "private", 2, "private", "NEW_GAME_ROW");
    var requestId = UUID.randomUUID();
    var definitions = new ArrayList<GameplayRuleManifest.Definition>();
    var revisions = new ArrayList<DraftCommitBinding.RevisionPayload>();
    var revisionIds = new ArrayList<UUID>();
    for (int index = 0; index < entryCount; index++) {
      var definition =
          new GameplayRuleManifest.AdmissionTag(
              String.format(java.util.Locale.ROOT, "tag-%05d", index));
      definitions.add(definition);
      var revisionId = UUID.randomUUID();
      revisionIds.add(revisionId);
      revisions.add(
          new DraftCommitBinding.RevisionPayload(
              "0",
              revisionId,
              DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
              GameplayRuleSourceRevision.upsertPayload(definition)));
    }
    var selectedBinding =
        DraftCommitBinding.create(
            target,
            requestId,
            UUID.randomUUID(),
            "genesis",
            List.of(revisions.getFirst()),
            List.of(
                new DraftCommitBinding.AffectedUnit(
                    DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                    "GAMEPLAY_RULE_SET",
                    version.toString(),
                    "GAMEPLAY_RULE_SET",
                    "effective",
                    "0")));
    var families =
        new EnumMap<GameplayRuleManifest.Family, List<GameplayRuleManifest.Definition>>(
            GameplayRuleManifest.Family.class);
    for (var family : GameplayRuleManifest.Family.values()) families.put(family, new ArrayList<>());
    families.get(GameplayRuleManifest.Family.ADMISSION_TAGS).addAll(definitions);
    var manifest = new GameplayRuleManifest(families);
    var entries = new ArrayList<Map<String, Object>>();
    for (int index = 0; index < entryCount; index++) {
      var definition = definitions.get(index);
      var entryBinding =
          DraftCommitBinding.create(
              target,
              requestId,
              UUID.randomUUID(),
              "genesis",
              List.of(revisions.get(index)),
              List.of(
                  new DraftCommitBinding.AffectedUnit(
                      DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                      "GAMEPLAY_RULE_SET",
                      version.toString(),
                      "GAMEPLAY_RULE_SET",
                      "effective",
                      Integer.toString(index))));
      entries.add(
          Map.of(
              "family", definition.family().name(),
              "definitionJson", GameplayRuleManifest.canonical(definition),
              "sourceBindingJson", entryBinding.canonicalJson(),
              "sourceBindingDigest", entryBinding.digest(),
              "revisionOrder", "0",
              "revisionId", revisionIds.get(index).toString()));
    }
    String snapshot =
        GameplayRuleManifest.canonical(
            Map.of(
                "schema",
                "game-design-gameplay-rule-source-snapshot/v1",
                "bindingJson",
                selectedBinding.canonicalJson(),
                "bindingDigest",
                selectedBinding.digest(),
                "sourceEpoch",
                "1",
                "inheritedCommitId",
                "",
                "genesisReceiptId",
                UUID.randomUUID().toString(),
                "manifestJson",
                manifest.canonicalJson(),
                "entries",
                entries));
    var source = new GameplayRuleSelectedSource(snapshot);
    var accountSource =
        new DraftAuthorizationFenceBinding.SourceEvidence(
            DraftAuthorizationFenceBinding.SourceKind.ACCOUNT,
            actor.toString(),
            "1",
            "1",
            null,
            null,
            new byte[] {1, 2, 3});
    return new GameLogicIntakeAuthorizationBinding(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        actor,
        source,
        List.of(accountSource));
  }
}
