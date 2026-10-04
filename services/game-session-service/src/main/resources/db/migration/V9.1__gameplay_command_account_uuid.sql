ALTER TABLE gameplay_command
    ADD COLUMN account_uuid UUID;

ALTER TABLE gameplay_command
    ADD CONSTRAINT chk_gameplay_command_account_uuid_non_nil
        CHECK (
            account_uuid IS NULL
            OR account_uuid <> '00000000-0000-0000-0000-000000000000'::UUID
        );
