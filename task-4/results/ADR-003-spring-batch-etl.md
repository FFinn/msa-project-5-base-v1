# ADR-003. Вынос обработки складских отчётов в асинхронный Spring Batch ETL

- **Статус:** Принято
- **Автор:** Александр Вафин
- **Дата:** 2026-09-06
- **Контекст:** TradeWare, проектная работа спринта 5, Task 4

## 1. Контекст и проблемы As Is

Сейчас Java/WildFly-монолит принимает CSV через ERP-интерфейс и в рамках пользовательского сценария выполняет валидацию, сохранение файла, обогащение справочными данными и построчное обновление БД номенклатуры.

Это создаёт следующие проблемы:

1. **Тяжёлая обработка выполняется в контексте пользовательского запроса.** Пользователь ждёт завершения импорта, а HTTP/API-ресурсы заняты длительной операцией.
2. **Построчные записи перегружают PostgreSQL.** При 400 тыс. строк/сутки и росте в 2–3 раза количество транзакций и round-trip становится bottleneck.
3. **Нет управляемого checkpoint/restart.** При ошибке трудно продолжить с последнего успешного фрагмента и безопасно повторить обработку.
4. **Пиковая нагрузка не изолирована от online-части.** Импорт конкурирует за CPU/RAM/DB connections с ERP/API и ухудшает пользовательский SLA.
5. **100–150 параллельных загрузок нельзя без ограничений превратить в 100–150 параллельных тяжёлых транзакций.** Нужны очередь, backpressure и контролируемый pool обработчиков.
6. **Низкая наблюдаемость.** Есть файловые логи и базовый VM/WildFly monitoring, но нет единой картины job/task, latency, ошибок и причин деградации.
7. **Система готовится к поэтапной миграции из монолита.** Новая обработка должна жить рядом с монолитом и не требовать big-bang rewrite.

## 2. Требования

### 2.1. Decision drivers

- до **100–150 параллельных загрузок** в пике;
- обработка отчёта на **2 000 строк ≤ 30 секунд в среднем**;
- рост суточного объёма с 400 тыс. строк до 800 тыс.–1,2 млн и далее;
- retry/restart и локализация ошибок;
- интеграция с Java-экосистемой;
- возможность горизонтального масштабирования в GCP;
- изоляция batch-нагрузки от online API;
- постепенная миграция к микросервисам;
- централизованные metrics/logs/traces.

### 2.2. FURPS+

| Категория | Архитектурно значимое требование |
|---|---|
| **F — Functionality** | Приём CSV, валидация, обогащение справочными данными, пакетная запись остатков/номенклатуры, получение статуса обработки |
| **U — Usability** | Пользователь не должен ждать завершения ETL внутри HTTP-запроса; должен видеть понятный бизнес-статус загрузки |
| **R — Reliability** | Retry, restart from checkpoint, идемпотентность, локальный rollback chunk, контролируемая обработка ошибочных строк |
| **P — Performance** | Среднее время обработки отчёта на 2 000 строк ≤ 30 сек.; burst 100–150 загрузок без неконтролируемого давления на PostgreSQL |
| **S — Supportability** | Отдельный worker, metrics/logs/traces, JobRepository, тестируемая chunk-конфигурация, постепенный вынос из монолита |
| **+ — Constraints / Compatibility** | Java 11/Spring, PostgreSQL, GCS, GCP/GKE, сохранение совместимости с существующим ERP/WildFly на этапе миграции |

### 2.3. Use Cases

| UC-ID | Название | Основной поток | Альтернативный поток | Особые требования |
|---|---|---|---|---|
| UC-01 | Загрузка складского отчёта | Сотрудник → ERP UI → Intake API → быстрая проверка → GCS → статус `ACCEPTED` → очередь → `202 Accepted + file_id` | Ошибка формата до постановки в очередь → пользователь получает ошибку и исправляет файл | HTTP-запрос не ждёт ETL; повторная регистрация по идемпотентному ключу не создаёт дубликат обработки |
| UC-02 | Пакетная обработка отчёта | Queue → Batch Worker → Spring Batch Job → Reader → Processor → Writer → PostgreSQL → статус `COMPLETED` | Ошибка элемента/инфраструктуры → rollback текущего chunk → retry/skip по политике → при исчерпании попыток `FAILED` | Chunk commit, batch upsert, контролируемая concurrency, метрики выполнения |
| UC-03 | Восстановление после сбоя | Worker запускает тот же JobInstance → JobRepository/ExecutionContext → продолжение с checkpoint → идемпотентная запись | Невосстановимая бизнес-ошибка → Job остаётся `FAILED`, формируется validation/error report | Reader должен быть restartable; writer не создаёт дубликаты |

