package net.firedevops.firemud.common.account.sourceintake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import org.junit.jupiter.api.Test;

/** Stipulated immutable fixtures prove integrity only, never genuine Account or owner authority. */
class SelectedOwnerIntakeSourceReadScopeTest {
  private static final UUID TENANT = id(1);
  private static final UUID VERSION = id(2);
  private static final UUID OPERATION = id(3);
  private static final UUID FENCE = id(4);
  private static final UUID INTAKE = id(5);
  private static final UUID ACTOR = id(6);
  private static final UUID REQUEST = id(7);
  private static final UUID COMMIT = id(8);
  private static final UUID REVISION = id(9);
  private static final String PAYLOAD = "{\"originalRevision\":\"retained exactly\"}";

  @Test
  void frozenDirectPreimageAndDigestRoundTripBothOwnerDomains() throws Exception {
    for (Owner owner : List.of(Owner.ENTITY_MANAGEMENT, Owner.AUTOMATION_SCRIPTING)) {
      var scope = scope(owner);
      String schema =
          owner == Owner.ENTITY_MANAGEMENT
              ? "account-entity-intake-source-read/v1"
              : "account-automation-intake-source-read/v1";
      String purpose =
          owner == Owner.ENTITY_MANAGEMENT ? "ENTITY_INTAKE_SOURCE" : "AUTOMATION_INTAKE_SOURCE";
      // Independent, fixed-field preimage: no production scope encoder or framing helper.
      byte[] expected =
          frames(
              schema,
              owner.name(),
              "test",
              OPERATION.toString(),
              FENCE.toString(),
              INTAKE.toString(),
              ACTOR.toString(),
              frozenSelectedJson(),
              digest(frozenSelectedJson().getBytes(StandardCharsets.UTF_8)),
              "spiffe://firemud/ns/test/sa/account-service",
              purpose);
      assertThat(scope.selected().canonicalJson()).isEqualTo(frozenSelectedJson());
      assertThat(scope.canonicalBytes()).isEqualTo(expected);
      assertThat(scope.digest()).isEqualTo(digest(expected));
      var restored = SelectedOwnerIntakeSourceReadScope.fromStored(expected);
      assertThat(restored).isEqualTo(scope);
      assertThat(restored.canonicalBytes()).isEqualTo(expected);
      assertThat(restored.schema()).isEqualTo(schema);
      assertThat(restored.purpose()).isEqualTo(purpose);
      assertThat(restored.intendedReader())
          .isEqualTo("spiffe://firemud/ns/test/sa/account-service");
    }
  }

  @Test
  void everyIdentityAndTheCompleteSelectedBindingAffectIntegrity() {
    var original = scope(Owner.ENTITY_MANAGEMENT);
    var selected = original.selected();
    for (var changed :
        List.of(
            scope(Owner.AUTOMATION_SCRIPTING),
            new SelectedOwnerIntakeSourceReadScope(
                original.owner(), "other", OPERATION, FENCE, INTAKE, ACTOR, selected),
            new SelectedOwnerIntakeSourceReadScope(
                original.owner(), "test", id(10), FENCE, INTAKE, ACTOR, selected),
            new SelectedOwnerIntakeSourceReadScope(
                original.owner(), "test", OPERATION, id(10), INTAKE, ACTOR, selected),
            new SelectedOwnerIntakeSourceReadScope(
                original.owner(), "test", OPERATION, FENCE, id(10), ACTOR, selected),
            new SelectedOwnerIntakeSourceReadScope(
                original.owner(), "test", OPERATION, FENCE, INTAKE, id(10), selected),
            new SelectedOwnerIntakeSourceReadScope(
                original.owner(),
                "test",
                OPERATION,
                FENCE,
                INTAKE,
                ACTOR,
                binding(id(10), COMMIT, PAYLOAD)),
            new SelectedOwnerIntakeSourceReadScope(
                original.owner(),
                "test",
                OPERATION,
                FENCE,
                INTAKE,
                ACTOR,
                binding(REQUEST, id(10), PAYLOAD)),
            new SelectedOwnerIntakeSourceReadScope(
                original.owner(),
                "test",
                OPERATION,
                FENCE,
                INTAKE,
                ACTOR,
                binding(REQUEST, COMMIT, PAYLOAD + " ")))) {
      assertThat(changed.canonicalBytes()).isNotEqualTo(original.canonicalBytes());
      assertThat(changed.digest()).isNotEqualTo(original.digest());
      assertThat(SelectedOwnerIntakeSourceReadScope.fromStored(changed.canonicalBytes()))
          .isEqualTo(changed);
    }
  }

