-- Connected repository metadata for the RepositoryConnection aggregate (issue #19).
--
-- Only metadata and the name of the webhook signing secret are stored. The
-- secret value itself lives in the environment or a secret store and never
-- reaches this table.
--
-- Column bounds mirror the domain validation (RepositoryIdentity, BranchName,
-- SecretReference); the constraints keep rows written outside the Control
-- Plane from contradicting it.
CREATE TABLE repository_connection (
    id                       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    provider                 VARCHAR(16)  NOT NULL,
    owner                    VARCHAR(39)  NOT NULL,
    name                     VARCHAR(100) NOT NULL,
    default_branch           VARCHAR(255) NOT NULL,
    webhook_secret_reference VARCHAR(128) NOT NULL,
    status                   VARCHAR(16)  NOT NULL,
    connected_at             TIMESTAMPTZ  NOT NULL,
    status_changed_at        TIMESTAMPTZ  NOT NULL,

    -- The aggregate identity. One repository can be connected only once, and
    -- the adapter relies on this constraint to reject duplicates atomically.
    CONSTRAINT repository_connection_identity_key UNIQUE (provider, owner, name),
    CONSTRAINT repository_connection_provider_check CHECK (provider IN ('GITHUB')),
    CONSTRAINT repository_connection_canonical_identity_check CHECK (owner = lower(owner) AND name = lower(name)),
    CONSTRAINT repository_connection_status_check
        CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED', 'DISCONNECTED')),
    CONSTRAINT repository_connection_status_time_check CHECK (status_changed_at >= connected_at)
);
