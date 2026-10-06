package net.firedevops.firemud.worldmanagement.tenant;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Complete fresh graph and the original Account operation; neither supplies permission. */
public record WorldDraftGraphApplication(
    WorldDraftTerminalOperation operation, WorldDraftTopologyCommitPlan plan) {
  public WorldDraftGraphApplication {
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(plan, "plan");
    if (!operation.binding().equals(plan.binding())
        || !operation.ownerBinding().equals(plan.ownerBinding())) {
      throw new IllegalArgumentException(
          "World graph application differs from original Account operation");
    }
    Set<String> families =
        plan.graph().nodes().stream()
            .map(
                node ->
                    node.mutation()
                        .getAggregateType()
                        .name()
                        .replace("WORLD_DESIGN_AGGREGATE_TYPE_", ""))
            .collect(Collectors.toSet());
    if (!families.equals(
        Set.of(
            "REGION",
            "ZONE",
            "ROOM",
            "ROOM_EXIT",
            "GENERATION_RULE",
            "WORLD_ENTITY_SPAWN_BINDING"))) {
      throw new IllegalArgumentException(
          "Account-bound application requires all six fresh World families");
    }
  }
}
