INSERT INTO product.categories (id, code, name, sort_order, created_at, updated_at) VALUES
    (gen_random_uuid(), 'APPAREL', '의류', 1, now(), now()),
    (gen_random_uuid(), 'ACCESSORY', '액세서리', 2, now(), now()),
    (gen_random_uuid(), 'STATIONERY', '문구', 3, now(), now()),
    (gen_random_uuid(), 'ELECTRONICS', '전자기기', 4, now(), now()),
    (gen_random_uuid(), 'FIGURE', '피규어', 5, now(), now()),
    (gen_random_uuid(), 'ETC', '기타', 6, now(), now())
ON CONFLICT (code) DO NOTHING;
