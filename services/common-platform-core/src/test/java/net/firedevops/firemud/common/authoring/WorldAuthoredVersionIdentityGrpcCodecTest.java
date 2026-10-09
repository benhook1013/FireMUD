package net.firedevops.firemud.common.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.UnknownFieldSet;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateEvidence;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.worldmanagement.v1.AssociateAuthoredWorldVersionResponse;
import org.junit.jupiter.api.Test;

/** Structural transport proof with synthetic valid values, not real owner storage proof. */
class WorldAuthoredVersionIdentityGrpcCodecTest {
  @Test
  void roundTripsCompletePublicCommittedEvidenceAndPreservesHistoricalRetryRead() {
    var request = request("test");
    var result = committed(request);
    var wire = WorldAuthoredVersionIdentityGrpcCodec.toResponse(result);
    assertThat(WorldAuthoredVersionIdentityGrpcCodec.fromResponse(request, wire)).isEqualTo(result);
    assertThat(wire.getDescriptorForType().findFieldByName("local_version_key")).isNull();
    assertThat(
            wire.getSourceIntakeReceipt()
                .getDescriptorForType()
                .findFieldByName("local_tenant_key"))
        .isNull();
    var retry =
        new WorldAuthoredVersionIdentityEvidence.Request(
            1,
            request.targetNamespace(),
            request.canonicalTenantId(),
            request.worldSlug(),
            request.sourceOperationId(),
            request.sourceEvidenceDigest(),
            request.expectedCanonicalVersionId(),
            request.gameDesignVersionId(),
            id(9));
    var retried =
        new WorldAuthoredVersionIdentityEvidence.Result(
            retry,
            result.operationId(),
            result.sourceIntakeReceipt(),
            result.versionStateEvidence());
    var decoded =
        WorldAuthoredVersionIdentityGrpcCodec.fromResponse(
            retry, WorldAuthoredVersionIdentityGrpcCodec.toResponse(retried));
    assertThat(decoded.request().readRequestId()).isEqualTo(id(9));
    assertThat(decoded.versionStateEvidence()).isEqualTo(result.versionStateEvidence());
    assertThat(decoded.versionStateEvidence().request().readRequestId())
        .isEqualTo(request.readRequestId());
  }

