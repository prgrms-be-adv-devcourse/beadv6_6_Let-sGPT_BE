# 상품 1,000건 배치 등록 가이드

## 1. 이 가이드의 목적

이 기능은 이미 준비된 상품 이미지와 `products.csv`가 들어 있는 폴더 하나를 읽어 상품 1,000건을 등록합니다.

이번 데모 데이터는 아래 폴더를 한 번 생성한 뒤 ZIP으로 공유하는 방식입니다. 팀원은 이미지를 다시 다운로드하거나 CSV를 다시 생성할 필요가 없습니다.

```text
abo-limited-demo-1000/
├─ products.csv
├─ products_전체 설명.csv     # 비교용 원본, API가 읽지 않음
├─ images/
│  ├─ 0001.jpg
│  ├─ 0002.jpg
│  └─ 1000.jpg
├─ image_sources.csv
├─ batch-csv-generation-report.json
└─ README.md
```

배치 API가 직접 사용하는 것은 `products.csv`와 CSV의 `thumbnail_file`·`image_files`가 가리키는 이미지입니다. 나머지 파일은 출처와 검증 기록이며 API가 무시합니다.

현재 `products.csv`는 이미지 임베딩의 색상 기여도를 검증하기 위해 상품명과 상품 설명에서 색상 정보만 제외한 실험용 파일입니다. 상품 종류·소재·형태·스타일·용도·모델 코드는 유지합니다. 검색 모듈은 `상품명 + 상품 설명 + 이미지 분석 설명`을 합쳐 임베딩하므로 상품명에서도 색상을 제외해야 `빨간색 소파` 같은 검색이 이미지 분석 설명 덕분에 검색되는지 분리해서 확인할 수 있습니다.

`products_전체 설명.csv`는 색상 정보가 포함된 비교 원본입니다. 파일명이 `products.csv`가 아니므로 배치 API가 읽지 않습니다.

> ZIP 파일 자체는 배치 API 입력이 아닙니다. 로컬에서는 ZIP을 먼저 풀고 그 폴더 경로를 전달하고, S3에서는 압축을 푼 파일 구조를 prefix 아래에 업로드해야 합니다.

## 2. 확인된 데모 데이터 규격

- 상품 행: 1,000개
- 대표 이미지: `images/0001.jpg` ~ `images/1000.jpg`
- 상품과 이미지: `demo-0001` ↔ `images/0001.jpg` 방식으로 1:1 연결
- 이미지당 제한: 10MB 이하
- CSV: UTF-8 BOM, CRLF 줄바꿈
- 상품명·설명의 색상 표현: 0건(이미지 분석 색상 검색 실험용)
- CSV 파일명: 반드시 `products.csv`
- 이미지 경로: 데이터 폴더 기준 상대 경로만 사용

`products.csv` 헤더는 다음과 같습니다.

```csv
external_id,name,description,category_id,category_name,price,thumbnail_file,image_files,drop_price,total_quantity,limit_per_user,open_at,close_at
```

| 열 | 필수 | 설명 |
|---|---:|---|
| `external_id` | 예 | 판매자별 재실행 중복 방지 키, 최대 100자 |
| `name` | 예 | 상품명, 최대 100자 |
| `description` | 아니요 | 상품 설명 |
| `category_id` | 아니요 | 환경마다 달라질 수 있는 카테고리 UUID |
| `category_name` | 아니요 | 환경 간 이동에 적합한 카테고리 이름 |
| `price` | 예 | 0보다 큰 정수 |
| `thumbnail_file` | 예 | 대표 이미지 상대 경로 |
| `image_files` | 아니요 | 추가 이미지 상대 경로, 여러 개면 `;` 구분, 최대 10개 |
| `drop_price` | 조건부 | 드롭 가격, 비우면 상품 가격 사용 |
| `total_quantity` | 아니요 | 입력하면 드롭도 생성 |
| `limit_per_user` | 아니요 | 1인 구매 제한 수량 |
| `open_at` | 드롭 시 필수 | 실행 시점보다 미래인 UTC ISO-8601 시각 |
| `close_at` | 아니요 | `open_at`보다 뒤인 UTC ISO-8601 시각 |

공유용 CSV는 환경마다 UUID가 다른 문제를 피하기 위해 `category_id`를 비우고 `category_name`을 사용합니다. 현재 기본 시드 카테고리는 `의류`, `액세서리`, `문구`, `전자기기`, `피규어`, `기타`입니다.

파일을 Excel에서 직접 저장하면 인코딩이나 날짜·숫자 형식이 바뀔 수 있습니다. 수정해야 한다면 반드시 `CSV UTF-8`로 저장하고 먼저 dry-run을 실행합니다.

