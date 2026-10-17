package net.firedevops.firemud.gamedesign.publication;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamedesign.CommandSource;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.AppliedEpoch;

/** Exact staged command-source application evidence, separate from the combined owner outcome. */
public record CommandApplication(
    DraftCommitBinding binding,
    UUID inheritedCommitId,
    String expectedEpoch,
    List<CommandSource.Mutation> mutations,
    CommandSnapshot snapshot) {
  public CommandApplication {
    Objects.requireNonNull(binding, "binding");
    if (inheritedCommitId != null && new UUID(0, 0).equals(inheritedCommitId)) {
      throw new IllegalArgumentException("inheritedCommitId must be non-nil when present");
    }
    requireCounter(expectedEpoch);
    mutations = List.copyOf(Objects.requireNonNull(mutations, "mutations"));
    if (mutations.isEmpty()) {
      throw new IllegalArgumentException(
          "Command application must contain explicit command mutations");
    }
    if (!CommandSource.mutationsFromStored(CommandSource.mutationsJson(mutations))
        .equals(mutations)) {
      throw new IllegalArgumentException(
          "Command application mutations must retain canonical revision order");
    }
    Objects.requireNonNull(snapshot, "snapshot");
    if (!binding.equals(snapshot.binding())
        || !Objects.equals(inheritedCommitId, snapshot.inheritedCommitId())
        || !new BigInteger(expectedEpoch)
            .add(BigInteger.ONE)
            .toString()
            .equals(snapshot.sourceEpoch())
        || mutations.stream()
            .anyMatch(mutation -> !binding.commitId().equals(mutation.commitId()))) {
      throw new IllegalArgumentException(
          "Command application evidence differs from its exact binding");
    }
    List<DraftCommitBinding.AffectedUnit> commandScopes =
        binding.affectedUnits(DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE).stream()
            .filter(CommandApplication::isCommandScope)
            .toList();
    if (commandScopes.size() != 1
        || !expectedEpoch.equals(commandScopes.getFirst().expectedEpoch())) {
      throw new IllegalArgumentException("Exact command source scope and epoch are required");
    }
  }

  public byte[] canonicalBytes() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(out, CommandSource.APPLICATION_SCHEMA);
    DraftAuthorizationFenceBinding.frame(
        out,
        inheritedCommitId == null
            ? new byte[0]
            : inheritedCommitId.toString().getBytes(StandardCharsets.UTF_8));
    DraftAuthorizationFenceBinding.frame(out, expectedEpoch);
    DraftAuthorizationFenceBinding.frame(out, CommandSource.mutationsJson(mutations));
    DraftAuthorizationFenceBinding.frame(out, snapshot.canonicalBytes());
    return out.toByteArray();
  }

  public String resultIdentity() {
    return "command-source:" + binding.commitId() + ":" + CommandSource.sha256(canonicalBytes());
  }

  public AppliedEpoch commandAppliedEpoch() {
    return new AppliedEpoch(
        CommandSource.SCOPE,
        binding.target().canonicalVersionId().toString(),
        CommandSource.SCOPE,
        CommandSource.SCOPE_ID,
        expectedEpoch,
        snapshot.sourceEpoch());
  }

  private static boolean isCommandScope(DraftCommitBinding.AffectedUnit unit) {
    return CommandSource.SCOPE.equals(unit.aggregateType())
        && CommandSource.SCOPE.equals(unit.scopeType())
        && CommandSource.SCOPE_ID.equals(unit.scopeId());
  }

  private static void requireCounter(String value) {
    if (value == null || !value.matches("0|[1-9][0-9]*")) {
      throw new IllegalArgumentException("expectedEpoch must be a canonical nonnegative decimal");
    }
  }
}
