package net.firedevops.firemud.gamesession.service;

import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Objects;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.PublicJwksSnapshot;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.SourceUnavailableException;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.TrustedPublicJwksSource;

/** Finite readiness-only identity and digest fence; never a steady-state LOGIN trust source. */
public final class GameSessionJwtReadinessProtectedJwksSource implements TrustedPublicJwksSource {
  public static final Path JWKS_PATH = ProtectedAccountPublicJwksProjection.JWKS_PATH;

  private final GameSessionJwtReadinessLocalIdentityProvider identityProvider;
  private final ProtectedAccountPublicJwksProjection projection;

  public GameSessionJwtReadinessProtectedJwksSource(
      GameSessionJwtReadinessLocalIdentityProvider identityProvider) {
    this.identityProvider = Objects.requireNonNull(identityProvider);
    this.projection = new ProtectedAccountPublicJwksProjection();
  }

  GameSessionJwtReadinessProtectedJwksSource(
      GameSessionJwtReadinessLocalIdentityProvider identityProvider,
      Path jwksPath,
      long expectedOwnerUid) {
    this.identityProvider = Objects.requireNonNull(identityProvider);
    this.projection = new ProtectedAccountPublicJwksProjection(jwksPath, expectedOwnerUid);
  }

  static boolean isCanonicalProductionAlias(Path configuredDirectory, Path realDirectory) {
    return ProtectedAccountPublicJwksProjection.isCanonicalProductionAlias(
        configuredDirectory, realDirectory);
  }

  @Override
  public PublicJwksSnapshot load() {
    try {
      GameSessionJwtReadinessLocalIdentityProvider.LocalObservation before =
          Objects.requireNonNull(identityProvider.observe());
      byte[] bytes = projection.read();
      try {
        if (!before.wireIdentity().getAccountPublicJwksSha256().equals(sha256(bytes))) {
          throw unavailable();
        }
        GameSessionJwtReadinessLocalIdentityProvider.LocalObservation after =
            Objects.requireNonNull(identityProvider.observe());
        if (!before.equals(after)) {
          throw unavailable();
        }
        return new PublicJwksSnapshot(before.jwksSourceIdentity(), bytes);
      } finally {
        java.util.Arrays.fill(bytes, (byte) 0);
      }
    } catch (SourceUnavailableException unavailable) {
      throw unavailable;
    } catch (RuntimeException | IOException unavailable) {
      throw unavailable();
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception unavailable) {
      throw new SourceUnavailableException();
    }
  }

  private static SourceUnavailableException unavailable() {
    return new SourceUnavailableException();
  }
}
