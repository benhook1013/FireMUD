package unit.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import de.mkammerer.argon2.Argon2Factory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountPlatformRestrictionBirthSource.Category;
import net.firedevops.firemud.accountservice.dto.AccountPlatformRestrictionMutationRequest;
import net.firedevops.firemud.accountservice.dto.AccountPlatformRestrictionMutationRequest.RestrictionState;
import net.firedevops.firemud.accountservice.dto.AccountPlatformRestrictionMutationRequest.SourceKind;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountFreshEnrollmentPolicySource;
import net.firedevops.firemud.accountservice.repository.AccountFreshEnrollmentRequest;
import net.firedevops.firemud.accountservice.repository.AccountPlatformRestrictionBirthRepository;
import net.firedevops.firemud.accountservice.repository.AccountPlatformRestrictionOperationRepository;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccountFreshEnrollmentRequestTest {
  @TempDir Path temporary;

  @Test
  void stableRequestNormalizesLoginModesAndEmailButBindsEveryInsertionField() {
    UUID id = UUID.randomUUID();
    var original = request(id, "player", "Person@Example.TEST", "hash", "PASSWORD,EMAIL_OTP");
    assertThat(original.requestDigest())
        .isEqualTo(
            request(id, "player", "person@example.test", "hash", "EMAIL_OTP,PASSWORD")
                .requestDigest());
    assertThat(original.requestDigest())
        .isNotEqualTo(
            request(id, "other", "person@example.test", "hash", "PASSWORD,EMAIL_OTP")
                .requestDigest());
    assertThat(original.requestDigest())
        .isNotEqualTo(
            request(id, "player", "person@example.test", "different", "PASSWORD,EMAIL_OTP")
                .requestDigest());
    assertThat(original.requestDigest())
        .isNotIn(
            request(
                    UUID.randomUUID(),
                    "player",
                    "person@example.test",
                    "hash",
                    "PASSWORD,EMAIL_OTP")
                .requestDigest(),
            request(id, "player", "other@example.test", "hash", "PASSWORD,EMAIL_OTP")
                .requestDigest(),
            new AccountFreshEnrollmentRequest(
                    id, "player", "person@example.test", "hash", true, "PASSWORD,EMAIL_OTP")
                .requestDigest(),
            request(id, "player", "person@example.test", "hash", "PASSWORD").requestDigest());
    assertThat(original.toString()).doesNotContain("hash", "person", "Person");
  }

  @Test
  void lostResponseRetryMustReuseTheOriginalSaltedVerifier() {
    UUID enrollmentId = UUID.randomUUID();
    String firstVerifier = hash("same plaintext password");
    String saltedRehash = hash("same plaintext password");
    var original =
        request(enrollmentId, "player", "person@example.test", firstVerifier, "PASSWORD");
    var exactRetry =
        request(enrollmentId, "player", "person@example.test", firstVerifier, "PASSWORD");
    var rehashedRequest =
        request(enrollmentId, "player", "person@example.test", saltedRehash, "PASSWORD");

    assertThat(firstVerifier).isNotEqualTo(saltedRehash);
    assertThat(exactRetry.requestDigest()).isEqualTo(original.requestDigest());
    assertThat(rehashedRequest.requestDigest()).isNotEqualTo(original.requestDigest());
  }

  @Test
  void canonicalInsertionExplicitlyHasNoLegacyRole() throws ReflectiveOperationException {
    var request = request(UUID.randomUUID(), "player", "person@example.test", "hash", "PASSWORD");
    var newAccount = AccountFreshEnrollmentRequest.class.getDeclaredMethod("newAccount");
    newAccount.setAccessible(true);
    Account account = (Account) newAccount.invoke(request);
    assertThat(account.getRole()).isNull();
  }

  @Test
  void freshSourceCanNeverDeclareARestrictionOrAuthorizeOrdinaryCommand() {
    DSLContext dsl = mock(DSLContext.class);
    var repository =
        new AccountPlatformRestrictionOperationRepository(
            dsl,
            mock(AccountAuthorityGenerationRepository.class),
            mock(AccountAuthorityOutboxRepository.class),
            mock(AccountAuthoritySourceEvidenceRepository.class),
            mock(AccountPlatformRestrictionBirthRepository.class));
    for (Category category : Category.values()) {
      var request = mutation(category, RestrictionState.NONRESTRICTED);
      assertThatThrownBy(() -> repository.commit(request))
          .isInstanceOf(
              AccountPlatformRestrictionOperationRepository.OwnerAuthorizationUnresolvedException
                  .class);
      assertThatThrownBy(() -> mutation(category, RestrictionState.RESTRICTED))
          .isInstanceOf(IllegalArgumentException.class);
    }
    verifyNoInteractions(dsl);
  }

  @Test
  void nonRootOwnedMountAndRequestControlledMountAreRejected() throws Exception {
    Assumptions.assumeTrue(
        ((Number) Files.getAttribute(temporary, "unix:uid")).longValue() != 0,
        "This unit covers non-root-owned mount and relative-path guards; "
            + "root-owned protected-pin proof uses the controlled fixture");
    var source = new AccountFreshEnrollmentPolicySource(temporary.toAbsolutePath());
    assertThatThrownBy(() -> source.read(Category.ACCOUNT_SECURITY_LOCK))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new AccountFreshEnrollmentPolicySource(Path.of("request-controlled")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static AccountFreshEnrollmentRequest request(
      UUID id, String user, String email, String hash, String modes) {
    return new AccountFreshEnrollmentRequest(id, user, email, hash, false, modes);
  }

  private static AccountPlatformRestrictionMutationRequest mutation(
      Category category, RestrictionState state) {
    return new AccountPlatformRestrictionMutationRequest(
        UUID.randomUUID(),
        UUID.randomUUID(),
        category,
        1,
        1,
        1,
        1,
        UUID.randomUUID(),
        state,
        SourceKind.ACCOUNT_FRESH_ENROLLMENT,
        UUID.randomUUID(),
        "sha256:" + "0".repeat(64));
  }

  private static String hash(String password) {
    char[] secret = password.toCharArray();
    var argon = Argon2Factory.create();
    try {
      return argon.hash(2, 4096, 1, secret);
    } finally {
      argon.wipeArray(secret);
    }
  }
}
