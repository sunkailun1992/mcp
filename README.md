# MCP 服务说明

`mcp` 是 Kellen 微服务群中的 MCP 三方系统接入服务，负责承接 AI 工具目录、三方系统 connector、受控工具调用、调用审计、Nacos AI Registry MCP Server 自动发布和 AgentCard 发布。

当前项目保留 Java 微服务基础工程、统一异常处理、DDL 入口、Nacos profile、AI 编码规范和最小 MCP 工具接口；真实三方系统 connector 需要按业务系统逐步接入。

## 启动入口

主应用类为 `com.kellen.McpApplication`，IDE 可直接运行该类。Gradle `bootRun` 与可执行 JAR 的入口由 `springBoot.mainClass` 统一指定；仍使用 `./gradlew bootRun` 或 `java -jar build/libs/mcp.jar`，工作目录保持为当前服务仓库根目录。

## 技术基线

- Java 21
- Spring Boot 4.0.9
- Spring Cloud 2025.1.4
- Spring Cloud Alibaba 2025.1.0.0
- Nacos Client 3.2.2（服务端 3.3.0；AI Registry SDK 迁移到 3.3.0 需另行验证）
- Nacos AI Registry / MCP Server / A2A AgentCard
- Seata Server 2.7.0
- Seata Client 2.6.0
- MyBatis-Plus
- 公共能力依赖 `com:utils`
- RPC 契约依赖 `com:rpc-api`
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

除 `application-*.yml` 中连接 Nacos 自身的启动入口外，PostgreSQL、Redis、Seata、Elasticsearch、Kibana、Zipkin 等基础设施地址统一放在 Nacos `reuse-configuration.yaml`。

蒲公英、Tailscale、节点小宝等组网地址变化时，优先只修改公共配置中的基础设施变量，例如：

```yaml
custom:
  infra-host: <INFRA_HOST>
  infra-nacos-addr: ${custom.infra-host}:8848
  infra-postgresql-addr: ${custom.infra-host}:<POSTGRESQL_PORT>
  infra-redis-addr: ${custom.infra-host}:6379
  infra-seata-addr: ${custom.infra-host}:8091
  infra-elasticsearch-addr: ${custom.infra-host}:9200
  infra-elasticsearch-uri: http://${custom.infra-host}:9200
  local-service-host: <LOCAL_SERVICE_HOST>
```

业务配置只引用公共变量，不直接散落基础设施 IP。

## Nacos 配置

