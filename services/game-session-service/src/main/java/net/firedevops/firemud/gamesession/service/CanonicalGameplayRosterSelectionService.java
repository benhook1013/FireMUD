package net.firedevops.firemud.gamesession.service;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.gamesession.charactercreate.GameSessionCharacterCreatePublicSourceService;
import net.firedevops.firemud.gamesession.charactercreate.GameSessionCharacterCreatePublicSourceService.ResolvedPlayerCreatedRosterSource;
import net.firedevops.firemud.gamesession.client.CanonicalGameplayRosterClient;
import net.firedevops.firemud.gamesession.client.CanonicalPlayerCreatedRosterClient;
import net.firedevops.firemud.gamesession.client.CanonicalPlayerCreatedRosterClient.VerifiedPlayerCreatedCharacter;
import net.firedevops.firemud.gamesession.client.CanonicalPlayerCreatedRosterClient.VerifiedPlayerCreatedRoster;
import net.firedevops.firemud.gamesession.command.text.CanonicalGameplayActorSelection;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;
import net.firedevops.firemud.gamesession.dto.CanonicalPublishedPlayerRoute;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayRosterReferenceRepository.SelectionOutcome;
import net.firedevops.firemud.gamesession.v1.ReadPlayerCreatedRosterSourceRequest;
import net.firedevops.firemud.shared.v1.PlayerExecutionContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Non-admitting composition of the independently resolved policy and Entity's exact roster reads.
 * The execution context must already have been constructed from authenticated Game Session state;
 * this unsigned scope carrier is not authentication, Account eligibility, or gameplay authority.
 */
public final class CanonicalGameplayRosterSelectionService {
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Pattern SNAPSHOT_DIGEST = Pattern.compile("[0-9a-f]{64}");

  private final CanonicalPlayerRouteReadService routeReader;
  private final CanonicalPublishedPlayerRouteReadService publishedRouteReader;
  private final CanonicalGameplayRosterClient rosterClient;
  private final GameSessionCharacterCreatePublicSourceService createdSourceReader;
  private final CanonicalPlayerCreatedRosterClient createdRosterClient;

  public CanonicalGameplayRosterSelectionService(
      CanonicalPlayerRouteReadService routeReader,
      CanonicalPublishedPlayerRouteReadService publishedRouteReader,
      CanonicalGameplayRosterClient rosterClient) {
    this(routeReader, publishedRouteReader, rosterClient, null, null);
  }

  public CanonicalGameplayRosterSelectionService(
      CanonicalPlayerRouteReadService routeReader,
      CanonicalPublishedPlayerRouteReadService publishedRouteReader,
      CanonicalGameplayRosterClient rosterClient,
      GameSessionCharacterCreatePublicSourceService createdSourceReader,
      CanonicalPlayerCreatedRosterClient createdRosterClient) {
    this.routeReader = Objects.requireNonNull(routeReader, "routeReader");
    this.publishedRouteReader =
        Objects.requireNonNull(publishedRouteReader, "publishedRouteReader");
    this.rosterClient = Objects.requireNonNull(rosterClient, "rosterClient");
    this.createdSourceReader = createdSourceReader;
    this.createdRosterClient = createdRosterClient;
  }

