package net.firedevops.firemud.automationscripting.sourceintake;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import io.grpc.Context;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.automationscripting.service.ScriptDesignDigestService.ScriptDraftDesignDigest;
import net.firedevops.firemud.automationscripting.v1.GetDraftDesignDigestRequest;
import net.firedevops.firemud.automationscripting.v1.GetDraftDesignDigestResponse;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.common.security.SessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AutomationSelectedEmptyPublicationDigestGrpcReceiverTest {
  private static final String NAMESPACE = "test";
  private static final String TENANT = "11111111-1111-4111-8111-111111111111";
  private static final String VERSION_ROW = "23";

  @AfterEach
  void clearSessionContext() {
    SessionContext.clear();
  }

  @Test
  void exactGameDesignPeerWithoutSessionContextCanReadValidatedFullVersionBinding() {
    SessionContext.clear();
    AutomationSelectedEmptyPublicationDigestService digestService =
        Mockito.mock(AutomationSelectedEmptyPublicationDigestService.class);
    PublicationDigestRequestBinding binding =
        PublicationDigestRequestBinding.full(TENANT, VERSION_ROW, "request-7");
    Mockito.when(digestService.getDraftDesignDigest(any(PublicationDigestRequestBinding.class)))
        .thenReturn(
            new ScriptDraftDesignDigest(
                TENANT, VERSION_ROW, 0L, "selected-commit", "b".repeat(64), 6));
    AutomationSelectedEmptyPublicationDigestGrpcReceiver receiver =
        new AutomationSelectedEmptyPublicationDigestGrpcReceiver(
            digestService, NAMESPACE, new SimpleMeterRegistry());

    GetDraftDesignDigestResponse response = invokeAsGameDesign(receiver, request(binding));

    assertEquals("", response.getError().getCode());
    assertEquals(TENANT, response.getTenantId());
    assertEquals(VERSION_ROW, response.getVersionId());
    assertEquals("", response.getBaseVersionId());
    assertEquals("selected-commit", response.getAppliedCommitId());
    assertEquals("b".repeat(64), response.getContentDigest());
    assertEquals(6, response.getDigestSchemaVersion());
    verify(digestService).getDraftDesignDigest(any(PublicationDigestRequestBinding.class));
  }

  @Test
  void accountOrGlobalOrScopedRoleContextDeniesEvenTheExactGameDesignPeer() {
    AutomationSelectedEmptyPublicationDigestService digestService =
        Mockito.mock(AutomationSelectedEmptyPublicationDigestService.class);
    AutomationSelectedEmptyPublicationDigestGrpcReceiver receiver =
        new AutomationSelectedEmptyPublicationDigestGrpcReceiver(
            digestService, NAMESPACE, new SimpleMeterRegistry());
    List<Runnable> deniedContexts =
        List.of(
            () -> SessionContext.setContext("user-1", List.of("player"), Map.of()),
            () -> SessionContext.setContext(null, List.of("platformAdmin"), Map.of()),
            () ->
                SessionContext.setContext(
                    null, List.of(), Map.of("tenant-1", List.of("designer"))));
    for (Runnable setDeniedContext : deniedContexts) {
      SessionContext.clear();
      setDeniedContext.run();
      GetDraftDesignDigestResponse response =
          invokeAsGameDesign(
              receiver,
              request(PublicationDigestRequestBinding.full(TENANT, VERSION_ROW, "request-7")));
      assertEquals("PERMISSION_DENIED", response.getError().getCode());
    }
    verifyNoInteractions(digestService);
  }

  @Test
  void changedCompleteRequestBindingIsRejectedBeforeReceiptLookup() {
    SessionContext.clear();
    AutomationSelectedEmptyPublicationDigestService digestService =
        Mockito.mock(AutomationSelectedEmptyPublicationDigestService.class);
    AutomationSelectedEmptyPublicationDigestGrpcReceiver receiver =
        new AutomationSelectedEmptyPublicationDigestGrpcReceiver(
            digestService, NAMESPACE, new SimpleMeterRegistry());
    GetDraftDesignDigestRequest changedRequest =
        request(PublicationDigestRequestBinding.full(TENANT, VERSION_ROW, "request-7")).toBuilder()
            .setRequestDigest("0".repeat(64))
            .build();

    GetDraftDesignDigestResponse response = invokeAsGameDesign(receiver, changedRequest);

    assertEquals("INVALID_ARGUMENT", response.getError().getCode());
    verifyNoInteractions(digestService);
  }

  @Test
  void patchScopeIsUnsupportedAndDoesNotReadTheSelectedEmptyReceipt() {
    SessionContext.clear();
    AutomationSelectedEmptyPublicationDigestService digestService =
        Mockito.mock(AutomationSelectedEmptyPublicationDigestService.class);
    AutomationSelectedEmptyPublicationDigestGrpcReceiver receiver =
        new AutomationSelectedEmptyPublicationDigestGrpcReceiver(
            digestService, NAMESPACE, new SimpleMeterRegistry());
    PublicationDigestRequestBinding binding =
        PublicationDigestRequestBinding.patch(TENANT, VERSION_ROW, "patch-1", "request-7");
    GetDraftDesignDigestRequest request =
        GetDraftDesignDigestRequest.newBuilder()
            .setTenantId(binding.tenantId())
            .setBaseVersionId(binding.baseVersionId())
            .setScriptPatchVersion(binding.scriptPatchVersion())
            .setPublishRequestId(binding.publishRequestId())
            .setDerivedWorkflowIdentity(binding.derivedWorkflowIdentity())
            .setRequestDigest(binding.requestDigest())
            .build();

    GetDraftDesignDigestResponse response = invokeAsGameDesign(receiver, request);

    assertEquals("UNSUPPORTED_SCOPE", response.getError().getCode());
    verifyNoInteractions(digestService);
  }

  private static GetDraftDesignDigestRequest request(PublicationDigestRequestBinding binding) {
    return GetDraftDesignDigestRequest.newBuilder()
        .setTenantId(binding.tenantId())
        .setVersionId(binding.versionId())
        .setPublishRequestId(binding.publishRequestId())
        .setDerivedWorkflowIdentity(binding.derivedWorkflowIdentity())
        .setRequestDigest(binding.requestDigest())
        .build();
  }

  private static GetDraftDesignDigestResponse invokeAsGameDesign(
      AutomationSelectedEmptyPublicationDigestGrpcReceiver receiver,
      GetDraftDesignDigestRequest request) {
    AtomicReference<GetDraftDesignDigestResponse> response = new AtomicReference<>();
    Context.current()
        .withValue(
            GrpcPeerIdentity.CONTEXT_KEY,
            new GrpcPeerIdentity(
                "spiffe://firemud/ns/test/sa/game-design-service",
                NAMESPACE,
                "game-design-service"))
        .run(
            () ->
                receiver.getDraftDesignDigest(
                    request,
                    new StreamObserver<>() {
                      @Override
                      public void onNext(GetDraftDesignDigestResponse value) {
                        response.set(value);
                      }

                      @Override
                      public void onError(Throwable error) {
                        throw new AssertionError(error);
                      }

                      @Override
                      public void onCompleted() {}
                    }));
    return response.get();
  }
}
