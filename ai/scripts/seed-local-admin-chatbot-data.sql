-- 로컬 관리자 챗봇 통합 테스트 전용 데이터다.
-- 고정 UUID와 AI_TEST_/ORD-AI- 식별자를 사용하며 기존 데이터는 수정하지 않는다.
-- 같은 로컬 DB에서 여러 번 실행해도 동일한 테스트 행만 갱신된다.

\set ON_ERROR_STOP on

BEGIN;
SET LOCAL TIME ZONE 'Asia/Seoul';

CREATE TEMP TABLE ai_seed_clock ON COMMIT DROP AS
SELECT
    date_trunc('day', CURRENT_TIMESTAMP) AS today_start,
    date_trunc('month', CURRENT_TIMESTAMP) AS this_month_start,
    date_trunc('month', CURRENT_TIMESTAMP) - INTERVAL '1 month' AS previous_month_start,
    CURRENT_TIMESTAMP AS seed_now;

INSERT INTO member.member (
    created_at,
    deleted_at,
    updated_at,
    id,
    email,
    nickname,
    password,
    platform_type
)
VALUES
    (
        (SELECT today_start - INTERVAL '180 days' FROM ai_seed_clock),
        NULL,
        (SELECT seed_now FROM ai_seed_clock),
        'a1000000-0000-4000-8000-000000000001',
        'ai-test-seller@openat.local',
        'AI_TEST_SELLER',
        'AI_TEST_ONLY_NOT_FOR_LOGIN',
        'LOCAL'
    ),
    (
        (SELECT today_start - INTERVAL '120 days' FROM ai_seed_clock),
        NULL,
        (SELECT seed_now FROM ai_seed_clock),
        'a1000000-0000-4000-8000-000000000002',
        'ai-test-buyer-local@openat.local',
        'AI_TEST_BUYER_LOCAL',
        'AI_TEST_ONLY_NOT_FOR_LOGIN',
        'LOCAL'
    ),
    (
        (SELECT today_start - INTERVAL '90 days' FROM ai_seed_clock),
        NULL,
        (SELECT seed_now FROM ai_seed_clock),
        'a1000000-0000-4000-8000-000000000003',
        'ai-test-buyer-kakao@openat.local',
        'AI_TEST_BUYER_KAKAO',
        'AI_TEST_ONLY_NOT_FOR_LOGIN',
        'KAKAO'
    ),
    (
        (SELECT today_start - INTERVAL '60 days' FROM ai_seed_clock),
        NULL,
        (SELECT seed_now FROM ai_seed_clock),
        'a1000000-0000-4000-8000-000000000004',
        'ai-test-buyer-google@openat.local',
        'AI_TEST_BUYER_GOOGLE',
        'AI_TEST_ONLY_NOT_FOR_LOGIN',
        'GOOGLE'
    ),
    (
        (SELECT today_start - INTERVAL '30 days' FROM ai_seed_clock),
        NULL,
        (SELECT seed_now FROM ai_seed_clock),
        'a1000000-0000-4000-8000-000000000005',
        'ai-test-buyer-naver@openat.local',
        'AI_TEST_BUYER_NAVER',
        'AI_TEST_ONLY_NOT_FOR_LOGIN',
        'NAVER'
    ),
    (
        (SELECT today_start - INTERVAL '200 days' FROM ai_seed_clock),
        NULL,
        (SELECT seed_now FROM ai_seed_clock),
        'a1000000-0000-4000-8000-000000000006',
        'ai-test-admin@openat.local',
        'AI_TEST_ADMIN',
        'AI_TEST_ONLY_NOT_FOR_LOGIN',
        'LOCAL'
    )
ON CONFLICT (id) DO UPDATE
SET
    created_at = EXCLUDED.created_at,
    deleted_at = NULL,
    updated_at = EXCLUDED.updated_at,
    email = EXCLUDED.email,
    nickname = EXCLUDED.nickname,
    password = EXCLUDED.password,
    platform_type = EXCLUDED.platform_type;

INSERT INTO member.role_history (
    created_at,
    deleted_at,
    id,
    role_id,
    member_id
)
VALUES
    (
        (SELECT today_start - INTERVAL '180 days' FROM ai_seed_clock),
        NULL,
        9100000001,
        (SELECT id FROM member.role WHERE role = 'ROLE_SELLER'),
        'a1000000-0000-4000-8000-000000000001'
    ),
    (
        (SELECT today_start - INTERVAL '120 days' FROM ai_seed_clock),
        NULL,
        9100000002,
        (SELECT id FROM member.role WHERE role = 'ROLE_USER'),
        'a1000000-0000-4000-8000-000000000002'
    ),
    (
        (SELECT today_start - INTERVAL '90 days' FROM ai_seed_clock),
        NULL,
        9100000003,
        (SELECT id FROM member.role WHERE role = 'ROLE_USER'),
        'a1000000-0000-4000-8000-000000000003'
    ),
    (
        (SELECT today_start - INTERVAL '60 days' FROM ai_seed_clock),
        NULL,
        9100000004,
        (SELECT id FROM member.role WHERE role = 'ROLE_USER'),
        'a1000000-0000-4000-8000-000000000004'
    ),
    (
        (SELECT today_start - INTERVAL '30 days' FROM ai_seed_clock),
        NULL,
        9100000005,
        (SELECT id FROM member.role WHERE role = 'ROLE_USER'),
        'a1000000-0000-4000-8000-000000000005'
    ),
    (
        (SELECT today_start - INTERVAL '200 days' FROM ai_seed_clock),
        NULL,
        9100000006,
        (SELECT id FROM member.role WHERE role = 'ROLE_ADMIN'),
        'a1000000-0000-4000-8000-000000000006'
    )
ON CONFLICT (id) DO UPDATE
SET
    created_at = EXCLUDED.created_at,
    deleted_at = NULL,
    role_id = EXCLUDED.role_id,
    member_id = EXCLUDED.member_id;

INSERT INTO product.categories (id, created_at, name, updated_at)
VALUES
    (
        'a2000000-0000-4000-8000-000000000001',
        (SELECT today_start - INTERVAL '180 days' FROM ai_seed_clock),
        'AI_TEST_스니커즈',
        (SELECT seed_now FROM ai_seed_clock)
    ),
    (
        'a2000000-0000-4000-8000-000000000002',
        (SELECT today_start - INTERVAL '180 days' FROM ai_seed_clock),
        'AI_TEST_의류',
        (SELECT seed_now FROM ai_seed_clock)
    ),
    (
        'a2000000-0000-4000-8000-000000000003',
        (SELECT today_start - INTERVAL '180 days' FROM ai_seed_clock),
        'AI_TEST_굿즈',
        (SELECT seed_now FROM ai_seed_clock)
    )
ON CONFLICT (id) DO UPDATE
SET
    name = EXCLUDED.name,
    updated_at = EXCLUDED.updated_at;

