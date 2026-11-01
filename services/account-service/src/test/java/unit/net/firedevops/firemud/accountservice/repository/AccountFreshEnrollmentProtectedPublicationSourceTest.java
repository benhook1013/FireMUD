package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountPlatformRestrictionBirthSource.Category;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** Controlled root-owned artifact fixtures; these are not production owner policy delivery. */
class AccountFreshEnrollmentProtectedPublicationSourceTest {
  @TempDir Path mount;

  @Test
  void actualProtectedPinnedBytesProduceTheExactCategoryPublicationAndRejectChangedPins()
      throws Exception {
    requireControlledRootOwnedFixture();
    byte[] bytes = artifact(Category.ACCOUNT_SECURITY_LOCK);
    String digest = installControlledArtifact(Category.ACCOUNT_SECURITY_LOCK, bytes);
    var source = new AccountFreshEnrollmentPolicySource(mount.toAbsolutePath());
    var publication = source.read(Category.ACCOUNT_SECURITY_LOCK);
    assertThat(publication.bytes()).isEqualTo(bytes);
    assertThat(publication.digest).isEqualTo(digest);
    assertThat(publication.sourceIdentity)
        .isEqualTo(
            AccountFreshEnrollmentPolicySource.sourceIdentity(Category.ACCOUNT_SECURITY_LOCK));
    publication.requireCurrentProtectedSource();
    installControlledArtifact(
        Category.ACCOUNT_SECURITY_LOCK, artifact(Category.ACCOUNT_SECURITY_LOCK));
    assertThatThrownBy(publication::requireCurrentProtectedSource)
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void exactDigestCannotAuthorizeAnotherCategoryMissingClosureOrWritableArtifact()
      throws Exception {
    requireControlledRootOwnedFixture();
    var source = new AccountFreshEnrollmentPolicySource(mount.toAbsolutePath());
    installControlledArtifact(
        Category.ACCOUNT_SECURITY_LOCK, artifact(Category.PLATFORM_ACCESS_BAN));
    assertThatThrownBy(() -> source.read(Category.ACCOUNT_SECURITY_LOCK))
        .isInstanceOf(IllegalStateException.class);
    installControlledArtifact(
        Category.ACCOUNT_SECURITY_LOCK, "{}".getBytes(StandardCharsets.UTF_8));
    assertThatThrownBy(() -> source.read(Category.ACCOUNT_SECURITY_LOCK))
        .isInstanceOf(IllegalStateException.class);
    String digest =
        installControlledArtifact(
            Category.ACCOUNT_SECURITY_LOCK, artifact(Category.ACCOUNT_SECURITY_LOCK));
    Files.setPosixFilePermissions(
        mount.resolve(digest.substring(7) + ".json"), PosixFilePermissions.fromString("rw-rw-r--"));
    assertThatThrownBy(() -> source.read(Category.ACCOUNT_SECURITY_LOCK))
        .isInstanceOf(IllegalStateException.class);
  }

  private void requireControlledRootOwnedFixture() throws Exception {
    assumeTrue(
        ((Number) Files.getAttribute(mount, "unix:uid")).longValue() == 0,
        "Positive protected-mount qualification requires an explicitly root-owned controlled fixture; non-root unit runs do not prove custody");
    Files.setPosixFilePermissions(mount, PosixFilePermissions.fromString("rwxr-xr-x"));
  }

  private String installControlledArtifact(Category pinCategory, byte[] bytes) throws Exception {
    String digest = AccountFreshEnrollmentPolicySource.digest(bytes);
    Path artifact = mount.resolve(digest.substring(7) + ".json");
    Files.write(artifact, bytes);
    Files.setPosixFilePermissions(artifact, PosixFilePermissions.fromString("rw-r--r--"));
    Path pin = mount.resolve(pinCategory.storageValue() + ".pin");
    Files.writeString(pin, digest + "\n", StandardCharsets.US_ASCII);
    Files.setPosixFilePermissions(pin, PosixFilePermissions.fromString("rw-r--r--"));
    return digest;
  }

  private static byte[] artifact(Category category) throws Exception {
    return Rfc8785CanonicalJson.canonicalizeUtf8(
        JsonMapper.builder()
            .build()
            .writeValueAsString(
                Map.of(
                    "schemaVersion",
                    "account-fresh-enrollment-policy/v1",
                    "category",
                    category.storageValue(),
                    "sourceIdentity",
                    AccountFreshEnrollmentPolicySource.sourceIdentity(category),
                    "publicationId",
                    UUID.randomUUID().toString(),
                    "revision",
                    "1",
                    "policyId",
                    UUID.randomUUID().toString(),
                    "policyRule",
                    "CERTIFY_NEW_ACTIVE_ACCOUNT",
                    "closureId",
                    UUID.randomUUID().toString(),
                    "closureRule",
                    "EXACT_FRESH_INSERT_ONLY")));
  }
}
