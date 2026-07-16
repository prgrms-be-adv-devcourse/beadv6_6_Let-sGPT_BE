# INFERENCE_SERVER.md — 추론 서버 연동 가이드

> 이 문서는 openAt의 각 서비스가 **자체 추론 서버를 호출할 때 준수해야 하는 클라이언트 계약**을 정의한다.
>
> 연동 규격은 **OpenAI 호환 HTTP 인터페이스**를 기준으로 하며, 특정 언어·프레임워크·SDK에 종속되지 않는다. 추론 서버의 구현과 운영은 별도 저장소에서 관리하며, 여기서는 호출하는 서비스가 알아야 할 요청·응답 규칙과 장애 처리 원칙만 다룬다.

---

## 1. 문서의 적용 범위와 기준

이 문서의 대상은 추론 서버를 호출하는 모든 openAt 서비스다. 각 서비스는 사용하는 언어나 라이브러리와 관계없이 최종 HTTP 요청이 이 문서의 계약을 만족하도록 구현해야 한다.

서버가 직접 제공하는 최신 계약 문서는 다음 경로에서 확인한다.

```text
https://api.inferway.xyz/docs
```

`/docs`는 인증 없이 접근할 수 있으며 **서버 기준의 원본 계약**이다. 이 문서와 `/docs`의 내용이 다르면 `/docs`를 우선한다.

다음 경로는 의도적으로 제공하지 않는다.

```text
/openapi.json
/redoc
```

두 경로는 `404`를 반환한다. 자동 생성된 스키마가 실제 서버 계약과 달라지는 문제를 방지하기 위한 정책이다.

---

## 2. 연동 계약 한눈에 보기

| 항목 | 계약 |
|---|---|
| API Origin | `https://api.inferway.xyz` |
| OpenAI 호환 Base URL | `https://api.inferway.xyz/v1` |
| 인증 | `Authorization: Bearer <API_KEY>` |
| API 키 발급 | 추론 서버 관리자에게 직접 요청 |
| API 키 유효성 | 추론 서버에 등록된 활성 키만 사용 가능, 폐기된 키는 사용 불가 |
| 모델 지정 | 기능 별칭 또는 검색 호환 모델명 사용 |
| 임베딩 차원 | `embed` **1024차원**, `text-embedding-3-small` **1536차원** 고정 |
| 스트리밍 | SSE, 요청에 `"stream": true` 지정 |
| 스트림 정상 종료 | 마지막 이벤트 `data: [DONE]` 필수 |
| 요청 본문 상한 | **32MiB** (`32 × 1024 × 1024`바이트) |
| 로컬 추론 응답 시작 기한 | 90초, 이후 폴백 가능 여부에 따라 처리 |
| 전체 응답 시작 기한 | 115초, 초과 시 `504` |
| 오류 본문 | OpenAI 호환 `error` 객체 |
| 캐시 정책 | `/v1/*`, `/health` 모두 `no-store` |
| 최신 계약 문서 | `GET /docs` |

지원하는 엔드포인트는 다음 다섯 개뿐이다.

| 메서드 | 경로 | 인증 | 용도 |
|---|---|---|---|
| `POST` | `/v1/chat/completions` | 필요 | 대화형 생성 및 이미지 분석 |
| `POST` | `/v1/responses` | 필요 | OpenAI Responses 형식 이미지 분석 |
| `POST` | `/v1/embeddings` | 필요 | 임베딩 생성 |
| `GET` | `/health` | 필요 | 서버 운영 상태 확인 |
| `GET` | `/docs` | 불필요 | 서버가 렌더링하는 최신 계약 문서 |

OpenAI 호환 인터페이스라고 해서 OpenAI API의 다른 엔드포인트까지 지원되는 것은 아니다. 위 목록에 없는 경로는 사용하지 않는다.

---

## 3. URL 구성 규칙

최종 요청 URL은 반드시 다음 형태여야 한다.

```text
https://api.inferway.xyz/v1/chat/completions
https://api.inferway.xyz/v1/responses
https://api.inferway.xyz/v1/embeddings
https://api.inferway.xyz/health
https://api.inferway.xyz/docs
```

연동 과정에서 가장 자주 발생하는 오류는 `/v1`을 누락하거나 두 번 붙이는 것이다.

```text
잘못된 예: https://api.inferway.xyz/chat/completions
잘못된 예: https://api.inferway.xyz/v1/v1/chat/completions
올바른 예: https://api.inferway.xyz/v1/chat/completions
```

