package net.firedevops.firemud.worldmanagement.service.impl;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.security.RequestIdValidation;

final class RuntimeRoomInstanceIds {
  private static final String CANONICAL_PREFIX = "R-";
  private static final Pattern RUNTIME_ROOM_INSTANCE_ROW_ID_PATTERN =
      Pattern.compile("^R-([1-9][0-9]*)$");

  private RuntimeRoomInstanceIds() {}

  static String canonical(long roomInstanceRowId) {
    return CANONICAL_PREFIX + roomInstanceRowId;
  }

  static long requireRowId(String runtimeRoomInstanceId) {
    Matcher matcher = RUNTIME_ROOM_INSTANCE_ROW_ID_PATTERN.matcher(runtimeRoomInstanceId);
    if (!matcher.matches()) {
      throw new IllegalArgumentException("roomInstanceId must be a runtime room id like R-1021");
    }
    return RequestIdValidation.requirePositiveLong(matcher.group(1), "roomInstanceId");
  }
}
