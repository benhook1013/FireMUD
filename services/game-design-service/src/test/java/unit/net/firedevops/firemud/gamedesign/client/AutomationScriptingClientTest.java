package net.firedevops.firemud.gamedesign.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.automationscripting.v1.AutomationScriptingServiceGrpc;
import net.firedevops.firemud.automationscripting.v1.GetDraftDesignDigestRequest;
import net.firedevops.firemud.automationscripting.v1.GetDraftDesignDigestResponse;
import net.firedevops.firemud.automationscripting.v1.NotifyScriptVersionUpdateRequest;
import net.firedevops.firemud.automationscripting.v1.NotifyScriptVersionUpdateResponse;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import org.junit.jupiter.api.Test;

class AutomationScriptingClientTest {

  @Test
  void buildStubAppliesInjectedStubCustomizer() {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    CommonGrpcClientProperties grpc = new CommonGrpcClientProperties();
    grpc.setPlaintext(true);
    AutomationScriptingServiceGrpc.AutomationScriptingServiceBlockingStub stub =
        mock(AutomationScriptingServiceGrpc.AutomationScriptingServiceBlockingStub.class);
    AtomicReference<AutomationScriptingServiceGrpc.AutomationScriptingServiceBlockingStub>
        customized = new AtomicReference<>();
    BlockingGrpcStubCustomizer stubCustomizer =
        new BlockingGrpcStubCustomizer() {
          @Override
          public <T extends io.grpc.stub.AbstractStub<T>> T customize(T candidate) {
            customized.set(
                (AutomationScriptingServiceGrpc.AutomationScriptingServiceBlockingStub) candidate);
            return candidate;
          }
        };
    TestAutomationScriptingClient client =
        new TestAutomationScriptingClient(
            endpoints, grpc, mock(GrpcChannelFactory.class), stubCustomizer, stub);

    client.buildStub(mock(ManagedChannel.class));

    assertThat(customized.get()).isSameAs(stub);
  }

  @Test
  void patchDigestReadCarriesBindingAndRejectsMismatchedTenant() throws Exception {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    CommonGrpcClientProperties grpc = new CommonGrpcClientProperties();
    grpc.setPlaintext(true);
    AutomationScriptingServiceGrpc.AutomationScriptingServiceBlockingStub stub =
        mock(AutomationScriptingServiceGrpc.AutomationScriptingServiceBlockingStub.class);
    PublicationDigestRequestBinding binding =
        PublicationDigestRequestBinding.patch("tenant-é", "7", "patch:é:1", "req-é");
    when(stub.getDraftDesignDigest(any(GetDraftDesignDigestRequest.class)))
        .thenReturn(
            GetDraftDesignDigestResponse.newBuilder()
                .setTenantId("other-tenant")
                .setScriptPatchVersion(binding.scriptPatchVersion())
                .setBaseVersionId(binding.baseVersionId())
                .setAppliedCommitId("commit-7")
                .setContentDigest("digest-7")
                .setDigestSchemaVersion(4)
                .build());
    TestAutomationScriptingClient client =
        new TestAutomationScriptingClient(
            endpoints,
            grpc,
            mock(GrpcChannelFactory.class),
            BlockingGrpcStubCustomizer.noop(),
            stub);
    client.initialize();

    var digest = client.getDraftDesignDigestForScriptPatch(binding);

    assertThat(digest.succeeded()).isFalse();
    assertThat(digest.errorCode()).isEqualTo("RESPONSE_BINDING_MISMATCH");
    var requestCaptor = org.mockito.ArgumentCaptor.forClass(GetDraftDesignDigestRequest.class);
    verify(stub).getDraftDesignDigest(requestCaptor.capture());
    GetDraftDesignDigestRequest request = requestCaptor.getValue();
    assertThat(request.getTenantId()).isEqualTo(binding.tenantId());
    assertThat(request.getBaseVersionId()).isEqualTo(binding.baseVersionId());
    assertThat(request.getScriptPatchVersion()).isEqualTo(binding.scriptPatchVersion());
    assertThat(request.getPublishRequestId()).isEqualTo(binding.publishRequestId());
    assertThat(request.getDerivedWorkflowIdentity()).isEqualTo(binding.derivedWorkflowIdentity());
    assertThat(request.getRequestDigest()).isEqualTo(binding.requestDigest());
  }

  @Test
  void notificationWithoutAcceptedReadinessFailsClosed() throws Exception {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    CommonGrpcClientProperties grpc = new CommonGrpcClientProperties();
    grpc.setPlaintext(true);
    AutomationScriptingServiceGrpc.AutomationScriptingServiceBlockingStub stub =
        mock(AutomationScriptingServiceGrpc.AutomationScriptingServiceBlockingStub.class);
    when(stub.notifyScriptVersionUpdate(any(NotifyScriptVersionUpdateRequest.class)))
        .thenReturn(NotifyScriptVersionUpdateResponse.newBuilder().setSuccess(false).build());
    TestAutomationScriptingClient client =
        new TestAutomationScriptingClient(
            endpoints,
            grpc,
            mock(GrpcChannelFactory.class),
            BlockingGrpcStubCustomizer.noop(),
            stub);
    client.initialize();

    assertThatThrownBy(
            () -> client.notifyScriptVersionUpdate("tenant-1", "patch-1", java.util.List.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SCRIPT_PATCH_NOTIFICATION_REJECTED");
    verify(stub).notifyScriptVersionUpdate(any(NotifyScriptVersionUpdateRequest.class));
  }

  private static final class TestAutomationScriptingClient extends AutomationScriptingClient {
    private final AutomationScriptingServiceGrpc.AutomationScriptingServiceBlockingStub stub;

    private TestAutomationScriptingClient(
        ServiceEndpointsProperties endpoints,
        CommonGrpcClientProperties tlsProps,
        GrpcChannelFactory channelFactory,
        BlockingGrpcStubCustomizer stubCustomizer,
        AutomationScriptingServiceGrpc.AutomationScriptingServiceBlockingStub stub) {
      super(endpoints, tlsProps, channelFactory, stubCustomizer);
      this.stub = stub;
    }

    private void initialize() throws Exception {
      initReloadingClient();
    }

    @Override
    protected AutomationScriptingServiceGrpc.AutomationScriptingServiceBlockingStub buildStub(
        ManagedChannel channel) {
      return applyStubCustomizer(stub);
    }
  }
}
