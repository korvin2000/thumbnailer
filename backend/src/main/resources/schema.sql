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

CREATE TABLE IF NOT EXISTS entry (
    album_id VARCHAR(36)   NOT NULL,
    idx      INT           NOT NULL,
    name     VARCHAR(4096) NOT NULL,
    bytes    BIGINT        NOT NULL,
    mtime    BIGINT        NOT NULL,
    width    INT           NOT NULL,
    height   INT           NOT NULL,
    shot_at  TIMESTAMP,
    PRIMARY KEY (album_id, idx)
);
