package net.firedevops.firemud.common.conflict;

/** Simple interface for recording lock or tick conflicts. */
public interface ConflictTracker {
  /** Record a conflict for the given region or session key. */
  void recordConflict(String key);
}
