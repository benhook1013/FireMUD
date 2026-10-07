package net.firedevops.firemud.accountservice.service.session;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * Reads Account's dedicated mounted LOGIN request-digest keyring without activating it as a bean.
 * The supplied mount path is a trust root and is never used for writes.
 */
public final class AccountMountedGameplayCredentialDigestKeySource
    implements AccountGameplayCredentialRequestDigestKeySource {
  static final int KEY_BYTES = 32;
  static final int MAX_MANIFEST_BYTES = 16 * 1024;
  static final int MAX_RETAINED_KEYS = 8;
  private static final String MANIFEST_HEADER =
      "firemud-account-credential-request-digest-keyring-v1";
  private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");

  private final Path mountRoot;
  private final Clock clock;

  public AccountMountedGameplayCredentialDigestKeySource(Path mountRoot, Clock clock) {
    this.mountRoot = Objects.requireNonNull(mountRoot, "Account digest-key mount path is required");
    if (!mountRoot.isAbsolute()) {
      throw new IllegalArgumentException("Account digest-key mount path must be absolute");
    }
    this.clock = Objects.requireNonNull(clock, "Account digest-key clock is required");
  }

  /**
   * Reloads the mounted manifest for each lookup and requires the active key's horizon to be live.
   */
  @Override
  public CredentialDigestKey currentKey() {
    try (Snapshot snapshot = readCurrent()) {
      return snapshot.currentKey(clock.instant());
    }
  }

  /** Reloads the mounted manifest and resolves exactly the requested live active or retained ID. */
  @Override
  public CredentialDigestKey requireKey(String keyId) {
    try (Snapshot snapshot = readCurrent()) {
      return snapshot.requireKey(keyId, clock.instant());
    }
  }

  private Snapshot readCurrent() {
    byte[] contents = null;
    try {
      Path resolvedRoot = mountRoot.toRealPath();
      if (!Files.isDirectory(resolvedRoot, LinkOption.NOFOLLOW_LINKS)) {
        throw unavailable();
      }
      // Kubernetes projected Secrets use key -> ..data/key and ..data -> ..<version> symlinks.
      // Follow that projection only when its resolved regular file stays inside this trust root.
      Path manifest = resolvedRoot.resolve("keyring").toRealPath();
      if (!manifest.startsWith(resolvedRoot)
          || !Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
        throw unavailable();
      }
      contents = readBounded(manifest);
      return parse(contents);
    } catch (KeyUnavailableException ex) {
      throw ex;
    } catch (Exception ex) {
      throw unavailable();
    } finally {
      wipe(contents);
    }
  }

  private static byte[] readBounded(Path path) throws IOException {
    byte[] snapshot = new byte[MAX_MANIFEST_BYTES + 1];
    try {
      int total = 0;
      try (SeekableByteChannel channel =
          Files.newByteChannel(path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
        ByteBuffer buffer = ByteBuffer.wrap(snapshot);
        while (true) {
          int count = channel.read(buffer);
          if (count < 0) {
            break;
          }
          if (count == 0) {
            continue;
          }
          if (count > MAX_MANIFEST_BYTES - total) {
            throw unavailable();
          }
          total += count;
        }
      }
      return Arrays.copyOf(snapshot, total);
    } finally {
      wipe(snapshot);
    }
  }

  private static Snapshot parse(byte[] contents) {
    if (contents.length == 0 || contents.length > MAX_MANIFEST_BYTES) {
      throw unavailable();
    }
    String text;
    try {
      text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(contents))
              .toString();
    } catch (CharacterCodingException ex) {
      throw unavailable();
    }
    if (text.indexOf('\r') >= 0 || text.indexOf('\0') >= 0 || text.startsWith("\uFEFF")) {
      throw unavailable();
    }
    for (int index = 0; index < text.length(); index++) {
      char value = text.charAt(index);
      if (value > 0x7e || (value < 0x20 && value != '\n')) {
        throw unavailable();
      }
    }

    String normalized = text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
    if (normalized.isEmpty() || normalized.endsWith("\n")) {
      throw unavailable();
    }
    String[] lines = normalized.split("\n", -1);
    if (!MANIFEST_HEADER.equals(lines[0]) || lines.length < 2) {
      throw unavailable();
    }

    Map<String, KeyEntry> keys = new HashMap<>();
    Set<String> seenIds = new HashSet<>();
    KeyEntry active = null;
    int retainedCount = 0;
    try {
      for (int index = 1; index < lines.length; index++) {
        String[] fields = lines[index].split(" ", -1);
        if (fields.length != 4) {
          throw unavailable();
        }
        boolean isActive = "active".equals(fields[0]);
        boolean isRetained = "retained".equals(fields[0]);
        if (!isActive && !isRetained) {
          throw unavailable();
        }
        if (isActive) {
          if (active != null || index != 1) {
            throw unavailable();
          }
        } else {
          if (active == null || ++retainedCount > MAX_RETAINED_KEYS) {
            throw unavailable();
          }
        }

        Instant retainedUntil = parseEpochMillis(fields[2]);
        KeyEntry entry = parseEntry(fields[1], fields[3], retainedUntil);
        if (!seenIds.add(entry.keyId())) {
          entry.destroy();
          throw unavailable();
        }
        keys.put(entry.keyId(), entry);
        if (isActive) {
          active = entry;
        }
      }
      if (active == null) {
        throw unavailable();
      }
      return new Snapshot(active, keys);
    } catch (RuntimeException ex) {
      keys.values().forEach(KeyEntry::destroy);
      throw ex instanceof KeyUnavailableException ? ex : unavailable();
    }
  }

  private static Instant parseEpochMillis(String value) {
    if (value == null || !value.matches("[1-9][0-9]{0,18}")) {
      throw unavailable();
    }
    try {
      return Instant.ofEpochMilli(Long.parseLong(value));
    } catch (RuntimeException ex) {
      throw unavailable();
    }
  }

  private static KeyEntry parseEntry(String keyId, String encodedKey, Instant retainedUntil) {
    if (keyId == null || !KEY_ID.matcher(keyId).matches() || encodedKey == null) {
      throw unavailable();
    }
    byte[] keyMaterial = null;
    try {
      keyMaterial = Base64.getUrlDecoder().decode(encodedKey);
      if (keyMaterial.length != KEY_BYTES
          || !Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(keyMaterial)
              .equals(encodedKey)) {
        throw unavailable();
      }
      return new KeyEntry(keyId, keyMaterial, retainedUntil);
    } catch (IllegalArgumentException ex) {
      throw unavailable();
    } finally {
      wipe(keyMaterial);
    }
  }

  private static KeyUnavailableException unavailable() {
    return new KeyUnavailableException();
  }

  private static void wipe(byte[] bytes) {
    if (bytes != null) {
      Arrays.fill(bytes, (byte) 0);
    }
  }

  @Override
  public String toString() {
    return "AccountMountedGameplayCredentialDigestKeySource[redacted]";
  }

  private static final class Snapshot implements AutoCloseable {
    private final KeyEntry active;
    private final Map<String, KeyEntry> keys;

    private Snapshot(KeyEntry active, Map<String, KeyEntry> keys) {
      this.active = active;
      this.keys = Map.copyOf(keys);
    }

    private CredentialDigestKey currentKey(Instant now) {
      if (!now.isBefore(active.retainedUntil())) {
        throw unavailable();
      }
      return active.toCredentialDigestKey();
    }

    private CredentialDigestKey requireKey(String keyId, Instant now) {
      KeyEntry key = keyId == null ? null : keys.get(keyId);
      if (key == null || !now.isBefore(key.retainedUntil())) {
        throw unavailable();
      }
      return key.toCredentialDigestKey();
    }

    @Override
    public void close() {
      keys.values().forEach(KeyEntry::destroy);
    }

    @Override
    public String toString() {
      return "AccountMountedGameplayCredentialDigestKeySource.Snapshot[redacted]";
    }
  }

  private static final class KeyEntry {
    private final String keyId;
    private final byte[] keyMaterial;
    private final Instant retainedUntil;

    private KeyEntry(String keyId, byte[] keyMaterial, Instant retainedUntil) {
      this.keyId = keyId;
      this.keyMaterial = keyMaterial.clone();
      this.retainedUntil = retainedUntil;
    }

    private String keyId() {
      return keyId;
    }

    private Instant retainedUntil() {
      return retainedUntil;
    }

    private CredentialDigestKey toCredentialDigestKey() {
      byte[] keyCopy = keyMaterial.clone();
      try {
        SecretKey key = new SecretKeySpec(keyCopy, "HmacSHA256");
        return new CredentialDigestKey(keyId, key, retainedUntil);
      } finally {
        wipe(keyCopy);
      }
    }

    private void destroy() {
      wipe(keyMaterial);
    }

    @Override
    public String toString() {
      return "AccountMountedGameplayCredentialDigestKeySource.KeyEntry[keyId="
          + keyId
          + ", material=redacted]";
    }
  }

  /** Retryable failure for absent, withdrawn, malformed, expired, or unreadable mounted keys. */
  public static final class KeyUnavailableException extends IllegalStateException {
    private KeyUnavailableException() {
      super("Account credential-request digest keyring is unavailable");
    }

    public String errorCode() {
      return "AUTH_UNAVAILABLE";
    }
  }
}
