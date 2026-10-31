package unit.net.firedevops.firemud.accountservice.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Attributes;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLSession;
import net.firedevops.firemud.account.v1.GetCurrentReadinessProbeOwnerRequest;
import net.firedevops.firemud.account.v1.GetCurrentReadinessProbeOwnerResponse;
import net.firedevops.firemud.account.v1.GetCurrentReadinessReceiverMetadataRequest;
import net.firedevops.firemud.account.v1.GetCurrentReadinessReceiverMetadataResponse;
import net.firedevops.firemud.accountservice.config.AccountJwtJwksApiBinding;
import net.firedevops.firemud.accountservice.config.AccountJwtReadinessProbeOwnerWorkloadGuard;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding;
import net.firedevops.firemud.accountservice.service.impl.AccountJwtReadinessProbeOwnerGrpcService;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessProbeOwnerProtoMapper;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessProbeOwnerService;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessProbeService;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessReceiverInvocationPort;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessReceiverMetadataService;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class AccountJwtReadinessProbeOwnerGrpcServiceTest {
  @Test
  void adapterHasNoRegistrationAnnotationsAndOrdinaryScanningDoesNotRegisterIt() {
    assertThat(AccountJwtReadinessProbeOwnerGrpcService.class.getDeclaredAnnotations()).isEmpty();
    try (var context = new AnnotationConfigApplicationContext()) {
      context.scan("net.firedevops.firemud.accountservice.service.impl");
      assertThat(
              context
                  .getBeanFactory()
                  .containsBeanDefinition("accountJwtReadinessProbeOwnerGrpcService"))
          .isFalse();
    }
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  void manuallyInstalledPeerInterceptorCarriesTlsIdentityToBothReadMethods() throws Exception {
    var owner = mock(AccountJwtReadinessProbeOwnerService.class);
    var metadata = mock(AccountJwtReadinessReceiverMetadataService.class);
    AtomicReference<GrpcPeerIdentity> observedOwnerPeer = new AtomicReference<>();
    AtomicReference<GrpcPeerIdentity> observedMetadataPeer = new AtomicReference<>();
    doAnswer(
            invocation -> {
              observedOwnerPeer.set(GrpcPeerIdentity.current());
              return GetCurrentReadinessProbeOwnerResponse.getDefaultInstance();
            })
        .when(owner)
        .getCurrentReadinessProbeOwner(org.mockito.ArgumentMatchers.any());
    doAnswer(
            invocation -> {
              observedMetadataPeer.set(GrpcPeerIdentity.current());
              return GetCurrentReadinessReceiverMetadataResponse.getDefaultInstance();
            })
        .when(metadata)
        .getCurrentReadinessReceiverMetadata(org.mockito.ArgumentMatchers.any());
    var service = new AccountJwtReadinessProbeOwnerGrpcService(owner, metadata);
    var definition = ServerInterceptors.intercept(service, new GrpcPeerIdentityInterceptor());
    String peerUri = "spiffe://firemud/ns/firemud-prod/sa/game-session-service";
    X509Certificate certificate = mock(X509Certificate.class);
    when(certificate.getSubjectAlternativeNames()).thenReturn(List.of(List.of(6, peerUri)));
    SSLSession sslSession = mock(SSLSession.class);
    when(sslSession.getPeerCertificates()).thenReturn(new Certificate[] {certificate});
    for (var method : definition.getMethods()) {
      ServerCall call = mock(ServerCall.class);
      when(call.getMethodDescriptor()).thenReturn(method.getMethodDescriptor());
      when(call.getAttributes())
          .thenReturn(
              Attributes.newBuilder().set(Grpc.TRANSPORT_ATTR_SSL_SESSION, sslSession).build());
      ServerCall.Listener listener = method.getServerCallHandler().startCall(call, new Metadata());
      listener.onMessage(
          method
                  .getMethodDescriptor()
                  .getFullMethodName()
                  .endsWith("/GetCurrentReadinessProbeOwner")
              ? GetCurrentReadinessProbeOwnerRequest.getDefaultInstance()
              : GetCurrentReadinessReceiverMetadataRequest.getDefaultInstance());
      listener.onHalfClose();
    }
    var expected = GrpcPeerIdentity.parseUri(peerUri).orElseThrow();
    assertThat(observedOwnerPeer.get()).isEqualTo(expected);
    assertThat(observedMetadataPeer.get()).isEqualTo(expected);
  }

  @Test
  void missingProtectedAccountBindingDeniesBeforeInventoryOrSqlAccess() {
    var materializerBinding = mock(AccountJwtSignerMaterializerTrustBinding.class);
    when(materializerBinding.current()).thenReturn(Optional.empty());
    var probeService = mock(AccountJwtReadinessProbeService.class);
    var guard =
        new AccountJwtReadinessProbeOwnerWorkloadGuard(
            materializerBinding, mock(AccountJwtJwksApiBinding.class), "firemud-prod");
    var owner =
        new AccountJwtReadinessProbeOwnerService(
            probeService,
            materializerBinding,
            guard,
            new AccountJwtReadinessProbeOwnerProtoMapper());
    var service =
        new AccountJwtReadinessProbeOwnerGrpcService(
            owner, mock(AccountJwtReadinessReceiverMetadataService.class));
    RecordingObserver<GetCurrentReadinessProbeOwnerResponse> response = new RecordingObserver<>();

    service.getCurrentReadinessProbeOwner(
        GetCurrentReadinessProbeOwnerRequest.newBuilder().setSchemaVersion(1).build(), response);

    assertThat(response.value.get()).isNull();
    assertThat(response.completed).isFalse();
    assertThat(Status.fromThrowable(response.failure.get()).getCode())
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(probeService);
  }

  @Test
  void bothMethodsPreserveTypedStatusAndHideUnexpectedSourceDetails() {
    List<RuntimeException> failures =
        List.of(
            new AccountJwtReadinessProbeOwnerWorkloadGuard.OwnerReadDeniedException(),
            new AccountJwtReadinessProbeOwnerProtoMapper.InvalidOwnerReadRequestException(),
            new AccountJwtReadinessReceiverInvocationPort.ReceiverUnavailableException(),
            new IllegalStateException("private source detail"));
    List<Status.Code> codes =
        List.of(
            Status.Code.PERMISSION_DENIED,
            Status.Code.INVALID_ARGUMENT,
            Status.Code.FAILED_PRECONDITION,
            Status.Code.UNAVAILABLE);
    for (int index = 0; index < failures.size(); index++) {
      var owner = mock(AccountJwtReadinessProbeOwnerService.class);
      var metadata = mock(AccountJwtReadinessReceiverMetadataService.class);
      var ownerRequest = GetCurrentReadinessProbeOwnerRequest.getDefaultInstance();
      var metadataRequest = GetCurrentReadinessReceiverMetadataRequest.getDefaultInstance();
      when(owner.getCurrentReadinessProbeOwner(ownerRequest)).thenThrow(failures.get(index));
      when(metadata.getCurrentReadinessReceiverMetadata(metadataRequest))
          .thenThrow(failures.get(index));
      var service = new AccountJwtReadinessProbeOwnerGrpcService(owner, metadata);
      RecordingObserver<GetCurrentReadinessProbeOwnerResponse> ownerResponse =
          new RecordingObserver<>();
      RecordingObserver<GetCurrentReadinessReceiverMetadataResponse> metadataResponse =
          new RecordingObserver<>();
      service.getCurrentReadinessProbeOwner(ownerRequest, ownerResponse);
      service.getCurrentReadinessReceiverMetadata(metadataRequest, metadataResponse);
      for (var response : List.of(ownerResponse, metadataResponse)) {
        assertThat(response.value.get()).isNull();
        assertThat(response.completed).isFalse();
        Status status = Status.fromThrowable(response.failure.get());
        assertThat(status.getCode()).isEqualTo(codes.get(index));
        assertThat(status.getDescription()).doesNotContain("private source detail");
      }
    }
  }

  private static final class RecordingObserver<T> implements StreamObserver<T> {
    private final AtomicReference<T> value = new AtomicReference<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private boolean completed;

    @Override
    public void onNext(T response) {
      value.set(response);
    }

    @Override
    public void onError(Throwable throwable) {
      failure.set(throwable);
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
