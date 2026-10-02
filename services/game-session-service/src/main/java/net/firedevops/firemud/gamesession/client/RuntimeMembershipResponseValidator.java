package net.firedevops.firemud.gamesession.client;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeResponse;
import net.firedevops.firemud.account.v1.RuntimeMembershipBaseline;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.AccountSecurityCutoff;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.AuthorityTuple;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.MembershipEvent;
import net.firedevops.firemud.common.account.authority.RuntimeMembershipAuthorityEvidenceValidator;
import net.firedevops.firemud.common.account.authority.RuntimeMembershipAuthorityEvidenceValidator.Checkpoint;
import net.firedevops.firemud.common.account.authority.RuntimeMembershipAuthorityEvidenceValidator.Snapshot;
import net.firedevops.firemud.common.account.authority.RuntimeMembershipAuthorityEvidenceValidator.SourceEvidence;

/** Adapts Account's runtime membership protobuf to the shared closed content validator. */
public final class RuntimeMembershipResponseValidator {
  private static final String ACCOUNT_AUTHORITY_ISSUER = "firemud-account-service";
  private static final String ACCOUNT_AUTHORITY_STREAM_PREFIX = "account:auth-authority:v1:";

  private RuntimeMembershipResponseValidator() {}

  /**
   * Converts and validates one complete Account content carrier.
   *
   * <p>This checks only response content and its internal historical/current evidence binding. It
   * does not authenticate Account, validate the request echoes or freshness, prove absence in
   * Account, or authorize gameplay admission. The returned event is immutable historical evidence;
   * an empty result is the shared validator's non-admitting sequence-zero membership baseline.
   *
   * @throws IllegalArgumentException if the content carrier is incomplete, unsupported, or
   *     inconsistent
   */
  public static Optional<MembershipEvent> validateContent(
      GetTenantMembershipForRuntimeResponse response) {
    if (response == null) {
      throw invalid("response is absent");
    }
    requireNoUnknownFields(response);
    if (response.hasError()) {
      throw invalid("response contains an Account error");
    }
    if (!response.hasMembershipBaseline() || !response.hasAuthorityTuple()) {
      throw invalid("membership baseline or authority tuple is absent");
    }
    if (!isCanonicalNonNilUuid(response.getAccountId())
        || !isCanonicalUuid(response.getTenantId())) {
      throw invalid("account or tenant identity is not canonical");
    }
    if (!response
        .getMembershipBaseline()
        .equals(
            RuntimeMembershipBaseline.newBuilder()
                .setMembershipLifecycleState(response.getMembershipLifecycleState())
                .putAllMembershipVersion(response.getMembershipVersionMap())
                .setMembershipAuthorityGeneration(response.getMembershipAuthorityGeneration())
                .build())) {
      throw invalid("membership baseline differs from the current membership carrier");
    }

    var tuple = response.getAuthorityTuple();
    if (tuple.getPrivateRealmGrantVersionsCount() != 0 || tuple.hasTenantBillingCutoff()) {
      throw invalid("unsupported grant or tenant billing authority is present");
    }

    String membershipStream =
        ACCOUNT_AUTHORITY_STREAM_PREFIX
            + "membership/"
            + response.getAccountId()
            + "/"
            + response.getTenantId();
    if (response.getOutboxSourceEvidenceList().stream()
        .filter(source -> membershipStream.equals(source.getOutboxStreamKey()))
        .anyMatch(source -> !isCanonicalUuid(source.getEventId()))) {
      throw invalid("membership source event identity is not a canonical UUID");
    }

    Optional<AccountSecurityCutoff> accountSecurityCutoff =
        tuple.hasAccountSecurityCutoff()
            ? Optional.of(
                new AccountSecurityCutoff(
                    tuple.getAccountSecurityCutoff().getAccountAuthorityGeneration(),
                    tuple.getAccountSecurityCutoff().getOutboxStreamKey(),
                    tuple.getAccountSecurityCutoff().getOutboxSequence()))
            : Optional.empty();
    AuthorityTuple currentTuple =
        new AuthorityTuple(
            tuple.getIssuerAuthGeneration(),
            tuple.getAccountAuthorityGeneration(),
            tuple.getTenantAuthorityGenerationMap(),
            tuple.getMembershipAuthorityGenerationMap(),
            List.of(),
            accountSecurityCutoff,
            Optional.empty());
    List<Checkpoint> checkpoints =
        response.getOutboxCheckpointsList().stream()
            .map(
                checkpoint ->
                    new Checkpoint(checkpoint.getOutboxStreamKey(), checkpoint.getOutboxSequence()))
            .toList();
    List<SourceEvidence> sourceEvidence =
        response.getOutboxSourceEvidenceList().stream()
            .map(
                source ->
                    new SourceEvidence(
                        source.getOutboxStreamKey(),
                        source.getOutboxSequence(),
                        source.getEventId(),
                        source.getEventDigest(),
                        source.getCanonicalEventJson()))
            .toList();
    return RuntimeMembershipAuthorityEvidenceValidator.validate(
        new Snapshot(
            ACCOUNT_AUTHORITY_ISSUER,
            response.getAccountId(),
            response.getTenantId(),
            response.getMembershipExists(),
            response.getMembershipLifecycleState(),
            response.getGameplayAdmissionAllowed(),
            response.getMembershipVersionMap(),
            response.getMembershipAuthorityGeneration(),
            response.getRolesList(),
            currentTuple,
            response.getIssuanceFence(),
            checkpoints,
            sourceEvidence));
  }

  /**
   * Returns false for malformed content so existing command denial paths remain local and stable.
   */
  public static boolean hasCompleteAuthorityCarrier(
      GetTenantMembershipForRuntimeResponse response) {
    try {
      validateContent(response);
      return true;
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  private static void requireNoUnknownFields(GetTenantMembershipForRuntimeResponse response) {
    if (!response.getUnknownFields().asMap().isEmpty()
        || response.hasError() && !response.getError().getUnknownFields().asMap().isEmpty()
        || response.hasMembershipBaseline()
            && !response.getMembershipBaseline().getUnknownFields().asMap().isEmpty()) {
      throw invalid("response or a nested message contains unsupported fields");
    }
    if (!response.hasAuthorityTuple()) {
      return;
    }
    var tuple = response.getAuthorityTuple();
    if (!tuple.getUnknownFields().asMap().isEmpty()
        || tuple.hasAccountSecurityCutoff()
            && !tuple.getAccountSecurityCutoff().getUnknownFields().asMap().isEmpty()) {
      throw invalid("authority tuple contains unsupported fields");
    }
    for (var grant : tuple.getPrivateRealmGrantVersionsList()) {
      if (!grant.getUnknownFields().asMap().isEmpty()) {
        throw invalid("authority tuple grant contains unsupported fields");
      }
    }
    for (var checkpoint : response.getOutboxCheckpointsList()) {
      if (!checkpoint.getUnknownFields().asMap().isEmpty()) {
        throw invalid("outbox checkpoint contains unsupported fields");
      }
    }
    for (var source : response.getOutboxSourceEvidenceList()) {
      if (!source.getUnknownFields().asMap().isEmpty()) {
        throw invalid("outbox source evidence contains unsupported fields");
      }
    }
  }

  private static boolean isCanonicalNonNilUuid(String value) {
    return isCanonicalUuid(value) && !new UUID(0L, 0L).toString().equals(value);
  }

  private static boolean isCanonicalUuid(String value) {
    if (value == null || value.isBlank()) {
      return false;
    }
    try {
      return UUID.fromString(value).toString().equals(value);
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException("Runtime membership response " + message);
  }
}
