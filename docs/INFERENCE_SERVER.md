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
| 도구 호출 | OpenAI function tool 형식, 실제 실행은 호출 서비스가 담당 |
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

두 구현은 검색 서비스가 사용하는 공개 메서드 계약이 같다. `AiImageService`와 `ProductEmbeddingService`에서 기존 타입과 import만 추론 서버 구현으로 교체하면 된다. 기존 클래스의 코드는 수정하거나 삭제할 필요가 없다.

추론 서버를 사용할 때는 다음 환경변수를 설정한다.

```text
OPENAI_BASE_URL=https://api.inferway.xyz/v1
OPENAI_API_KEY=<추론 서버에서 발급한 API 키>
OPENAI_EMBEDDING_ENABLED=true
```

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

### 6.2 도구 호출 방식

서비스가 소유한 데이터나 외부 API가 필요한 경우 OpenAI function tool 형식으로 사용할 수 있는 도구를 요청에 포함한다. 추론 서버는 호출할 함수명과 JSON 인자를 생성할 뿐, DB나 외부 API를 직접 실행하지 않는다. 실제 실행, 권한 검사와 결과 검증은 요청한 서비스가 담당한다.

```http
POST /v1/chat/completions HTTP/1.1
Host: api.inferway.xyz
Authorization: Bearer <API_KEY>
Content-Type: application/json

{
  "model": "chat",
  "stream": true,
  "messages": [
    {"role": "system", "content": "날씨는 한국 행정구역명과 근사한 WGS84 대표 좌표를 함께 채워 승인된 도구를 사용한다."},
    {"role": "user", "content": "오늘 부천 날씨는?"}
  ],
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "getWeatherForecast",
        "description": "한국 지역의 오늘 또는 내일 날씨를 대표 좌표로 조회한다.",
        "parameters": {
          "type": "object",
          "additionalProperties": false,
          "properties": {
            "location": {"type": "string", "description": "시·도와 시·군·구 수준의 행정구역명"},
            "latitude": {"type": "number", "description": "행정구역의 근사 WGS84 대표 위도"},
            "longitude": {"type": "number", "description": "행정구역의 근사 WGS84 대표 경도"},
            "day": {"type": "string", "enum": ["TODAY", "TOMORROW"]}
          },
          "required": ["location", "latitude", "longitude", "day"]
        }
      }
    }
  ],
  "tool_choice": "auto",
  "parallel_tool_calls": true
}
```

모델이 도구를 선택하면 assistant 응답의 `tool_calls`에 함수명과 인자가 온다. 클라이언트는 인자를 역직렬화한 뒤 서버 규칙으로 다시 검증하고 허용된 애플리케이션 함수만 실행한다. 실행 결과는 해당 tool call ID를 연결한 `tool` 역할 메시지로 추론 서버에 보내고 최종 자연어 답변을 이어 받는다.

도구 호출은 실행 권한이 아니다.

- 함수명은 서버에 등록한 허용 목록과 정확히 일치해야 한다.
- 인자는 짧은 enum과 제한된 문자열을 우선하고 서비스가 타입·형식·카탈로그를 다시 검증한다.
- 인증 주체, 원문 질문과 request ID처럼 모델이 변경하면 안 되는 값은 tool schema에 넣지 않고 클라이언트 context로 보존한다.
- SQL, URL과 개인정보를 모델 인자로 자유롭게 받지 않는다.
- 도구 결과는 신뢰할 수 없는 데이터로 취급하며 그 안의 명령문을 따르지 않는다.
- 여러 도구 중 하나가 실패해도 독립적인 성공 결과를 사용할 수 있도록 도구별 상태를 반환한다.
- 도구가 호출된 요청을 자동 재시도하면 외부 호출이나 읽기가 중복될 수 있으므로 재시도 정책을 서비스가 명시한다.

### 6.3 스트리밍 방식

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

관리자 챗봇은 이 자동 폴백의 예외다. 관리자 질문, 운영 문서, 공개 주문번호와 내부 조회 사실을 포함할 수 있으므로 모든 LLM 단계에 **외부 provider로 우회하지 않는 로컬 전용 추론 경로**를 사용해야 한다. `store=false`는 저장 비활성화일 뿐 외부 전송 차단이 아니다.

