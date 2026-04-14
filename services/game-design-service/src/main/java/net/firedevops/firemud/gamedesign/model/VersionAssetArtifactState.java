package net.firedevops.firemud.gamedesign.model;

public enum VersionAssetArtifactState {
  STAGED,
  EXPORTED_UNATTESTED,
  PUBLISHED,
  FAILED,
  TOMBSTONED,
  PURGE_IN_PROGRESS,
  PURGE_FAILED,
  PURGED
}
