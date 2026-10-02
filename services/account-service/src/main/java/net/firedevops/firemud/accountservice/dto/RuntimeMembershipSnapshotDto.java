package net.firedevops.firemud.accountservice.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer.OutboxCheckpointEntry;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer.OutboxSourceEvidence;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.AuthorityTuple;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.MembershipEvent;
import net.firedevops.firemud.common.account.authority.RuntimeMembershipAuthorityEvidenceValidator;
import net.firedevops.firemud.common.account.authority.RuntimeMembershipAuthorityEvidenceValidator.Snapshot;

/** Same-fence Account snapshot candidate for the denied runtime membership RPC. */
public record RuntimeMembershipSnapshotDto(
    String requestAccountUuid,
    String requestTenantUuid,
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
    List<OutboxSourceEvidence> outboxSourceEvidence,
    MembershipEvent sourceEvent) {
  public RuntimeMembershipSnapshotDto {
    requestAccountUuid = requireCanonicalUuid(requestAccountUuid, "requested Account UUID");
    requestTenantUuid = requireCanonicalUuid(requestTenantUuid, "requested tenant UUID");
    accountUuid = requireCanonicalUuid(accountUuid, "Account UUID");
    tenantUuid = requireCanonicalUuid(tenantUuid, "tenant UUID");
    if (!requestAccountUuid.equals(accountUuid) || !requestTenantUuid.equals(tenantUuid)) {
      throw new IllegalArgumentException(
          "Runtime membership request identity differs from its verified Account snapshot");
    }
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
    String lifecycleState = membershipBaseline.membershipLifecycleState();
    boolean presentLifecycle = "ACTIVE".equals(lifecycleState) || "INACTIVE".equals(lifecycleState);
    boolean activeMembership = "ACTIVE".equals(lifecycleState);
    if (membershipExists != presentLifecycle
        || gameplayAdmissionAllowed != activeMembership
        || (!membershipExists && !roles.isEmpty())
        || (activeMembership && !roles.contains("player"))) {
      throw new IllegalArgumentException("Runtime membership state is not a supported snapshot");
    }
    requireConsistentSourceEvent(
        accountUuid,
        tenantUuid,
        membershipExists,
        gameplayAdmissionAllowed,
        membershipBaseline,
        roles,
        authorityTuple,
        issuanceFence,
        outboxCheckpoints,
        outboxSourceEvidence,
        sourceEvent);
  }

  /**
   * Revalidates event identity and content against this exact producer snapshot before encoding.
   */
  public MembershipEvent requireConsistentSourceEvent() {
    return requireConsistentSourceEvent(
        accountUuid,
        tenantUuid,
        membershipExists,
        gameplayAdmissionAllowed,
        membershipBaseline,
        roles,
        authorityTuple,
        issuanceFence,
        outboxCheckpoints,
        outboxSourceEvidence,
        sourceEvent);
  }

  private static MembershipEvent requireConsistentSourceEvent(
      String accountUuid,
      String tenantUuid,
      boolean membershipExists,
      boolean gameplayAdmissionAllowed,
      MembershipBaseline membershipBaseline,
      List<String> roles,
      AuthorityTuple authorityTuple,
      String issuanceFence,
      List<OutboxCheckpointEntry> outboxCheckpoints,
      List<OutboxSourceEvidence> outboxSourceEvidence,
      MembershipEvent sourceEvent) {
    Optional<MembershipEvent> validated =
        RuntimeMembershipAuthorityEvidenceValidator.validate(
            new Snapshot(
                AccountServiceImpl.ACCOUNT_JWT_ISSUER,
                accountUuid,
                tenantUuid,
                membershipExists,
                membershipBaseline.membershipLifecycleState(),
                gameplayAdmissionAllowed,
                membershipBaseline.membershipVersion(),
                membershipBaseline.membershipAuthorityGeneration(),
                roles,
                authorityTuple,
                issuanceFence,
                outboxCheckpoints.stream()
                    .map(
                        checkpoint ->
                            new RuntimeMembershipAuthorityEvidenceValidator.Checkpoint(
                                checkpoint.outboxStreamKey(), checkpoint.outboxSequence()))
                    .toList(),
                outboxSourceEvidence.stream()
                    .map(
                        source ->
                            new RuntimeMembershipAuthorityEvidenceValidator.SourceEvidence(
                                source.outboxStreamKey(),
                                source.outboxSequence(),
                                source.eventId(),
                                source.eventDigest(),
                                source.canonicalEventJson()))
                    .toList()));
    if (sourceEvent == null && validated.isPresent()) {
      throw new IllegalArgumentException("Runtime membership source event is missing");
    }
    if (sourceEvent != null
        && validated
            .map(MembershipEvent::canonicalJson)
            .filter(sourceEvent.canonicalJson()::equals)
            .isEmpty()) {
      throw new IllegalArgumentException(
          "Runtime membership source event differs from its exact source evidence");
    }
    return validated.orElse(null);
  }

  private static String requireCanonicalUuid(String value, String field) {
    Objects.requireNonNull(value, field + " is required");
    try {
      java.util.UUID parsed = java.util.UUID.fromString(value);
      if (parsed.equals(new java.util.UUID(0L, 0L)) || !parsed.toString().equals(value)) {
        throw new IllegalArgumentException(field + " must be a non-nil canonical lowercase UUID");
      }
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(
          field + " must be a non-nil canonical lowercase UUID", exception);
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
          && !"INACTIVE".equals(membershipLifecycleState)
          && !"MISSING".equals(membershipLifecycleState)) {
        throw new IllegalArgumentException("Runtime membership baseline lifecycle is unsupported");
      }
    }
  }
}
