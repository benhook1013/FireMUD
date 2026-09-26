package net.firedevops.firemud.accountservice.dto;

import java.util.UUID;

/** Provisional per-membership transition receipt, not a canonical authority outbox checkpoint. */
public record MembershipTransitionReceipt(
    String receiptStreamKey,
    long receiptSequence,
    UUID receiptId,
    String receiptDigest,
    String evidenceStatus,
    String transitionType,
    String requestId,
    long membershipId) {}
