package net.firedevops.firemud.accountservice.repository;

import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Credential-free cancellation-resolution comparison data. Construction grants no authorization.
 * The unregistered owner must invoke an explicit final authorization source under its SQL locks;
 * actual Account actor/primary-authentication integration belongs to its outer composition.
 */
public final class AccountHostingSubscriptionTerminalResolution {
  private AccountHostingSubscriptionTerminalResolution() {}

  public record Request(
      UUID resolutionRequestId,
      UUID failedRequestId,
      UUID creationRequestId,
      UUID subscriptionId,
      UUID tenantId,
      long expectedFailedOperationVersion,
      long expectedSubscriptionVersion,
      UUID actorAccountId,
      UUID controlUiIssuanceOperationId,
      String controlUiTokenHash,
      UUID auditEventId,
      String auditReason) {
    public Request {
      for (UUID id :
          new UUID[] {
            resolutionRequestId,
            failedRequestId,
            creationRequestId,
            subscriptionId,
            tenantId,
            actorAccountId,
            controlUiIssuanceOperationId,
            auditEventId
          }) {
        AccountBillingCustomerRecord.requireUuid(id);
      }
      if (resolutionRequestId.equals(failedRequestId)
          || expectedFailedOperationVersion < 1
          || expectedSubscriptionVersion < 1
          || expectedSubscriptionVersion >= Long.MAX_VALUE - 2) {
        throw new IllegalArgumentException(
            "Distinct resolution identity and advanceable versions required");
      }
      requireTokenHash(controlUiTokenHash);
      auditReason = requireReason(auditReason);
    }

    public byte[] payload() {
      return AccountHostingSubscriptionLifecycleEvidence.encode(
          Map.ofEntries(
              Map.entry("schemaVersion", "account-hosting-terminal-resolution/v1"),
              Map.entry("outcome", "CANCELED"),
              Map.entry("resolutionRequestId", resolutionRequestId.toString()),
              Map.entry("failedRequestId", failedRequestId.toString()),
              Map.entry("creationRequestId", creationRequestId.toString()),
              Map.entry("subscriptionId", subscriptionId.toString()),
              Map.entry("tenantId", tenantId.toString()),
              Map.entry(
                  "expectedFailedOperationVersion", Long.toString(expectedFailedOperationVersion)),
              Map.entry("expectedSubscriptionVersion", Long.toString(expectedSubscriptionVersion)),
              Map.entry("actorAccountId", actorAccountId.toString()),
              Map.entry("controlUiIssuanceOperationId", controlUiIssuanceOperationId.toString()),
              Map.entry("controlUiTokenHash", controlUiTokenHash),
              Map.entry("auditEventId", auditEventId.toString()),
              Map.entry("auditReason", auditReason)));
    }

    public String digest() {
      return AccountHostingSubscriptionLifecycleEvidence.digest(payload());
    }
  }

  public record LockedTarget(
      Request request, UUID billingOwnerAccountId, long currentSubscriptionVersion) {
    public LockedTarget {
      Objects.requireNonNull(request);
      AccountBillingCustomerRecord.requireUuid(billingOwnerAccountId);
    }
  }

  /**
   * Same-transaction primary-authentication source identifiers, not authority from construction.
   */
  public record AuthorizedActor(
      UUID actorAccountId,
      UUID controlUiIssuanceOperationId,
      String controlUiTokenHash,
      Instant authenticatedAt) {
    public AuthorizedActor {
      AccountBillingCustomerRecord.requireUuid(actorAccountId);
      AccountBillingCustomerRecord.requireUuid(controlUiIssuanceOperationId);
      requireTokenHash(controlUiTokenHash);
      Objects.requireNonNull(authenticatedAt);
    }
  }

  /**
   * Must revalidate the exact actual Account actor and fresh primary authentication in this SQL.
   */
  @FunctionalInterface
  public interface Authorization {
    AuthorizedActor requireAuthorized(LockedTarget target);
  }

  private static String requireReason(String reason) {
    String normalized = Normalizer.normalize(Objects.requireNonNull(reason), Normalizer.Form.NFC);
    try {
      int bytes =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .encode(CharBuffer.wrap(normalized))
              .remaining();
      if (bytes > 1000
          || normalized.indexOf(0) >= 0
          || normalized
              .codePoints()
              .allMatch(AccountHostingSubscriptionTerminalResolution::sharedWhitespace)) {
        throw new IllegalArgumentException(
            "Nonblank credential-free audit reason of at most 1000 UTF-8 bytes required");
      }
    } catch (CharacterCodingException failure) {
      throw new IllegalArgumentException("Audit reason must contain Unicode scalars", failure);
    }
    return normalized;
  }

  private static boolean sharedWhitespace(int value) {
    return (value >= 0x0009 && value <= 0x000D)
        || value == 0x0020
        || value == 0x0085
        || value == 0x00A0
        || value == 0x1680
        || (value >= 0x2000 && value <= 0x200A)
        || value == 0x2028
        || value == 0x2029
        || value == 0x202F
        || value == 0x205F
        || value == 0x3000;
  }

  private static void requireTokenHash(String hash) {
    if (hash == null || !hash.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Exact native control-ui token hash required");
    }
  }
}
