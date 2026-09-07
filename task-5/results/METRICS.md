# Обоснование выбранных метрик

## Подход

TradeWare сочетает request-driven API и асинхронный batch. Поэтому одной методологии RED недостаточно:

- для `Report Intake Service` используются **RED / Four Golden Signals**;
- для Spring Batch — специализированные job/step/queue/SLA-метрики;
- для инфраструктуры и БД — **USE** (`Utilization`, `Saturation`, `Errors`).

Метрики собираются Prometheus по pull-модели. Spring Boot экспортирует их через Micrometer/Actuator (`/actuator/prometheus`), Kubernetes/Cloud SQL/очередь — через соответствующие exporters/integration.

## API

- `http_server_requests_seconds_count{method,uri,status}` — request rate и error rate;
- `http_server_requests_seconds_sum{method,uri,status}` — суммарная длительность для average latency;
- `http_server_requests_seconds_bucket{method,uri,status,le}` — latency/p95/p99;
- `tradeware_uploads_total{result}` — бизнесово-технический результат загрузок;
- `tradeware_upload_file_size_bytes` — изменение профиля входных файлов.

Эти показатели отвечают на вопросы: доступен ли API, сколько запросов приходит, растёт ли число ошибок и время ответа.

Для расчёта p95/p99 через `*_bucket` в Spring Boot нужно включить публикацию histogram для `http.server.requests`:

```yaml
management:
  metrics:
    distribution:
      percentiles-histogram:
        http.server.requests: true
```

## Spring Batch

- `tradeware_batch_jobs_started_total`;
- `tradeware_batch_jobs_completed_total`;
- `tradeware_batch_jobs_failed_total{error_code}`;
- `tradeware_batch_job_duration_seconds{size_class}` — Histogram;
- `tradeware_batch_chunk_duration_seconds` — Histogram;
- `tradeware_batch_rows_read_total`;
- `tradeware_batch_rows_written_total`;
- `tradeware_batch_rows_skipped_total{reason}`;
- `tradeware_batch_retries_total{reason}`;
- `tradeware_batch_active_jobs`.

Они показывают throughput, стабильность, стоимость retry и позволяют проверить бизнес-требование: средняя обработка отчёта на 2 000 строк должна укладываться в 30 секунд.

Для SLA используется низкокардинальная метка `size_class` с фиксированными значениями: `le_2000`, `2001_10000`, `gt_10000`. Алерт на 30 секунд считается только для `size_class="le_2000"`, чтобы крупные файлы не искажали требование к типовым отчётам на 2 000 строк.

## Очередь и backpressure

- `tradeware_queue_depth`;
- `tradeware_queue_oldest_message_age_seconds`;
- consumption rate;
- publish/consume errors.

Для burst 100–150 загрузок это ключевые показатели насыщения. Даже при нормальном CPU очередь может уже расти и нарушать SLA.

## PostgreSQL

Нужно собирать:

- active/max connections;
- pool utilization;
- query/transaction duration;
- transaction rate;
- lock waits/deadlocks;
- disk I/O;
- errors/timeouts.

Рост числа workers без контроля способен перегрузить PostgreSQL, поэтому DB saturation является обязательным ограничителем масштабирования.

## Kubernetes/JVM

- CPU utilization/throttling;
- memory working set и JVM heap;
- OOMKilled/restarts;
- pending pods;
- configured/active worker concurrency;
- filesystem/volume errors.

## Cardinality

В Prometheus labels нельзя помещать `file_id`, `user_id`, trace id или URL с динамическими идентификаторами: это создаёт высокую cardinality. Для расследования конкретного файла используются Kibana и Jaeger. В labels остаются ограниченные множества: `service`, `environment`, `status`, `error_code`, `reason`, `method`, нормализованный `uri`.

## Основные алерты

- failed batch job после retry — critical;
- queue oldest age выше допустимого — critical;
- устойчивый рост queue depth — warning;
- средняя batch duration > 30 сек — warning;
- Intake API down / высокий 5xx — critical;
- DB pool saturation > 80% — warning;
- повышенный skip/retry rate — warning.

Стартовые пороги являются гипотезами и должны быть откалиброваны нагрузочным тестом и SLO. Пример PromQL находится в `prometheus-alerts.yml`.
