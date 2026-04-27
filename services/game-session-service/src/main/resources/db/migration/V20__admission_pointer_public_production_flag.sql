ALTER TABLE gameplay_admission_pointer
    ADD COLUMN public_production_realm BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE gameplay_admission_pointer_event
    ADD COLUMN public_production_realm BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE gameplay_admission_pointer
SET public_production_realm = TRUE
WHERE visible = TRUE AND LOWER(realm_slug) = 'production';

UPDATE gameplay_admission_pointer_event
SET public_production_realm = TRUE
WHERE visible = TRUE AND LOWER(realm_slug) = 'production';
