# Демонстрация POC Apache Airflow

## Запуск

Из директории `task-1/results`:

```bash
docker compose up -d
```

Airflow standalone создаст локальную Metadata DB и пользователя администратора. Пароль можно увидеть в логах:

```bash
docker compose logs airflow | grep -i password
```

Открыть:

- Airflow UI: `http://localhost:8080`
- MailHog UI: `http://localhost:8025`

DAG: `marketing_batch_poc`.

## Успешный сценарий

1. Открыть DAG `marketing_batch_poc`.
2. Нажать **Trigger DAG**.
3. Оставить параметр:

```json
{"force_failure": false}
```

4. В Graph/Grid должно быть видно:
   - `read_source` — success;
   - `branch_on_amount` — success;
   - `high_value_processing` — success, потому что сумма тестовых заказов больше 1000;
   - `regular_processing` — skipped;
   - `join` — success;
   - `success_email` — success.
5. В MailHog должно появиться письмо `[OK] marketing_batch_poc completed`.

## Сценарий ошибки, retry и failure-email

Запустить DAG с параметром:

```json
{"force_failure": true}
```

`read_source` намеренно завершится ошибкой. Для него настроены:

- `retries=2`;
- `retry_delay=15s`;
- `retry_exponential_backoff=True`;
- `max_retry_delay=2m`.

После исчерпания retry задача станет `failed`, а `failure_email`, работающий по `TriggerRule.ONE_FAILED`, отправит письмо в MailHog.

## Что снять для сдачи

Рекомендуемые скриншоты положить в `task-1/results/screenshots/`:

1. `01-airflow-dag.png` — Graph/Grid успешного запуска с выбранной веткой.
2. `02-success-email.png` — письмо об успешном завершении в MailHog.
3. `03-retries.png` — лог/статус `read_source`, где видны повторные попытки.
4. `04-failure-email.png` — письмо об ошибке в MailHog.

Скриншоты должны быть сделаны после реального локального запуска; подменять их статическими изображениями не нужно.