OpenAI 호환 SDK마다 Base URL과 엔드포인트 경로를 조합하는 방식이 다르다. 어떤 SDK는 `/v1`을 자동으로 추가하고, 어떤 SDK는 설정값을 그대로 사용한다. 따라서 설정 이름이나 SDK 문서만 보고 판단하지 말고, 첫 연동 시 **실제로 전송되는 최종 URL을 반드시 확인한다.**

`404`가 발생하면 가장 먼저 `/v1` 조립 오류를 점검한다.

---

### 3.1 검색 모듈 교체 방법

검색 모듈에는 기존 OpenAI 구현을 유지한 채 다음 추론 서버 전용 구현이 추가되어 있다.

| 기능 | 기존 구현 | 추론 서버 구현 |
|---|---|---|
| 이미지 분석 | `OpenAiImageClient` | `InferenceServerImageClient` |
| 상품 임베딩 | `AiProductEmbeddingGenerator` | `InferenceServerProductEmbeddingGenerator` |

`feat/search-inference-server-client` 브랜치의 `AiImageService`와 `ProductEmbeddingService`는 추론 서버 구현을 사용하도록 연결되어 있다. 기존 OpenAI 구현은 비교와 롤백을 위해 남겨 두지만 이 브랜치의 서비스 흐름에서는 호출하지 않는다.

추론 서버를 사용할 때는 다음 환경변수를 설정한다.

```text
OPENAI_API_KEY=<추론 서버에서 발급한 API 키>
OPENAI_EMBEDDING_ENABLED=true
```

`OPENAI_BASE_URL`의 기본값은 `https://api.inferway.xyz/v1`이다. 다른 환경의 추론 서버를 시험할 때만 해당 환경변수로 덮어쓴다. API 키는 기본값이 없으므로 반드시 직접 주입해야 한다.

`OPENAI_EMBEDDING_ENABLED`는 `ProductEmbeddingService`의 임베딩 사용 스위치(`openai.embedding.enabled`)다. local 프로필에는 `true`가 이미 설정돼 있지만, compose·k8s 배포에는 이 값이 없어 기본값 `false`가 적용되고 상품 적재 시 임베딩이 생략되며 검색어가 있는 벡터 검색이 실패한다. 임베딩을 사용하는 배포에서는 어떤 generator를 쓰든 반드시 `true`를 함께 설정한다.

기존 모델 설정인 `gpt-5.4-nano`와 `text-embedding-3-small`은 그대로 둔다. 추론 서버가 이 모델명을 호환 별칭으로 받아 로컬 모델에 연결한다.

검색 API가 사용자에게서 이미지를 받는 경계는 기존과 같이 `MultipartFile`이다. 추론 서버 클라이언트는 파일 바이트를 OpenAI Responses 형식의 base64 data URL로 변환해 JSON으로 전송하므로, 서비스나 컨트롤러의 멀티파트 계약은 바뀌지 않는다.

### 3.2 provider 전환 시 전체 재색인 필수

이번 교체는 임베딩 provider가 바뀌는 전환이다. 추론 서버의 `text-embedding-3-small`은 모델명과 1536차원만 OpenAI와 같고, 내부적으로는 `snowflake-arctic-embed2` 모델의 1024차원 벡터 뒤에 512개의 0을 붙인 벡터라 OpenAI 벡터와 **벡터 공간이 다르다.** 차원이 같아 Elasticsearch는 두 벡터의 혼합 저장을 막지 않으며, 섞이면 오류 없이 검색 관련도만 조용히 훼손되므로 탐지가 어렵다.

기존 OpenAI `text-embedding-3-small` 벡터가 저장된 색인이 있다면, generator 교체와 함께 다음 순서로 전체 재색인을 진행한다.

1. 새 색인을 생성한다. `dense_vector.dims`는 그대로 1536이다.
2. 교체한 generator로 전체 상품을 다시 임베딩해 새 색인에 적재한다.
3. 검색 품질을 검증한다.
4. alias 또는 색인 설정을 새 색인으로 전환한다.

기존 벡터와 새 벡터를 같은 색인에 섞지 않는다. 기존 OpenAI provider로 롤백할 때도 같은 이유로 전체 재색인이 필요하다.

---

## 4. 인증과 API 키 발급

`/v1/*`와 `/health`는 추론 서버에서 관리하는 API 키로 인증한다. 연동하는 서비스 담당자는 사용 전에 **추론 서버 관리자에게 API 키 발급을 직접 요청해야 한다.** 관리자가 발급한 키를 전달받은 뒤, 인증이 필요한 모든 요청에 Bearer 토큰으로 추가한다.

