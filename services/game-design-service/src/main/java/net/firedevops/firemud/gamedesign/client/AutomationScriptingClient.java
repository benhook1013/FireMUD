package net.firedevops.firemud.gamedesign.client;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.List;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.automationscripting.v1.AutomationScriptingServiceGrpc;
import net.firedevops.firemud.automationscripting.v1.GetDraftDesignDigestRequest;
import net.firedevops.firemud.automationscripting.v1.NotifyScriptVersionUpdateRequest;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import org.springframework.stereotype.Component;

/** gRPC client for Automation & Scripting Service. */
@Component
public class AutomationScriptingClient
    extends AbstractReloadingBlockingGrpcClient<
        AutomationScriptingServiceGrpc.AutomationScriptingServiceBlockingStub> {
  public AutomationScriptingClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory,
      BlockingGrpcStubCustomizer stubCustomizer) {
    super(endpoints, tlsProps, channelFactory, stubCustomizer, AutomationScriptingClient.class);
  }

  @PostConstruct
  void init() throws SSLException, IOException {
    initReloadingClient();
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getAutomationScriptingService();
  }

  @Override
  protected String defaultTarget() {
    return "automation-scripting-service:6565";
  }

  @Override
  protected AutomationScriptingServiceGrpc.AutomationScriptingServiceBlockingStub buildStub(
      io.grpc.ManagedChannel channel) {
    return applyStubCustomizer(
        AutomationScriptingServiceGrpc.newBlockingStub(channel).withCompression("gzip"));
  }

  /** Notify the Automation service that a new script patch version is active. */
  public void notifyScriptVersionUpdate(
      String tenantId, String patchVersion, List<String> scripts) {
    NotifyScriptVersionUpdateRequest request =
        NotifyScriptVersionUpdateRequest.newBuilder()
            .setTenantId(tenantId)
            .setScriptPatchVersion(patchVersion)
            .addAllAffectedScripts(scripts)
            .build();
    stub().notifyScriptVersionUpdate(request);
  }

  public PublishParticipantDigestDto getDraftDesignDigestForScriptPatch(
      PublicationDigestRequestBinding binding) {
    var response =
        stub()
            .getDraftDesignDigest(
                GetDraftDesignDigestRequest.newBuilder()
                    .setTenantId(binding.tenantId())
                    .setBaseVersionId(requirePatchBaseVersionId(binding))
                    .setScriptPatchVersion(binding.scriptPatchVersion())
                    .setPublishRequestId(binding.publishRequestId())
                    .setDerivedWorkflowIdentity(binding.derivedWorkflowIdentity())
                    .setRequestDigest(binding.requestDigest())
                    .build());
    if (response.hasError() && !response.getError().getCode().isBlank()) {
      return new PublishParticipantDigestDto(
          "AUTOMATION_SCRIPTING",
          binding.scriptPatchVersion(),
          null,
          null,
          null,
          response.getError().getCode(),
          response.getError().getMessage());
    }
    String mismatch = responseBindingMismatch(response, binding);
    if (mismatch != null) {
      return new PublishParticipantDigestDto(
          "AUTOMATION_SCRIPTING",
          binding.scopeKind() == PublicationDigestRequestBinding.ScopeKind.FULL_VERSION
              ? binding.versionId()
              : binding.scriptPatchVersion(),
          null,
          null,
          null,
          "RESPONSE_BINDING_MISMATCH",
          mismatch);
    }
    return new PublishParticipantDigestDto(
        "AUTOMATION_SCRIPTING",
        response.hasVersionId() ? response.getVersionId() : response.getScriptPatchVersion(),
        response.getAppliedCommitId(),
        response.getContentDigest(),
        response.getDigestSchemaVersion(),
        null,
        null);
  }

  public PublishParticipantDigestDto getDraftDesignDigestForVersion(
      PublicationDigestRequestBinding binding) {
    var response =
        stub()
            .getDraftDesignDigest(
                GetDraftDesignDigestRequest.newBuilder()
                    .setTenantId(binding.tenantId())
                    .setVersionId(requireFullVersionId(binding))
                    .setPublishRequestId(binding.publishRequestId())
                    .setDerivedWorkflowIdentity(binding.derivedWorkflowIdentity())
                    .setRequestDigest(binding.requestDigest())
                    .build());
    if (response.hasError() && !response.getError().getCode().isBlank()) {
      return new PublishParticipantDigestDto(
          "AUTOMATION_SCRIPTING",
          binding.versionId(),
          null,
          null,
          null,
          response.getError().getCode(),
          response.getError().getMessage());
    }
    String mismatch = responseBindingMismatch(response, binding);
    if (mismatch != null) {
      return new PublishParticipantDigestDto(
          "AUTOMATION_SCRIPTING",
          binding.versionId(),
          null,
          null,
          null,
          "RESPONSE_BINDING_MISMATCH",
          mismatch);
    }
    return new PublishParticipantDigestDto(
        "AUTOMATION_SCRIPTING",
        response.getVersionId(),
        response.getAppliedCommitId(),
        response.getContentDigest(),
        response.getDigestSchemaVersion(),
        null,
        null);
  }

  private String requireFullVersionId(PublicationDigestRequestBinding binding) {
    if (binding.scopeKind() != PublicationDigestRequestBinding.ScopeKind.FULL_VERSION) {
      throw new IllegalArgumentException("full-version binding required");
    }
    return binding.versionId();
  }

  private String requirePatchBaseVersionId(PublicationDigestRequestBinding binding) {
    if (binding.scopeKind() != PublicationDigestRequestBinding.ScopeKind.SCRIPT_PATCH) {
      throw new IllegalArgumentException("script-patch binding required");
    }
    return binding.baseVersionId();
  }

  private String responseBindingMismatch(
      net.firedevops.firemud.automationscripting.v1.GetDraftDesignDigestResponse response,
      PublicationDigestRequestBinding binding) {
    if (!binding.tenantId().equals(response.getTenantId())) {
      return "owner returned a tenant that does not match request";
    }
    if (binding.scopeKind() == PublicationDigestRequestBinding.ScopeKind.FULL_VERSION) {
      if (!response.hasVersionId()
          || !binding.versionId().equals(response.getVersionId())
          || !response.getBaseVersionId().isEmpty()) {
        return "owner returned tenant or typed full-version scope that does not match request";
      }
    } else if (!response.hasScriptPatchVersion()
        || !binding.scriptPatchVersion().equals(response.getScriptPatchVersion())
        || !binding.baseVersionId().equals(response.getBaseVersionId())) {
      return "owner returned tenant or typed script-patch scope that does not match request";
    }
    return null;
  }
}
