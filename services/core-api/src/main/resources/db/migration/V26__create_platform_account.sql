CREATE TABLE platform_account (
    account_id UUID PRIMARY KEY,
    username VARCHAR(128) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(32) NOT NULL,
    enabled BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_platform_account_username UNIQUE (username),
    CONSTRAINT ck_platform_account_username
        CHECK (username ~ '^[A-Za-z0-9][A-Za-z0-9._:@-]{2,127}$'),
    CONSTRAINT ck_platform_account_password_hash
        CHECK (char_length(password_hash) BETWEEN 20 AND 100),
    CONSTRAINT ck_platform_account_role
        CHECK (role IN ('ADMIN', 'MEMBER')),
    CONSTRAINT ck_platform_account_row_version
        CHECK (row_version >= 0)
);

CREATE INDEX ix_platform_account_enabled_username
    ON platform_account (enabled, username);
