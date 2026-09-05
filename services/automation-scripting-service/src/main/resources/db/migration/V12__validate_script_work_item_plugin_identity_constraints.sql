-- [jooq ignore start]
ALTER TABLE script_work_items
    VALIDATE CONSTRAINT ck_script_work_items_plugin_pair_coherent;
-- [jooq ignore stop]
