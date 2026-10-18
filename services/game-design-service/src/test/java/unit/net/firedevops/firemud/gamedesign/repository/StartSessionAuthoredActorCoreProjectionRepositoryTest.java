package net.firedevops.firemud.gamedesign.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreProjection;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreSourceBinding;
import net.firedevops.firemud.common.gamedesign.CommandSource;
import net.firedevops.firemud.common.gamedesign.GameplayRuleSource;
import net.firedevops.firemud.common.gamedesign.RealmPolicySnapshot;
import net.firedevops.firemud.common.gamedesign.RealmPolicySource;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Audience;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Disposition;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Family;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Feedback;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.ReplayPolicy;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Visibility;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSelectedSource;
import net.firedevops.firemud.gamedesign.repository.StartSessionAuthoredActorCoreProjectionRepository.RetainedProjection;
import net.firedevops.firemud.gamedesign.repository.StartSessionLaunchDescriptorRepository.Binding;
import org.junit.jupiter.api.Test;

class StartSessionAuthoredActorCoreProjectionRepositoryTest {
  private static final String NAMESPACE = "world-runtime";
  private static final UUID TENANT = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID VERSION = uuid("22222222-2222-4222-8222-222222222222");
  private static final UUID COMMIT = uuid("33333333-3333-4333-8333-333333333333");
  private static final UUID SOURCE_OPERATION = uuid("44444444-4444-4444-8444-444444444444");
  private static final UUID BINDING_ID = uuid("55555555-5555-4555-8555-555555555555");
  private static final UUID ATTEMPT = uuid("66666666-6666-4666-8666-666666666666");
  private static final String REQUEST_ID = "actor-core-owner-request";
  private static final String WORKFLOW = "published-workflow";
  private static final String SOURCE_DIGEST = digest('a');

  @Test
  void replaysExactFullPrivateProofAndSameProjectionIdentityWithoutReselection() {
    AuthoredInitialActorCoreSourceBinding source = sourceBinding("original terminal evidence");
    AuthoredInitialActorCoreProjection projection =
        AuthoredInitialActorCoreProjection.fromValidatedBinding(BINDING_ID, source);
    RetainedProjection retained = retained(owner(), source, projection, 0);

    var replay =
        StartSessionAuthoredActorCoreProjectionRepository.validateReplay(
            List.of(retained), List.of(source), owner());

    assertThat(replay).hasSize(1);
    assertThat(replay.getFirst().bindingId()).isEqualTo(BINDING_ID);
    assertThat(replay.getFirst().canonicalBytes()).isEqualTo(projection.canonicalBytes());
    assertThat(retained.sourceBindingBytes()).isEqualTo(source.canonicalBytes());
    assertThat(retained.projectionBytes()).isEqualTo(projection.canonicalBytes());
  }

  @Test
  void deniesHistoricalMissingProofAndFullPrivateContentConflict() {
    AuthoredInitialActorCoreSourceBinding source = sourceBinding("original terminal evidence");
    AuthoredInitialActorCoreProjection projection =
        AuthoredInitialActorCoreProjection.fromValidatedBinding(BINDING_ID, source);

    assertThatThrownBy(
            () ->
                StartSessionAuthoredActorCoreProjectionRepository.validateReplay(
                    List.of(), List.of(source), owner()))
        .isInstanceOf(
            StartSessionAuthoredActorCoreProjectionRepository.MissingRetainedProjectionException
                .class);

    AuthoredInitialActorCoreSourceBinding changed =
        sourceBinding("different but internally well-formed terminal evidence");
    assertThatThrownBy(
            () ->
                StartSessionAuthoredActorCoreProjectionRepository.validateReplay(
                    List.of(retained(owner(), source, projection, 0)), List.of(changed), owner()))
        .isInstanceOf(
            StartSessionAuthoredActorCoreProjectionRepository.RetainedProjectionConflictException
                .class)
        .hasMessageContaining("private source proof differs");
  }

