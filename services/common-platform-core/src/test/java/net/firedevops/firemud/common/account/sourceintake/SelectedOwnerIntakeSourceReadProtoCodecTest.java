package net.firedevops.firemud.common.account.sourceintake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import org.junit.jupiter.api.Test;

/** Synthetic canonical scopes exercise the wire boundary, not Account or owner-source proof. */
class SelectedOwnerIntakeSourceReadProtoCodecTest {
  private static final String NAMESPACE = "test";

  @Test
  void roundTripsEntityAndAutomationScopesWithIndependentCorrelationsAndExactEchoes() {
    for (Owner owner : List.of(Owner.ENTITY_MANAGEMENT, Owner.AUTOMATION_SCRIPTING)) {
      var request = SelectedOwnerIntakeSourceReadEvidence.Request.create(NAMESPACE, scope(owner));
      var wire = SelectedOwnerIntakeSourceReadProtoCodec.toRequest(request);

      assertThat(SelectedOwnerIntakeSourceReadProtoCodec.fromRequest(wire)).isEqualTo(request);
      assertThat(wire.getPreliminaryScopeDigest()).isEqualTo(request.scope().digest());
      assertThat(request.readRequestId())
          .isNotIn(
              request.scope().operationId(),
              request.scope().fenceId(),
              request.scope().intakeRequestId(),
              request.scope().actorAccountId(),
              request.scope().selected().requestId(),
              request.scope().selected().commitId());
      var response = SelectedOwnerIntakeSourceReadProtoCodec.toResponse(request);
      assertThat(SelectedOwnerIntakeSourceReadProtoCodec.fromResponse(request, response).request())
          .isEqualTo(request);
    }
  }

