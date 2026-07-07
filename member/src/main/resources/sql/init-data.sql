-- =============================================================================
-- role 테이블 시드 데이터
--   compose/운영 프로필도 sql.init.mode=always 로 매 기동 실행되고 테이블이 persistent 하므로
--   반드시 멱등해야 한다(중복 INSERT 시 unique/PK 위반으로 member 크래시 방지).
-- =============================================================================
INSERT INTO member.role (role) VALUES ('ROLE_USER')   ON CONFLICT (role) DO NOTHING;
INSERT INTO member.role (role) VALUES ('ROLE_SELLER') ON CONFLICT (role) DO NOTHING;
INSERT INTO member.role (role) VALUES ('ROLE_ADMIN')  ON CONFLICT (role) DO NOTHING;

-- =============================================================================
-- 관리자 계정 시드 데이터 (local + compose/배포 공통)
--   email    : admin@test.com
--   password : admin1234
-- =============================================================================
INSERT INTO member.member (id, email, password, nickname, platform_type, created_at, updated_at)
VALUES (
    '00000000-0000-7000-8000-000000000001',
    'admin@test.com',
    '$2a$10$KDnPKcU1E4yQQHgFLISooOX2tVUwM2pM8NXwpaALb/Fbb1bfKWc/G',
    'admin',
    'LOCAL',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
)
ON CONFLICT (id) DO NOTHING;

-- 관리자 계정에 ROLE_ADMIN 부여 (이미 부여돼 있으면 재삽입 금지)
INSERT INTO member.role_history (member_id, role_id, created_at)
SELECT '00000000-0000-7000-8000-000000000001'::uuid, r.id, CURRENT_TIMESTAMP
FROM member.role r
WHERE r.role = 'ROLE_ADMIN'
  AND NOT EXISTS (
      SELECT 1 FROM member.role_history rh
      WHERE rh.member_id = '00000000-0000-7000-8000-000000000001'::uuid
        AND rh.role_id = r.id
  );