  /**
   * Reads a route-bound roster, accepts only an optional one-based ordinal, and obtains Entity's
   * original policy-specific selected owner reference. An explicit ordinal must carry the identity
   * returned with its menu so a refreshed roster cannot silently rebind that choice. No result from
   * this method authorizes or activates gameplay.
   */
  public Result select(
      PlayerExecutionContext authenticatedContext,
      String selector,
      SelectionMenuIdentity expectedMenuIdentity) {
    if (hasAmbientTransaction()) {
      return new Denied(Denial.AMBIENT_TRANSACTION);
    }
    Optional<UUID> accountUuid = validateContext(authenticatedContext);
    if (accountUuid.isEmpty()) {
      return new Denied(Denial.INVALID_CONTEXT);
    }

    CanonicalPublishedPlayerRoute current;
    try {
      CanonicalPlayableTarget selectedTarget =
          routeReader.readCurrentTarget(parseCanonicalUuid(authenticatedContext.getTenantId()));
      if (selectedTarget == null || !matchesContext(authenticatedContext, selectedTarget)) {
        return new Denied(Denial.ROUTE_MISMATCH);
      }
      if ("PLAYER_CREATED".equals(selectedTarget.characterCreationPolicy())) {
        return selectPlayerCreated(
            authenticatedContext,
            selector,
            expectedMenuIdentity,
            selectedTarget,
            accountUuid.orElseThrow());
      }
      if (!"PRESEEDED_ONLY".equals(selectedTarget.characterCreationPolicy())) {
        return new Denied(Denial.UNSUPPORTED_ENTRY_POLICY);
      }
      current = publishedRouteReader.readCurrent(selectedTarget);
      if (current == null || !matchesContext(authenticatedContext, current.route())) {
        return new Denied(Denial.ROUTE_MISMATCH);
      }
    } catch (RuntimeException unavailableOrInvalid) {
      return new Denied(routeFailure(unavailableOrInvalid));
    }

    Result result =
        selectForCurrent(
            authenticatedContext,
            selector,
            expectedMenuIdentity,
            current,
            accountUuid.orElseThrow());
    var outcome = completionOutcome(result);
    if (outcome != null) {
      try {
        rosterClient.completeSelection(current, authenticatedContext, outcome);
      } catch (RuntimeException unavailableOrRecoverable) {
        return result instanceof Denied ? result : new Denied(Denial.ROSTER_UNAVAILABLE);
      }
    }
    return result;
  }

  private static SelectionOutcome completionOutcome(Result result) {
    SelectionOutcome outcome = null;
    if (result instanceof SelectedOwnerReference
        || result instanceof SelectedPlayerCreatedOwnerReference) {
      outcome = SelectionOutcome.SELECTED;
    } else if (result instanceof SelectionRequired) {
      outcome = SelectionOutcome.MENU;
    } else if (result instanceof Denied denied
        && switch (denied.reason()) {
          case NO_PRESEEDED_ACTOR,
              NO_PLAYER_CREATED_ACTOR,
              INVALID_SELECTOR,
              SELECTOR_OUT_OF_RANGE,
              MISSING_MENU_IDENTITY,
              STALE_MENU_IDENTITY,
              ROUTE_CHANGED ->
              true;
          default -> false;
        }) {
      outcome = SelectionOutcome.DENIED;
    }
    return outcome;
  }

  private Result selectPlayerCreated(
      PlayerExecutionContext context,
      String selector,
      SelectionMenuIdentity expectedMenu,
      CanonicalPlayableTarget current,
      UUID account) {
    if (createdSourceReader == null || createdRosterClient == null) {
      return new Denied(Denial.ROSTER_UNAVAILABLE);
    }
    if (!"SHARED".equals(current.playableStateScope())) {
      return new Denied(Denial.ROUTE_MISMATCH);
    }
    var request = createdSourceRequest(current, context.getRequestId());
    ResolvedPlayerCreatedRosterSource source;
    try {
      source = createdSourceReader.resolvePlayerCreatedRosterSource(request);
      if (source == null
          || !current.equals(source.route())
          || !matchesContext(context, source.route())) {
        return new Denied(Denial.ROUTE_CHANGED);
      }
    } catch (RuntimeException invalidOrUnavailable) {
      return new Denied(routeFailure(invalidOrUnavailable));
    }
    var result = selectForCreated(context, selector, expectedMenu, source, request, account);
    var outcome = completionOutcome(result);
    if (outcome != null) {
      try {
        createdRosterClient.completeSelection(source, context, outcome);
      } catch (RuntimeException unavailableOrRecoverable) {
        return result instanceof Denied ? result : new Denied(Denial.ROSTER_UNAVAILABLE);
      }
    }
    return result;
  }

