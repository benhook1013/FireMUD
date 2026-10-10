package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.UnknownFieldSet;
import net.firedevops.firemud.account.v1.AccountJwtReadinessProbeOwnerServiceGrpc;
import net.firedevops.firemud.account.v1.GetCurrentReadinessProbeOwnerRequest;
import net.firedevops.firemud.account.v1.GetCurrentReadinessProbeOwnerResponse;
import net.firedevops.firemud.account.v1.GetCurrentReadinessReceiverMetadataRequest;
import net.firedevops.firemud.account.v1.ReadinessProbeCoordinates;
import org.junit.jupiter.api.Test;

class AccountJwtReadinessProbeOwnerProtoMapperTest {
  private final AccountJwtReadinessProbeOwnerProtoMapper mapper =
      new AccountJwtReadinessProbeOwnerProtoMapper();

  @Test
  void parsesClosedV2CoordinatesWithExplicitNoActiveSignerFence() {
    var parsed = mapper.parse(request().build());

    assertThat(parsed.planVersion()).isEqualTo(2);
    assertThat(parsed.expectedActive()).isEmpty();
    assertThat(parsed.localIdentity().podUid()).isEqualTo("66666666-6666-4666-8666-666666666666");
    assertThat(parsed.compactTokenSha256()).isEqualTo("d".repeat(64));
  }