```http
Authorization: Bearer <API_KEY>
```

API 키는 클라이언트가 임의로 생성하거나 추정해서 사용하는 값이 아니다. **추론 서버에 등록되어 있고 현재 활성 상태인 키만 사용할 수 있다.**

- API 키가 없거나 올바르지 않으면 요청은 추론 엔진에 도달하지 않고 `401`로 종료된다.
- **폐기된 API 키는 더 이상 사용할 수 없으며**, 해당 키로 요청하면 `401`이 반환된다.
- 키가 필요하거나 기존 키의 교체가 필요한 경우 추론 서버 관리자에게 발급을 요청한다.
- 발급받은 키는 소스 코드, 일반 설정 파일, 커밋 기록에 저장하지 않는다.
- 배포 환경에서는 환경변수 또는 시크릿 저장소를 통해 키를 주입한다.

예시는 다음과 같다.

```yaml
inference:
  base-url: https://api.inferway.xyz/v1
  api-key: ${INFERENCE_API_KEY}
```

`/docs`를 제외한 인증 대상 엔드포인트를 호출할 때는 관리자로부터 발급받은 유효한 API 키를 요청 헤더에 포함해야 한다.

---

## 5. 모델 별칭 계약

클라이언트는 실제 로컬 provider 모델명을 알 필요가 없으며 요청에 직접 지정하지 않는다. `model` 필드에는 서버가 정의한 기능 별칭이나 검색 모듈의 OpenAI 호환 모델명을 사용한다.

| 별칭 | 허용 엔드포인트 | 용도 |
|---|---|---|
| `chat` | `/v1/chat/completions` | 일반 대화형 생성 |
| `vision` | `/v1/chat/completions` | 이미지가 포함된 생성 |
| `embed` | `/v1/embeddings` | 임베딩 생성 |
| `gpt-5.4-nano` | `/v1/responses` | 검색 모듈 이미지 분석 호환 |
| `text-embedding-3-small` | `/v1/embeddings` | 검색 모듈 임베딩 호환 |

별칭과 실제 모델의 매핑은 서버 설정이 소유한다. 서버가 내부 모델이나 provider를 교체하더라도 클라이언트는 변경 없이 같은 별칭을 계속 사용한다.

다음 요청은 게이트웨이에서 `400`으로 거절되며 업스트림 provider를 호출하지 않는다.

```json
{"model": "gemma4:12b-it-qat"}
```

실제 모델명을 직접 지정했기 때문에 거절된다.

```json
{"model": "chatt"}
```

등록되지 않은 별칭이므로 거절된다.

```json
{
  "model": "embed",
  "messages": [{"role": "user", "content": "안녕하세요"}]
}
```

`/v1/chat/completions`에서 허용되지 않는 별칭이므로 거절된다.

> **중요:** 응답의 `model` 값을 비즈니스 로직이나 분기 조건으로 사용하지 않는다. `/v1/chat/completions`의 응답 `model`은 실제 요청을 처리한 provider 모델명이라 서버의 모델 교체나 폴백에 따라 달라질 수 있다. `/v1/responses`와 `/v1/embeddings`의 응답 `model`은 요청에 보낸 별칭을 그대로 돌려준다.

---

## 6. Chat Completions

### 6.1 일반 응답 방식

짧은 생성이나 응답 전체가 한 번에 필요한 경우 일반 응답 방식을 사용할 수 있다.

```http
POST /v1/chat/completions HTTP/1.1
Host: api.inferway.xyz
Authorization: Bearer <API_KEY>
Content-Type: application/json

{
  "model": "chat",
  "messages": [
    {
      "role": "user",
      "content": "배송 지연 안내 문구를 작성해 줘."
    }
  ]
}
```

응답은 OpenAI Chat Completions 형식을 따른다.

```json
{
  "id": "chatcmpl-621",
  "object": "chat.completion",
  "model": "gemma4:12b-it-qat",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "요청하신 안내 문구입니다."
      },
      "finish_reason": "stop"
    }
  ],
  "usage": {
    "prompt_tokens": 14,
    "completion_tokens": 10,
    "total_tokens": 24
  }
}
```

일반 응답 방식에서는 서버가 전체 응답 본문을 확보한 시점을 응답 시작으로 본다. 생성이 길어질수록 115초 응답 시작 기한에 도달할 가능성이 커지므로, 긴 출력에는 스트리밍을 우선한다.

### 6.2 스트리밍 방식

