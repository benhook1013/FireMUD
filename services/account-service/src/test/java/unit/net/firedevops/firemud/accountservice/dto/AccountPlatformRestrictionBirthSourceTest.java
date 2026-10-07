package unit.net.firedevops.firemud.accountservice.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountPlatformRestrictionBirthSource;
import net.firedevops.firemud.accountservice.dto.AccountPlatformRestrictionBirthSource.Category;
import net.firedevops.firemud.accountservice.dto.AccountPlatformRestrictionBirthSource.CategorySource;
import net.firedevops.firemud.accountservice.dto.AccountPlatformRestrictionBirthSource.RestrictionState;
import net.firedevops.firemud.accountservice.dto.AccountPlatformRestrictionBirthSource.SourceKind;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.IssuanceFence;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import org.junit.jupiter.api.Test;

class AccountPlatformRestrictionBirthSourceTest {
  private static final UUID ACCOUNT = UUID.fromString("10203040-5060-4070-8090-a0b0c0d0e0f0");
  private static final ScopeState BIRTH =
      new ScopeState(AuthorityScope.account(ACCOUNT), 1, 1, new IssuanceFence(ACCOUNT, 1, 1));

  @Test
  void explicitIndependentCategoryEvidenceHasStableExactDigests() {
    CategorySource lock = category(Category.ACCOUNT_SECURITY_LOCK);
    CategorySource ban = category(Category.PLATFORM_ACCESS_BAN);
    var source = source(lock, ban);
    assertThat(source.accountSecurityLock().sourceKind()).isEqualTo(SourceKind.CATEGORY);
    assertThat(source.platformAccessBan().restrictionState())
        .isEqualTo(RestrictionState.NONRESTRICTED);
    assertThat(source.platformAccessBan().payloadDigest())
        .isEqualTo(
            AccountPlatformRestrictionBirthSource.birthDigest(
                ACCOUNT,
                7,
                19,
                BIRTH,
                ban.category(),
                ban.operationId(),
                ban.resultId(),
                ban.eventId()));
    assertThat(lock.outboxStreamKey()).doesNotContain("auth-authority");
    assertThat(lock.payloadDigest()).isNotEqualTo(ban.payloadDigest());
  }

  @Test
  void absenceCategorySubstitutionAndChangedDigestDeny() {
    CategorySource lock = category(Category.ACCOUNT_SECURITY_LOCK);
    CategorySource ban = category(Category.PLATFORM_ACCESS_BAN);
    assertThatThrownBy(() -> source(null, ban)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> source(ban, lock)).isInstanceOf(IllegalArgumentException.class);
    CategorySource forged =
        new CategorySource(
            ban.category(),
            ban.sourceKind(),
            ban.restrictionState(),
            1,
            1,
            ban.operationId(),
            UUID.randomUUID(),
            ban.eventId(),
            ban.payloadDigest(),
            ban.outboxStreamKey(),
            1);
    assertThatThrownBy(() -> source(lock, forged))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("digest");
  }

  @Test
  void changedProvenanceOrBirthFenceCannotReuseOriginalDigest() {
    CategorySource lock = category(Category.ACCOUNT_SECURITY_LOCK);
    CategorySource ban = category(Category.PLATFORM_ACCESS_BAN);
    assertThatThrownBy(
            () ->
                new AccountPlatformRestrictionBirthSource(ACCOUNT, 8, 19, BIRTH, BIRTH, lock, ban))
        .isInstanceOf(IllegalArgumentException.class);
    ScopeState later =
        new ScopeState(AuthorityScope.account(ACCOUNT), 2, 2, new IssuanceFence(ACCOUNT, 2, 2));
    assertThatThrownBy(
            () ->
                new AccountPlatformRestrictionBirthSource(ACCOUNT, 7, 19, later, later, lock, ban))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static AccountPlatformRestrictionBirthSource source(
      CategorySource lock, CategorySource ban) {
    return new AccountPlatformRestrictionBirthSource(ACCOUNT, 7, 19, BIRTH, BIRTH, lock, ban);
  }

  private static CategorySource category(Category category) {
    UUID operation = UUID.randomUUID();
    UUID result = UUID.randomUUID();
    UUID event = UUID.randomUUID();
    String digest =
        AccountPlatformRestrictionBirthSource.birthDigest(
            ACCOUNT, 7, 19, BIRTH, category, operation, result, event);
    return new CategorySource(
        category,
        SourceKind.CATEGORY,
        RestrictionState.NONRESTRICTED,
        1,
        1,
        operation,
        result,
        event,
        digest,
        AccountPlatformRestrictionBirthSource.streamKey(ACCOUNT, category),
        1);
  }
}