## 3. 로컬에서 ZIP을 받아 등록하기

### 3.1 압축 해제

```powershell
Expand-Archive `
  -LiteralPath ".\abo-limited-demo-1000.zip" `
  -DestinationPath ".\product-imports"

$packagePath = (Resolve-Path ".\product-imports\abo-limited-demo-1000").Path
$packagePath
```

압축을 푼 직후 아래 두 경로가 존재해야 합니다.

```powershell
Test-Path (Join-Path $packagePath "products.csv")
Test-Path (Join-Path $packagePath "images\0001.jpg")
```

### 3.2 서버가 폴더를 읽도록 허용

허용 경로 환경 변수는 상품 서버를 시작하기 전에 설정합니다. 데이터 폴더 자체 또는 그 상위 폴더를 지정할 수 있습니다.

```powershell
$allowedRoot = Split-Path $packagePath -Parent
$env:PRODUCT_BATCH_IMPORT_LOCAL_ALLOWED_ROOTS = $allowedRoot
$env:JAVA_HOME = "C:\path\to\jdk-21"
.\gradlew.bat :product:bootRun
```

기본 허용 경로는 `${user.home}/.openat/product-imports`입니다. 그 아래에 압축을 풀면 별도 환경 변수 없이 사용할 수 있습니다.

### 3.3 dry-run 실행

상품 서비스를 직접 호출하는 개발 환경 예시입니다.

```powershell
$baseUrl = "http://localhost:9110/api/v1"
$sellerId = "11111111-1111-1111-1111-111111111111"
$headers = @{ "X-Seller-Id" = $sellerId }

$body = @{
  sourceType = "LOCAL"
  location = $packagePath
  dryRun = $true
} | ConvertTo-Json

$job = Invoke-RestMethod `
  -Method Post `
  -Uri "$baseUrl/products/import-jobs" `
  -Headers $headers `
  -ContentType "application/json" `
  -Body $body

do {
  Start-Sleep -Seconds 2
  $status = Invoke-RestMethod `
    -Uri "$baseUrl/products/import-jobs/$($job.jobId)" `
    -Headers $headers
  $status
} while ($status.status -in @("PENDING", "RUNNING"))
```

정상 기준은 `status: COMPLETED`, `successCount: 1000`, `failureCount: 0`입니다. dry-run은 DB와 이미지 저장소에 아무것도 쓰지 않습니다.

게이트웨이 `http://localhost:8000`을 통과할 때는 개발용 `X-Seller-Id` 대신 `openat-product` audience의 판매자 scoped 토큰을 `Authorization: Bearer ...`로 보냅니다.

### 3.4 실제 등록

dry-run이 모두 성공한 뒤 요청 본문의 `dryRun`만 `false`로 바꿔 새 작업을 시작합니다.

```powershell
$body = @{
  sourceType = "LOCAL"
  location = $packagePath
  dryRun = $false
} | ConvertTo-Json
```

같은 판매자가 같은 `external_id`를 다시 실행하면 이미 성공한 행은 `SKIPPED` 처리됩니다.

## 4. EC2에서 등록하기

EC2에서도 LOCAL과 S3 두 방식이 모두 가능합니다. 운영·시연 환경에서는 애플리케이션 인스턴스 교체와 컨테이너 경로 문제를 피할 수 있는 S3 방식을 권장합니다.

### 4.1 EC2 로컬 디스크 방식

1. ZIP을 EC2에 복사합니다.
2. `/opt/openat/product-imports/abo-limited-demo-1000`처럼 서버가 읽을 수 있는 위치에 압축을 풉니다.
3. 상품 서버 시작 전에 허용 루트를 설정합니다.

```bash
sudo mkdir -p /opt/openat/product-imports
sudo unzip abo-limited-demo-1000.zip -d /opt/openat/product-imports
export PRODUCT_BATCH_IMPORT_LOCAL_ALLOWED_ROOTS=/opt/openat/product-imports
```

API 요청의 위치는 다음과 같습니다.

```json
{
  "sourceType": "LOCAL",
  "location": "/opt/openat/product-imports/abo-limited-demo-1000",
  "dryRun": true
}
```

상품 서버가 Docker 또는 Kubernetes 안에서 실행된다면 호스트 경로를 컨테이너에 읽기 전용으로 마운트해야 합니다. 이때 `location`과 허용 루트는 호스트 경로가 아니라 **컨테이너 내부 경로**여야 합니다.

### 4.2 S3 prefix 방식

ZIP을 풀어 폴더 내용물을 같은 상대 구조로 업로드합니다. 다음 명령을 실행하면 S3의 `abo-limited-demo-1000/products.csv`와 `abo-limited-demo-1000/images/0001.jpg` 구조가 만들어집니다.

