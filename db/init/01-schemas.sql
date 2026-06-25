-- postgres 컨테이너 최초 기동(빈 볼륨) 시 docker-entrypoint-initdb.d에 의해 자동 실행됨
CREATE SCHEMA IF NOT EXISTS member;
CREATE SCHEMA IF NOT EXISTS product;
CREATE SCHEMA IF NOT EXISTS orders;
CREATE SCHEMA IF NOT EXISTS payment;
CREATE SCHEMA IF NOT EXISTS settlement;