긴 생성, 대화형 UI, 사용자에게 토큰을 즉시 전달해야 하는 기능은 스트리밍을 기본으로 사용한다.

```http
POST /v1/chat/completions HTTP/1.1
Host: api.inferway.xyz
Authorization: Bearer <API_KEY>
Content-Type: application/json
Accept: text/event-stream

{
  "model": "chat",
  "stream": true,
  "messages": [
    {
      "role": "user",
      "content": "상품 설명을 세 문단으로 작성해 줘."
    }
  ]
}
```

응답은 `data:` 이벤트가 연속해서 전달되는 SSE 형식이며, 마지막 이벤트는 반드시 `[DONE]`이어야 한다.

```text
data: {"id":"chatcmpl-281","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"첫"}}]}

data: {"id":"chatcmpl-281","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":" 문장"}}]}

data: {"id":"chatcmpl-281","object":"chat.completion.chunk","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

data: [DONE]

```

스트리밍 클라이언트는 다음 규칙을 반드시 지켜야 한다.

- `data: [DONE]`을 수신한 경우에만 정상 완료로 처리한다.
- `finish_reason: "stop"` 청크를 받았더라도 `[DONE]`이 없으면 정상 완료로 확정하지 않는다.
- `[DONE]` 전에 연결이 끊기거나 전송 오류가 발생하면 해당 응답을 **불완전한 실패**로 처리한다.
- 이미 받은 일부 문장을 정상 결과로 저장하거나 사용자에게 완료된 답변처럼 표시하지 않는다.
- 첫 SSE 이벤트가 전달된 뒤에는 서버가 다른 provider의 응답을 이어 붙이지 않는다. 중간 장애가 발생하면 스트림은 그대로 실패한다.

`stream` 필드는 생략하거나 JSON boolean 값만 사용할 수 있다.

```json
{"stream": true}
```

다음 값은 모두 `400`이다.

```json
{"stream": "true"}
```

```json
{"stream": 1}
```

```json
{"stream": null}
```

---

## 7. 이미지 분석

이미지가 포함된 요청은 `vision` 별칭과 OpenAI의 다중 콘텐츠 메시지 형식을 사용한다.

```json
{
  "model": "vision",
  "messages": [
    {
      "role": "user",
      "content": [
        {
          "type": "text",
          "text": "이 이미지의 핵심 내용을 설명해 줘."
        },
        {
          "type": "image_url",
          "image_url": {
            "url": "data:image/jpeg;base64,<BASE64_IMAGE>"
          }
        }
      ]
    }
  ]
}
```

이미지는 base64 data URL로 인라인 전송한다.

검색 모듈은 기존 OpenAI Responses 요청 구조와 모델 설정을 그대로 사용할 수 있다.

```json
{
  "model": "gpt-5.4-nano",
  "instructions": "이미지에 직접 보이는 사실만 설명해 줘.",
  "input": [
    {
      "role": "user",
      "content": [
        {"type": "input_text", "text": "검색에 사용할 특징을 설명해 줘."},
        {
          "type": "input_image",
          "image_url": "data:image/jpeg;base64,<BASE64_IMAGE>"
        }
      ]
    }
  ]
}
```

`/v1/responses`의 현재 지원 범위는 비스트리밍 이미지 분석이다. 성공 응답의 분석 문장은 `output[].content[]` 중 `type`이 `output_text`인 항목의 `text`에서 읽는다.

생성이 정상 종료된 응답은 `status`가 `completed`다. 토큰 한도 등으로 생성이 중단되면 `status`가 `incomplete`가 되고 `incomplete_details.reason`(`max_output_tokens` 또는 `content_filter`)에 사유가 담긴다. `completed`가 아닌 응답의 텍스트는 잘린 결과일 수 있으므로 완전한 분석으로 저장하지 않는다.

요청 본문 상한 32MiB는 원본 이미지 파일의 크기가 아니라 **base64 문자열과 JSON 구조를 모두 포함한 최종 HTTP 본문 크기**에 적용된다. base64 인코딩 결과는 일반적으로 원본 바이너리보다 약 33% 커진다. 검색 모듈이 허용하는 20MiB 파일은 Base64 인코딩 시 약 26.7MiB가 되므로 이 범위에 들어오지만, 다른 메타데이터를 포함한 최종 요청이 32MiB를 넘으면 `413`으로 거절된다.

---

## 8. 임베딩

### 8.1 요청 형식

단건 입력은 문자열로 전달한다.

```json
{
  "model": "embed",
  "input": "임베딩할 문장",
  "encoding_format": "float"
}
```

