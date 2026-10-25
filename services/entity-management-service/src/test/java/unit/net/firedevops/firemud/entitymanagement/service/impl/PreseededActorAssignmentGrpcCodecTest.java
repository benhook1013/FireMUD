package net.firedevops.firemud.entitymanagement.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.UnknownFieldSet;
import java.time.Instant;
import java.util.UUID;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentExpectedTarget;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentOwnerEvidence;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentReceipt;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentReceiptRequest;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentRequest;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentResult;
import net.firedevops.firemud.entitymanagement.service.PreseededActorCorePayload;
import net.firedevops.firemud.entitymanagement.v1.AssignPreseededActorRequest;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActorKind;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentOutcome;
import net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentReceiptStatus;
import net.firedevops.firemud.entitymanagement.v1.ReadPreseededActorAssignmentReceiptRequest;
import org.junit.jupiter.api.Test;

class PreseededActorAssignmentGrpcCodecTest {
  private static final UUID ASSIGNMENT_UUID = uuid("10000000-0000-4000-8000-000000000001");
  private static final UUID ACCOUNT_UUID = uuid("20000000-0000-4000-8000-000000000002");
  private static final UUID TENANT_UUID = uuid("30000000-0000-4000-8000-000000000003");
  private static final UUID REALM_UUID = uuid("40000000-0000-4000-8000-000000000004");
  private static final UUID VERSION_UUID = uuid("50000000-0000-4000-8000-000000000005");
  private static final UUID NAMESPACE_UUID = uuid("60000000-0000-4000-8000-000000000006");
  private static final UUID CHARACTER_UUID = uuid("70000000-0000-4000-8000-000000000007");
  private static final UUID PLACEMENT_OPERATION_UUID = uuid("80000000-0000-4000-8000-000000000008");

  @Test
  void decodesExactIntentAndPreservesOpaqueGameInstanceText() {
    AssignPreseededActorRequest wire = request();

    PreseededActorAssignmentRequest decoded = PreseededActorAssignmentGrpcCodec.fromRequest(wire);

    assertThat(decoded.assignmentUuid()).isEqualTo(ASSIGNMENT_UUID);
    assertThat(decoded.canonicalAccountUuid()).isEqualTo(ACCOUNT_UUID);
    assertThat(decoded.expectedTarget().gameInstanceId()).isEqualTo("instance:opaque/value");
    assertThat(decoded.expectedTarget().playableStateScope())
        .isEqualTo(PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED);
    assertThat(decoded.corePayload())
        .isEqualTo(
            new PreseededActorCorePayload(PreseededActorCorePayload.ActorKind.PLAYER, "Sora"));
    assertThat(decoded.mutationIntentDigest()).isEqualTo(wire.getMutationIntentDigest());
  }

