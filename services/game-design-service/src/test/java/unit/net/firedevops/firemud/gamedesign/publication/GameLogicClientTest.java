package net.firedevops.firemud.gamedesign.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.ClientInterceptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamelogic.GameLogicGameplayRuleIntakeOperation;
import net.firedevops.firemud.common.gamelogic.GameLogicGameplayRuleIntakeTerminal;
import net.firedevops.firemud.common.gamelogic.GameLogicPublicationSourceReadBinding;
import net.firedevops.firemud.common.gamelogic.GameLogicPublicationSourceReadGrpcCodec;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.gamedesign.client.GameLogicClient;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import net.firedevops.firemud.gamelogic.v1.GameLogicServiceGrpc;
import net.firedevops.firemud.gamelogic.v1.GetDraftDesignDigestRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class GameLogicClientTest {
  private static final String WORKLOAD_NAMESPACE = "test";

  @Test
  void readsExactRetainedSelectionAndReturnsBothDigestProjections(@TempDir Path tlsDirectory)
      throws IOException {
    var receipt = SelectedDraftGameLogicReceiptTest.fixture();
    var publication = publicationBinding(receipt);
    var sourceRead =
        new GameLogicPublicationSourceReadBinding(publication, receipt.authorization());
    var response =
        GameLogicPublicationSourceReadGrpcCodec.toResponse(
            sourceRead, receipt.receipt().terminal());
    var clientAndStub = client(tlsDirectory);
    var client = clientAndStub.client();
    var stub = clientAndStub.stub();
    when(stub.getDraftDesignDigest(any(GetDraftDesignDigestRequest.class))).thenReturn(response);

    var result = client.getDraftDesignDigestForVersion(publication, receipt);

    assertThat(result.succeeded()).isTrue();
    assertThat(result.participantKey()).isEqualTo("GAME_LOGIC");
    assertThat(result.scopeValue()).isEqualTo(publication.versionId());
    assertThat(result.baseVersionId()).isNull();
    assertThat(result.appliedCommitId())
        .isEqualTo(receipt.authorization().source().binding().commitId().toString());
    assertThat(response.getContentDigest()).matches("sha256:[0-9a-f]{64}");
    assertThat(result.contentDigest())
        .isEqualTo(response.getContentDigest().substring("sha256:".length()))
        .matches("[0-9a-f]{64}")
        .isNotEqualTo(response.getContentDigest());
    assertThat(result.digestSchemaVersion()).isEqualTo(response.getDigestSchemaVersion());
    assertThat(result.abilitySchemaDigest()).isEqualTo(response.getAbilitySchemaDigest());
    assertThat(result.abilitySchemaDigest()).matches("sha256:[0-9a-f]{64}");

    var request = org.mockito.ArgumentCaptor.forClass(GetDraftDesignDigestRequest.class);
    verify(stub).getDraftDesignDigest(request.capture());
    assertThat(request.getValue().getTenantId()).isEqualTo(publication.tenantId());
    assertThat(request.getValue().getVersionId()).isEqualTo(publication.versionId());
    assertThat(request.getValue().getPublishRequestId()).isEqualTo(publication.publishRequestId());
    assertThat(request.getValue().getDerivedWorkflowIdentity())
        .isEqualTo(publication.derivedWorkflowIdentity());
    assertThat(request.getValue().getRequestDigest()).isEqualTo(publication.requestDigest());
    var decoded = GameLogicPublicationSourceReadGrpcCodec.fromRequest(request.getValue());
    assertThat(decoded.canonicalBytes()).containsExactly(sourceRead.canonicalBytes());
    assertThat(decoded.authorization().canonicalBytes())
        .containsExactly(receipt.authorization().canonicalBytes());
    verify(stub).withCallCredentials(any());
    verify(stub).withInterceptors(any(ClientInterceptor[].class));
    verify(stub).withMaxInboundMessageSize(GameLogicPublicationSourceReadGrpcCodec.MAX_WIRE_BYTES);
    verify(stub).withDeadlineAfter(5L, TimeUnit.SECONDS);
  }

  @Test
  void rejectsAValidButDifferentTerminalFromTheImmutableReceipt(@TempDir Path tlsDirectory)
      throws IOException {
    var receipt = SelectedDraftGameLogicReceiptTest.fixture();
    var publication = publicationBinding(receipt);
    var sourceRead =
        new GameLogicPublicationSourceReadBinding(publication, receipt.authorization());
    var retained = receipt.receipt().terminal();
    var differentTerminal =
        GameLogicGameplayRuleIntakeTerminal.retained(
            new GameLogicGameplayRuleIntakeOperation("other", receipt.authorization()),
            retained.selectedSourceBytes(),
            retained.manifestBytes());
    assertThat(Arrays.equals(differentTerminal.canonicalBytes(), retained.canonicalBytes()))
        .isFalse();
    var response =
        GameLogicPublicationSourceReadGrpcCodec.toResponse(sourceRead, differentTerminal);
    var clientAndStub = client(tlsDirectory);
    ReflectionTestUtils.setField(clientAndStub.client(), "workloadNamespace", "other");
    when(clientAndStub.stub().getDraftDesignDigest(any(GetDraftDesignDigestRequest.class)))
        .thenReturn(response);

    var result = clientAndStub.client().getDraftDesignDigestForVersion(publication, receipt);

    assertThat(result.succeeded()).isFalse();
    assertThat(result.errorCode()).isEqualTo("RESPONSE_TERMINAL_MISMATCH");
    assertThat(result.contentDigest()).isNull();
    assertThat(result.abilitySchemaDigest()).isNull();
  }

  @Test
  void propagatesUnavailableTransportFailureForRetry(@TempDir Path tlsDirectory)
      throws IOException {
    var receipt = SelectedDraftGameLogicReceiptTest.fixture();
    var publication = publicationBinding(receipt);
    var clientAndStub = client(tlsDirectory);
    when(clientAndStub.stub().getDraftDesignDigest(any(GetDraftDesignDigestRequest.class)))
        .thenThrow(Status.UNAVAILABLE.withDescription("readback unavailable").asRuntimeException());

    assertThatThrownBy(
            () -> clientAndStub.client().getDraftDesignDigestForVersion(publication, receipt))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure ->
                assertThat(Status.fromThrowable(failure).getCode())
                    .isEqualTo(Status.Code.UNAVAILABLE));
    verify(clientAndStub.stub()).getDraftDesignDigest(any(GetDraftDesignDigestRequest.class));
  }

  @Test
  void rejectsMalformedManifestDigestFromPublicationResponse(@TempDir Path tlsDirectory)
      throws IOException {
    var receipt = SelectedDraftGameLogicReceiptTest.fixture();
    var publication = publicationBinding(receipt);
    var sourceRead =
        new GameLogicPublicationSourceReadBinding(publication, receipt.authorization());
    var validResponse =
        GameLogicPublicationSourceReadGrpcCodec.toResponse(
            sourceRead, receipt.receipt().terminal());
    var response =
        validResponse.toBuilder()
            .setContentDigest(validResponse.getContentDigest().substring("sha256:".length()))
            .build();
    var clientAndStub = client(tlsDirectory);
    when(clientAndStub.stub().getDraftDesignDigest(any(GetDraftDesignDigestRequest.class)))
        .thenReturn(response);

    var result = clientAndStub.client().getDraftDesignDigestForVersion(publication, receipt);

    assertThat(result.succeeded()).isFalse();
    assertThat(result.errorCode()).isEqualTo("RESPONSE_BINDING_MISMATCH");
    assertThat(result.contentDigest()).isNull();
    assertThat(result.abilitySchemaDigest()).isNull();
  }

  @Test
  void rejectsSwappedManifestAndAbilityDigestsFromPublicationResponse(@TempDir Path tlsDirectory)
      throws IOException {
    var receipt = SelectedDraftGameLogicReceiptTest.fixture();
    var publication = publicationBinding(receipt);
    var sourceRead =
        new GameLogicPublicationSourceReadBinding(publication, receipt.authorization());
    var validResponse =
        GameLogicPublicationSourceReadGrpcCodec.toResponse(
            sourceRead, receipt.receipt().terminal());
    assertThat(validResponse.getContentDigest())
        .isNotEqualTo(validResponse.getAbilitySchemaDigest());
    var response =
        validResponse.toBuilder()
            .setContentDigest(validResponse.getAbilitySchemaDigest())
            .setAbilitySchemaDigest(validResponse.getContentDigest())
            .build();
    var clientAndStub = client(tlsDirectory);
    when(clientAndStub.stub().getDraftDesignDigest(any(GetDraftDesignDigestRequest.class)))
        .thenReturn(response);

    var result = clientAndStub.client().getDraftDesignDigestForVersion(publication, receipt);

    assertThat(result.succeeded()).isFalse();
    assertThat(result.errorCode()).isEqualTo("RESPONSE_BINDING_MISMATCH");
    assertThat(result.contentDigest()).isNull();
    assertThat(result.abilitySchemaDigest()).isNull();
  }

  @Test
  void missingReceiptLegacyPathFailsClosedWithoutCallingGameLogic(@TempDir Path tlsDirectory)
      throws IOException {
    var receipt = SelectedDraftGameLogicReceiptTest.fixture();
    var clientAndStub = client(tlsDirectory);

    var result = clientAndStub.client().getDraftDesignDigestForVersion(publicationBinding(receipt));

    assertThat(result.succeeded()).isFalse();
    assertThat(result.errorCode()).isEqualTo("SELECTED_GAME_LOGIC_RECEIPT_REQUIRED");
    verifyNoInteractions(clientAndStub.stub());
  }

  @Test
  void rejectsPublicationRequestThatDoesNotMatchTheReceiptSelection(@TempDir Path tlsDirectory)
      throws IOException {
    var receipt = SelectedDraftGameLogicReceiptTest.fixture();
    var publication = publicationBinding(receipt);
    var changedPublication =
        PublicationDigestRequestBinding.full(
            publication.tenantId(),
            publication.versionId(),
            publication.publishRequestId() + "-different");
    var clientAndStub = client(tlsDirectory);

    assertThatThrownBy(
            () ->
                clientAndStub.client().getDraftDesignDigestForVersion(changedPublication, receipt))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("differs from immutable selected Game Logic receipt");
    verifyNoInteractions(clientAndStub.stub());
  }

  @Test
  void missingWorkloadNamespaceReturnsUnavailableBeforeRpc(@TempDir Path tlsDirectory)
      throws IOException {
    var receipt = SelectedDraftGameLogicReceiptTest.fixture();
    var clientAndStub = client(tlsDirectory);
    ReflectionTestUtils.setField(clientAndStub.client(), "workloadNamespace", "");

    var result =
        clientAndStub.client().getDraftDesignDigestForVersion(publicationBinding(receipt), receipt);

    assertConfigurationUnavailable(result);
    verifyNoInteractions(clientAndStub.stub());
  }

  @Test
  void invalidWorkloadNamespaceReturnsUnavailableBeforeRpc(@TempDir Path tlsDirectory)
      throws IOException {
    var receipt = SelectedDraftGameLogicReceiptTest.fixture();
    var clientAndStub = client(tlsDirectory);
    ReflectionTestUtils.setField(clientAndStub.client(), "workloadNamespace", "Not_A_Namespace");

    var result =
        clientAndStub.client().getDraftDesignDigestForVersion(publicationBinding(receipt), receipt);

    assertConfigurationUnavailable(result);
    verifyNoInteractions(clientAndStub.stub());
  }

  @Test
  void plaintextTlsConfigurationReturnsUnavailableBeforeRpc(@TempDir Path tlsDirectory)
      throws IOException {
    var receipt = SelectedDraftGameLogicReceiptTest.fixture();
    var tls = new CommonGrpcClientProperties();
    tls.setPlaintext(true);
    var clientAndStub = client(tlsDirectory, tls);

    var result =
        clientAndStub.client().getDraftDesignDigestForVersion(publicationBinding(receipt), receipt);

    assertConfigurationUnavailable(result);
    verifyNoInteractions(clientAndStub.stub());
  }

  @Test
  void unreadableTlsMaterialReturnsUnavailableBeforeRpc(@TempDir Path tlsDirectory)
      throws IOException {
    var receipt = SelectedDraftGameLogicReceiptTest.fixture();
    var tls = fileBackedTls(tlsDirectory);
    tls.setCaCert(tlsDirectory.resolve("missing-ca.crt").toString());
    var clientAndStub = client(tlsDirectory, tls);

    var result =
        clientAndStub.client().getDraftDesignDigestForVersion(publicationBinding(receipt), receipt);

    assertConfigurationUnavailable(result);
    verifyNoInteractions(clientAndStub.stub());
  }

  @Test
  void uninitializedClientReturnsUnavailableBeforeRpc(@TempDir Path tlsDirectory)
      throws IOException {
    var receipt = SelectedDraftGameLogicReceiptTest.fixture();
    var clientAndStub = client(tlsDirectory);
    ReflectionTestUtils.setField(clientAndStub.client(), "stub", null);

    var result =
        clientAndStub.client().getDraftDesignDigestForVersion(publicationBinding(receipt), receipt);

    assertConfigurationUnavailable(result);
    verifyNoInteractions(clientAndStub.stub());
  }

  @Test
  void activeSqlTransactionDeniesBeforeRpc(@TempDir Path tlsDirectory) throws IOException {
    var receipt = SelectedDraftGameLogicReceiptTest.fixture();
    var clientAndStub = client(tlsDirectory);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThatThrownBy(
              () ->
                  clientAndStub
                      .client()
                      .getDraftDesignDigestForVersion(publicationBinding(receipt), receipt))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("no ambient SQL transaction");
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
    verifyNoInteractions(clientAndStub.stub());
  }

  @Test
  void activeSqlSynchronizationDeniesBeforeRpc(@TempDir Path tlsDirectory) throws IOException {
    var receipt = SelectedDraftGameLogicReceiptTest.fixture();
    var clientAndStub = client(tlsDirectory);
    TransactionSynchronizationManager.initSynchronization();
    try {
      assertThatThrownBy(
              () ->
                  clientAndStub
                      .client()
                      .getDraftDesignDigestForVersion(publicationBinding(receipt), receipt))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("no ambient SQL transaction");
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
    verifyNoInteractions(clientAndStub.stub());
  }

  private static PublicationDigestRequestBinding publicationBinding(
      SelectedDraftGameLogicReceipt receipt) {
    return PublicationDigestRequestBinding.full(
        receipt.selection().intent().canonicalTenantId().toString(),
        Long.toString(receipt.selection().target().gameDesignVersionRowId()),
        receipt.selection().intent().publishRequestId());
  }

  private static ClientAndStub client(Path tlsDirectory) throws IOException {
    return client(tlsDirectory, fileBackedTls(tlsDirectory));
  }

  private static void assertConfigurationUnavailable(PublishParticipantDigestDto result) {
    assertThat(result.succeeded()).isFalse();
    assertThat(result.errorCode()).isEqualTo("PARTICIPANT_UNAVAILABLE");
    assertThat(result.errorMessage())
        .isEqualTo("Game Logic publication source read is not configured");
    assertThat(result.contentDigest()).isNull();
    assertThat(result.abilitySchemaDigest()).isNull();
  }

  private static ClientAndStub client(Path tlsDirectory, CommonGrpcClientProperties tls) {
    var client =
        new GameLogicClient(
            new ServiceEndpointsProperties(),
            tls,
            mock(GrpcChannelFactory.class),
            BlockingGrpcStubCustomizer.noop());
    ReflectionTestUtils.setField(client, "workloadNamespace", WORKLOAD_NAMESPACE);

    var stub = mock(GameLogicServiceGrpc.GameLogicServiceBlockingStub.class);
    when(stub.withCallCredentials(any())).thenReturn(stub);
    when(stub.withInterceptors(any(ClientInterceptor[].class))).thenReturn(stub);
    when(stub.withMaxInboundMessageSize(GameLogicPublicationSourceReadGrpcCodec.MAX_WIRE_BYTES))
        .thenReturn(stub);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    clearInvocations(stub);
    ReflectionTestUtils.setField(client, "stub", stub);
    return new ClientAndStub(client, stub);
  }

  private static CommonGrpcClientProperties fileBackedTls(Path tlsDirectory) throws IOException {
    var tls = new CommonGrpcClientProperties();
    tls.setCertChain(write(tlsDirectory.resolve("client.crt")));
    tls.setPrivateKey(write(tlsDirectory.resolve("client.key")));
    tls.setCaCert(write(tlsDirectory.resolve("ca.crt")));
    return tls;
  }

  private static String write(Path path) throws IOException {
    Files.writeString(path, "test material");
    return path.toString();
  }

  private record ClientAndStub(
      GameLogicClient client, GameLogicServiceGrpc.GameLogicServiceBlockingStub stub) {}
}
