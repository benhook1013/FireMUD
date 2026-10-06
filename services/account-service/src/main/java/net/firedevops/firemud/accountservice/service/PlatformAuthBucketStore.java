package net.firedevops.firemud.accountservice.service;

import java.time.Duration;

/** Atomic operations on one opaque platform-auth cache bucket at a time. */
public interface PlatformAuthBucketStore {
  long increment(String key, String collisionFingerprint, Duration ttl, long maximumValue);

  long count(String key, String collisionFingerprint);
}
