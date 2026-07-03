# MCP 服务说明

`mcp` 是 Kellen 微服务群中的 MCP 三方系统接入服务，负责承接 AI 工具目录、三方系统 connector、受控工具调用、调用审计、Nacos AI Registry MCP Server 自动发布和 AgentCard 发布。

当前项目保留 Java 微服务基础工程、统一异常处理、DDL 入口、Nacos profile、AI 编码规范和最小 MCP 工具接口；真实三方系统 connector 需要按业务系统逐步接入。

## 技术基线

- Java 17
- Spring Boot 4.0.4
- Spring Cloud 2025.1.1
- Spring Cloud Alibaba 2025.1.0.0
- Nacos Client 3.2.2
- Nacos AI Registry / MCP Server / A2A AgentCard
- Seata Server 2.7.0
- Seata Client 2.6.0
- MyBatis-Plus
- 公共能力依赖 `com.kellen:utils`
- RPC 契约依赖 `com.kellen:rpc-api`
- 测试框架统一使用 JUnit 5

## 服务职责

当前服务定位为 MCP 工具接入中心，后续新增业务时优先围绕以下边界展开：

- MCP 工具目录和工具启停。
- 三方系统 connector 适配。
- 工具调用权限、租户隔离、限流、重试、熔断和审计。
- Nacos AI Registry MCP Server 自动发布，让 Nacos MCP 管理可发现当前服务和工具清单。
- Nacos AI Registry AgentCard 发布，让 `ai` / `ai-agent` 可发现 MCP 工具入口。
- 管理后台配置接口，供 `admin-web` 做三方系统配置、工具启停和调用日志页面。

模型执行、Prompt、具体 Agent 推理不在本服务重复实现，继续由同级 `ai` / `ai-agent` 负责。

公共工具、认证上下文、多租户、统一返回、统一异常、数据权限、MyBatis-Plus 公共配置等能力不在本服务重复实现，统一复用同级 `utils` 项目。

当前先保持一个 `mcp` 微服务模块。后续多个 MCP 功能优先放在本模块内按 `tool`、`connector`、`credential`、`audit`、`job` 分层扩展；只有出现独立部署生命周期、强安全隔离、明显资源瓶颈或完全不同团队边界时，才拆新的 MCP 微服务。

## 鉴权边界

`mcp` 不签发入站 token，不保存三方客户端密钥。所有进入 Kellen 的身份认证、JWT、三方客户端身份、OpenAPI HMAC 签名校验和 nonce 防重放统一由同级 `user` 服务负责。

MCP 对外 HTTP 接入使用 OAuth2 Resource Server 模式：

```text
外部 MCP 客户端 -> gateway /oauth2/token -> user 签发短期 Bearer token -> gateway /api/mcp -> mcp 校验 token
```

`mcp` 只校验：

- `Authorization: Bearer <token>`。
- JWT `issuer` 符合 `mcp.oauth.resource-server.issuer`。
- JWT `aud` / `resource` 命中 `mcp.oauth.resource-server.audiences`。
- JWT `scope` 包含 `mcp.tools.read` 或 `mcp.tools.call`。

scope 到权限码映射：

| OAuth scope | MCP 权限码 |
| --- | --- |
| `mcp.tools.read` | `mcp:tool:list` |
| `mcp.tools.call` | `mcp:tool:call` |

MCP 服务只做业务授权补充：

- 当前用户是否有权限查看或调用某个工具。
- 当前租户是否启用某个 connector。
- 工具是否处于启用状态。
- 某次工具调用是否满足参数、数据范围、限流和审计要求。

外部系统应统一配置 gateway 地址，不直接暴露 `user` 或 `mcp` 服务端口。MCP 客户端通过 `GET /.well-known/oauth-protected-resource` 发现资源元数据，通过其中的 `authorization_servers` 发现授权服务器，再调用 `POST /oauth2/token` 换短期 access token。未携带 token、token 无效或 scope 不满足时，MCP 服务按 OAuth Bearer challenge 返回 `WWW-Authenticate`，便于通用 MCP 客户端自动发现授权配置。