  private Result selectForCreated(
      PlayerExecutionContext context,
      String selector,
      SelectionMenuIdentity expectedMenu,
      ResolvedPlayerCreatedRosterSource source,
      ReadPlayerCreatedRosterSourceRequest sourceRequest,
      UUID account) {
    VerifiedPlayerCreatedRoster snapshot;
    try {
      createdRosterClient.reserveSelectionInput(
          source,
          context,
          selector,
          expectedMenu == null ? null : expectedMenu.snapshotUuid(),
          expectedMenu == null ? null : expectedMenu.snapshotDigest());
      snapshot = createdRosterClient.listPlayerCreatedRoster(source, context);
    } catch (StatusRuntimeException failure) {
      return new Denied(rosterFailure(failure));
    } catch (RuntimeException invalidOrUnavailable) {
      var failure = routeFailure(invalidOrUnavailable);
      return new Denied(
          failure == Denial.INVALID_ROUTE_PROOF ? Denial.INVALID_ROSTER_PROOF : failure);
    }
    var freshness = requireExactCurrentCreatedSource(source, sourceRequest);
    if (freshness != null) {
      return new Denied(freshness);
    }
    if (!matchesCreatedRoster(snapshot, source, context, account)) {
      return new Denied(Denial.INVALID_ROSTER_PROOF);
    }
    if (selector != null && !selector.isBlank()) {
      if (expectedMenu == null) {
        return new Denied(Denial.MISSING_MENU_IDENTITY);
      }
      if (!expectedMenu.matches(snapshot)) {
        return new Denied(Denial.STALE_MENU_IDENTITY);
      }
    }
    var selection = CanonicalGameplayActorSelection.select(snapshot, selector);
    if (selection instanceof CanonicalGameplayActorSelection.SelectionRequired required) {
      return new SelectionRequired(
          required.choices(),
          new SelectionMenuIdentity(snapshot.snapshotUuid(), snapshot.snapshotDigest()));
    }
    if (selection instanceof CanonicalGameplayActorSelection.Denied denied) {
      return new Denied(mapSelectionDenial(denied.reason()));
    }
    var selected = (CanonicalGameplayActorSelection.Selected) selection;
    var selectedContext =
        context.toBuilder().setCharacterId(selected.characterUuid().toString()).build();
    VerifiedPlayerCreatedCharacter reference;
    try {
      reference = createdRosterClient.readSelectedPlayerCreatedCharacter(snapshot, selectedContext);
    } catch (StatusRuntimeException failure) {
      return new Denied(assignmentFailure(failure, expectedMenu != null));
    } catch (RuntimeException invalidOrUnavailable) {
      var failure = routeFailure(invalidOrUnavailable);
      return new Denied(
          failure == Denial.INVALID_ROUTE_PROOF ? Denial.INVALID_ASSIGNMENT_PROOF : failure);
    }
    if (reference == null
        || reference.roster() != snapshot
        || !reference.selectedCharacterUuid().equals(selected.characterUuid())
        || !reference.response().getCanonicalAccountUuid().equals(account.toString())
        || !reference.response().getRequestUuid().equals(context.getRequestId())
        || !reference.response().getTarget().equals(source.target())
        || !reference.response().getSourceIdentity().equals(source.sourceIdentity())
        || !reference
            .response()
            .getSnapshot()
            .getSnapshotUuid()
            .equals(snapshot.snapshotUuid().toString())
        || !reference
            .response()
            .getSnapshot()
            .getSnapshotDigest()
            .equals(snapshot.snapshotDigest())) {
      return new Denied(Denial.INVALID_ASSIGNMENT_PROOF);
    }
    freshness = requireExactCurrentCreatedSource(source, sourceRequest);
    if (freshness != null) {
      return new Denied(freshness);
    }
    return new SelectedPlayerCreatedOwnerReference(
        reference, selected.displayName(), selected.ordinal());
  }