基础 `application.yml` 保持服务名、端口和默认 profile；各 `application-*.yml` 保存对应环境连接 Nacos 的启动入口和 `spring.config.import` 远程配置列表。当前 MCP 加载：

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
  dubbo.yaml
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
src/main/java/com/kellen/bean/PostgresqlDdl.java
```

当前 MCP 模块只声明公共基础设施脚本：

```text
db/postgresql/common-infra-schema.sql
```

全新或空业务库首次启动前，必须先在目标业务库手动执行同级 `../utils/src/main/resources/db/postgresql/common-infra-schema.sql`，先建 `ddl_history` 和 Seata AT `undo_log`。Seata AT 会在 `DataSource` 初始化时先检查 `undo_log`，不能依赖应用首次启动自动创建该表。

后续新增 MCP 业务表时：

- SQL 放在 `src/main/resources/db/postgresql/*.sql`。
- 先检查目标数据库 `ddl_history`。
- 已执行或无法确认是否执行过的 SQL 文件不得回改。
- 新增变更使用新的 SQL 文件，并追加到 `PostgresqlDdl#getSqlFiles()`。
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

## 通用安全与架构同步

公共安全依赖精确固定为 `com:utils:1.6.6`。Actuator 除 health 外要求运行时配置 `security.actuator.token` 并在 `actuatorReq` 请求头提交令牌；缺失或错误令牌一律拒绝。令牌由独立环境配置提供，不写入仓库。隔离的消费者回归测试只装配真实 MVC/Actuator 与安全链，不连接 Nacos、数据库、MQ 或远程服务；它不替代目标环境验收。

MCP OAuth 过滤器保存请求级 `McpVerifiedCaller`，进入请求前清除残留身份、结束时清理。验签失败才返回 OAuth challenge，connector 异常交由原有业务异常链处理。协议 `isError` 跟随工具返回状态，不把拒绝结果标为成功。

MCP Server 与 AgentCard 注册采用制品构建信息加规范化内容摘要生成不可变版本，发布前后核对内容和 latest；基于 namespace/资源的 Redis 锁防止合作实例并发覆盖，旧制品不能夺回 latest。没有构建信息、锁不可用、读回不匹配都停止发布，服务启动保持 fail-soft。Registry 应配置可用 Redis；关闭 Registry 不依赖 Redis 或构建信息。`public-base-url` 可声明受控 HTTP(S) 网关根地址，拒绝凭据、路径、查询和片段，未配置时保留 REF；`transport-protocol` 只接受 HTTP(S)，不与 MCP transport 混用。注册日志仅记录固定原因码或 SDK 数字错误码。

本仓继续只发布现有两个占位工具及对应目录/调用技能；不包含医疗报告、医院连接器、天气或其他来源业务。应用当前版本为 `1.0.6`，本次由 `1.0.5` 递增以移除 dev Nacos 地址的 localhost 回退；未据本机打包推定 Registry 或应用制品已发布。

公共 RPC 契约固定为 `com:rpc-api:1.0.9`，包含供执行层使用的强制验签入口；本服务不调用旧的可选签名方法来授予 Agent 身份。JWT 签发/验签共用运行时 `security.jwt.secret`（Base64 编码且解码至少 32 字节），无内置默认密钥；测试使用独立合成密钥。

## 个人 Maven 坐标边界

个人公共库使用 `com:utils:1.6.6` 与 `com:rpc-api:1.0.9`；同一远端 Aliyun Maven 地址中的公司同名制品属于 `com.kellen` group。制品名仍是 `utils` / `rpc-api`，以完整 groupId:artifactId:version 区分，不能混用公司坐标或覆盖既有版本。Java 包名继续使用 `com.kellen.*`，不随 Maven groupId 改动。

公共库先以明确版本发布 Maven Local 供消费者验证；远端发布和 POM/JAR 校验由公共库流程独立完成。凭据保留在用户私有配置或环境变量中，不写入仓库。`com:utils:1.5.0` 已在远端 metadata 出现，本轮不复用，即使其单文件路径返回404。

## 本机 dev 私有连接配置

以 MCP 服务根目录为工作目录启动时，Spring Boot 默认加载 `config/application-dev.properties`，无需修改基础 `application.yml` 或预先 export。该文件仅保存 Nacos 地址、dev namespace、group、username、password 五个连接键，权限为 0600，路径已精确加入 Git ignore；不得提交真实凭据。操作系统环境变量优先于 properties；test/prod 不加载 dev 私有文件，dev 默认 namespace 继续为 `github-dev`。

dev 地址按 `GITHUB_NACOS_DEV_SERVER_ADDR` → `GITHUB_NACOS_SERVER_ADDR` 解析，缺失则配置解析失败，不回退 127.0.0.1。Console 使用 18080，Java SDK 的 `server-addr` 使用 8848，gRPC 使用 9848；Console 地址不能替代 SDK 地址。中间件、认证和业务配置仍由独立 Nacos 环境提供。

## 本批可观测性接入

当前服务版本为 `1.2.2`，公共能力精确消费 `com:utils:1.6.2`，并由公共包传递引入轻量 `com:fleet-observability:0.1.2` 的安全 HTTP 终态事件。公共制品先完成 Maven Local 验证，再按对应 main 确定提交发布远端；目标环境的实际采集与全链读回单独验收。九服务共享 `actuator.yaml` 暴露 health/info/prometheus；除 health 外仍要求 `actuatorReq` 运维令牌，空值或错误值失败关闭。`logging.yml` 使用 ECS 固定字段白名单，未知 MDC、原始消息、正文和异常不进入采集结果；OTel agent 标准 `trace_id/span_id` 与业务会话 ID 分开。应用指标带固定 application/environment 标签，禁止动态业务 ID。

Admin/Zipkin 模板与三 profile 的 imports 已退役。服务 JVM 的可选 OTel agent 由统一启动入口负责，不通过全局 JAVA_TOOL_OPTIONS 注入 Gradle 或测试。部署时 Java 指标只由 Micrometer Prometheus 提供，OTEL_METRICS_EXPORTER 与 OTEL_LOGS_EXPORTER 均为 none；监控组件、配置发布和实际采集仍须独立验证。回滚须恢复匹配的制品与配置，先恢复旧 dataId 再回退旧消费者，不能用已删除的配置项假装完成回滚。

个人业务 PostgreSQL 迁移基线服务版本为 `1.2.0`，首版公共库为 `com:utils:1.6.0`（传递安全采集库 `com:fleet-observability:0.1.1`）。版本和本机构建通过不代表目标环境已切库，正式迁移与运行态验收另行记录。

个人 Java 服务已移除 XXL-JOB 依赖、执行器配置与演示任务，不再读取 `xxl-job.yaml`。Conductor OSS 作为 NAS 独立服务通过 REST 接入；当前未接入业务 Java 调度接口，不添加 Java 21 SDK 或虚构任务开关。其他环境仍使用的 NAS 容器不由本次个人源码退役处理。

## PostgreSQL 启动修复补丁

当前服务补丁版本为 `1.2.2`，公共依赖精确固定 `com:utils:1.6.2`，接入 PostgreSQL/Druid 超时单位修复及事务化、失败即拒绝启动的 DDL 执行。原定连接 3 秒、读取 30 秒预算保持，RPC 契约 `com:rpc-api:1.0.7` 与轻量采集库 `com:fleet-observability:0.1.2` 保持。公共制品、Jar 构建、服务器与 IDEA 原生启动须分别验证，修改依赖不代表目标环境已恢复。

本次公共观测补丁通过 `com:utils:1.6.2` 精确传递 `com:fleet-observability:0.1.2`，修复 Servlet Security 在 ERROR 分派中再次拒绝导致终态缺失的问题；认证和错误响应保持。源码与测试通过后，目标进程和追踪/日志联结仍须单独读回验收。

`1.2.4` 为第1阶段底座升级：Java 21、Gradle 9.9.1、Spring Boot 4.0.9、Spring Cloud 2025.1.4、Nacos 服务端 3.3.0（客户端暂保持 3.2.2，见上方技术基线），公共依赖 `com:utils:1.6.6`（传递 `com:fleet-observability:0.1.4`）与 `com:rpc-api:1.0.9`，OTel BOM 1.67.0 对齐 Java agent 2.33.1。业务接口与配置不变；运行环境须为 Java 21。
