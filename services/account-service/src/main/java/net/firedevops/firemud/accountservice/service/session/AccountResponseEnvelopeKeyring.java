package net.firedevops.firemud.accountservice.service.session;

import java.io.ByteArrayOutputStream;
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
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Loads Account's separate response-envelope encryption ring from its mounted read-only directory.
 */
@Component
public final class AccountResponseEnvelopeKeyring {
  static final int KEY_BYTES = 32;
  static final int MAX_MANIFEST_BYTES = 16 * 1024;
  static final int MAX_RETIRING_KEYS = 8;
  private static final String MANIFEST_HEADER = "firemud-account-response-envelope-keyring-v1";
  private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");

  private final String mountPath;

  @Autowired
  public AccountResponseEnvelopeKeyring(
      @Value("${firemud.account.response-envelope.keyring-path:}") String mountPath) {
    this.mountPath = mountPath == null ? "" : mountPath;
  }

  /** Reloads on every operation so a withdrawn or unreadable mounted key is never cached. */
  Snapshot readCurrent() {
    if (mountPath.isBlank()) {
      throw new KeyUnavailableException();
    }
    try {
      Path configuredRoot = Path.of(mountPath);
      if (!configuredRoot.isAbsolute()) {
        throw new KeyUnavailableException();
      }
      Path mountRoot = configuredRoot.toRealPath();
      if (!Files.isDirectory(mountRoot, LinkOption.NOFOLLOW_LINKS)) {
        throw new KeyUnavailableException();
      }
      // Kubernetes projected Secrets use key -> ..data/key and ..data -> ..<version> symlinks.
      // Resolve that projection, but do not let its target leave this configured trust root.
      Path projectedKey = mountRoot.resolve("keyring").toRealPath();
      if (!projectedKey.startsWith(mountRoot)
          || !Files.isRegularFile(projectedKey, LinkOption.NOFOLLOW_LINKS)) {
        throw new KeyUnavailableException();
      }
      return parse(readBounded(projectedKey));
    } catch (KeyUnavailableException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new KeyUnavailableException();
    }
  }

  private static byte[] readBounded(Path path) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (SeekableByteChannel channel =
        Files.newByteChannel(path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
      ByteBuffer buffer = ByteBuffer.allocate(1024);
      int total = 0;
      while (true) {
        int count = channel.read(buffer);
        if (count < 0) {
          break;
        }
        if (count == 0) {
          continue;
        }
        total += count;
        if (total > MAX_MANIFEST_BYTES) {
          throw new KeyUnavailableException();
        }
        buffer.flip();
        output.write(buffer.array(), buffer.position(), buffer.remaining());
        buffer.clear();
      }
    }
    return output.toByteArray();
  }

  private static Snapshot parse(byte[] contents) {
    if (contents.length == 0 || contents.length > MAX_MANIFEST_BYTES) {
      throw new KeyUnavailableException();
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
      throw new KeyUnavailableException();
    }
    if (text.indexOf('\r') >= 0 || text.indexOf('\0') >= 0 || text.startsWith("\uFEFF")) {
      throw new KeyUnavailableException();
    }
    for (int index = 0; index < text.length(); index++) {
      char value = text.charAt(index);
      if (value > 0x7e || (value < 0x20 && value != '\n')) {
        throw new KeyUnavailableException();
      }
    }

    String normalized = text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
    if (normalized.isEmpty() || normalized.endsWith("\n")) {
      throw new KeyUnavailableException();
    }
    String[] lines = normalized.split("\n", -1);
    if (!MANIFEST_HEADER.equals(lines[0]) || lines.length < 2) {
      throw new KeyUnavailableException();
    }

    KeyEntry active = null;
    Map<String, KeyEntry> allKeys = new HashMap<>();
    Set<String> seenIds = new HashSet<>();
    int retiringCount = 0;
    for (int index = 1; index < lines.length; index++) {
      String line = lines[index];
      if (line.isEmpty()) {
        throw new KeyUnavailableException();
      }
      String[] fields = line.split(" ", -1);
      if (fields.length == 3 && "active".equals(fields[0])) {
        if (active != null) {
          throw new KeyUnavailableException();
        }
        if (index != 1) {
          throw new KeyUnavailableException();
        }
        KeyEntry entry = parseEntry(fields[1], fields[2], null);
        if (!seenIds.add(entry.keyId())) {
          throw new KeyUnavailableException();
        }
        active = entry;
        allKeys.put(entry.keyId(), entry);
      } else if (fields.length == 4 && "retiring".equals(fields[0])) {
        if (active == null) {
          throw new KeyUnavailableException();
        }
        retiringCount++;
        if (retiringCount > MAX_RETIRING_KEYS) {
          throw new KeyUnavailableException();
        }
        long decryptUntilMillis;
        try {
          if (!fields[2].matches("[1-9][0-9]{0,18}")) {
            throw new NumberFormatException();
          }
          decryptUntilMillis = Long.parseLong(fields[2]);
          if (decryptUntilMillis <= 0) {
            throw new NumberFormatException();
          }
        } catch (NumberFormatException ex) {
          throw new KeyUnavailableException();
        }
        Instant decryptUntil;
        try {
          decryptUntil = Instant.ofEpochMilli(decryptUntilMillis);
        } catch (RuntimeException ex) {
          throw new KeyUnavailableException();
        }
        KeyEntry entry = parseEntry(fields[1], fields[3], decryptUntil);
        if (!seenIds.add(entry.keyId())) {
          throw new KeyUnavailableException();
        }
        allKeys.put(entry.keyId(), entry);
      } else {
        throw new KeyUnavailableException();
      }
    }
    if (active == null) {
      throw new KeyUnavailableException();
    }
    return new Snapshot(active, allKeys);
  }

