package net.firedevops.firemud.accountservice.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer.OutboxCheckpointEntry;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer.OutboxSourceEvidence;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.AuthorityTuple;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.MembershipEvent;

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
    if (!membershipExists) {
      if (sourceEvent != null || !outboxSourceEvidence.isEmpty()) {
        throw new IllegalArgumentException(
            "Sequence-zero membership snapshot cannot carry an event source");
      }
      return null;
    }

    if (sourceEvent == null || outboxSourceEvidence.size() != 1) {
      throw new IllegalArgumentException(
          "Positive membership snapshot requires its exact source event");
    }
    OutboxSourceEvidence source = outboxSourceEvidence.getFirst();
    MembershipEvent verified = MembershipAuthorityEventV1Codec.verify(source.canonicalEventJson());
    if (!verified.canonicalJson().equals(source.canonicalEventJson())
        || !sourceEvent.canonicalJson().equals(source.canonicalEventJson())
        || !verified.eventId().equals(source.eventId())
        || !verified.eventDigest().equals(source.eventDigest())
        || !verified.outboxStreamKey().equals(source.outboxStreamKey())
        || !verified.outboxSequence().equals(source.outboxSequence())
        || !verified.accountId().equals(accountUuid)
        || !verified.tenantId().equals(tenantUuid)
        || !verified
            .membershipLifecycleState()
            .equals(membershipBaseline.membershipLifecycleState())
        || !verified.membershipVersion().equals(membershipBaseline.membershipVersion())
        || !verified
            .membershipAuthorityGeneration()
            .equals(membershipBaseline.membershipAuthorityGeneration())
        || !verified.authorityTuple().equals(authorityTuple)
        || !verified.issuanceFence().equals(issuanceFence)
        || !verified.roles().equals(roles)
        || verified.gameplayAdmissionAllowed() != gameplayAdmissionAllowed) {
      throw new IllegalArgumentException(
          "Positive membership source event differs from its exact Account snapshot");
    }
    List<OutboxCheckpointEntry> matchingCheckpoints =
        outboxCheckpoints.stream()
            .filter(item -> item.outboxStreamKey().equals(verified.outboxStreamKey()))
            .toList();
    if (matchingCheckpoints.size() != 1
        || !matchingCheckpoints.getFirst().outboxSequence().equals(verified.outboxSequence())) {
      throw new IllegalArgumentException(
          "Positive membership source event differs from its outbox checkpoint");
    }
    return verified;
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
