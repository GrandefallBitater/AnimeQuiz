CREATE TABLE anime (
                       id                 BIGSERIAL PRIMARY KEY,
                       shikimori_id       BIGINT UNIQUE,
                       mal_id             BIGINT UNIQUE,
                       yummy_id           BIGINT UNIQUE,
                       name               VARCHAR(512),
                       russian            VARCHAR(512),
                       title              VARCHAR(512) NOT NULL,
                       kind               VARCHAR(32),
                       airing_status      VARCHAR(32),
                       total_episodes     INT,
                       url                VARCHAR(1024),
                       poster_hash        VARCHAR(64),
                       poster_ext         VARCHAR(8),
                       poster_mime        VARCHAR(64),
                       poster_url         VARCHAR(1024),
                       poster_status      VARCHAR(16) NOT NULL DEFAULT 'pending',
                       completeness       VARCHAR(16) NOT NULL DEFAULT 'stub',
                       is_active          BOOLEAN NOT NULL DEFAULT true,
                       enrich_attempts    INT NOT NULL DEFAULT 0,
                       enrich_last_error  TEXT,
                       enrich_next_at     TIMESTAMPTZ,
                       enriched_sources   TEXT[],
                       enriched_at        TIMESTAMPTZ,
                       created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
                       updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),

                       CONSTRAINT anime_at_least_one_id CHECK (
                           shikimori_id IS NOT NULL OR mal_id IS NOT NULL OR yummy_id IS NOT NULL
                           )
);

CREATE INDEX idx_anime_enrich_queue ON anime (completeness, enrich_next_at)
    WHERE is_active = true;

CREATE INDEX idx_anime_poster_pending ON anime (poster_status)
    WHERE poster_status IN ('pending', 'failed');

CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX idx_anime_title_trgm ON anime USING gin (title gin_trgm_ops);

CREATE TABLE user_anime (
                            id                BIGSERIAL PRIMARY KEY,
                            user_id           VARCHAR(255) NOT NULL REFERENCES users(username) ON DELETE CASCADE,
                            anime_id          BIGINT NOT NULL REFERENCES anime(id) ON DELETE RESTRICT,

                            source            VARCHAR(32) NOT NULL,
                            source_rate_id    VARCHAR(64),

                            status            VARCHAR(16),
                            score             INT,
                            episodes_watched  INT,
                            rewatches         INT,

                            created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
                            updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

                            UNIQUE (user_id, anime_id)
);

CREATE INDEX idx_user_anime_user     ON user_anime (user_id);
CREATE INDEX idx_user_anime_anime    ON user_anime (anime_id);
CREATE INDEX idx_user_anime_status   ON user_anime (user_id, status);

CREATE TABLE user_import (
                             user_id         VARCHAR(255) PRIMARY KEY REFERENCES users(username) ON DELETE CASCADE,
                             source          VARCHAR(32) NOT NULL,
                             nickname        VARCHAR(255) NOT NULL,
                             source_user_id  BIGINT,
                             total_count     INT NOT NULL,
                             imported_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE import_job (
                            id                 BIGSERIAL PRIMARY KEY,
                            type               VARCHAR(32) NOT NULL,
                            source             VARCHAR(32),
                            state              VARCHAR(16) NOT NULL,
                            stage              VARCHAR(32),
                            processed_count    INT NOT NULL DEFAULT 0,
                            total_estimate     INT,
                            message            TEXT,
                            error              TEXT,
                            checkpoint         JSONB,
                            started_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
                            finished_at        TIMESTAMPTZ
);

CREATE INDEX idx_import_job_state ON import_job (state)
    WHERE state = 'running';