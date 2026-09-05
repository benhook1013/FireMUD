ALTER TABLE script_work_items
    ADD CONSTRAINT ck_script_work_items_plugin_pair_coherent CHECK (
        (plugin_id = '' AND plugin_version_id = '')
        OR (
            BTRIM(plugin_id) <> ''
            AND BTRIM(plugin_version_id) <> ''
        )
    ) /* [jooq ignore start] */ NOT VALID /* [jooq ignore stop] */;
