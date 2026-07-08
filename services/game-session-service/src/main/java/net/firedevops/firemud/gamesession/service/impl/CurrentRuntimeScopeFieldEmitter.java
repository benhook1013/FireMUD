package net.firedevops.firemud.gamesession.service.impl;

import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.v1.RemoteCommandCoordinatorEntry;
import net.firedevops.firemud.gamesession.v1.RemoteFollowupEntry;
import net.firedevops.firemud.gamesession.v1.RemoteFollowupResultEntry;

final class CurrentRuntimeScopeFieldEmitter {

  interface CurrentRuntimeScopeWriter {
    void setCurrentRuntimeGameInstanceId(boolean originScope, String gameInstanceId);

    void setCurrentRuntimeRegionId(boolean originScope, String regionId);

    void setCurrentRuntimeRegionEpoch(boolean originScope, long regionEpoch);

    void setCurrentRuntimePlayableStateScope(
        boolean originScope, PlayableStateScope playableStateScope);

    void setCurrentRuntimeWorldSlug(boolean originScope, String worldSlug);

    void setCurrentRuntimeRealmSlug(boolean originScope, String realmSlug);

    void setCurrentRuntimePointerVersion(boolean originScope, long pointerVersion);
  }

  static void applyCurrentRuntimeScopeFields(
      long gameInstanceId,
      String regionId,
      long regionEpoch,
      PlayableStateScope playableStateScope,
      String worldSlug,
      String realmSlug,
      Long pointerVersion,
      boolean originScope,
      CurrentRuntimeScopeWriter writer) {
    if (gameInstanceId > 0) {
      writer.setCurrentRuntimeGameInstanceId(originScope, Long.toString(gameInstanceId));
    }
    if (regionId != null && !regionId.isBlank()) {
      writer.setCurrentRuntimeRegionId(originScope, regionId);
    }
    writer.setCurrentRuntimeRegionEpoch(originScope, regionEpoch);
    if (playableStateScope != null) {
      writer.setCurrentRuntimePlayableStateScope(originScope, playableStateScope);
    }
    if (worldSlug != null && !worldSlug.isBlank()) {
      writer.setCurrentRuntimeWorldSlug(originScope, worldSlug);
    }
    if (realmSlug != null && !realmSlug.isBlank()) {
      writer.setCurrentRuntimeRealmSlug(originScope, realmSlug);
    }
    if (pointerVersion != null && pointerVersion > 0) {
      writer.setCurrentRuntimePointerVersion(originScope, pointerVersion);
    }
  }

  static CurrentRuntimeScopeWriter writerFor(RemoteCommandCoordinatorEntry.Builder builder) {
    return new CurrentRuntimeScopeWriter() {
      @Override
      public void setCurrentRuntimeGameInstanceId(boolean originScope, String gameInstanceId) {
        if (originScope) {
          builder.setCurrentOriginRuntimeGameInstanceId(gameInstanceId);
        } else {
          builder.setCurrentTargetRuntimeGameInstanceId(gameInstanceId);
        }
      }

      @Override
      public void setCurrentRuntimeRegionId(boolean originScope, String regionId) {
        if (originScope) {
          builder.setCurrentOriginRuntimeRegionId(regionId);
        } else {
          builder.setCurrentTargetRuntimeRegionId(regionId);
        }
      }

      @Override
      public void setCurrentRuntimeRegionEpoch(boolean originScope, long regionEpoch) {
        if (originScope) {
          builder.setCurrentOriginRuntimeRegionEpoch(regionEpoch);
        } else {
          builder.setCurrentTargetRuntimeRegionEpoch(regionEpoch);
        }
      }

      @Override
      public void setCurrentRuntimePlayableStateScope(
          boolean originScope, PlayableStateScope playableStateScope) {
        if (originScope) {
          builder.setCurrentOriginRuntimePlayableStateScope(playableStateScope);
        } else {
          builder.setCurrentTargetRuntimePlayableStateScope(playableStateScope);
        }
      }

      @Override
      public void setCurrentRuntimeWorldSlug(boolean originScope, String worldSlug) {
        if (originScope) {
          builder.setCurrentOriginRuntimeWorldSlug(worldSlug);
        } else {
          builder.setCurrentTargetRuntimeWorldSlug(worldSlug);
        }
      }

      @Override
      public void setCurrentRuntimeRealmSlug(boolean originScope, String realmSlug) {
        if (originScope) {
          builder.setCurrentOriginRuntimeRealmSlug(realmSlug);
        } else {
          builder.setCurrentTargetRuntimeRealmSlug(realmSlug);
        }
      }

      @Override
      public void setCurrentRuntimePointerVersion(boolean originScope, long pointerVersion) {
        if (originScope) {
          builder.setCurrentOriginRuntimePointerVersion(pointerVersion);
        } else {
          builder.setCurrentTargetRuntimePointerVersion(pointerVersion);
        }
      }
    };
  }

