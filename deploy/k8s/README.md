# KBook Kubernetes 部署清单

## 部署顺序

```bash
# 1. 创建命名空间
kubectl apply -f 07-namespace.yaml

# 2. 创建 ConfigMap 和 Secrets
kubectl apply -f 01-configmap.yaml
kubectl apply -f 02-secrets.yaml

# 3. 部署基础设施（MySQL、Redis、ES、Qdrant）
kubectl apply -f 03-infra.yaml

# 4. 部署应用
kubectl apply -f 04-app.yaml
kubectl apply -f 06-nginx-configmap.yaml

# 5. 创建 Ingress
kubectl apply -f 05-ingress.yaml
```

## 说明

- **01-configmap.yaml**: 非敏感配置（数据库地址、端口、AI 配置路径等）
- **02-secrets.yaml**: 敏感配置（密码、API Key），**请勿提交明文到 Git**
- **03-infra.yaml**: 基础设施 StatefulSet/Deployment + Service（MySQL、Redis、ES、Qdrant）
- **04-app.yaml**: 后端 Deployment + Service + PVC；前端 Nginx Deployment + Service + PVC
- **05-ingress.yaml**: Ingress 暴露 book.keiskei.top，API 代理到后端
- **06-nginx-configmap.yaml**: 前端 Nginx 配置文件
- **07-namespace.yaml**: kbook 命名空间

## 环境变量覆盖

所有配置通过 ConfigMap 和 Secret 注入，生产环境应：

1. 修改 `02-secrets.yaml` 中的密码和 API Key
2. 使用外部托管数据库（RDS 等）而非集群内 MySQL
3. 配置 TLS 证书 Secret（`kbook-tls`）
4. 前端静态文件需先构建并上传到 `frontend-static-pvc`

## AI 配置

`ai-config.json` 通过 ConfigMap (`kbook-ai-config`) 挂载到容器的 `/app/config/ai-config.json`。
修改配置后，执行以下命令热加载：

```bash
# 更新 ConfigMap
kubectl create configmap kbook-ai-config --from-file=ai-config.json -n kbook -o yaml --dry-run=client | kubectl apply -f -

# 触发后端热加载
kubectl exec deploy/backend -n kbook -- curl -X POST http://localhost:8181/api/admin/ai-config/reload
```