```bash
aws s3 sync ./abo-limited-demo-1000 \
  s3://openat-demo-data/abo-limited-demo-1000
```

상품 서버 환경 변수:

```bash
export PRODUCT_BATCH_IMPORT_S3_ALLOWED_BUCKETS=openat-demo-data
export PRODUCT_BATCH_IMPORT_S3_REGION=ap-northeast-2
```

EC2 인스턴스 역할에는 최소한 해당 prefix의 `s3:GetObject` 권한이 필요합니다. SSE-KMS 객체라면 키의 `kms:Decrypt` 권한도 필요합니다.

API 요청:

```json
{
  "sourceType": "S3",
  "location": "s3://openat-demo-data/abo-limited-demo-1000",
  "dryRun": true
}
```

S3 모드는 **배치 원본을 읽는 위치**를 뜻합니다. 등록된 상품 이미지가 최종적으로 저장되는 위치는 `ImageStorageUseCase`의 현재 구현을 따릅니다. 현재 로컬 이미지 어댑터를 사용한다면 EC2 로컬 디스크에 저장되고, 이미지 S3 어댑터로 전환하면 같은 배치 API가 최종 이미지를 S3에 저장합니다.

AWS CLI 자격 증명 또는 인스턴스 역할과 대상 버킷 정보가 준비되어 있다면 이 데이터 폴더의 S3 업로드 작업도 자동화할 수 있습니다.

## 5. 작업 상태와 오류 확인

작업 시작 API는 즉시 `202 Accepted`를 반환하고 백그라운드에서 처리합니다.

```http
POST /api/v1/products/import-jobs
GET  /api/v1/products/import-jobs/{jobId}
GET  /api/v1/products/import-jobs/{jobId}/items?page=0&size=50
```

작업 상태:

- `PENDING`: 실행 대기
- `RUNNING`: 처리 중
- `COMPLETED`: 전 행 성공
- `COMPLETED_WITH_ERRORS`: 일부 행 실패
- `FAILED`: CSV나 입력 위치를 열 수 없는 작업 전체 오류

행 상태:

- `VALIDATED`: dry-run 검증 성공
- `IMPORTED`: 실제 등록 성공
- `SKIPPED`: 판매자와 `external_id`가 같은 기존 성공 행
- `FAILED`: 해당 행 실패, `message`에서 원인 확인

## 6. 서버 설정값

| 환경 변수 | 기본값 | 설명 |
|---|---|---|
| `PRODUCT_BATCH_IMPORT_LOCAL_ALLOWED_ROOTS` | `${user.home}/.openat/product-imports` | 허용할 서버 로컬 상위 폴더, 쉼표로 복수 지정 |
| `PRODUCT_BATCH_IMPORT_S3_ALLOWED_BUCKETS` | 없음 | 허용할 S3 버킷, 쉼표로 복수 지정 |
| `PRODUCT_BATCH_IMPORT_S3_REGION` | `ap-northeast-2` | S3 리전 |
| `PRODUCT_BATCH_IMPORT_MAX_ROWS` | `1000` | 작업당 CSV 최대 행 수 |
| `PRODUCT_BATCH_IMPORT_MAX_MANIFEST_BYTES` | `5242880` | CSV 최대 크기 |
| `PRODUCT_BATCH_IMPORT_MAX_IMAGE_BYTES` | `10485760` | 이미지당 최대 크기 |
| `PRODUCT_BATCH_IMPORT_WORKER_THREADS` | `2` | 동시에 실행할 작업 수 |

## 7. 공유·운영 시 주의사항

- 1,000건 패키지는 압축 전 약 592MB이므로 메신저나 저장소의 파일 크기 제한을 확인합니다.
- `products.csv`의 드롭 시작 시각은 실행 시점보다 미래여야 합니다. 현재 데모 CSV는 2035년 일정입니다.
- 클라이언트 PC의 경로를 원격 EC2 API에 보낼 수 없습니다. `LOCAL`은 항상 상품 서버 프로세스가 접근할 수 있는 경로입니다.
- 여러 애플리케이션 인스턴스에서 처리 중 작업을 시작한 인스턴스가 종료되면 현재는 자동 재개되지 않습니다.
- 이미지 저장 뒤 DB 등록이 실패하면 현재 이미지 저장 포트에 삭제 기능이 없어 고아 이미지가 남을 수 있습니다.
- 운영에서 Hibernate `ddl-auto`를 끈 경우 배치 작업·항목·영수증 테이블의 DB 마이그레이션을 먼저 적용해야 합니다.