  static CurrentRuntimeScopeWriter writerFor(RemoteFollowupEntry.Builder builder) {
    return new CurrentRuntimeScopeWriter() {
      @Override
      public void setCurrentRuntimeGameInstanceId(boolean originScope, String gameInstanceId) {
        if (originScope) {
          builder.setCurrentOriginRuntimeGameInstanceId(gameInstanceId);
        } else {
          builder.setCurrentTargetRuntimeGameInstanceId(gameInstanceId);
        }
      }

      @Override
      public void setCurrentRuntimeRegionId(boolean originScope, String regionId) {
        if (originScope) {
          builder.setCurrentOriginRuntimeRegionId(regionId);
        } else {
          builder.setCurrentTargetRuntimeRegionId(regionId);
        }
      }

      @Override
      public void setCurrentRuntimeRegionEpoch(boolean originScope, long regionEpoch) {
        if (originScope) {
          builder.setCurrentOriginRuntimeRegionEpoch(regionEpoch);
        } else {
          builder.setCurrentTargetRuntimeRegionEpoch(regionEpoch);
        }
      }

      @Override
      public void setCurrentRuntimePlayableStateScope(
          boolean originScope, PlayableStateScope playableStateScope) {
        if (originScope) {
          builder.setCurrentOriginRuntimePlayableStateScope(playableStateScope);
        } else {
          builder.setCurrentTargetRuntimePlayableStateScope(playableStateScope);
        }
      }

      @Override
      public void setCurrentRuntimeWorldSlug(boolean originScope, String worldSlug) {
        if (originScope) {
          builder.setCurrentOriginRuntimeWorldSlug(worldSlug);
        } else {
          builder.setCurrentTargetRuntimeWorldSlug(worldSlug);
        }
      }

      @Override
      public void setCurrentRuntimeRealmSlug(boolean originScope, String realmSlug) {
        if (originScope) {
          builder.setCurrentOriginRuntimeRealmSlug(realmSlug);
        } else {
          builder.setCurrentTargetRuntimeRealmSlug(realmSlug);
        }
      }

      @Override
      public void setCurrentRuntimePointerVersion(boolean originScope, long pointerVersion) {
        if (originScope) {
          builder.setCurrentOriginRuntimePointerVersion(pointerVersion);
        } else {
          builder.setCurrentTargetRuntimePointerVersion(pointerVersion);
        }
      }
    };
  }

  static CurrentRuntimeScopeWriter writerFor(RemoteFollowupResultEntry.Builder builder) {
    return new CurrentRuntimeScopeWriter() {
      @Override
      public void setCurrentRuntimeGameInstanceId(boolean originScope, String gameInstanceId) {
        if (originScope) {
          builder.setCurrentOriginRuntimeGameInstanceId(gameInstanceId);
        } else {
          builder.setCurrentTargetRuntimeGameInstanceId(gameInstanceId);
        }
      }

      @Override
      public void setCurrentRuntimeRegionId(boolean originScope, String regionId) {
        if (originScope) {
          builder.setCurrentOriginRuntimeRegionId(regionId);
        } else {
          builder.setCurrentTargetRuntimeRegionId(regionId);
        }
      }

      @Override
      public void setCurrentRuntimeRegionEpoch(boolean originScope, long regionEpoch) {
        if (originScope) {
          builder.setCurrentOriginRuntimeRegionEpoch(regionEpoch);
        } else {
          builder.setCurrentTargetRuntimeRegionEpoch(regionEpoch);
        }
      }

      @Override
      public void setCurrentRuntimePlayableStateScope(
          boolean originScope, PlayableStateScope playableStateScope) {
        if (originScope) {
          builder.setCurrentOriginRuntimePlayableStateScope(playableStateScope);
        } else {
          builder.setCurrentTargetRuntimePlayableStateScope(playableStateScope);
        }
      }

      @Override
      public void setCurrentRuntimeWorldSlug(boolean originScope, String worldSlug) {
        if (originScope) {
          builder.setCurrentOriginRuntimeWorldSlug(worldSlug);
        } else {
          builder.setCurrentTargetRuntimeWorldSlug(worldSlug);
        }
      }

      @Override
      public void setCurrentRuntimeRealmSlug(boolean originScope, String realmSlug) {
        if (originScope) {
          builder.setCurrentOriginRuntimeRealmSlug(realmSlug);
        } else {
          builder.setCurrentTargetRuntimeRealmSlug(realmSlug);
        }
      }

      @Override
      public void setCurrentRuntimePointerVersion(boolean originScope, long pointerVersion) {
        if (originScope) {
          builder.setCurrentOriginRuntimePointerVersion(pointerVersion);
        } else {
          builder.setCurrentTargetRuntimePointerVersion(pointerVersion);
        }
      }
    };
  }

  private CurrentRuntimeScopeFieldEmitter() {}
}
