package net.firedevops.firemud.common.gamedesign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Audience;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Disposition;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Family;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Feedback;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Visibility;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSelectedSource;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class AuthoredInitialActorCoreProjectionTest {
  private static final UUID TENANT = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID VERSION = uuid("22222222-2222-4222-8222-222222222222");
  private static final UUID COMMIT = uuid("dddddddd-dddd-4ddd-8ddd-dddddddddddd");
  private static final UUID SELECTED_COMMIT = uuid("99999999-9999-4999-8999-999999999999");
  private static final UUID POLICY_REVISION = uuid("33333333-3333-4333-8333-333333333333");
  private static final UUID DISPOSITION_REVISION = uuid("44444444-4444-4444-8444-444444444444");
  private static final UUID FEEDBACK_REVISION = uuid("55555555-5555-4555-8555-555555555555");
  private static final UUID BINDING_ID = uuid("abababab-abab-4bab-8bab-abababababab");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void roundTripsClosedProjectionAndPreservesExactPublicSourceProvenance() {
    var projection = projection();
    var decoded =
        AuthoredInitialActorCoreProjection.fromStored(
            projection.canonicalBytes(), projection.digest());

    assertThat(decoded.bindingId()).isEqualTo(BINDING_ID);
    assertThat(decoded.bindingDigest()).matches("sha256:[0-9a-f]{64}");
    assertThat(decoded.canonicalTenantId()).isEqualTo(TENANT);
    assertThat(decoded.canonicalVersionId()).isEqualTo(VERSION);
    assertThat(decoded.selectedCommitId()).isEqualTo(COMMIT);
    assertThat(decoded.policy().revisionId()).isEqualTo(POLICY_REVISION);
    assertThat(decoded.core().displayName()).isEqualTo("Wayfarer");
    assertThat(decoded.descriptor().canonicalJson()).contains("\"inputKind\":\"EMPTY_OBJECT\"");
    assertThat(decoded.disposition().revisionId()).isEqualTo(DISPOSITION_REVISION);
    assertThat(decoded.disposition().revisionOrder()).isEqualTo("1");
    assertThat(decoded.disposition().definitionDigest()).matches("sha256:[0-9a-f]{64}");
    assertThat(decoded.disposition().sourceCommitDigest())
        .isEqualTo(decoded.selectedCommitDigest());
    assertThat(decoded.safeFeedback().revisionId()).isEqualTo(FEEDBACK_REVISION);
    assertThat(decoded.safeFeedback().revisionOrder()).isEqualTo("2");
    assertThat(decoded.canonicalBytes()).isEqualTo(projection.canonicalBytes());
  }

  @Test
  void roundTripsInheritedPolicyAndRuleOriginsDistinctFromSelectedCommit() {
    var origin = sourceBinding();
    var source = inheritedSourceBinding(origin);
    var projection = AuthoredInitialActorCoreProjection.fromValidatedBinding(BINDING_ID, source);
    var decoded =
        AuthoredInitialActorCoreProjection.fromStored(
            projection.canonicalBytes(), projection.digest());

    assertThat(decoded.selectedCommitId()).isEqualTo(SELECTED_COMMIT);
    assertThat(decoded.selectedCommitId()).isNotEqualTo(origin.selectedCommit().commitId());
    assertThat(decoded.policy().commitId()).isEqualTo(origin.policySource().commitId());
    assertThat(decoded.policy().revisionId()).isEqualTo(POLICY_REVISION);
    assertThat(decoded.disposition().sourceCommitId()).isEqualTo(COMMIT);
    assertThat(decoded.disposition().sourceCommitDigest())
        .isEqualTo(origin.selectedCommit().digest());
    assertThat(decoded.disposition().revisionId()).isEqualTo(DISPOSITION_REVISION);
    assertThat(decoded.safeFeedback().sourceCommitId()).isEqualTo(COMMIT);
    assertThat(decoded.safeFeedback().sourceCommitDigest())
        .isEqualTo(origin.selectedCommit().digest());
    assertThat(decoded.safeFeedback().revisionId()).isEqualTo(FEEDBACK_REVISION);
    assertThat(decoded.selectedCommitDigest()).isEqualTo(source.selectedCommit().digest());
  }

  @Test
  void returnsImmutableEntryProvenanceAndDefensivelyCopiesProjectionBytes() {
    var source = sourceBinding();
    byte[] sourceBytes = source.canonicalBytes();
    String sourceDigest = source.digest();
    var projection = AuthoredInitialActorCoreProjection.fromValidatedBinding(BINDING_ID, source);
    byte[] bytes = projection.canonicalBytes();
    bytes[0] ^= 1;
    assertThat(projection.canonicalBytes()).isNotEqualTo(bytes);

    var disposition = source.dispositionSource();
    var feedback = source.safeFeedbackSource();
    assertThat(disposition.revisionId()).isEqualTo(DISPOSITION_REVISION);
    assertThat(feedback.revisionId()).isEqualTo(FEEDBACK_REVISION);
    assertThat(disposition.sourceBinding().digest()).isEqualTo(source.selectedCommit().digest());
    assertThat(source.canonicalBytes()).isEqualTo(sourceBytes);
    assertThat(source.digest()).isEqualTo(sourceDigest);
    assertThatThrownBy(
            () -> ((Disposition) disposition.definition()).deniedAdmissionTags().add("changed"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(((Disposition) disposition.definition()).deniedAdmissionTags()).isEmpty();
  }

  @Test
  void rejectsChangedFieldsDigestUnknownDuplicateAndTrailingInput() {
    var projection = projection();
    String json = new String(projection.canonicalBytes(), StandardCharsets.UTF_8);

    byte[] changedTenant =
        json.replace(TENANT.toString(), "99999999-9999-4999-8999-999999999999")
            .getBytes(StandardCharsets.UTF_8);
    assertRejected(changedTenant, projection.digest());
    assertThatThrownBy(
            () ->
                AuthoredInitialActorCoreProjection.fromStored(
                    projection.canonicalBytes(), digest('0')))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("digest differs");

    byte[] unknown = canonicalize(json.substring(0, json.length() - 1) + ",\"unknown\":true}");
    assertRejected(unknown, CommandSource.sha256(unknown));

    byte[] duplicate =
        json.replaceFirst("\\\"schema\\\":", "\\\"schema\\\":\\\"duplicate\\\",\\\"schema\\\":")
            .getBytes(StandardCharsets.UTF_8);
    assertRejected(duplicate, CommandSource.sha256(duplicate));

    byte[] trailing = (json + " true").getBytes(StandardCharsets.UTF_8);
    assertRejected(trailing, CommandSource.sha256(trailing));
  }

  @Test
  void rejectsPolicyCoreDescriptorAndRuleCrossSubstitution() {
    var projection = projection();
    String json = new String(projection.canonicalBytes(), StandardCharsets.UTF_8);

    String changedCore = json.replaceFirst("Wayfarer", "Wanderer");
    assertRejected(canonicalize(changedCore), null);

    String changedDescriptor = json.replace("EMPTY_OBJECT", "NONEMPTY_OBJECT");
    assertRejected(canonicalize(changedDescriptor), null);

    String changedRuleKey = replaceLast(json, "\"key\":\"welcome\"", "\"key\":\"different\"");
    assertRejected(canonicalize(changedRuleKey), null);

    String changedRuleDefinition = json.replace("Welcome, traveler.", "Welcome home.");
    assertRejected(canonicalize(changedRuleDefinition), null);

    String changedPolicyContent = json.replace("World", "Other World");
    assertRejected(canonicalize(changedPolicyContent), null);
  }

  @Test
  void rejectsSelfConsistentFeedbackNotSelectedByDisposition() throws Exception {
    var projection = projection();
    ObjectNode root =
        (ObjectNode) JSON.readTree(new String(projection.canonicalBytes(), StandardCharsets.UTF_8));
    ObjectNode feedback = (ObjectNode) root.get("safeFeedback");
    ObjectNode definition = (ObjectNode) JSON.readTree(feedback.path("definitionJson").asText());
    definition.put("feedbackKey", "other_feedback");
    String definitionJson = GameplayRuleManifest.canonical(definition);
    feedback.put("definitionJson", definitionJson);
    feedback.put(
        "definitionDigest", CommandSource.sha256(definitionJson.getBytes(StandardCharsets.UTF_8)));
    feedback.put("key", "other_feedback");

    assertRejected(canonicalize(CommandSource.canonical(root)), null);
  }

  @Test
  void projectionOmitsPrivateCommitTargetsAndPublicationCaptureBytes() {
    var source = sourceBinding();
    var projection = AuthoredInitialActorCoreProjection.fromValidatedBinding(BINDING_ID, source);
    String publicJson = new String(projection.canonicalBytes(), StandardCharsets.UTF_8);

    assertThat(publicJson)
        .doesNotContain("private-owner-key")
        .doesNotContain("publication operation bytes")
        .doesNotContain("selected release evidence")
        .doesNotContain("gameplay-rule-source-snapshot")
        .doesNotContain("sourceBindingJson")
        .doesNotContain("operationBytesBase64")
        .doesNotContain("captureBytesBase64");
    assertThat(publicJson).contains(source.digest());
  }

  private static void assertRejected(byte[] bytes, String digest) {
    String verifiedDigest = digest == null ? CommandSource.sha256(bytes) : digest;
    assertThatThrownBy(() -> AuthoredInitialActorCoreProjection.fromStored(bytes, verifiedDigest))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static byte[] canonicalize(String json) {
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(json);
    } catch (java.io.IOException malformed) {
      throw new IllegalStateException("Invalid projection test JSON", malformed);
    }
  }

  private static String replaceLast(String value, String oldText, String newText) {
    int index = value.lastIndexOf(oldText);
    if (index < 0) throw new IllegalStateException("Expected test source text was absent");
    return value.substring(0, index) + newText + value.substring(index + oldText.length());
  }

  private static AuthoredInitialActorCoreProjection projection() {
    return AuthoredInitialActorCoreProjection.fromValidatedBinding(BINDING_ID, sourceBinding());
  }

  private static AuthoredInitialActorCoreSourceBinding sourceBinding() {
    var policy =
        RealmEntryPolicy.parse(
            "{\"schemaVersion\":3,\"worldSlug\":\"world\",\"worldDisplayName\":\"World\","
                + "\"realmSlug\":\"realm\",\"realmDisplayName\":\"Realm\",\"visible\":true,"
                + "\"publicProduction\":true,\"stateScope\":\"SHARED\","
                + "\"entryPolicy\":\"PLAYER_CREATED\",\"creationDescriptor\":{"
                + "\"schemaVersion\":1,\"descriptorId\":\"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa\","
                + "\"descriptorRevisionId\":\"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb\","
                + "\"inputKind\":\"EMPTY_OBJECT\",\"dependencies\":[]},"
                + "\"initialActorCore\":{\"displayName\":\"Wayfarer\",\"dispositionKey\":\"newcomer\"}}",
            JSON);
    var policyPayload =
        RealmPolicySource.canonical(
            Map.of(
                "revisionKind",
                RealmPolicySource.REVISION_KIND,
                "logicalRevisionId",
                "policy-logical-r1",
                "policy",
                RealmPolicySource.tree(policy.canonicalJson())));
    var disposition = new Disposition("newcomer", List.of(), "welcome");
    var feedback =
        new Feedback(
            "welcome",
            "actor_created",
            Audience.SOURCE_ACTOR,
            "welcome_message",
            "Welcome, traveler.",
            List.of(),
            Visibility.PUBLIC,
            GameplayRuleManifest.ReplayPolicy.EFFECT_IDEMPOTENT);
    List<RevisionPayload> revisions =
        List.of(
            new RevisionPayload(
                "0", POLICY_REVISION, Owner.GAME_DESIGN_CONTROL_PLANE, policyPayload),
            new RevisionPayload(
                "1",
                DISPOSITION_REVISION,
                Owner.GAME_DESIGN_CONTROL_PLANE,
                GameplayRuleSource.upsertPayload(disposition)),
            new RevisionPayload(
                "2",
                FEEDBACK_REVISION,
                Owner.GAME_DESIGN_CONTROL_PLANE,
                GameplayRuleSource.upsertPayload(feedback)));
    TargetProof target =
        new TargetProof(
            TENANT, VERSION, 23L, "private-owner-key", 42L, "private-owner-key", "NEW_GAME_ROW");
    DraftCommitBinding commit =
        DraftCommitBinding.create(
            target,
            uuid("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
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
    var selectedPolicy = RealmPolicySource.revision(commit, revisions.getFirst());
    var policySnapshot = new RealmPolicySnapshot(commit, "1", List.of(selectedPolicy));
    var dispositionEntry =
        new GameplayRuleSource.Entry(
            Family.DISPOSITIONS, disposition, commit, "1", DISPOSITION_REVISION);
    var feedbackEntry =
        new GameplayRuleSource.Entry(Family.FEEDBACK, feedback, commit, "2", FEEDBACK_REVISION);
    var gameplay =
        new GameplayRuleSelectedSource(
            gameplaySnapshot(commit, List.of(dispositionEntry, feedbackEntry)));
    return AuthoredInitialActorCoreSourceBinding.resolveAll(
            uuid("66666666-6666-4666-8666-666666666666"),
            digest('a'),
            "world",
            "workflow-1",
            bytes("publication operation bytes"),
            bytes("selected release evidence"),
            commit,
            policySnapshot,
            gameplay)
        .getFirst();
  }

  private static AuthoredInitialActorCoreSourceBinding inheritedSourceBinding(
      AuthoredInitialActorCoreSourceBinding origin) {
    var selectedCommit =
        DraftCommitBinding.create(
            origin.selectedCommit().target(),
            uuid("88888888-8888-4888-8888-888888888888"),
            SELECTED_COMMIT,
            origin.selectedCommit().commitId().toString(),
            List.of(
                new RevisionPayload(
                    "0",
                    uuid("77777777-7777-4777-8777-777777777778"),
                    Owner.GAME_DESIGN_CONTROL_PLANE,
                    CommandSource.deletePayload("obsolete-command"))),
            List.of(
                new AffectedUnit(
                    Owner.GAME_DESIGN_CONTROL_PLANE,
                    CommandSource.SCOPE,
                    VERSION.toString(),
                    CommandSource.SCOPE,
                    CommandSource.SCOPE_ID,
                    "0")));
    var policySnapshot =
        new RealmPolicySnapshot(selectedCommit, "2", List.of(origin.policySource()));
    var gameplaySnapshot =
        new GameplayRuleSelectedSource(
            gameplaySnapshot(
                selectedCommit, List.of(origin.dispositionSource(), origin.safeFeedbackSource())));
    return AuthoredInitialActorCoreSourceBinding.resolveAll(
            uuid("66666666-6666-4666-8666-666666666666"),
            digest('a'),
            "world",
            "workflow-inherited",
            bytes("publication operation bytes"),
            bytes("selected release evidence"),
            selectedCommit,
            policySnapshot,
            gameplaySnapshot)
        .getFirst();
  }

  private static String gameplaySnapshot(
      DraftCommitBinding binding, List<GameplayRuleSource.Entry> entries) {
    Map<String, Object> families = new java.util.LinkedHashMap<>();
    for (Family family : Family.values()) {
      families.put(
          family.name(),
          entries.stream()
              .filter(value -> value.family() == family)
              .map(GameplayRuleSource.Entry::definition)
              .toList());
    }
    var manifest =
        GameplayRuleManifest.fromStored(
            GameplayRuleManifest.canonical(
                Map.of("schema", GameplayRuleManifest.SCHEMA, "families", families)));
    Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
    snapshot.put("schema", "game-design-gameplay-rule-source-snapshot/v1");
    snapshot.put("bindingJson", binding.canonicalJson());
    snapshot.put("bindingDigest", binding.digest());
    snapshot.put("sourceEpoch", "1");
    snapshot.put("inheritedCommitId", "");
    snapshot.put("genesisReceiptId", "77777777-7777-4777-8777-777777777777");
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
    return CommandSource.canonical(snapshot);
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
