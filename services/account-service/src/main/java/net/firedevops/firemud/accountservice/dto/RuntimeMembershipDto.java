package net.firedevops.firemud.accountservice.dto;

import java.util.List;

public record RuntimeMembershipDto(
    Long accountId,
    Long tenantId,
    List<String> roles,
    boolean gameplayAdmissionAllowed,
    long membershipVersion,
    Long membershipAuthorityGeneration,
    String authorityTupleJson,
    String evaluatedAt,
    List<RuntimeOutboxCheckpointDto> outboxCheckpoints) {
  public RuntimeMembershipDto {
    roles = roles == null ? List.of() : List.copyOf(roles);
    outboxCheckpoints = outboxCheckpoints == null ? List.of() : List.copyOf(outboxCheckpoints);
  }

  /** Current runtime compatibility constructor; target authority evidence is not live yet. */
  public RuntimeMembershipDto(
      Long accountId,
      Long tenantId,
      boolean gameplayAdmissionAllowed,
      long membershipVersion,
      String evaluatedAt) {
    this(
        accountId,
        tenantId,
        List.of(),
        gameplayAdmissionAllowed,
        membershipVersion,
        null,
        null,
        evaluatedAt,
        List.of());
  }
}
