-- The historical bootstrap configuration made both retained default realms public for a tenant.
-- Repair only the exact, unchanged bootstrap pair with its complete creation evidence. Any
-- operator-edited, ambiguous, incomplete, or otherwise unproven catalog remains untouched.
-- This data-only PostgreSQL CTE is ignored by jOOQ's H2 migration parser and is covered by the
-- PostgreSQL migration integration proof; it changes no generated schema objects.
-- [jooq ignore start]
WITH eligible_pairs AS (
    SELECT demo.id AS demo_pointer_id,
           sandbox.id AS sandbox_pointer_id
    FROM gameplay_admission_pointer demo
    JOIN gameplay_admission_pointer sandbox
      ON sandbox.tenant_id = demo.tenant_id
    JOIN gameplay_tenant_shared_playable_state_namespace shared_namespace
      ON shared_namespace.tenant_id = demo.tenant_id
     AND shared_namespace.playable_state_namespace_id = demo.playable_state_namespace_id
    WHERE demo.world_slug = 'demo'
      AND demo.world_display_name = 'Demo World'
      AND demo.realm_slug = 'production'
      AND demo.realm_display_name = 'Live Realm'
      AND demo.visible = true
      AND demo.public_production_realm = true
      AND demo.requires_character_selection = false
      AND demo.state_scope = 'SHARED'
      AND demo.character_creation_policy = 'ALLOW_NEW'
      AND demo.pointer_version = 1
      AND demo.catalog_revision = 1
      AND demo.last_updated_by = 'system/bootstrap'
      AND demo.last_update_reason = 'Initial gameplay pointer bootstrap'
      AND demo.realm_id IS NOT NULL
      AND demo.playable_state_namespace_id IS NOT NULL
      AND sandbox.world_slug = 'sandbox'
      AND sandbox.world_display_name = 'Builder Sandbox'
      AND sandbox.realm_slug = 'production'
      AND sandbox.realm_display_name = 'Live Realm'
      AND sandbox.visible = true
      AND sandbox.public_production_realm = true
      AND sandbox.requires_character_selection = true
      AND sandbox.state_scope = 'SHARED'
      AND sandbox.character_creation_policy = 'ALLOW_NEW'
      AND sandbox.pointer_version = 1
      AND sandbox.catalog_revision = 1
      AND sandbox.last_updated_by = 'system/bootstrap'
      AND sandbox.last_update_reason = 'Initial gameplay pointer bootstrap'
      AND sandbox.realm_id IS NOT NULL
      AND sandbox.playable_state_namespace_id IS NOT NULL
      AND demo.tenant_id > 0
      AND demo.game_instance_id > 0
      AND sandbox.game_instance_id > 0
      AND demo.game_instance_id <> sandbox.game_instance_id
      AND demo.realm_id <> sandbox.realm_id
      AND demo.playable_state_namespace_id = sandbox.playable_state_namespace_id
      AND (
          SELECT COUNT(*)
          FROM gameplay_admission_pointer public_pointer
          WHERE public_pointer.tenant_id = demo.tenant_id
            AND public_pointer.public_production_realm = true
      ) = 2
      AND (
          SELECT COUNT(*)
          FROM gameplay_admission_pointer_event demo_event
          WHERE demo_event.tenant_id = demo.tenant_id
            AND demo_event.world_slug = 'demo'
            AND demo_event.realm_slug = 'production'
      ) = 1
      AND EXISTS (
          SELECT 1
          FROM gameplay_admission_pointer_event demo_event
          WHERE demo_event.tenant_id = demo.tenant_id
            AND demo_event.game_instance_id = demo.game_instance_id
            AND demo_event.world_slug = 'demo'
            AND demo_event.world_display_name = 'Demo World'
            AND demo_event.realm_slug = 'production'
            AND demo_event.realm_display_name = 'Live Realm'
            AND demo_event.pointer_version = 1
            AND demo_event.visible = true
            AND demo_event.public_production_realm = true
            AND demo_event.requires_character_selection = false
            AND demo_event.state_scope = 'SHARED'
            AND demo_event.character_creation_policy = 'ALLOW_NEW'
            AND demo_event.actor_principal = 'system/bootstrap'
            AND demo_event.reason = 'Initial gameplay pointer bootstrap'
            AND demo_event.control_plane_request_id =
                'bootstrap:' || demo.tenant_id || ':' || demo.game_instance_id || ':demo:production'
            AND demo_event.prepared_version_upgrade_id IS NULL
            AND (demo_event.catalog_revision IS NULL OR demo_event.catalog_revision = 1)
            AND (demo_event.realm_id IS NULL OR demo_event.realm_id = demo.realm_id)
            AND (demo_event.playable_state_namespace_id IS NULL
                 OR demo_event.playable_state_namespace_id = demo.playable_state_namespace_id)
      )
      AND (
          SELECT COUNT(*)
          FROM gameplay_admission_pointer_event sandbox_event
          WHERE sandbox_event.tenant_id = sandbox.tenant_id
            AND sandbox_event.world_slug = 'sandbox'
            AND sandbox_event.realm_slug = 'production'
      ) = 1
      AND EXISTS (
          SELECT 1
          FROM gameplay_admission_pointer_event sandbox_event
          WHERE sandbox_event.tenant_id = sandbox.tenant_id
            AND sandbox_event.game_instance_id = sandbox.game_instance_id
            AND sandbox_event.world_slug = 'sandbox'
            AND sandbox_event.world_display_name = 'Builder Sandbox'
            AND sandbox_event.realm_slug = 'production'
            AND sandbox_event.realm_display_name = 'Live Realm'
            AND sandbox_event.pointer_version = 1
            AND sandbox_event.visible = true
            AND sandbox_event.public_production_realm = true
            AND sandbox_event.requires_character_selection = true
            AND sandbox_event.state_scope = 'SHARED'
            AND sandbox_event.character_creation_policy = 'ALLOW_NEW'
            AND sandbox_event.actor_principal = 'system/bootstrap'
            AND sandbox_event.reason = 'Initial gameplay pointer bootstrap'
            AND sandbox_event.control_plane_request_id =
                'bootstrap:' || sandbox.tenant_id || ':' || sandbox.game_instance_id || ':sandbox:production'
            AND sandbox_event.prepared_version_upgrade_id IS NULL
            AND (sandbox_event.catalog_revision IS NULL OR sandbox_event.catalog_revision = 1)
            AND (sandbox_event.realm_id IS NULL OR sandbox_event.realm_id = sandbox.realm_id)
            AND (sandbox_event.playable_state_namespace_id IS NULL
                 OR sandbox_event.playable_state_namespace_id = sandbox.playable_state_namespace_id)
      )
      AND NOT EXISTS (
          SELECT 1
          FROM gameplay_admission_pointer_event migration_event
          WHERE migration_event.control_plane_request_id =
              'migration:V8:repair-legacy-bootstrap-public-realm:' || demo.tenant_id
      )
    FOR UPDATE OF demo, sandbox
), repaired_sandbox AS (
    UPDATE gameplay_admission_pointer sandbox
    SET public_production_realm = false,
        catalog_revision = sandbox.catalog_revision + 1,
        last_updated_by = 'system/migration',
        last_update_reason = 'Repair duplicate public production in retained bootstrap catalog',
        updated_at = CURRENT_TIMESTAMP
    FROM eligible_pairs eligible
    WHERE sandbox.id = eligible.sandbox_pointer_id
      AND sandbox.public_production_realm = true
      AND sandbox.catalog_revision = 1
    RETURNING sandbox.*
)
INSERT INTO gameplay_admission_pointer_event (
    world_slug,
    realm_slug,
    world_display_name,
    realm_display_name,
    tenant_id,
    game_instance_id,
    pointer_version,
    catalog_revision,
    realm_id,
    playable_state_namespace_id,
    visible,
    requires_character_selection,
    state_scope,
    character_creation_policy,
    actor_principal,
    reason,
    control_plane_request_id,
    occurred_at,
    prepared_version_upgrade_id,
    public_production_realm
)
SELECT sandbox.world_slug,
       sandbox.realm_slug,
       sandbox.world_display_name,
       sandbox.realm_display_name,
       sandbox.tenant_id,
       sandbox.game_instance_id,
       sandbox.pointer_version,
       sandbox.catalog_revision,
       sandbox.realm_id,
       sandbox.playable_state_namespace_id,
       sandbox.visible,
       sandbox.requires_character_selection,
       sandbox.state_scope,
       sandbox.character_creation_policy,
       'system/migration',
       'Repair duplicate public production in retained bootstrap catalog',
       'migration:V8:repair-legacy-bootstrap-public-realm:' || sandbox.tenant_id,
       CURRENT_TIMESTAMP,
       NULL,
       sandbox.public_production_realm
FROM repaired_sandbox sandbox;
-- [jooq ignore stop]