다건 입력은 하나 이상의 문자열이 포함된 배열로 전달한다.

```json
{
  "model": "embed",
  "input": [
    "첫 번째 문장",
    "두 번째 문장"
  ]
}
```

배치 응답의 `data[].index`는 입력 항목의 위치를 나타낸다. 결과를 원문과 연결할 때는 `index`를 기준으로 매칭한다.

### 8.2 응답 형식

```json
{
  "object": "list",
  "data": [
    {
      "object": "embedding",
      "index": 0,
      "embedding": [0.012, -0.034]
    }
  ],
  "model": "embed",
  "usage": {
    "prompt_tokens": 3,
    "total_tokens": 3
  }
}
```

위 예시는 표현 형식만 보여 주기 위해 벡터 값을 축약했다. 실제 `embedding` 배열의 길이는 별칭이 결정한다 — `embed`는 **1024개**, `text-embedding-3-small`은 **1536개**의 숫자가 들어간다. 두 별칭은 같은 내부 모델의 같은 벡터 공간을 쓰고, `text-embedding-3-small`은 1024차원 벡터 뒤에 512개의 0을 붙여 OpenAI와 같은 차원을 맞춘 것이다(zero-padding은 코사인 유사도를 보존한다).

### 8.3 인코딩 형식

`encoding_format`을 생략하면 기본값은 `float`다.

```json
{
  "model": "embed",
  "input": "임베딩할 문장"
}
```

`"encoding_format": "base64"`를 지정하면 각 벡터는 float32 리틀엔디언 바이트 배열을 base64로 인코딩한 문자열로 반환된다.

```text
embed:                   1024차원 × float32 4바이트 = 4096바이트
text-embedding-3-small:  1536차원 × float32 4바이트 = 6144바이트
```

base64 형식은 JSON 숫자 배열보다 전송량을 줄일 수 있지만, 클라이언트가 다음 순서로 디코딩해야 한다.

1. base64 문자열을 바이트 배열로 디코딩한다.
2. 바이트 배열을 리틀엔디언 float32 값으로 해석한다.
3. 결과 원소 수가 사용한 별칭의 계약 차원(1024 또는 1536)인지 검증한다.

### 8.4 별칭별 고정 차원 계약

임베딩 차원과 벡터 공간은 기존 색인과의 호환성을 보장하기 위한 고정 계약이다. `embed`는 1024차원, `text-embedding-3-small`은 1536차원이며, 클라이언트가 임의로 차원을 변경하거나 다른 임베딩 모델의 결과로 대체해서는 안 된다.

임베딩 모델이 달라지면 차원이 같더라도 벡터 공간이 다를 수 있다. 서로 다른 모델의 벡터를 같은 색인에 섞으면 기존 벡터와 새 벡터 사이의 유사도 비교가 의미를 잃는다.

따라서 다음 원칙을 지킨다.

- 검색 색인의 벡터 차원은 사용하는 별칭의 계약 차원으로 고정한다.
- 임베딩 요청이 실패하면 재시도한다.
- 장애를 우회하기 위해 다른 임베딩 모델을 사용하지 않는다.
- 임베딩 모델이나 벡터 공간이 변경되면 전체 재색인이 필요하다고 본다.

---

## 9. 요청 제한, 응답 기한, 캐시 정책

### 9.1 제한과 기한

| 항목 | 기준 | 초과 시 동작 |
|---|---:|---|
| 요청 본문 크기 | 32MiB | `413` |
| 로컬 추론 응답 시작 | 90초 | 가능한 요청에 한해 폴백 시도 |
| 전체 응답 시작 | 115초 | `504` |

요청 본문 크기는 `Content-Length` 헤더의 존재 여부와 관계없이 실제로 수신한 바이트 수를 기준으로 검사한다. 제한을 초과한 요청은 JSON 파싱이나 추론 호출 전에 거절된다.

응답 시작 기한은 서버가 요청을 받은 시점부터 계산한다.

| 응답 방식 | 서버가 판단하는 “응답 시작” |
|---|---|
| 일반 응답 | 전체 응답 본문을 확보한 시점 |
| 스트리밍 | 첫 번째 유효한 SSE 이벤트를 확보한 시점 |

첫 SSE 이벤트가 전송된 이후의 생성 시간은 115초 응답 시작 기한에 포함되지 않는다. 긴 생성에서 스트리밍이 유리한 이유다.

클라이언트는 서버가 최초 응답을 시작할 때까지 115초보다 충분히 오래 기다릴 수 있도록 타임아웃을 설정한다. 클라이언트 타임아웃이 더 짧으면 서버가 정상적으로 처리 중인 요청을 호출 측에서 먼저 종료하게 된다.