## 3. Решение

Использовать **Spring Batch как движок ETL внутри отдельного stateless Spring Boot Batch Worker**, а обработку файлов сделать асинхронной.

Решение выбрано, потому что оно напрямую покрывает ключевые FURPS+ требования:

- **F:** Spring Batch предоставляет Job/Step и Reader/Processor/Writer для типового ETL;
- **R:** JobRepository, chunk transaction semantics, retry/skip и restartability дают управляемое восстановление;
- **P:** batch insert/upsert и регулируемая concurrency снимают построчный overhead и позволяют защищать PostgreSQL;
- **S:** Spring Boot/Spring Batch органичны для Java-команды, наблюдаемость и state выполнения можно стандартизировать;
- **+:** решение можно развернуть в GKE рядом с существующим монолитом и мигрировать функциональность постепенно.

### 3.1. Новый поток

1. Сотрудник загружает CSV через существующий UI.
2. Intake API выполняет только быструю синхронную проверку формата/размера и сохраняет исходный файл в GCS.
3. Для файла создаётся уникальный `file_id`; в отдельном **Import Status Store** создаётся бизнес-статус `ACCEPTED`. Повторная регистрация того же файла/идемпотентного ключа не создаёт параллельную копию обработки.
4. Intake API публикует команду `ReportUploaded(file_id, object_uri)` в очередь (в GCP — Pub/Sub; допустим Kafka при наличии корпоративного брокера) и немедленно возвращает пользователю `202 Accepted` + `file_id`, не удерживая HTTP-запрос до конца ETL.
5. Spring Batch Worker получает job, переводит бизнес-статус в `PROCESSING` и запускает Spring Batch `Job`.
6. Job обрабатывает CSV chunk-ами:
   - `ItemReader` — для надёжного restart worker загружает immutable CSV из GCS во временный локальный файл и обрабатывает его restartable `FlatFileItemReader`; состояние reader сохраняется в `ExecutionContext`;
   - при прямом чтении из GCS-stream должен использоваться custom restartable `ItemStreamReader`, иначе нельзя гарантировать resume строго с нужной строки после сбоя;
   - `ItemProcessor` — полная валидация, нормализация и обогащение справочными данными;
   - `ItemWriter` — batch insert/upsert в PostgreSQL, а не по одной строке.
7. Spring Batch хранит **техническое состояние** `JobExecution`, `StepExecution` и checkpoints в **JobRepository (PostgreSQL)**.
8. После завершения Batch Worker обновляет бизнес-статус в `COMPLETED` или `FAILED`, сохраняет ссылку на validation/error report при необходимости и публикует `ReportProcessed`/`ReportFailed`.
9. UI получает бизнес-статус через status API/WebSocket. Внутренние таблицы JobRepository напрямую в UI не экспонируются.

Разделение важно: **JobRepository — техническое хранилище Spring Batch, Import Status Store — бизнесовая модель статуса для пользователя и интеграций.**

### 3.2. Chunk size

Начальная гипотеза для отчётов по 2 000 строк — **chunk 200–500**, но это не фиксированное архитектурное правило.

Размер выбирается нагрузочными тестами по:

- end-to-end времени;
- CPU/RAM;
- числу DB commit;
- длительности транзакций и lock wait;
- стоимости rollback/retry;
- требованию 30 секунд.

Малый chunk даёт дешёвый rollback и лучше балансирует workers; слишком малый увеличит транзакционный overhead. Большой chunk уменьшит число commit, но увеличит memory footprint, lock time и стоимость retry.

### 3.3. Масштабирование

**Не запускать безусловно 150 тяжёлых worker-процессов одновременно.**

- очередь принимает burst в 100–150 загрузок;
- Kubernetes/GKE масштабирует Batch Workers горизонтально;
- максимальная concurrency ограничивается по результатам capacity test PostgreSQL;
- DB connection pool ограничен;
- backlog/queue age становится метрикой насыщенности;
- если один файл в будущем станет существенно больше, можно использовать Spring Batch partitioning/remote partitioning, но для 2 000 строк это преждевременная сложность.

