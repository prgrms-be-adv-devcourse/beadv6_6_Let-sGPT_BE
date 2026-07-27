# Payment

> 2026-07-27 `dev`, Flyway V1~V7 기준.

## 테이블 상세

### wallets
| 컬럼 | 타입 | 제약/비고 |
|---|---|---|
| id | UUID | PK |
| member_id | UUID | UNIQUE, 회원 도메인 참조(FK 아님) |
| balance | BIGINT | |
| version | BIGINT | DEFAULT 0 |
| created_at | TIMESTAMP | DEFAULT now() |
| updated_at | TIMESTAMP | |

### wallet_transactions
| 컬럼 | 타입 | 제약/비고 |
|---|---|---|
| id | UUID | PK |
| wallet_id | UUID | FK → wallets.id |
| type | VARCHAR(20) | CHARGE / DEDUCT / REFUND |
| amount | BIGINT | |
| balance_after | BIGINT | |
| idempotency_key | VARCHAR(100) | UNIQUE |
| created_at | TIMESTAMP | DEFAULT now() |

### wallet_charges
| 컬럼 | 타입 | 제약/비고 |
|---|---|---|
| id | UUID | PK |
| member_id | UUID | 회원 도메인 참조(FK 아님) |
| amount | BIGINT | |
| method | VARCHAR(20) | MOCK / PG |
| status | VARCHAR(20) | PENDING / APPROVED / FAILED |
| pg_payment_key | VARCHAR(500) | 컬럼 암호화(AES-GCM) |
| pg_payment_key_hash | VARCHAR(64) | V4, 웹훅 매칭용 SHA-256 |
| pg_tx_id | VARCHAR(100) | V4 |
| idempotency_key | VARCHAR(100) | UNIQUE |
| request_hash | VARCHAR(64) | V2, 멱등 바디대조 |
| created_at | TIMESTAMP | DEFAULT now() |
| updated_at | TIMESTAMP | |

### payments
| 컬럼 | 타입 | 제약/비고 |
|---|---|---|
| id | UUID | PK |
| order_id | UUID | 주문 도메인 참조(FK 아님), 전체 UNIQUE `uq_payments_order_id` |
| member_id | UUID | 회원 도메인 참조(FK 아님) |
| seller_id | UUID | 판매자 도메인 참조, 사후채움(nullable) |
| product_id | UUID | 상품 도메인 참조, 사후채움(nullable) |
| amount | BIGINT | |
| method | VARCHAR(20) | WALLET/PG |
| pg_provider | VARCHAR(20) | |
| pg_payment_key | VARCHAR(500) | 컬럼 암호화(AES-GCM) |
| pg_payment_key_hash | VARCHAR(64) | V3, 웹훅 매칭용 SHA-256 |
| pg_tx_id | VARCHAR(100) | |
| status | VARCHAR(20) | PENDING / PAYMENT_PENDING / APPROVED / PARTIALLY_REFUNDED / FAILED / CANCELED / REFUNDED |
| refunded_amount | BIGINT | DEFAULT 0 |
| idempotency_key | VARCHAR(100) | UNIQUE |
| request_hash | VARCHAR(64) | V2, 멱등 바디대조 |
| approved_at | TIMESTAMP | |
| pg_recon_status | VARCHAR(20) | V7, NOT_CHECKED / MATCHED / MISMATCH, DEFAULT NOT_CHECKED |
| pg_reconciled_at | TIMESTAMP | V7, PG 대사 완료 시각 |
| created_at | TIMESTAMP | DEFAULT now() |
| updated_at | TIMESTAMP | |

### payment_events
| 컬럼 | 타입 | 제약/비고 |
|---|---|---|
| id | UUID | PK |
| payment_id | UUID | FK → payments.id |
| type | VARCHAR(20) | APPROVE / FAIL / CANCEL / REFUND |
| amount | BIGINT | |
| created_at | TIMESTAMP | DEFAULT now() |