服务间需要保留旧企业内部 HMAC 校验时，优先复用 `user` 暴露的 `AuthOpenApiRpcService` 或 `/auth/open/signatures/verify` 能力。

MCP 主动调用外部系统时如需出站 API Key、OAuth token 或 Webhook secret，应按具体 connector 单独设计配置和存储方案。不要为了预留而新增通用 `mcp-secrets.yaml`。

## 基础设施地址

除 `application.yml` 中连接 Nacos 自身的启动入口外，MySQL、Redis、Seata、XXL-JOB、Elasticsearch、Kibana、Zipkin 等基础设施地址统一放在 Nacos `reuse-configuration.yaml`。

蒲公英、Tailscale、节点小宝等组网地址变化时，优先只修改公共配置中的基础设施变量，例如：

```yaml
custom:
  infra-host: <INFRA_HOST>
  infra-nacos-addr: ${custom.infra-host}:8848
  infra-mysql-addr: ${custom.infra-host}:3306
  infra-redis-addr: ${custom.infra-host}:6379
  infra-seata-addr: ${custom.infra-host}:8091
  infra-xxl-job-admin: http://${custom.infra-host}:19090/xxl-job-admin
  infra-elasticsearch-addr: ${custom.infra-host}:9200
  infra-elasticsearch-uri: http://${custom.infra-host}:9200
  admin-server-url: <ADMIN_SERVER_URL>
  zipkin-base-url: <ZIPKIN_BASE_URL>
  local-service-host: <LOCAL_SERVICE_HOST>
```

业务配置只引用公共变量，不直接散落基础设施 IP。

## Nacos 配置

本地 `application.yml` 只保存连接 Nacos 需要的启动入口和 `spring.config.import` 远程配置列表。当前 MCP 加载：

```text
logging.yml
reuse-configuration.yaml
traffic-governance.yaml
security-auth.yaml
swagger.yaml
mcp.yaml
mcp-spring.yaml
mybatis-plus.yaml
redis.yaml
rabbitmq.yaml
elasticsearch.yaml
seata.yaml
  zipkin.yaml
  admin.yaml
  dubbo.yaml
  xxl-job.yaml
  a2a.yaml
```

配置分工：

- `mcp.yaml`：工具目录、connector 开关、allowlist、限流、重试、超时等非敏感业务配置，以及 `mcp.server.registry.*` MCP Server 自动发布配置。
- `mcp.yaml`：同时可放 `mcp.oauth.resource-server.*` 的非敏感校验策略，例如 issuer、audience、scope 名称、MCP 外部基地址和授权服务器地址。`external-base-url` 与 `authorization-server` 必须指向外部可访问的 gateway / auth 地址，本地默认可用 `${MCP_EXTERNAL_BASE_URL:http://localhost:7100}` 与 `${MCP_AUTHORIZATION_SERVER:http://localhost:7100}`，测试和生产通过环境变量覆盖为对应网关域名。
- `mcp-spring.yaml`：MCP 服务 Spring、数据源和服务私有基础配置。
- `rabbitmq.yaml`：RabbitMQ 连接和消息基础配置，MCP 需要消息能力时必须导入。
- `a2a.yaml`：Nacos AI Registry / A2A 公共协议和命名约定。

配置边界：

- 放代码：工具名、工具协议方法、接口路径、JSON Schema、权限码、审计字段、connector 类型和服务端防 SSRF 规则。这些属于代码契约，不能只靠 Nacos 临时拼装。
- 放 Nacos：启停开关、connector allowlist、目标系统白名单、超时、重试、限流、服务可达地址、namespace、版本号和是否发布到 AI Registry。
- 放 `user` 数据库或后续管理端：OAuth `client_id`、client secret 哈希、scope、audience、密钥轮换和审计状态。
- 放安全存储或具体 connector 配置：MCP 主动调用外部系统所需的出站 API Key、OAuth token、Webhook secret。不要新增通用 `mcp-secrets.yaml` 预留密钥。

