-- =============================================================================
-- role 테이블 시드 데이터
-- =============================================================================
INSERT INTO member.role (role) VALUES ('ROLE_USER');
INSERT INTO member.role (role) VALUES ('ROLE_SELLER');
INSERT INTO member.role (role) VALUES ('ROLE_ADMIN');

-- =============================================================================
-- 관리자 계정 시드 데이터 (로컬 개발 전용)
--   email    : admin@test.com
--   password : admin1234
-- =============================================================================
INSERT INTO member.member (id, email, password, nickname, platform_type, created_at, updated_at)
VALUES (
    '00000000-0000-7000-8000-000000000001',
    'admin@test.com',
    '$2a$10$eACCYoNOHEqXve8aIWT8Nu3PkMXWBaObN5REkGFbtJKTKMDoFAWRS',
    'admin',
    'LOCAL',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
);

-- 관리자 계정에 ROLE_ADMIN 부여
INSERT INTO member.role_history (member_id, role_id, created_at)
SELECT '00000000-0000-7000-8000-000000000001'::uuid, r.id, CURRENT_TIMESTAMP
FROM member.role r
WHERE r.role = 'ROLE_ADMIN';
