# Проектная работа 5 спринта — масштабируемая пакетная обработка и observability

Результаты выполнения пяти заданий находятся в директориях `task-1/results` … `task-5/results`.

Рабочая ветка с итоговым решением: `submission`.

Архитектурные решения сведены в единый [Architecture Decision Log](ADR-LOG.md).

## Состав решения

| Задание | Решение | Основные файлы |
|---|---|---|
| Task 1 | Apache Airflow | `task-1/results/ADR-001-batch-processing-platform.md`, `README.md`, `docker-compose.yml`, `dags/marketing_etl.py`, `DEMO.md` |
| Task 2 | Kubernetes CronJob для ежедневной выгрузки прайс-листов | `task-2/results/ADR-002-b2b-price-list-batch-processing.md`, `README.md`, `IMPLEMENTATION_PLAN.md`, C4 `.puml/.drawio/.png` |
| Task 3 | Kubernetes CronJob + Python exporter + PostgreSQL demo | `task-3/results/app`, `task-3/results/k8s`, `DEMO.md` |
| Task 4 | Асинхронный ETL на Spring Batch | `task-4/results/ADR-003-spring-batch-etl.md`, `c4-to-be.puml`, `c4-to-be.drawio`, `c4-to-be.png` |
| Task 5 | Prometheus/Grafana + ELK + OpenTelemetry/Jaeger + Alertmanager | `task-5/results/monitoring-logging-alerting.md`, `prometheus-alerts.yml`, `c4-observability.puml` |

## Что уже подготовлено

- технологическое обоснование по всем заданиям;
- ADR-001, ADR-002 и ADR-003 с единым Architecture Decision Log;
- POC Airflow с чтением источника, условным ветвлением, retry и success/failure email;
- C4 System Context и container-level To Be для Task 2;
- Dockerfile, exporter и Kubernetes CronJob для Task 3;
- ADR по Spring Batch, локальный POC и To Be архитектура TradeWare;
- проект metrics/logs/traces/alerting для Task 5;
- пример Prometheus alert rules.

## Что необходимо выполнить локально перед финальной сдачей

Два требования нельзя корректно подменить статическими файлами — ревьюеру нужно показать **реальный запуск**:

1. **Task 1:** запустить Airflow POC по `task-1/results/DEMO.md` и добавить реальные скриншоты Airflow + MailHog.
2. **Task 3:** запустить CronJob/Job в MiniKube по `task-3/results/DEMO.md` и добавить реальные скриншоты `kubectl`, логов и сформированного CSV.

После этого следует проверить все файлы, создать pull request из `submission` в `main` и уже ссылку на этот PR отправлять на ревью.

## Диаграммы

Диаграммы хранятся как редактируемые Draw.io (`*.drawio`), PlantUML/C4 source (`*.puml`) и, где подготовлено, PNG-экспорт (`*.png`). PlantUML-исходники можно открыть в IDE с PlantUML plugin или отрендерить PlantUML CLI. Используется C4-PlantUML.

При переносе результатов в финальный репозиторий сохраняйте структуру `task-N/results` исходного репозитория Яндекс Практикума.
