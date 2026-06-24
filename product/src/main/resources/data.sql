INSERT INTO product.categories (id, name, created_at, updated_at) VALUES
    (gen_random_uuid(), '의류', now(), now()),
    (gen_random_uuid(), '액세서리', now(), now()),
    (gen_random_uuid(), '문구', now(), now()),
    (gen_random_uuid(), '전자기기', now(), now()),
    (gen_random_uuid(), '피규어', now(), now()),
    (gen_random_uuid(), '기타', now(), now())
ON CONFLICT (name) DO NOTHING;
