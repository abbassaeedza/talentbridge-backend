CREATE TABLE application_settings (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    global_deadline TIMESTAMP
);

INSERT INTO application_settings (id) VALUES (1);
