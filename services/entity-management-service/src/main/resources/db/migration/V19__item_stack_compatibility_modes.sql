ALTER TABLE items
    ADD COLUMN stack_compatibility_mode VARCHAR(64) NOT NULL DEFAULT 'DEFINITION_ONLY';

ALTER TABLE items
    ADD COLUMN default_stack_family_key VARCHAR(128);

ALTER TABLE item_stacks
    ADD COLUMN stack_family_key VARCHAR(128);
