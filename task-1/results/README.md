# Task 1. Выбор и реализация решения для пакетной обработки данных

## Выбранное решение: Apache Airflow

Для кейса маркетингового отдела выбран **Apache Airflow**. Главная причина — требуется не просто обработать около 1 млн записей, а гибко оркестрировать разнородный pipeline: файловая система, PostgreSQL, Kafka, внешние API, BigQuery, Redshift и Spark, с ветвлениями, retry, уведомлениями и наблюдаемостью.

Airflow здесь используется именно как **оркестратор**, а не как движок обработки данных. Тяжёлые вычисления следует делегировать PostgreSQL/BigQuery/Redshift/Spark, а между задачами передавать ссылки на данные, идентификаторы или небольшие служебные значения, а не миллион строк через XCom.

## Почему Airflow подходит

| Требование | Решение в Airflow |
|---|---|
| PostgreSQL | Готовые connection/hook/operator из provider PostgreSQL |
| BigQuery | `apache-airflow-providers-google`, BigQuery operators/hooks |
| Redshift | `apache-airflow-providers-amazon`, Redshift operators/hooks |
| Kafka | `apache-airflow-providers-apache-kafka`, sensors/operators; также можно триггерить DAG внешним consumer/REST API |
| Spark | `SparkSubmitOperator` и Spark provider |
| Внешние API | `HttpOperator`/hooks или обычный Python-код |
| Ветвление | `BranchPythonOperator`, trigger rules |
| Условия и fallback | trigger rules (`one_failed`, `none_failed_min_one_success` и др.), callbacks |
| Event triggers | REST API trigger DAG run; sensors; для настоящего low-latency event-driven сценария событие лучше принимать Kafka consumer/функцией и триггерить DAG |
| Retry | `retries`, `retry_delay`, exponential backoff на уровне task |
| Email | `EmailOperator`, callbacks, SMTP |
| Мониторинг | Web UI, task logs, состояние DAG/task в Metadata DB; экспорт метрик во внешние системы |

### Почему не Spring Batch

Spring Batch хорошо подходит для Java ETL внутри приложения и chunk-oriented processing, но здесь инфраструктура гетерогенная и требуется оркестрация большого количества внешних систем. Для такого DAG Airflow проще расширять и сопровождать.

### Почему не Kubernetes CronJob

CronJob отлично подходит для одной независимой периодической контейнеризованной задачи. Но он не предоставляет полноценный DAG, ветвления, централизованные зависимости и богатую модель повторных запусков/наблюдаемости.

### Почему не Spark как основное решение

Spark — вычислительный движок, а не workflow-оркестратор. Его разумно запускать из Airflow для тяжёлого `Transform`.

## Развёртывание в облаке

### Целевой вариант

1. **Managed Airflow** (например, Cloud Composer в GCP) — минимальная операционная нагрузка на команду.
2. Либо Airflow в Kubernetes/GKE:
   - Scheduler и Webserver как отдельные deployment;
   - PostgreSQL как отказоустойчивая Metadata DB;
   - KubernetesExecutor или CeleryExecutor;
   - DAG-и доставляются из Git/CI;
   - secrets хранятся в Secret Manager/Kubernetes Secrets;
   - логи и метрики отправляются в централизованную observability-платформу.
3. Для тяжёлых шагов Airflow запускает Spark/BigQuery/Redshift, а не обрабатывает большие наборы данных в памяти scheduler/worker.

Для объёма около **1 млн записей за запуск** сам по себе этот объём не требует Spark. Решение зависит от веса строки и сложности преобразований. Сначала следует использовать SQL/bulk-операции, а Spark подключать, когда это подтверждено замерами.

## POC

POC находится в этой директории:

- `docker-compose.yml` — локальный Airflow + MailHog;
- `dags/marketing_etl.py` — DAG с чтением источника, анализом, ветвлением, retry и email;
- `data/orders.csv` — тестовый источник;
- `DEMO.md` — точные шаги запуска и список скриншотов для сдачи.

### Pipeline POC

```text
read_source
     |
branch_on_amount
   /        \
high_value  regular
   \        /
      join
       |
 success_email

failure_email <- one_failed(read_source, branch, high_value, regular)
```

В POC `read_source` читает CSV и передаёт через XCom только маленький агрегированный summary. В production большие данные через XCom передавать нельзя: следует сохранять промежуточный результат во внешнем хранилище и передавать URI/ID.
