DROP TABLE IF EXISTS competition;
CREATE TABLE competition (
    password VARCHAR(255) PRIMARY KEY,
    competition_id VARCHAR(255) NOT NULL
);

CREATE TABLE IF NOT EXISTS dns_entry (
    id BIGSERIAL PRIMARY KEY,
    competition_id VARCHAR(255) NOT NULL,
    competitor_number INTEGER NOT NULL,
    registered_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    registered_by VARCHAR(255),
    comment VARCHAR(500)
);

DROP TABLE IF EXISTS approved_machine;
CREATE TABLE approved_machine (
    id BIGSERIAL PRIMARY KEY,
    competition_id VARCHAR(255) NOT NULL,
    machine_id VARCHAR(255) NOT NULL,
    machine_name VARCHAR(255) NOT NULL,
    approved BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (competition_id, machine_id)
);

CREATE TABLE IF NOT EXISTS machine_reading (
    id BIGSERIAL PRIMARY KEY,
    competition_id VARCHAR(255) NOT NULL,
    machine_id VARCHAR(255) NOT NULL,
    machine_name VARCHAR(255),
    bib INTEGER,
    cc VARCHAR(255),
    read_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    found BOOLEAN NOT NULL DEFAULT FALSE
);