  private static boolean matchesCreatedRoster(
      VerifiedPlayerCreatedRoster snapshot,
      ResolvedPlayerCreatedRosterSource source,
      PlayerExecutionContext context,
      UUID account) {
    if (snapshot == null || snapshot.resolvedSource() == null) return false;
    var original =
        net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence
            .playerCreatedRequest(
                UUID.fromString(context.getRequestId()),
                account,
                UUID.fromString(context.getSessionId()),
                source.route().targetNamespace(),
                source.target(),
                source.sourceIdentity());
    return original.equals(snapshot.originalSelection())
        && account.equals(snapshot.canonicalAccountUuid())
        && source.route().equals(snapshot.resolvedSource().route())
        && source.target().equals(snapshot.target())
        && source.sourceIdentity().equals(snapshot.sourceIdentity());
  }

  private Denial requireExactCurrentCreatedSource(
      ResolvedPlayerCreatedRosterSource expected, ReadPlayerCreatedRosterSourceRequest request) {
    try {
      var actual = createdSourceReader.resolvePlayerCreatedRosterSource(request);
      return actual != null
              && expected.route().equals(actual.route())
              && expected.target().equals(actual.target())
              && expected.sourceIdentity().equals(actual.sourceIdentity())
          ? null
          : Denial.ROUTE_CHANGED;
    } catch (RuntimeException invalidOrUnavailable) {
      return routeFailure(invalidOrUnavailable);
    }
  }

  private static ReadPlayerCreatedRosterSourceRequest createdSourceRequest(
      CanonicalPlayableTarget route, String requestId) {
    return ReadPlayerCreatedRosterSourceRequest.newBuilder()
        .setSchemaVersion(1)
        .setReadRequestId(requestId)
        .setTargetNamespace(route.targetNamespace())
        .setCanonicalTenantId(route.canonicalTenantId().toString())
        .setRealmId(route.realmId().toString())
        .setWorldSlug(route.worldSlug())
        .setRealmSlug(route.realmSlug())
        .setPlayableStateNamespaceId(route.playableStateNamespaceId().toString())
        .setPlayableStateScope(route.playableStateScope())
        .setCanonicalGameInstanceId(route.canonicalGameInstanceId().toString())
        .setCatalogRevision(route.catalogRevision())
        .setPointerVersion(route.pointerVersion())
        .setExpectedCanonicalVersionId(route.canonicalVersionId().toString())
        .setActiveWorldEpoch(route.activeWorldEpoch())
        .build();
  }

