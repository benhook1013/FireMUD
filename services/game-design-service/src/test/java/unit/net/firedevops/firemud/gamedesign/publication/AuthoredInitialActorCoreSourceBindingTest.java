package net.firedevops.firemud.gamedesign.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.gamedesign.GameplayRuleSource;
import net.firedevops.firemud.common.gamedesign.RealmPolicySource;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Audience;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Definition;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Disposition;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Family;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Feedback;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.PrimitiveKind;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Stat;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Visibility;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AuthoredInitialActorCoreSourceBindingTest {
  private static final UUID TENANT = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID VERSION = uuid("22222222-2222-4222-8222-222222222222");
  private static final UUID POLICY_REVISION = uuid("33333333-3333-4333-8333-333333333333");
  private static final UUID DISPOSITION_REVISION = uuid("44444444-4444-4444-8444-444444444444");
  private static final UUID FEEDBACK_REVISION = uuid("55555555-5555-4555-8555-555555555555");
  private static final UUID SOURCE_OPERATION = uuid("66666666-6666-4666-8666-666666666666");
  private static final UUID GENESIS = uuid("77777777-7777-4777-8777-777777777777");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void bindsExactPolicyAndGameplaySnapshotsWithFullDispositionFeedbackProvenance() {
    Fixture fixture = fixture(List.of());
    var binding = fixture.resolve().getFirst();

    assertThat(binding.core().displayName()).isEqualTo("Wayfarer");
    assertThat(binding.core().dispositionKey()).isEqualTo("newcomer");
    assertThat(binding.expectedWorldSlug()).isEqualTo("world");
    assertThat(binding.disposition().safeFeedbackKey()).isEqualTo("welcome");
    assertThat(binding.safeFeedback().feedbackKey()).isEqualTo("welcome");
    assertThat(binding.gameplayManifest().families()).containsKeys(Family.values());
    for (Family family : Family.values()) {
      if (family != Family.DISPOSITIONS && family != Family.FEEDBACK) {
        assertThat(binding.gameplayManifest().families().get(family)).isEmpty();
      }
    }
    assertThat(binding.canonicalBytes()).isNotEmpty();
    assertThat(binding.digest()).matches("sha256:[0-9a-f]{64}");
    assertThat(binding.canonicalBytes())
        .satisfies(
            value ->
                assertThat(new String(value, StandardCharsets.ISO_8859_1))
                    .contains(
                        new String(
                            fixture.policy().canonicalJson().getBytes(StandardCharsets.UTF_8),
                            StandardCharsets.ISO_8859_1)));
    assertThat(binding.canonicalBytes())
        .satisfies(
            value ->
                assertThat(new String(value, StandardCharsets.ISO_8859_1))
                    .contains(
                        new String(
                            fixture
                                .gameplaySnapshot()
                                .canonicalJson()
                                .getBytes(StandardCharsets.UTF_8),
                            StandardCharsets.ISO_8859_1)));
  }

  @Test
  void changedSourceOperationChangesTheBindingAndDifferentWorldDoesNotSubstitute() {
    Fixture fixture = fixture(List.of());
    var original = fixture.resolve().getFirst();
    var changedOperation =
        resolve(
                fixture,
                uuid("88888888-8888-4888-8888-888888888888"),
                "world",
                fixture.policySnapshot(),
                fixture.gameplaySnapshot())
            .getFirst();

    assertThat(changedOperation.digest()).isNotEqualTo(original.digest());
    assertThat(
            resolve(
                fixture,
                SOURCE_OPERATION,
                "different-world",
                fixture.policySnapshot(),
                fixture.gameplaySnapshot()))
        .isEmpty();
  }

  @Test
  void resolvesOnlyTheAssociatedWorldAndFramesTheFullMixedWorldSnapshot() {
    Fixture fixture = withForeignWorldPolicy(fixture(List.of()));
    var bindings =
        resolve(
            fixture,
            SOURCE_OPERATION,
            "world",
            fixture.policySnapshot(),
            fixture.gameplaySnapshot());

    assertThat(bindings).hasSize(1);
    assertThat(bindings.getFirst().policySource().policy().worldSlug()).isEqualTo("world");
    assertThat(bindings.getFirst().canonicalBytes())
        .satisfies(
            value ->
                assertThat(new String(value, StandardCharsets.ISO_8859_1))
                    .contains(
                        new String(
                            fixture.policySnapshot().canonicalBytes(),
                            StandardCharsets.ISO_8859_1)));
    assertThat(bindings.getFirst().canonicalBytes())
        .satisfies(
            value ->
                assertThat(new String(value, StandardCharsets.ISO_8859_1))
                    .contains("foreign-only"));
  }

  @Test
  void doesNotSubstituteAValidPolicyForAnotherWorldWhenSelectedWorldHasNoCorePolicy() {
    Fixture foreignOnly =
        fixtureWithPolicy(
            List.of(disposition("newcomer", "welcome")),
            List.of(feedback("welcome", "Welcome")),
            foreignPolicyJson());

    assertThat(
            resolve(
                foreignOnly,
                SOURCE_OPERATION,
                "world",
                foreignOnly.policySnapshot(),
                foreignOnly.gameplaySnapshot()))
        .isEmpty();
  }

  @Test
  void changedReleaseEvidenceOrOriginalPolicyProvenanceChangesTheBinding() {
    Fixture fixture = fixture(List.of());
    var original = fixture.resolve().getFirst();
    var changedReleaseSource =
        AuthoredInitialActorCoreSourceBinding.resolveAll(
                SOURCE_OPERATION,
                digest('a'),
                "world",
                "workflow-1",
                bytes("publication operation bytes"),
                bytes("changed selected release evidence"),
                fixture.binding(),
                fixture.policySnapshot(),
                fixture.gameplaySnapshot())
            .getFirst();

    assertThat(changedReleaseSource.digest()).isNotEqualTo(original.digest());

    var alteredPolicy =
        new RealmPolicySource.Policy(
            fixture.policySource().commitId(),
            fixture.policySource().revisionId(),
            "different-logical-revision",
            fixture.policy());
    var changedPolicySnapshot =
        new net.firedevops.firemud.common.gamedesign.RealmPolicySnapshot(
            fixture.binding(), "1", List.of(alteredPolicy));
    var changedPolicy =
        resolve(
                fixture,
                SOURCE_OPERATION,
                "world",
                changedPolicySnapshot,
                fixture.gameplaySnapshot())
            .getFirst();
    assertThat(changedPolicy.digest()).isNotEqualTo(original.digest());
  }

  @Test
  void rejectsPolicyOrRuleSnapshotsFromAnotherSelectedCommit() {
    Fixture fixture = fixture(List.of());
    DraftCommitBinding otherCommit =
        DraftCommitBinding.create(
            fixture.binding().target(),
            uuid("99999999-9999-4999-8999-999999999999"),
            uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
            "base-0",
            fixture.binding().revisions(),
            fixture.binding().affectedUnits());

    assertThatThrownBy(
            () ->
                AuthoredInitialActorCoreSourceBinding.resolveAll(
                    SOURCE_OPERATION,
                    digest('a'),
                    "world",
                    "workflow-1",
                    bytes("publication operation bytes"),
                    bytes("release evidence bytes"),
                    otherCommit,
                    fixture.policySnapshot(),
                    fixture.gameplaySnapshot()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("selected authored commit");

    String changedRuleSnapshot =
        fixture
            .gameplaySnapshot()
            .canonicalJson()
            .replace("\"sourceEpoch\":\"1\"", "\"sourceEpoch\":\"2\"");
    var changedRuleSource =
        resolve(
                fixture,
                SOURCE_OPERATION,
                "world",
                fixture.policySnapshot(),
                GameplayRuleSnapshot.fromStored(changedRuleSnapshot))
            .getFirst();
    assertThat(changedRuleSource.digest()).isNotEqualTo(fixture.resolve().getFirst().digest());
  }

  @Test
  void rejectsAuthoredPresenceAddedToTheClosedSelectedGameplaySnapshot() {
    Fixture fixture = fixture(List.of());
    String changedSnapshot =
        fixture
            .gameplaySnapshot()
            .canonicalJson()
            .replace("{\"bindingDigest\":", "{\"presence\":\"ACTIVE\",\"bindingDigest\":");

    assertThatThrownBy(() -> GameplayRuleSnapshot.fromStored(changedSnapshot))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unknown or missing");
  }

  @Test
  void rejectsMissingDispositionOrFeedbackAndPreservesOtherAuthoredRuleFamilies() {
    Fixture fixture = fixture(List.of());
    var feedbackOnly = List.of(fixture.entries().getLast());
    var feedbackOnlySnapshot =
        GameplayRuleSnapshot.fromStored(snapshot(fixture.binding(), feedbackOnly));
    assertThatThrownBy(
            () ->
                resolve(
                    fixture,
                    SOURCE_OPERATION,
                    "world",
                    fixture.policySnapshot(),
                    feedbackOnlySnapshot))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("disposition is absent");

    Feedback changedFeedback = feedback("otherWelcome", "Welcome, traveler");
    assertThatThrownBy(
            () ->
                fixtureWithDefinitions(
                        List.of(disposition("newcomer", "welcome"), changedFeedback), List.of())
                    .resolve())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("FEEDBACK");

    Stat strength =
        new Stat(
            "strength",
            PrimitiveKind.NUMERIC,
            BigDecimal.ONE,
            null,
            BigDecimal.ZERO,
            BigDecimal.TEN,
            Visibility.PUBLIC,
            List.of());
    Fixture withStat =
        fixtureWithDefinitions(
            List.of(disposition("newcomer", "welcome"), feedback("welcome", "Welcome")),
            List.of(strength));
    var actorCoreSource = withStat.resolve().getFirst();
    assertThat(actorCoreSource.gameplayManifest().families().get(Family.STATS))
        .containsExactly(strength);
    assertThat(actorCoreSource.canonicalBytes()).isNotEmpty();
  }

  @Test
  void rejectsEntryProvenanceThatDoesNotBelongToItsOriginalRevision() {
    Fixture fixture = fixture(List.of());
    GameplayRuleSource.Entry entry = fixture.entries().getFirst();
    assertThatThrownBy(
            () ->
                new GameplayRuleSource.Entry(
                    entry.family(),
                    entry.definition(),
                    entry.sourceBinding(),
                    entry.revisionOrder(),
                    uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("original Game Design revision");
  }

  private static Fixture fixture(List<Definition> extraDefinitions) {
    return fixtureWithDefinitions(
        List.of(disposition("newcomer", "welcome"), feedback("welcome", "Welcome")),
        extraDefinitions);
  }

  private static Fixture fixtureWithDefinitions(
      List<Definition> coreDefinitions, List<Definition> extraDefinitions) {
    return fixtureWithPolicy(coreDefinitions, extraDefinitions, policyJson());
  }

  private static Fixture fixtureWithPolicy(
      List<Definition> coreDefinitions,
      List<Definition> extraDefinitions,
      String selectedPolicyJson) {
    var policy = RealmEntryPolicy.parse(selectedPolicyJson, JSON);
    var policyPayload =
        RealmPolicySource.canonical(
            Map.of(
                "revisionKind",
                RealmPolicySource.REVISION_KIND,
                "logicalRevisionId",
                "policy-logical-r1",
                "policy",
                RealmPolicySource.tree(policy.canonicalJson())));

    List<Definition> definitions = new ArrayList<>(coreDefinitions);
    definitions.addAll(extraDefinitions);
    var rulePayloads = definitions.stream().map(GameplayRuleSource::upsertPayload).toList();
    List<RevisionPayload> revisions = new ArrayList<>();
    revisions.add(
        new RevisionPayload("0", POLICY_REVISION, Owner.GAME_DESIGN_CONTROL_PLANE, policyPayload));
    for (int index = 0; index < rulePayloads.size(); index++) {
      UUID revisionId =
          index == 0
              ? DISPOSITION_REVISION
              : index == 1 ? FEEDBACK_REVISION : uuid("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
      revisions.add(
          new RevisionPayload(
              Integer.toString(index + 1),
              revisionId,
              Owner.GAME_DESIGN_CONTROL_PLANE,
              rulePayloads.get(index)));
    }
    TargetProof target =
        new TargetProof(
            TENANT, VERSION, 23L, "private-owner-key", 42L, "private-owner-key", "NEW_GAME_ROW");
    DraftCommitBinding binding =
        DraftCommitBinding.create(
            target,
            uuid("dddddddd-dddd-4ddd-8ddd-dddddddddddd"),
            uuid("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
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
    RealmPolicySource.Policy policySource =
        RealmPolicySource.revision(binding, revisions.getFirst());
    List<GameplayRuleSource.Entry> entries = new ArrayList<>();
    for (int index = 0; index < definitions.size(); index++) {
      Definition definition = definitions.get(index);
      entries.add(
          new GameplayRuleSource.Entry(
              definition.family(),
              definition,
              binding,
              Integer.toString(index + 1),
              revisions.get(index + 1).revisionId()));
    }
    List<GameplayRuleSource.Entry> selectedEntries = List.copyOf(entries);
    var policySnapshot =
        new net.firedevops.firemud.common.gamedesign.RealmPolicySnapshot(
            binding, "1", List.of(policySource));
    String rulesJson = snapshot(binding, selectedEntries);
    return new Fixture(binding, policy, policySource, policySnapshot, selectedEntries, rulesJson);
  }

  private static Fixture withForeignWorldPolicy(Fixture fixture) {
    RealmEntryPolicy foreignPolicy = RealmEntryPolicy.parse(foreignPolicyJson(), JSON);
    String foreignPolicyPayload =
        RealmPolicySource.canonical(
            Map.of(
                "revisionKind",
                RealmPolicySource.REVISION_KIND,
                "logicalRevisionId",
                "foreign-policy-logical-r1",
                "policy",
                RealmPolicySource.tree(foreignPolicy.canonicalJson())));
    var revisions = new ArrayList<>(fixture.binding().revisions());
    revisions.add(
        new RevisionPayload(
            Integer.toString(revisions.size()),
            uuid("cccccccc-cccc-4ccc-8ccc-dddddddddddd"),
            Owner.GAME_DESIGN_CONTROL_PLANE,
            foreignPolicyPayload));
    DraftCommitBinding binding =
        DraftCommitBinding.create(
            fixture.binding().target(),
            fixture.binding().requestId(),
            fixture.binding().commitId(),
            fixture.binding().baseCommitId(),
            revisions,
            fixture.binding().affectedUnits());
    RealmPolicySource.Policy selectedPolicy =
        RealmPolicySource.revision(binding, revisions.getFirst());
    RealmPolicySource.Policy otherWorldPolicy =
        RealmPolicySource.revision(binding, revisions.getLast());
    var policySnapshot =
        new net.firedevops.firemud.common.gamedesign.RealmPolicySnapshot(
            binding, "1", List.of(selectedPolicy, otherWorldPolicy));
    List<GameplayRuleSource.Entry> entries =
        fixture.entries().stream()
            .map(
                entry ->
                    new GameplayRuleSource.Entry(
                        entry.family(),
                        entry.definition(),
                        binding,
                        entry.revisionOrder(),
                        entry.revisionId()))
            .toList();
    return new Fixture(
        binding,
        fixture.policy(),
        selectedPolicy,
        policySnapshot,
        entries,
        snapshot(binding, entries));
  }

  private static List<AuthoredInitialActorCoreSourceBinding> resolve(
      Fixture fixture,
      UUID sourceOperation,
      String expectedWorldSlug,
      net.firedevops.firemud.common.gamedesign.RealmPolicySnapshot policySnapshot,
      GameplayRuleSnapshot gameplaySnapshot) {
    return AuthoredInitialActorCoreSourceBinding.resolveAll(
        sourceOperation,
        digest('a'),
        expectedWorldSlug,
        "workflow-1",
        bytes("publication operation bytes"),
        bytes("release evidence bytes"),
        fixture.binding(),
        policySnapshot,
        gameplaySnapshot);
  }

  private static String snapshot(
      DraftCommitBinding binding, List<GameplayRuleSource.Entry> entries) {
    return GameplayRuleManifest.canonical(
        Map.of(
            "schema", GameplayRuleSnapshot.SCHEMA,
            "bindingJson", binding.canonicalJson(),
            "bindingDigest", binding.digest(),
            "sourceEpoch", "1",
            "inheritedCommitId", "",
            "genesisReceiptId", GENESIS.toString(),
            "manifestJson", GameplayRuleSource.manifest(entries).canonicalJson(),
            "entries", entries.stream().map(GameplayRuleSource.Entry::object).toList()));
  }

  private static String policyJson() {
    return "{\"schemaVersion\":3,\"worldSlug\":\"world\",\"worldDisplayName\":\"World\","
        + "\"realmSlug\":\"realm\",\"realmDisplayName\":\"Realm\",\"visible\":true,"
        + "\"publicProduction\":true,\"stateScope\":\"SHARED\",\"entryPolicy\":\"PLAYER_CREATED\","
        + "\"creationDescriptor\":{\"schemaVersion\":1,\"descriptorId\":\"11111111-1111-4111-8111-111111111111\","
        + "\"descriptorRevisionId\":\"22222222-2222-4222-8222-222222222222\","
        + "\"inputKind\":\"EMPTY_OBJECT\",\"dependencies\":[]},"
        + "\"initialActorCore\":{\"displayName\":\"Wayfarer\",\"dispositionKey\":\"newcomer\"}}";
  }

  private static String foreignPolicyJson() {
    return policyJson()
        .replace(
            "\"worldSlug\":\"world\",\"worldDisplayName\":\"World\"",
            "\"worldSlug\":\"other-world\",\"worldDisplayName\":\"Other World\"")
        .replace(
            "\"realmSlug\":\"realm\",\"realmDisplayName\":\"Realm\"",
            "\"realmSlug\":\"other-realm\",\"realmDisplayName\":\"Other Realm\"")
        .replace(
            "\"visible\":true,\"publicProduction\":true",
            "\"visible\":false,\"publicProduction\":false")
        .replace(
            "\"displayName\":\"Wayfarer\",\"dispositionKey\":\"newcomer\"",
            "\"displayName\":\"Other Wayfarer\",\"dispositionKey\":\"foreign-only\"");
  }

  private static Disposition disposition(String key, String feedbackKey) {
    return new Disposition(key, List.of(), feedbackKey);
  }

  private static Feedback feedback(String key, String message) {
    return new Feedback(
        key,
        "actor.created",
        Audience.SOURCE_ACTOR,
        "actor.created",
        message,
        List.of(),
        Visibility.PUBLIC,
        GameplayRuleManifest.ReplayPolicy.EFFECT_IDEMPOTENT);
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private static String digest(char value) {
    return "sha256:" + String.valueOf(value).repeat(64);
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private record Fixture(
      DraftCommitBinding binding,
      RealmEntryPolicy policy,
      RealmPolicySource.Policy policySource,
      net.firedevops.firemud.common.gamedesign.RealmPolicySnapshot policySnapshot,
      List<GameplayRuleSource.Entry> entries,
      String rulesJson) {
    private Fixture {
      entries = List.copyOf(entries);
    }

    private GameplayRuleSnapshot gameplaySnapshot() {
      return GameplayRuleSnapshot.fromStored(rulesJson);
    }

    private List<AuthoredInitialActorCoreSourceBinding> resolve() {
      return AuthoredInitialActorCoreSourceBindingTest.resolve(
          this, SOURCE_OPERATION, "world", policySnapshot, gameplaySnapshot());
    }
  }
}
