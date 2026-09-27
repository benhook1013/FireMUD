package net.firedevops.firemud.accountservice.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer.OutboxCheckpointEntry;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer.OutboxSourceEvidence;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.AuthorityTuple;

/** Same-fence Account snapshot candidate for the denied runtime membership RPC. */
public record RuntimeMembershipSnapshotDto(
    Long requestAccountId,
    Long requestTenantId,
    String accountUuid,
    String tenantUuid,
    boolean membershipExists,
    boolean gameplayAdmissionAllowed,
    MembershipBaseline membershipBaseline,
    List<String> roles,
    AuthorityTuple authorityTuple,
    String issuanceFence,
    Instant evaluatedAt,
    List<OutboxCheckpointEntry> outboxCheckpoints,
    List<OutboxSourceEvidence> outboxSourceEvidence) {
  public RuntimeMembershipSnapshotDto {
    if (requestAccountId == null
        || requestAccountId <= 0L
        || requestTenantId == null
        || requestTenantId <= 0L) {
      throw new IllegalArgumentException(
          "Runtime membership account and tenant IDs must be positive");
    }
    accountUuid = requireCanonicalUuid(accountUuid, "Account UUID");
    tenantUuid = requireCanonicalUuid(tenantUuid, "tenant UUID");
    Objects.requireNonNull(membershipBaseline, "unchanged membership baseline is required");
    roles = List.copyOf(roles);
    Objects.requireNonNull(authorityTuple, "complete membership authority tuple is required");
    Objects.requireNonNull(issuanceFence, "Account issuance fence is required");
    Objects.requireNonNull(evaluatedAt, "membership evaluation time is required");
    outboxCheckpoints = List.copyOf(outboxCheckpoints);
    outboxSourceEvidence = List.copyOf(outboxSourceEvidence);
    String version = membershipBaseline.membershipVersion().get(tenantUuid);
    if (membershipBaseline.membershipVersion().size() != 1
        || version == null
        || !membershipBaseline
            .membershipAuthorityGeneration()
            .equals(authorityTuple.membershipAuthorityGeneration().get(tenantUuid))) {
      throw new IllegalArgumentException(
          "Runtime membership baseline differs from its tenant tuple");
    }
    if (membershipExists != "ACTIVE".equals(membershipBaseline.membershipLifecycleState())
        || gameplayAdmissionAllowed != membershipExists
        || membershipExists != roles.contains("player")) {
      throw new IllegalArgumentException("Runtime membership state is not a supported snapshot");
    }
  }

  private static String requireCanonicalUuid(String value, String field) {
    Objects.requireNonNull(value, field + " is required");
    try {
      if (!java.util.UUID.fromString(value).toString().equals(value)) {
        throw new IllegalArgumentException(field + " must be a canonical lowercase UUID");
      }
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(field + " must be a canonical lowercase UUID", exception);
    }
    return value;
  }

  public record MembershipBaseline(
      String membershipLifecycleState,
      Map<String, String> membershipVersion,
      String membershipAuthorityGeneration) {
    public MembershipBaseline {
      Objects.requireNonNull(membershipLifecycleState, "membership lifecycle state is required");
      membershipVersion = Map.copyOf(membershipVersion);
      Objects.requireNonNull(
          membershipAuthorityGeneration, "membership authority generation is required");
      if (membershipVersion.size() != 1
          || membershipVersion.values().stream().anyMatch(value -> !value.matches("[1-9][0-9]*"))
          || !membershipAuthorityGeneration.matches("[1-9][0-9]*")) {
        throw new IllegalArgumentException("Runtime membership baseline is not canonical");
      }
      if (!"ACTIVE".equals(membershipLifecycleState)
          && !"MISSING".equals(membershipLifecycleState)) {
        throw new IllegalArgumentException("Runtime membership baseline lifecycle is unsupported");
      }
    }
  }
}
