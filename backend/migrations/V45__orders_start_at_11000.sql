-- Order ids are the customer-facing order numbers. The previous shop already issued
-- 10060..10081, so new orders start at 11000; GREATEST keeps a database that is past it.
SELECT setval(
    pg_get_serial_sequence('orders', 'id'),
    GREATEST(10999, (SELECT COALESCE(MAX(id), 0) FROM orders))
);
