# Local infrastructure

阶段 0 的本地基础设施包含 PostgreSQL、RabbitMQ 和一个 S3 兼容对象存储。

```powershell
docker compose --env-file .env.example -f infra/docker-compose/compose.yaml config --quiet
docker compose --env-file .env.example -f infra/docker-compose/compose.yaml up -d --wait
docker compose --env-file .env.example -f infra/docker-compose/compose.yaml ps
```

默认端点：

- PostgreSQL：`localhost:15432`（容器内部仍为 `5432`）
- S3 API：`http://localhost:9000`
- RabbitMQ AMQP：`localhost:5672`；管理界面：`http://localhost:15672`
- 初始 bucket：`medication-platform-local`

`up -d --wait` 返回成功且 `docker compose ... ps` 中三个容器均为 `healthy` 后，才继续启动 Core API 和 Worker。
应用脚本还会验证上述三个主机端口；端口未发布时只重建对应基础设施容器，不会终止不属于本项目的进程。

默认凭证仅用于合成本地数据。不得把真实视频、生产凭证或医院数据放入该环境。

SeaweedFS 仅是本地开发使用的 S3 兼容实现，不代表生产对象存储产品已获批准。应用代码只能依赖 S3 API，不能依赖 SeaweedFS 专有接口。

停止服务但保留数据：

```powershell
docker compose --env-file .env.example -f infra/docker-compose/compose.yaml down
```

删除命名卷会永久删除本地数据，因此不提供自动删除命令。