  @Test
  void deniesIncompleteOrReorderedOwnerProofSets() {
    AuthoredInitialActorCoreSourceBinding source = sourceBinding("original terminal evidence");
    AuthoredInitialActorCoreProjection projection =
        AuthoredInitialActorCoreProjection.fromValidatedBinding(BINDING_ID, source);
    RetainedProjection wrongOrder = retained(owner(), source, projection, 1);

    assertThatThrownBy(
            () ->
                StartSessionAuthoredActorCoreProjectionRepository.validateReplay(
                    List.of(wrongOrder), List.of(source), owner()))
        .isInstanceOf(
            StartSessionAuthoredActorCoreProjectionRepository.RetainedProjectionConflictException
                .class)
        .hasMessageContaining("owner identity or order changed");

    assertThatThrownBy(
            () ->
                StartSessionAuthoredActorCoreProjectionRepository.validateReplay(
                    List.of(retained(owner(), source, projection, 0)),
                    List.of(source, source),
                    owner()))
        .isInstanceOf(
            StartSessionAuthoredActorCoreProjectionRepository.RetainedProjectionConflictException
                .class)
        .hasMessageContaining("count differs");
  }

  private static RetainedProjection retained(
      Binding owner,
      AuthoredInitialActorCoreSourceBinding source,
      AuthoredInitialActorCoreProjection projection,
      int policyOrder) {
    return new RetainedProjection(
        owner.targetNamespace(),
        owner.canonicalTenantId(),
        owner.controlPlaneRequestId(),
        owner.descriptorRowId(),
        policyOrder,
        projection.bindingId(),
        source.canonicalBytes(),
        source.digest(),
        projection.canonicalBytes(),
        projection.digest(),
        LocalDateTime.of(2026, 10, 11, 0, 0));
  }

  private static Binding owner() {
    return new Binding(
        NAMESPACE,
        TENANT,
        REQUEST_ID,
        81L,
        91L,
        3L,
        bytes("original complete Account tuple"),
        ATTEMPT,
        2L,
        7L,
        VERSION,
        COMMIT,
        WORKFLOW,
        digest('b'),
        digest('c'),
        SOURCE_OPERATION,
        SOURCE_DIGEST,
        "world",
        4L,
        "EMPTY_V1",
        "game-logic-publish-request",
        digest('d'),
        digest('e'),
        digest('f'),
        bytes("original association response"),
        LocalDateTime.of(2026, 10, 11, 0, 0));
  }

