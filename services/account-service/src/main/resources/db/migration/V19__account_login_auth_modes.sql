ALTER TABLE accounts
    ADD COLUMN login_auth_modes TEXT NOT NULL DEFAULT 'PASSWORD,EMAIL_OTP';

ALTER TABLE accounts
    ADD CONSTRAINT chk_accounts_login_auth_modes
        CHECK (login_auth_modes IN ('PASSWORD', 'EMAIL_OTP', 'PASSWORD,EMAIL_OTP'));
