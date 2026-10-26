package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CanonicalGameplayTenantGameplayTagRepositoryTest {
  @Test
  void acceptsOnlyBoundedOpaqueTagsWithoutRedisDelimitersOrHashTags() {
    assertThatCode(
            () ->
                CanonicalGameplayTenantGameplayTagRepository.requireCanonicalTag(
                    "g2147483647-0123456789abcdef0123456789abcdef"))
        .doesNotThrowAnyException();

    assertThatThrownBy(
            () ->
                CanonicalGameplayTenantGameplayTagRepository.requireCanonicalTag(
                    "g1-{0123456789abcdef0123456789abcdef"))
        .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class);
    assertThatThrownBy(
            () ->
                CanonicalGameplayTenantGameplayTagRepository.requireCanonicalTag(
                    "g1-0123456789abcdef0123456789abcdef:other"))
        .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class);
    assertThatThrownBy(
            () ->
                CanonicalGameplayTenantGameplayTagRepository.requireCanonicalTag(
                    "g0-0123456789abcdef0123456789abcdef"))
        .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class);
    assertThatThrownBy(
            () ->
                CanonicalGameplayTenantGameplayTagRepository.requireCanonicalTag(
                    "g1-0123456789ABCDEF0123456789abcdef"))
        .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class);
  }
}
