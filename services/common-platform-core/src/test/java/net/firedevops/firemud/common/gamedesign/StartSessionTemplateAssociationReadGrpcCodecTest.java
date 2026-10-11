package net.firedevops.firemud.common.gamedesign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.UnknownFieldSet;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.ExactReplay;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.InitialConfigured;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.gamedesign.v1.ReadStartSessionTemplateAssociationRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class StartSessionTemplateAssociationReadGrpcCodecTest {
  private static final int EXPECTED_TUPLE_BYTES = 4176;
  private static final String EXPECTED_TUPLE_SHA256 =
      "891de4fb1ae82664ffbe9837db71c19bbbe28e7db759836ddca0d4a2f7002807";
  private static final int EXPECTED_INITIAL_WIRE_BYTES = 4276;
  private static final String EXPECTED_INITIAL_WIRE_SHA256 =
      "593394efa9b972fce74beefbf23cd3ba3791f3b57fe4b86426f1dc3c2dcf71ab";
  private static final int EXPECTED_REPLAY_WIRE_BYTES = 4455;
  private static final String EXPECTED_REPLAY_WIRE_SHA256 =
      "38cfef3dc73b485e19618580e88ee545ac26f1857a91940cebd9cd5740d4add0";
  private static final UUID TENANT = uuid("9f8f06b4-36e5-4d11-9c2a-5adfd7f41531");
  private static final UUID ACTOR = uuid("a4f5f4eb-8243-4d42-903a-33495456a622");
  private static final UUID TARGET_OWNER = uuid("36aa9ce5-0ebc-4c14-9f6b-d160edc6059a");
  private static final UUID READ_ID = uuid("01111111-1111-4111-8111-111111111111");
  private static final UUID ATTEMPT = uuid("02222222-2222-4222-8222-222222222222");
  private static final UUID VERSION = uuid("03333333-3333-4333-8333-333333333333");
  private static final UUID COMMIT = uuid("04444444-4444-4444-8444-444444444444");
  private static final UUID RESERVATION_OWNER = uuid("7c005b65-fcb1-4ac9-a714-f3d0f449edcf");
  private static final UUID ISSUANCE_ID = uuid("f5d044bd-7e5f-4e2d-9859-9025cbdcc60f");
  private static final UUID TOKEN_JTI = uuid("a681bba7-c215-4cf1-a35b-14348912cbdc");
  private static final String WORKLOAD =
      "spiffe://firemud/ns/world-runtime/sa/logging-admin-service";
  private static final String FINGERPRINT = "arfp/v1/test-key/" + "b".repeat(64);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void initialAndExactReplayHaveClosedDeterministicRequestVectors() {
    var initial = request(new InitialConfigured());
    var initialWire = StartSessionTemplateAssociationReadGrpcCodec.toRequest(initial);
    assertThat(initial.canonicalPostAuthorizationTuple()).hasSize(EXPECTED_TUPLE_BYTES);
    assertThat(sha256(initial.canonicalPostAuthorizationTuple())).isEqualTo(EXPECTED_TUPLE_SHA256);
    assertThat(initialWire.toByteArray()).hasSize(EXPECTED_INITIAL_WIRE_BYTES);
    assertThat(sha256(initialWire.toByteArray())).isEqualTo(EXPECTED_INITIAL_WIRE_SHA256);
    assertThat(initialWire.getSelectionCase())
        .isEqualTo(ReadStartSessionTemplateAssociationRequest.SelectionCase.INITIAL_CONFIGURED);
    assertThat(StartSessionTemplateAssociationReadGrpcCodec.fromRequest(initialWire))
        .isEqualTo(initial);
    assertThat(StartSessionTemplateAssociationReadGrpcCodec.toRequest(initial).toByteArray())
        .containsExactly(initialWire.toByteArray());

    var replay =
        request(new ExactReplay(VERSION, COMMIT, "template-publish-workflow-1", digest('a')));
    var replayWire = StartSessionTemplateAssociationReadGrpcCodec.toRequest(replay);
    assertThat(replayWire.toByteArray()).hasSize(EXPECTED_REPLAY_WIRE_BYTES);
    assertThat(sha256(replayWire.toByteArray())).isEqualTo(EXPECTED_REPLAY_WIRE_SHA256);
    assertThat(replayWire.getSelectionCase())
        .isEqualTo(ReadStartSessionTemplateAssociationRequest.SelectionCase.EXACT_REPLAY);
    assertThat(StartSessionTemplateAssociationReadGrpcCodec.fromRequest(replayWire))
        .isEqualTo(replay);
    assertThat(replayWire.getExactReplay().getCanonicalVersionId()).isEqualTo(VERSION.toString());
    assertThat(replayWire.getExactReplay().getSelectedCommitId()).isEqualTo(COMMIT.toString());
    assertThat(replayWire.getExactReplay().getAssociationDigest()).isEqualTo(digest('a'));
  }

  @Test
  void rejectsMissingSelectionUnknownFieldsNoncanonicalIdsAndChangedNamespace() {
    var valid =
        StartSessionTemplateAssociationReadGrpcCodec.toRequest(request(new InitialConfigured()));
    assertThatThrownBy(
            () ->
                StartSessionTemplateAssociationReadGrpcCodec.fromRequest(
                    valid.toBuilder().clearSelection().build()))
        .isInstanceOf(IllegalArgumentException.class);

    var unknown =
        valid.toBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                    .build())
            .build();
    assertThatThrownBy(() -> StartSessionTemplateAssociationReadGrpcCodec.fromRequest(unknown))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");

    var uppercaseId =
        valid.toBuilder()
            .setReadRequestId(TENANT.toString().toUpperCase(java.util.Locale.ROOT))
            .build();
    assertThatThrownBy(() -> StartSessionTemplateAssociationReadGrpcCodec.fromRequest(uppercaseId))
        .isInstanceOf(IllegalArgumentException.class);

    var tuple = postTuple("codec-namespace");
    assertThatThrownBy(
            () ->
                StartSessionTemplateAssociationReadGrpcCodec.toRequest(
                    new StartSessionTemplateAssociationReadEvidence.Request(
                        1,
                        "another-runtime",
                        READ_ID,
                        tuple.canonicalBytes(),
                        ATTEMPT,
                        8L,
                        new InitialConfigured())))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsReplaySelectorsWithoutCanonicalPinnedDigestOrIds() {
    assertThatThrownBy(() -> new ExactReplay(VERSION, COMMIT, "workflow", "A" + "a".repeat(70)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ExactReplay(new UUID(0L, 0L), COMMIT, "workflow", digest('b')))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ExactReplay(VERSION, COMMIT, "", digest('b')))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void preservesChangedAttemptFieldsAndRejectsOversizedOrUnknownNestedFields() {
    var valid =
        StartSessionTemplateAssociationReadGrpcCodec.toRequest(request(new InitialConfigured()));
    var changedFence = valid.toBuilder().setOwnerFence(valid.getOwnerFence() + 1L).build();
    assertThat(StartSessionTemplateAssociationReadGrpcCodec.fromRequest(changedFence).ownerFence())
        .isEqualTo(valid.getOwnerFence() + 1L);
    assertThat(StartSessionTemplateAssociationReadGrpcCodec.fromRequest(changedFence))
        .isNotEqualTo(request(new InitialConfigured()));

    byte[] oversized =
        new byte
            [net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple
                    .MAX_CANONICAL_TUPLE_BYTES
                + 1];
    assertThatThrownBy(
            () ->
                new StartSessionTemplateAssociationReadEvidence.Request(
                    1, "world-runtime", READ_ID, oversized, ATTEMPT, 8L, new InitialConfigured()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("bounded");

    var replay =
        StartSessionTemplateAssociationReadGrpcCodec.toRequest(
            request(new ExactReplay(VERSION, COMMIT, "template-publish-workflow-1", digest('a'))));
    var nestedUnknown =
        replay.toBuilder()
            .setExactReplay(
                replay.getExactReplay().toBuilder()
                    .setUnknownFields(
                        UnknownFieldSet.newBuilder()
                            .addField(77, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                            .build())
                    .build())
            .build();
    assertThatThrownBy(
            () -> StartSessionTemplateAssociationReadGrpcCodec.fromRequest(nestedUnknown))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");
  }

  private static StartSessionTemplateAssociationReadEvidence.Request request(
      StartSessionTemplateAssociationReadEvidence.Selection selection) {
    return new StartSessionTemplateAssociationReadEvidence.Request(
        1,
        "world-runtime",
        READ_ID,
        postTuple("codec-request").canonicalBytes(),
        ATTEMPT,
        8L,
        selection);
  }

  private static StartSessionPostAuthorizationExecutionTuple postTuple(String requestId) {
    StartSessionPreAuthorizationReservationTuple pre =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            requestId,
            ACTOR,
            new StartSessionOperatorAction(
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
                new StartSessionOperatorAction.Scope(TENANT, "world-runtime"),
                new StartSessionOperatorAction.Target(91L, TARGET_OWNER),
                StartSessionOperatorAction.ExpectedVersion.ABSENT,
                new StartSessionOperatorAction.Mutation(
                    StartSessionOperatorAction.ClientIp.absent()),
                "canonical StartSession owner attempt"));
    return StartSessionPostAuthorizationExecutionTuple.createHuman(
        pre,
        WORKLOAD,
        FINGERPRINT,
        RESERVATION_OWNER,
        19L,
        authorityBundle(pre),
        new StartSessionAuthorityEvidenceBundle.BundleReference(
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "17",
            "23",
            "18446744073709551615"));
  }

  private static byte[] authorityBundle(StartSessionPreAuthorizationReservationTuple tuple) {
    String tenant = TENANT.toString();
    Map<String, Object> value =
        Map.of(
            "bundleVersion",
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "authorityScope",
            Map.of(
                "scope", Map.of("tenantId", tenant, "targetNamespace", "world-runtime"),
                "actionFamily", tuple.actionFamily(),
                "applicableAccountId", ACTOR.toString(),
                "applicableTenantId", tenant),
            "accountProjectionEvidence",
            Map.of(
                "sourceType", "ACCOUNT",
                "sourceEvidenceId", digest('a'),
                "sourceEvidenceVersion", "17",
                "projectionStatus", "CURRENT",
                "evaluatedAt", "2026-10-09T00:00:00Z",
                "expiresAt", "2026-10-09T00:05:00Z"),
            "issuanceOperationIdentity",
            Map.of(
                "issuanceOperationId", ISSUANCE_ID.toString(),
                "controlPlaneRequestId", tuple.controlPlaneRequestId(),
                "actionFamilyRequestIdentity",
                    Map.of(
                        "requestIdentityKind",
                        "controlPlaneRequestId",
                        "requestId",
                        tuple.controlPlaneRequestId()),
                "mutationDigest", tuple.mutationDigest()),
            "issuanceKind",
            "human_operator",
            "authorityTuple",
            Map.of(
                "issuerAuthGeneration", 1L,
                "accountAuthorityGeneration", 2L,
                "tenantAuthorityGeneration", Map.of(tenant, 3L),
                "membershipAuthorityGeneration", Map.of(tenant, 4L),
                "privateRealmGrantVersions", List.of()),
            "membershipVersion",
            Map.of(tenant, 5L),
            "issuanceFence",
            "23",
            "issuanceEvidence",
            Map.of(
                "evidenceType",
                StartSessionAuthorityEvidenceBundle.HUMAN_EVIDENCE_TYPE,
                "actorAccountId",
                ACTOR.toString(),
                "controlUiTokenJti",
                TOKEN_JTI.toString(),
                "role",
                "tenantAdmin",
                "accountGeneration",
                "2",
                "tenantGeneration",
                "3"));
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (IOException invalid) {
      throw new IllegalStateException(invalid);
    }
  }

  private static String digest(char value) {
    return "sha256:" + String.valueOf(value).repeat(64);
  }

  private static String sha256(byte[] bytes) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
      StringBuilder result = new StringBuilder(digest.length * 2);
      for (byte value : digest) result.append(String.format("%02x", value));
      return result.toString();
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
    }
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