INSERT INTO product.products (
    id,
    created_at,
    description,
    name,
    price,
    seller_id,
    thumbnail_key,
    updated_at,
    deleted_at,
    category_id
)
VALUES
    (
        'a3000000-0000-4000-8000-000000000001',
        (SELECT today_start - INTERVAL '45 days' FROM ai_seed_clock),
        '관리자 챗봇 테스트용 한정 스니커즈',
        'AI_TEST_한정 스니커즈',
        129000,
        'a1000000-0000-4000-8000-000000000001',
        'ai-test/sneakers.webp',
        (SELECT seed_now FROM ai_seed_clock),
        NULL,
        'a2000000-0000-4000-8000-000000000001'
    ),
    (
        'a3000000-0000-4000-8000-000000000002',
        (SELECT today_start - INTERVAL '10 days' FROM ai_seed_clock),
        '관리자 챗봇 테스트용 후드',
        'AI_TEST_드롭 후드',
        89000,
        'a1000000-0000-4000-8000-000000000001',
        'ai-test/hoodie.webp',
        (SELECT seed_now FROM ai_seed_clock),
        NULL,
        'a2000000-0000-4000-8000-000000000002'
    ),
    (
        'a3000000-0000-4000-8000-000000000003',
        (SELECT today_start + (seed_now - today_start) * 0.15 FROM ai_seed_clock),
        NULL,
        'AI_TEST_설명 미등록 상품',
        NULL,
        'a1000000-0000-4000-8000-000000000001',
        NULL,
        (SELECT seed_now FROM ai_seed_clock),
        NULL,
        'a2000000-0000-4000-8000-000000000003'
    ),
    (
        'a3000000-0000-4000-8000-000000000004',
        (SELECT previous_month_start + INTERVAL '10 days' FROM ai_seed_clock),
        '지난달 상품 추이 테스트용 상품',
        'AI_TEST_지난달 상품',
        49000,
        'a1000000-0000-4000-8000-000000000001',
        NULL,
        (SELECT seed_now FROM ai_seed_clock),
        NULL,
        'a2000000-0000-4000-8000-000000000003'
    ),
    (
        'a3000000-0000-4000-8000-000000000005',
        (SELECT today_start + (seed_now - today_start) * 0.25 FROM ai_seed_clock),
        '오늘 등록 상품 집계 테스트용 상품',
        'AI_TEST_오늘 등록 상품',
        39000,
        'a1000000-0000-4000-8000-000000000001',
        'ai-test/today-product.webp',
        (SELECT seed_now FROM ai_seed_clock),
        NULL,
        'a2000000-0000-4000-8000-000000000003'
    )
ON CONFLICT (id) DO UPDATE
SET
    created_at = EXCLUDED.created_at,
    description = EXCLUDED.description,
    name = EXCLUDED.name,
    price = EXCLUDED.price,
    seller_id = EXCLUDED.seller_id,
    thumbnail_key = EXCLUDED.thumbnail_key,
    updated_at = EXCLUDED.updated_at,
    deleted_at = NULL,
    category_id = EXCLUDED.category_id;

INSERT INTO product.product_images (
    deleted_at,
    product_id,
    image_key,
    image_order
)
VALUES
    (NULL, 'a3000000-0000-4000-8000-000000000001', 'ai-test/sneakers-detail.webp', 0),
    (NULL, 'a3000000-0000-4000-8000-000000000002', 'ai-test/hoodie-detail.webp', 0)
ON CONFLICT (product_id, image_order) DO UPDATE
SET
    deleted_at = NULL,
    image_key = EXCLUDED.image_key;

INSERT INTO product.drops (
    id,
    close_at,
    created_at,
    drop_price,
    limit_per_user,
    open_at,
    status,
    total_quantity,
    updated_at,
    deleted_at,
    product_id
)
VALUES
    (
        'a4000000-0000-4000-8000-000000000001',
        (SELECT seed_now + INTERVAL '5 days' FROM ai_seed_clock),
        (SELECT today_start - INTERVAL '3 days' FROM ai_seed_clock),
        119000,
        2,
        (SELECT seed_now - INTERVAL '2 days' FROM ai_seed_clock),
        'REGISTERED',
        100,
        (SELECT seed_now FROM ai_seed_clock),
        NULL,
        'a3000000-0000-4000-8000-000000000001'
    ),
    (
        'a4000000-0000-4000-8000-000000000002',
        (SELECT seed_now + INTERVAL '2 days' FROM ai_seed_clock),
        (SELECT today_start - INTERVAL '4 days' FROM ai_seed_clock),
        79000,
        3,
        (SELECT seed_now - INTERVAL '3 days' FROM ai_seed_clock),
        'REGISTERED',
        5,
        (SELECT seed_now FROM ai_seed_clock),
        NULL,
        'a3000000-0000-4000-8000-000000000002'
    ),
    (
        'a4000000-0000-4000-8000-000000000003',
        (SELECT today_start - INTERVAL '20 days' FROM ai_seed_clock),
        (SELECT today_start - INTERVAL '35 days' FROM ai_seed_clock),
        45000,
        NULL,
        (SELECT today_start - INTERVAL '30 days' FROM ai_seed_clock),
        'CLOSE',
        50,
        (SELECT seed_now FROM ai_seed_clock),
        NULL,
        'a3000000-0000-4000-8000-000000000004'
    ),
    (
        'a4000000-0000-4000-8000-000000000004',
        (SELECT seed_now + INTERVAL '10 days' FROM ai_seed_clock),
        (SELECT today_start + (seed_now - today_start) * 0.20 FROM ai_seed_clock),
        35000,
        1,
        (SELECT seed_now + INTERVAL '3 days' FROM ai_seed_clock),
        'REGISTERED',
        30,
        (SELECT seed_now FROM ai_seed_clock),
        NULL,
        'a3000000-0000-4000-8000-000000000005'
    ),
    (
        'a4000000-0000-4000-8000-000000000005',
        NULL,
        (SELECT today_start + (seed_now - today_start) * 0.30 FROM ai_seed_clock),
        29000,
        NULL,
        (SELECT seed_now - INTERVAL '1 day' FROM ai_seed_clock),
        'REGISTERED',
        20,
        (SELECT seed_now FROM ai_seed_clock),
        NULL,
        'a3000000-0000-4000-8000-000000000003'
    )
ON CONFLICT (id) DO UPDATE
SET
    close_at = EXCLUDED.close_at,
    created_at = EXCLUDED.created_at,
    drop_price = EXCLUDED.drop_price,
    limit_per_user = EXCLUDED.limit_per_user,
    open_at = EXCLUDED.open_at,
    status = EXCLUDED.status,
    total_quantity = EXCLUDED.total_quantity,
    updated_at = EXCLUDED.updated_at,
    deleted_at = NULL,
    product_id = EXCLUDED.product_id;

