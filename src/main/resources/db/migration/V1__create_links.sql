CREATE TABLE links (
    id         UUID PRIMARY KEY,
    slug       VARCHAR(32) NOT NULL CONSTRAINT uk_links_slug UNIQUE,
    target_url TEXT        NOT NULL,
    owner_id   UUID,
    is_custom  BOOLEAN     NOT NULL DEFAULT FALSE,
    active     BOOLEAN     NOT NULL DEFAULT TRUE,
    expires_at TIMESTAMPTZ,
    created_ip VARCHAR(45),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_links_owner_created ON links (owner_id, created_at DESC);
