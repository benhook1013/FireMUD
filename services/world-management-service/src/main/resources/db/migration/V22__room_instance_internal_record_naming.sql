ALTER TABLE room_instance
    RENAME COLUMN room_instance_id TO room_instance_row_id;

ALTER TABLE room_instance_exit
    RENAME COLUMN from_room_instance_id TO from_room_instance_record_id;

ALTER TABLE room_instance_exit
    RENAME COLUMN to_room_instance_id TO to_room_instance_record_id;

ALTER INDEX idx_room_instance_exit_from
    RENAME TO idx_room_instance_exit_from_record;
