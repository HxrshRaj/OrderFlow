-- Synthetic purchase history so "frequently bought together" has data in a fresh demo.
-- These are NOT real customers or sales: customer_id is 'seed-history', order numbers start
-- with SEED-, and the bundles below are invented. Remove this migration to start empty.
DO $$
DECLARE
    bundles TEXT[][] := ARRAY[
        ARRAY['SKU-MOUSE',       'SKU-KEYBOARD',    'SKU-MOUSEPAD'],
        ARRAY['SKU-MONITOR',     'SKU-MONITORARM',  'SKU-HDMI'],
        ARRAY['SKU-WEBCAM',      'SKU-MIC',         'SKU-RINGLIGHT'],
        ARRAY['SKU-LAPTOPSTAND', 'SKU-USBHUB',      'SKU-SLEEVE'],
        ARRAY['SKU-CHAIR',       'SKU-FOOTREST',    'SKU-DESKMAT'],
        ARRAY['SKU-HEADSET',     'SKU-MIC',         'SKU-WEBCAMCOVER']
    ];
    noise TEXT[] := ARRAY['SKU-CABLE', 'SKU-NOTEBOOK', 'SKU-PENS', 'SKU-CLEANKIT'];
    b INT; r INT; n INT := 0; oid BIGINT; items TEXT[]; s TEXT;
BEGIN
    FOR b IN 1 .. array_length(bundles, 1) LOOP
        FOR r IN 1 .. 14 LOOP
            n := n + 1;
            -- Most orders take the whole bundle, some only the first two items.
            items := CASE WHEN r % 4 = 0 THEN ARRAY[bundles[b][1], bundles[b][2]]
                          ELSE ARRAY[bundles[b][1], bundles[b][2], bundles[b][3]] END;
            -- Every third order also picks up a popular cheap item.
            IF r % 3 = 0 THEN
                items := items || noise[(n % array_length(noise, 1)) + 1];
            END IF;
            INSERT INTO orders (order_number, reservation_id, customer_id, status, total_amount)
            VALUES ('SEED-' || lpad(n::text, 4, '0'), gen_random_uuid(), 'seed-history',
                    CASE WHEN n % 2 = 0 THEN 'SHIPPED' ELSE 'CONFIRMED' END, 0)
            RETURNING id INTO oid;
            FOREACH s IN ARRAY items LOOP
                INSERT INTO order_item (order_fk, sku, quantity, unit_price) VALUES (oid, s, 1, 10.00);
            END LOOP;
            UPDATE orders SET total_amount = 10.00 * array_length(items, 1) WHERE id = oid;
        END LOOP;
    END LOOP;
END $$;
