# Demo Scripts

本目录提供面向本地体验的最小脚本集。

## 推荐顺序

```bash
./scripts/demo/start-demo.sh
./scripts/demo/health-check.sh
./scripts/demo/create-session.sh
./scripts/demo/get-session.sh 1
./scripts/demo/list-business.sh 1
./scripts/demo/get-business-detail.sh 1 PROD-002
./scripts/demo/execute-apply.sh 1
```

## 说明

- 所有脚本默认使用 `BASE_URL=http://localhost:8080`
- 如需修改地址，可在执行前导出环境变量，例如：

```bash
BASE_URL=http://127.0.0.1:8080 ./scripts/demo/health-check.sh
```
