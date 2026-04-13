ALTER TABLE profiles
    ADD COLUMN presence_visibility_policy VARCHAR(32) NOT NULL DEFAULT 'FRIENDS_ONLY';
