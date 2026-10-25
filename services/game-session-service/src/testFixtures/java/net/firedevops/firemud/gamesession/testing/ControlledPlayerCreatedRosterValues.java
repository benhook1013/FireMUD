package net.firedevops.firemud.gamesession.testing;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.testing.ControlledPlayerCreatedRosterTerminalFixtures;
import net.firedevops.firemud.common.world.CanonicalPlayerCreatedRosterEvidence;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActor;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActorKind;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterResponse;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterSnapshotReference;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterTarget;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPlayerCreatedRosterRequest;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPlayerCreatedRosterResponse;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPlayerCreatedRosterSourceIdentity;
import net.firedevops.firemud.entitymanagement.v1.CanonicalSelectedPlayerCreatedCharacterRequest;
import net.firedevops.firemud.entitymanagement.v1.CanonicalSelectedPlayerCreatedCharacterResponse;
import net.firedevops.firemud.shared.v1.PlayerExecutionContext;

/** Shared controlled created-roster values for Game Session unit compositions only. */
public final class ControlledPlayerCreatedRosterValues {
  private static final String TEST_NAMESPACE = "firemud";

  private ControlledPlayerCreatedRosterValues() {}

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "Test protobuf values are immutable and collection inputs are copied.")
  public record Values(
      String namespace,
      CanonicalGameplayRosterTarget target,
      CanonicalPlayerCreatedRosterSourceIdentity source,
      PlayerExecutionContext context,
      net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceRequest original,
      CanonicalPlayerCreatedRosterResponse roster,
      List<CanonicalGameplayRosterActor> actors,
      Map<UUID, CanonicalSelectedPlayerCreatedCharacterResponse> selected) {
    public Values {
      actors = List.copyOf(actors);
      selected = Map.copyOf(selected);
    }
  }

  /** Uses the shared Common codec vector without changing its complete original terminal. */
  public static Values forClient() {
    var bundle =
        ControlledPlayerCreatedRosterTerminalFixtures.withFiveMebibyteAccountProtection(
            TEST_NAMESPACE);
    var original = bundle.originalRequest();
    var target = target(original);
    var source = source(original);
    var context = context(original, target);
    CanonicalPlayerCreatedRosterResponse roster;
    try {
      roster =
          CanonicalPlayerCreatedRosterResponse.parseFrom(
              bundle.original().getCanonicalPlayerCreatedRosterReceipt());
    } catch (com.google.protobuf.InvalidProtocolBufferException invalid) {
      throw new IllegalStateException("Controlled created roster vector is invalid", invalid);
    }
    UUID selectedActor = UUID.fromString(bundle.selected().getSelectedCharacterUuid());
    return new Values(
        original.getTargetNamespace(),
        target,
        source,
        context,
        original,
        roster,
        roster.getRoster().getActorsList(),
        Map.of(selectedActor, bundle.selected()));
  }

  /**
   * Builds the variable-size selection menu around the same Common action, source and complete
   * terminal vector, then validates each selected receipt with the production evidence codec.
   */
  public static Values forSelection(
      int actorCount, UUID snapshotUuid, UUID firstActor, UUID secondActor) {
    if (actorCount < 0 || actorCount > 2) {
      throw new IllegalArgumentException("Controlled selection vector supports zero to two actors");
    }
    var bundle =
        ControlledPlayerCreatedRosterTerminalFixtures.withFiveMebibyteAccountProtection(
            TEST_NAMESPACE);
    var original = bundle.originalRequest();
    var target = target(original);
    var source = source(original);
    var context = context(original, target);
    var actors = new ArrayList<CanonicalGameplayRosterActor>();
    if (actorCount >= 1) actors.add(actor(firstActor, "Created One"));
    if (actorCount == 2) actors.add(actor(secondActor, "Created Two"));
    String digest =
        CanonicalPlayerCreatedRosterEvidence.computeSnapshotDigest(
            original.getCanonicalAccountUuid(), target, source, actors);
    var rosterRequest =
        CanonicalPlayerCreatedRosterRequest.newBuilder()
            .setRequestUuid(original.getOperationUuid())
            .setCanonicalAccountUuid(original.getCanonicalAccountUuid())
            .setExpectedTarget(target)
            .setExpectedSource(source)
            .setPlayerExecutionContext(context)
            .build();
    var roster =
        CanonicalPlayerCreatedRosterEvidence.requireResponse(
            rosterRequest,
            CanonicalPlayerCreatedRosterResponse.newBuilder()
                .setSourceIdentity(source)
                .setRoster(
                    CanonicalGameplayRosterResponse.newBuilder()
                        .setCanonicalAccountUuid(original.getCanonicalAccountUuid())
                        .setTarget(target)
                        .setSnapshotUuid(snapshotUuid.toString())
                        .setSnapshotDigest(digest)
                        .addAllActors(actors))
                .build());
    var snapshot =
        CanonicalGameplayRosterSnapshotReference.newBuilder()
            .setSnapshotUuid(snapshotUuid.toString())
            .setSnapshotDigest(digest)
            .build();
    var selected = new LinkedHashMap<UUID, CanonicalSelectedPlayerCreatedCharacterResponse>();
    for (var actor : actors) {
      UUID actorUuid = UUID.fromString(actor.getCharacterUuid());
      var request =
          CanonicalSelectedPlayerCreatedCharacterRequest.newBuilder()
              .setRequestUuid(original.getOperationUuid())
              .setCanonicalAccountUuid(original.getCanonicalAccountUuid())
              .setSelectedCharacterUuid(actorUuid.toString())
              .setExpectedTarget(target)
              .setExpectedSource(source)
              .setExpectedSnapshot(snapshot)
              .setPlayerExecutionContext(context.toBuilder().setCharacterId(actorUuid.toString()))
              .build();
      String creationOperation = UUID.randomUUID().toString();
      var template = bundle.selected().getTerminal();
      var terminal =
          template.toBuilder()
              .setCharacterId(actorUuid.toString())
              .setTerminalId(UUID.randomUUID().toString())
              .setEntityIntakeRequestId(UUID.randomUUID().toString())
              .setOriginalRequest(
                  template.getOriginalRequest().toBuilder()
                      .setOriginal(
                          template.getOriginalRequest().getOriginal().toBuilder()
                              .setOperationId(creationOperation)))
              .setAccountOriginal(
                  template.getAccountOriginal().toBuilder().setOperationId(creationOperation));
      var response =
          bundle.selected().toBuilder()
              .setCreationOperationUuid(creationOperation)
              .setRequestUuid(original.getOperationUuid())
              .setCanonicalAccountUuid(original.getCanonicalAccountUuid())
              .setSelectedCharacterUuid(actorUuid.toString())
              .setTarget(target)
              .setSnapshot(snapshot)
              .setSourceIdentity(source)
              .setTerminal(terminal)
              .build();
      selected.put(
          actorUuid,
          CanonicalPlayerCreatedRosterEvidence.requireSelectedResponse(request, response));
    }
    return new Values(
        original.getTargetNamespace(), target, source, context, original, roster, actors, selected);
  }

  private static CanonicalGameplayRosterTarget target(
      net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceRequest original) {
    try {
      return CanonicalGameplayRosterTarget.parseFrom(original.getCanonicalTarget());
    } catch (com.google.protobuf.InvalidProtocolBufferException invalid) {
      throw new IllegalStateException("Controlled created target vector is invalid", invalid);
    }
  }

  private static CanonicalPlayerCreatedRosterSourceIdentity source(
      net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceRequest original) {
    try {
      return CanonicalPlayerCreatedRosterSourceIdentity.parseFrom(
          original.getCanonicalPlayerCreatedSourceIdentity());
    } catch (com.google.protobuf.InvalidProtocolBufferException invalid) {
      throw new IllegalStateException("Controlled created source vector is invalid", invalid);
    }
  }

  private static PlayerExecutionContext context(
      net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceRequest original,
      CanonicalGameplayRosterTarget target) {
    return PlayerExecutionContext.newBuilder()
        .setRequestId(original.getOperationUuid())
        .setAccountId(original.getCanonicalAccountUuid())
        .setSessionId(original.getSessionUuid())
        .setTenantId(target.getTenantUuid())
        .setRealmId(target.getRealmUuid())
        .setGameInstanceId(target.getGameInstanceUuid())
        .setPlayableStateNamespaceId(target.getPlayableStateNamespaceUuid())
        .setPlayableStateScope("SHARED")
        .build();
  }

  private static CanonicalGameplayRosterActor actor(UUID uuid, String name) {
    return CanonicalGameplayRosterActor.newBuilder()
        .setCharacterUuid(uuid.toString())
        .setDisplayName(name)
        .setActorKind(CanonicalGameplayRosterActorKind.CANONICAL_ROSTER_ACTOR_KIND_PLAYER)
        .build();
  }
}