  private static KeyEntry parseEntry(String keyId, String encodedKey, Instant decryptUntil) {
    if (keyId == null || !KEY_ID.matcher(keyId).matches() || encodedKey == null) {
      throw new KeyUnavailableException();
    }
    byte[] keyMaterial;
    try {
      keyMaterial = Base64.getUrlDecoder().decode(encodedKey);
    } catch (IllegalArgumentException ex) {
      throw new KeyUnavailableException();
    }
    if (keyMaterial.length != KEY_BYTES
        || !Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(keyMaterial)
            .equals(encodedKey)) {
      java.util.Arrays.fill(keyMaterial, (byte) 0);
      throw new KeyUnavailableException();
    }
    KeyEntry entry = new KeyEntry(keyId, keyMaterial, decryptUntil);
    java.util.Arrays.fill(keyMaterial, (byte) 0);
    return entry;
  }

  static final class Snapshot {
    private final KeyEntry active;
    private final Map<String, KeyEntry> keys;

    private Snapshot(KeyEntry active, Map<String, KeyEntry> keys) {
      this.active = active;
      this.keys = Map.copyOf(keys);
    }

    String activeKeyId() {
      return active.keyId();
    }

    KeyEntry activeKey() {
      return active;
    }

    KeyEntry decryptionKey(String keyId, Instant now) {
      KeyEntry key = keys.get(keyId);
      if (key == null || (key.decryptUntil() != null && !now.isBefore(key.decryptUntil()))) {
        throw new KeyUnavailableException();
      }
      return key;
    }

    @Override
    public String toString() {
      return "AccountResponseEnvelopeKeyring.Snapshot[redacted]";
    }
  }

  static final class KeyEntry {
    private final String keyId;
    private final byte[] keyMaterial;
    private final Instant decryptUntil;

    private KeyEntry(String keyId, byte[] keyMaterial, Instant decryptUntil) {
      this.keyId = keyId;
      this.keyMaterial = keyMaterial.clone();
      this.decryptUntil = decryptUntil;
    }

    String keyId() {
      return keyId;
    }

    Instant decryptUntil() {
      return decryptUntil;
    }

    javax.crypto.SecretKey secretKey() {
      return new javax.crypto.spec.SecretKeySpec(keyMaterial.clone(), "AES");
    }

    @Override
    public String toString() {
      return "AccountResponseEnvelopeKeyring.KeyEntry[keyId=" + keyId + ", material=redacted]";
    }
  }

  /** Retryable failure for absent, withdrawn, malformed, or unreadable mounted key material. */
  public static final class KeyUnavailableException extends RuntimeException {
    private KeyUnavailableException() {
      super("Account response-envelope encryption keyring is unavailable");
    }

    public String errorCode() {
      return "AUTH_UNAVAILABLE";
    }
  }
}
