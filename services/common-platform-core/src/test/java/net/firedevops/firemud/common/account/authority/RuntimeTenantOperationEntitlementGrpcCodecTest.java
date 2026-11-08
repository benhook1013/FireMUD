package net.firedevops.firemud.common.account.authority;

import static net.firedevops.firemud.common.account.authority.TenantOperationEntitlementTransportFixture.evidence;
import static net.firedevops.firemud.common.account.authority.TenantOperationEntitlementTransportFixture.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.util.UUID;
import net.firedevops.firemud.account.v1.ReadTenantOperationEntitlementRequest;
import net.firedevops.firemud.common.account.authority.RuntimeTenantOperationEntitlementGrpcCodec.SourceKind;
import org.junit.jupiter.api.Test;

class RuntimeTenantOperationEntitlementGrpcCodecTest {
  @Test
  void bothExplicitSourcesPreserveExactCanonicalEvidence() {
    for (var source : SourceKind.values()) {
      var request = request(source);
      var evidence = evidence(request);
      assertThat(
              RuntimeTenantOperationEntitlementGrpcCodec.parseRequest(
                  RuntimeTenantOperationEntitlementGrpcCodec.toRequest(request)))
          .isEqualTo(request);
      assertThat(
              RuntimeTenantOperationEntitlementGrpcCodec.requireResponse(
                  request,
                  RuntimeTenantOperationEntitlementGrpcCodec.toResponse(request, evidence)))
          .isEqualTo(evidence);
    }
  }

  @Test
  void rejectsMissingDuplicateUnknownUnboundedAndNoncanonicalRequestMembers() throws Exception {
    var wire =
        RuntimeTenantOperationEntitlementGrpcCodec.toRequest(request(SourceKind.PAID_HOSTING));
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build();
    for (var invalid :
        new ReadTenantOperationEntitlementRequest[] {
          wire.toBuilder().clearSourceKind().build(),
          wire.toBuilder().addSourceKind("PAID_HOSTING").build(),
          wire.toBuilder().addTenantId(wire.getTenantId(0)).build(),
          wire.toBuilder().addRequestId(wire.getRequestId(0)).build(),
          wire.toBuilder().addSchemaVersion(1).build(),
          wire.toBuilder().addTargetNamespace("firemud").build(),
          wire.toBuilder().setSourceKind(0, "DEFAULT").build(),
          wire.toBuilder().setTenantId(0, "00000000-0000-0000-0000-000000000000").build(),
          wire.toBuilder().setRequestId(0, "11111111111141118111111111111111").build(),
          wire.toBuilder().setTargetNamespace(0, "a".repeat(2048)).build(),
          wire.toBuilder().setUnknownFields(unknown).build()
        }) {
      assertThatThrownBy(() -> RuntimeTenantOperationEntitlementGrpcCodec.parseRequest(invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }
    // Actual repeated protobuf field occurrences survive parsing for cardinality rejection.
    byte[] bytes = wire.toByteArray();
    byte[] duplicate = new byte[bytes.length * 2];
    System.arraycopy(bytes, 0, duplicate, 0, bytes.length);
    System.arraycopy(bytes, 0, duplicate, bytes.length, bytes.length);
    assertThatThrownBy(
            () ->
                RuntimeTenantOperationEntitlementGrpcCodec.parseRequest(
                    ReadTenantOperationEntitlementRequest.parseFrom(duplicate)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsResponseCardinalityUnknownFieldsBindingsAndBadCanonicalValue() {
    var request = request(SourceKind.PAID_HOSTING);
    var evidence = evidence(request);
    var wire = RuntimeTenantOperationEntitlementGrpcCodec.toResponse(request, evidence);
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build();
    for (var invalid :
        java.util.List.of(
            wire.toBuilder().clearRequest().build(),
            wire.toBuilder().addRequest(wire.getRequest(0)).build(),
            wire.toBuilder().addCanonicalEvidence(wire.getCanonicalEvidence(0)).build(),
            wire.toBuilder().clearCanonicalEvidence().build(),
            wire.toBuilder().setCanonicalEvidence(0, ByteString.copyFromUtf8("{}")).build(),
            wire.toBuilder().setCanonicalEvidence(0, ByteString.copyFrom(new byte[65537])).build(),
            wire.toBuilder().setUnknownFields(unknown).build(),
            wire.toBuilder()
                .setRequest(0, wire.getRequest(0).toBuilder().setSourceKind(0, "NON_PAID_DEMO"))
                .build())) {
      assertThatThrownBy(
              () -> RuntimeTenantOperationEntitlementGrpcCodec.requireResponse(request, invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }
    for (var changed :
        java.util.List.of(
            new RuntimeTenantOperationEntitlementGrpcCodec.Request(
                "other", request.tenantId(), request.requestId(), request.sourceKind()),
            new RuntimeTenantOperationEntitlementGrpcCodec.Request(
                "firemud", UUID.randomUUID(), request.requestId(), request.sourceKind()),
            new RuntimeTenantOperationEntitlementGrpcCodec.Request(
                "firemud", request.tenantId(), UUID.randomUUID(), request.sourceKind()),
            request(SourceKind.NON_PAID_DEMO))) {
      var substituted =
          wire.toBuilder()
              .setRequest(0, RuntimeTenantOperationEntitlementGrpcCodec.toRequest(changed))
              .build();
      assertThatThrownBy(
              () ->
                  RuntimeTenantOperationEntitlementGrpcCodec.requireResponse(changed, substituted))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
