ALTER TABLE published_release_bundle
    ADD COLUMN command_definitions_json TEXT NOT NULL DEFAULT '[]';
