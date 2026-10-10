package net.firedevops.firemud.gamedesign.client;

import io.grpc.ManagedChannel;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamelogic.GameLogicPublicationSourceReadBinding;
import net.firedevops.firemud.common.gamelogic.GameLogicPublicationSourceReadGrpcCodec;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import net.firedevops.firemud.gamedesign.publication.SelectedDraftGameLogicReceipt;
import net.firedevops.firemud.gamelogic.v1.GameLogicServiceGrpc;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class GameLogicClient
    extends AbstractReloadingBlockingGrpcClient<GameLogicServiceGrpc.GameLogicServiceBlockingStub> {
  private static final long CALL_DEADLINE_SECONDS = 5L;

  private final CommonGrpcClientProperties tlsProps;

  @Value("${firemud.grpc.workload-namespace:}")
  private String workloadNamespace;

  public GameLogicClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory,
      BlockingGrpcStubCustomizer stubCustomizer) {
    super(endpoints, tlsProps, channelFactory, stubCustomizer, GameLogicClient.class);
    this.tlsProps = tlsProps.copy();
  }

  @PostConstruct
  void init() throws SSLException, IOException {
    initReloadingClient();
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getGameLogicService();
  }

  @Override
  protected String defaultTarget() {
    return "game-logic-service:6565";
  }

  @Override
  protected GameLogicServiceGrpc.GameLogicServiceBlockingStub buildStub(ManagedChannel channel) {
    return applyStubCustomizer(
        GameLogicServiceGrpc.newBlockingStub(channel).withCompression("gzip"));
  }

  /**
   * Legacy publication read cannot prove the selected Account authorization and is deliberately
   * closed. Full-version publication must use the immutable-receipt overload below.
   */
  public PublishParticipantDigestDto getDraftDesignDigestForVersion(
      PublicationDigestRequestBinding binding) {
    Objects.requireNonNull(binding, "binding");
    String versionId = requireFullVersionId(binding);
    return failure(
        versionId,
        "SELECTED_GAME_LOGIC_RECEIPT_REQUIRED",
        "exact retained Game Logic receipt is required for a full-version publication read");
  }

  /** Reads the retained full-version digest selected by the original Account receipt. */
  public PublishParticipantDigestDto getDraftDesignDigestForVersion(
      PublicationDigestRequestBinding binding, SelectedDraftGameLogicReceipt receipt) {
    Objects.requireNonNull(binding, "binding");
    Objects.requireNonNull(receipt, "receipt");
    String versionId = requireFullVersionId(binding);
    requireOutsideSql();
    if (!receipt.selection().intent().publishRequestId().equals(binding.publishRequestId())
        || !receipt.workflowIdentity().equals(binding.derivedWorkflowIdentity())) {
      throw new IllegalArgumentException(
          "publication request differs from immutable selected Game Logic receipt");
    }

    // The receipt constructor enforces the Account-finalized RETAINED terminal. Use its exact
    // original authorization, never IDs or bytes from an ambient/latest source lookup.
    GameLogicPublicationSourceReadBinding expectedBinding =
        new GameLogicPublicationSourceReadBinding(binding, receipt.authorization());
    var request = GameLogicPublicationSourceReadGrpcCodec.toRequest(expectedBinding);
    var response =
        requirePublicationReadStub()
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .getDraftDesignDigest(request);
    if (response.hasError() && !response.getError().getCode().isBlank()) {
      return failure(versionId, response.getError().getCode(), response.getError().getMessage());
    }

    GameLogicPublicationSourceReadGrpcCodec.Evidence evidence;
    try {
      evidence =
          GameLogicPublicationSourceReadGrpcCodec.fromResponse(
              expectedBinding, response, workloadNamespace);
    } catch (IllegalArgumentException invalid) {
      return failure(versionId, "RESPONSE_BINDING_MISMATCH", invalid.getMessage());
    }
    if (!Arrays.equals(
        receipt.receipt().terminal().canonicalBytes(), evidence.terminal().canonicalBytes())) {
      return failure(
          versionId,
          "RESPONSE_TERMINAL_MISMATCH",
          "owner terminal differs from the immutable Account-finalized receipt");
    }

    // The authenticated owner response remains sha256:-prefixed. Release participants carry the
    // same aggregate hash as bare hex; keep the separately scoped ability digest unchanged.
    return new PublishParticipantDigestDto(
        "GAME_LOGIC",
        versionId,
        null,
        receipt.authorization().source().binding().commitId().toString(),
        evidence.manifestDigest().substring("sha256:".length()),
        evidence.digestSchemaVersion(),
        evidence.abilitySchemaDigest(),
        null,
        null);
  }

  private GameLogicServiceGrpc.GameLogicServiceBlockingStub requirePublicationReadStub() {
    requireFileBackedMtls(tlsProps);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalStateException(
          "Game Logic publication reads require the configured workload namespace");
    }
    var current = stub();
    if (current == null) {
      throw new IllegalStateException("Game Logic publication client is not initialized");
    }
    String expectedPeerUri = "spiffe://firemud/ns/" + workloadNamespace + "/sa/game-logic-service";
    return current
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(expectedPeerUri))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(expectedPeerUri))
        .withMaxInboundMessageSize(GameLogicPublicationSourceReadGrpcCodec.MAX_WIRE_BYTES);
  }

  private static void requireOutsideSql() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Game Logic publication source reads require no ambient SQL transaction");
    }
  }

  private static CommonGrpcClientProperties requireFileBackedMtls(
      CommonGrpcClientProperties tlsProperties) {
    if (tlsProperties == null || tlsProperties.isPlaintext()) {
      throw new IllegalStateException(
          "Game Logic publication source reads require file-backed workload mTLS");
    }
    requireReadableFile(tlsProperties.getCertChain(), "certificate chain");
    requireReadableFile(tlsProperties.getPrivateKey(), "private key");
    requireReadableFile(tlsProperties.getCaCert(), "CA certificate");
    return tlsProperties;
  }

  private static void requireReadableFile(String configuredPath, String label) {
    if (configuredPath == null || configuredPath.isBlank()) {
      throw new IllegalStateException(
          "Game Logic publication source reads require file-backed TLS certificate, key, and CA");
    }
    String pathText = configuredPath.trim();
    if (pathText.startsWith("classpath:")) {
      throw new IllegalStateException(
          "Game Logic publication source reads require file-backed TLS certificate, key, and CA");
    }
    Path path;
    try {
      path = Path.of(pathText);
    } catch (RuntimeException invalid) {
      throw new IllegalStateException(
          "Game Logic publication source-read " + label + " must be a file-backed path", invalid);
    }
    if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
      throw new IllegalStateException(
          "Game Logic publication source-read " + label + " must be an existing readable file");
    }
  }

  private static PublishParticipantDigestDto failure(
      String versionId, String errorCode, String errorMessage) {
    return new PublishParticipantDigestDto(
        "GAME_LOGIC", versionId, null, null, null, null, errorCode, errorMessage);
  }

  private String requireFullVersionId(PublicationDigestRequestBinding binding) {
    if (binding.scopeKind() != PublicationDigestRequestBinding.ScopeKind.FULL_VERSION) {
      throw new IllegalArgumentException("full-version binding required");
    }
    return binding.versionId();
  }
}
