# Payment
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
| type | VARCHAR(20) | DEDUCT/CHARGE/REFUND 등 |
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
| method | VARCHAR(20) | |
| status | VARCHAR(20) | |
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
| order_id | UUID | 주문 도메인 참조(FK 아님). 부분 유니크 인덱스 `uq_payments_order_id_approved` (status='APPROVED') |
| member_id | UUID | 회원 도메인 참조(FK 아님) |
| seller_id | UUID | 판매자 도메인 참조, 사후채움(nullable) |
| product_id | UUID | 상품 도메인 참조, 사후채움(nullable) |
| amount | BIGINT | |
| method | VARCHAR(20) | WALLET/PG |
| pg_provider | VARCHAR(20) | |
| pg_payment_key | VARCHAR(500) | 컬럼 암호화(AES-GCM) |
| pg_payment_key_hash | VARCHAR(64) | V3, 웹훅 매칭용 SHA-256 |
| pg_tx_id | VARCHAR(100) | |
| status | VARCHAR(20) | |
| refunded_amount | BIGINT | DEFAULT 0 |
| idempotency_key | VARCHAR(100) | UNIQUE |
| request_hash | VARCHAR(64) | V2, 멱등 바디대조 |
| approved_at | TIMESTAMP | |
| created_at | TIMESTAMP | DEFAULT now() |
| updated_at | TIMESTAMP | |

### payment_events
| 컬럼 | 타입 | 제약/비고 |
|---|---|---|
| id | UUID | PK |
| payment_id | UUID | FK → payments.id |
| type | VARCHAR(20) | APPROVE 등 |
| amount | BIGINT | |
| created_at | TIMESTAMP | DEFAULT now() |

### refunds
| 컬럼 | 타입 | 제약/비고 |
|---|---|---|
| id | UUID | PK |
| payment_id | UUID | FK → payments.id |
| amount | BIGINT | |
| status | VARCHAR(20) | |
| reason | VARCHAR(255) | |
| pg_refund_key | VARCHAR(500) | |
| idempotency_key | VARCHAR(100) | UNIQUE |
| request_hash | VARCHAR(64) | V5, 멱등 바디대조 |
| completed_at | TIMESTAMP | |
| created_at | TIMESTAMP | DEFAULT now() |

### outbox_events
| 컬럼 | 타입 | 제약/비고 |
|---|---|---|
| id | UUID | PK |
| aggregate_type | VARCHAR(30) | PAYMENT/REFUND 등, FK 아님 |
| aggregate_id | UUID | FK 아님, 여러 애그리거트를 한 테이블에서 다룸 |
| topic | VARCHAR(100) | |
| payload | TEXT | |
| status | VARCHAR(20) | DEFAULT PENDING |
| created_at | TIMESTAMP | DEFAULT now() |
| published_at | TIMESTAMP | |

## 관계 요약
- `wallets` 1 — N `wallet_transactions` (FK `wallet_id`)
- `payments` 1 — N `payment_events` (FK `payment_id`)
- `payments` 1 — N `refunds` (FK `payment_id`)
- `wallet_charges.member_id` ↔ `wallets.member_id`: FK 없음, 애플리케이션 레벨 매칭
- `outbox_events.aggregate_id`: FK 없음, `payments.id`/`refunds.id` 등을 느슨하게 참조

## 비고
- `member_id`/`order_id`/`seller_id`/`product_id`는 모두 다른 서비스(member/order/product) 소유 데이터에 대한 논리적 참조 — 스키마가 분리된 MSA 구조라 실제 외래키 제약은 걸 수 없음.
- `pg_payment_key`는 `EncryptedStringConverter`로 컬럼 암호화(AES-GCM, 비결정적 IV)되어 있어 등호 조회가 불가능 — 별도의 결정적 해시 컬럼(`pg_payment_key_hash`, SHA-256)을 두고 웹훅 매칭에는 그 해시 컬럼을 사용.
- 마이그레이션 파일: `V1__create_payment_tables.sql`, `V2__day2_wallet_payment_outbox.sql`, `V3__pg_payment_key_hash.sql`, `V4__day5_wallet_charge_pg_confirm.sql`, `V5__day5_refund.sql`.
