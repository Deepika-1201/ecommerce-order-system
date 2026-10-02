-- Categories, products, variants and images (LLD §3.5–3.8).

CREATE TABLE categories (
    id          uuid        PRIMARY KEY,
    parent_id   uuid        REFERENCES categories (id),
    name        text        NOT NULL,
    slug        text        NOT NULL,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    CONSTRAINT categories_slug_unique UNIQUE (slug)
);

CREATE INDEX categories_by_parent ON categories (parent_id);

CREATE TABLE products (
    id             uuid        PRIMARY KEY,
    category_id    uuid        NOT NULL REFERENCES categories (id),
    title          text        NOT NULL,
    description    text        NOT NULL DEFAULT '',
    gst_category   text        NOT NULL
        CHECK (gst_category IN ('STANDARD', 'REDUCED', 'EXEMPT', 'APPAREL', 'FOOTWEAR', 'DEMERIT')),
    options        jsonb       NOT NULL DEFAULT '[]',
    status         text        NOT NULL CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    version        bigint      NOT NULL,
    search_vector  tsvector    GENERATED ALWAYS AS (
                       setweight(to_tsvector('english', title), 'A')
                       || setweight(to_tsvector('english', description), 'B')) STORED,
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL
);

COMMENT ON COLUMN products.options IS 'Option dimensions in order: [{"name": "size", "values": ["S", "M"]}].';
COMMENT ON COLUMN products.version IS 'Increases with every change to the product, its variants or images; the admin ETag.';

CREATE INDEX products_search ON products USING gin (search_vector);
CREATE INDEX products_active_by_category ON products (category_id, id) WHERE status = 'ACTIVE';
CREATE INDEX products_by_status ON products (status, id);

CREATE TABLE variants (
    id               uuid        PRIMARY KEY,
    product_id       uuid        NOT NULL REFERENCES products (id),
    sku              text        NOT NULL,
    option_values    jsonb       NOT NULL,
    combination_key  text        NOT NULL,
    price_paise      bigint      NOT NULL CHECK (price_paise BETWEEN 1 AND 1000000000),
    status           text        NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    CONSTRAINT variants_sku_unique UNIQUE (sku),
    CONSTRAINT variants_combination_unique UNIQUE (product_id, combination_key)
);

COMMENT ON COLUMN variants.combination_key IS 'Canonical option values (color=red;size=m), so each combination is sold once.';

CREATE TABLE product_images (
    id            uuid        PRIMARY KEY,
    product_id    uuid        NOT NULL REFERENCES products (id),
    object_key    text        NOT NULL UNIQUE,
    content_type  text        NOT NULL,
    size_bytes    bigint      NOT NULL CHECK (size_bytes > 0),
    alt_text      text        NOT NULL DEFAULT '',
    status        text        NOT NULL CHECK (status IN ('PENDING', 'READY')),
    position      integer,
    created_at    timestamptz NOT NULL,
    completed_at  timestamptz
);

CREATE INDEX product_images_by_product ON product_images (product_id, position);
CREATE INDEX product_images_pending ON product_images (created_at) WHERE status = 'PENDING';