### 9.2 캐시 정책

다음 경로의 응답에는 `no-store` 정책이 적용된다.

```text
/v1/*
/health
```

클라이언트, 프록시, CDN은 해당 응답을 캐시하지 않는다. 같은 프롬프트를 다시 보내더라도 항상 같은 결과가 반환된다는 보장은 없다.

---

## 10. 오류 처리와 재시도 정책

### 10.1 오류 본문

게이트웨이가 생성하는 오류는 OpenAI 호환 형식을 사용한다.

```json
{
  "error": {
    "message": "오류 설명",
    "type": "invalid_request_error",
    "param": null,
    "code": "오류 코드"
  }
}
```

클라이언트는 **HTTP 상태 코드와 `error.code`를 기준으로 분기**한다. `error.message`는 사람이 읽기 위한 설명이므로 문구가 바뀔 수 있으며, 프로그램 로직에서 파싱하지 않는다.

### 10.2 상태 코드별 대응

| 상태 | 의미 | 권장 대응 |
|---|---|---|
| `400` | JSON, 필드 형식, 모델 별칭 또는 엔드포인트 조합 오류 | 요청을 수정한다. 같은 요청의 단순 재시도는 의미가 없다 |
| `401` | API 키가 없거나 올바르지 않거나 폐기됨 | 추론 서버 관리자가 발급한 활성 키인지 확인한다. 발급 또는 교체가 필요하면 관리자에게 요청한다. 같은 키로 단순 재시도해도 해결되지 않는다 |
| `413` | 최종 요청 본문이 32MiB 초과 | 입력을 줄인다. 이미지는 리사이즈·압축한다 |
| `502` | provider가 유효한 응답을 반환하지 못함 | 재시도할 수 있다 |
| `503` | 게이트웨이 또는 추론 기능을 현재 사용할 수 없음 | 재시도할 수 있다 |
| `504` | 115초 안에 응답을 시작하지 못함 | 재시도할 수 있다. 반복되면 스트리밍 전환을 검토한다 |

일부 provider 오류는 provider가 반환한 HTTP 상태와 오류 정보가 그대로 전달될 수 있다. 이 경우에도 오류 본문은 위의 `error` 객체 형식을 따른다.

스트리밍이 시작된 뒤 발생한 전송 장애는 일반 JSON 오류 응답으로 처리되지 않을 수 있다. 스트리밍 클라이언트는 HTTP 상태만으로 성공을 판단하지 말고, 반드시 `[DONE]` 수신 여부까지 확인한다.

---

## 11. 폴백 동작

추론 서버는 로컬 추론 환경에 장애가 발생하더라도 주요 생성 기능을 유지할 수 있도록 일부 요청에 폴백을 적용한다. 폴백은 서버가 처리하며 클라이언트가 별도의 provider를 선택할 필요는 없다.

| 모델 별칭 | 로컬 추론 장애 시 동작 |
|---|---|
| `chat` | 외부 모델로 자동 우회 |
| `vision` | 외부 모델로 자동 우회 |
| `gpt-5.4-nano` | `vision`과 같은 정책으로 외부 모델로 자동 우회 |
| `embed` | 다른 모델로 우회하지 않음. `502` 또는 `503` 반환 |
| `text-embedding-3-small` | `embed`와 같은 정책으로 우회하지 않음. `502` 또는 `503` 반환 |

`chat`과 `vision`이 폴백되면 요청은 성공할 수 있지만 응답의 `model` 값은 평소와 달라질 수 있다. 이는 정상적인 동작이다. `model` 값은 관측이나 진단 목적으로 기록할 수 있지만, 서비스 동작을 결정하는 분기 조건으로 사용하지 않는다. `gpt-5.4-nano` Responses 응답의 `model`은 폴백과 무관하게 요청 별칭을 그대로 돌려준다.

임베딩이 폴백하지 않는 이유는 벡터 공간의 일관성을 지키기 위해서다. 다른 임베딩 모델의 결과를 기존 색인에 섞으면 차원이 같더라도 검색 품질과 유사도 비교의 의미가 깨질 수 있다.

따라서 색인 파이프라인은 임베딩 실패를 다음과 같이 처리한다.

```text
임베딩 실패 → 재시도 또는 작업 보류
임베딩 실패 → 다른 임베딩 모델로 대체 금지
```

