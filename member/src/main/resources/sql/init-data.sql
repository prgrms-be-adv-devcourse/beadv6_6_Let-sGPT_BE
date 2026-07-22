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

-- =============================================================================
-- 대기열 테스트용
--   email    : test[1~10]@test.com
--   password : 12341234
-- =============================================================================

INSERT INTO member.member (id, email, password, nickname, platform_type, created_at, updated_at)
VALUES ('00000000-0000-0000-0000-000000000001','test1@test.com','$2a$10$qpCNOAOzhVdFCPfzyNY2Uud12qbx/KW9/amy/mG.ZCrnc1Uf8DHpu','테스트1', 'LOCAL', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
       ('00000000-0000-0000-0000-000000000002','test2@test.com','$2a$10$qpCNOAOzhVdFCPfzyNY2Uud12qbx/KW9/amy/mG.ZCrnc1Uf8DHpu','테스트2', 'LOCAL', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
       ('00000000-0000-0000-0000-000000000003','test3@test.com','$2a$10$qpCNOAOzhVdFCPfzyNY2Uud12qbx/KW9/amy/mG.ZCrnc1Uf8DHpu','테스트3', 'LOCAL', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
       ('00000000-0000-0000-0000-000000000004','test4@test.com','$2a$10$qpCNOAOzhVdFCPfzyNY2Uud12qbx/KW9/amy/mG.ZCrnc1Uf8DHpu','테스트4', 'LOCAL', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
       ('00000000-0000-0000-0000-000000000005','test5@test.com','$2a$10$qpCNOAOzhVdFCPfzyNY2Uud12qbx/KW9/amy/mG.ZCrnc1Uf8DHpu','테스트5', 'LOCAL', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
       ('00000000-0000-0000-0000-000000000006','test6@test.com','$2a$10$qpCNOAOzhVdFCPfzyNY2Uud12qbx/KW9/amy/mG.ZCrnc1Uf8DHpu','테스트6', 'LOCAL', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
       ('00000000-0000-0000-0000-000000000007','test7@test.com','$2a$10$qpCNOAOzhVdFCPfzyNY2Uud12qbx/KW9/amy/mG.ZCrnc1Uf8DHpu','테스트7', 'LOCAL', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
       ('00000000-0000-0000-0000-000000000008','test8@test.com','$2a$10$qpCNOAOzhVdFCPfzyNY2Uud12qbx/KW9/amy/mG.ZCrnc1Uf8DHpu','테스트8', 'LOCAL', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
       ('00000000-0000-0000-0000-000000000009','test9@test.com','$2a$10$qpCNOAOzhVdFCPfzyNY2Uud12qbx/KW9/amy/mG.ZCrnc1Uf8DHpu','테스트9', 'LOCAL', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
       ('00000000-0000-0000-0000-000000000010','test10@test.com','$2a$10$qpCNOAOzhVdFCPfzyNY2Uud12qbx/KW9/amy/mG.ZCrnc1Uf8DHpu','테스트10', 'LOCAL', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

-- test1~10에 ROLE_USER 부여 (없으면 로그인 시 MemberService.getCurrentRole()이 role_history를
-- 못 찾아 MEMBER_NOT_FOUND를 던진다 - 정상 회원가입은 가입 트랜잭션 안에서 자동으로 이걸
-- 부여하지만, SQL로 직접 넣은 계정은 이 단계가 빠지므로 admin 계정과 동일하게 명시적으로 부여).
INSERT INTO member.role_history (member_id, role_id, created_at)
SELECT m.id, r.id, CURRENT_TIMESTAMP
FROM member.member m
CROSS JOIN member.role r
WHERE m.id IN (
    '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000002',
    '00000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000004',
    '00000000-0000-0000-0000-000000000005', '00000000-0000-0000-0000-000000000006',
    '00000000-0000-0000-0000-000000000007', '00000000-0000-0000-0000-000000000008',
    '00000000-0000-0000-0000-000000000009', '00000000-0000-0000-0000-000000000010'
  )
  AND r.role = 'ROLE_USER'
  AND NOT EXISTS (
      SELECT 1 FROM member.role_history rh
      WHERE rh.member_id = m.id AND rh.role_id = r.id
  );