  @Test
  void preservesEntireOriginalRevisionAndBaseWithoutInventingWorldAssociation() {
    var original = scope(Owner.ENTITY_MANAGEMENT);
    var decoded = SelectedOwnerIntakeSourceReadScope.fromStored(original.canonicalBytes());
    assertThat(decoded.selected()).isEqualTo(original.selected());
    assertThat(decoded.selected().baseCommitId()).isEqualTo("base-commit-0");
    assertThat(decoded.selected().revisions())
        .containsExactly(
            new DraftCommitBinding.RevisionPayload(
                "0", REVISION, Owner.GAME_DESIGN_CONTROL_PLANE, PAYLOAD));
    assertThat(decoded.selected().requiredOwners())
        .containsExactly(Owner.GAME_DESIGN_CONTROL_PLANE);
  }

  @Test
  void deniesUnknownOwnersAndCrossOwnerSchemaPurposeOrReader() throws Exception {
    var scope = scope(Owner.ENTITY_MANAGEMENT);
    for (Owner owner :
        List.of(Owner.WORLD_MANAGEMENT, Owner.GAME_LOGIC, Owner.GAME_DESIGN_CONTROL_PLANE))
      assertThatThrownBy(
              () ->
                  new SelectedOwnerIntakeSourceReadScope(
                      owner, "test", OPERATION, FENCE, INTAKE, ACTOR, scope.selected()))
          .isInstanceOf(IllegalArgumentException.class);
    for (String[] changed :
        List.of(
            new String[] {
              "account-automation-intake-source-read/v1",
              "ENTITY_MANAGEMENT",
              "ENTITY_INTAKE_SOURCE",
              scope.intendedReader()
            },
            new String[] {
              scope.schema(), "AUTOMATION_SCRIPTING", scope.purpose(), scope.intendedReader()
            },
            new String[] {scope.schema(), "UNKNOWN", scope.purpose(), scope.intendedReader()},
            new String[] {
              "account-entity-intake-source-read/v2",
              "ENTITY_MANAGEMENT",
              scope.purpose(),
              scope.intendedReader()
            },
            new String[] {
              scope.schema(), "GAME_LOGIC", "GAME_LOGIC_INTAKE_SOURCE", scope.intendedReader()
            },
            new String[] {
              "account-game-logic-intake-source-read/v1",
              "ENTITY_MANAGEMENT",
              scope.purpose(),
              scope.intendedReader()
            },
            new String[] {
              scope.schema(),
              "ENTITY_MANAGEMENT",
              "AUTOMATION_INTAKE_SOURCE",
              scope.intendedReader()
            },
            new String[] {
              scope.schema(),
              "ENTITY_MANAGEMENT",
              scope.purpose(),
              "spiffe://firemud/ns/test/sa/entity-management-service"
            },
            new String[] {
              scope.schema(),
              "ENTITY_MANAGEMENT",
              scope.purpose(),
              "spiffe://firemud/ns/other/sa/account-service"
            })) {
      byte[] bytes =
          wire(
              changed[0],
              changed[1],
              "test",
              ACTOR.toString(),
              scope.selected().canonicalJson(),
              scope.selected().digest(),
              changed[3],
              changed[2]);
      assertThatThrownBy(() -> SelectedOwnerIntakeSourceReadScope.fromStored(bytes))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void deniesMalformedTruncatedTrailingOversizedAndNoncanonicalInput() throws Exception {
    var scope = scope(Owner.ENTITY_MANAGEMENT);
    byte[] bytes = scope.canonicalBytes();
    byte[] invalidUtf8 = bytes.clone();
    invalidUtf8[4] = (byte) 0xc0;
    for (byte[] invalid :
        List.of(
            new byte[0],
            new byte[] {-1, -1, -1, -1},
            invalidUtf8,
            Arrays.copyOf(bytes, bytes.length - 1),
            Arrays.copyOf(bytes, bytes.length + 1),
            new byte[SelectedOwnerIntakeSourceReadScope.MAX_BYTES + 1]))
      assertThatThrownBy(() -> SelectedOwnerIntakeSourceReadScope.fromStored(invalid))
          .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SelectedOwnerIntakeSourceReadScope.fromStored(null))
        .isInstanceOf(IllegalArgumentException.class);
    for (String actor :
        List.of("00000000-0000-0000-0000-000000000000", "1-1-1-1-1", "NOT-A-UUID")) {
      byte[] invalid =
          wire(
              scope.schema(),
              scope.owner().name(),
              "test",
              actor,
              scope.selected().canonicalJson(),
              scope.selected().digest(),
              scope.intendedReader(),
              scope.purpose());
      assertThatThrownBy(() -> SelectedOwnerIntakeSourceReadScope.fromStored(invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }
    for (String namespace : List.of("Test", "test/other", " test", "")) {
      byte[] invalid =
          wire(
              scope.schema(),
              scope.owner().name(),
              namespace,
              ACTOR.toString(),
              scope.selected().canonicalJson(),
              scope.selected().digest(),
              scope.intendedReader(),
              scope.purpose());
      assertThatThrownBy(() -> SelectedOwnerIntakeSourceReadScope.fromStored(invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }
    byte[] wrongDigest =
        wire(
            scope.schema(),
            scope.owner().name(),
            "test",
            ACTOR.toString(),
            scope.selected().canonicalJson(),
            "sha256:" + "0".repeat(64),
            scope.intendedReader(),
            scope.purpose());
    assertThatThrownBy(() -> SelectedOwnerIntakeSourceReadScope.fromStored(wrongDigest))
        .isInstanceOf(RuntimeException.class);
    String noncanonical = " " + scope.selected().canonicalJson();
    byte[] padded =
        wire(
            scope.schema(),
            scope.owner().name(),
            "test",
            ACTOR.toString(),
            noncanonical,
            digest(noncanonical.getBytes(StandardCharsets.UTF_8)),
            scope.intendedReader(),
            scope.purpose());
    assertThatThrownBy(() -> SelectedOwnerIntakeSourceReadScope.fromStored(padded))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                new SelectedOwnerIntakeSourceReadScope(
                    scope.owner(),
                    "test",
                    OPERATION,
                    FENCE,
                    INTAKE,
                    ACTOR,
                    binding(
                        REQUEST, COMMIT, "x".repeat(SelectedOwnerIntakeSourceReadScope.MAX_BYTES))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsNilReservationIdentitiesAndUnsupportedNullOwner() {
    var selected = scope(Owner.ENTITY_MANAGEMENT).selected();
    UUID nil = new UUID(0, 0);
    for (var ids :
        List.of(
            List.of(nil, FENCE, INTAKE, ACTOR),
            List.of(OPERATION, nil, INTAKE, ACTOR),
            List.of(OPERATION, FENCE, nil, ACTOR),
            List.of(OPERATION, FENCE, INTAKE, nil)))
      assertThatThrownBy(
              () ->
                  new SelectedOwnerIntakeSourceReadScope(
                      Owner.ENTITY_MANAGEMENT,
                      "test",
                      ids.get(0),
                      ids.get(1),
                      ids.get(2),
                      ids.get(3),
                      selected))
          .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new SelectedOwnerIntakeSourceReadScope(
                    null, "test", OPERATION, FENCE, INTAKE, ACTOR, selected))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static SelectedOwnerIntakeSourceReadScope scope(Owner owner) {
    return new SelectedOwnerIntakeSourceReadScope(
        owner, "test", OPERATION, FENCE, INTAKE, ACTOR, binding(REQUEST, COMMIT, PAYLOAD));
  }

  private static DraftCommitBinding binding(UUID request, UUID commit, String payload) {
    return DraftCommitBinding.create(
        new DraftCommitBinding.TargetProof(
            TENANT, VERSION, 23L, "tenant", 42L, "tenant", "NEW_GAME_ROW"),
        request,
        commit,
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

  private static String frozenSelectedJson() {
    return "{\"affectedUnits\":[{\"aggregateId\":\""
        + VERSION
        + "\",\"aggregateType\":\"TEMPLATE_CONFIG\",\"expectedEpoch\":\"0\","
        + "\"owner\":\"GAME_DESIGN_CONTROL_PLANE\",\"scopeId\":\"ALL\","
        + "\"scopeType\":\"TEMPLATE_CONFIG\"}],\"baseCommitId\":\"base-commit-0\","
        + "\"canonicalTenantId\":\""
        + TENANT
        + "\",\"canonicalVersionId\":\""
        + VERSION
        + "\",\"commitId\":\""
        + COMMIT
        + "\",\"requestId\":\""
        + REQUEST
        + "\",\"requiredOwners\":[\"GAME_DESIGN_CONTROL_PLANE\"],"
        + "\"revisions\":[{\"owner\":\"GAME_DESIGN_CONTROL_PLANE\","
        + "\"payload\":\"{\\\"originalRevision\\\":\\\"retained exactly\\\"}\",\"revisionId\":\""
        + REVISION
        + "\",\"revisionOrder\":\"0\"}],\"schemaVersion\":\"1\","
        + "\"target\":{\"gameDesignVersionRowId\":\"23\",\"gameDesignVersionTenantKey\":\"tenant\","
        + "\"sourceGameRowId\":\"42\",\"sourceGameTenantKey\":\"tenant\","
        + "\"sourceProvenanceKind\":\"NEW_GAME_ROW\"}}";
  }

  private static byte[] wire(
      String schema,
      String owner,
      String namespace,
      String actor,
      String selected,
      String selectedDigest,
      String reader,
      String purpose)
      throws Exception {
    return frames(
        schema,
        owner,
        namespace,
        OPERATION.toString(),
        FENCE.toString(),
        INTAKE.toString(),
        actor,
        selected,
        selectedDigest,
        reader,
        purpose);
  }

  private static byte[] frames(String... fields) throws Exception {
    var output = new ByteArrayOutputStream();
    var data = new DataOutputStream(output);
    for (String field : fields) {
      byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
      data.writeInt(bytes.length);
      data.write(bytes);
    }
    return output.toByteArray();
  }

  private static String digest(byte[] bytes) throws Exception {
    return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private static UUID id(int value) {
    return UUID.fromString("00000000-0000-4000-8000-" + String.format("%012d", value));
  }
}
