# Проверка проектной работы перед сдачей

## Task 1

- [x] Обоснован выбор Apache Airflow.
- [x] Описаны интеграции BigQuery, Redshift, Kafka, Spark, API.
- [x] Описаны branching, event-trigger, fallback/trigger rules, retry, email.
- [x] Обосновано облачное развёртывание.
- [x] Есть локальный `docker-compose.yml`.
- [x] DAG читает источник данных.
- [x] DAG анализирует данные и ветвится.
- [x] Настроены retry/backoff.
- [x] Настроены success/failure email через локальный SMTP MailHog.
- [ ] Выполнить локальный запуск и добавить реальные скриншоты/скринкаст.

## Task 2

- [x] Заполнено сравнение Spring Batch / Airflow / K8s Job(CronJob) / Spark.
- [x] Выбор Kubernetes CronJob обоснован размером и простотой задачи.
- [x] Есть C4 System Context To Be (`.puml`, `.drawio`).
- [x] Есть дополнительная container-level схема.
- [x] Описан поток решения.
- [x] Есть верхнеуровневый план имплементации/конфигурации.

## Task 3

- [x] Реализован экспорт одной таблицы PostgreSQL в CSV.
- [x] Есть Dockerfile и зависимости.
- [x] Есть CronJob с `0 20 * * *`, явным timezone, `Forbid`, retry/backoff history limits и resources.
- [x] Есть demo PostgreSQL и данные для MiniKube.
- [x] Есть PVC для POC и Pod для просмотра результата.
- [x] Есть точный сценарий демонстрации.
- [ ] Запустить в MiniKube и добавить реальные скриншоты/видео.

## Task 4

- [x] Проанализированы проблемы As Is.
- [x] Подготовлен ADR по Spring Batch.
- [x] Обоснованы chunk processing, retry/skip/restart, JobRepository, idempotency.
- [x] Учтены 100–150 параллельных загрузок через queue/backpressure и контролируемую concurrency.
- [x] Учтён SLA 2 000 строк / 30 сек и необходимость load test.
- [x] Описано хранение raw/status/business/batch metadata.
- [x] Рассмотрены альтернативы Airflow, SCDF, K8s Job/CronJob, Spark/Dataflow.
- [x] Есть To Be C4 (`.puml`, `.drawio`).

## Task 5

- [x] Есть доработанная C4 observability (`.puml`, `.drawio`).
- [x] Выбраны Prometheus + Grafana для metrics.
- [x] Метрики обоснованы отдельно в `METRICS.md`.
- [x] Для API применены RED/Golden Signals, для ресурсов USE, для batch — job/queue/SLA метрики.
- [x] Есть пример Prometheus alert rules и runbooks.
- [x] Выбран ELK для централизованных логов.
- [x] Способ доставки и хранения логов обоснован отдельно в `LOGGING.md`.
- [x] Описаны mapping, ILM/retention, запрет секретов/PII.
- [x] Добавлен OpenTelemetry + Jaeger для корреляции traces.

## Перед финальным PR

1. Выполнить обе локальные демонстрации.
2. Добавить скриншоты в `task-1/results` и `task-3/results`.
3. Открыть `.drawio` и/или отрендерить `.puml`, визуально проверить стрелки и подписи.
4. Проверить `docker compose config` для Task 1.
5. Проверить `kubectl apply --dry-run=client -f ...` для Task 3.
6. Проверить, что в репозитории нет токенов, настоящих паролей и `.env`.
7. Убедиться, что финальный репозиторий публичный.
8. Создать PR из ветки с результатами в `main` **своего** репозитория и отправить ревьюеру именно ссылку на этот PR.