## Nacos AI Registry

`mcp` 启动后可以自动发布两类 AI Registry 元数据：

- `McpServerRegistrar`：发布 MCP Server 到 Nacos **MCP 管理**，默认协议为 Streamable HTTP，endpoint 默认用 `REF` 引用 Nacos 服务发现里的 `mcp` 服务。
- `McpAgentCardRegistrar`：发布 A2A AgentCard 到 Nacos **Agent 管理**，供 `ai` / `ai-agent` 发现工具入口。

默认配置前缀：

```yaml
mcp:
  server:
    registry:
      enabled: false
      server-addr: ${custom.infra-nacos-addr}
      namespace: ${custom.namespace}
      username: ${custom.nacos-username}
      password: ${custom.nacos-password}
      context-path: /nacos
      server-name: mcp
      version: "1.0.2"
      description: Kellen MCP 三方系统工具接入服务
      protocol: mcp-streamable
      export-path: /api/mcp
      endpoint-type: REF
      service-name: mcp
      group-name: ${custom.nacos-group:DEFAULT_GROUP}
      advertise-host: ${custom.local-service-host:}
      advertise-port: 7700
  agent:
    registry:
      enabled: false
      server-addr: ${custom.infra-nacos-addr}
      namespace: ${custom.namespace}
      username: ${custom.nacos-username}
      password: ${custom.nacos-password}
      context-path: /nacos
      advertise-host: ${custom.local-service-host:}
      advertise-port: 7700
      support-tls: false
      protocol-version: "1.0"
      card-version: "1.0.0"
      tenant: ""
      provider-org: Kellen MCP
      agent-name: mcp-tool-agent
```

注意：

- `mcp.server.registry.enabled=false` 和 `mcp.agent.registry.enabled=false` 时不连接 AI Registry，便于本地编译、单元测试和空环境启动。
- MCP Server 默认用 `REF` endpoint，不写死机器 IP；`DIRECT` 只适合无法走 Nacos 服务发现的特殊场景。
- Java 微服务形态优先使用 `mcp-streamable`；不要把本服务配置成截图里的 Stdio 模式。
- `advertise-host` 必须是 `ai` 或 `ai-agent` 所在网络可达的地址；容器部署不要把只能本机访问的 `127.0.0.1` 写入 AgentCard。
- MCP Server 的工具、路径、协议或 endpoint 变化时递增 `mcp.server.registry.version`。
- AgentCard 的路径、技能、描述、协议或可达地址变化时递增 `mcp.agent.registry.card-version`。
- AI Registry 只保存工具发现元数据，不保存三方系统密钥。

## 当前接口

OpenAPI 原始文档地址：

```text
http://127.0.0.1:7700/v3/api-docs
```

当前最小 MCP 接口：

```text
POST /api/mcp
GET  /api/mcp/tools
POST /api/mcp/tools/call
GET  /.well-known/oauth-protected-resource
```

`POST /api/mcp` 是 Streamable HTTP MCP JSON-RPC 入口，当前支持：

```text
initialize
tools/list
tools/call
```

权限码建议：

```text
mcp:tool:list
mcp:tool:call
mcp:connector:list
mcp:connector:create
mcp:connector:update
mcp:connector:delete
mcp:audit:list
```

外部客户端配置：

```text
类型: streamableHttp
URL: http://<gateway>/api/mcp
Authorization: Bearer <user /oauth2/token 返回的 access_token>
```

如果客户端不支持 OAuth 自动换 token，不要配置永久 Bearer；应使用轻量 proxy 保存 `client_id/client_secret` 并自动刷新短期 token。

Controller 必须使用 OpenAPI3 注解：

```java
@Tag(name = "MCP 工具接入", description = "维护 MCP 工具目录和三方系统工具调用入口")
@Operation(summary = "查询 MCP 工具目录", description = "返回当前租户和环境可暴露给 AI 编排层的 MCP 工具元数据")
```

需要鉴权的接口使用：

```java
@PreAuthorize("hasAuthority('mcp:tool:call')")
```

