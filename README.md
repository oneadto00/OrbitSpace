# OrbitSpace

围绕团队项目构建的研发协作平台，将成员权限、Git 轨道与本地未提交改动（WIP）共享、空间主群聊、文件交付、实时协作文档与 PDF 批注整合进同一个协同空间。

> 共享者通过 `orbit` CLI 预览并主动上报本地状态或 Patch；团队成员仅需浏览器与空间权限即可查看改动、行级批注、发起带快照引用的讨论并沉淀文档与结论。

---

## 核心能力

### 1. 空间管理与全路径撤权
- **账号与多设备会话**：支持邮箱注册、登录、验证与密码重置，采用短时效 Access Token 搭配 `HttpOnly` Refresh Cookie 与 CSRF 防护。
- **空间与角色权限**：以 `Space` 组织团队项目，提供邀请链接与 `OWNER` / `ADMIN` / `MEMBER` / `VIEWER` 四级角色控制。
- **周期隔离与即时撤权**：成员关系绑定独立的授权周期号（`membership_epoch`）。成员被移出或退出空间后，HTTP API、WebSocket 订阅/发送、CLI 设备凭证、快照读取、文件下载与文档协同路径同步失效；重新加入会开启新周期，不自动恢复旧凭证。

### 2. 主动式 Git / WIP 共享与轨道画布
- **主动上报，无后台监控**：CLI 采用 OAuth 设备授权流登录，凭证绑定用户、设备、工程与当前成员授权周期。
- **隐私过滤与固定载荷确认**：CLI 不直接打包 `.git` 目录，支持 `.orbitignore` 规则与已跟踪敏感文件/明文密钥扫描。`--dry-run` 仅作本地预览；正式上报前先固定待发载荷，扫描并经用户确认后再上传。
- **顺序控制与受控补传**：每个工作树通过服务端管理的 `worktree_epoch` 与单调递增 `capture_seq` 条件更新当前 WIP 指针，防止乱序上报或迟到事件覆盖新状态；支持通过 `orbit backfill` 按需预览并补传缺失对象。
- **Web 轨道画布与代码讨论**：前端通过 Web Worker 计算拓扑布局并结合 SVG 渲染提交与 WIP 轨道，提供单点详情、数据充足范围内的双点 Diff 与行级代码批注。

### 3. 空间主群聊与讨论结论
- **上下文引用卡片**：每个空间包含唯一主群聊，支持文本消息与不可变引用卡片（提交、WIP 快照、代码批注、文件、文档段落与 PDF 批注），引用快照不会随后续新上传发生漂移。
- **可靠消息同步**：消息写入在同一数据库事务内完成权限校验、房间加锁与序号分配（`seq` / `event_seq`），写入消息与 `outbox_events` 并提交后才返回 ACK；支持 `client_msg_id` 幂等与双游标断线补拉。
- **轻量讨论结论**：支持围绕讨论主题记录与切换 `OPEN` / `RESOLVED` 状态。

### 4. 文件交付与好友空投
- **空间文件区**：继承空间权限，支持分片上传与对象存储分离管理（元数据存 MySQL，对象内容存 S3 兼容存储）。
- **好友与独立空投**：支持好友管理、面向指定好友的独立文件空投与授权下载管理。

### 5. 协作文档、PDF 阅读批注与受控异步集成
- **多人协作文档**：前端集成 TipTap + Yjs，由独立 Hocuspocus 服务承接实时协同，后端维护持久化快照、历史版本与可定位的引用锚点。
- **PDF 阅读与批注**：支持空间内 PDF 文件阅读、索引与批注转发至主群聊。
- **受控异步解析与远端同步**：Python Worker 消费 Redis Streams 异步任务（Diff 补全、缩略图、PDF 索引、远端 Git 拉取与附来源摘要）；受控远端 Git 同步通过独立 `egress-proxy` 与可信主机白名单隔离网络出站，结果写回前在业务事务内校验任务与资源生命周期版本，防止迟到任务覆盖或复活已取消/撤权内容。

---

## 数据能力边界

OrbitSpace 严格区分客户端实际共享的数据级别，不会凭空还原未上传或被排除的文件：

