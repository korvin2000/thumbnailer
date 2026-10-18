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

-- Tombstones (AlbumRepository.delete / recall), one per (content, album id): an album that comes back after a removal
-- (moved out of a rescanned folder, restored from a backup, a copy) takes back its id, rating, edits, pinned cover,
-- hidden flag and seq (so its links and photo marks); the newest wins. Older libraries keyed it by content_id alone:
-- JdbcAlbumRepository widens that key on start.
CREATE TABLE IF NOT EXISTS removed (
    content_id   VARCHAR(64)   NOT NULL,
    id           VARCHAR(36)   NOT NULL,
    revision     BIGINT        NOT NULL,
    rating       INT           NOT NULL,
    cover        VARCHAR(4096),
    cover_pinned BOOLEAN       NOT NULL,
    edited_json  CLOB          NOT NULL,
    removed_at   BIGINT        NOT NULL,
    PRIMARY KEY (content_id, id)
);
CREATE INDEX IF NOT EXISTS removed_id ON removed (id); -- every save consumes the tombstones of its id
ALTER TABLE removed ADD COLUMN IF NOT EXISTS seq INT DEFAULT 0 NOT NULL;
ALTER TABLE removed ADD COLUMN IF NOT EXISTS hidden BOOLEAN DEFAULT FALSE NOT NULL;

-- Catalog (catalog_plan.md §2). Albums get a compact surrogate key that every catalog table references (assigned by the
-- identity on insert, never in the MERGE column list, adopted from a tombstone by one UPDATE), the safe-delete flag
-- (filtered in queries, no index: it leaves few rows) and the entry name of their bio text.
ALTER TABLE album ADD COLUMN IF NOT EXISTS hidden BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE album ADD COLUMN IF NOT EXISTS bio VARCHAR(4096);
ALTER TABLE album ADD COLUMN IF NOT EXISTS seq INT AUTO_INCREMENT;
CREATE UNIQUE INDEX IF NOT EXISTS album_seq ON album (seq);

-- Models, studios, photographers, genres, tags and favorites lists; kind = EntityKind code (1 model · 2 studio ·
-- 3 photographer · 4 genre · 5 tag · 6 list); albums = visible linked albums, maintained by saves and Catalog.recount.
CREATE TABLE IF NOT EXISTS entity (
    id      INT          AUTO_INCREMENT PRIMARY KEY,
    kind    TINYINT      NOT NULL,
    name    VARCHAR(256) NOT NULL,
    rating  TINYINT      NOT NULL,
    auto    BOOLEAN      NOT NULL,
    albums  INT          NOT NULL,
    profile CLOB
);
ALTER TABLE entity ADD COLUMN IF NOT EXISTS sort_name VARCHAR(256) GENERATED ALWAYS AS (LOWER(name));
CREATE INDEX IF NOT EXISTS entity_kind ON entity (kind, sort_name);
CREATE INDEX IF NOT EXISTS entity_count ON entity (kind, albums DESC);

-- Every name an entity is known by, its primary name included (Catalog.norm); unique per kind.
CREATE TABLE IF NOT EXISTS alias (
    kind      TINYINT      NOT NULL,
    norm      VARCHAR(256) NOT NULL,
    entity_id INT          NOT NULL,
    PRIMARY KEY (kind, norm)
);
CREATE INDEX IF NOT EXISTS alias_entity ON alias (entity_id);

-- catalog_version; KEY and VALUE are reserved words in H2 2.x
CREATE TABLE IF NOT EXISTS meta (name VARCHAR(32) PRIMARY KEY, val VARCHAR(256) NOT NULL);

-- An album credited to an entity, role = the entity's kind; roles 1-5 are derived from the album's text on every save,
-- 6 (favorites) only from link/unlink. alias = the token in the text when it is not the primary name.
CREATE TABLE IF NOT EXISTS album_link (
    album_seq INT          NOT NULL,
    role      TINYINT      NOT NULL,
    entity_id INT          NOT NULL,
    alias     VARCHAR(256),
    PRIMARY KEY (album_seq, role, entity_id)
);
CREATE INDEX IF NOT EXISTS album_link_entity ON album_link (entity_id, album_seq);

-- Curated entity-to-entity facts (subject -> object); model -> studio: alias = her name there, roles = genre names.
CREATE TABLE IF NOT EXISTS entity_link (
    from_id INT          NOT NULL,
    to_id   INT          NOT NULL,
    alias   VARCHAR(256),
    roles   VARCHAR(256),
    PRIMARY KEY (from_id, to_id)
);
CREATE INDEX IF NOT EXISTS entity_link_to ON entity_link (to_id);

-- Photo marks, sparse: only photos the user rated or listed, by entry name (stable across re-index, unlike the index).
CREATE TABLE IF NOT EXISTS photo (
    id        INT           AUTO_INCREMENT PRIMARY KEY,
    album_seq INT           NOT NULL,
    name      VARCHAR(4096) NOT NULL,
    rating    TINYINT       NOT NULL,
    UNIQUE (album_seq, name)
);
CREATE TABLE IF NOT EXISTS photo_link (
    photo_id  INT NOT NULL,
    entity_id INT NOT NULL,
    PRIMARY KEY (photo_id, entity_id)
);
CREATE INDEX IF NOT EXISTS photo_link_entity ON photo_link (entity_id, photo_id);
