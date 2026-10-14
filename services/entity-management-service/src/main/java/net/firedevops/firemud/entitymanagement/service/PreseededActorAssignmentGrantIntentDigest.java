package net.firedevops.firemud.entitymanagement.service;

/** Computes the assignment digest from the caller target before any owner reads are performed. */
final class PreseededActorAssignmentGrantIntentDigest {
  private PreseededActorAssignmentGrantIntentDigest() {}

  static String compute(PreseededActorAssignmentRequest request) {
    return PreseededActorAssignmentIntentDigest.computeGrant(request);
  }
}
