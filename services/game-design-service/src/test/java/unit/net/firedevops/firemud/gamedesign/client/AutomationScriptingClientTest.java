package net.firedevops.firemud.gamedesign.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.automationscripting.v1.AutomationScriptingServiceGrpc;
import net.firedevops.firemud.automationscripting.v1.GetDraftDesignDigestRequest;
import net.firedevops.firemud.automationscripting.v1.GetDraftDesignDigestResponse;
import net.firedevops.firemud.automationscripting.v1.NotifyScriptVersionUpdateRequest;
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
  void fullDigestReadRejectsLegacyResponseWithoutTypedScope() throws Exception {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    CommonGrpcClientProperties grpc = new CommonGrpcClientProperties();
    grpc.setPlaintext(true);
    AutomationScriptingServiceGrpc.AutomationScriptingServiceBlockingStub stub =
        mock(AutomationScriptingServiceGrpc.AutomationScriptingServiceBlockingStub.class);
    PublicationDigestRequestBinding binding =
        PublicationDigestRequestBinding.full("tenant-1", "7", "req-1");
    when(stub.getDraftDesignDigest(any(GetDraftDesignDigestRequest.class)))
        .thenReturn(
            GetDraftDesignDigestResponse.newBuilder()
                .setTenantId(binding.tenantId())
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

    var digest = client.getDraftDesignDigestForVersion(binding);

    assertThat(digest.succeeded()).isFalse();
    assertThat(digest.errorCode()).isEqualTo("RESPONSE_BINDING_MISMATCH");
  }

  @Test
  void patchDigestReadRejectsLegacyResponseWithoutTypedScope() throws Exception {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    CommonGrpcClientProperties grpc = new CommonGrpcClientProperties();
    grpc.setPlaintext(true);
    AutomationScriptingServiceGrpc.AutomationScriptingServiceBlockingStub stub =
        mock(AutomationScriptingServiceGrpc.AutomationScriptingServiceBlockingStub.class);
    PublicationDigestRequestBinding binding =
        PublicationDigestRequestBinding.patch("tenant-1", "7", "patch-1", "req-1");
    when(stub.getDraftDesignDigest(any(GetDraftDesignDigestRequest.class)))
        .thenReturn(
            GetDraftDesignDigestResponse.newBuilder()
                .setTenantId(binding.tenantId())
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
  }

  @Test
  void scriptPatchNotificationCarriesExactBaseVersion() throws Exception {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    CommonGrpcClientProperties grpc = new CommonGrpcClientProperties();
    grpc.setPlaintext(true);
    AutomationScriptingServiceGrpc.AutomationScriptingServiceBlockingStub stub =
        mock(AutomationScriptingServiceGrpc.AutomationScriptingServiceBlockingStub.class);
    TestAutomationScriptingClient client =
        new TestAutomationScriptingClient(
            endpoints,
            grpc,
            mock(GrpcChannelFactory.class),
            BlockingGrpcStubCustomizer.noop(),
            stub);
    client.initialize();

    client.notifyScriptVersionUpdate("tenant-1", 7L, "patch-7", java.util.List.of("script-a"));

    var requestCaptor = org.mockito.ArgumentCaptor.forClass(NotifyScriptVersionUpdateRequest.class);
    verify(stub).notifyScriptVersionUpdate(requestCaptor.capture());
    NotifyScriptVersionUpdateRequest request = requestCaptor.getValue();
    assertThat(request.getTenantId()).isEqualTo("tenant-1");
    assertThat(request.getBaseVersionId()).isEqualTo(7L);
    assertThat(request.getScriptPatchVersion()).isEqualTo("patch-7");
    assertThat(request.getAffectedScriptsList()).containsExactly("script-a");
  }

  @Test
  void scriptPatchNotificationRejectsMissingOrNonpositiveBaseBeforeCallingAutomation()
      throws Exception {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    CommonGrpcClientProperties grpc = new CommonGrpcClientProperties();
    grpc.setPlaintext(true);
    AutomationScriptingServiceGrpc.AutomationScriptingServiceBlockingStub stub =
        mock(AutomationScriptingServiceGrpc.AutomationScriptingServiceBlockingStub.class);
    TestAutomationScriptingClient client =
        new TestAutomationScriptingClient(
            endpoints,
            grpc,
            mock(GrpcChannelFactory.class),
            BlockingGrpcStubCustomizer.noop(),
            stub);
    client.initialize();

    for (Long baseVersionId : java.util.Arrays.asList(null, 0L, -1L)) {
      assertThatIllegalArgumentException()
          .isThrownBy(
              () ->
                  client.notifyScriptVersionUpdate(
                      "tenant-1", baseVersionId, "patch-7", java.util.List.of()));
    }

    verify(stub, never()).notifyScriptVersionUpdate(any(NotifyScriptVersionUpdateRequest.class));
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