| 数据等级 | 支持能力 | 边界说明 |
| :--- | :--- | :--- |
| `METADATA_ONLY` | 轨道拓扑、文件变更状态与统计 | 不可查看代码正文 |
| `PATCH_ONLY` | 查看当次允许范围内的变化与补丁 | 不承诺任意两点完整代码树对比 |
| `TREE_AVAILABLE` | 在对象完整且获授权范围内重建文件树并对比 | 不还原未共享或已排除文件 |

每次共享同步附带 `content_manifest`（`AVAILABLE`、`ABSENT_CONFIRMED`、`NOT_SHARED`、`MISSING`、`UNSUPPORTED`），明确区分“未共享 / 缺失”与“文件已删除”；原始 Git OID 与脱敏/筛选后的快照 ID 分开保存。

---

## 系统架构与目录结构

项目采用 **Java 21 模块化单体 + 按职责拆分的扩展服务** 架构，通过 OpenAPI 与 JSON Schema 维护跨端契约：

```text
.
├── apps/
│   ├── server/                   # Java 21 + Spring Boot 3.5 模块化单体（Maven 多模块）
│   │   ├── orbit-kernel/         # 跨模块 SPI 端口、领域异常、枚举与公共模型
│   │   ├── orbit-platform/       # 对象存储、Outbox、邮件与撤权恢复底座
│   │   ├── orbit-security/       # 鉴权、CSRF、空间角色切面与 WebSocket 安全
│   │   ├── orbit-mod-identity/   # 账号、多设备会话与 CLI 设备授权
│   │   ├── orbit-mod-space/      # 空间、邀请与成员授权周期管理
│   │   ├── orbit-mod-chat/       # 空间主群聊、双游标事件与讨论结论
│   │   ├── orbit-mod-gitproject/ # Git 工程、WIP 状态、补传、批注与远端配置
│   │   ├── orbit-mod-friendship/ # 好友关系
│   │   ├── orbit-mod-file/       # 分片上传、空间文件区与独立空投
│   │   ├── orbit-mod-document/   # 协作文档、版本快照、PDF 批注与摘要
│   │   ├── orbit-mod-integration/# 异步任务调度、HMAC 内部回调与出口策略
│   │   └── orbit-app/            # 应用装配入口、Flyway 迁移与守护任务
│   ├── web/                      # React 19 + TypeScript + Vite + Tailwind CSS v4 前端工作台
│   └── cli/                      # Node.js + TypeScript 主动共享命令行工具（orbit）
├── packages/
│   └── shared-types/             # 由 OpenAPI 契约生成的共享 TypeScript 类型包
├── services/
│   ├── collab-hocuspocus/        # Node.js + Hocuspocus Yjs 实时文档协同服务
│   └── worker-py/                # Python Redis Streams 异步解析与远端拉取 Worker
├── contracts/                    # OpenAPI 契约与 WebSocket / CLI JSON Schema
├── infra/                        # Docker Compose 编排、Nginx 本地 TLS、MinIO 与出口代理
├── package.json
├── pnpm-workspace.yaml
├── tsconfig.base.json
└── LICENSE
```

### 核心技术栈

| 层次 | 技术实现 |
| :--- | :--- |
| **核心服务端** | Java 21、Spring Boot 3.5、Spring Security、Spring WebSocket、Flyway |
| **Web 前端** | React 19、TypeScript、Vite、Tailwind CSS v4、Zustand、TanStack Query、TipTap、Yjs、pdfjs-dist |
| **命令行工具** | Node.js、TypeScript、Commander、Zod（直接调用系统 `git`） |
| **协作与解析服务** | Hocuspocus（Node.js + ioredis）、Python Worker（redis、boto3、httpx、Pillow、PyMuPDF） |
| **基础设施** | MySQL 8.4、Redis 7.4、MinIO（S3 兼容）、Mailpit（本地邮件）、Nginx（TLS 反向代理） |

---

## 本地启动与开发

### 环境要求
- Docker 与 Docker Compose
- Node.js `^22.18.0 || ^24.11.0 || >=26.0.0` 与 `pnpm@11.25.0`（构建前端、CLI 或共享类型时）
- Java 21 与 Maven 3.9+（宿主机直接构建后端时）
- Python `>= 3.9`（宿主机直接运行 `worker-py` 时）

