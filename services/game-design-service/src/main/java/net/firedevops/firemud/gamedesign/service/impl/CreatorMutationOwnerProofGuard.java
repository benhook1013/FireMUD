package net.firedevops.firemud.gamedesign.service.impl;

import java.util.Objects;
import java.util.function.Supplier;

/** Shared deny-only boundary for mutations without Account commit-bound owner proof. */
final class CreatorMutationOwnerProofGuard {
  private CreatorMutationOwnerProofGuard() {}

  static <T> T denyUntilAccountCommitBoundProof(Supplier<? extends RuntimeException> refusal) {
    throw Objects.requireNonNull(refusal.get(), "refusal");
  }
}