Такой подход реализует backpressure: система принимает загрузки быстро, но обрабатывает их с безопасной для БД параллельностью.

### 3.4. Транзакционность и идемпотентность

- commit выполняется на границе chunk;
- при ошибке откатывается текущий chunk, а завершённые checkpoint не пересчитываются без необходимости;
- временные ошибки БД/сети — `retry` с ограничением и backoff;
- ошибки конкретной строки могут использовать `skip` только для заранее согласованных типов ошибок и с лимитом; бизнес-критичные ошибки должны завершать job;
- writer должен быть идемпотентным: upsert/unique business key + `file_id`/version;
- повторный job с теми же параметрами не должен создавать дубликаты;
- бизнес-статус изменяется идемпотентно по `file_id` и не используется вместо транзакционных checkpoints Spring Batch;
- для production-связки «обновить Import Status Store + опубликовать `ReportProcessed`/`ReportFailed`» желательно использовать transactional outbox, чтобы сбой между SQL update и publish не приводил к потерянному событию.

## 4. Хранение данных

| Данные | Хранилище | Причина |
|---|---|---|
| Исходные CSV | GCS | дешёвое и масштабируемое object storage, аудит исходного файла |
| Бизнес-статус импорта | PostgreSQL / Cloud SQL (`Import Status Store`) | status API, `file_id`, пользовательские состояния и ссылки на результаты |
| Справочные данные | существующая PostgreSQL / read replica при необходимости | источник обогащения |
| Номенклатура/остатки | PostgreSQL | транзакционные актуальные данные |
| Spring Batch metadata | отдельная PostgreSQL БД/схема JobRepository | restartability, состояние Job/Step |
| Ошибочные строки/validation report | GCS или отдельная таблица | повторный анализ без засорения JobRepository |

JobRepository является критической зависимостью и в production должен работать на отказоустойчивом PostgreSQL/Cloud SQL с backup/HA. Бизнесовый статус не следует получать напрямую из внутренних таблиц Spring Batch.

## 5. Развёртывание

Целевой вариант — контейнеры в **GKE**:

- `report-intake-service` — stateless API;
- `report-batch-worker` — Spring Boot + Spring Batch;
- Pub/Sub/Kafka — буферизация и распределение job;
- Cloud SQL PostgreSQL — business DB/status store и отдельная metadata DB/schema JobRepository;
- GCS — raw/error files;
- HPA/KEDA или аналог — масштабирование workers по CPU и/или длине очереди;
- Secret Manager/Kubernetes Secret — credentials;
- requests/limits, PDB и health checks.

Spring Batch не обязан постоянно работать как один огромный process: worker-компонент можно масштабировать горизонтально либо запускать отдельные Kubernetes Job/Pod на batch execution, если это будет удобнее операционной модели.

## 6. Наблюдаемость

Подробное решение вынесено в Task 5. Минимально Spring Boot/Spring Batch должен экспортировать:

- количество started/completed/failed jobs;
- job/step duration;
- read/process/write/skip counts;
- retry count;
- queue depth/oldest message age;
- DB pool saturation;
- JVM/container CPU/RAM;
- structured logs с `file_id`, `job_execution_id`, `step`, `rows`, `error_code`;
- OpenTelemetry trace/context для входного запроса, команды и batch job.

## 7. Зависимости

- **Task 5** — централизованные monitoring/logging/alerting и tracing;
- **GCS** — исходные immutable CSV и validation/error reports;
- **Cloud SQL/PostgreSQL** — доменные данные, Import Status Store и отдельная schema/DB JobRepository;
- **Pub/Sub или Kafka** — буферизация, backpressure и доставка команд обработки;
- **GKE** — runtime для Intake Service и Batch Workers;
- **Secret Manager/Kubernetes Secrets** — credentials;
- **transactional outbox** рекомендуется для согласования изменения финального бизнес-статуса и публикации итогового события.

## 8. Альтернативы

### Apache Airflow

**Плюсы:** сильная оркестрация DAG, ветвления, внешние integrations, retry/UI, удобен для BigQuery/Spark/ML и множества heterogenous pipelines.

**Почему не основной выбор сейчас:** текущая задача — обработка одного типа файла внутри Java-домена. Airflow добавит отдельный Python/orchestration stack и не заменит саму реализацию chunk-oriented ETL.

