# Обоснование способа отправки и хранения логов

## Выбранное решение

Для централизованного логирования выбран стек:

```text
Application stdout/stderr JSON
        ↓
Filebeat
        ↓
Logstash
        ↓
Elasticsearch
        ↓
Kibana
```

## Почему ELK

1. У TradeWare одновременно остаётся legacy Java/WildFly и появляются новые Spring Boot/Spring Batch сервисы. ELK позволяет собирать их логи в одном месте.
2. Filebeat — лёгкий агент, который удобно запускать на Kubernetes nodes/pods и использовать для файловых логов legacy-компонентов.
3. Logstash позволяет привести legacy и новые JSON-логи к единой схеме, отфильтровать технический шум и добавить служебные поля.
4. Elasticsearch оптимизирован для индексированного поиска по документам и подходит для расследования по `file_id`, `job_execution_id`, `trace_id`, `error_code`.
5. Kibana позволяет искать, фильтровать и визуализировать ошибки без ручного подключения к каждой VM/Pod.

Если корпоративные ограничения по лицензии Elastic не позволяют использовать Elasticsearch/Kibana, функционально близкая альтернатива — OpenSearch + OpenSearch Dashboards.

## Способ отправки

Новые контейнеризированные приложения пишут **structured JSON в stdout/stderr**. Это соответствует контейнерной модели: приложение не управляет файлами логов, ротацией и доставкой.

Filebeat собирает stdout/container logs и legacy file logs и передаёт их в Logstash. Такой подход разделяет бизнес-приложение и транспорт логов: временная недоступность Elasticsearch не должна превращать бизнес-запрос в ошибку только потому, что лог невозможно отправить синхронно.

## Минимальная схема события

```json
{
  "@timestamp": "2026-09-06T16:20:15.123Z",
  "level": "INFO",
  "service": "report-batch-worker",
  "environment": "prod",
  "event": "chunk_completed",
  "trace_id": "...",
  "span_id": "...",
  "file_id": "f-12345",
  "job_execution_id": 9123,
  "step": "enrich-and-write",
  "rows_read": 500,
  "rows_written": 500,
  "duration_ms": 820
}
```

Для ошибки дополнительно: `error_code`, `exception_class`, `message`, stacktrace и retry attempt.

## Уровни логирования

- `INFO` — старт/завершение job/step, агрегированные counts;
- `WARN` — recoverable error, retry, допустимый skip, подозрительное состояние;
- `ERROR` — неисправимая ошибка step/job;
- `DEBUG`/`TRACE` — только временно для диагностики, постоянно в production не включать.

## Что запрещено логировать

- пароли и секреты;
- access/refresh tokens;
- connection strings с credentials;
- полное содержимое CSV;
- персональные данные, которые не нужны для диагностики;
- платёжные реквизиты.

Вместо строки данных следует логировать `file_id`, checksum, номер строки и стабильный `error_code`.

## Индексы и mapping

Использовать явный mapping. Базовые поля:

- `service`, `environment`, `level`, `event`, `error_code`, `file_id`, `trace_id` — `keyword`;
- `message`/stacktrace — `text`;
- `duration_ms`, row counters — numeric;
- `@timestamp` — `date`.

Избегать uncontrolled dynamic mapping, чтобы случайные поля не раздували индекс.

## ILM / retention

Использовать Index Lifecycle Management:

1. hot — свежие индексы;
2. rollover по `max_primary_shard_size`, age или size;
3. warm/cold — если это оправдано стоимостью хранения;
4. delete по утверждённому retention.

Для обычных технических логов разумная стартовая гипотеза — около 30 дней. Audit/security logs хранятся по требованиям ИБ и законодательства. Сроки должны быть утверждены владельцем данных.

## Корреляция с metrics и tracing

В JSON-log включаются `trace_id`/`span_id`. При инциденте инженер:

1. получает alert из Alertmanager;
2. смотрит метрики Grafana;
3. ищет `file_id`/`trace_id` в Kibana;
4. по тому же `trace_id` открывает trace в Jaeger.

Таким образом метрики обнаруживают проблему, логи объясняют технический контекст, а distributed tracing показывает полный путь операции.
