CREATE TABLE scheduled_charging (
    id SERIAL PRIMARY KEY,
    charge_box_id VARCHAR(255),
    id_tag VARCHAR(255),
    start_time TIMESTAMP,
    stop_time TIMESTAMP,
    status VARCHAR(20) DEFAULT 'PENDING'
);