**Когда выбрать:** если TradeWare перейдёт к десяткам разнородных ETL/ML pipeline с внешними зависимостями.

### Spring Cloud Data Flow

**Плюсы:** централизует Spring Batch jobs/tasks, scheduling, UI/API, distributed execution, event-triggering.

**Почему не сейчас:** повышает инфраструктурную сложность раньше, чем появилось множество независимых jobs.

**Когда выбрать:** когда количество Spring Batch jobs вырастет и понадобится централизованная orchestration/registry/versioning.

### Kubernetes Job/CronJob без Spring Batch

**Плюсы:** минимальная инфраструктура, нативно для GKE.

**Минусы:** самостоятельно придётся реализовать checkpoint, chunk transaction semantics, skip/retry на уровне элементов, JobRepository-подобное состояние.

### Spark/Dataflow

**Плюсы:** распределённые вычисления на очень больших объёмах.

**Почему не сейчас:** файлы по ~2 000 строк и даже суточные 1,2 млн строк сами по себе не оправдывают distributed compute. Сначала требуется убрать построчные транзакции и отделить batch от online path.

## 9. Последствия решения

### Положительные

- online API больше не ждёт обработки файла;
- нагрузка batch изолирована и регулируется;
- batch insert/upsert резко сокращает количество транзакционных round-trip;
- появляются restart/checkpoint/retry;
- решение органично для Java-команды;
- можно мигрировать функционал из монолита постепенно;
- масштабирование workers отделено от масштабирования ERP.

### Отрицательные и нейтральные

- появляется очередь и eventual consistency: пользователь получает результат асинхронно;
- JobRepository становится критической инфраструктурой;
- появляется отдельная бизнес-модель статусов, которую нужно согласованно обновлять при сбоях;
- без transactional outbox есть риск dual-write между обновлением статуса и публикацией финального события;
- неверная concurrency может всё равно перегрузить PostgreSQL;
- слишком большой chunk способен увеличить memory/locks/recovery time;
- идемпотентность writer и повторных запусков должна быть спроектирована явно;
- SLA 30 секунд нельзя гарантировать архитектурной схемой — его нужно подтвердить нагрузочным тестом.

## 10. Риски и ограничения

- производительность зависит от capacity PostgreSQL, качества SQL/индексов и выбранной concurrency;
- JobRepository — критическая dependency, поэтому требуется HA/backup;
- restartability гарантируется только при корректно настроенном restartable Reader/ExecutionContext и стабильных JobParameters;
- некорректная skip-policy способна скрыть бизнес-критичные ошибки данных;
- remote partitioning не следует добавлять до появления подтверждённой необходимости;
- eventual consistency меняет пользовательский сценарий: UI должен работать со статусом, а не ждать синхронного результата;
- связка DB update + publish требует outbox или эквивалентного механизма, если потеря итогового события недопустима.

## 11. Критерии приёмки решения

1. P95/P50 и среднее время файла 2 000 строк измерено; среднее ≤ 30 сек.
2. Тест burst: 100–150 одновременных загрузок не деградирует ERP/API сверх согласованного SLO.
3. Нет построчного commit на каждую запись.
4. При падении worker job восстанавливается из JobRepository без дублирования уже применённых данных.
5. Есть ограничение максимальной concurrency и DB connections.
6. `file_id` имеет корректный бизнес-статус после retry/restart/ошибки.
7. Метрики, логи, traces и alert rules проверены искусственными сбоями.

## 12. Критерии пересмотра ADR

Решение следует пересмотреть, если:

- появятся десятки разнородных ETL/ML workflow со сложными межсистемными DAG-зависимостями — тогда Airflow может стать более подходящим оркестратором;
- количество Spring Batch jobs существенно вырастет и понадобится централизованный registry/scheduling/versioning — тогда стоит повторно оценить SCDF;
- объёмы одного файла и тяжесть transform вырастут настолько, что обычное chunk processing не укладывается в SLA после оптимизации — тогда рассмотреть partitioning/Spark/Dataflow;
- требования изменятся с batch на low-latency streaming.

## 13. Связанные артефакты

- C4 To Be: `c4-to-be.puml`;
- редактируемая C4: `c4-to-be.drawio`;
- экспорт диаграммы: `c4-to-be.png`;
- локальный POC: `poc/`;
- доказательства запуска: `screenshots/`;
- observability-решение: Task 5.