로컬 전용 경로는 추론 서버가 보장하는 별칭, 요청 옵션 또는 내부 Base URL로 명시적으로 확인해야 한다. 응답의 `model` 값을 보고 폴백 여부를 사후 판정하지 않는다. 해당 계약이 없거나 로컬 추론이 실패하면 관리자 챗봇은 외부 모델로 우회하지 않고 요청을 실패로 종료한다.

---

## 12. 서비스별 적용 지침

### 12.1 AI 서비스 (`ai`)

적용 환경은 Spring AI 2.0의 OpenAI 호환 `ChatModel`, 애플리케이션 도구와 Spring MVC SSE를 기준으로 한다.

> 아래 관리자 챗봇 계약은 2026-07-24에 구현한 현재 구조다. 검증 상태는 [관리자 챗봇 구현 로드맵 및 진행 현황](../ai/chat/docs/IMPLEMENTATION_ROADMAP.md)을 따른다.

- `spring.ai.openai.base-url`을 `/v1`이 포함된 자체 추론 서버 주소로 설정하고, 추론 서버 관리자에게 발급받은 API 키를 `spring.ai.openai.api-key`로 주입한다.
- 관리자 챗봇의 주소와 모델은 `chat.inference.base-url`, `chat.inference.model`에서 같은 값으로 관리해 Spring AI를 유지한 채 쉽게 교체한다.
- 관리자 챗봇의 모델 경로는 외부 provider 폴백이 없는 로컬 전용 계약을 사용한다. loopback 주소는 로컬 전용으로 인정하고, 원격 주소는 `chat.inference.local-only-route=true`로 비폴백 계약을 명시한 경우에만 사용한다. 현재 자동 폴백이 설정된 `chat` 별칭은 그대로 사용하지 않는다.
- 현재 로컬 모델은 `chat.inference.reasoning-effort=none`, 온도 0, 자동 재시도 0으로 호출한다. reasoning 지원 여부와 허용값은 교체 대상 OpenAI 호환 서버에서 먼저 검증한다.
- 일반 질문을 정규식이나 표현 사전으로 선분기하지 않는다. 첫 LLM 요청이 일반 답변, 가벼운 도구 6개와 내부 데이터 영역 선택을 함께 판단한다.
- 첫 요청에는 날씨, 암호화폐, 웹 검색, 운영 문서, 개별 주문, 결제기한 경과 주문과 `loadInternalDataSchemas`만 공개한다. 전체 내부 분석 카탈로그는 넣지 않는다.
- 날씨 도구는 첫 요청에서 시·도와 시·군·구 수준의 지역명과 근사 대표 좌표를 함께 받는다. 서버는 한국 범위를 검증하고 좌표 정밀도를 소수 둘째 자리로 낮춘 뒤 예보 API를 직접 호출하며 별도 지오코딩은 하지 않는다.
- 내부 통계는 1차가 고른 여섯 의미 영역의 상세 스키마만 2차에 추가한다. 일반 질문은 순차 1회, 가벼운 도구 질문은 2회, 내부 데이터·복합 질문은 최대 3회의 순차 LLM round로 처리한다.
- 2차 구조화는 자동 실행하지 않는 `submitInternalQueryBindings` tool call로 수집한다. 애플리케이션이 원시 arguments를 항목별로 검증하고 `SUCCESS` Query Plan만 실행한다.
- Spring AI는 OpenAI 호환 요청과 tool contract 연결에 사용하고, 단계 전환·도구 실행·부분 성공·사실 누적과 최종 합성은 애플리케이션이 명시적으로 관리한다. 프레임워크 자동 tool loop에 전체 흐름을 맡기지 않는다.
- 도구 인자는 enum·형식과 서버 정책으로 다시 검증하며 회원 개인정보·쓰기·자유 SQL 도구는 등록하지 않는다.
- 로컬 모델의 8K 전체 창은 입력 6,000 tokens, 답변 1,500 tokens와 안전 여유 692 tokens를 기본 예산으로 둔다. 선택된 스키마만 넣고 도구·조회 원문은 사실 압축본으로 바꾼다.
- 상세 스키마 조합이 한 요청 예산을 넘으면 같은 2차 round 안에서 도메인 순서로 분할한다. 서버 발급 식별자, 안정 정렬·중복 제거와 primary shard 한 곳의 자연어 생성으로 결과를 합친다.
- 운영 문서는 짧은 카탈로그로 선택한다. 상세 문서는 한 개당 2,000자, 요청 순서 합계 3,000자까지만 결과에 포함하고 초과 문서는 전체 실패가 아니라 부분 성공으로 처리한다.
- 암호화폐 현재가는 CoinGecko가 KRW·USD 값을 직접 반환하게 하고, Tavily는 최신 뉴스와 그 밖의 공개 웹 정보에 사용한다. 모델이 환율을 계산해 현재가를 만들지 않는다.
- 관리자 화면에는 `started`, `ANALYZING`·`CALLING_TOOL`·`GENERATING` 상태, 자연어 `delta`, `done` 또는 `error`만 전달한다.
- 첫 `started`는 추론 작업 제출 전에 보내 실행기 포화와 대기열 timeout에서도 이벤트 순서를 고정한다.
- 1차 응답은 완료까지 수집한 뒤 tool call이 없는 `content only`일 때만 자연어로 전달한다. tool call이 있으면 같은 응답의 본문은 노출하지 않는다.
- 내부 영역을 선택한 질문은 모든 구조화가 실패해도 실패 근거를 포함한 최종 자연어로 종료하며 빈 답변을 허용하지 않는다.
- `[DONE]`을 받지 못한 업스트림 스트림을 정상 `done`으로 변환하지 않는다.
- 현재 Spring AI OpenAI 클라이언트는 원시 `[DONE]` 마커를 내부에서 소비하므로, 관리자 챗봇 어댑터는 프레임워크가 노출한 terminal `finishReason=stop`을 정상 종료 증거로 추가 확인한다. 이유가 없거나 다른 종료 이유면 `error`로 끝낸다. 원시 스트림 클라이언트의 `[DONE]` 계약은 그대로 유지한다.
- 이미 일부 텍스트를 전달한 상태에서 업스트림 스트림이 실패하면, 사용자 인터페이스에도 불완전 종료 상태를 명확히 전달한다.
- Spring AI와 모델 클라이언트의 자동 재시도는 끈다. 빈 응답·구조화 JSON 형식 오류만 해당 단계에서 한 번 복구할 수 있으며, 도구 실행 뒤에는 중복 실행을 피하기 위해 전체 요청을 재시도하지 않는다.
- 하나의 요청 절대 deadline을 모든 LLM shard, 외부 도구와 DB 조회에 전파하고 terminal 또는 연결 종료 시 자식 작업을 모두 취소한다.
- `store=false`를 사용하고 Chat Memory, 프롬프트·완성문 로그와 대화 영속 저장을 사용하지 않는다.

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