  private Result selectForCurrent(
      PlayerExecutionContext authenticatedContext,
      String selector,
      SelectionMenuIdentity expectedMenuIdentity,
      CanonicalPublishedPlayerRoute current,
      UUID accountUuid) {
    CanonicalGameplayRosterClient.PreseededRosterSnapshot snapshot;
    try {
      rosterClient.reserveSelectionInput(
          current,
          authenticatedContext,
          selector,
          expectedMenuIdentity == null ? null : expectedMenuIdentity.snapshotUuid(),
          expectedMenuIdentity == null ? null : expectedMenuIdentity.snapshotDigest());
      snapshot = rosterClient.listPreseededRoster(current, authenticatedContext);
    } catch (StatusRuntimeException failure) {
      return new Denied(rosterFailure(failure));
    } catch (RuntimeException invalidOrUnavailable) {
      Denial failure = routeFailure(invalidOrUnavailable);
      return new Denied(
          failure == Denial.INVALID_ROUTE_PROOF ? Denial.INVALID_ROSTER_PROOF : failure);
    }
    Denial listFreshnessFailure = requireExactCurrentRoute(current);
    if (listFreshnessFailure != null) {
      return new Denied(listFreshnessFailure);
    }

    Optional<CanonicalGameplayActorSelection.ValidatedRoster> validated =
        CanonicalGameplayActorSelection.validate(snapshot, accountUuid, current);
    if (validated.isEmpty()) {
      return new Denied(Denial.INVALID_ROSTER_PROOF);
    }

    if (selector != null && !selector.isBlank()) {
      if (expectedMenuIdentity == null) {
        return new Denied(Denial.MISSING_MENU_IDENTITY);
      }
      if (!expectedMenuIdentity.matches(snapshot)) {
        return new Denied(Denial.STALE_MENU_IDENTITY);
      }
    }

    CanonicalGameplayActorSelection.SelectionResult selection =
        CanonicalGameplayActorSelection.select(validated.orElseThrow(), selector);
    if (selection instanceof CanonicalGameplayActorSelection.SelectionRequired required) {
      return new SelectionRequired(
          required.choices(),
          new SelectionMenuIdentity(snapshot.snapshotUuid(), snapshot.snapshotDigest()));
    }
    if (selection instanceof CanonicalGameplayActorSelection.Denied denied) {
      return new Denied(mapSelectionDenial(denied.reason()));
    }

    CanonicalGameplayActorSelection.Selected selected =
        (CanonicalGameplayActorSelection.Selected) selection;
    PlayerExecutionContext selectedContext =
        authenticatedContext.toBuilder()
            .setCharacterId(selected.characterUuid().toString())
            .build();

    CanonicalGameplayRosterClient.VerifiedPreseededAssignment ownerReference;
    try {
      ownerReference = rosterClient.readSelectedPreseededAssignment(snapshot, selectedContext);
    } catch (StatusRuntimeException failure) {
      return new Denied(assignmentFailure(failure, expectedMenuIdentity != null));
    } catch (RuntimeException invalidOrUnavailable) {
      return new Denied(Denial.INVALID_ASSIGNMENT_PROOF);
    }
    if (!matchesOwnerReference(ownerReference, selected)) {
      return new Denied(Denial.INVALID_ASSIGNMENT_PROOF);
    }

    Denial assignmentFreshnessFailure = requireExactCurrentRoute(current);
    if (assignmentFreshnessFailure != null) {
      return new Denied(assignmentFreshnessFailure);
    }
    return new SelectedOwnerReference(ownerReference, selected.displayName(), selected.ordinal());
  }

  private Denial requireExactCurrentRoute(CanonicalPublishedPlayerRoute expected) {
    try {
      CanonicalPublishedPlayerRoute current = publishedRouteReader.readCurrent(expected.route());
      return expected.equals(current) ? null : Denial.ROUTE_CHANGED;
    } catch (RuntimeException unavailableOrInvalid) {
      return routeFailure(unavailableOrInvalid);
    }
  }

  private static Denial routeFailure(RuntimeException failure) {
    if (failure instanceof CanonicalPublishedPlayerRouteReadService.StaleAuthorityException) {
      return Denial.ROUTE_CHANGED;
    }
    if (failure instanceof CanonicalPlayerRouteReadService.ReadUnavailableException
        || failure instanceof CanonicalPublishedPlayerRouteReadService.StorageUnavailableException
        || failure
            instanceof CanonicalPublishedPlayerRouteReadService.GameDesignUnavailableException
        || failure
            instanceof
            GameSessionCharacterCreatePublicSourceService.SourceDependencyUnavailableException
        || failure instanceof StatusRuntimeException status && isTransient(status.getStatus())) {
      return Denial.ROUTE_UNAVAILABLE;
    }
    if (failure instanceof StatusRuntimeException status
        && status.getStatus().getCode() == Status.Code.FAILED_PRECONDITION) {
      return Denial.ROUTE_CHANGED;
    }
    return Denial.INVALID_ROUTE_PROOF;
  }

