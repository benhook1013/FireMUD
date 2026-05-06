ALTER TABLE remote_command_coordinator
    ADD COLUMN automation_dispatch_id VARCHAR(128),
    ADD COLUMN automation_work_item_id VARCHAR(128),
    ADD COLUMN script_id VARCHAR(128);

ALTER TABLE remote_followup
    ADD COLUMN command_id VARCHAR(128),
    ADD COLUMN automation_dispatch_id VARCHAR(128),
    ADD COLUMN automation_work_item_id VARCHAR(128),
    ADD COLUMN script_id VARCHAR(128);

ALTER TABLE remote_followup_result
    ADD COLUMN command_id VARCHAR(128),
    ADD COLUMN automation_dispatch_id VARCHAR(128),
    ADD COLUMN automation_work_item_id VARCHAR(128),
    ADD COLUMN script_id VARCHAR(128);
