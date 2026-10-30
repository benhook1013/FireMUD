package net.firedevops.firemud.socialgroups.dto;

import static net.firedevops.firemud.socialgroups.test.AccountUuidFixtures.accountUuid;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import net.firedevops.firemud.socialgroups.enums.ChatType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class SocialAccountUuidRequestTest {
  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "42",
        "00000000-0000-0000-0000-000000000000",
        "1-1-1-1-1",
        "10000000-0000-4000-8000-00000000000A",
        " 10000000-0000-4000-8000-000000000001"
      })
  void everyAccountRequestRejectsNonCanonicalAccountIdentity(String invalid) {
    assertThrows(
        IllegalArgumentException.class, () -> new AddFriendRequest(7L, invalid, accountUuid(2L)));
    assertThrows(
        IllegalArgumentException.class, () -> new AddFriendRequest(7L, accountUuid(1L), invalid));
    assertThrows(
        IllegalArgumentException.class, () -> new CreateGuildRequest(7L, invalid, "guild"));
    assertThrows(
        IllegalArgumentException.class, () -> new AddGuildMemberRequest(7L, 9L, invalid, "member"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new UpdateGuildMemberRoleRequest(7L, 9L, invalid, "member"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SendMailRequest(7L, invalid, accountUuid(2L), "subject", "body"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SendMailRequest(7L, accountUuid(1L), invalid, "subject", "body"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SendMessageRequestDto(
                7L, invalid, ChatType.SAY, "", null, null, null, "body", null));
    assertThrows(
        IllegalArgumentException.class, () -> new VoiceTokenRequestDto(7L, invalid, "guild-9"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new UpdateFriendPresencePolicyRequest(
                7L, invalid, FriendPresenceVisibilityPolicyValue.PRIVATE));
  }

  @Test
  void optionalChatRecipientIsAbsentOrCanonicalWhileTenantGuildAndCityKeepTheirOwnIds() {
    var request =
        new SendMessageRequestDto(
            7L, accountUuid(1L), ChatType.SAY, "", null, 9L, 11L, "body", null);
    assertEquals(accountUuid(1L), request.senderAccountId());
    assertNull(request.recipientAccountId());
    assertEquals(7L, request.tenantId());
    assertEquals(9L, request.guildId());
    assertEquals(11L, request.cityId());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SendMessageRequestDto(
                7L, accountUuid(1L), ChatType.TELL, "", "2", null, null, "body", null));
  }
}
