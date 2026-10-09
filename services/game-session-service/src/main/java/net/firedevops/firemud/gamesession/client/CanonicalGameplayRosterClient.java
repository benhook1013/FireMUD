package net.firedevops.firemud.gamesession.client;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.ManagedChannel;
import jakarta.annotation.PostConstruct;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicyEvidence;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActor;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActorKind;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterEntryPolicy;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterRequest;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterResponse;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterSelectedAssignmentRequest;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterSelectedAssignmentResponse;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterSnapshotReference;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterTarget;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;
import net.firedevops.firemud.gamesession.dto.CanonicalPublishedPlayerRoute;
import net.firedevops.firemud.shared.v1.PlayerExecutionContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Default-off, authenticated point-in-time reader for Entity's persisted PRESEEDED roster. */
@Component
@ConditionalOnProperty(
    prefix = "firemud.canonical-gameplay-roster-owner-read",
    name = "enabled",
    havingValue = "true")
public final class CanonicalGameplayRosterClient
    extends AbstractReloadingBlockingGrpcClient<
        CanonicalGameplayRosterServiceGrpc.CanonicalGameplayRosterServiceBlockingStub> {
  private static final long CALL_DEADLINE_SECONDS = 5L;
  // Matches Entity's bounded CanonicalGameplayRosterSnapshotDigest contract.
  private static final int MAX_ROSTER_SIZE = 100;
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final String SNAPSHOT_DIGEST_DOMAIN =
      "firemud.entity.canonical-gameplay-roster.v3";

  private final String workloadNamespace;

  public CanonicalGameplayRosterClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace) {
    super(
        endpoints,
        requireGameSessionMtls(tlsProperties),
        channelFactory,
        CanonicalGameplayRosterClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Game Session workload namespace must be one DNS label");
    }
    this.workloadNamespace = workloadNamespace;
  }

  @PostConstruct
  void init() throws SSLException, IOException {
    initReloadingClient();
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
    return CanonicalGameplayRosterServiceGrpc.newBlockingStub(channel)
        .withInterceptors(
            new GrpcServerPeerIdentityClientInterceptor(
                "spiffe://firemud/ns/" + workloadNamespace + "/sa/entity-management-service"))
        .withCompression("gzip");
  }

  /**
   * Reads one complete Entity snapshot for an exact current published route. The result is
   * discovery evidence only: it does not establish Account membership, admission, or PLAY.
   */
  public PreseededRosterSnapshot listPreseededRoster(
      CanonicalPublishedPlayerRoute publishedRoute, PlayerExecutionContext playerExecutionContext) {
    Objects.requireNonNull(publishedRoute, "publishedRoute");
    CanonicalGameplayRosterTarget expectedTarget = toExpectedTarget(publishedRoute);
    UUID canonicalAccountUuid =
        validatePlayerExecutionContext(playerExecutionContext, expectedTarget, false);
    UUID requestUuid = parseContextUuid(playerExecutionContext.getRequestId(), "request_id");
    CanonicalGameplayRosterRequest request =
        CanonicalGameplayRosterRequest.newBuilder()
            .setRequestUuid(requestUuid.toString())
            .setCanonicalAccountUuid(canonicalAccountUuid.toString())
            .setExpectedTarget(expectedTarget)
            .setPlayerExecutionContext(playerExecutionContext)
            .build();

    var currentStub = stub();
    if (currentStub == null) {
      throw new IllegalStateException("Entity canonical gameplay roster client is not initialized");
    }
    CanonicalGameplayRosterResponse response =
        currentStub
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .listPreseededRoster(request);
    return validateResponse(canonicalAccountUuid, expectedTarget, response);
  }

  /**
   * Reads Entity's owner-issued assignment reference for the actor selected in the typed context
   * from the exact previously validated SHARED PRESEEDED_ONLY roster snapshot. This point-in-time
   * reference is not Account eligibility or admission evidence; the later World hold revalidates
   * assignment.
   */
  public VerifiedPreseededAssignment readSelectedPreseededAssignment(
      PreseededRosterSnapshot roster, PlayerExecutionContext playerExecutionContext) {
    requireOutsideTransactionAndSynchronization();
    Objects.requireNonNull(roster, "roster");
    requireValidSelectedRosterSnapshot(roster);
    CanonicalGameplayRosterTarget expectedTarget = roster.target();
    UUID contextAccountUuid =
        validatePlayerExecutionContext(playerExecutionContext, expectedTarget, true);
    if (!roster.canonicalAccountUuid().equals(contextAccountUuid)) {
      throw new IllegalArgumentException(
          "Player execution context account does not match the validated Entity roster");
    }
    UUID selectedCharacterUuid =
        parseContextUuid(playerExecutionContext.getCharacterId(), "character_id");
    long actorMatches =
        roster.actors().stream()
            .filter(actor -> actor.characterUuid().equals(selectedCharacterUuid))
            .count();
    if (actorMatches != 1L) {
      throw new IllegalArgumentException(
          "Selected character must occur exactly once in the validated Entity roster");
    }

    UUID requestUuid = parseContextUuid(playerExecutionContext.getRequestId(), "request_id");
    CanonicalGameplayRosterSelectedAssignmentRequest request =
        CanonicalGameplayRosterSelectedAssignmentRequest.newBuilder()
            .setRequestUuid(requestUuid.toString())
            .setCanonicalAccountUuid(roster.canonicalAccountUuid().toString())
            .setSelectedCharacterUuid(selectedCharacterUuid.toString())
            .setExpectedTarget(expectedTarget)
            .setExpectedSnapshot(
                CanonicalGameplayRosterSnapshotReference.newBuilder()
                    .setSnapshotUuid(roster.snapshotUuid().toString())
                    .setSnapshotDigest(roster.snapshotDigest()))
            .setPlayerExecutionContext(playerExecutionContext)
            .build();

    var currentStub = stub();
    if (currentStub == null) {
      throw new IllegalStateException("Entity canonical gameplay roster client is not initialized");
    }
    CanonicalGameplayRosterSelectedAssignmentResponse response =
        currentStub
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .readSelectedPreseededAssignment(request);
    return validateSelectedAssignmentResponse(requestUuid, roster, selectedCharacterUuid, response);
  }

  private static UUID validatePlayerExecutionContext(
      PlayerExecutionContext context,
      CanonicalGameplayRosterTarget expectedTarget,
      boolean selectedCharacterRequired) {
    if (context == null) {
      throw new IllegalArgumentException("Player execution context is required");
    }
    if (!context.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException("Player execution context contains unknown fields");
    }

    UUID accountUuid = parseContextUuid(context.getAccountId(), "account_id");
    parseContextUuid(context.getSessionId(), "session_id");
    parseContextUuid(context.getRequestId(), "request_id");
    requireContextUuidEquals(context.getTenantId(), expectedTarget.getTenantUuid(), "tenant_id");
    requireContextUuidEquals(context.getRealmId(), expectedTarget.getRealmUuid(), "realm_id");
    requireContextUuidEquals(
        context.getPlayableStateNamespaceId(),
        expectedTarget.getPlayableStateNamespaceUuid(),
        "playable_state_namespace_id");
    requireContextUuidEquals(
        context.getGameInstanceId(), expectedTarget.getGameInstanceUuid(), "game_instance_id");

    PlayableStateScope contextScope =
        switch (context.getPlayableStateScope()) {
          case "SHARED" -> PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED;
          case "ISOLATED" -> PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED;
          default ->
              throw new IllegalArgumentException(
                  "Player execution context playable_state_scope must be exact SHARED or ISOLATED");
        };
    if (contextScope != expectedTarget.getPlayableStateScope()) {
      throw new IllegalArgumentException(
          "Player execution context playable_state_scope does not match the expected target");
    }

    if (selectedCharacterRequired) {
      parseContextUuid(context.getCharacterId(), "character_id");
    } else if (!context.getCharacterId().isEmpty()) {
      throw new IllegalArgumentException(
          "Initial roster reads must not select an actor in player execution context");
    }
    return accountUuid;
  }

  private static void requireContextUuidEquals(String value, String expected, String fieldName) {
    UUID parsed = parseContextUuid(value, fieldName);
    if (!parsed.toString().equals(expected)) {
      throw new IllegalArgumentException(
          "Player execution context " + fieldName + " does not match the expected target");
    }
  }

  private static UUID parseContextUuid(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Player execution context " + fieldName + " is required");
    }
    UUID parsed;
    try {
      parsed = UUID.fromString(value);
    } catch (IllegalArgumentException malformed) {
      throw new IllegalArgumentException(
          "Player execution context " + fieldName + " must be a canonical non-nil UUID", malformed);
    }
    if (parsed.equals(NIL_UUID) || !parsed.toString().equals(value)) {
      throw new IllegalArgumentException(
          "Player execution context " + fieldName + " must be a canonical non-nil UUID");
    }
    return parsed;
  }

  private static void requireOutsideTransactionAndSynchronization() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Entity selected assignment reads cannot run inside a Game Session transaction "
              + "or synchronization");
    }
  }

  private static void requireValidSelectedRosterSnapshot(PreseededRosterSnapshot roster) {
    CanonicalGameplayRosterTarget target = Objects.requireNonNull(roster.target(), "roster.target");
    if (!target.getUnknownFields().asMap().isEmpty()
        || target.getPlayableStateScope() != PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED
        || target.getEntryPolicy()
            != CanonicalGameplayRosterEntryPolicy.CANONICAL_ROSTER_ENTRY_POLICY_PRESEEDED_ONLY) {
      throw new IllegalArgumentException(
          "Selected assignment reads require an exact SHARED PRESEEDED_ONLY roster target");
    }
    if (roster.actors().size() > MAX_ROSTER_SIZE) {
      throw new IllegalArgumentException("Validated Entity roster exceeds its maximum size");
    }
    Set<UUID> characterUuids = new HashSet<>();
    for (RosterActor actor : roster.actors()) {
      if (actor == null) {
        throw new IllegalArgumentException("Validated Entity roster contains an absent actor");
      }
      UUID characterUuid = actor.characterUuid();
      requireNonNil(characterUuid, "roster character UUID");
      if (!characterUuids.add(characterUuid)) {
        throw new IllegalArgumentException("Validated Entity roster contains a duplicate actor");
      }
      String displayName = actor.displayName();
      if (displayName == null
          || displayName.isBlank()
          || !displayName.equals(displayName.trim())
          || displayName.length() > 100
          || displayName.codePoints().anyMatch(Character::isISOControl)) {
        throw new IllegalArgumentException("Validated Entity roster contains a malformed actor");
      }
    }
    if (!roster
        .snapshotDigest()
        .equals(calculateSnapshotDigest(roster.canonicalAccountUuid(), target, roster.actors()))) {
      throw new IllegalArgumentException(
          "Selected assignment input is not the exact validated Entity roster snapshot");
    }
  }

  private static VerifiedPreseededAssignment validateSelectedAssignmentResponse(
      UUID requestUuid,
      PreseededRosterSnapshot roster,
      UUID selectedCharacterUuid,
      CanonicalGameplayRosterSelectedAssignmentResponse response) {
    if (response == null) {
      throw invalidSelectedAssignmentResponse("response is absent");
    }
    if (!response.getUnknownFields().asMap().isEmpty()) {
      throw invalidSelectedAssignmentResponse("response contains unknown fields");
    }
    if (!requestUuid.toString().equals(response.getRequestUuid())) {
      throw invalidSelectedAssignmentResponse("response changed the request correlation");
    }
    if (!roster.canonicalAccountUuid().toString().equals(response.getCanonicalAccountUuid())) {
      throw invalidSelectedAssignmentResponse("response changed the canonical account UUID");
    }
    if (!selectedCharacterUuid.toString().equals(response.getSelectedCharacterUuid())) {
      throw invalidSelectedAssignmentResponse("response changed the selected character UUID");
    }
    if (!response.hasTarget()
        || !response.getTarget().getUnknownFields().asMap().isEmpty()
        || !roster.target().equals(response.getTarget())) {
      throw invalidSelectedAssignmentResponse("response changed the complete expected target");
    }
    if (!response.hasSnapshot() || !response.getSnapshot().getUnknownFields().asMap().isEmpty()) {
      throw invalidSelectedAssignmentResponse("response snapshot is absent or malformed");
    }
    UUID echoedSnapshotUuid =
        parseCanonicalUuid(response.getSnapshot().getSnapshotUuid(), "snapshot_uuid");
    String echoedSnapshotDigest = response.getSnapshot().getSnapshotDigest();
    if (!roster.snapshotUuid().equals(echoedSnapshotUuid)
        || !roster.snapshotDigest().equals(echoedSnapshotDigest)) {
      throw invalidSelectedAssignmentResponse(
          "response changed the exact roster snapshot UUID or digest");
    }
    UUID assignmentOperationId =
        parseCanonicalUuid(response.getAssignmentUuid(), "assignment_uuid");
    String intentDigest = response.getIntentDigest();
    if (intentDigest == null || !intentDigest.matches("[0-9a-f]{64}")) {
      throw invalidSelectedAssignmentResponse(
          "intent_digest is not bare canonical lowercase SHA-256 hex");
    }
    return new VerifiedPreseededAssignment(
        roster.canonicalAccountUuid(),
        selectedCharacterUuid,
        roster.target(),
        roster.snapshotUuid(),
        roster.snapshotDigest(),
        assignmentOperationId,
        intentDigest);
  }

  private static IllegalStateException invalidSelectedAssignmentResponse(String message) {
    return new IllegalStateException("Entity selected assignment response is invalid: " + message);
  }

  private static PreseededRosterSnapshot validateResponse(
      UUID expectedAccountUuid,
      CanonicalGameplayRosterTarget expectedTarget,
      CanonicalGameplayRosterResponse response) {
    if (response == null) {
      throw invalidResponse("response is absent");
    }
    if (!response.getUnknownFields().asMap().isEmpty()) {
      throw invalidResponse("response contains unknown fields");
    }
    if (!expectedAccountUuid.toString().equals(response.getCanonicalAccountUuid())) {
      throw invalidResponse("response changed the canonical account UUID");
    }
    if (!response.hasTarget()
        || !response.getTarget().getUnknownFields().asMap().isEmpty()
        || !expectedTarget.equals(response.getTarget())) {
      throw invalidResponse("response changed the complete expected target");
    }
    UUID snapshotUuid = parseCanonicalUuid(response.getSnapshotUuid(), "snapshot_uuid");
    String snapshotDigest = response.getSnapshotDigest();
    if (snapshotDigest == null || !snapshotDigest.matches("[0-9a-f]{64}")) {
      throw invalidResponse("snapshot_digest is not canonical lowercase SHA-256 hex");
    }
    if (response.getActorsCount() > MAX_ROSTER_SIZE) {
      throw invalidResponse("actor roster exceeds its maximum size");
    }

    Set<UUID> characterUuids = new HashSet<>();
    List<RosterActor> actors = new ArrayList<>(response.getActorsCount());
    for (CanonicalGameplayRosterActor actor : response.getActorsList()) {
      if (!actor.getUnknownFields().asMap().isEmpty()) {
        throw invalidResponse("actor contains unknown fields");
      }
      UUID characterUuid = parseCanonicalUuid(actor.getCharacterUuid(), "character_uuid");
      if (!characterUuids.add(characterUuid)) {
        throw invalidResponse("actor roster contains a duplicate character UUID");
      }
      if (actor.getActorKind()
          != CanonicalGameplayRosterActorKind.CANONICAL_ROSTER_ACTOR_KIND_PLAYER) {
        throw invalidResponse("actor roster contains an unsupported actor kind");
      }
      String displayName = actor.getDisplayName();
      if (displayName == null
          || displayName.isBlank()
          || !displayName.equals(displayName.trim())
          || displayName.length() > 100
          || displayName.codePoints().anyMatch(Character::isISOControl)) {
        throw invalidResponse("actor display name is malformed");
      }
      actors.add(new RosterActor(characterUuid, displayName));
    }
    if (!snapshotDigest.equals(
        calculateSnapshotDigest(expectedAccountUuid, expectedTarget, actors))) {
      throw invalidResponse("snapshot_digest does not bind the returned target and roster");
    }
    return new PreseededRosterSnapshot(
        expectedAccountUuid, snapshotUuid, snapshotDigest, expectedTarget, actors);
  }

  private static CanonicalGameplayRosterTarget toExpectedTarget(
      CanonicalPublishedPlayerRoute publishedRoute) {
    CanonicalPlayableTarget route = publishedRoute.route();
    RealmEntryPolicy policy = publishedRoute.entryPolicy();
    if (policy.entryPolicy() != RealmEntryPolicy.EntryPolicy.PRESEEDED_ONLY
        || policy.stateScope() != RealmEntryPolicy.StateScope.SHARED
        || !"SHARED".equals(route.playableStateScope())) {
      throw new IllegalArgumentException(
          "Canonical roster reads require the exact published SHARED PRESEEDED_ONLY route");
    }
    PublishedRealmEntryPolicyEvidence selectedPolicy = publishedRoute.selectedPolicyEvidence();
    publishedRoute.policySetEvidence().requireValidDigest();
    selectedPolicy.requireValidDigest(publishedRoute.policySetEvidence());
    return CanonicalGameplayRosterTarget.newBuilder()
        .setTenantUuid(route.canonicalTenantId().toString())
        .setRealmUuid(route.realmId().toString())
        .setWorldSlug(route.worldSlug())
        .setRealmSlug(route.realmSlug())
        .setGameInstanceUuid(route.canonicalGameInstanceId().toString())
        .setCatalogRevision(route.catalogRevision())
        .setPublishedPolicyDigest(rawPublishedPolicyDigest(selectedPolicy.policyDigest()))
        .setPublishedReleaseBundleRef(
            publishedRoute.policySetEvidence().publishedReleaseBundleRef())
        .setAdmissionPointerSnapshotDigest(route.admissionPointerSnapshotDigest())
        .setPublishedOwnerProofDigest(rawOwnerProofDigest(route.ownerProofDigest()))
        .setPlayableStateNamespaceUuid(route.playableStateNamespaceId().toString())
        .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
        .setEntryPolicy(
            CanonicalGameplayRosterEntryPolicy.CANONICAL_ROSTER_ENTRY_POLICY_PRESEEDED_ONLY)
        .setCanonicalVersionUuid(route.canonicalVersionId().toString())
        .setPointerVersion(route.pointerVersion())
        .setActiveWorldEpoch(route.activeWorldEpoch())
        .build();
  }

  private static String rawOwnerProofDigest(String prefixedDigest) {
    if (prefixedDigest == null || !prefixedDigest.matches("sha256:[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Canonical route owner-proof digest is malformed");
    }
    return prefixedDigest.substring("sha256:".length());
  }

  private static String rawPublishedPolicyDigest(String prefixedDigest) {
    if (prefixedDigest == null || !prefixedDigest.matches("sha256:[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Published route policy digest is malformed");
    }
    return prefixedDigest.substring("sha256:".length());
  }

  private static String calculateSnapshotDigest(
      UUID canonicalAccountUuid, CanonicalGameplayRosterTarget target, List<RosterActor> actors) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        // Mirrors Entity's canonical roster digest/v3; divergence fails closed at the client.
        write(output, SNAPSHOT_DIGEST_DOMAIN);
        write(output, canonicalAccountUuid.toString());
        write(output, target.getTenantUuid());
        write(output, target.getRealmUuid());
        write(output, target.getWorldSlug());
        write(output, target.getRealmSlug());
        write(output, target.getGameInstanceUuid());
        write(output, Long.toString(target.getCatalogRevision()));
        write(output, Long.toString(target.getPointerVersion()));
        write(output, Long.toString(target.getActiveWorldEpoch()));
        write(output, target.getCanonicalVersionUuid());
        write(output, target.getPublishedPolicyDigest());
        write(output, target.getPublishedReleaseBundleRef());
        write(output, target.getAdmissionPointerSnapshotDigest());
        write(output, target.getPublishedOwnerProofDigest());
        write(output, target.getPlayableStateNamespaceUuid());
        write(output, target.getPlayableStateScope().name());
        write(output, "PRESEEDED_ONLY");
        write(output, Integer.toString(actors.size()));
        for (RosterActor actor : actors) {
          write(output, actor.characterUuid().toString());
          write(output, actor.displayName());
        }
      }
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    } catch (NoSuchAlgorithmException | IOException impossible) {
      throw new IllegalStateException(
          "Unable to validate Entity roster snapshot digest", impossible);
    }
  }

  private static void write(DataOutputStream output, String value) throws IOException {
    byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
    output.writeInt(encoded.length);
    output.write(encoded);
  }

  private static UUID parseCanonicalUuid(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw invalidResponse(fieldName + " is missing");
    }
    UUID parsed;
    try {
      parsed = UUID.fromString(value);
    } catch (IllegalArgumentException malformed) {
      throw invalidResponse(fieldName + " is not a canonical UUID", malformed);
    }
    if (parsed.equals(NIL_UUID) || !parsed.toString().equals(value)) {
      throw invalidResponse(fieldName + " is not a canonical non-nil UUID");
    }
    return parsed;
  }

  private static void requireNonNil(UUID value, String fieldName) {
    if (value == null || NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(fieldName + " must be a canonical non-nil UUID");
    }
  }

  private static IllegalStateException invalidResponse(String message) {
    return new IllegalStateException(
        "Entity canonical gameplay roster response is invalid: " + message);
  }

  private static IllegalStateException invalidResponse(String message, Throwable cause) {
    return new IllegalStateException(
        "Entity canonical gameplay roster response is invalid: " + message, cause);
  }

  private static CommonGrpcClientProperties requireGameSessionMtls(
      CommonGrpcClientProperties tlsProperties) {
    if (tlsProperties == null) {
      throw new IllegalArgumentException("Game Session gRPC TLS configuration is required");
    }
    if (tlsProperties.isPlaintext()) {
      throw new IllegalArgumentException(
          "Canonical roster reads require Game Session workload mTLS");
    }
    if (!hasText(tlsProperties.getCertChain())
        || !hasText(tlsProperties.getPrivateKey())
        || !hasText(tlsProperties.getCaCert())) {
      throw new IllegalArgumentException(
          "Canonical roster reads require Game Session certificate, key, and CA files");
    }
    if (tlsProperties.getCertChain().trim().startsWith("classpath:")
        || tlsProperties.getPrivateKey().trim().startsWith("classpath:")
        || tlsProperties.getCaCert().trim().startsWith("classpath:")) {
      throw new IllegalArgumentException(
          "Canonical roster reads require file-backed Game Session workload mTLS");
    }
    return tlsProperties;
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  /** Entity-authenticated assignment reference bound to one exact roster actor and target. */
  public static final class VerifiedPreseededAssignment {
    private final UUID canonicalAccountUuid;
    private final UUID selectedCharacterUuid;
    private final CanonicalGameplayRosterTarget target;
    private final UUID rosterSnapshotUuid;
    private final String rosterSnapshotDigest;
    private final UUID assignmentOperationId;
    private final String intentDigest;

    private VerifiedPreseededAssignment(
        UUID canonicalAccountUuid,
        UUID selectedCharacterUuid,
        CanonicalGameplayRosterTarget target,
        UUID rosterSnapshotUuid,
        String rosterSnapshotDigest,
        UUID assignmentOperationId,
        String intentDigest) {
      this.canonicalAccountUuid =
          Objects.requireNonNull(canonicalAccountUuid, "canonicalAccountUuid");
      this.selectedCharacterUuid =
          Objects.requireNonNull(selectedCharacterUuid, "selectedCharacterUuid");
      this.target = Objects.requireNonNull(target, "target");
      this.rosterSnapshotUuid = Objects.requireNonNull(rosterSnapshotUuid, "rosterSnapshotUuid");
      this.rosterSnapshotDigest =
          Objects.requireNonNull(rosterSnapshotDigest, "rosterSnapshotDigest");
      this.assignmentOperationId =
          Objects.requireNonNull(assignmentOperationId, "assignmentOperationId");
      this.intentDigest = Objects.requireNonNull(intentDigest, "intentDigest");
    }

    public UUID canonicalAccountUuid() {
      return canonicalAccountUuid;
    }

    public UUID selectedCharacterUuid() {
      return selectedCharacterUuid;
    }

    @SuppressFBWarnings(
        value = "EI_EXPOSE_REP",
        justification = "The target is an immutable generated protobuf message.")
    public CanonicalGameplayRosterTarget target() {
      return target;
    }

    public UUID rosterSnapshotUuid() {
      return rosterSnapshotUuid;
    }

    public String rosterSnapshotDigest() {
      return rosterSnapshotDigest;
    }

    public UUID assignmentOperationId() {
      return assignmentOperationId;
    }

    public String intentDigest() {
      return intentDigest;
    }
  }

  public record RosterActor(UUID characterUuid, String displayName) {}

  /** Entity's exact source snapshot; its UUID is not admission or controller authority. */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "The target is an immutable built protobuf message; actor lists are copied.")
  public record PreseededRosterSnapshot(
      UUID canonicalAccountUuid,
      UUID snapshotUuid,
      String snapshotDigest,
      CanonicalGameplayRosterTarget target,
      List<RosterActor> actors) {
    public PreseededRosterSnapshot {
      requireNonNil(canonicalAccountUuid, "canonicalAccountUuid");
      requireNonNil(snapshotUuid, "snapshotUuid");
      Objects.requireNonNull(target, "target");
      if (snapshotDigest == null || !snapshotDigest.matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException(
            "snapshotDigest must be canonical lowercase SHA-256 hex");
      }
      actors = List.copyOf(Objects.requireNonNull(actors, "actors"));
      if (actors.size() > MAX_ROSTER_SIZE) {
        throw new IllegalArgumentException("Roster exceeds the maximum supported size");
      }
    }
  }
}