INSERT INTO orders.orders (
    id,
    cancelled_at,
    completed_at,
    created_at,
    deleted_at,
    drop_id,
    fail_code,
    fail_message,
    idempotency_key,
    member_id,
    next_payment_status_check_at,
    order_number,
    paid_at,
    payment_expires_at,
    payment_id,
    payment_status_check_failed_at,
    payment_status_check_failure_count,
    product_id,
    product_name,
    quantity,
    refunded_at,
    saga_id,
    seller_id,
    status,
    total_price,
    unit_price,
    updated_at,
    version
)
VALUES
    (
        'a5000000-0000-4000-8000-000000000001',
        NULL,
        (SELECT today_start + (seed_now - today_start) * 0.12 FROM ai_seed_clock),
        (SELECT today_start + (seed_now - today_start) * 0.10 FROM ai_seed_clock),
        NULL,
        'a4000000-0000-4000-8000-000000000002',
        NULL,
        NULL,
        'AI_TEST_ORDER_0001',
        'a1000000-0000-4000-8000-000000000002',
        NULL,
        'ORD-AI-0001',
        (SELECT today_start + (seed_now - today_start) * 0.11 FROM ai_seed_clock),
        NULL,
        'a8000000-0000-4000-8000-000000000001',
        NULL,
        0,
        'a3000000-0000-4000-8000-000000000002',
        'AI_TEST_드롭 후드',
        2,
        NULL,
        'AI-SAGA-0001',
        'a1000000-0000-4000-8000-000000000001',
        'COMPLETED',
        158000,
        79000,
        (SELECT today_start + (seed_now - today_start) * 0.12 FROM ai_seed_clock),
        1
    ),
    (
        'a5000000-0000-4000-8000-000000000002',
        NULL,
        NULL,
        (SELECT today_start + (seed_now - today_start) * 0.20 FROM ai_seed_clock),
        NULL,
        'a4000000-0000-4000-8000-000000000001',
        NULL,
        NULL,
        'AI_TEST_ORDER_0002',
        'a1000000-0000-4000-8000-000000000003',
        (SELECT seed_now + INTERVAL '7 days' FROM ai_seed_clock),
        'ORD-AI-0002',
        NULL,
        (SELECT seed_now - INTERVAL '1 minute' FROM ai_seed_clock),
        'a8000000-0000-4000-8000-000000000002',
        NULL,
        0,
        'a3000000-0000-4000-8000-000000000001',
        'AI_TEST_한정 스니커즈',
        1,
        NULL,
        'AI-SAGA-0002',
        'a1000000-0000-4000-8000-000000000001',
        'PAYMENT_PENDING',
        119000,
        119000,
        (SELECT seed_now - INTERVAL '1 minute' FROM ai_seed_clock),
        1
    ),
    (
        'a5000000-0000-4000-8000-000000000003',
        NULL,
        NULL,
        (SELECT today_start + (seed_now - today_start) * 0.30 FROM ai_seed_clock),
        NULL,
        'a4000000-0000-4000-8000-000000000001',
        'PAYMENT_FAILED',
        'AI 테스트 결제 실패',
        'AI_TEST_ORDER_0003',
        'a1000000-0000-4000-8000-000000000004',
        NULL,
        'ORD-AI-0003',
        NULL,
        NULL,
        'a8000000-0000-4000-8000-000000000003',
        NULL,
        1,
        'a3000000-0000-4000-8000-000000000001',
        'AI_TEST_한정 스니커즈',
        1,
        NULL,
        'AI-SAGA-0003',
        'a1000000-0000-4000-8000-000000000001',
        'FAILED',
        119000,
        119000,
        (SELECT today_start + (seed_now - today_start) * 0.32 FROM ai_seed_clock),
        1
    ),
    (
        'a5000000-0000-4000-8000-000000000004',
        NULL,
        (SELECT today_start + (seed_now - today_start) * 0.42 FROM ai_seed_clock),
        (SELECT today_start + (seed_now - today_start) * 0.40 FROM ai_seed_clock),
        NULL,
        'a4000000-0000-4000-8000-000000000005',
        NULL,
        NULL,
        'AI_TEST_ORDER_0004',
        'a1000000-0000-4000-8000-000000000005',
        NULL,
        'ORD-AI-0004',
        (SELECT today_start + (seed_now - today_start) * 0.41 FROM ai_seed_clock),
        NULL,
        'a8000000-0000-4000-8000-000000000004',
        NULL,
        0,
        'a3000000-0000-4000-8000-000000000003',
        'AI_TEST_설명 미등록 상품',
        1,
        (SELECT today_start + (seed_now - today_start) * 0.45 FROM ai_seed_clock),
        'AI-SAGA-0004',
        'a1000000-0000-4000-8000-000000000001',
        'REFUNDED',
        29000,
        29000,
        (SELECT today_start + (seed_now - today_start) * 0.45 FROM ai_seed_clock),
        2
    ),
    (
        'a5000000-0000-4000-8000-000000000005',
        NULL,
        (SELECT today_start + (seed_now - today_start) * 0.52 FROM ai_seed_clock),
        (SELECT today_start + (seed_now - today_start) * 0.50 FROM ai_seed_clock),
        NULL,
        'a4000000-0000-4000-8000-000000000002',
        NULL,
        NULL,
        'AI_TEST_ORDER_0005',
        'a1000000-0000-4000-8000-000000000002',
        NULL,
        'ORD-AI-0005',
        (SELECT today_start + (seed_now - today_start) * 0.51 FROM ai_seed_clock),
        NULL,
        'a8000000-0000-4000-8000-000000000005',
        NULL,
        0,
        'a3000000-0000-4000-8000-000000000002',
        'AI_TEST_드롭 후드',
        3,
        NULL,
        'AI-SAGA-0005',
        'a1000000-0000-4000-8000-000000000001',
        'COMPLETED',
        237000,
        79000,
        (SELECT today_start + (seed_now - today_start) * 0.52 FROM ai_seed_clock),
        1
    ),
    (
        'a5000000-0000-4000-8000-000000000006',
        NULL,
        (SELECT today_start - INTERVAL '1 day' + INTERVAL '12 hours' FROM ai_seed_clock),
        (SELECT today_start - INTERVAL '1 day' + INTERVAL '11 hours' FROM ai_seed_clock),
        NULL,
        'a4000000-0000-4000-8000-000000000001',
        NULL,
        NULL,
        'AI_TEST_ORDER_0006',
        'a1000000-0000-4000-8000-000000000003',
        NULL,
        'ORD-AI-0006',
        (SELECT today_start - INTERVAL '1 day' + INTERVAL '11 hours 10 minutes' FROM ai_seed_clock),
        NULL,
        'a8000000-0000-4000-8000-000000000006',
        NULL,
        0,
        'a3000000-0000-4000-8000-000000000001',
        'AI_TEST_한정 스니커즈',
        2,
        NULL,
        'AI-SAGA-0006',
        'a1000000-0000-4000-8000-000000000001',
        'COMPLETED',
        238000,
        119000,
        (SELECT today_start - INTERVAL '1 day' + INTERVAL '12 hours' FROM ai_seed_clock),
        1
    ),
    (
        'a5000000-0000-4000-8000-000000000007',
        NULL,
        (SELECT today_start - INTERVAL '7 days' + INTERVAL '10 hours' FROM ai_seed_clock),
        (SELECT today_start - INTERVAL '7 days' + INTERVAL '9 hours' FROM ai_seed_clock),
        NULL,
        'a4000000-0000-4000-8000-000000000003',
        NULL,
        NULL,
        'AI_TEST_ORDER_0007',
        'a1000000-0000-4000-8000-000000000004',
        NULL,
        'ORD-AI-0007',
        (SELECT today_start - INTERVAL '7 days' + INTERVAL '9 hours 10 minutes' FROM ai_seed_clock),
        NULL,
        'a8000000-0000-4000-8000-000000000007',
        NULL,
        0,
        'a3000000-0000-4000-8000-000000000004',
        'AI_TEST_지난달 상품',
        1,
        NULL,
        'AI-SAGA-0007',
        'a1000000-0000-4000-8000-000000000001',
        'COMPLETED',
        45000,
        45000,
        (SELECT today_start - INTERVAL '7 days' + INTERVAL '10 hours' FROM ai_seed_clock),
        1
    ),
    (
        'a5000000-0000-4000-8000-000000000008',
        NULL,
        (SELECT previous_month_start + INTERVAL '10 days 11 hours' FROM ai_seed_clock),
        (SELECT previous_month_start + INTERVAL '10 days 10 hours' FROM ai_seed_clock),
        NULL,
        'a4000000-0000-4000-8000-000000000003',
        NULL,
        NULL,
        'AI_TEST_ORDER_0008',
        'a1000000-0000-4000-8000-000000000005',
        NULL,
        'ORD-AI-0008',
        (SELECT previous_month_start + INTERVAL '10 days 10 hours 10 minutes' FROM ai_seed_clock),
        NULL,
        'a8000000-0000-4000-8000-000000000008',
        NULL,
        0,
        'a3000000-0000-4000-8000-000000000004',
        'AI_TEST_지난달 상품',
        2,
        NULL,
        'AI-SAGA-0008',
        'a1000000-0000-4000-8000-000000000001',
        'COMPLETED',
        90000,
        45000,
        (SELECT previous_month_start + INTERVAL '10 days 11 hours' FROM ai_seed_clock),
        1
    ),
    (
        'a5000000-0000-4000-8000-000000000009',
        (SELECT today_start + (seed_now - today_start) * 0.62 FROM ai_seed_clock),
        NULL,
        (SELECT today_start + (seed_now - today_start) * 0.60 FROM ai_seed_clock),
        NULL,
        'a4000000-0000-4000-8000-000000000005',
        NULL,
        NULL,
        'AI_TEST_ORDER_0009',
        'a1000000-0000-4000-8000-000000000003',
        NULL,
        'ORD-AI-0009',
        NULL,
        NULL,
        'a8000000-0000-4000-8000-000000000009',
        NULL,
        0,
        'a3000000-0000-4000-8000-000000000003',
        'AI_TEST_설명 미등록 상품',
        1,
        NULL,
        'AI-SAGA-0009',
        'a1000000-0000-4000-8000-000000000001',
        'CANCELLED',
        29000,
        29000,
        (SELECT today_start + (seed_now - today_start) * 0.62 FROM ai_seed_clock),
        1
    ),
    (
        'a5000000-0000-4000-8000-000000000010',
        NULL,
        (SELECT today_start + (seed_now - today_start) * 0.72 FROM ai_seed_clock),
        (SELECT today_start + (seed_now - today_start) * 0.70 FROM ai_seed_clock),
        NULL,
        'a4000000-0000-4000-8000-000000000001',
        NULL,
        NULL,
        'AI_TEST_ORDER_SAGA_0001',
        'a1000000-0000-4000-8000-000000000004',
        NULL,
        'ORD-AI-SAGA-0001',
        (SELECT today_start + (seed_now - today_start) * 0.71 FROM ai_seed_clock),
        NULL,
        'a8000000-0000-4000-8000-000000000010',
        NULL,
        0,
        'a3000000-0000-4000-8000-000000000001',
        'AI_TEST_한정 스니커즈',
        1,
        NULL,
        'AI-SAGA-CURRENT-0001',
        'a1000000-0000-4000-8000-000000000001',
        'REFUND_PENDING',
        119000,
        119000,
        (SELECT today_start + (seed_now - today_start) * 0.75 FROM ai_seed_clock),
        3
    ),
    (
        'a5000000-0000-4000-8000-000000000011',
        NULL,
        NULL,
        (SELECT today_start + (seed_now - today_start) * 0.80 FROM ai_seed_clock),
        NULL,
        'a4000000-0000-4000-8000-000000000001',
        NULL,
        NULL,
        'AI_TEST_ORDER_0011',
        'a1000000-0000-4000-8000-000000000005',
        (SELECT seed_now + INTERVAL '1 hour' FROM ai_seed_clock),
        'ORD-AI-0011',
        NULL,
        (SELECT seed_now + INTERVAL '1 hour' FROM ai_seed_clock),
        'a8000000-0000-4000-8000-000000000011',
        NULL,
        0,
        'a3000000-0000-4000-8000-000000000001',
        'AI_TEST_한정 스니커즈',
        1,
        NULL,
        'AI-SAGA-0011',
        'a1000000-0000-4000-8000-000000000001',
        'PAYMENT_PENDING',
        119000,
        119000,
        (SELECT today_start + (seed_now - today_start) * 0.80 FROM ai_seed_clock),
        1
    ),
    (
        'a5000000-0000-4000-8000-000000000012',
        NULL,
        (SELECT today_start + (seed_now - today_start) * 0.92 FROM ai_seed_clock),
        (SELECT today_start + (seed_now - today_start) * 0.90 FROM ai_seed_clock),
        NULL,
        'a4000000-0000-4000-8000-000000000001',
        NULL,
        NULL,
        'AI_TEST_ORDER_0012',
        'a1000000-0000-4000-8000-000000000002',
        NULL,
        'ORD-AI-0012',
        (SELECT today_start + (seed_now - today_start) * 0.91 FROM ai_seed_clock),
        NULL,
        'a8000000-0000-4000-8000-000000000012',
        NULL,
        0,
        'a3000000-0000-4000-8000-000000000001',
        'AI_TEST_한정 스니커즈',
        1,
        NULL,
        'AI-SAGA-0012',
        'a1000000-0000-4000-8000-000000000001',
        'COMPLETED',
        119000,
        119000,
        (SELECT today_start + (seed_now - today_start) * 0.92 FROM ai_seed_clock),
        1
    )