스트리밍 요청의 폴백은 첫 SSE 이벤트를 클라이언트에 보내기 전에만 가능하다. 첫 이벤트가 전송된 이후에는 provider를 변경하지 않으며, 중간 장애가 발생해도 다른 provider의 출력을 기존 스트림에 이어 붙이지 않는다.

---

## 12. 서비스별 적용 지침

### 12.1 AI 서비스 (`ai`)

적용 환경은 Spring AI, RAG, WebFlux SSE를 기준으로 한다.

- OpenAI 호환 클라이언트를 사용하는 경우 Base URL을 자체 추론 서버 주소로 설정하고, 추론 서버 관리자에게 발급받은 API 키를 사용한다.
- 요청의 모델명은 실제 모델명이 아니라 `chat` 또는 `vision` 별칭을 사용한다.
- 챗봇과 긴 생성 기능은 스트리밍을 기본값으로 한다.
- WebFlux로 SSE를 프런트엔드에 중계할 때 `[DONE]`을 받지 못한 스트림을 정상 완료 이벤트로 변환하지 않는다.
- 이미 일부 텍스트를 전달한 상태에서 업스트림 스트림이 실패하면, 사용자 인터페이스에도 불완전 종료 상태를 명확히 전달한다.
- 사고 과정이 필요한 요청에만 `"reasoning_effort": "high"`를 지정한다.
- `reasoning_effort`를 생략하는 것이 기본이며, 생략하면 별도의 사고 과정 없이 바로 응답한다. `high`를 사용하면 응답 시간이 길어지고 토큰 사용량이 증가할 수 있다.

### 12.2 검색 서비스 (`search`)

Elasticsearch 기반 색인과 검색에서는 다음 계약을 고정한다.

- 검색 모듈은 `text-embedding-3-small` 별칭을 사용하며 `dense_vector` 매핑의 `dims`는 **1536**으로 설정한다.
- 임베딩 실패는 재시도 가능한 작업 실패로 처리한다.
- 실패한 임베딩을 다른 모델의 벡터로 대체하지 않는다.
- 다건 색인은 여러 문장을 `input` 배열 한 번에 보내 왕복 횟수를 줄인다.
- 응답의 `data[].index`를 기준으로 원문과 임베딩을 연결한다.
- 배치 요청의 최종 JSON 본문이 32MiB를 넘지 않도록 배치 크기를 제한한다.

---

## 13. 연동 검증 절차

새로운 서비스가 추론 서버를 연동할 때는 다음 순서로 검증한다.

### 13.1 최신 계약 확인

브라우저 또는 HTTP 클라이언트에서 다음 경로를 연다.

```text
GET https://api.inferway.xyz/docs
```

인증 없이 열려야 하며, 이 문서와 차이가 있으면 `/docs`의 내용을 따른다.

### 13.2 서버 상태와 인증 확인

추론 서버 관리자에게 발급받은 유효한 API 키를 사용해 상태 확인 요청을 보낸다.

```http
GET /health HTTP/1.1
Host: api.inferway.xyz
Authorization: Bearer <API_KEY>
```

정상 응답은 다음과 같다.

```json
{"status": "ok"}
```

결과는 다음 기준으로 해석한다.

| 결과 | 판단 |
|---|---|
| `200`과 `{"status":"ok"}` | 서버와 인증이 정상이다. 이후 요청 형식을 점검한다 |
| `401` | 발급받은 API 키가 누락되었거나 올바르지 않거나 폐기된 상태다 |
| 응답 없음 | 서버 또는 네트워크 경로 문제를 의심한다 |

### 13.3 기본 생성 요청 확인

`model`에 `chat` 별칭을 사용해 짧은 일반 응답 요청을 보낸다. 실제 전송 URL이 다음과 정확히 일치하는지 확인한다.

```text
https://api.inferway.xyz/v1/chat/completions
```

### 13.4 스트리밍 종료 확인

스트리밍 요청을 보내고 다음 두 조건을 모두 검증한다.

1. 응답이 SSE 이벤트로 순차 전달된다.
2. 마지막에 `data: [DONE]`이 도착한다.

연결을 인위적으로 중단한 테스트에서도 클라이언트가 해당 응답을 성공으로 저장하지 않는지 확인한다.

### 13.5 임베딩 계약 확인

사용하는 임베딩 별칭으로 요청을 한 번 실행하고 다음 항목을 검사한다.

- 응답 벡터 길이가 별칭의 계약 차원(`embed` 1024, `text-embedding-3-small` 1536)인지 확인한다.
- 배치 요청에서 `data[].index`가 입력과 올바르게 연결되는지 확인한다.
- 검색 색인의 `dense_vector.dims`가 사용하는 별칭의 계약 차원과 같은지 확인한다.

