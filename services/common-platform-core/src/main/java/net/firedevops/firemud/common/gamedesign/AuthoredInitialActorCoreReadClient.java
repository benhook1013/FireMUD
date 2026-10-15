package net.firedevops.firemud.common.gamedesign;

/** Explicit client for the standalone authenticated Game Design authored-core read. */
public interface AuthoredInitialActorCoreReadClient extends AutoCloseable {
  AuthoredInitialActorCoreReadEvidence.ReadResult read(
      AuthoredInitialActorCoreReadEvidence.ReadRequest request);

  @Override
  void close() throws Exception;
}