ON CONFLICT (id) DO UPDATE
SET
    cancelled_at = EXCLUDED.cancelled_at,
    completed_at = EXCLUDED.completed_at,
    created_at = EXCLUDED.created_at,
    deleted_at = NULL,
    drop_id = EXCLUDED.drop_id,
    fail_code = EXCLUDED.fail_code,
    fail_message = EXCLUDED.fail_message,
    idempotency_key = EXCLUDED.idempotency_key,
    member_id = EXCLUDED.member_id,
    next_payment_status_check_at = EXCLUDED.next_payment_status_check_at,
    order_number = EXCLUDED.order_number,
    paid_at = EXCLUDED.paid_at,
    payment_expires_at = EXCLUDED.payment_expires_at,
    payment_id = EXCLUDED.payment_id,
    payment_status_check_failed_at = EXCLUDED.payment_status_check_failed_at,
    payment_status_check_failure_count = EXCLUDED.payment_status_check_failure_count,
    product_id = EXCLUDED.product_id,
    product_name = EXCLUDED.product_name,
    quantity = EXCLUDED.quantity,
    refunded_at = EXCLUDED.refunded_at,
    saga_id = EXCLUDED.saga_id,
    seller_id = EXCLUDED.seller_id,
    status = EXCLUDED.status,
    total_price = EXCLUDED.total_price,
    unit_price = EXCLUDED.unit_price,
    updated_at = EXCLUDED.updated_at,
    version = EXCLUDED.version;

