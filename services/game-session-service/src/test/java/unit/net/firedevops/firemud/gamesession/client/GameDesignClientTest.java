package net.firedevops.firemud.gamesession.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorGrpcCodec;
import net.firedevops.firemud.common.grpc.AbstractBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamedesign.v1.GameDesignServiceGrpc;
import net.firedevops.firemud.gamedesign.v1.GetPublishedScriptPatchVersionResponse;
import net.firedevops.firemud.gamedesign.v1.ResolveLaunchDescriptorRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveLaunchDescriptorResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class GameDesignClientTest {
  private static final String CONTROL_PLANE_REQUEST_ID = "7f1e5306-e9a7-46d9-b5cf-75348e20ffbd";
  private static final String SOURCE_OPERATION_ID = "3f773701-e812-44f5-8ae0-7268d543beed";

  @Test
  void typedSourceResolveUsesExistingProtoFieldsAndReturnsSupportedV1Descriptor() throws Exception {
    GameDesignServiceGrpc.GameDesignServiceBlockingStub stub =
        mock(GameDesignServiceGrpc.GameDesignServiceBlockingStub.class);
    doReturn(stub).when(stub).withDeadlineAfter(anyLong(), any(TimeUnit.class));
    GameDesignClient client = newClient(stub);
    AuthoredWorldSourceEvidence sourceEvidence = sourceEvidence();
    AuthoredWorldLaunchDescriptorEvidence.Request expectedRequest =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            sourceEvidence.targetNamespace(),
            sourceEvidence.registrationRequestId().toString(),
            sourceEvidence.canonicalTenantId(),
            sourceEvidence.worldSlug(),
            sourceEvidence.operationId(),
            sourceEvidence.evidenceDigest(),
            810L,
            false,
            null,
            false,
            null,
            false,
            null,
            false,
            null);
    var supportedDescriptor =
        AuthoredWorldLaunchDescriptorGrpcCodec.toLaunchDescriptorMessage(
            AuthoredWorldLaunchDescriptorEvidence.create(
                expectedRequest,
                "ld-fixture-source",
                11L,
                false,
                null,
                "{}",
                "genrev-11",
                77L,
                77L,
                "prb:41:11:77",
                false,
                null));
    ResolveLaunchDescriptorResponse response =
        ResolveLaunchDescriptorResponse.newBuilder()
            .setLaunchDescriptor(supportedDescriptor)
            .build();
    when(stub.resolveLaunchDescriptor(any(ResolveLaunchDescriptorRequest.class)))
        .thenReturn(response);

    ResolveLaunchDescriptorResponse actual =
        client.resolveLaunchDescriptor(
            41L, 810L, sourceEvidence.registrationRequestId().toString(), sourceEvidence);

    ArgumentCaptor<ResolveLaunchDescriptorRequest> request =
        ArgumentCaptor.forClass(ResolveLaunchDescriptorRequest.class);
    org.mockito.Mockito.verify(stub).resolveLaunchDescriptor(request.capture());
    assertThat(request.getValue().getTenantId()).isEqualTo("41");
    assertThat(request.getValue().getWorldSlug()).isEqualTo(sourceEvidence.worldSlug());
    assertThat(request.getValue().getControlPlaneRequestId())
        .isEqualTo(sourceEvidence.registrationRequestId().toString());
    assertThat(request.getValue().getAuthoredWorldSourceOperationId())
        .isEqualTo(sourceEvidence.operationId().toString());
    assertThat(request.getValue().getExpectedAuthoredWorldSourceEvidenceDigest())
        .isEqualTo(sourceEvidence.evidenceDigest());
    assertThat(request.getValue().getCanonicalTenantId())
        .isEqualTo(sourceEvidence.canonicalTenantId().toString());
    assertThat(actual).isEqualTo(response);
    assertThat(
            AuthoredWorldLaunchDescriptorGrpcCodec.fromLaunchDescriptorMessage(
                expectedRequest, actual.getLaunchDescriptor()))
        .isNotNull();
  }

  @ParameterizedTest
  @ValueSource(longs = {0L, -1L})
  void rejectsNonpositiveBaseVersionAsInvalidArgumentWithoutCallingGameDesign(long baseVersionId)
      throws Exception {
    GameDesignServiceGrpc.GameDesignServiceBlockingStub stub =
        mock(GameDesignServiceGrpc.GameDesignServiceBlockingStub.class);
    GameDesignClient client = newClient(stub);

    GetPublishedScriptPatchVersionResponse response =
        client.getPublishedScriptPatchVersion(1L, "patch-1", baseVersionId);

    assertThat(response.getError().getCode()).isEqualTo("INVALID_ARGUMENT");
    assertThat(response.getError().getMessage())
        .isEqualTo("base_version_id must be positive for script-patch publication lookup");
    verifyNoInteractions(stub);
  }

  @Test
  void legacyNumericLaunchResolveFailsClosedWithoutStubOrChannelInteraction() throws Exception {
    GameDesignServiceGrpc.GameDesignServiceBlockingStub stub =
        mock(GameDesignServiceGrpc.GameDesignServiceBlockingStub.class);
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    GameDesignClient client = newClient(stub, channelFactory);

    ResolveLaunchDescriptorResponse response = client.resolveLaunchDescriptor(1L, 9L, "cp-1");

    assertLaunchBindingRequired(response);
    verifyNoInteractions(stub, channelFactory);
  }

  @Test
  void legacyNumericReplacementLaunchResolveFailsClosedWithoutStubOrChannelInteraction()
      throws Exception {
    GameDesignServiceGrpc.GameDesignServiceBlockingStub stub =
        mock(GameDesignServiceGrpc.GameDesignServiceBlockingStub.class);
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    GameDesignClient client = newClient(stub, channelFactory);

    ResolveLaunchDescriptorResponse response =
        client.resolveLaunchDescriptor(1L, 9L, "cp-1", 7L, 8L);

    assertLaunchBindingRequired(response);
    verifyNoInteractions(stub, channelFactory);
  }

  private static void assertLaunchBindingRequired(ResolveLaunchDescriptorResponse response) {
    assertThat(response.getError().getCode()).isEqualTo("AUTHORED_WORLD_LAUNCH_BINDING_REQUIRED");
    assertThat(response.getError().getMessage())
        .contains("Canonical authored-world source binding is required");
  }

  private static GameDesignClient newClient(
      GameDesignServiceGrpc.GameDesignServiceBlockingStub stub) throws Exception {
    return newClient(stub, mock(GrpcChannelFactory.class));
  }

  private static GameDesignClient newClient(
      GameDesignServiceGrpc.GameDesignServiceBlockingStub stub, GrpcChannelFactory channelFactory)
      throws Exception {
    GameDesignClient client =
        new GameDesignClient(
            new ServiceEndpointsProperties(),
            new CommonGrpcClientProperties(),
            channelFactory,
            BlockingGrpcStubCustomizer.noop());
    Field field = AbstractBlockingGrpcClient.class.getDeclaredField("stub");
    field.setAccessible(true);
    field.set(client, stub);
    return client;
  }

  private static AuthoredWorldSourceEvidence sourceEvidence() {
    String targetNamespace = "dev";
    UUID registrationRequestId = UUID.fromString(CONTROL_PLANE_REQUEST_ID);
    UUID operationId = UUID.fromString(SOURCE_OPERATION_ID);
    UUID canonicalTenantId = UUID.fromString("9db3ab6f-cc09-49d2-8f93-84484397a120");
    String tenantSlug = "fixture-tenant";
    String worldSlug = "demo";
    String worldDisplayName = "Demo World";
    long sourceGameRowId = 29L;
    String sourceGameTenantKey = "41";
    String provenanceKind = "NEW_GAME_ROW";
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            targetNamespace,
            registrationRequestId,
            canonicalTenantId,
            tenantSlug,
            worldSlug,
            worldDisplayName);
    return new AuthoredWorldSourceEvidence(
        1,
        targetNamespace,
        registrationRequestId,
        operationId,
        requestDigest,
        canonicalTenantId,
        tenantSlug,
        worldSlug,
        worldDisplayName,
        sourceGameRowId,
        sourceGameTenantKey,
        provenanceKind,
        AuthoredWorldSourceDigest.evidenceDigest(
            targetNamespace,
            registrationRequestId,
            operationId,
            requestDigest,
            canonicalTenantId,
            tenantSlug,
            worldSlug,
            worldDisplayName,
            sourceGameRowId,
            sourceGameTenantKey,
            provenanceKind));
  }
}
