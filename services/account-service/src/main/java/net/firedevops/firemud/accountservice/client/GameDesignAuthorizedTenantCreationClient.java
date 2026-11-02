package net.firedevops.firemud.accountservice.client;

import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.FileBackedMtls;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.common.tenant.GameTenantCreationOriginalOutcomeEvidence.Binding;
import net.firedevops.firemud.common.tenant.GameTenantCreationOriginalOutcomeGrpcCodec;
import net.firedevops.firemud.gamedesign.v1.CreateAuthorizedTenantRequest;
import net.firedevops.firemud.gamedesign.v1.CreateAuthorizedTenantResponse;
import net.firedevops.firemud.gamedesign.v1.OriginalTenantCreationOutcome;
import net.firedevops.firemud.gamedesign.v1.TenantCreationServiceGrpc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Unregistered Account workload client. GD independently resolves actual Account authorization. */
public final class GameDesignAuthorizedTenantCreationClient
    extends AbstractReloadingBlockingGrpcClient<
        TenantCreationServiceGrpc.TenantCreationServiceBlockingStub> {
  private static final int MAX_BYTES = 4096;
  private static final long MAX_CALL_MILLIS = 5000;
  private final String namespace;
  private final GrpcServerPeerIdentityClientInterceptor peer;

  public GameDesignAuthorizedTenantCreationClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tls,
      GrpcChannelFactory channels,
      String namespace) {
    super(
        endpoints,
        FileBackedMtls.require(tls, "Authorized tenant creation"),
        channels,
        GameDesignAuthorizedTenantCreationClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(namespace))
      throw new IllegalArgumentException("Canonical workload namespace required");
    this.namespace = namespace;
    peer =
        new GrpcServerPeerIdentityClientInterceptor(
            "spiffe://firemud/ns/" + namespace + "/sa/game-design-service");
  }

  public void init() throws SSLException, IOException {
    requireNoAmbientSql("Authorized tenant creation client initialization requires no SQL");
    initOnce(
        "Authorized tenant creation client is closed",
        "Authorized tenant creation client is not initialized");
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getGameDesignService();
  }

  @Override
  protected String defaultTarget() {
    return "game-design-service:6565";
  }

  @Override
  protected TenantCreationServiceGrpc.TenantCreationServiceBlockingStub buildStub(
      ManagedChannel channel) {
    String expectedPeer = "spiffe://firemud/ns/" + namespace + "/sa/game-design-service";
    return TenantCreationServiceGrpc.newBlockingStub(ClientInterceptors.intercept(channel, peer))
        .withMaxInboundMessageSize(MAX_BYTES)
        .withMaxOutboundMessageSize(MAX_BYTES)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(expectedPeer))
        .withCompression("gzip");
  }

  /** One bounded send, without retry. Its receipt is data, never a privately verified terminal. */
  public FreshTenantCreationEvidence create(
      UUID callId,
      Binding binding,
      String sourceKey,
      String name,
      String description,
      long validBeforeEpochMillis) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive())
      throw new IllegalStateException("Creation network work must be outside SQL");
    Objects.requireNonNull(binding);
    requireUuid(Objects.requireNonNull(callId).toString());
    GameTenantCreationDigest.validateCandidateInputs(sourceKey, name, description);
    if (!namespace.equals(binding.targetNamespace())
        || !binding
            .creationRequestDigest()
            .equals(
                GameTenantCreationDigest.requestDigest(
                    namespace, binding.creationRequestId(), sourceKey, name, description))) {
      throw new IllegalArgumentException("Exact bounded original creation inputs required");
    }
    if (validBeforeEpochMillis <= 0 || validBeforeEpochMillis > 9_007_199_254_740_991L)
      throw new IllegalStateException("Original creation dispatch deadline invalid");
    long remaining = validBeforeEpochMillis - System.currentTimeMillis();
    if (remaining <= 0)
      throw new IllegalStateException("Original creation dispatch deadline expired");
    var builder =
        CreateAuthorizedTenantRequest.newBuilder()
            .setSchemaVersion(1)
            .setCreateRequestId(callId.toString())
            .setTargetNamespace(namespace)
            .setCreationRequestId(binding.creationRequestId().toString())
            .setAccountAuthorizationOperationId(
                binding.accountAuthorizationOperationId().toString())
            .setInitiatingAccountId(binding.initiatingAccountId().toString())
            .setCreationRequestDigest(binding.creationRequestDigest())
            .setAccountAuthorizationDigest(binding.accountAuthorizationDigest())
            .setSourceGameTenantKey(sourceKey)
            .setName(name);
    if (description != null) builder.setDescription(description);
    var request = builder.build();
    if (request.getSerializedSize() > MAX_BYTES)
      throw new IllegalArgumentException("Creation request exceeds wire budget");
    var response =
        requireInitializedStub("Authorized tenant creation client is not initialized")
            .withDeadlineAfter(Math.min(MAX_CALL_MILLIS, remaining), TimeUnit.MILLISECONDS)
            .createAuthorizedTenant(request);
    return decode(callId, binding, sourceKey, response);
  }

  /**
   * One explicit exclusion request, valid for historical recovery too. Its Empty reply is not
   * proof.
   */
  public void closeOriginal(Binding binding) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive())
      throw new IllegalStateException("Original creation closure network work must be outside SQL");
    Objects.requireNonNull(binding);
    if (!namespace.equals(binding.targetNamespace()))
      throw new IllegalArgumentException("Original closure namespace differs from Account");
    var request =
        GameTenantCreationOriginalOutcomeGrpcCodec.toReadRequest(
            new GameTenantCreationOriginalOutcomeGrpcCodec.ReadRequest(UUID.randomUUID(), binding));
    if (request.getSerializedSize() > MAX_BYTES)
      throw new IllegalArgumentException("Original closure exceeds wire budget");
    var ack =
        requireInitializedStub("Authorized tenant creation client is not initialized")
            .withDeadlineAfter(MAX_CALL_MILLIS, TimeUnit.MILLISECONDS)
            .closeOriginalTenantCreation(request);
    if (ack == null || !ack.getUnknownFields().asMap().isEmpty())
      throw new IllegalStateException("Original closure acknowledgment is malformed");
  }

  private static FreshTenantCreationEvidence decode(
      UUID callId, Binding binding, String sourceKey, CreateAuthorizedTenantResponse response) {
    try {
      if (response == null
          || response.getSerializedSize() > MAX_BYTES
          || !response.getUnknownFields().asMap().isEmpty()
          || response.getSchemaVersion() != 1
          || !callId.toString().equals(response.getCreateRequestId())
          || !binding.targetNamespace().equals(response.getTargetNamespace())
          || !binding.creationRequestId().toString().equals(response.getCreationRequestId())
          || !binding
              .accountAuthorizationOperationId()
              .toString()
              .equals(response.getAccountAuthorizationOperationId())
          || !binding.initiatingAccountId().toString().equals(response.getInitiatingAccountId())
          || !binding.creationRequestDigest().equals(response.getCreationRequestDigest())
          || !binding.accountAuthorizationDigest().equals(response.getAccountAuthorizationDigest())
          || response.getStatus()
              != OriginalTenantCreationOutcome.ORIGINAL_TENANT_CREATION_OUTCOME_CREATED
          || !response.hasCreatedReceipt())
        throw new IllegalArgumentException("Uncorrelated creation reply");
      var receipt = response.getCreatedReceipt();
      if (!receipt.getUnknownFields().asMap().isEmpty()
          || !binding.targetNamespace().equals(receipt.getTargetNamespace())
          || !binding.creationRequestId().toString().equals(receipt.getCreationRequestId())
          || !binding.creationRequestDigest().equals(receipt.getRequestDigest())
          || !sourceKey.equals(receipt.getSourceGameTenantKey()))
        throw new IllegalArgumentException("Unbound creation receipt");
      return new FreshTenantCreationEvidence(
          receipt.getSchemaVersion(),
          receipt.getTargetNamespace(),
          requireUuid(receipt.getCreationRequestId()),
          requireUuid(receipt.getOperationId()),
          receipt.getRequestDigest(),
          requireUuid(receipt.getCanonicalTenantId()),
          receipt.getSourceGameRowId(),
          receipt.getSourceGameTenantKey(),
          receipt.getProvenanceKind(),
          receipt.getEvidenceDigest());
    } catch (IllegalArgumentException malformed) {
      throw new IllegalStateException(
          "Game Design creation response differs from the exact original", malformed);
    }
  }

  private static UUID requireUuid(String value) {
    UUID uuid = UUID.fromString(value);
    if (new UUID(0, 0).equals(uuid) || !uuid.toString().equals(value))
      throw new IllegalArgumentException("Canonical non-nil UUID required");
    return uuid;
  }
}
