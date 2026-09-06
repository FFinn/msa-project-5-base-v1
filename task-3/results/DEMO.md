# Демонстрация Task 3 в MiniKube

## 1. Запустить MiniKube

```bash
minikube start
kubectl config current-context
```

## 2. Собрать образ прямо в Docker daemon MiniKube

Из `task-3/results`:

```bash
eval $(minikube docker-env)
docker build -t shipments-exporter:local ./app
```

Проверка:

```bash
docker images | grep shipments-exporter
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
```

В результате должно быть видно расписание `0 20 * * *`.

## 5. Не ждать 20:00 — запустить Job вручную из шаблона CronJob

```bash
kubectl create job --from=cronjob/shipments-daily-export shipments-export-manual-1
kubectl get jobs
kubectl get pods -l job-name=shipments-export-manual-1
```

Дождаться `Completed`:

```bash
kubectl wait --for=condition=complete job/shipments-export-manual-1 --timeout=120s
kubectl logs job/shipments-export-manual-1
```

В логе должен быть `shipment_export_completed`, `status=success` и `rows_exported=3`.

## 6. Проверить CSV на PVC

Создать временный pod для чтения PVC:

```bash
kubectl run output-reader \
  --image=busybox:1.36 \
  --restart=Never \
  --overrides='{
    "spec": {
      "containers": [{
        "name": "output-reader",
        "image": "busybox:1.36",
        "command": ["sh", "-c", "ls -la /output && cat /output/shipments-*.csv && sleep 3600"],
        "volumeMounts": [{"name": "output", "mountPath": "/output"}]
      }],
      "volumes": [{
        "name": "output",
        "persistentVolumeClaim": {"claimName": "shipments-export-output"}
      }]
    }
  }'

kubectl logs output-reader
kubectl delete pod output-reader
```

## 7. Проверить защиту от параллельного запуска

В манифесте установлен:

```yaml
concurrencyPolicy: Forbid
```

Это означает, что плановый запуск CronJob не создаст новый Job, пока предыдущий запуск этого CronJob ещё выполняется.

## Скриншоты для сдачи

Сделать после фактического запуска и положить в `task-3/results/screenshots/`:

1. `01-cronjob.png` — `kubectl get cronjob shipments-daily-export`.
2. `02-job-completed.png` — `kubectl get jobs,pods` с `Complete/Completed`.
3. `03-job-log.png` — лог с `rows_exported`.
4. `04-csv.png` — содержимое сформированного CSV на PVC.

Скриншоты должны подтверждать реальный запуск в MiniKube.
