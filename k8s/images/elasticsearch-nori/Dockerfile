# WS-G(7/10 observability plan) — analysis-nori는 ES 공식 이미지에 기본 미포함이라
# 커스텀 이미지가 필요하다. 플러그인 버전은 ES 버전과 정확히 일치해야 하므로 이미지 태그로
# 고정한다(k8s/13-elasticsearch.yaml의 image 버전과 반드시 동일하게 유지할 것).
#
# 빌드/푸시(수동 1회 — 갱신 빈도가 극히 낮아 CI 편입은 과잉):
#   docker build -t ghcr.io/prgrms-be-adv-devcourse/beadv6_6_let-sgpt_be/elasticsearch-nori:9.2.5 .
#   docker push ghcr.io/prgrms-be-adv-devcourse/beadv6_6_let-sgpt_be/elasticsearch-nori:9.2.5
FROM docker.elastic.co/elasticsearch/elasticsearch:9.2.5
RUN bin/elasticsearch-plugin install analysis-nori --batch
