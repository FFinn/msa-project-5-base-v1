# ADR-001. Выбор платформы для пакетной обработки данных

- **Статус:** Accepted
- **Дата:** 2026-09-07
- **Контекст:** проектная работа 5 спринта, Task 1

## 1. Контекст

Маркетинговому отделу требуется расширенная пакетная обработка клиентских и заказных данных из нескольких источников:

- CSV-файлы со статусами доставок;
- PostgreSQL с заказами, платежами и расширенными данными пользователей;
- Kafka с событиями изменения заказов;
- внешние API.

Пайплайн должен в дальнейшем интегрироваться с **BigQuery, Redshift, Kafka и Spark**, поддерживать гибкую последовательность шагов, условия и ветвления, повторные попытки, fallback-сценарии, уведомления и мониторинг.

Ожидаемый объём одного запуска — около **1 млн записей**.

Требуется выбрать решение, которое можно продемонстрировать локально и затем перенести в облачную инфраструктуру.

## 2. Decision drivers

При выборе решения учитываются следующие требования:

1. Workflow должен описываться явно и быть версионируемым как код.
2. Нужны зависимости между шагами и возможность параллельного выполнения.
3. Нужны branching и условные переходы.
4. Нужны retry, backoff и обработка ошибок отдельных шагов.
5. Нужны fallback-сценарии после ошибки.
6. Нужны email-уведомления об успехе и ошибке.
7. Нужен запуск по расписанию и возможность внешнего/event-triggered запуска.
8. Требуются готовые интеграции с BigQuery, Redshift, Kafka, Spark и HTTP API.
9. Требуются встроенные средства наблюдения за состоянием pipeline.
10. Решение должно масштабироваться и разворачиваться в облаке.
11. Оркестратор не должен становиться местом передачи и хранения всего набора из миллиона записей.

## 3. Рассмотренные варианты

### 3.1. Apache Airflow

**Плюсы:**

- Workflow as Code и DAG на Python;
- развитая модель зависимостей, branching и trigger rules;
- task-level retries и callbacks;
- Web UI, история запусков и task logs;
- большое количество provider-пакетов для внешних систем;
- может оркестрировать Spark, BigQuery и другие внешние compute/storage-системы;
- хорошо подходит для гетерогенных data pipelines.

**Минусы:**

- отдельная инфраструктура: Scheduler, Metadata DB, Executor/Workers, Web UI;
- избыточен для одной простой независимой cron-задачи;
- не является движком потоковой обработки и не предназначен для low-latency event processing.

### 3.2. Spring Batch + Spring Cloud Data Flow

**Плюсы:**

- сильная chunk-oriented обработка;
- restartability через JobRepository;
- retry/skip;
- хорошо интегрируется с Java/Spring;
- SCDF добавляет централизованное управление batch/stream tasks.

**Минусы для текущего кейса:**

- основная задача здесь — оркестрация разнородных внешних систем, а не только Java ETL;
- интеграции BigQuery/Redshift/Spark/Kafka и сложные cross-system DAG требуют больше собственного кода и инфраструктуры;
- SCDF добавляет отдельный orchestration stack.

### 3.3. Kubernetes CronJob

**Плюсы:**

- простое облачное развёртывание;
- хорошо подходит для независимых контейнеризованных задач по расписанию;
- можно задать `concurrencyPolicy`, retry на уровне Job и resource limits.

**Минусы:**

- нет полноценного DAG;
- нет встроенного branching между несколькими шагами;
- нет централизованной модели зависимостей и rich workflow state;
- fallback/retry между отдельными логическими этапами пришлось бы реализовывать самостоятельно.

### 3.4. Apache Spark

**Плюсы:**

- распределённая обработка больших объёмов данных;
- хорош для тяжёлых transform/aggregation операций.

**Минусы:**

- Spark — compute engine, а не workflow orchestrator;
- сам по себе не решает задачу управления всем pipeline, зависимостями, email-уведомлениями и внешними шагами.

## 4. Решение

Выбрать **Apache Airflow** как платформу оркестрации пакетной обработки.

Airflow используется именно как **оркестратор**, а не как место обработки и передачи всего объёма данных. Тяжёлые преобразования выполняются специализированными системами — SQL/BigQuery/Redshift/Spark, а между Airflow tasks передаются ссылки, идентификаторы и небольшие служебные значения.

## 5. Обоснование требований

### 5.1. Интеграция с BigQuery

Для Airflow существует Google provider — `apache-airflow-providers-google`.

Он содержит hooks/operators для работы с сервисами GCP, включая BigQuery. Это позволяет запускать SQL/jobs и контролировать их выполнение из DAG без написания собственной инфраструктуры оркестрации.

### 5.2. Интеграция с Redshift

Для AWS используется `apache-airflow-providers-amazon`.

Airflow может работать с Redshift через готовые hooks/operators и AWS integrations, поэтому загрузка или выполнение SQL в Redshift оформляется как отдельный task DAG.

### 5.3. Интеграция с Kafka

Для Kafka доступен provider `apache-airflow-providers-apache-kafka` и интеграционные механизмы sensors/operators.

Kafka может использоваться:

- как внешний источник событий/данных;
- как триггер через внешний consumer/service, который вызывает Airflow REST API;
- через sensor, если допустима модель ожидания события внутри workflow.

