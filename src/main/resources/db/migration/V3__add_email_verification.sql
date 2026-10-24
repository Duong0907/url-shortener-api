ALTER TABLE users
    ADD COLUMN email_verified          BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN verification_token_hash VARCHAR(64),
    ADD COLUMN verification_expires_at TIMESTAMPTZ;

-- Accounts that exist before verification was introduced must keep working.
UPDATE users SET email_verified = true;

-- Many NULLs are allowed. Only a live token must be unique.
ALTER TABLE users
    ADD CONSTRAINT uk_users_verification_token_hash UNIQUE (verification_token_hash);