### 方式一：通过 Docker Compose 启动

[infra/docker-compose.yml](./infra/docker-compose.yml) 提供按需组合的启动 Profile：

```bash
# 1. 仅启动基础设施（MySQL、Redis、MinIO、Mailpit、Nginx 本地 TLS），适合宿主机联调后端与前端
docker compose -f infra/docker-compose.yml up -d --build

# 2. 启动核心全栈（基础设施 + server + web）
docker compose -f infra/docker-compose.yml --profile fullstack up -d --build

# 3. 启动含 Python 异步 Worker 的环境（基础设施 + server + web + worker-py）
docker compose -f infra/docker-compose.yml --profile p2 up -d --build

# 4. 启动完整服务（额外包含 collab-hocuspocus 与受控 egress-proxy）
docker compose -f infra/docker-compose.yml --profile p3 up -d --build
```

启动后的本地入口（默认仅绑定 `127.0.0.1`）：
- **Web 与 API 入口**：`https://localhost:8443`
- **Mailpit 本地邮件收件箱**（用于查看注册验证与重置密码邮件）：`http://localhost:8025`
- **MinIO 控制台**：`http://localhost:9001`

Nginx 会在首次启动时自动生成本地自签名 TLS 证书，可按需导出供本机信任或 `curl --cacert` 使用：

```bash
docker compose -f infra/docker-compose.yml cp \
  nginx:/etc/nginx/local-tls/localhost.crt /tmp/orbitspace-localhost.crt
```

> **安全提示**：[infra/env.example](./infra/env.example) 与 [infra/docker-compose.yml](./infra/docker-compose.yml) 中的默认密码和密钥均为**本地开发占位符**。部署至非本机环境前，务必通过外部环境变量或私有 `--env-file` 替换 `MYSQL_ROOT_PASSWORD`、`MYSQL_PASSWORD`、`MINIO_ROOT_USER`、`MINIO_ROOT_PASSWORD`、`ORBIT_AUTH_JWT_KEY`、`ORBIT_GIT_KEY_V1` 与 `ORBIT_INTERNAL_HMAC_SECRET`。

### 方式二：宿主机分模块构建与开发

```bash
# 安装 JS/TS 工作区依赖
pnpm install

# 从 contracts/openapi/*.yaml 重新生成共享类型
pnpm --filter @orbitspace/shared-types build

# 启动 Web 开发服务器（需先启动基础设施与后端）
pnpm --filter @orbitspace/web dev

# 构建前端与 CLI
pnpm --filter @orbitspace/web... build
pnpm --filter @orbitspace/cli... build

# 构建 Java 后端可执行 JAR
mvn -f apps/server/pom.xml clean package -DskipTests
```

---

## CLI 命令速查

构建完成后，可通过 `apps/cli/dist/cli.js`（或安装后的 `orbit` 命令）执行主动共享。全局支持 `--server <origin>` 选项或 `ORBITSPACE_SERVER` 环境变量（默认 `https://localhost:8443`）：

| 命令 | 说明 | 常用选项 |
| :--- | :--- | :--- |
| `orbit login` | 通过浏览器完成 CLI 设备授权 | `--device-label <name>`、`--metadata-only` |
| `orbit init` | 将当前 Git 工作树绑定到空间工程并初始化代次 | `--project <project-id>`（必填） |
| `orbit push-state` | 预览或主动共享当前工作树状态与允许范围内的 Patch | `--dry-run`、`--with-diff`、`-m, --message <note>` |
| `orbit backfill` | 检查缺失对象并在本地预览确认后受控补传 | `--target <ref-or-id>`（必填）、`--base <ref-or-id>`、`--project <id>`、`--dry-run` |

CLI 本地凭证与工作树状态保存在 `~/.orbitspace/`（位于 Git 工作树之外），并强制校验 `0600` 文件权限与并发文件锁。

---

## 开源许可

本项目基于 **Apache License 2.0** 开源，详见 [LICENSE](./LICENSE)。