Airflow не следует использовать вместо Kafka/Flink для постоянной streaming-обработки с миллисекундной задержкой.

### 5.4. Интеграция со Spark

Airflow предоставляет Spark provider и `SparkSubmitOperator`.

Airflow отвечает за запуск и зависимости, а Spark — за тяжёлый distributed transform. Это соответствует разделению ответственности «оркестратор управляет, вычислительный движок обрабатывает».

### 5.5. Интеграция с внешними API

HTTP-вызовы можно оформлять через HTTP hooks/operators или Python tasks.

Так интеграции остаются отдельными наблюдаемыми шагами DAG с собственными retry, timeout и логами.

## 6. Ветвление, условия и event triggers

### Branching

Для выбора ветки применяется `BranchPythonOperator`.

Невыбранные ветки отмечаются `skipped`, что позволяет явно увидеть принятое решение в истории DAG.

### Conditional execution

Для правил запуска используются `TriggerRule`, например:

- `all_success`;
- `one_failed`;
- `one_success`;
- `none_failed_min_one_success`.

Они позволяют строить success/failure/fallback ветки.

### Event-triggered запуск

Airflow поддерживает внешний запуск DAG через REST API/CLI и ожидание внешних условий через Sensors.

Для Kafka/webhook event-driven сценария предпочтительный вариант:

```text
Kafka/Webhook → consumer/API adapter → Airflow REST API → DAG run
```

Так событие инициирует batch pipeline сразу после поступления, но Airflow остаётся оркестратором и не заменяет брокер или stream processor.

## 7. Fallback, retry и email

### Retry

Retry задаётся на уровне task:

- `retries`;
- `retry_delay`;
- `retry_exponential_backoff`;
- `max_retry_delay`.

Падение одного шага не требует автоматически повторять весь DAG.

### Fallback logic

В Airflow нет необходимости в одном специальном операторе `fallback`: fallback-сценарий строится штатными средствами workflow — `TriggerRule`, branching и callbacks.

Например, после исчерпания retries task становится `failed`, а downstream task с `TriggerRule.ONE_FAILED` выполняет аварийное действие или уведомление.

### Email

Для email используется `EmailOperator` либо callback (`on_failure_callback`, `on_success_callback`) с SMTP/provider integration.

В POC SMTP имитируется локальным MailHog.

## 8. Мониторинг и наблюдаемость

Airflow предоставляет:

- Web UI;
- Graph/Grid representation DAG;
- состояние каждого task;
- историю DAG runs;
- task logs;
- состояние retries/failures;
- Metadata DB с состоянием workflow.

В production метрики и логи Airflow следует отправлять в централизованные системы мониторинга и логирования.

## 9. Развёртывание в облаке

### Предпочтительный вариант в GCP

**Cloud Composer** как managed Apache Airflow:

- снижает операционную нагрузку на команду;
- интегрируется с GCP IAM и сервисами Google Cloud;
- подходит для BigQuery/GCS и других GCP workloads;
- предоставляет управляемую инфраструктуру Airflow.

### Альтернатива — Airflow в Kubernetes/GKE

Компоненты:

- Scheduler;
- Webserver;
- отказоустойчивая PostgreSQL Metadata DB;
- KubernetesExecutor либо CeleryExecutor;
- Workers/Pods;
- DAG delivery через Git/CI/CD;
- Kubernetes Secrets или Secret Manager;
- централизованные logs/metrics/alerts.

Для изоляции тяжёлых задач можно использовать KubernetesExecutor: отдельная task выполняется в отдельном Pod с заданными requests/limits.

## 10. Масштабирование и объём данных

Около **1 млн записей за запуск** не означает автоматически необходимость Spark.

Размер записи, сложность transform и SLA важнее самого числа строк.

Начальный подход:

1. bulk/SQL операции в источниках и аналитических хранилищах;
2. промежуточные данные — во внешнем storage;
3. через XCom — только небольшие metadata/summary/URI;
4. Spark подключается только для вычислительно тяжёлых трансформаций, когда это подтверждено нагрузочным тестированием.

Так Scheduler, Metadata DB и Workers Airflow не превращаются в канал передачи большого набора данных.

## 11. Последствия решения

### Положительные

- единая точка управления pipeline;
- явно описанные зависимости;
- branching и fallback;
- retry отдельных шагов;
- готовые integrations/providers;
- встроенная история выполнения и диагностика;
- возможность локального POC и последующего cloud deployment;
- лёгкое подключение внешних compute engines.

### Отрицательные

- инфраструктура сложнее cron/CronJob;
- необходимо сопровождать Metadata DB, Scheduler и Workers, если не используется managed service;
- Airflow не является low-latency streaming engine;
- некорректное использование XCom для больших данных может перегрузить Metadata DB.

## 12. POC, подтверждающий решение

В `task-1/results` реализован DAG `marketing_batch_poc`, который демонстрирует требуемые возможности:

1. чтение CSV;
2. анализ агрегированного значения;
3. branching;
4. объединение выбранной ветки;
5. email при успешном завершении;
6. failure path после исчерпания retries;
7. exponential retry/backoff;
8. локальный запуск Airflow в Docker Compose;
9. MailHog для проверки email-уведомлений.

Инструкция по запуску и фиксации доказательств: `DEMO.md`.
