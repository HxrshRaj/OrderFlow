-- Catalogue search: full-text (prefix) matching plus trigram similarity for typos.
-- pg_trgm ships with PostgreSQL's contrib modules (postgres:16 images and Render include it).
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX idx_inventory_item_name_fts  ON inventory_item USING GIN (to_tsvector('simple', name));
CREATE INDEX idx_inventory_item_name_trgm ON inventory_item USING GIN (name gin_trgm_ops);

-- A larger demo catalogue so search and recommendations have something to work on.
INSERT INTO inventory_item (sku, name, available_quantity, reserved_quantity) VALUES
    ('SKU-MOUSE-ERGO',   'Ergonomic Wireless Mouse',           30, 0),
    ('SKU-MOUSE-GAMING', 'Gaming Mouse RGB',                   18, 0),
    ('SKU-MOUSEPAD',     'Mouse Pad XL',                       60, 0),
    ('SKU-KEYBOARD-BT',  'Bluetooth Keyboard Slim',            22, 0),
    ('SKU-WRISTREST',    'Keyboard Wrist Rest',                35, 0),
    ('SKU-LAPTOPSTAND',  'Laptop Stand Aluminium',             20, 0),
    ('SKU-MONITORARM',   'Monitor Arm Single',                 14, 0),
    ('SKU-LAMP',         'Desk Lamp LED',                      26, 0),
    ('SKU-HDMI',         'HDMI Cable 2m',                      80, 0),
    ('SKU-ETHERNET',     'Ethernet Cable 5m',                  70, 0),
    ('SKU-USBHUB',       'USB-C Hub 7-in-1',                   24, 0),
    ('SKU-SSD',          'Portable SSD 1TB',                   16, 0),
    ('SKU-FLASH32',      '32GB USB Flash Drive',               90, 0),
    ('SKU-MIC',          'Studio Microphone USB',              11, 0),
    ('SKU-SPEAKER',      'Bluetooth Speaker Mini',             28, 0),
    ('SKU-EARBUDS',      'Wireless Earbuds',                   32, 0),
    ('SKU-SLEEVE',       'Laptop Sleeve 14 inch',              40, 0),
    ('SKU-PHONESTAND',   'Phone Stand Adjustable',             55, 0),
    ('SKU-POWERSTRIP',   'Power Strip 6-outlet',               45, 0),
    ('SKU-CLEANKIT',     'Screen Cleaning Kit',                65, 0),
    ('SKU-CABLEORG',     'Cable Organizer Pack',               75, 0),
    ('SKU-DESKMAT',      'Anti-Fatigue Standing Desk Mat',     12, 0),
    ('SKU-CHAIR',        'Ergonomic Office Chair',              6, 0),
    ('SKU-FOOTREST',     'Under-Desk Footrest',                17, 0),
    ('SKU-MARKERS',      'Whiteboard Markers 8 Pack',          50, 0),
    ('SKU-NOTEBOOK',     'Notebook A5 Dotted',                 85, 0),
    ('SKU-PENS',         'Gel Pens 12 Pack',                   95, 0),
    ('SKU-RINGLIGHT',    'Webcam Ring Light',                  21, 0),
    ('SKU-CHARGER',      'Wireless Charger Pad',               38, 0),
    ('SKU-SMARTPLUG',    'Smart Plug WiFi',                    48, 0),
    ('SKU-WEBCAMCOVER',  'Webcam Privacy Cover 3 Pack',       110, 0),
    ('SKU-OOS-TABLET',   'Drawing Tablet Pro (sold out)',       0, 0);