  private static Denial rosterFailure(StatusRuntimeException failure) {
    Status status = failure.getStatus();
    if (isTransient(status)) {
      return Denial.ROSTER_UNAVAILABLE;
    }
    if (status.getCode() == Status.Code.FAILED_PRECONDITION) {
      return Denial.ROUTE_CHANGED;
    }
    return Denial.INVALID_ROSTER_PROOF;
  }

  private static Denial assignmentFailure(
      StatusRuntimeException failure, boolean menuIdentitySupplied) {
    Status status = failure.getStatus();
    if (isTransient(status)) {
      return Denial.ASSIGNMENT_UNAVAILABLE;
    }
    if (status.getCode() == Status.Code.FAILED_PRECONDITION) {
      return menuIdentitySupplied ? Denial.STALE_MENU_IDENTITY : Denial.ROUTE_CHANGED;
    }
    return Denial.INVALID_ASSIGNMENT_PROOF;
  }

  private static boolean isTransient(Status status) {
    return switch (status.getCode()) {
      case UNAVAILABLE, DEADLINE_EXCEEDED, RESOURCE_EXHAUSTED, ABORTED -> true;
      default -> false;
    };
  }

  private static Optional<UUID> validateContext(PlayerExecutionContext context) {
    if (context == null || !context.getUnknownFields().asMap().isEmpty()) {
      return Optional.empty();
    }
    try {
      UUID accountUuid = parseCanonicalUuid(context.getAccountId());
      parseCanonicalUuid(context.getSessionId());
      parseCanonicalUuid(context.getRequestId());
      parseCanonicalUuid(context.getTenantId());
      parseCanonicalUuid(context.getRealmId());
      parseCanonicalUuid(context.getPlayableStateNamespaceId());
      parseCanonicalUuid(context.getGameInstanceId());
      if ((!"SHARED".equals(context.getPlayableStateScope())
              && !"ISOLATED".equals(context.getPlayableStateScope()))
          || !context.getCharacterId().isEmpty()) {
        return Optional.empty();
      }
      return Optional.of(accountUuid);
    } catch (IllegalArgumentException malformed) {
      return Optional.empty();
    }
  }

