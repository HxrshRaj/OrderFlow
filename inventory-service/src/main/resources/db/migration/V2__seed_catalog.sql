-- Demo catalogue so the system is usable immediately after `docker-compose up`.
INSERT INTO inventory_item (sku, name, available_quantity, reserved_quantity) VALUES
    ('SKU-KEYBOARD',  'Mechanical Keyboard',        25, 0),
    ('SKU-MOUSE',     'Wireless Mouse',             40, 0),
    ('SKU-MONITOR',   '27" 4K Monitor',             12, 0),
    ('SKU-DOCK',      'USB-C Docking Station',       8, 0),
    ('SKU-WEBCAM',    '1080p Webcam',               15, 0),
    ('SKU-HEADSET',   'Noise-Cancelling Headset',    5, 0),
    ('SKU-CABLE',     'USB-C Cable 2m',            100, 0),
    ('SKU-LASTUNIT',  'Collector Edition (1 left)',  1, 0);
