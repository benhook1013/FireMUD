package net.firedevops.firemud.worldmanagement.service.impl;

import net.firedevops.firemud.common.security.RequestIdValidation;

final class RuntimeRoomInstanceIds {
  private static final String CANONICAL_PREFIX = "R-";

  private RuntimeRoomInstanceIds() {}

  static String canonical(long roomInstanceRowId) {
    return CANONICAL_PREFIX + roomInstanceRowId;
  }

  static long requireRowId(String runtimeRoomInstanceId) {
    return RequestIdValidation.requireCanonicalRuntimeRoomRowId(
        runtimeRoomInstanceId, "roomInstanceId");
  }
}
