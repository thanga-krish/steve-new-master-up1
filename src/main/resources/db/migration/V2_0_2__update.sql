CREATE TABLE schedule_charging (

    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    id_tag VARCHAR(25) NOT NULL,
    charge_box_id VARCHAR(30) NOT NULL,
    connector_id INT NOT NULL,
    notification_id VARCHAR(25),
    start_time DATETIME NOT NULL,
    end_time DATETIME NOT NULL,
    is_start TINYINT(1) NOT NULL DEFAULT 0
);