  @Test
  void rejectsUnsupportedSchemaWrongDomainNamespaceReaderPurposeAndCorrelation() {
    var request =
        SelectedOwnerIntakeSourceReadEvidence.Request.create(
            NAMESPACE, scope(Owner.ENTITY_MANAGEMENT));
    var wire = SelectedOwnerIntakeSourceReadProtoCodec.toRequest(request);

    assertThatThrownBy(
            () ->
                SelectedOwnerIntakeSourceReadProtoCodec.fromRequest(
                    wire.toBuilder().setSchemaVersion(2).build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                SelectedOwnerIntakeSourceReadProtoCodec.fromRequest(
                    wire.toBuilder().setTargetNamespace("other").build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                SelectedOwnerIntakeSourceReadProtoCodec.fromRequest(
                    wire.toBuilder()
                        .setIntendedReader("spiffe://firemud/ns/test/sa/entity-management-service")
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                SelectedOwnerIntakeSourceReadProtoCodec.fromRequest(
                    wire.toBuilder().setPurpose("AUTOMATION_INTAKE_SOURCE").build()))
        .isInstanceOf(IllegalArgumentException.class);
    for (UUID reusedIdentity :
        List.of(
            request.scope().operationId(),
            request.scope().fenceId(),
            request.scope().intakeRequestId(),
            request.scope().actorAccountId(),
            request.scope().selected().requestId(),
            request.scope().selected().commitId())) {
      assertThatThrownBy(
              () ->
                  SelectedOwnerIntakeSourceReadProtoCodec.fromRequest(
                      wire.toBuilder().setReadRequestId(reusedIdentity.toString()).build()))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(
            () ->
                SelectedOwnerIntakeSourceReadProtoCodec.fromRequest(
                    wire.toBuilder()
                        .setReadRequestId("00000000-0000-0000-0000-000000000000")
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsMissingUnknownOversizedAndDigestSubstitutedRequests() {
    var request =
        SelectedOwnerIntakeSourceReadEvidence.Request.create(
            NAMESPACE, scope(Owner.AUTOMATION_SCRIPTING));
    var wire = SelectedOwnerIntakeSourceReadProtoCodec.toRequest(request);

    assertThatThrownBy(
            () ->
                SelectedOwnerIntakeSourceReadProtoCodec.fromRequest(
                    wire.toBuilder().clearPreliminarySourceScope().build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                SelectedOwnerIntakeSourceReadProtoCodec.fromRequest(
                    wire.toBuilder().setPreliminaryScopeDigest("sha256:" + "0".repeat(64)).build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                SelectedOwnerIntakeSourceReadProtoCodec.fromRequest(
                    wire.toBuilder()
                        .setPreliminarySourceScope(ByteString.copyFrom(new byte[] {1}))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                SelectedOwnerIntakeSourceReadProtoCodec.fromRequest(
                    wire.toBuilder().setUnknownFields(unknownField()).build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                SelectedOwnerIntakeSourceReadProtoCodec.fromRequest(
                    wire.toBuilder()
                        .setPurpose(
                            "x".repeat(SelectedOwnerIntakeSourceReadProtoCodec.MAX_WIRE_BYTES))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void requiresACompletePermittedAndUnmodifiedResponseEcho() {
    var request =
        SelectedOwnerIntakeSourceReadEvidence.Request.create(
            NAMESPACE, scope(Owner.ENTITY_MANAGEMENT));
    var wire = SelectedOwnerIntakeSourceReadProtoCodec.toRequest(request);
    var response = SelectedOwnerIntakeSourceReadProtoCodec.toResponse(request);

    assertThatThrownBy(
            () ->
                SelectedOwnerIntakeSourceReadProtoCodec.fromResponse(
                    request,
                    response.toBuilder()
                        .setRequest(wire.toBuilder().setReadRequestId(UUID.randomUUID().toString()))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                SelectedOwnerIntakeSourceReadProtoCodec.fromResponse(
                    request, response.toBuilder().setPermitted(false).build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                SelectedOwnerIntakeSourceReadProtoCodec.fromResponse(
                    request, response.toBuilder().clearRequest().build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                SelectedOwnerIntakeSourceReadProtoCodec.fromResponse(
                    request, response.toBuilder().setUnknownFields(unknownField()).build()))
        .isInstanceOf(IllegalArgumentException.class);
    var oversizedResponseUnknownFields =
        UnknownFieldSet.newBuilder()
            .addField(
                99,
                UnknownFieldSet.Field.newBuilder()
                    .addLengthDelimited(
                        ByteString.copyFrom(
                            new byte[SelectedOwnerIntakeSourceReadProtoCodec.MAX_WIRE_BYTES]))
                    .build())
            .build();
    assertThatThrownBy(
            () ->
                SelectedOwnerIntakeSourceReadProtoCodec.fromResponse(
                    request,
                    response.toBuilder().setUnknownFields(oversizedResponseUnknownFields).build()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static SelectedOwnerIntakeSourceReadScope scope(Owner owner) {
    UUID tenant = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    var selected =
        DraftCommitBinding.create(
            new DraftCommitBinding.TargetProof(
                tenant,
                UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
                1L,
                "tenant-key",
                2L,
                "tenant-key",
                "NEW_GAME_ROW"),
            UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
            UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd"),
            "base",
            List.of(
                new DraftCommitBinding.RevisionPayload(
                    "0",
                    UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
                    Owner.GAME_DESIGN_CONTROL_PLANE,
                    "{}")),
            List.of(
                new DraftCommitBinding.AffectedUnit(
                    Owner.GAME_DESIGN_CONTROL_PLANE,
                    "TEMPLATE_CONFIG",
                    "templates",
                    "TENANT",
                    tenant.toString(),
                    "0")));
    return new SelectedOwnerIntakeSourceReadScope(
        owner,
        NAMESPACE,
        UUID.fromString("ffffffff-ffff-4fff-8fff-ffffffffffff"),
        UUID.fromString("99999999-9999-4999-8999-999999999999"),
        selected.requestId(),
        UUID.fromString("12121212-1212-4212-8212-121212121212"),
        selected);
  }

  private static UnknownFieldSet unknownField() {
    return UnknownFieldSet.newBuilder()
        .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
        .build();
  }
}