### refunds
| 컬럼 | 타입 | 제약/비고 |
|---|---|---|
| id | UUID | PK |
| payment_id | UUID | FK → payments.id |
| amount | BIGINT | |
| status | VARCHAR(20) | PENDING / COMPLETE / FAILED |
| reason | VARCHAR(255) | |
| pg_refund_key | VARCHAR(500) | 컬럼 암호화(AES-GCM) |
| idempotency_key | VARCHAR(100) | UNIQUE |
| request_hash | VARCHAR(64) | V5, 멱등 바디대조 |
| completed_at | TIMESTAMP | |
| pg_recon_status | VARCHAR(20) | V7, NOT_CHECKED / MATCHED / MISMATCH, DEFAULT NOT_CHECKED |
| pg_reconciled_at | TIMESTAMP | V7, PG 대사 완료 시각 |
| created_at | TIMESTAMP | DEFAULT now() |

### outbox_events
| 컬럼 | 타입 | 제약/비고 |
|---|---|---|
| id | UUID | PK |
| aggregate_type | VARCHAR(30) | PAYMENT/REFUND 등, FK 아님 |
| aggregate_id | UUID | FK 아님, 여러 애그리거트를 한 테이블에서 다룸 |
| topic | VARCHAR(100) | |
| payload | TEXT | |
| status | VARCHAR(20) | PENDING / PUBLISHED, DEFAULT PENDING |
| created_at | TIMESTAMP | DEFAULT now() |
| published_at | TIMESTAMP | |

### reconciliation_discrepancies
| 컬럼 | 타입 | 제약/비고 |
|---|---|---|
| id | UUID | PK |
| business_date | DATE | 대사 영업일 |
| entity_type | VARCHAR(20) | PAYMENT / REFUND |
| entity_id | UUID | 결제 또는 환불 식별자, FK 없음 |
| discrepancy_type | VARCHAR(30) | NOT_FOUND_IN_PG / STATUS_MISMATCH / AMOUNT_MISMATCH |
| detail | VARCHAR(500) | 불일치 상세 |
| created_at | TIMESTAMP | DEFAULT now() |

## 관계 요약
- `wallets` 1 — N `wallet_transactions` (FK `wallet_id`)
- `payments` 1 — N `payment_events` (FK `payment_id`)
- `payments` 1 — N `refunds` (FK `payment_id`)
- `wallet_charges.member_id` ↔ `wallets.member_id`: FK 없음, 애플리케이션 레벨 매칭
- `outbox_events.aggregate_id`: FK 없음, `payments.id`/`refunds.id` 등을 느슨하게 참조
- `reconciliation_discrepancies.entity_id`: 결제·환불을 논리 참조하며 FK 없음

## 비고
- `member_id`/`order_id`/`seller_id`/`product_id`는 모두 다른 서비스(member/order/product) 소유 데이터에 대한 논리적 참조 — 스키마가 분리된 MSA 구조라 실제 외래키 제약은 걸 수 없음.
- `pg_payment_key`는 `EncryptedStringConverter`로 컬럼 암호화(AES-GCM, 비결정적 IV)되어 있어 등호 조회가 불가능 — 별도의 결정적 해시 컬럼(`pg_payment_key_hash`, SHA-256)을 두고 웹훅 매칭에는 그 해시 컬럼을 사용.
- V6에서 PG confirm을 주문별 단일 진입점으로 바꾸며 기존 승인 상태 부분 유니크 인덱스를 제거하고
  `payments.order_id` 전체 UNIQUE 제약을 추가했다.
- V7에서 결제·환불 PG 대사 상태와 `reconciliation_discrepancies`를 추가했다. WALLET 결제·환불은
  PG 호출이 없으므로 생성·백필 시 `MATCHED`로 처리한다.
- 마이그레이션 파일: `V1__create_payment_tables.sql`부터 `V7__pg_reconciliation.sql`까지 순서대로 적용한다.
