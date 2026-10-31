package net.firedevops.firemud.common.security;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Kernel mount evidence for the protected Pod UID and Account JWKS projection roots. This evidence
 * does not establish kubelet provenance or replace platform admission of the projection.
 */
public final class ProtectedProjectionMount {
  private static final int MAX_MOUNTINFO_BYTES = 4 * 1024 * 1024;
  private static final int MAX_LINE_LENGTH = 32 * 1024;

  private ProtectedProjectionMount() {}

  /** Requires one dedicated read-only mount at the canonical root, with no nested mounts. */
  public static Snapshot read(Path canonicalRoot) throws IOException {
    if (!"Linux".equals(System.getProperty("os.name"))) {
      throw unavailable();
    }
    try (InputStream input = Files.newInputStream(Path.of("/proc/self/mountinfo"))) {
      return parse(canonicalRoot, input.readNBytes(MAX_MOUNTINFO_BYTES + 1));
    }
  }

  /** Package-private parser fixture seam; production reads always use the fixed kernel path. */
  static Snapshot parse(Path canonicalRoot, byte[] bytes) throws IOException {
    if (canonicalRoot == null
        || !canonicalRoot.isAbsolute()
        || !canonicalRoot.equals(canonicalRoot.normalize())
        || bytes.length == 0
        || bytes.length > MAX_MOUNTINFO_BYTES) {
      throw unavailable();
    }
    String table =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString();
    if (!table.endsWith("\n")) {
      throw unavailable();
    }
    Set<String> mountIds = new HashSet<>();
    Map<Path, MountEntry> mounts = new HashMap<>();
    MountEntry selected = null;
    for (String line : table.substring(0, table.length() - 1).split("\n", -1)) {
      if (line.isEmpty() || line.length() > MAX_LINE_LENGTH) {
        throw unavailable();
      }
      for (int index = 0; index < line.length(); index++) {
        if (Character.isISOControl(line.charAt(index))) {
          throw unavailable();
        }
      }
      String[] fields = line.split(" ", -1);
      if (fields.length < 10
          || !fields[0].matches("[1-9][0-9]*")
          || !fields[1].matches("[1-9][0-9]*")
          || !fields[2].matches("[0-9]+:[0-9]+")
          || !mountIds.add(fields[0])) {
        throw unavailable();
      }
      for (String field : fields) {
        if (field.isEmpty()) {
          throw unavailable();
        }
      }
      int separator = 6;
      while (separator < fields.length && !"-".equals(fields[separator])) {
        separator++;
      }
      if (separator != fields.length - 4) {
        throw unavailable();
      }
      parsePath(fields[3]);
      Path mountPoint = parsePath(fields[4]);
      Set<String> options = parseOptions(fields[5]);
      parseOptions(fields[separator + 3]);
      MountEntry entry = new MountEntry(fields[0], fields[1], mountPoint, line);
      if (mounts.putIfAbsent(mountPoint, entry) != null
          && (mountPoint.startsWith(canonicalRoot) || canonicalRoot.startsWith(mountPoint))) {
        throw unavailable();
      }
      if (mountPoint.equals(canonicalRoot)) {
        if (selected != null || !options.contains("ro") || options.contains("rw")) {
          throw unavailable();
        }
        selected = entry;
      } else if (mountPoint.startsWith(canonicalRoot)) {
        // Reject even read-only descendants: the entire projection must be one dedicated mount.
        throw unavailable();
      }
    }
    if (selected == null) {
      throw unavailable();
    }
    // An overmount of an ancestor can leave an obscured exact-root entry in mountinfo.
    // Require the selected root's parent chain to agree with the visible pathname ancestry.
    MountEntry current = selected;
    while (current.mountPoint().getParent() != null) {
      Path parentPath = current.mountPoint().getParent();
      while (parentPath != null && !mounts.containsKey(parentPath)) {
        parentPath = parentPath.getParent();
      }
      MountEntry parent = mounts.get(parentPath);
      if (parent == null || !current.parentId().equals(parent.id())) {
        throw unavailable();
      }
      current = parent;
    }
    return new Snapshot(selected.record());
  }

  private static Set<String> parseOptions(String field) throws IOException {
    Set<String> options = new HashSet<>();
    for (String option : field.split(",", -1)) {
      if (option.isEmpty() || !options.add(option)) {
        throw unavailable();
      }
    }
    if (options.contains("ro") == options.contains("rw")) {
      throw unavailable();
    }
    return options;
  }

  private static Path parsePath(String field) throws IOException {
    StringBuilder decoded = new StringBuilder();
    for (int index = 0; index < field.length(); index++) {
      char character = field.charAt(index);
      if (character != '\\') {
        decoded.append(character);
        continue;
      }
      if (index + 3 >= field.length()) {
        throw unavailable();
      }
      String escape = field.substring(index + 1, index + 4);
      decoded.append(
          switch (escape) {
            case "040" -> ' ';
            case "011" -> '\t';
            case "012" -> '\n';
            case "134" -> '\\';
            default -> throw unavailable();
          });
      index += 3;
    }
    Path path = Path.of(decoded.toString());
    if (!path.isAbsolute() || !path.equals(path.normalize())) {
      throw unavailable();
    }
    return path;
  }

  private record MountEntry(String id, String parentId, Path mountPoint, String record) {}

  private static IOException unavailable() {
    return new IOException("Protected projection mount evidence is unavailable");
  }

  /** Immutable selected mount identity and options, for comparison across a protected read. */
  public static final class Snapshot {
    private final String mountRecord;

    private Snapshot(String mountRecord) {
      this.mountRecord = mountRecord;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Snapshot snapshot && mountRecord.equals(snapshot.mountRecord);
    }

    @Override
    public int hashCode() {
      return Objects.hash(mountRecord);
    }
  }
}