  private static AuthoredInitialActorCoreSourceBinding sourceBinding(String terminalText) {
    var target =
        new TargetProof(
            TENANT, VERSION, 23L, "private-owner-key", 42L, "private-owner-key", "NEW_GAME_ROW");
    String policyJson =
        "{\"schemaVersion\":3,\"worldSlug\":\"world\",\"worldDisplayName\":\"World\","
            + "\"realmSlug\":\"realm\",\"realmDisplayName\":\"Realm\",\"visible\":true,"
            + "\"publicProduction\":true,\"stateScope\":\"SHARED\",\"entryPolicy\":\"PLAYER_CREATED\","
            + "\"creationDescriptor\":{\"schemaVersion\":1,\"descriptorId\":\"77777777-7777-4777-8777-777777777777\","
            + "\"descriptorRevisionId\":\"88888888-8888-4888-8888-888888888888\","
            + "\"inputKind\":\"EMPTY_OBJECT\",\"dependencies\":[]},"
            + "\"initialActorCore\":{\"displayName\":\"Wayfarer\",\"dispositionKey\":\"newcomer\"}}";
    var policyPayload =
        RealmPolicySource.canonical(
            Map.of(
                "revisionKind",
                RealmPolicySource.REVISION_KIND,
                "logicalRevisionId",
                "policy-logical-r1",
                "policy",
                RealmPolicySource.tree(policyJson)));
    var disposition = new Disposition("newcomer", List.of(), "welcome");
    var feedback =
        new Feedback(
            "welcome",
            "actor.created",
            Audience.SOURCE_ACTOR,
            "actor.created",
            "Welcome, traveler.",
            List.of(),
            Visibility.PUBLIC,
            ReplayPolicy.EFFECT_IDEMPOTENT);
    List<RevisionPayload> revisions =
        List.of(
            new RevisionPayload(
                "0",
                uuid("99999999-9999-4999-8999-999999999999"),
                Owner.GAME_DESIGN_CONTROL_PLANE,
                policyPayload),
            new RevisionPayload(
                "1",
                uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
                Owner.GAME_DESIGN_CONTROL_PLANE,
                GameplayRuleSource.upsertPayload(disposition)),
            new RevisionPayload(
                "2",
                uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
                Owner.GAME_DESIGN_CONTROL_PLANE,
                GameplayRuleSource.upsertPayload(feedback)));
    DraftCommitBinding commit =
        DraftCommitBinding.create(
            target,
            uuid("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
            COMMIT,
            "base-0",
            revisions,
            List.of(
                new AffectedUnit(
                    Owner.GAME_DESIGN_CONTROL_PLANE,
                    RealmPolicySource.SCOPE,
                    VERSION.toString(),
                    RealmPolicySource.SCOPE,
                    "effective",
                    "0"),
                new AffectedUnit(
                    Owner.GAME_DESIGN_CONTROL_PLANE,
                    GameplayRuleSource.SCOPE,
                    VERSION.toString(),
                    GameplayRuleSource.SCOPE,
                    GameplayRuleSource.SCOPE_ID,
                    "0")));
    RealmPolicySnapshot policySnapshot =
        new RealmPolicySnapshot(
            commit, "1", List.of(RealmPolicySource.revision(commit, revisions.getFirst())));
    var rules = GameplayRuleSource.replay(List.of(), commit);
    GameplayRuleSelectedSource gameplaySnapshot = selectedGameplaySource(commit, rules);
    return AuthoredInitialActorCoreSourceBinding.resolveAll(
            SOURCE_OPERATION,
            SOURCE_DIGEST,
            "world",
            WORKFLOW,
            bytes("original publication request bytes"),
            bytes(terminalText),
            commit,
            policySnapshot,
            gameplaySnapshot)
        .getFirst();
  }

  private static GameplayRuleSelectedSource selectedGameplaySource(
      DraftCommitBinding binding, List<GameplayRuleSource.Entry> entries) {
    Map<Family, List<GameplayRuleManifest.Definition>> families = new LinkedHashMap<>();
    for (Family family : Family.values()) {
      families.put(
          family,
          entries.stream()
              .filter(entry -> entry.family() == family)
              .map(GameplayRuleSource.Entry::definition)
              .toList());
    }
    GameplayRuleManifest manifest = new GameplayRuleManifest(families);
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("schema", "game-design-gameplay-rule-source-snapshot/v1");
    snapshot.put("bindingJson", binding.canonicalJson());
    snapshot.put("bindingDigest", binding.digest());
    snapshot.put("sourceEpoch", "1");
    snapshot.put("inheritedCommitId", "");
    snapshot.put("genesisReceiptId", "dddddddd-dddd-4ddd-8ddd-dddddddddddd");
    snapshot.put("manifestJson", manifest.canonicalJson());
    snapshot.put(
        "entries",
        entries.stream()
            .map(
                entry ->
                    Map.of(
                        "family", entry.family().name(),
                        "definitionJson", GameplayRuleManifest.canonical(entry.definition()),
                        "sourceBindingJson", entry.sourceBinding().canonicalJson(),
                        "sourceBindingDigest", entry.sourceBinding().digest(),
                        "revisionOrder", entry.revisionOrder(),
                        "revisionId", entry.revisionId().toString()))
            .toList());
    return new GameplayRuleSelectedSource(CommandSource.canonical(snapshot));
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private static String digest(char value) {
    return "sha256:" + String.valueOf(value).repeat(64);
  }
}
