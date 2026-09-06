# Runbooks для основных алертов TradeWare

## Batch job failed

1. Открыть Grafana: batch failures, queue age, DB saturation.
2. Найти `job_execution_id`/`file_id` в Kibana.
3. Проверить `error_code`, stacktrace и число retry.
4. По `trace_id` открыть Jaeger и определить медленный/ошибочный dependency.
5. Если ошибка временная и причина устранена — выполнить управляемый restart Spring Batch Job с теми же business parameters.
6. Перед рестартом убедиться, что writer идемпотентен и job не создаст дубли.

## Batch SLA

1. Сравнить job duration с baseline и размером входного файла.
2. Проверить chunk duration, retries, queue age.
3. Проверить PostgreSQL query latency, locks, pool saturation.
4. Проверить CPU throttling/RAM/GC worker pod.
5. Не увеличивать concurrency/chunk без нагрузочного теста: это может ухудшить DB saturation и recovery cost.

## Queue backlog

1. Проверить queue depth и oldest message age.
2. Сравнить publish rate и consume rate.
3. Проверить число healthy workers и их active jobs.
4. Проверить PostgreSQL connection pool и locks.
5. Масштабировать workers только до capacity limit БД.
6. Если consumer сломан — остановить бессмысленное масштабирование, устранить ошибку и затем разгребать backlog.

## DB saturation

1. Проверить active/max connections, pool usage, query duration, locks/deadlocks.
2. Найти top slow batch queries.
3. Проверить, не выросла ли worker concurrency.
4. При необходимости временно снизить число batch workers, чтобы стабилизировать online API.
5. После инцидента пересмотреть индексы, batch writer и chunk size.

## Data quality / high skip rate

1. Разбить skip по `reason`.
2. Найти конкретные `file_id` и row numbers в Kibana.
3. Сравнить с последними изменениями Excel-шаблона/источника.
4. Если нарушено бизнес-правило, не повышать `skipLimit` для сокрытия проблемы — согласовать поведение с владельцем данных.

## Intake API down

1. Проверить pod replicas/readiness/restarts/OOMKilled.
2. Проверить ingress/network и Cloud SQL/GCS/PubSub dependencies.
3. Открыть последние ERROR logs.
4. При релизной регрессии выполнить rollback согласно процедуре deployment.

## Intake API high 5xx rate

1. Сравнить 5xx по нормализованному `uri`/exception class.
2. Проверить latency и saturation.
3. Найти trace_id типовой ошибки в Kibana и открыть Jaeger.
4. Определить, является ли причиной Intake Service или downstream dependency.