INSERT INTO orders.order_histories (
    id,
    created_at,
    new_status,
    order_id,
    previous_status,
    reason_code,
    reason_message,
    source_event_key
)
VALUES
    (
        'a6000000-0000-4000-8000-000000000001',
        (SELECT today_start + (seed_now - today_start) * 0.70 FROM ai_seed_clock),
        'PAYMENT_PENDING',
        'a5000000-0000-4000-8000-000000000010',
        NULL,
        NULL,
        NULL,
        'AI_TEST_SAGA_EVENT_1'
    ),
    (
        'a6000000-0000-4000-8000-000000000002',
        (SELECT today_start + (seed_now - today_start) * 0.71 FROM ai_seed_clock),
        'COMPLETED',
        'a5000000-0000-4000-8000-000000000010',
        'PAYMENT_PENDING',
        NULL,
        NULL,
        'AI_TEST_SAGA_EVENT_2'
    ),
    (
        'a6000000-0000-4000-8000-000000000003',
        (SELECT today_start + (seed_now - today_start) * 0.73 FROM ai_seed_clock),
        'CANCEL_REQUESTED',
        'a5000000-0000-4000-8000-000000000010',
        'COMPLETED',
        'ADMIN_CANCEL_REQUEST',
        '관리자 챗봇 사가 조회 테스트',
        'AI_TEST_SAGA_EVENT_3'
    ),
    (
        'a6000000-0000-4000-8000-000000000004',
        (SELECT today_start + (seed_now - today_start) * 0.75 FROM ai_seed_clock),
        'REFUND_PENDING',
        'a5000000-0000-4000-8000-000000000010',
        'CANCEL_REQUESTED',
        'REFUND_REQUESTED',
        '환불 처리 대기',
        'AI_TEST_SAGA_EVENT_4'
    ),
    (
        'a6000000-0000-4000-8000-000000000005',
        (SELECT today_start + (seed_now - today_start) * 0.20 FROM ai_seed_clock),
        'PAYMENT_PENDING',
        'a5000000-0000-4000-8000-000000000002',
        NULL,
        NULL,
        NULL,
        'AI_TEST_PENDING_EVENT'
    ),
    (
        'a6000000-0000-4000-8000-000000000006',
        (SELECT today_start + (seed_now - today_start) * 0.30 FROM ai_seed_clock),
        'PAYMENT_PENDING',
        'a5000000-0000-4000-8000-000000000003',
        NULL,
        NULL,
        NULL,
        'AI_TEST_FAILED_EVENT_1'
    ),
    (
        'a6000000-0000-4000-8000-000000000007',
        (SELECT today_start + (seed_now - today_start) * 0.32 FROM ai_seed_clock),
        'FAILED',
        'a5000000-0000-4000-8000-000000000003',
        'PAYMENT_PENDING',
        'PAYMENT_FAILED',
        'AI 테스트 결제 실패',
        'AI_TEST_FAILED_EVENT_2'
    )
ON CONFLICT (id) DO UPDATE
SET
    created_at = EXCLUDED.created_at,
    new_status = EXCLUDED.new_status,
    order_id = EXCLUDED.order_id,
    previous_status = EXCLUDED.previous_status,
    reason_code = EXCLUDED.reason_code,
    reason_message = EXCLUDED.reason_message,
    source_event_key = EXCLUDED.source_event_key;

INSERT INTO orders.order_saga_states (
    id,
    order_id,
    saga_id,
    current_step,
    compensating_since,
    created_at,
    updated_at
)
VALUES (
    'a7000000-0000-4000-8000-000000000001',
    'a5000000-0000-4000-8000-000000000010',
    'AI-SAGA-CURRENT-0001',
    'PAYMENT_REFUND_PENDING',
    (SELECT today_start + (seed_now - today_start) * 0.74 FROM ai_seed_clock),
    (SELECT today_start + (seed_now - today_start) * 0.70 FROM ai_seed_clock),
    (SELECT today_start + (seed_now - today_start) * 0.75 FROM ai_seed_clock)
)
ON CONFLICT (id) DO UPDATE
SET
    order_id = EXCLUDED.order_id,
    saga_id = EXCLUDED.saga_id,
    current_step = EXCLUDED.current_step,
    compensating_since = EXCLUDED.compensating_since,
    created_at = EXCLUDED.created_at,
    updated_at = EXCLUDED.updated_at;

INSERT INTO product.stock_histories (
    id,
    buyer_id,
    change_type,
    created_at,
    drop_id,
    order_id,
    quantity_delta
)
VALUES
    (
        'b1000000-0000-4000-8000-000000000001',
        'a1000000-0000-4000-8000-000000000002',
        'DEDUCT',
        (SELECT today_start + (seed_now - today_start) * 0.10 FROM ai_seed_clock),
        'a4000000-0000-4000-8000-000000000002',
        'a5000000-0000-4000-8000-000000000001',
        -2
    ),
    (
        'b1000000-0000-4000-8000-000000000002',
        'a1000000-0000-4000-8000-000000000002',
        'DEDUCT',
        (SELECT today_start + (seed_now - today_start) * 0.50 FROM ai_seed_clock),
        'a4000000-0000-4000-8000-000000000002',
        'a5000000-0000-4000-8000-000000000005',
        -3
    ),
    (
        'b1000000-0000-4000-8000-000000000003',
        'a1000000-0000-4000-8000-000000000003',
        'DEDUCT',
        (SELECT today_start - INTERVAL '1 day' + INTERVAL '11 hours' FROM ai_seed_clock),
        'a4000000-0000-4000-8000-000000000001',
        'a5000000-0000-4000-8000-000000000006',
        -2
    ),
    (
        'b1000000-0000-4000-8000-000000000004',
        'a1000000-0000-4000-8000-000000000004',
        'DEDUCT',
        (SELECT today_start + (seed_now - today_start) * 0.30 FROM ai_seed_clock),
        'a4000000-0000-4000-8000-000000000001',
        'a5000000-0000-4000-8000-000000000003',
        -1
    ),
    (
        'b1000000-0000-4000-8000-000000000005',
        'a1000000-0000-4000-8000-000000000004',
        'ROLLBACK',
        (SELECT today_start + (seed_now - today_start) * 0.32 FROM ai_seed_clock),
        'a4000000-0000-4000-8000-000000000001',
        'a5000000-0000-4000-8000-000000000003',
        1
    )
ON CONFLICT (id) DO UPDATE
SET
    buyer_id = EXCLUDED.buyer_id,
    change_type = EXCLUDED.change_type,
    created_at = EXCLUDED.created_at,
    drop_id = EXCLUDED.drop_id,
    order_id = EXCLUDED.order_id,
    quantity_delta = EXCLUDED.quantity_delta;

INSERT INTO payment.payments (
    id,
    order_id,
    member_id,
    seller_id,
    product_id,
    amount,
    method,
    pg_provider,
    pg_payment_key,
    pg_tx_id,
    status,
    refunded_amount,
    idempotency_key,
    approved_at,
    created_at,
    updated_at,
    request_hash,
    pg_payment_key_hash,
    pg_recon_status,
    pg_reconciled_at
)
SELECT
    payment_id,
    order_id,
    member_id,
    seller_id,
    product_id,
    amount,
    'PG',
    'AI_TEST_PG',
    'AI_TEST_KEY_' || order_number,
    'AI_TEST_TX_' || order_number,
    payment_status,
    refunded_amount,
    'AI_TEST_PAYMENT_' || order_number,
    approved_at,
    created_at AT TIME ZONE 'Asia/Seoul',
    updated_at AT TIME ZONE 'Asia/Seoul',
    'AI_TEST_REQUEST_HASH',
    'AI_TEST_KEY_HASH_' || order_number,
    'MATCHED',
    updated_at AT TIME ZONE 'Asia/Seoul'