  private static UUID parseCanonicalUuid(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Canonical UUID is required");
    }
    UUID parsed = UUID.fromString(value);
    if (parsed.equals(NIL_UUID) || !parsed.toString().equals(value)) {
      throw new IllegalArgumentException("Canonical UUID is malformed");
    }
    return parsed;
  }

  private static boolean matchesContext(
      PlayerExecutionContext context, CanonicalPlayableTarget route) {
    return context.getTenantId().equals(route.canonicalTenantId().toString())
        && context.getRealmId().equals(route.realmId().toString())
        && context.getPlayableStateNamespaceId().equals(route.playableStateNamespaceId().toString())
        && context.getPlayableStateScope().equals(route.playableStateScope())
        && context.getGameInstanceId().equals(route.canonicalGameInstanceId().toString());
  }

  private static boolean matchesOwnerReference(
      CanonicalGameplayRosterClient.VerifiedPreseededAssignment ownerReference,
      CanonicalGameplayActorSelection.Selected selected) {
    return ownerReference != null
        && ownerReference.canonicalAccountUuid().equals(selected.canonicalAccountUuid())
        && ownerReference.selectedCharacterUuid().equals(selected.characterUuid())
        && ownerReference.target().equals(selected.target())
        && ownerReference.rosterSnapshotUuid().equals(selected.rosterSnapshotUuid())
        && ownerReference.rosterSnapshotDigest().equals(selected.rosterSnapshotDigest());
  }

  private static Denial mapSelectionDenial(CanonicalGameplayActorSelection.Denial reason) {
    return switch (reason) {
      case INVALID_SNAPSHOT -> Denial.INVALID_ROSTER_PROOF;
      case NO_PRESEEDED_ACTOR -> Denial.NO_PRESEEDED_ACTOR;
      case NO_PLAYER_CREATED_ACTOR -> Denial.NO_PLAYER_CREATED_ACTOR;
      case INVALID_SELECTOR -> Denial.INVALID_SELECTOR;
      case SELECTOR_OUT_OF_RANGE -> Denial.SELECTOR_OUT_OF_RANGE;
    };
  }

  private static boolean hasAmbientTransaction() {
    return TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive();
  }

  public sealed interface Result
      permits SelectedOwnerReference,
          SelectedPlayerCreatedOwnerReference,
          SelectionRequired,
          Denied {}

  /**
   * Entity's original committed owner reference, still only source evidence for later revalidation.
   */
  public record SelectedOwnerReference(
      CanonicalGameplayRosterClient.VerifiedPreseededAssignment ownerReference,
      String displayName,
      int ordinal)
      implements Result {
    public SelectedOwnerReference {
      Objects.requireNonNull(ownerReference, "ownerReference");
      Objects.requireNonNull(displayName, "displayName");
      if (ordinal < 1) {
        throw new IllegalArgumentException("ordinal must be one-based");
      }
    }
  }

  /** Complete original creation terminal for later owner revalidation; never actor admission. */
  public record SelectedPlayerCreatedOwnerReference(
      VerifiedPlayerCreatedCharacter ownerReference, String displayName, int ordinal)
      implements Result {
    public SelectedPlayerCreatedOwnerReference {
      Objects.requireNonNull(ownerReference, "ownerReference");
      Objects.requireNonNull(displayName, "displayName");
      if (ordinal < 1) throw new IllegalArgumentException("ordinal must be one-based");
    }
  }

  public record SelectionMenuIdentity(UUID snapshotUuid, String snapshotDigest) {
    public SelectionMenuIdentity {
      Objects.requireNonNull(snapshotUuid, "snapshotUuid");
      Objects.requireNonNull(snapshotDigest, "snapshotDigest");
      if (NIL_UUID.equals(snapshotUuid) || !SNAPSHOT_DIGEST.matcher(snapshotDigest).matches()) {
        throw new IllegalArgumentException("Selection menu identity is malformed");
      }
    }

    private boolean matches(VerifiedPlayerCreatedRoster snapshot) {
      return snapshotUuid.equals(snapshot.snapshotUuid())
          && snapshotDigest.equals(snapshot.snapshotDigest());
    }

    private boolean matches(CanonicalGameplayRosterClient.PreseededRosterSnapshot snapshot) {
      return snapshotUuid.equals(snapshot.snapshotUuid())
          && snapshotDigest.equals(snapshot.snapshotDigest());
    }
  }

  public record SelectionRequired(
      List<CanonicalGameplayActorSelection.Choice> choices, SelectionMenuIdentity menuIdentity)
      implements Result {
    public SelectionRequired {
      choices = List.copyOf(Objects.requireNonNull(choices, "choices"));
      Objects.requireNonNull(menuIdentity, "menuIdentity");
    }
  }

  public record Denied(Denial reason) implements Result {
    public Denied {
      Objects.requireNonNull(reason, "reason");
    }
  }

  public enum Denial {
    AMBIENT_TRANSACTION,
    INVALID_CONTEXT,
    ROUTE_UNAVAILABLE,
    INVALID_ROUTE_PROOF,
    ROUTE_MISMATCH,
    ROUTE_CHANGED,
    ROSTER_UNAVAILABLE,
    INVALID_ROSTER_PROOF,
    NO_PRESEEDED_ACTOR,
    NO_PLAYER_CREATED_ACTOR,
    UNSUPPORTED_ENTRY_POLICY,
    INVALID_SELECTOR,
    SELECTOR_OUT_OF_RANGE,
    MISSING_MENU_IDENTITY,
    STALE_MENU_IDENTITY,
    ASSIGNMENT_UNAVAILABLE,
    INVALID_ASSIGNMENT_PROOF
  }
}