  @Test
  void rejectsUnknownActiveStateAndUnknownNestedFields() {
    var unknownState =
        request()
            .setExpectedCoordinates(
                request().getExpectedCoordinates().toBuilder().setExpectedActiveStateValue(3))
            .build();
    assertThatThrownBy(() -> mapper.parse(unknownState))
        .isInstanceOf(
            AccountJwtReadinessProbeOwnerProtoMapper.InvalidOwnerReadRequestException.class);

    UnknownFieldSet unknown =
        UnknownFieldSet.newBuilder()
            .addField(100, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
            .build();
    var unknownLocal =
        request()
            .setProtectedLocalIdentity(
                request().getProtectedLocalIdentity().toBuilder().setUnknownFields(unknown))
            .build();
    assertThatThrownBy(() -> mapper.parse(unknownLocal))
        .isInstanceOf(
            AccountJwtReadinessProbeOwnerProtoMapper.InvalidOwnerReadRequestException.class);
  }

  @Test
  void wireHasNoCompactJwtRequestOrResponseField() {
    assertThat(GetCurrentReadinessProbeOwnerRequest.getDescriptor().findFieldByName("compact_jwt"))
        .isNull();
    assertThat(GetCurrentReadinessProbeOwnerResponse.getDescriptor().findFieldByName("compact_jwt"))
        .isNull();
    assertThat(
            AccountJwtReadinessProbeOwnerServiceGrpc.getGetCurrentReadinessProbeOwnerMethod()
                .getFullMethodName())
        .isEqualTo("account.v1.AccountJwtReadinessProbeOwnerService/GetCurrentReadinessProbeOwner");
  }

  @Test
  void receiverMetadataRequestIsClosedAndContainsOnlyCanonicalLocalSelectors() {
    var request =
        GetCurrentReadinessReceiverMetadataRequest.newBuilder()
            .setSchemaVersion(1)
            .setProjectedPodUid("66666666-6666-4666-8666-666666666666")
            .setServerLeafSpkiSha256("a".repeat(64))
            .build();

    var parsed = mapper.parseReceiverMetadataRequest(request);

    assertThat(parsed.projectedPodUid()).isEqualTo("66666666-6666-4666-8666-666666666666");
    assertThat(parsed.serverLeafSpkiSha256()).isEqualTo("a".repeat(64));
    assertThatThrownBy(
            () ->
                mapper.parseReceiverMetadataRequest(
                    GetCurrentReadinessReceiverMetadataRequest.getDefaultInstance()))
        .isInstanceOf(
            AccountJwtReadinessProbeOwnerProtoMapper.InvalidOwnerReadRequestException.class);
    assertThatThrownBy(
            () ->
                mapper.parseReceiverMetadataRequest(
                    request.toBuilder()
                        .setProjectedPodUid("66666666-6666-4666-8666-66666666666A")
                        .build()))
        .isInstanceOf(
            AccountJwtReadinessProbeOwnerProtoMapper.InvalidOwnerReadRequestException.class);
    assertThatThrownBy(
            () ->
                mapper.parseReceiverMetadataRequest(
                    request.toBuilder().setServerLeafSpkiSha256("A".repeat(64)).build()))
        .isInstanceOf(
            AccountJwtReadinessProbeOwnerProtoMapper.InvalidOwnerReadRequestException.class);
    assertThatThrownBy(
            () ->
                mapper.parseReceiverMetadataRequest(
                    request.toBuilder().setProjectedPodUid("6".repeat(4096)).build()))
        .isInstanceOf(
            AccountJwtReadinessProbeOwnerProtoMapper.InvalidOwnerReadRequestException.class);
    UnknownFieldSet unknown =
        UnknownFieldSet.newBuilder()
            .addField(100, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
            .build();
    assertThatThrownBy(
            () ->
                mapper.parseReceiverMetadataRequest(
                    request.toBuilder().setUnknownFields(unknown).build()))
        .isInstanceOf(
            AccountJwtReadinessProbeOwnerProtoMapper.InvalidOwnerReadRequestException.class);
  }

  @Test
  void rejectsReservedNumericTrustRevisionAndRequiresExactOpaqueSourceRevisionAndHash() {
    UnknownFieldSet reservedNumericRevision =
        UnknownFieldSet.newBuilder()
            .addField(14, UnknownFieldSet.Field.newBuilder().addVarint(4L).build())
            .build();
    var oldRevision =
        request()
            .setProtectedLocalIdentity(
                request().getProtectedLocalIdentity().toBuilder()
                    .setUnknownFields(reservedNumericRevision))
            .build();
    assertThatThrownBy(() -> mapper.parse(oldRevision))
        .isInstanceOf(
            AccountJwtReadinessProbeOwnerProtoMapper.InvalidOwnerReadRequestException.class);

    var mismatchedOpaqueRevision =
        request()
            .setProtectedLocalIdentity(
                request().getProtectedLocalIdentity().toBuilder()
                    .setAccountJwksTrustBindingRevision("other-r2"))
            .build();
    assertThatThrownBy(() -> mapper.parse(mismatchedOpaqueRevision))
        .isInstanceOf(
            AccountJwtReadinessProbeOwnerProtoMapper.InvalidOwnerReadRequestException.class);

    var missingPublicJwksHash =
        request()
            .setProtectedLocalIdentity(
                request().getProtectedLocalIdentity().toBuilder().clearAccountPublicJwksSha256())
            .build();
    assertThatThrownBy(() -> mapper.parse(missingPublicJwksHash))
        .isInstanceOf(
            AccountJwtReadinessProbeOwnerProtoMapper.InvalidOwnerReadRequestException.class);
  }

  private static GetCurrentReadinessProbeOwnerRequest.Builder request() {
    var coordinates =
        ReadinessProbeCoordinates.newBuilder()
            .setRotationOperationId("33333333-3333-4333-8333-333333333333")
            .setOperationDigest("b".repeat(64))
            .setPlanDigest("c".repeat(64))
            .setPlanVersion(2)
            .setRegistryVersion(1)
            .setValidatorId("game-session-service")
            .setTokenProfile("game-session-account-delegation")
            .setAudience("account-service")
            .setProbeKind(ReadinessProbeCoordinates.ProbeKind.REPRESENTATIVE)
            .setExpectedOutcome(ReadinessProbeCoordinates.ExpectedOutcome.ACCEPT)
            .setJti("44444444-4444-4444-8444-444444444444")
            .setEntryVersion(2)
            .setTargetGeneration("1")
            .setTargetKid("pending-key")
            .setExpectedActiveState(ReadinessProbeCoordinates.ExpectedActiveState.ABSENT)
            .setIssuedAtEpochSeconds(20_100)
            .setExpiresAtEpochSeconds(20_300)
            .setPlanExpiresAtEpochSeconds(20_300);
    var source =
        net.firedevops.firemud.account.v1.AccountSourceIdentity.newBuilder()
            .setEnvironmentId("prod")
            .setClusterId("cluster-a")
            .setClusterIncarnationUid("11111111-1111-4111-8111-111111111111")
            .setNamespace("firemud")
            .setNamespaceUid("22222222-2222-4222-8222-222222222222")
            .setConfigMapUid("77777777-7777-4777-8777-777777777777")
            .setBindingRevision("source-r1")
            .setApiServerOrigin("https://api.example.test:6443")
            .setServingCaSha256("8".repeat(64));
    var local =
        net.firedevops.firemud.account.v1.ReadinessReceiverLocalIdentity.newBuilder()
            .setValidatorId("game-session-service")
            .setDeploymentUid("55555555-5555-4555-8555-555555555555")
            .setPodUid("66666666-6666-4666-8666-666666666666")
            .setPodIp("10.0.0.1")
            .setDirectPodEndpoint("grpcs://10.0.0.1:9443")
            .setCanonicalServiceUri("spiffe://firemud/ns/firemud/sa/game-session-jwt-validator")
            .setImage("registry.example/validator@sha256:" + "e".repeat(64))
            .setVerifierConfigSha256("f".repeat(64))
            .setApplicabilityMatrixDigest("a".repeat(64))
            .setSourceInventoryRevision("gs-source-r1")
            .setSourceInventoryDigest("9".repeat(64))
            .setServerLeafSpkiSha256("8".repeat(64))
            .setAccountJwksSourceIdentity(source)
            .setAccountJwksTrustBindingRevision("source-r1")
            .setAccountPublicJwksSha256("7".repeat(64));
    return GetCurrentReadinessProbeOwnerRequest.newBuilder()
        .setSchemaVersion(1)
        .setExpectedCoordinates(coordinates)
        .setCompactTokenSha256("d".repeat(64))
        .setProtectedLocalIdentity(local);
  }
}
