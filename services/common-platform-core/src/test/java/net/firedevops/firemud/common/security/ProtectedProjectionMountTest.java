package net.firedevops.firemud.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "DMI_HARDCODED_ABSOLUTE_FILENAME",
    justification =
        "Synthetic mountinfo fixtures intentionally use absolute mountpoint paths and never access those paths.")
class ProtectedProjectionMountTest {
  private static final Path ROOT = Path.of("/projection");
  private static final String BASE_MOUNT = "1 2 0:1 / / rw - overlay overlay rw\n";
  private static final String ROOT_MOUNT = "10 1 0:9 / /projection ro,relatime - tmpfs tmpfs rw\n";

  @Test
  void acceptsDedicatedPerMountReadOnlyEvenWhenSuperblockIsWritable() throws IOException {
    assertThat(parse(ROOT_MOUNT)).isEqualTo(parse(ROOT_MOUNT));
  }

  @Test
  void rejectsWritableRootEvenWhenAncestorOrSuperblockIsReadOnly() {
    assertUnavailable(
        "1 2 0:9 / / ro - tmpfs tmpfs ro\n" + "10 1 0:9 / /projection rw - tmpfs tmpfs ro\n");
  }

  @Test
  void rejectsAncestorOnlyAndSiblingPrefixEvidence() {
    assertUnavailable("1 2 0:9 / / ro - tmpfs tmpfs ro\n");
    assertUnavailable(ROOT_MOUNT.replace("/projection", "/projection-other"));
  }

  @Test
  void rejectsEveryNestedMountIncludingSameDeviceWritableBind() {
    assertUnavailable(ROOT_MOUNT + "11 10 0:9 /other /projection/..data rw - tmpfs tmpfs rw\n");
    assertUnavailable(ROOT_MOUNT + "11 10 0:9 /other /projection/child ro - tmpfs tmpfs rw\n");
  }

  @Test
  void rejectsStackedRootOrDuplicateMountIdentity() {
    assertUnavailable(ROOT_MOUNT + ROOT_MOUNT.replace("10 1", "11 10"));
    assertUnavailable(ROOT_MOUNT + ROOT_MOUNT.replace("/projection", "/sibling"));
  }

  @Test
  void acceptsUnrelatedStackedMountsOutsideTheProjectionAncestry() throws IOException {
    assertThat(
            parse(
                ROOT_MOUNT
                    + "20 1 0:9 / /unrelated rw - tmpfs tmpfs rw\n"
                    + "21 20 0:9 /other /unrelated rw - tmpfs tmpfs rw\n"))
        .isNotNull();
  }

  @Test
  void rejectsStackedAncestorMounts() {
    assertUnavailable("2 1 0:1 / / ro - overlay overlay rw\n" + ROOT_MOUNT);
  }

  @Test
  void rejectsExactRootHiddenByAnAncestorOvermount() {
    String table =
        BASE_MOUNT
            + "10 1 0:9 / /parent/projection ro - tmpfs tmpfs rw\n"
            + "20 1 0:9 /other /parent rw - tmpfs tmpfs rw\n";
    assertThatThrownBy(
            () ->
                ProtectedProjectionMount.parse(
                    Path.of("/parent/projection"), table.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IOException.class);
  }

  @Test
  void acceptsExactRootWithConsistentNestedAncestorMount() throws IOException {
    String table =
        BASE_MOUNT
            + "10 20 0:9 / /parent/projection ro - tmpfs tmpfs rw\n"
            + "20 1 0:9 /other /parent rw - tmpfs tmpfs rw\n";
    assertThat(
            ProtectedProjectionMount.parse(
                Path.of("/parent/projection"), table.getBytes(StandardCharsets.UTF_8)))
        .isNotNull();
  }

  @Test
  void retainedEvidenceDetectsChangedMountIdentityOrOptions() throws IOException {
    assertThat(parse(ROOT_MOUNT)).isNotEqualTo(parse(ROOT_MOUNT.replace("10 1", "11 1")));
    assertThat(parse(ROOT_MOUNT)).isNotEqualTo(parse(ROOT_MOUNT.replace("relatime", "noatime")));
  }

  @Test
  void decodesOnlyTheFourKernelPathEscapes() throws IOException {
    String escaped = "/projection\\040space\\011tab\\012newline\\134slash";
    Path decoded = Path.of("/projection space\ttab\nnewline\\slash");
    byte[] bytes =
        (BASE_MOUNT + ROOT_MOUNT.replace("/projection", escaped)).getBytes(StandardCharsets.UTF_8);

    assertThat(ProtectedProjectionMount.parse(decoded, bytes)).isNotNull();
    assertUnavailable(ROOT_MOUNT.replace("/projection", "/projection\\041"));
    assertUnavailable(ROOT_MOUNT.replace("/projection", "/projection\\"));
  }

  @Test
  void acceptsUnknownOptionalKernelFieldsWithoutReinterpretingThem() throws IOException {
    assertThat(parse(ROOT_MOUNT.replace(" - ", " shared:3 future:4 - "))).isNotNull();
  }

  @Test
  void rejectsMalformedOrTruncatedMountTables() {
    for (String malformed :
        new String[] {
          "",
          ROOT_MOUNT.stripTrailing(),
          "\n",
          ROOT_MOUNT + "\n",
          ROOT_MOUNT.replace("10 1", "bad 1"),
          ROOT_MOUNT.replace("0:9", "0"),
          ROOT_MOUNT.replace("ro,relatime", "ro,rw"),
          ROOT_MOUNT.replace("ro,relatime", "ro,ro"),
          ROOT_MOUNT.replace("ro,relatime", "relatime"),
          ROOT_MOUNT.replace(" - ", " "),
          ROOT_MOUNT.replace(" - ", "  - "),
          ROOT_MOUNT.replace("/projection", "/other/../projection"),
          ROOT_MOUNT.replace("/projection", "relative"),
          ROOT_MOUNT.replace("tmpfs tmpfs", "tmpfs\ttmpfs"),
          ROOT_MOUNT + "malformed\n"
        }) {
      assertUnavailable(malformed);
    }
  }

  @Test
  void rejectsInvalidUtf8AndOversizedInputOrLine() {
    assertThatThrownBy(() -> ProtectedProjectionMount.parse(ROOT, new byte[] {(byte) 0xff, 10}))
        .isInstanceOf(IOException.class);
    assertThatThrownBy(() -> ProtectedProjectionMount.parse(ROOT, new byte[4 * 1024 * 1024 + 1]))
        .isInstanceOf(IOException.class);
    assertUnavailable(ROOT_MOUNT.replace("tmpfs tmpfs", "tmpfs " + "a".repeat(32 * 1024)));
  }

  private static ProtectedProjectionMount.Snapshot parse(String table) throws IOException {
    return ProtectedProjectionMount.parse(
        ROOT, (BASE_MOUNT + table).getBytes(StandardCharsets.UTF_8));
  }

  private static void assertUnavailable(String table) {
    assertThatThrownBy(() -> parse(table)).isInstanceOf(IOException.class);
  }
}
