# Демонстрация Apache Airflow

## Запуск

Из директории `task-1/results`:

```bash
docker compose up -d
```

Airflow в автономном режиме создаст локальную базу метаданных и пользователя администратора. Дождитесь запуска:

```bash
docker compose logs -f airflow
```

Пароль администратора можно получить так:

```bash
docker compose exec airflow cat /opt/airflow/standalone_admin_password.txt
```

Если файл ещё не появился, подождите завершения инициализации Airflow.

Открыть:

- веб-интерфейс Airflow: `http://localhost:8080`;
- веб-интерфейс MailHog: `http://localhost:8025`.

DAG: `marketing_batch_poc`.

## Успешный сценарий

1. Открыть DAG `marketing_batch_poc`.
2. Нажать **Trigger DAG**.
3. Оставить параметр:

```json
{"force_failure": false}
```

4. На представлении Graph/Grid должно быть видно:
   - `read_source` — `success`;
   - `branch_on_amount` — `success`;
   - `high_value_processing` — `success`, потому что сумма тестовых заказов больше 1000;
   - `regular_processing` — `skipped`;
   - `join` — `success`;
   - `success_email` — `success`.
5. В MailHog должно появиться письмо `[OK] marketing_batch_poc completed`.

Названия состояний `success` и `skipped` оставлены без перевода, потому что это штатные значения Airflow, которые отображаются в интерфейсе.

## Сценарий ошибки и повторных попыток

Запустить DAG с параметром:

```json
{"force_failure": true}
```

`read_source` намеренно завершится ошибкой. Для него настроены:

- `retries=2`;
- `retry_delay=15s`;
- `retry_exponential_backoff=True`;
- `max_retry_delay=2m`.

После исчерпания повторных попыток `read_source` перейдёт в состояние `failed`, последующие обычные шаги не выполнятся, а `failure_email` с `TriggerRule.ONE_FAILED` отправит письмо в MailHog.

## Что сохранить для сдачи

Скриншоты находятся в `task-1/results/screenshots/`:

1. `01-airflow-dag.png` — представление Graph/Grid успешного запуска с выбранной веткой.
2. `02-success-email.png` — письмо об успешном завершении в MailHog.
3. `03-retries.png` — журнал или состояние `read_source`, где видны повторные попытки.
4. `04-failure-email.png` — письмо об ошибке в MailHog.

Скриншоты должны подтверждать реальный локальный запуск.
