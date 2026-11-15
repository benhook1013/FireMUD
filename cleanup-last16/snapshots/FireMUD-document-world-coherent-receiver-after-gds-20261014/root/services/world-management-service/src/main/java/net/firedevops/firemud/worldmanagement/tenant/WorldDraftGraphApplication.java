package net.firedevops.firemud.worldmanagement.tenant;

import java.util.Map;
import java.util.Objects;
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
    plan.graph()
        .freshGraphDeclaration()
        .ifPresent(
            declaration -> {
              Map<String, Long> actualCounts =
                  plan.graph().nodes().stream()
                      .collect(
                          Collectors.groupingBy(
                              node ->
                                  node.mutation()
                                      .getAggregateType()
                                      .name()
                                      .replace("WORLD_DESIGN_AGGREGATE_TYPE_", ""),
                              Collectors.counting()));
              for (var family : declaration.familyCounts()) {
                String name = family.family().name().replace("WORLD_DESIGN_AGGREGATE_TYPE_", "");
                if (actualCounts.getOrDefault(name, 0L) != family.count()) {
                  throw new IllegalArgumentException(
                      "Account-bound application differs from its complete family declaration");
                }
              }
            });
  }
}
