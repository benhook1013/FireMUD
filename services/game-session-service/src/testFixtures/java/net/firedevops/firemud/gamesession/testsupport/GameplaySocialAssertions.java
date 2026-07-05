package net.firedevops.firemud.gamesession.testsupport;

import java.util.Optional;
import net.firedevops.firemud.socialgroups.v1.AddFriendRequest;
import net.firedevops.firemud.socialgroups.v1.FriendPresenceVisibilityPolicy;
import net.firedevops.firemud.socialgroups.v1.FriendRosterFilter;
import net.firedevops.firemud.socialgroups.v1.GetFriendByOrdinalRequest;
import net.firedevops.firemud.socialgroups.v1.GetFriendPresencePolicyRequest;
import net.firedevops.firemud.socialgroups.v1.GetFriendRosterSummaryRequest;
import net.firedevops.firemud.socialgroups.v1.ListFriendsRequest;
import net.firedevops.firemud.socialgroups.v1.RemoveFriendByOrdinalRequest;
import net.firedevops.firemud.socialgroups.v1.RemoveFriendRequest;
import net.firedevops.firemud.socialgroups.v1.UpdateFriendPresencePolicyRequest;

/** Shared social request-shape assertions for chained gameplay cross-service proofs. */
public final class GameplaySocialAssertions {

  private GameplaySocialAssertions() {}

  public static void assertListFriendsRequest(
      Optional<ListFriendsRequest> maybeRequest, String tenantId, String accountId) {
    ListFriendsRequest request = required(maybeRequest, "friends list request");
    requireEquals(request.getTenantId(), tenantId, "friends request tenantId");
    requireEquals(request.getAccountId(), accountId, "friends request accountId");
  }

  public static void assertListFriendsRequest(
      Optional<ListFriendsRequest> maybeRequest,
      String tenantId,
      String accountId,
      FriendRosterFilter filter) {
    ListFriendsRequest request = required(maybeRequest, "friends list request");
    requireEquals(request.getTenantId(), tenantId, "friends request tenantId");
    requireEquals(request.getAccountId(), accountId, "friends request accountId");
    requireEquals(request.getFilter(), filter, "friends request filter");
  }

  public static void assertAddFriendRequest(
      Optional<AddFriendRequest> maybeRequest,
      String tenantId,
      String accountId,
      String friendAccountId) {
    AddFriendRequest request = required(maybeRequest, "add friend request");
    assertSocialActorContext(request.getTenantId(), request.getAccountId(), tenantId, accountId);
    requireEquals(request.getFriendAccountId(), friendAccountId, "add friend accountId");
  }

  public static void assertRemoveFriendRequest(
      Optional<RemoveFriendRequest> maybeRequest,
      String tenantId,
      String accountId,
      String friendAccountId) {
    RemoveFriendRequest request = required(maybeRequest, "remove friend request");
    assertSocialActorContext(request.getTenantId(), request.getAccountId(), tenantId, accountId);
    requireEquals(request.getFriendAccountId(), friendAccountId, "remove friend accountId");
  }

  public static void assertRemoveFriendByOrdinalRequest(
      Optional<RemoveFriendByOrdinalRequest> maybeRequest,
      String tenantId,
      String accountId,
      int ordinal) {
    RemoveFriendByOrdinalRequest request =
        required(maybeRequest, "remove friend by ordinal request");
    assertSocialActorContext(request.getTenantId(), request.getAccountId(), tenantId, accountId);
    requireEquals(request.getOrdinal(), ordinal, "remove friend by ordinal request ordinal");
  }

  public static void assertGetFriendByOrdinalRequest(
      Optional<GetFriendByOrdinalRequest> maybeRequest,
      String tenantId,
      String accountId,
      int ordinal) {
    GetFriendByOrdinalRequest request = required(maybeRequest, "get friend by ordinal request");
    assertSocialActorContext(request.getTenantId(), request.getAccountId(), tenantId, accountId);
    requireEquals(request.getOrdinal(), ordinal, "get friend by ordinal request ordinal");
  }

  public static void assertFriendRosterSummaryRequest(
      Optional<GetFriendRosterSummaryRequest> maybeRequest, String tenantId, String accountId) {
    GetFriendRosterSummaryRequest request = required(maybeRequest, "friend roster summary request");
    assertSocialActorContext(request.getTenantId(), request.getAccountId(), tenantId, accountId);
  }

  public static void assertGetVisibilityRequest(
      Optional<GetFriendPresencePolicyRequest> maybeRequest, String tenantId, String accountId) {
    GetFriendPresencePolicyRequest request = required(maybeRequest, "get visibility request");
    assertSocialActorContext(request.getTenantId(), request.getAccountId(), tenantId, accountId);
  }

  public static void assertUpdateVisibilityRequest(
      Optional<UpdateFriendPresencePolicyRequest> maybeRequest,
      String tenantId,
      String accountId,
      FriendPresenceVisibilityPolicy visibilityPolicy) {
    UpdateFriendPresencePolicyRequest request = required(maybeRequest, "update visibility request");
    assertSocialActorContext(request.getTenantId(), request.getAccountId(), tenantId, accountId);
    requireEquals(request.getVisibilityPolicy(), visibilityPolicy, "visibility policy");
  }

  private static void assertSocialActorContext(
      String requestTenantId,
      String requestAccountId,
      String expectedTenantId,
      String expectedAccountId) {
    requireEquals(requestTenantId, expectedTenantId, "social request tenantId");
    requireEquals(requestAccountId, expectedAccountId, "social request accountId");
  }

  private static <T> T required(Optional<T> maybeValue, String description) {
    return maybeValue.orElseThrow(() -> new AssertionError("Expected " + description));
  }

  private static void requireEquals(String actual, String expected, String description) {
    if (!actual.equals(expected)) {
      throw new AssertionError(
          "Expected " + description + " to be '" + expected + "' but was '" + actual + "'");
    }
  }

  private static void requireEquals(int actual, int expected, String description) {
    if (actual != expected) {
      throw new AssertionError(
          "Expected " + description + " to be " + expected + " but was " + actual);
    }
  }

  private static void requireEquals(
      FriendRosterFilter actual, FriendRosterFilter expected, String description) {
    if (actual != expected) {
      throw new AssertionError(
          "Expected " + description + " to be " + expected + " but was " + actual);
    }
  }

  private static void requireEquals(
      FriendPresenceVisibilityPolicy actual,
      FriendPresenceVisibilityPolicy expected,
      String description) {
    if (actual != expected) {
      throw new AssertionError(
          "Expected " + description + " to be " + expected + " but was " + actual);
    }
  }
}
