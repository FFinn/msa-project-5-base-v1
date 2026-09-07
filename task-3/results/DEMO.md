# Демонстрация задания 3 в MiniKube

## 1. Запустить MiniKube

```bash
minikube start
kubectl config current-context
```

## 2. Собрать образ в Docker-среде MiniKube

Из `task-3/results`:

```bash
eval $(minikube docker-env)
docker build -t shipments-exporter:local ./app
```

Проверка:

```bash
docker images | grep shipments-exporter
```

Если выбранный драйвер MiniKube не использует Docker напрямую, можно загрузить уже собранный образ:

```bash
docker build -t shipments-exporter:local ./app
minikube image load shipments-exporter:local
```

## 3. Поднять тестовую PostgreSQL и PVC

```bash
kubectl apply -f k8s/postgres-demo.yaml
kubectl apply -f k8s/output-pvc.yaml
kubectl rollout status deployment/shipments-db
```

## 4. Создать CronJob

```bash
kubectl apply -f k8s/cronjob.yaml
kubectl get cronjob shipments-daily-export
kubectl get cronjob shipments-daily-export \
  -o jsonpath='{.spec.schedule}{"  timezone="}{.spec.timeZone}{"\n"}'
```

В результате должно быть видно расписание `0 20 * * *` и `timezone=Europe/Moscow`.

## 5. Не ждать 20:00 — запустить Job вручную из шаблона CronJob

```bash
kubectl create job --from=cronjob/shipments-daily-export shipments-export-manual-1
kubectl get jobs
kubectl get pods -l job-name=shipments-export-manual-1
```

Дождаться состояния `Completed`:

```bash
kubectl wait --for=condition=complete job/shipments-export-manual-1 --timeout=120s
kubectl logs job/shipments-export-manual-1
```

В журнале должен быть JSON с `event=shipment_export_completed`, `status=success` и `rows_exported=3`. Эти значения оставлены на английском, потому что являются машинными идентификаторами и значениями приложения.

## 6. Проверить CSV на PVC

```bash
kubectl apply -f k8s/output-reader.yaml
kubectl wait --for=condition=Ready pod/shipments-output-reader --timeout=60s
kubectl logs shipments-output-reader
kubectl delete pod shipments-output-reader
```

В выводе должны быть имя `shipments-YYYY-MM-DD.csv`, заголовок CSV и три тестовые строки.

## 7. Проверить защиту от параллельного планового запуска

В манифесте установлен:

```yaml
concurrencyPolicy: Forbid
```

Это означает, что **плановый запуск самого CronJob** не создаст новый Job, пока предыдущий запуск этого CronJob ещё выполняется. Ручные Job, созданные командой `kubectl create job --from=cronjob/...`, Kubernetes рассматривает отдельно; `concurrencyPolicy` не является общей блокировкой всех вручную созданных Job.

## Скриншоты для сдачи

Скриншоты находятся в `task-3/results/screenshots/`:

1. `00-minikube.png` — `minikube status` и `kubectl config current-context`.
2. `01-cronjob.png` — `kubectl get cronjob shipments-daily-export` и проверка `schedule/timeZone`.
3. `02-job-completed.png` — `kubectl get jobs` и `kubectl get pods -l job-name=shipments-export-manual-1` с завершённым Job.
4. `03-job-log.png` — JSON-журнал с `rows_exported`.
5. `04-csv.png` — содержимое сформированного CSV на PVC.

Скриншоты должны подтверждать реальный запуск в MiniKube.