FROM (
    VALUES
        (
            'a8000000-0000-4000-8000-000000000001'::uuid,
            'a5000000-0000-4000-8000-000000000001'::uuid,
            'a1000000-0000-4000-8000-000000000002'::uuid,
            'a1000000-0000-4000-8000-000000000001'::uuid,
            'a3000000-0000-4000-8000-000000000002'::uuid,
            158000::bigint,
            'ORD-AI-0001',
            'APPROVED',
            0::bigint,
            (SELECT today_start + (seed_now - today_start) * 0.11 FROM ai_seed_clock),
            (SELECT today_start + (seed_now - today_start) * 0.10 FROM ai_seed_clock),
            (SELECT today_start + (seed_now - today_start) * 0.12 FROM ai_seed_clock)
        ),
        (
            'a8000000-0000-4000-8000-000000000002'::uuid,
            'a5000000-0000-4000-8000-000000000002'::uuid,
            'a1000000-0000-4000-8000-000000000003'::uuid,
            'a1000000-0000-4000-8000-000000000001'::uuid,
            'a3000000-0000-4000-8000-000000000001'::uuid,
            119000::bigint,
            'ORD-AI-0002',
            'PAYMENT_PENDING',
            0::bigint,
            NULL::timestamptz,
            (SELECT today_start + (seed_now - today_start) * 0.20 FROM ai_seed_clock),
            (SELECT seed_now - INTERVAL '1 minute' FROM ai_seed_clock)
        ),
        (
            'a8000000-0000-4000-8000-000000000003'::uuid,
            'a5000000-0000-4000-8000-000000000003'::uuid,
            'a1000000-0000-4000-8000-000000000004'::uuid,
            'a1000000-0000-4000-8000-000000000001'::uuid,
            'a3000000-0000-4000-8000-000000000001'::uuid,
            119000::bigint,
            'ORD-AI-0003',
            'FAILED',
            0::bigint,
            NULL::timestamptz,
            (SELECT today_start + (seed_now - today_start) * 0.30 FROM ai_seed_clock),
            (SELECT today_start + (seed_now - today_start) * 0.32 FROM ai_seed_clock)
        ),
        (
            'a8000000-0000-4000-8000-000000000004'::uuid,
            'a5000000-0000-4000-8000-000000000004'::uuid,
            'a1000000-0000-4000-8000-000000000005'::uuid,
            'a1000000-0000-4000-8000-000000000001'::uuid,
            'a3000000-0000-4000-8000-000000000003'::uuid,
            29000::bigint,
            'ORD-AI-0004',
            'REFUNDED',
            29000::bigint,
            (SELECT today_start + (seed_now - today_start) * 0.41 FROM ai_seed_clock),
            (SELECT today_start + (seed_now - today_start) * 0.40 FROM ai_seed_clock),
            (SELECT today_start + (seed_now - today_start) * 0.45 FROM ai_seed_clock)
        ),
        (
            'a8000000-0000-4000-8000-000000000005'::uuid,
            'a5000000-0000-4000-8000-000000000005'::uuid,
            'a1000000-0000-4000-8000-000000000002'::uuid,
            'a1000000-0000-4000-8000-000000000001'::uuid,
            'a3000000-0000-4000-8000-000000000002'::uuid,
            237000::bigint,
            'ORD-AI-0005',
            'APPROVED',
            0::bigint,
            (SELECT today_start + (seed_now - today_start) * 0.51 FROM ai_seed_clock),
            (SELECT today_start + (seed_now - today_start) * 0.50 FROM ai_seed_clock),
            (SELECT today_start + (seed_now - today_start) * 0.52 FROM ai_seed_clock)
        ),
        (
            'a8000000-0000-4000-8000-000000000010'::uuid,
            'a5000000-0000-4000-8000-000000000010'::uuid,
            'a1000000-0000-4000-8000-000000000004'::uuid,
            'a1000000-0000-4000-8000-000000000001'::uuid,
            'a3000000-0000-4000-8000-000000000001'::uuid,
            119000::bigint,
            'ORD-AI-SAGA-0001',
            'APPROVED',
            0::bigint,
            (SELECT today_start + (seed_now - today_start) * 0.71 FROM ai_seed_clock),
            (SELECT today_start + (seed_now - today_start) * 0.70 FROM ai_seed_clock),
            (SELECT today_start + (seed_now - today_start) * 0.75 FROM ai_seed_clock)
        ),
        (
            'a8000000-0000-4000-8000-000000000012'::uuid,
            'a5000000-0000-4000-8000-000000000012'::uuid,
            'a1000000-0000-4000-8000-000000000002'::uuid,
            'a1000000-0000-4000-8000-000000000001'::uuid,
            'a3000000-0000-4000-8000-000000000001'::uuid,
            119000::bigint,
            'ORD-AI-0012',
            'APPROVED',
            0::bigint,
            (SELECT today_start + (seed_now - today_start) * 0.91 FROM ai_seed_clock),
            (SELECT today_start + (seed_now - today_start) * 0.90 FROM ai_seed_clock),
            (SELECT today_start + (seed_now - today_start) * 0.92 FROM ai_seed_clock)
        )
) AS seeded_payment (
    payment_id,
    order_id,
    member_id,
    seller_id,
    product_id,
    amount,
    order_number,
    payment_status,
    refunded_amount,
    approved_at,
    created_at,
    updated_at
)
ON CONFLICT (id) DO UPDATE
SET
    order_id = EXCLUDED.order_id,
    member_id = EXCLUDED.member_id,
    seller_id = EXCLUDED.seller_id,
    product_id = EXCLUDED.product_id,
    amount = EXCLUDED.amount,
    method = EXCLUDED.method,
    pg_provider = EXCLUDED.pg_provider,
    pg_payment_key = EXCLUDED.pg_payment_key,
    pg_tx_id = EXCLUDED.pg_tx_id,
    status = EXCLUDED.status,
    refunded_amount = EXCLUDED.refunded_amount,
    idempotency_key = EXCLUDED.idempotency_key,
    approved_at = EXCLUDED.approved_at,
    created_at = EXCLUDED.created_at,
    updated_at = EXCLUDED.updated_at,
    request_hash = EXCLUDED.request_hash,
    pg_payment_key_hash = EXCLUDED.pg_payment_key_hash,
    pg_recon_status = EXCLUDED.pg_recon_status,
    pg_reconciled_at = EXCLUDED.pg_reconciled_at;

INSERT INTO payment.payment_events (
    id,
    payment_id,
    type,
    amount,
    created_at
)
VALUES
    (
        'a9000000-0000-4000-8000-000000000001',
        'a8000000-0000-4000-8000-000000000001',
        'APPROVE',
        158000,
        (SELECT (today_start + (seed_now - today_start) * 0.11) AT TIME ZONE 'Asia/Seoul' FROM ai_seed_clock)
    ),
    (
        'a9000000-0000-4000-8000-000000000002',
        'a8000000-0000-4000-8000-000000000004',
        'APPROVE',
        29000,
        (SELECT (today_start + (seed_now - today_start) * 0.41) AT TIME ZONE 'Asia/Seoul' FROM ai_seed_clock)
    ),
    (
        'a9000000-0000-4000-8000-000000000003',
        'a8000000-0000-4000-8000-000000000004',
        'REFUND',
        29000,
        (SELECT (today_start + (seed_now - today_start) * 0.45) AT TIME ZONE 'Asia/Seoul' FROM ai_seed_clock)
    )