  @Test
  void rejectsUnknownMissingCorruptAndCrossBoundEvidence() {
    var request = request("test");
    var wire = WorldAuthoredVersionIdentityGrpcCodec.toResponse(committed(request));
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build();
    for (var invalid :
        List.of(
            AssociateAuthoredWorldVersionResponse.getDefaultInstance(),
            wire.toBuilder().setUnknownFields(unknown).build(),
            wire.toBuilder()
                .setRequest(wire.getRequest().toBuilder().setWorldSlug("other"))
                .build(),
            wire.toBuilder()
                .setRequest(wire.getRequest().toBuilder().setReadRequestId(id(9).toString()))
                .build(),
            wire.toBuilder()
                .setRequest(wire.getRequest().toBuilder().setUnknownFields(unknown))
                .build(),
            wire.toBuilder().clearSourceIntakeReceipt().build(),
            wire.toBuilder().clearVersionStateEvidence().build(),
            wire.toBuilder().setOperationId("00000000-0000-0000-0000-000000000000").build(),
            wire.toBuilder()
                .setSourceIntakeReceipt(
                    wire.getSourceIntakeReceipt().toBuilder().setUnknownFields(unknown))
                .build(),
            wire.toBuilder()
                .setVersionStateEvidence(
                    wire.getVersionStateEvidence().toBuilder().setUnknownFields(unknown))
                .build(),
            wire.toBuilder()
                .setVersionStateEvidence(
                    wire.getVersionStateEvidence().toBuilder()
                        .setEvidence(
                            wire.getVersionStateEvidence().getEvidence().toBuilder()
                                .setUnknownFields(unknown)))
                .build(),
            wire.toBuilder()
                .setVersionStateEvidence(
                    wire.getVersionStateEvidence().toBuilder()
                        .setEvidence(
                            wire.getVersionStateEvidence().getEvidence().toBuilder()
                                .setEvidenceDigest("sha256:" + "0".repeat(64))))
                .build(),
            wire.toBuilder()
                .setVersionStateEvidence(
                    wire.getVersionStateEvidence().toBuilder()
                        .setEvidence(
                            wire.getVersionStateEvidence().getEvidence().toBuilder()
                                .setSourceEvidence(
                                    wire
                                        .getVersionStateEvidence()
                                        .getEvidence()
                                        .getSourceEvidence()
                                        .toBuilder()
                                        .setUnknownFields(unknown))))
                .build())) {
      assertThatThrownBy(() -> WorldAuthoredVersionIdentityGrpcCodec.fromResponse(request, invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void rejectsUnsupportedOversizedAndNonCanonicalRequest() {
    var wire = WorldAuthoredVersionIdentityGrpcCodec.toRequest(request("test"));
    for (var invalid :
        List.of(
            wire.toBuilder().setSchemaVersion(2).build(),
            wire.toBuilder().setGameDesignVersionId(0).build(),
            wire.toBuilder().setCanonicalTenantId("1-1-1-1-1").build(),
            wire.toBuilder()
                .setExpectedCanonicalVersionId("00000000-0000-0000-0000-000000000000")
                .build(),
            wire.toBuilder().setReadRequestId(wire.getExpectedCanonicalVersionId()).build(),
            wire.toBuilder().setWorldSlug("x".repeat(40000)).build())) {
      assertThatThrownBy(() -> WorldAuthoredVersionIdentityGrpcCodec.fromRequest(invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  static WorldAuthoredVersionIdentityEvidence.Request request(String namespace) {
    var source = source(namespace);
    return new WorldAuthoredVersionIdentityEvidence.Request(
        1, namespace, id(1), "north-star", id(3), source.evidenceDigest(), id(4), 42L, id(5));
  }

  static WorldAuthoredVersionIdentityEvidence.Result committed(
      WorldAuthoredVersionIdentityEvidence.Request request) {
    var intakeBinding =
        new WorldAuthoredSourceIntakeGrpcCodec.IntakeRequest(
            1,
            request.targetNamespace(),
            id(6),
            request.canonicalTenantId(),
            request.worldSlug(),
            request.sourceOperationId(),
            request.sourceEvidenceDigest());
    var intake =
        new WorldAuthoredSourceIntakeGrpcCodec.CommittedReceipt(
            1,
            request.targetNamespace(),
            id(6),
            id(7),
            request.canonicalTenantId(),
            request.worldSlug(),
            request.sourceOperationId(),
            request.sourceEvidenceDigest(),
            WorldAuthoredSourceIntakeGrpcCodec.requestDigest(intakeBinding),
            "sha256:" + "a".repeat(64));
    var version =
        AuthoredWorldVersionStateEvidence.create(
            request.versionReadRequest(),
            source(request.targetNamespace()),
            request.expectedCanonicalVersionId(),
            VersionLifecycleState.VERSION_LIFECYCLE_STATE_DRAFT,
            1L);
    return new WorldAuthoredVersionIdentityEvidence.Result(request, id(8), intake, version);
  }

  static AuthoredWorldSourceEvidence source(String namespace) {
    var digest =
        AuthoredWorldSourceDigest.requestDigest(
            namespace, id(2), id(1), "north-star", "north-star", "North Star");
    return new AuthoredWorldSourceEvidence(
        1,
        namespace,
        id(2),
        id(3),
        digest,
        id(1),
        "north-star",
        "north-star",
        "North Star",
        42L,
        "game-tenant-42",
        "NEW_GAME_ROW",
        AuthoredWorldSourceDigest.evidenceDigest(
            namespace,
            id(2),
            id(3),
            digest,
            id(1),
            "north-star",
            "north-star",
            "North Star",
            42L,
            "game-tenant-42",
            "NEW_GAME_ROW"));
  }

  static UUID id(int suffix) {
    return UUID.fromString("11111111-1111-4111-8111-" + String.format("%012d", suffix));
  }
}
