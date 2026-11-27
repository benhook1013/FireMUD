package net.firedevops.firemud.worldmanagement.data;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import net.firedevops.firemud.common.smoke.RunOwnedFixtureImmutableFileReader;
import net.firedevops.firemud.worldmanagement.tenant.WorldRuntimeInstallationRegistry;
import net.firedevops.firemud.worldmanagement.tenant.WorldRuntimeInstallationRegistry.RegistryUnavailableException;

/**
 * Pre-v1 waiver (ADR 0185): Compose has no deployed installation controller, so the run-owned
 * fixture reads its runtime installation inventory from a root-owned, read-only file written by the
 * smoke wrapper. Holding re-reads the exact bytes; it cannot prevent a root change, which the
 * deployed controller must do before v1. Never used under the prod profile.
 */
public final class PreV1ComposeRuntimeInstallationSource
    implements WorldRuntimeInstallationRegistry.ProtectedInstallationSource {
  private static final int MAX_INVENTORY_BYTES = 64 * 1024;

  private final Path inventoryPath;

  public PreV1ComposeRuntimeInstallationSource(Path inventoryPath) {
    this.inventoryPath = Objects.requireNonNull(inventoryPath, "inventoryPath");
  }

  @Override
  public WorldRuntimeInstallationRegistry.HeldInstallation acquireCurrent() {
    byte[] held = read();
    return new WorldRuntimeInstallationRegistry.HeldInstallation() {
      @Override
      public byte[] exactArtifactBytes() {
        return held.clone();
      }

      @Override
      public void requireCurrent() {
        if (!Arrays.equals(held, read())) {
          throw new RegistryUnavailableException(
              "Compose runtime installation inventory changed while held");
        }
      }

      @Override
      public void close() {}
    };
  }

  private byte[] read() {
    return RunOwnedFixtureImmutableFileReader.readBoundedImmutableFile(
        inventoryPath,
        MAX_INVENTORY_BYTES,
        () ->
            new RegistryUnavailableException(
                "Compose runtime installation inventory is missing, writable or oversized"));
  }
}
