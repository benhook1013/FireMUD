package net.firedevops.firemud.gamedesign.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
import java.time.LocalDateTime;
import java.util.List;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import net.firedevops.firemud.gamedesign.dto.VersionDto;
import net.firedevops.firemud.gamedesign.model.PublishGateFailureCode;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.service.PublishGateFailureException;
import net.firedevops.firemud.gamedesign.service.impl.PublishGateServiceImpl;
import net.firedevops.firemud.worldmanagement.v1.GetDraftDesignDigestRequest;
import net.firedevops.firemud.worldmanagement.v1.GetDraftDesignDigestResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldManagementServiceGrpc;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Wire mapping and gate proof with a controlled World response, not live producer authority. */
class WorldManagementClientTest {
  private static final PublicationDigestRequestBinding BINDING =
      PublicationDigestRequestBinding.full("tenant-é", "7", "request-é");

  @Test
  void exactWorldSchemaThreeResponseReachesGateWithoutRewritingItsEvidence() throws Exception {
    var stub = mock(WorldManagementServiceGrpc.WorldManagementServiceBlockingStub.class);
    var response = response(3).build();
    when(stub.getDraftDesignDigest(any(GetDraftDesignDigestRequest.class))).thenReturn(response);
    var client = client(stub);

    var digest = client.getDraftDesignDigestForVersion(BINDING);

    assertThat(digest.succeeded()).isTrue();
    assertThat(digest.digestSchemaVersion()).isEqualTo(3);
    assertThat(digest.contentDigest()).isEqualTo(response.getContentDigest());
    assertThat(digest.appliedCommitId()).isEqualTo(response.getAppliedCommitId());
    gate().assertGatePassed(version(), participants(digest));
    var request = ArgumentCaptor.forClass(GetDraftDesignDigestRequest.class);
    verify(stub).getDraftDesignDigest(request.capture());
    assertThat(request.getValue().getTenantId()).isEqualTo(BINDING.tenantId());
    assertThat(request.getValue().getVersionId()).isEqualTo(BINDING.versionId());
    assertThat(request.getValue().getPublishRequestId()).isEqualTo(BINDING.publishRequestId());
    assertThat(request.getValue().getDerivedWorkflowIdentity())
        .isEqualTo(BINDING.derivedWorkflowIdentity());
    assertThat(request.getValue().getRequestDigest()).isEqualTo(BINDING.requestDigest());
  }

  @Test
  void obsoleteAndUnselectedWorldFramesRemainUnchangedAndAreRejectedByGate() throws Exception {
    var stub = mock(WorldManagementServiceGrpc.WorldManagementServiceBlockingStub.class);
    var client = client(stub);
    for (int schema : List.of(2, 4)) {
      when(stub.getDraftDesignDigest(any(GetDraftDesignDigestRequest.class)))
          .thenReturn(response(schema).build());
      var digest = client.getDraftDesignDigestForVersion(BINDING);
      assertThat(digest.digestSchemaVersion()).isEqualTo(schema);
      assertThatThrownBy(() -> gate().assertGatePassed(version(), participants(digest)))
          .isInstanceOfSatisfying(
              PublishGateFailureException.class,
              failure ->
                  assertThat(failure.failureCode())
                      .isEqualTo(PublishGateFailureCode.UNSUPPORTED_DIGEST_SCHEMA));
    }
  }

  @Test
  void schemaThreeCannotBypassExactScopeOrOneSharedCommit() throws Exception {
    var stub = mock(WorldManagementServiceGrpc.WorldManagementServiceBlockingStub.class);
    var client = client(stub);
    for (var mismatch :
        List.of(
            response(3).setTenantId("other"),
            response(3).setVersionId("8"),
            response(3).setBaseVersionId("6"))) {
      when(stub.getDraftDesignDigest(any(GetDraftDesignDigestRequest.class)))
          .thenReturn(mismatch.build());
      var digest = client.getDraftDesignDigestForVersion(BINDING);
      assertThat(digest.succeeded()).isFalse();
      assertThat(digest.errorCode()).isEqualTo("RESPONSE_BINDING_MISMATCH");
    }
    when(stub.getDraftDesignDigest(any(GetDraftDesignDigestRequest.class)))
        .thenReturn(response(3).setAppliedCommitId("other-commit").build());
    var digest = client.getDraftDesignDigestForVersion(BINDING);
    assertThatThrownBy(() -> gate().assertGatePassed(version(), participants(digest)))
        .isInstanceOfSatisfying(
            PublishGateFailureException.class,
            failure ->
                assertThat(failure.failureCode())
                    .isEqualTo(PublishGateFailureCode.APPLIED_COMMIT_MISMATCH));
  }

  private static GetDraftDesignDigestResponse.Builder response(int schema) {
    return GetDraftDesignDigestResponse.newBuilder()
        .setTenantId(BINDING.tenantId())
        .setVersionId(BINDING.versionId())
        .setAppliedCommitId("commit-7")
        .setContentDigest("a".repeat(64))
        .setDigestSchemaVersion(schema);
  }

  private static List<PublishParticipantDigestDto> participants(PublishParticipantDigestDto world) {
    return List.of(
        world,
        new PublishParticipantDigestDto(
            "ENTITY_MANAGEMENT", "7", "commit-7", "entity", 1, null, null),
        new PublishParticipantDigestDto("GAME_LOGIC", "7", "commit-7", "logic", 1, null, null),
        new PublishParticipantDigestDto(
            "AUTOMATION_SCRIPTING", "7", "commit-7", "script", 5, null, null),
        new PublishParticipantDigestDto(
            "GAME_DESIGN_CONTROL_PLANE", "7", "commit-7", "design", 1, null, null));
  }

  private static PublishGateServiceImpl gate() {
    return new PublishGateServiceImpl(
        null,
        null,
        null,
        null,
        null,
        mock(org.jooq.DSLContext.class),
        mock(org.springframework.transaction.PlatformTransactionManager.class),
        "");
  }

  private static VersionDto version() {
    return new VersionDto(
        7L,
        BINDING.tenantId(),
        8,
        VersionLifecycleState.PUBLISHED,
        2L,
        null,
        null,
        false,
        "notes",
        LocalDateTime.now(),
        LocalDateTime.now());
  }

  private static TestWorldManagementClient client(
      WorldManagementServiceGrpc.WorldManagementServiceBlockingStub stub) throws Exception {
    var properties = new CommonGrpcClientProperties();
    properties.setPlaintext(true);
    var client = new TestWorldManagementClient(properties, stub);
    client.init();
    return client;
  }

  private static final class TestWorldManagementClient extends WorldManagementClient {
    private final WorldManagementServiceGrpc.WorldManagementServiceBlockingStub stub;

    private TestWorldManagementClient(
        CommonGrpcClientProperties properties,
        WorldManagementServiceGrpc.WorldManagementServiceBlockingStub stub) {
      super(
          new ServiceEndpointsProperties(),
          properties,
          mock(GrpcChannelFactory.class),
          BlockingGrpcStubCustomizer.noop());
      this.stub = stub;
    }

    @Override
    protected WorldManagementServiceGrpc.WorldManagementServiceBlockingStub buildStub(
        ManagedChannel channel) {
      return applyStubCustomizer(stub);
    }
  }
}