ON CONFLICT (id) DO UPDATE
SET
    payment_id = EXCLUDED.payment_id,
    type = EXCLUDED.type,
    amount = EXCLUDED.amount,
    created_at = EXCLUDED.created_at;

INSERT INTO payment.refunds (
    id,
    payment_id,
    amount,
    status,
    reason,
    pg_refund_key,
    idempotency_key,
    completed_at,
    created_at,
    request_hash,
    pg_recon_status,
    pg_reconciled_at
)
VALUES (
    'aa000000-0000-4000-8000-000000000001',
    'a8000000-0000-4000-8000-000000000004',
    29000,
    'COMPLETE',
    'AI_TEST_관리자 테스트 환불',
    'AI_TEST_REFUND_KEY_0001',
    'AI_TEST_REFUND_0001',
    (SELECT (today_start + (seed_now - today_start) * 0.45) AT TIME ZONE 'Asia/Seoul' FROM ai_seed_clock),
    (SELECT (today_start + (seed_now - today_start) * 0.43) AT TIME ZONE 'Asia/Seoul' FROM ai_seed_clock),
    'AI_TEST_REFUND_HASH',
    'MATCHED',
    (SELECT seed_now AT TIME ZONE 'Asia/Seoul' FROM ai_seed_clock)
)
ON CONFLICT (id) DO UPDATE
SET
    payment_id = EXCLUDED.payment_id,
    amount = EXCLUDED.amount,
    status = EXCLUDED.status,
    reason = EXCLUDED.reason,
    pg_refund_key = EXCLUDED.pg_refund_key,
    idempotency_key = EXCLUDED.idempotency_key,
    completed_at = EXCLUDED.completed_at,
    created_at = EXCLUDED.created_at,
    request_hash = EXCLUDED.request_hash,
    pg_recon_status = EXCLUDED.pg_recon_status,
    pg_reconciled_at = EXCLUDED.pg_reconciled_at;

INSERT INTO settlement.settlement_batchs (
    batch_id,
    batch_type,
    created_at,
    ended_at,
    fail_reason,
    settlement_month,
    started_at,
    status,
    total_order_count,
    total_seller_count,
    total_settlement_amount
)
VALUES (
    'ab000000-0000-4000-8000-000000000001',
    'SETTLEMENT_RUN',
    (SELECT (previous_month_start + INTERVAL '1 month 1 day') AT TIME ZONE 'Asia/Seoul' FROM ai_seed_clock),
    (SELECT (previous_month_start + INTERVAL '1 month 1 day 10 minutes') AT TIME ZONE 'Asia/Seoul' FROM ai_seed_clock),
    NULL,
    (SELECT to_char(previous_month_start, 'YYYYMM') FROM ai_seed_clock),
    (SELECT (previous_month_start + INTERVAL '1 month 1 day 1 minute') AT TIME ZONE 'Asia/Seoul' FROM ai_seed_clock),
    'COMPLETED',
    6,
    5,
    731500
)
ON CONFLICT (batch_id) DO UPDATE
SET
    batch_type = EXCLUDED.batch_type,
    created_at = EXCLUDED.created_at,
    ended_at = EXCLUDED.ended_at,
    fail_reason = EXCLUDED.fail_reason,
    settlement_month = EXCLUDED.settlement_month,
    started_at = EXCLUDED.started_at,
    status = EXCLUDED.status,
    total_order_count = EXCLUDED.total_order_count,
    total_seller_count = EXCLUDED.total_seller_count,
    total_settlement_amount = EXCLUDED.total_settlement_amount;

INSERT INTO settlement.seller_settlements (
    seller_settlement_id,
    batch_id,
    completed_at,
    fail_reason,
    failed_at,
    final_settlement_amount,
    seller_id,
    settlement_month,
    status,
    total_adjustment_amount,
    total_fee_amount,
    total_order_count,
    total_paid_amount,
    total_refund_amount
)
VALUES
    (
        'ac000000-0000-4000-8000-000000000001',
        'ab000000-0000-4000-8000-000000000001',
        (SELECT (previous_month_start + INTERVAL '1 month 1 day 9 minutes') AT TIME ZONE 'Asia/Seoul' FROM ai_seed_clock),
        NULL,
        NULL,
        228000,
        'a1000000-0000-4000-8000-000000000001',
        (SELECT to_char(previous_month_start, 'YYYYMM') FROM ai_seed_clock),
        'COMPLETED',
        0,
        12000,
        2,
        240000,
        0
    ),
    (
        'ac000000-0000-4000-8000-000000000002',
        'ab000000-0000-4000-8000-000000000001',
        (SELECT (previous_month_start + INTERVAL '1 month 1 day 9 minutes') AT TIME ZONE 'Asia/Seoul' FROM ai_seed_clock),
        NULL,
        NULL,
        76000,
        'ac100000-0000-4000-8000-000000000002',
        (SELECT to_char(previous_month_start, 'YYYYMM') FROM ai_seed_clock),
        'COMPLETED',
        0,
        4000,
        1,
        80000,
        0
    ),
    (
        'ac000000-0000-4000-8000-000000000003',
        'ab000000-0000-4000-8000-000000000001',
        (SELECT (previous_month_start + INTERVAL '1 month 1 day 9 minutes') AT TIME ZONE 'Asia/Seoul' FROM ai_seed_clock),
        NULL,
        NULL,
        114000,
        'ac100000-0000-4000-8000-000000000003',
        (SELECT to_char(previous_month_start, 'YYYYMM') FROM ai_seed_clock),
        'COMPLETED',
        0,
        6000,
        1,
        120000,
        0
    ),
    (
        'ac000000-0000-4000-8000-000000000004',
        'ab000000-0000-4000-8000-000000000001',
        (SELECT (previous_month_start + INTERVAL '1 month 1 day 9 minutes') AT TIME ZONE 'Asia/Seoul' FROM ai_seed_clock),
        NULL,
        NULL,
        142500,
        'ac100000-0000-4000-8000-000000000004',
        (SELECT to_char(previous_month_start, 'YYYYMM') FROM ai_seed_clock),
        'COMPLETED',
        0,
        7500,
        1,
        150000,
        0
    ),
    (
        'ac000000-0000-4000-8000-000000000005',
        'ab000000-0000-4000-8000-000000000001',
        (SELECT (previous_month_start + INTERVAL '1 month 1 day 9 minutes') AT TIME ZONE 'Asia/Seoul' FROM ai_seed_clock),
        NULL,
        NULL,
        171000,
        'ac100000-0000-4000-8000-000000000005',
        (SELECT to_char(previous_month_start, 'YYYYMM') FROM ai_seed_clock),
        'COMPLETED',
        0,
        9000,
        1,
        180000,
        0
    )