  @Test
  void rejectsUnsupportedSchemaUnknownFieldsAndMissingNestedValues() {
    assertThatThrownBy(
            () ->
                PreseededActorAssignmentGrpcCodec.fromRequest(
                    request().toBuilder().setSchemaVersion(2).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported schema_version");
    assertThatThrownBy(
            () ->
                PreseededActorAssignmentGrpcCodec.fromRequest(
                    request().toBuilder().setUnknownFields(unknownField()).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown fields");
    assertThatThrownBy(
            () ->
                PreseededActorAssignmentGrpcCodec.fromRequest(
                    request().toBuilder()
                        .setExpectedTarget(
                            request().getExpectedTarget().toBuilder()
                                .setUnknownFields(unknownField())
                                .build())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown fields");
    assertThatThrownBy(
            () ->
                PreseededActorAssignmentGrpcCodec.fromRequest(
                    request().toBuilder().clearCorePayload().build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("complete assignment intent");
  }

  @Test
  void rejectsNoncanonicalIdentityInvalidScopeCoreAndDigestBeforeOwnerReads() {
    assertInvalid(
        request().toBuilder().setAssignmentUuid("A0000000-0000-4000-8000-000000000001").build());
    assertInvalid(
        request().toBuilder()
            .setCanonicalAccountUuid("00000000-0000-0000-0000-000000000000")
            .build());
    assertInvalid(
        request().toBuilder()
            .setExpectedTarget(
                request().getExpectedTarget().toBuilder()
                    .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_UNSPECIFIED)
                    .build())
            .build());
    assertInvalid(
        request().toBuilder()
            .setExpectedTarget(
                request().getExpectedTarget().toBuilder().setPlayableStateScopeValue(77).build())
            .build());
    assertInvalid(
        request().toBuilder()
            .setCorePayload(
                request().getCorePayload().toBuilder()
                    .setActorKind(
                        CanonicalGameplayRosterActorKind.CANONICAL_ROSTER_ACTOR_KIND_UNSPECIFIED)
                    .build())
            .build());
    assertInvalid(request().toBuilder().setMutationIntentDigest("A".repeat(64)).build());
    assertInvalid(request().toBuilder().setMutationIntentDigest("a".repeat(64)).build());
    assertInvalid(
        request().toBuilder()
            .setExpectedTarget(
                request().getExpectedTarget().toBuilder().setCatalogRevision(0).build())
            .build());
    assertInvalid(
        request().toBuilder()
            .setCorePayload(request().getCorePayload().toBuilder().setDisplayName("\n").build())
            .build());
  }

  @Test
  void encodesOnlyThePersistedAssignmentOutcomeAndActorIdentity() {
    PreseededActorAssignmentRequest request =
        PreseededActorAssignmentGrpcCodec.fromRequest(request());
    String digest = request.mutationIntentDigest();

    var assigned =
        PreseededActorAssignmentGrpcCodec.toResponse(
            request,
            new PreseededActorAssignmentResult(
                ASSIGNMENT_UUID,
                PreseededActorAssignmentResult.Outcome.ASSIGNED,
                CHARACTER_UUID,
                digest));
    assertThat(assigned.getSchemaVersion()).isEqualTo(1);
    assertThat(assigned.getAssignmentUuid()).isEqualTo(ASSIGNMENT_UUID.toString());
    assertThat(assigned.getOutcome())
        .isEqualTo(PreseededActorAssignmentOutcome.PRESEEDED_ACTOR_ASSIGNMENT_OUTCOME_ASSIGNED);
    assertThat(assigned.hasCharacterUuid()).isTrue();
    assertThat(assigned.getCharacterUuid()).isEqualTo(CHARACTER_UUID.toString());
    assertThat(assigned.getIntentDigest()).isEqualTo(digest);

    var conflict =
        PreseededActorAssignmentGrpcCodec.toResponse(
            request,
            new PreseededActorAssignmentResult(
                ASSIGNMENT_UUID,
                PreseededActorAssignmentResult.Outcome.IDEMPOTENCY_CONFLICT,
                null,
                digest));
    assertThat(conflict.getOutcome())
        .isEqualTo(
            PreseededActorAssignmentOutcome
                .PRESEEDED_ACTOR_ASSIGNMENT_OUTCOME_IDEMPOTENCY_CONFLICT);
    assertThat(conflict.hasCharacterUuid()).isFalse();
  }

  @Test
  void refusesToExposeAResultForAnotherOperationOrDigest() {
    PreseededActorAssignmentRequest request =
        PreseededActorAssignmentGrpcCodec.fromRequest(request());
    assertThatThrownBy(
            () ->
                PreseededActorAssignmentGrpcCodec.toResponse(
                    request,
                    new PreseededActorAssignmentResult(
                        uuid("80000000-0000-4000-8000-000000000008"),
                        PreseededActorAssignmentResult.Outcome.ASSIGNED,
                        CHARACTER_UUID,
                        request.mutationIntentDigest())))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void decodesReceiptSelectorAndPreservesPlacementCorrelationAndOpaqueTargetBytes() {
    var request = receiptReadRequest();

    var decoded = PreseededActorAssignmentGrpcCodec.fromReceiptReadRequest(request);

    assertThat(decoded.placementOperationUuid()).isEqualTo(PLACEMENT_OPERATION_UUID);
    assertThat(decoded.receiptRequest().assignmentUuid()).isEqualTo(ASSIGNMENT_UUID);
    assertThat(decoded.receiptRequest().canonicalAccountUuid()).isEqualTo(ACCOUNT_UUID);
    assertThat(decoded.receiptRequest().characterUuid()).isEqualTo(CHARACTER_UUID);
    assertThat(decoded.receiptRequest().intentDigest()).isEqualTo(request.getIntentDigest());
    assertThat(decoded.receiptRequest().expectedTarget().gameInstanceId())
        .isEqualTo("instance:opaque/value");
  }

  @Test
  void rejectsOpenMalformedReceiptSelectorsBeforeStorageRead() {
    assertThatThrownBy(
            () ->
                PreseededActorAssignmentGrpcCodec.fromReceiptReadRequest(
                    receiptReadRequest().toBuilder().setSchemaVersion(2).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported schema_version");
    assertThatThrownBy(
            () ->
                PreseededActorAssignmentGrpcCodec.fromReceiptReadRequest(
                    receiptReadRequest().toBuilder().setUnknownFields(unknownField()).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown fields");
    assertThatThrownBy(
            () ->
                PreseededActorAssignmentGrpcCodec.fromReceiptReadRequest(
                    receiptReadRequest().toBuilder().clearExpectedTarget().build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("complete receipt selector");
    assertThatThrownBy(
            () ->
                PreseededActorAssignmentGrpcCodec.fromReceiptReadRequest(
                    receiptReadRequest().toBuilder()
                        .setExpectedTarget(
                            receiptReadRequest().getExpectedTarget().toBuilder()
                                .setUnknownFields(unknownField())
                                .build())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown fields");
    assertReceiptReadInvalid(
        receiptReadRequest().toBuilder()
            .setPlacementOperationUuid("00000000-0000-0000-0000-000000000000")
            .build());
    assertReceiptReadInvalid(
        receiptReadRequest().toBuilder()
            .setAssignmentUuid("A0000000-0000-4000-8000-000000000001")
            .build());
    assertReceiptReadInvalid(
        receiptReadRequest().toBuilder()
            .setCanonicalAccountUuid("00000000-0000-0000-0000-000000000000")
            .build());
    assertReceiptReadInvalid(
        receiptReadRequest().toBuilder()
            .setCharacterUuid("00000000-0000-0000-0000-000000000000")
            .build());
    assertReceiptReadInvalid(
        receiptReadRequest().toBuilder().setIntentDigest("A".repeat(64)).build());
    assertReceiptReadInvalid(
        receiptReadRequest().toBuilder()
            .setExpectedTarget(
                receiptReadRequest().getExpectedTarget().toBuilder()
                    .setPlayableStateScopeValue(77)
                    .build())
            .build());
  }

  @Test
  void receiptResponseUsesPersistedAssignmentFieldsAndOnlyEchoesTransportCorrelation() {
    var selector = PreseededActorAssignmentGrpcCodec.fromReceiptReadRequest(receiptReadRequest());
    var persisted = receipt(selector.receiptRequest());

    var response = PreseededActorAssignmentGrpcCodec.toReceiptReadResponse(selector, persisted);

    assertThat(response.getPlacementOperationUuid()).isEqualTo(PLACEMENT_OPERATION_UUID.toString());
    assertThat(response.getAssignmentUuid()).isEqualTo(persisted.assignmentUuid().toString());
    assertThat(response.getCanonicalAccountUuid())
        .isEqualTo(persisted.canonicalAccountUuid().toString());
    assertThat(response.getCharacterUuid()).isEqualTo(persisted.characterUuid().toString());
    assertThat(response.getIntentDigest()).isEqualTo(persisted.intentDigest());
    assertThat(response.getExpectedTarget().getGameInstanceId()).isEqualTo("instance:opaque/value");
    assertThat(response.getCorePayload().getDisplayName()).isEqualTo("Sora");
    assertThat(response.getStatus())
        .isEqualTo(
            PreseededActorAssignmentReceiptStatus
                .PRESEEDED_ACTOR_ASSIGNMENT_RECEIPT_STATUS_ASSIGNED);
  }

  @Test
  void receiptResponseRejectsPersistedSelectorMismatch() {
    var selector = PreseededActorAssignmentGrpcCodec.fromReceiptReadRequest(receiptReadRequest());
    var mismatched =
        receipt(
            new PreseededActorAssignmentReceiptRequest(
                ASSIGNMENT_UUID,
                ACCOUNT_UUID,
                uuid("90000000-0000-4000-8000-000000000009"),
                selector.receiptRequest().intentDigest(),
                selector.receiptRequest().expectedTarget()));

    assertThatThrownBy(
            () -> PreseededActorAssignmentGrpcCodec.toReceiptReadResponse(selector, mismatched))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("exact selector");
  }

  private static void assertInvalid(AssignPreseededActorRequest request) {
    assertThatThrownBy(() -> PreseededActorAssignmentGrpcCodec.fromRequest(request))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static void assertReceiptReadInvalid(ReadPreseededActorAssignmentReceiptRequest request) {
    assertThatThrownBy(() -> PreseededActorAssignmentGrpcCodec.fromReceiptReadRequest(request))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static ReadPreseededActorAssignmentReceiptRequest receiptReadRequest() {
    AssignPreseededActorRequest assignmentRequest = request();
    PreseededActorAssignmentRequest assignment =
        PreseededActorAssignmentGrpcCodec.fromRequest(assignmentRequest);
    return ReadPreseededActorAssignmentReceiptRequest.newBuilder()
        .setSchemaVersion(1)
        .setPlacementOperationUuid(PLACEMENT_OPERATION_UUID.toString())
        .setAssignmentUuid(assignment.assignmentUuid().toString())
        .setCanonicalAccountUuid(assignment.canonicalAccountUuid().toString())
        .setCharacterUuid(CHARACTER_UUID.toString())
        .setIntentDigest(assignment.mutationIntentDigest())
        .setExpectedTarget(assignmentRequest.getExpectedTarget())
        .build();
  }

  private static PreseededActorAssignmentReceipt receipt(
      PreseededActorAssignmentReceiptRequest selector) {
    PreseededActorAssignmentRequest assignment =
        PreseededActorAssignmentGrpcCodec.fromRequest(request());
    return new PreseededActorAssignmentReceipt(
        selector.assignmentUuid(),
        selector.canonicalAccountUuid(),
        selector.characterUuid(),
        selector.intentDigest(),
        selector.expectedTarget(),
        assignment.corePayload(),
        1,
        "gameplay",
        23L,
        "ACCOUNT_REPOSITORY_INSERT",
        PreseededActorAssignmentOwnerEvidence.Eligibility.ELIGIBLE,
        Instant.parse("2026-10-25T00:00:00Z"),
        9L,
        "b".repeat(64),
        "c".repeat(64),
        PreseededActorAssignmentOwnerEvidence.AccountPurpose.PRESEEDED_ACTOR_STAGING,
        PreseededActorAssignmentOwnerEvidence.AccountCurrentness.CURRENT_AT_REVALIDATION,
        "d".repeat(64),
        selector.expectedTarget().publishedReleaseBundleRef(),
        "PRESEEDED_ONLY",
        "ASSIGNED");
  }

  private static AssignPreseededActorRequest request() {
    var target =
        new PreseededActorAssignmentExpectedTarget(
            TENANT_UUID,
            REALM_UUID,
            "earth",
            "main",
            "instance:opaque/value",
            71L,
            VERSION_UUID,
            "a".repeat(64),
            NAMESPACE_UUID,
            "release:test",
            PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED);
    var assignment =
        new PreseededActorAssignmentRequest(
            ASSIGNMENT_UUID,
            ACCOUNT_UUID,
            new PreseededActorCorePayload(PreseededActorCorePayload.ActorKind.PLAYER, "Sora"),
            target);
    return AssignPreseededActorRequest.newBuilder()
        .setSchemaVersion(1)
        .setAssignmentUuid(ASSIGNMENT_UUID.toString())
        .setCanonicalAccountUuid(ACCOUNT_UUID.toString())
        .setExpectedTarget(
            net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentExpectedTarget
                .newBuilder()
                .setCanonicalTenantUuid(TENANT_UUID.toString())
                .setRealmUuid(REALM_UUID.toString())
                .setWorldSlug("earth")
                .setRealmSlug("main")
                .setGameInstanceId("instance:opaque/value")
                .setCatalogRevision(71L)
                .setCanonicalVersionUuid(VERSION_UUID.toString())
                .setFrozenPolicyDigest("a".repeat(64))
                .setPlayableStateNamespaceUuid(NAMESPACE_UUID.toString())
                .setPublishedReleaseBundleRef("release:test")
                .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED)
                .build())
        .setCorePayload(
            net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentCorePayload
                .newBuilder()
                .setActorKind(CanonicalGameplayRosterActorKind.CANONICAL_ROSTER_ACTOR_KIND_PLAYER)
                .setDisplayName("Sora")
                .build())
        .setMutationIntentDigest(assignment.mutationIntentDigest())
        .build();
  }

  private static UnknownFieldSet unknownField() {
    return UnknownFieldSet.newBuilder()
        .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
        .build();
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