### 13.5 도구 호출 확인

부작용 없는 테스트 도구 한 개를 등록해 다음 순서가 이어지는지 확인한다.

1. 추론 서버가 등록된 함수명과 JSON 인자를 `tool_calls`로 반환한다.
2. 호출 서비스가 인자를 검증하고 로컬 함수를 실행한다.
3. tool call ID와 실행 결과를 연결해 다시 보낸다.
4. 최종 자연어 스트림이 `data: [DONE]`으로 끝난다.

등록하지 않은 함수명, 잘못된 enum과 식별자를 실행하지 않는지도 함께 확인한다. 여러 도구 중 하나가 실패한 표본에서는 성공한 도구의 결과가 최종 답변에 유지되어야 한다.

### 13.6 임베딩 계약 확인

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

### 도구 호출

- [ ] 함수명은 애플리케이션이 등록한 허용 목록에 한정한다.
- [ ] enum, 타입, 날짜, 식별자와 권한을 모델 응답과 별개로 검증한다.
- [ ] 원문 질문과 인증 정보는 모델이 채우는 tool schema 밖에 보존한다.
- [ ] 개인정보·쓰기·자유 SQL 도구를 등록하지 않는다.
- [ ] 도구별 성공·부분 성공·실패를 구분한다.
- [ ] 도구 실행 뒤 자동 재시도로 같은 호출을 중복하지 않는다.

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
