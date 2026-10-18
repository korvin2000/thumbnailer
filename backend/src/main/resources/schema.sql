CREATE TABLE IF NOT EXISTS album (
    id             VARCHAR(36)    PRIMARY KEY,
    root           VARCHAR(4096)  NOT NULL,
    path           VARCHAR(4096)  NOT NULL,
    kind           VARCHAR(16)    NOT NULL,
    name           VARCHAR(1024)  NOT NULL,
    cover          VARCHAR(4096),
    cover_pinned   BOOLEAN        NOT NULL,
    revision       BIGINT         NOT NULL,
    cover_portrait BOOLEAN        NOT NULL,
    image_count    INT            NOT NULL,
    bytes          BIGINT         NOT NULL,
    avg_pixels     BIGINT         NOT NULL,
    max_width      INT            NOT NULL,
    max_height     INT            NOT NULL,
    shot_date      DATE,
    modified_at    BIGINT         NOT NULL,
    indexed_at     BIGINT         NOT NULL,
    model          VARCHAR(1024),
    studio         VARCHAR(1024),
    photographer   VARCHAR(1024),
    description    VARCHAR(16384),
    tags           VARCHAR(4096)  NOT NULL,
    rating         INT            NOT NULL,
    content_id     VARCHAR(64)    NOT NULL,
    signature      VARCHAR(64)    NOT NULL,
    extracted_json CLOB           NOT NULL,
    edited_json    CLOB           NOT NULL,
    search         VARCHAR(32768) NOT NULL
);
CREATE INDEX IF NOT EXISTS album_root ON album (root);
CREATE INDEX IF NOT EXISTS album_content ON album (content_id);
-- Scopes, folders and the root filter select by path prefix: path = ? OR path LIKE 'dir/%'.
CREATE INDEX IF NOT EXISTS album_path ON album (path);
-- One index per sort order (JdbcAlbumRepository.order, same columns and directions): a page is an index scan that stops
-- after offset + limit rows instead of sorting every matching row. LOWER(name) cannot be indexed, sort_name can.
ALTER TABLE album ADD COLUMN IF NOT EXISTS sort_name VARCHAR(1024) GENERATED ALWAYS AS (LOWER(name));
CREATE INDEX IF NOT EXISTS album_newest ON album (shot_date DESC NULLS LAST, sort_name, id);
CREATE INDEX IF NOT EXISTS album_oldest ON album (shot_date ASC NULLS LAST, sort_name, id);
CREATE INDEX IF NOT EXISTS album_largest ON album (bytes DESC, sort_name, id);
CREATE INDEX IF NOT EXISTS album_smallest ON album (bytes, sort_name, id);
CREATE INDEX IF NOT EXISTS album_most ON album (image_count DESC, sort_name, id);
CREATE INDEX IF NOT EXISTS album_fewest ON album (image_count, sort_name, id);
CREATE INDEX IF NOT EXISTS album_quality ON album (avg_pixels DESC, sort_name, id);
CREATE INDEX IF NOT EXISTS album_rated ON album (rating DESC, sort_name, id);
CREATE INDEX IF NOT EXISTS album_name ON album (sort_name, id);
CREATE INDEX IF NOT EXISTS album_indexed ON album (indexed_at DESC, sort_name, id);
CREATE INDEX IF NOT EXISTS album_worst ON album (avg_pixels, sort_name, id);
CREATE INDEX IF NOT EXISTS album_lowest ON album (rating, sort_name, id);
CREATE INDEX IF NOT EXISTS album_name_desc ON album (sort_name DESC, id);
CREATE INDEX IF NOT EXISTS album_first ON album (indexed_at, sort_name, id);

-- One packed value per album (store/jdbc/EntryCodec), not one row per image; JdbcAlbumRepository packs and drops the
-- per-image table of older versions on start.
CREATE TABLE IF NOT EXISTS entries (
    album_id VARCHAR(36) PRIMARY KEY,
    data     VARBINARY   NOT NULL
);

-- Tombstones (AlbumRepository.delete / recall), by content: an album that comes back after a removal (moved out of a
-- rescanned folder, restored from a backup, a copy) takes back its id, rating, edits and pinned cover.
CREATE TABLE IF NOT EXISTS removed (
    content_id   VARCHAR(64)   PRIMARY KEY,
    id           VARCHAR(36)   NOT NULL,
    revision     BIGINT        NOT NULL,
    rating       INT           NOT NULL,
    cover        VARCHAR(4096),
    cover_pinned BOOLEAN       NOT NULL,
    edited_json  CLOB          NOT NULL,
    removed_at   BIGINT        NOT NULL
);
CREATE INDEX IF NOT EXISTS removed_id ON removed (id); -- every save consumes the tombstones of its id
