CREATE TABLE IF NOT EXISTS competition (
    id VARCHAR(255) PRIMARY KEY,
    password VARCHAR(255) NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS dns_entry (
    id BIGSERIAL PRIMARY KEY,
    competition_id VARCHAR(255) NOT NULL,
    competitor_number INTEGER NOT NULL,
    registered_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    registered_by VARCHAR(255),
    comment VARCHAR(500)
);