---

## 14. 문제 해결 가이드

| 증상 | 우선 확인할 항목 |
|---|---|
| `404` | 최종 URL에서 `/v1`이 누락되거나 두 번 붙지 않았는지 확인 |
| `401` | Bearer 헤더 형식과 추론 서버 관리자가 발급한 키의 유효 상태를 확인한다. 폐기된 키는 사용할 수 없다 |
| `400` | 모델 별칭, 엔드포인트 조합, `stream` 타입, JSON 필드 확인 |
| `413` | base64와 JSON을 포함한 최종 본문 크기 확인 |
| `502` 또는 `503` | provider 또는 추론 서버 상태 확인 후 재시도 |
| `504` | 클라이언트 타임아웃과 스트리밍 사용 여부 확인 |
| 스트림이 문장 중간에 종료됨 | `[DONE]` 수신 여부 확인. 없으면 실패로 처리 |
| 응답의 `model` 값이 달라짐 | 폴백 또는 서버 모델 교체 가능성. 비즈니스 로직에는 영향 없어야 함 |
| 임베딩 요청만 실패함 | 임베딩은 폴백하지 않으므로 재시도 또는 작업 보류 |

문제가 발생했을 때의 기본 진단 순서는 다음과 같다.

1. `/docs`에서 최신 계약을 확인한다.
2. 인증을 포함해 `/health`를 호출한다.
3. 실제 전송된 최종 URL을 확인한다.
4. HTTP 상태와 `error.code`를 확인한다.
5. 스트리밍 요청이라면 `[DONE]` 수신 여부를 확인한다.

---

## 15. 최종 체크리스트

### 공통

- [ ] 추론 서버 관리자에게 API 키 발급을 직접 요청했다.
- [ ] 관리자가 발급한 현재 유효한 API 키를 설정했다.
- [ ] 폐기된 API 키를 사용하지 않는다.
- [ ] 인증이 필요한 요청에 `Authorization: Bearer <API_KEY>` 헤더를 포함한다.
- [ ] API 키를 환경변수 또는 시크릿 저장소에서 주입한다.
- [ ] API 키가 코드, 설정 파일, 커밋에 포함되지 않았다.
- [ ] 실제 요청 URL이 `https://api.inferway.xyz/v1/...` 형식으로 전송된다.
- [ ] `model`에는 지원되는 기능 별칭 또는 검색 호환 모델명만 사용한다.
- [ ] 응답의 `model` 값으로 비즈니스 로직을 분기하지 않는다.
- [ ] 클라이언트가 최초 응답을 115초보다 충분히 오래 기다릴 수 있다.
- [ ] 오류 처리는 HTTP 상태와 `error.code`를 기준으로 한다.
- [ ] `error.message` 문자열을 파싱하지 않는다.
- [ ] `/v1/*`와 `/health` 응답을 캐시하지 않는다.
- [ ] `/health` 요청에도 인증 헤더를 포함한다.

### 스트리밍

- [ ] 긴 생성에는 `"stream": true`를 사용한다.
- [ ] `stream` 값은 JSON boolean으로 보낸다.
- [ ] `data: [DONE]`을 받아야만 성공으로 처리한다.
- [ ] `[DONE]` 없이 종료된 응답을 완성된 결과로 저장하지 않는다.
- [ ] 중간에 끊긴 일부 텍스트를 정상 완료 답변처럼 표시하지 않는다.

### 이미지

- [ ] 이미지를 base64 data URL로 인라인 전송한다.
- [ ] base64와 JSON을 포함한 최종 본문이 32MiB 이하인지 확인한다.
- [ ] 큰 이미지는 전송 전에 리사이즈하거나 압축한다.

### 검색·임베딩

- [ ] Elasticsearch `dense_vector.dims`가 사용하는 임베딩 별칭의 계약 차원과 같다.
- [ ] 실제 응답 벡터 길이를 별칭의 계약 차원으로 검증한다.
- [ ] 배치 응답을 `data[].index`로 원문과 연결한다.
- [ ] 임베딩 실패를 다른 임베딩 모델로 대체하지 않는다.
- [ ] 임베딩 실패는 재시도 또는 작업 보류로 처리한다.
- [ ] provider 전환·롤백 시 기존 벡터와 새 벡터를 같은 색인에 섞지 않고 전체 재색인한다.
- [ ] 배치 요청이 32MiB를 넘지 않도록 크기를 제한한다.
