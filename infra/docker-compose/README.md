# Local infrastructure

阶段 0 的本地基础设施只包含 PostgreSQL 和一个 S3 兼容对象存储。

```powershell
docker compose --env-file .env.example -f infra/docker-compose/compose.yaml config --quiet
docker compose --env-file .env.example -f infra/docker-compose/compose.yaml up -d --wait
docker compose --env-file .env.example -f infra/docker-compose/compose.yaml ps
```

默认端点：

- PostgreSQL：`localhost:15432`（容器内部仍为 `5432`）
- S3 API：`http://localhost:9000`
- 初始 bucket：`medication-platform-local`

默认凭证仅用于合成本地数据。不得把真实视频、生产凭证或医院数据放入该环境。

SeaweedFS 仅是本地开发使用的 S3 兼容实现，不代表生产对象存储产品已获批准。应用代码只能依赖 S3 API，不能依赖 SeaweedFS 专有接口。

停止服务但保留数据：

```powershell
docker compose --env-file .env.example -f infra/docker-compose/compose.yaml down
```

删除命名卷会永久删除本地数据，因此不提供自动删除命令。