## RESTful 接口约定

新增 Controller 必须优先使用 RESTful 风格：

```text
GET    /mcp/manage/connectors?current=1&size=10
GET    /mcp/manage/connectors/options
GET    /mcp/manage/connectors/{id}
POST   /mcp/manage/connectors
PUT    /mcp/manage/connectors/{id}
DELETE /mcp/manage/connectors/{id}
GET    /mcp/manage/audit-logs?current=1&size=10
```

规则：

- 不使用 `/save`、`/update`、`/remove`、`/select`、`/page` 等动词路径。
- 查询使用 `GET` URL 参数，不给普通查询接口加 `@RequestBody`。
- 分页接口使用 `@GetMapping(params = {"current", "size"})` 表达分页参数存在性。
- `options` 只表示轻量选择项集合，不承接完整管理列表。

## DDL

MyBatis-Plus DDL 入口在：

```text
src/main/java/com/kellen/bean/MysqlDdl.java
```

当前 MCP 模块只声明公共基础设施脚本：

```text
db/common-infra-schema.sql
```

全新或空业务库首次启动前，必须先在目标业务库手动执行同级 `../utils/src/main/resources/db/common-infra-schema.sql`，先建 `ddl_history` 和 Seata AT `undo_log`。Seata AT 会在 `DataSource` 初始化时先检查 `undo_log`，不能依赖应用首次启动自动创建该表。

后续新增 MCP 业务表时：

- SQL 放在 `src/main/resources/db/*.sql`。
- 先检查目标数据库 `ddl_history`。
- 已执行或无法确认是否执行过的 SQL 文件不得回改。
- 新增变更使用新的 SQL 文件，并追加到 `MysqlDdl#getSqlFiles()`。
- 工具目录、connector、凭据索引、token 元数据、调用审计等表字段、Entity、BO、Query、VO、ServiceQuery、ServiceResults 和测试同步补齐。

## 安全边界

- MCP 工具参数不能直接决定服务端访问任意 URL；目标系统和 endpoint 必须来自受控 connector 配置。
- 入站三方鉴权、开放接口签名和 nonce 防重放必须走 `user`，MCP 不保存入站 `clientSecret`。
- 出站三方系统 API key、OAuth token、refresh token、Webhook secret、签名密钥和 Nacos 密码只允许存在 Nacos、环境变量、密钥管理系统或经过批准的加密存储方案中。
- 日志和响应不得输出完整认证头、完整 token、完整 URL 查询串、三方系统敏感原始响应或个人敏感信息。
- 工具调用必须校验当前用户、租户、工具权限、connector 启用状态和数据范围。
- 批量同步、导出、Webhook 回调和 AI 自动调用必须有审计日志和限流。

## AI 编码规范

AI 编码规范入口：

```text
AGENTS.md
docs/ai-coding/README.md
```

阅读顺序：

```text
docs/ai-coding/AI_CODING_GUIDE.md
docs/ai-coding/PROJECT_CODING_SPEC.md
docs/ai-coding/SECURITY_CODING_SPEC.md
docs/ai-coding/NACOS_CONFIG_SPEC.md
docs/ai-coding/examples
```

新增或修改 Java 代码时：

- 类、字段、公开方法和关键业务方法必须补充说明职责、业务含义和边界的注释。
- 方法前优先使用 JavaDoc；复杂逻辑优先使用逻辑块前置说明。
- 关键业务逻辑、认证上下文、租户上下文、权限、Redis、DDL、事务、异常处理、工具调用和返回值组装必须解释为什么这样做，避免机械逐行注释。
- 公共能力先检查同级 `utils`，不要在 MCP 微服务里重复写通用工具类。
- 修改完成后同步更新本 README。

## 验证命令

```bash
./gradlew clean compileJava -x test --no-daemon
./gradlew test --no-daemon
bash scripts/check-secrets.sh
```

如果依赖 `utils` 或 `rpc-api` 有调整，先在对应同级项目执行：

```bash
./gradlew publishToMavenLocal
```
