package net.firedevops.firemud.gamelogic.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.gamelogic.service.GameLogicDraftDesignDigestService;
import net.firedevops.firemud.gamelogic.service.impl.GameLogicDraftDesignDigestServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class GameLogicRuleInputManifestTest {
  private static final String EMPTY_INTENT_JSON =
      "{\"intentKind\":\"EMPTY_RULE_INPUT_MANIFEST/v1\","
          + "\"manifest\":{\"abilitySchemas\":[],\"ruleInputs\":[],\"schemaVersion\":1}}";
  private static final String CONTENT_DIGEST_PREIMAGE =
      "{\"abilitySchemas\":[],\"digestSchemaVersion\":1,\"ruleInputs\":[]}";
  private static final String CONTENT_DIGEST_VECTOR =
      "71b4da6a96f68a6f85d48731f5b4e448d05d21241ca75016ef0c9a0000c14f74";
  private static final String ABILITY_SCHEMA_DIGEST_PREIMAGE =
      "{\"abilitySchemaDigestDomain\":\"firemud.game-logic.ability-schema/v1\","
          + "\"abilitySchemaVersion\":1,\"abilitySchemas\":[]}";
  private static final String ABILITY_SCHEMA_DIGEST_VECTOR =
      "a6c1b6d52654bce002ddb64de2d84853061aa87cb15407547038cc3d4e7cfbd4";

  @Test
  void canonicalEmptyIntentAndBothDigestPreimagesMatchTheirGoldenVectors() {
    GameLogicRuleInputManifest manifest =
        GameLogicRuleInputManifest.fromExplicitEmptyIntent(
            GameLogicRuleInputManifestFixtures.fullBinding());

    assertThat(manifest.ownerIntentJson()).isEqualTo(EMPTY_INTENT_JSON);
    assertThat(manifest.manifestJson())
        .isEqualTo("{\"abilitySchemas\":[],\"ruleInputs\":[],\"schemaVersion\":1}");
    assertThat(sha256Hex(CONTENT_DIGEST_PREIMAGE)).isEqualTo(CONTENT_DIGEST_VECTOR);
    assertThat(manifest.contentDigest()).isEqualTo(CONTENT_DIGEST_VECTOR);
    assertThat(manifest.abilitySchemaJson()).isEqualTo(ABILITY_SCHEMA_DIGEST_PREIMAGE);
    assertThat(sha256Hex(ABILITY_SCHEMA_DIGEST_PREIMAGE)).isEqualTo(ABILITY_SCHEMA_DIGEST_VECTOR);
    assertThat(manifest.abilitySchemaDigest()).isEqualTo(ABILITY_SCHEMA_DIGEST_VECTOR);
    assertThat(manifest.digestSchemaVersion()).isEqualTo(1);
    assertThat(manifest.abilitySchemaVersion()).isEqualTo(1);
  }

  @Test
  void retainsTheOriginalCompleteFiveOwnerBindingAndItsExactGameLogicRevision() {
    DraftCommitBinding binding = GameLogicRuleInputManifestFixtures.fullBinding();
    GameLogicRuleInputManifest manifest =
        GameLogicRuleInputManifest.fromExplicitEmptyIntent(binding);

    assertThat(binding.requiredOwners()).containsExactly(Owner.values());
    assertThat(manifest.binding()).isSameAs(binding);
    assertThat(manifest.binding().canonicalJson()).isEqualTo(binding.canonicalJson());
    assertThat(manifest.binding().digest()).isEqualTo(binding.digest());
    assertThat(manifest.binding().canonicalBytes()).containsExactly(binding.canonicalBytes());
    assertThat(manifest.binding().revisions()).containsExactlyElementsOf(binding.revisions());
    assertThat(manifest.binding().affectedUnits())
        .containsExactlyElementsOf(binding.affectedUnits());

    var gameLogicRevision =
        binding.revisions().stream()
            .filter(revision -> revision.owner() == Owner.GAME_LOGIC)
            .findFirst()
            .orElseThrow();
    assertThat(manifest.revisionId()).isEqualTo(gameLogicRevision.revisionId());
    assertThat(gameLogicRevision.payload()).isEqualTo(EMPTY_INTENT_JSON);
    assertThat(manifest.tenantId()).isEqualTo(binding.target().canonicalTenantId());
    assertThat(manifest.versionId()).isEqualTo(binding.target().canonicalVersionId());
    assertThat(manifest.sourceProofJson()).isEqualTo(sourceProofJson(binding.target()));
    assertThat(manifest.requestId()).isEqualTo(binding.requestId());
    assertThat(manifest.commitId()).isEqualTo(binding.commitId());
    assertThat(manifest.baseCommitId()).isEqualTo(binding.baseCommitId());
  }

  @Test
  void semanticDigestsStayStableWhenSourceAndCommitProvenanceChanges() {
    TargetProof originalTarget = GameLogicRuleInputManifestFixtures.target();
    DraftCommitBinding originalBinding =
        GameLogicRuleInputManifestFixtures.fullBinding(
            originalTarget,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "base-original",
            EMPTY_INTENT_JSON,
            1,
            List.of(GameLogicRuleInputManifestFixtures.gameLogicUnit(originalTarget)));
    TargetProof changedTarget =
        GameLogicRuleInputManifestFixtures.targetWithSameCanonicalVersionAndDifferentSource(
            originalTarget);
    DraftCommitBinding changedBinding =
        GameLogicRuleInputManifestFixtures.fullBinding(
            changedTarget,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "base-changed",
            EMPTY_INTENT_JSON,
            1,
            List.of(GameLogicRuleInputManifestFixtures.gameLogicUnit(changedTarget)));

    GameLogicRuleInputManifest original =
        GameLogicRuleInputManifest.fromExplicitEmptyIntent(originalBinding);
    GameLogicRuleInputManifest changed =
        GameLogicRuleInputManifest.fromExplicitEmptyIntent(changedBinding);

    assertThat(changed.contentDigest()).isEqualTo(original.contentDigest());
    assertThat(changed.abilitySchemaDigest()).isEqualTo(original.abilitySchemaDigest());
    assertThat(changed.sourceProofJson()).isNotEqualTo(original.sourceProofJson());
    assertThat(changed.binding().canonicalJson()).isNotEqualTo(original.binding().canonicalJson());
    assertThat(changed.binding().digest()).isNotEqualTo(original.binding().digest());
    assertThat(changed.requestId()).isNotEqualTo(original.requestId());
    assertThat(changed.commitId()).isNotEqualTo(original.commitId());
    assertThat(changed.baseCommitId()).isNotEqualTo(original.baseCommitId());
    assertThat(changed).isNotEqualTo(original);
    // The migrated repository's same-Version changed-binding conflict is covered by its PostgreSQL
    // test; equal semantic hashes do not relax that full-provenance storage comparison.
  }

  @Test
  void rejectsNonemptyOrUnknownRevisionIntent() {
    TargetProof target = GameLogicRuleInputManifestFixtures.target();
    List<String> unsupportedPayloads =
        List.of(
            EMPTY_INTENT_JSON.replace("\"ruleInputs\":[]", "\"ruleInputs\":[{}]"),
            EMPTY_INTENT_JSON.replace("\"abilitySchemas\":[]", "\"abilitySchemas\":[{}]"),
            "{\"intentKind\":\"EMPTY_RULE_INPUT_MANIFEST/v2\","
                + "\"manifest\":{\"abilitySchemas\":[],\"ruleInputs\":[],\"schemaVersion\":1}}",
            "{\"intentKind\":\"EMPTY_RULE_INPUT_MANIFEST/v1\","
                + "\"manifest\":{\"abilitySchemas\":[],\"ruleInputs\":[]}}");

    for (String payload : unsupportedPayloads) {
      DraftCommitBinding binding =
          fullBinding(
              target,
              payload,
              1,
              List.of(GameLogicRuleInputManifestFixtures.gameLogicUnit(target)));
      assertInvalid(binding);
    }
  }

  @Test
  void rejectsExtraOrMissingGameLogicRevisionDeclarations() {
    TargetProof target = GameLogicRuleInputManifestFixtures.target();
    AffectedUnit gameLogicUnit = GameLogicRuleInputManifestFixtures.gameLogicUnit(target);

    assertInvalid(fullBinding(target, EMPTY_INTENT_JSON, 2, List.of(gameLogicUnit)));
    assertInvalid(fullBinding(target, EMPTY_INTENT_JSON, 0, List.of()));

    assertThatThrownBy(() -> fullBinding(target, EMPTY_INTENT_JSON, 1, List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Required owner set must exactly match");
    assertThatThrownBy(() -> fullBinding(target, EMPTY_INTENT_JSON, 0, List.of(gameLogicUnit)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Required owner set must exactly match");
  }

  @Test
  void rejectsExtraOrWrongTypedGameLogicAffectedUnits() {
    TargetProof target = GameLogicRuleInputManifestFixtures.target();
    String versionId = target.canonicalVersionId().toString();
    AffectedUnit correct = GameLogicRuleInputManifestFixtures.gameLogicUnit(target);
    List<AffectedUnit> extra =
        List.of(
            correct,
            new AffectedUnit(
                Owner.GAME_LOGIC,
                GameLogicRuleInputManifest.AGGREGATE_TYPE,
                versionId,
                GameLogicRuleInputManifest.SCOPE_TYPE,
                versionId + "-extra",
                "0"));
    assertInvalid(fullBinding(target, EMPTY_INTENT_JSON, 1, extra));

    assertInvalid(
        fullBinding(
            target,
            EMPTY_INTENT_JSON,
            1,
            List.of(unit(target, "OTHER_AGGREGATE", versionId, "VERSION", versionId, "0"))));
    assertInvalid(
        fullBinding(
            target,
            EMPTY_INTENT_JSON,
            1,
            List.of(
                unit(
                    target,
                    GameLogicRuleInputManifest.AGGREGATE_TYPE,
                    "different-version",
                    "VERSION",
                    versionId,
                    "0"))));
    assertInvalid(
        fullBinding(
            target,
            EMPTY_INTENT_JSON,
            1,
            List.of(
                unit(
                    target,
                    GameLogicRuleInputManifest.AGGREGATE_TYPE,
                    versionId,
                    "TENANT",
                    versionId,
                    "0"))));
    assertInvalid(
        fullBinding(
            target,
            EMPTY_INTENT_JSON,
            1,
            List.of(
                unit(
                    target,
                    GameLogicRuleInputManifest.AGGREGATE_TYPE,
                    versionId,
                    "VERSION",
                    "different-version",
                    "0"))));
    assertInvalid(
        fullBinding(
            target,
            EMPTY_INTENT_JSON,
            1,
            List.of(
                unit(
                    target,
                    GameLogicRuleInputManifest.AGGREGATE_TYPE,
                    versionId,
                    "VERSION",
                    versionId,
                    "1"))));

    AffectedUnit wrongOwner =
        new AffectedUnit(
            Owner.WORLD_MANAGEMENT,
            GameLogicRuleInputManifest.AGGREGATE_TYPE,
            versionId,
            GameLogicRuleInputManifest.SCOPE_TYPE,
            versionId,
            "0");
    assertThatThrownBy(() -> fullBinding(target, EMPTY_INTENT_JSON, 1, List.of(wrongOwner)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Required owner set must exactly match");
  }

  @Test
  void rejectsNonzeroInitialEpoch() {
    TargetProof target = GameLogicRuleInputManifestFixtures.target();
    AffectedUnit stale =
        new AffectedUnit(
            Owner.GAME_LOGIC,
            GameLogicRuleInputManifest.AGGREGATE_TYPE,
            target.canonicalVersionId().toString(),
            GameLogicRuleInputManifest.SCOPE_TYPE,
            target.canonicalVersionId().toString(),
            "2");

    assertInvalid(fullBinding(target, EMPTY_INTENT_JSON, 1, List.of(stale)));
  }

  @Test
  void defaultServiceDeniesBeforeRepositoryMutation() {
    GameLogicRuleInputManifestRepository repository =
        mock(GameLogicRuleInputManifestRepository.class);
    GameLogicEmptyRuleInputManifestService service =
        new GameLogicEmptyRuleInputManifestService(repository);
    DraftCommitBinding binding = GameLogicRuleInputManifestFixtures.fullBinding();

    assertThatThrownBy(() -> service.apply(binding))
        .isInstanceOf(GameLogicEmptyRuleInputManifestService.ApplicationDeniedException.class);
    verifyNoInteractions(repository);
  }

  @Test
  void ambientOwnerTransactionIsRejectedBeforeVerifierOrRepository() {
    GameLogicRuleInputManifestRepository repository =
        mock(GameLogicRuleInputManifestRepository.class);
    GameLogicEmptyRuleInputManifestService service =
        new GameLogicEmptyRuleInputManifestService(
            repository,
            ignored -> {
              throw new AssertionError("Verifier must not run inside an ambient transaction");
            });
    DraftCommitBinding binding = GameLogicRuleInputManifestFixtures.fullBinding();
    boolean wasTransactionActive = TransactionSynchronizationManager.isActualTransactionActive();

    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThatThrownBy(() -> service.apply(binding))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("outside an ambient transaction");
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(wasTransactionActive);
    }
    verifyNoInteractions(repository);
  }

  @Test
  void ownerStorageAndApplyRemainUnregisteredAndCannotEnablePublicDigest() {
    assertThat(GameLogicEmptyRuleInputManifestService.class.isAnnotationPresent(Component.class))
        .isFalse();
    assertThat(GameLogicEmptyRuleInputManifestService.class.isAnnotationPresent(Service.class))
        .isFalse();
    assertThat(GameLogicRuleInputManifestRepository.class.isAnnotationPresent(Component.class))
        .isFalse();
    assertThat(GameLogicRuleInputManifestRepository.class.isAnnotationPresent(Repository.class))
        .isFalse();

    DraftCommitBinding binding = GameLogicRuleInputManifestFixtures.fullBinding();
    GameLogicRuleInputManifest syntheticStorageResult =
        GameLogicRuleInputManifest.fromExplicitEmptyIntent(binding);
    assertThat(syntheticStorageResult.ownerResultJson()).contains("\"status\":\"APPLIED\"");
    assertThatThrownBy(
            () ->
                new GameLogicDraftDesignDigestServiceImpl()
                    .getDraftDesignDigest(
                        binding.target().canonicalTenantId().toString(),
                        binding.target().canonicalVersionId().toString()))
        .isInstanceOf(GameLogicDraftDesignDigestService.UnsupportedDigestScopeException.class);
  }

  private static DraftCommitBinding fullBinding(
      TargetProof target,
      String gameLogicPayload,
      int gameLogicRevisionCount,
      List<AffectedUnit> units) {
    return GameLogicRuleInputManifestFixtures.fullBinding(
        target,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "base-commit",
        gameLogicPayload,
        gameLogicRevisionCount,
        units);
  }

  private static AffectedUnit unit(
      TargetProof target,
      String aggregateType,
      String aggregateId,
      String scopeType,
      String scopeId,
      String expectedEpoch) {
    return new AffectedUnit(
        Owner.GAME_LOGIC, aggregateType, aggregateId, scopeType, scopeId, expectedEpoch);
  }

  private static void assertInvalid(DraftCommitBinding binding) {
    assertThatThrownBy(() -> GameLogicRuleInputManifest.fromExplicitEmptyIntent(binding))
        .isInstanceOf(GameLogicRuleInputManifest.InvalidRuleManifestIntentException.class);
  }

  private static String sourceProofJson(TargetProof target) {
    return "{\"canonicalTenantId\":\""
        + target.canonicalTenantId()
        + "\",\"canonicalVersionId\":\""
        + target.canonicalVersionId()
        + "\",\"gameDesignVersionRowId\":\""
        + target.gameDesignVersionRowId()
        + "\",\"gameDesignVersionTenantKey\":\""
        + target.gameDesignVersionTenantKey()
        + "\",\"sourceGameRowId\":\""
        + target.sourceGameRowId()
        + "\",\"sourceGameTenantKey\":\""
        + target.sourceGameTenantKey()
        + "\",\"sourceProvenanceKind\":\""
        + target.sourceProvenanceKind()
        + "\"}";
  }

  private static String sha256Hex(String value) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