ON CONFLICT (seller_settlement_id) DO UPDATE
SET
    batch_id = EXCLUDED.batch_id,
    completed_at = EXCLUDED.completed_at,
    fail_reason = EXCLUDED.fail_reason,
    failed_at = EXCLUDED.failed_at,
    final_settlement_amount = EXCLUDED.final_settlement_amount,
    seller_id = EXCLUDED.seller_id,
    settlement_month = EXCLUDED.settlement_month,
    status = EXCLUDED.status,
    total_adjustment_amount = EXCLUDED.total_adjustment_amount,
    total_fee_amount = EXCLUDED.total_fee_amount,
    total_order_count = EXCLUDED.total_order_count,
    total_paid_amount = EXCLUDED.total_paid_amount,
    total_refund_amount = EXCLUDED.total_refund_amount;

INSERT INTO settlement.settlement_orders (
    settlement_order_id,
    buyer_id,
    fee_amount,
    net_settlement_amount,
    order_amount,
    order_id,
    paid_amount,
    paid_at,
    payment_id,
    product_id,
    refund_amount,
    seller_id,
    seller_settlement_id,
    settlement_month,
    settlement_status
)
VALUES
    (
        'ad000000-0000-4000-8000-000000000001',
        'a1000000-0000-4000-8000-000000000005',
        4500,
        85500,
        90000,
        'a5000000-0000-4000-8000-000000000008',
        90000,
        (SELECT (previous_month_start + INTERVAL '10 days 10 hours 10 minutes') AT TIME ZONE 'Asia/Seoul' FROM ai_seed_clock),
        'a8000000-0000-4000-8000-000000000008',
        'a3000000-0000-4000-8000-000000000004',
        0,
        'a1000000-0000-4000-8000-000000000001',
        'ac000000-0000-4000-8000-000000000001',
        (SELECT to_char(previous_month_start, 'YYYYMM') FROM ai_seed_clock),
        'COMPLETED'
    ),
    (
        'ad000000-0000-4000-8000-000000000002',
        'a1000000-0000-4000-8000-000000000003',
        7500,
        142500,
        150000,
        'a5000000-0000-4000-8000-000000000007',
        150000,
        (SELECT (previous_month_start + INTERVAL '20 days 10 hours') AT TIME ZONE 'Asia/Seoul' FROM ai_seed_clock),
        'a8000000-0000-4000-8000-000000000007',
        'a3000000-0000-4000-8000-000000000004',
        0,
        'a1000000-0000-4000-8000-000000000001',
        'ac000000-0000-4000-8000-000000000001',
        (SELECT to_char(previous_month_start, 'YYYYMM') FROM ai_seed_clock),
        'COMPLETED'
    )
ON CONFLICT (settlement_order_id) DO UPDATE
SET
    buyer_id = EXCLUDED.buyer_id,
    fee_amount = EXCLUDED.fee_amount,
    net_settlement_amount = EXCLUDED.net_settlement_amount,
    order_amount = EXCLUDED.order_amount,
    order_id = EXCLUDED.order_id,
    paid_amount = EXCLUDED.paid_amount,
    paid_at = EXCLUDED.paid_at,
    payment_id = EXCLUDED.payment_id,
    product_id = EXCLUDED.product_id,
    refund_amount = EXCLUDED.refund_amount,
    seller_id = EXCLUDED.seller_id,
    seller_settlement_id = EXCLUDED.seller_settlement_id,
    settlement_month = EXCLUDED.settlement_month,
    settlement_status = EXCLUDED.settlement_status;

INSERT INTO settlement.settlement_refunds (
    settlement_refund_id,
    buyer_id,
    created_at,
    order_id,
    payment_id,
    reflected_type,
    refund_amount,
    refund_id,
    refund_reason,
    refund_status,
    refunded_at,
    seller_id
)
VALUES (
    'ae000000-0000-4000-8000-000000000001',
    'a1000000-0000-4000-8000-000000000005',
    (SELECT (today_start + (seed_now - today_start) * 0.45) AT TIME ZONE 'Asia/Seoul' FROM ai_seed_clock),
    'a5000000-0000-4000-8000-000000000004',
    'a8000000-0000-4000-8000-000000000004',
    'AFTER_SETTLEMENT',
    29000,
    'aa000000-0000-4000-8000-000000000001',
    'AI_TEST_관리자 테스트 환불',
    'COMPLETED',
    (SELECT (today_start + (seed_now - today_start) * 0.45) AT TIME ZONE 'Asia/Seoul' FROM ai_seed_clock),
    'a1000000-0000-4000-8000-000000000001'
)
ON CONFLICT (settlement_refund_id) DO UPDATE
SET
    buyer_id = EXCLUDED.buyer_id,
    created_at = EXCLUDED.created_at,
    order_id = EXCLUDED.order_id,
    payment_id = EXCLUDED.payment_id,
    reflected_type = EXCLUDED.reflected_type,
    refund_amount = EXCLUDED.refund_amount,
    refund_id = EXCLUDED.refund_id,
    refund_reason = EXCLUDED.refund_reason,
    refund_status = EXCLUDED.refund_status,
    refunded_at = EXCLUDED.refunded_at,
    seller_id = EXCLUDED.seller_id;

INSERT INTO settlement.settlement_adjustments (
    adjustment_id,
    adjustment_amount,
    adjustment_type,
    created_at,
    order_id,
    reason,
    refund_id,
    seller_id,
    settlement_month,
    status
)
VALUES (
    'af000000-0000-4000-8000-000000000001',
    -29000,
    'POST_REFUND',
    (SELECT (today_start + (seed_now - today_start) * 0.46) AT TIME ZONE 'Asia/Seoul' FROM ai_seed_clock),
    'a5000000-0000-4000-8000-000000000004',
    'AI_TEST_정산 후 환불 보정',
    'aa000000-0000-4000-8000-000000000001',
    'a1000000-0000-4000-8000-000000000001',
    (SELECT to_char(this_month_start, 'YYYYMM') FROM ai_seed_clock),
    'READY'
)
ON CONFLICT (adjustment_id) DO UPDATE
SET
    adjustment_amount = EXCLUDED.adjustment_amount,
    adjustment_type = EXCLUDED.adjustment_type,
    created_at = EXCLUDED.created_at,
    order_id = EXCLUDED.order_id,
    reason = EXCLUDED.reason,
    refund_id = EXCLUDED.refund_id,
    seller_id = EXCLUDED.seller_id,
    settlement_month = EXCLUDED.settlement_month,
    status = EXCLUDED.status;

COMMIT;

SELECT 'member.member' AS dataset, count(*) AS seeded_rows
FROM member.member
WHERE nickname LIKE 'AI_TEST_%'
UNION ALL
SELECT 'product.products', count(*)
FROM product.products
WHERE name LIKE 'AI_TEST_%'
UNION ALL
SELECT 'product.drops', count(*)
FROM product.drops
WHERE id::text LIKE 'a4000000-%'
UNION ALL
SELECT 'orders.orders', count(*)
FROM orders.orders
WHERE order_number LIKE 'ORD-AI-%'
UNION ALL
SELECT 'payment.payments', count(*)
FROM payment.payments
WHERE idempotency_key LIKE 'AI_TEST_PAYMENT_%'
UNION ALL
SELECT 'settlement.settlement_orders', count(*)
FROM settlement.settlement_orders
WHERE settlement_order_id::text LIKE 'ad000000-%'
ORDER BY dataset;
