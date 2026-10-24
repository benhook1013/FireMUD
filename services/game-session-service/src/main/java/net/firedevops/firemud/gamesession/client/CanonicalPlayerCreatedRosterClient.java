package net.firedevops.firemud.gamesession.client;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence;
import net.firedevops.firemud.common.world.CanonicalPlayerCreatedRosterEvidence;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActor;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterSnapshotReference;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterTarget;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPlayerCreatedRosterRequest;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPlayerCreatedRosterResponse;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPlayerCreatedRosterSourceIdentity;
import net.firedevops.firemud.entitymanagement.v1.CanonicalSelectedPlayerCreatedCharacterRequest;
import net.firedevops.firemud.entitymanagement.v1.CanonicalSelectedPlayerCreatedCharacterResponse;
import net.firedevops.firemud.entitymanagement.v1.EntityCharacterCreateTerminal;
import net.firedevops.firemud.gamesession.charactercreate.GameSessionCharacterCreatePublicSourceService.ResolvedPlayerCreatedRosterSource;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayRosterReferenceRepository.SelectionOutcome;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayRosterReferenceOwner;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterSelectionInput;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceRequest;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceResponse;
import net.firedevops.firemud.shared.v1.PlayerExecutionContext;

/** Unregistered manual consumer of independently resolved SHARED PLAYER_CREATED source facts. */
public final class CanonicalPlayerCreatedRosterClient
    extends AbstractReloadingBlockingGrpcClient<
        CanonicalGameplayRosterServiceGrpc.CanonicalGameplayRosterServiceBlockingStub> {
  private static final long PLAYER_CREATED_OUTER_RPC_SECONDS = 20L;

  private final String namespace;
  private final CanonicalGameplayRosterReferenceOwner owner;

  public CanonicalPlayerCreatedRosterClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tls,
      GrpcChannelFactory channels,
      String namespace,
      CanonicalGameplayRosterReferenceOwner owner) {
    super(
        endpoints,
        requireFileBackedMtls(tls, "PLAYER_CREATED roster reads"),
        channels,
        CanonicalPlayerCreatedRosterClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("Fixed Game Session workload namespace required");
    }
    this.namespace = namespace;
    this.owner = owner;
  }

  public void init() throws IOException {
    requireNoAmbientSql("PLAYER_CREATED client initialization requires no ambient SQL");
    initOnce("PLAYER_CREATED roster client is closed", "Entity PLAYER_CREATED roster unavailable");
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getEntityManagementService();
  }

  @Override
  protected String defaultTarget() {
    return "entity-management-service:6565";
  }

  @Override
  protected CanonicalGameplayRosterServiceGrpc.CanonicalGameplayRosterServiceBlockingStub buildStub(
      ManagedChannel channel) {
    String peer = "spiffe://firemud/ns/" + namespace + "/sa/entity-management-service";
    return CanonicalGameplayRosterServiceGrpc.newBlockingStub(channel)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(peer))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(peer))
        .withCompression("gzip");
  }

  public void reserveSelectionInput(
      ResolvedPlayerCreatedRosterSource source,
      PlayerExecutionContext context,
      String selector,
      UUID menuSnapshotUuid,
      String digest) {
    requireOutsideSql();
    var request = rosterRequest(source, context);
    var input =
        CanonicalGameplayRosterSelectionInput.newBuilder()
            .setSchemaVersion(1)
            .setSelector(selector == null ? "" : selector);
    if (menuSnapshotUuid != null || digest != null) {
      input
          .setMenuSnapshotUuid(
              Objects.requireNonNull(menuSnapshotUuid, "menuSnapshotUuid").toString())
          .setMenuSnapshotDigest(Objects.requireNonNull(digest, "digest"));
    }
    requireOwner()
        .reserveSelectionInput(
            original(request),
            CanonicalGameplayRosterReferenceEvidence.requireSelectionInput(input.build()));
  }

  public void completeSelection(
      ResolvedPlayerCreatedRosterSource source,
      PlayerExecutionContext context,
      SelectionOutcome outcome) {
    requireOutsideSql();
    requireOwner().completeSelection(original(rosterRequest(source, context)), outcome);
  }

  public VerifiedPlayerCreatedRoster listPlayerCreatedRoster(
      ResolvedPlayerCreatedRosterSource source, PlayerExecutionContext context) {
    requireOutsideSql();
    var request = rosterRequest(source, context);
    var originalRequest = original(request);
    var referenceOwner = requireOwner();
    var retained = requireOriginal(originalRequest, referenceOwner.begin(originalRequest));
    if (!retained.getCanonicalPlayerCreatedRosterReceipt().isEmpty()) {
      var response =
          CanonicalPlayerCreatedRosterEvidence.requireResponse(
              request, CanonicalGameplayRosterReferenceEvidence.playerCreatedReceipt(retained));
      return new VerifiedPlayerCreatedRoster(source, request, originalRequest, response);
    }
    var current = requireStub();
    referenceOwner.markRosterReadStarted(originalRequest);
    var response =
        CanonicalPlayerCreatedRosterEvidence.requireResponse(
            request,
            current
                .withDeadlineAfter(PLAYER_CREATED_OUTER_RPC_SECONDS, TimeUnit.SECONDS)
                .withMaxInboundMessageSize(
                    CanonicalPlayerCreatedRosterEvidence.MAX_ROSTER_RECEIPT_BYTES)
                .listPlayerCreatedRoster(request));
    var recorded =
        requireOriginal(
            originalRequest, referenceOwner.recordPlayerCreated(originalRequest, response));
    if (!response.equals(CanonicalGameplayRosterReferenceEvidence.playerCreatedReceipt(recorded))) {
      throw new IllegalArgumentException(
          "Recorded PLAYER_CREATED roster differs from Entity response");
    }
    return new VerifiedPlayerCreatedRoster(source, request, originalRequest, response);
  }

  public VerifiedPlayerCreatedCharacter readSelectedPlayerCreatedCharacter(
      VerifiedPlayerCreatedRoster roster, PlayerExecutionContext context) {
    requireOutsideSql();
    Objects.requireNonNull(roster, "roster");
    // Recheck the exact original context and private source, not a caller-built response wrapper.
    var originalContext = roster.listRequest.getPlayerExecutionContext();
    var listContext = context.toBuilder().clearCharacterId().build();
    if (!originalContext.equals(listContext)
        || !roster.listRequest.equals(rosterRequest(roster.source, listContext))) {
      throw new IllegalArgumentException(
          "Selected context or source changed the original roster operation");
    }
    long matches =
        roster.actors().stream()
            .filter(actor -> actor.getCharacterUuid().equals(context.getCharacterId()))
            .count();
    if (matches != 1L) {
      throw new IllegalArgumentException(
          "Selected actor must occur exactly once in the original menu");
    }
    var request =
        CanonicalPlayerCreatedRosterEvidence.requireSelectedRequest(
            CanonicalSelectedPlayerCreatedCharacterRequest.newBuilder()
                .setRequestUuid(roster.original.getOperationUuid())
                .setCanonicalAccountUuid(roster.original.getCanonicalAccountUuid())
                .setSelectedCharacterUuid(context.getCharacterId())
                .setExpectedTarget(roster.target())
                .setExpectedSnapshot(
                    CanonicalGameplayRosterSnapshotReference.newBuilder()
                        .setSnapshotUuid(roster.snapshotUuid().toString())
                        .setSnapshotDigest(roster.snapshotDigest()))
                .setExpectedSource(roster.sourceIdentity())
                .setPlayerExecutionContext(context)
                .build());
    var referenceOwner = requireOwner();
    var selected =
        requireOriginal(
            roster.original,
            referenceOwner.selectOriginal(
                roster.original, UUID.fromString(context.getCharacterId())));
    if (!selected.getSelectedCharacterUuid().equals(context.getCharacterId())
        || !roster.response.equals(
            CanonicalGameplayRosterReferenceEvidence.playerCreatedReceipt(selected))) {
      throw new IllegalArgumentException(
          "Selected original differs from its exact retained created menu");
    }
    CanonicalSelectedPlayerCreatedCharacterResponse response;
    boolean cached = !selected.getCanonicalSelectedPlayerCreatedCharacterReceipt().isEmpty();
    if (cached) {
      response =
          CanonicalGameplayRosterReferenceEvidence.selectedPlayerCreatedCharacterReceipt(selected);
    } else {
      response =
          requireStub()
              .withDeadlineAfter(PLAYER_CREATED_OUTER_RPC_SECONDS, TimeUnit.SECONDS)
              .withMaxInboundMessageSize(
                  CanonicalPlayerCreatedRosterEvidence.MAX_SELECTED_RECEIPT_BYTES)
              .readSelectedPlayerCreatedCharacter(request);
    }
    requireSelectedResponse(request, response);
    if (!cached) {
      referenceOwner.recordSelectedPlayerCreatedCharacter(roster.original, response);
    }
    return new VerifiedPlayerCreatedCharacter(roster, response);
  }

  private void requireSelectedResponse(
      CanonicalSelectedPlayerCreatedCharacterRequest request,
      CanonicalSelectedPlayerCreatedCharacterResponse response) {
    CanonicalPlayerCreatedRosterEvidence.requireSelectedResponse(request, response);
    if (!namespace.equals(
        response.getTerminal().getOriginalRequest().getOriginal().getTargetNamespace())) {
      throw new IllegalArgumentException("Selected creation original changed the owner namespace");
    }
  }

  private CanonicalPlayerCreatedRosterRequest rosterRequest(
      ResolvedPlayerCreatedRosterSource source, PlayerExecutionContext context) {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(context, "context");
    if (!namespace.equals(source.route().targetNamespace())) {
      throw new IllegalArgumentException("Resolved PLAYER_CREATED source changed owner namespace");
    }
    return CanonicalPlayerCreatedRosterEvidence.requireRequest(
        CanonicalPlayerCreatedRosterRequest.newBuilder()
            .setRequestUuid(context.getRequestId())
            .setCanonicalAccountUuid(context.getAccountId())
            .setExpectedTarget(source.target())
            .setExpectedSource(source.sourceIdentity())
            .setPlayerExecutionContext(context)
            .build());
  }

  private GetCanonicalGameplayRosterReferenceRequest original(
      CanonicalPlayerCreatedRosterRequest request) {
    return CanonicalGameplayRosterReferenceEvidence.playerCreatedRequest(
        UUID.fromString(request.getRequestUuid()),
            UUID.fromString(request.getCanonicalAccountUuid()),
        UUID.fromString(request.getPlayerExecutionContext().getSessionId()), namespace,
        request.getExpectedTarget(), request.getExpectedSource());
  }

  private static GetCanonicalGameplayRosterReferenceResponse requireOriginal(
      GetCanonicalGameplayRosterReferenceRequest request,
      GetCanonicalGameplayRosterReferenceResponse response) {
    CanonicalGameplayRosterReferenceEvidence.requireResponse(request, response);
    CanonicalGameplayRosterReferenceEvidence.requireDereference(response);
    return response;
  }

  private CanonicalGameplayRosterReferenceOwner requireOwner() {
    if (owner == null) {
      throw Status.UNAVAILABLE
          .withDescription("Canonical roster reference owner is unavailable")
          .asRuntimeException();
    }
    return owner;
  }

  private CanonicalGameplayRosterServiceGrpc.CanonicalGameplayRosterServiceBlockingStub
      requireStub() {
    try {
      return requireInitializedStub("Entity PLAYER_CREATED roster unavailable");
    } catch (IllegalStateException unavailable) {
      throw Status.UNAVAILABLE
          .withDescription("Entity PLAYER_CREATED roster unavailable")
          .withCause(unavailable)
          .asRuntimeException();
    }
  }

  private static void requireOutsideSql() {
    requireNoAmbientSql(
        "PLAYER_CREATED roster operations require no ambient transaction or synchronization");
  }

  /** Exact Entity response, obtained by this client or retained by the original reference owner. */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "Private resolved source and protobuf messages are immutable")
  public static final class VerifiedPlayerCreatedRoster {
    private final ResolvedPlayerCreatedRosterSource source;
    private final CanonicalPlayerCreatedRosterRequest listRequest;
    private final GetCanonicalGameplayRosterReferenceRequest original;
    private final CanonicalPlayerCreatedRosterResponse response;

    private VerifiedPlayerCreatedRoster(
        ResolvedPlayerCreatedRosterSource source,
        CanonicalPlayerCreatedRosterRequest listRequest,
        GetCanonicalGameplayRosterReferenceRequest original,
        CanonicalPlayerCreatedRosterResponse response) {
      this.source = source;
      this.listRequest = listRequest;
      this.original = original;
      this.response = response;
    }

    public ResolvedPlayerCreatedRosterSource resolvedSource() {
      return source;
    }

    public GetCanonicalGameplayRosterReferenceRequest originalSelection() {
      return original;
    }

    public CanonicalPlayerCreatedRosterResponse response() {
      return response;
    }

    public CanonicalGameplayRosterTarget target() {
      return response.getRoster().getTarget();
    }

    public CanonicalPlayerCreatedRosterSourceIdentity sourceIdentity() {
      return response.getSourceIdentity();
    }

    public UUID canonicalAccountUuid() {
      return UUID.fromString(response.getRoster().getCanonicalAccountUuid());
    }

    public UUID snapshotUuid() {
      return UUID.fromString(response.getRoster().getSnapshotUuid());
    }

    public String snapshotDigest() {
      return response.getRoster().getSnapshotDigest();
    }

    public List<CanonicalGameplayRosterActor> actors() {
      return response.getRoster().getActorsList();
    }
  }

  /** Exact persisted creation terminal; this is neither an assignment UUID nor admission proof. */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "The verified roster and complete selected protobuf response are immutable")
  public static final class VerifiedPlayerCreatedCharacter {
    private final VerifiedPlayerCreatedRoster roster;
    private final CanonicalSelectedPlayerCreatedCharacterResponse response;

    private VerifiedPlayerCreatedCharacter(
        VerifiedPlayerCreatedRoster roster,
        CanonicalSelectedPlayerCreatedCharacterResponse response) {
      this.roster = roster;
      this.response = response;
    }

    public VerifiedPlayerCreatedRoster roster() {
      return roster;
    }

    public CanonicalSelectedPlayerCreatedCharacterResponse response() {
      return response;
    }

    public UUID selectedCharacterUuid() {
      return UUID.fromString(response.getSelectedCharacterUuid());
    }

    public UUID creationOperationUuid() {
      return UUID.fromString(response.getCreationOperationUuid());
    }

    public String mutationDigest() {
      return response.getMutationDigest();
    }

    public EntityCharacterCreateTerminal terminal() {
      return response.getTerminal();
    }
  }
}
