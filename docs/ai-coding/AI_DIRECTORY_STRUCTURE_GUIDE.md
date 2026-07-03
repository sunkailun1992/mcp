# AI 目录管理规范

本规范约束 AI 在 `mcp` 服务中新增、移动、拆分和命名目录的方式。目录管理必须基于当前 Java 17、Spring Boot、Gradle、MyBatis-Plus、Nacos 和 AI Registry 微服务结构。

## 核心依据

- Gradle Java SourceSet：生产源码放 `src/main/java`，生产资源放 `src/main/resources`，测试源码放 `src/test/java`，测试资源放 `src/test/resources`。
- Spring Boot 包结构：主应用类位于根包下，业务组件位于根包子包内，避免默认包和扫描边界外的业务类。
- Java 包命名：包名小写，目录结构必须和 `package` 声明一致。
- MyBatis-Plus 分层：Controller、Service、Mapper、Entity、BO、Query、VO、ServiceQuery、ServiceResults 职责清晰。
- GitHub / AI 规范：CI 放 `.github/workflows/`，AI 规范放 `docs/ai-coding/`，根目录只保留 `AGENTS.md` 作为入口。

## 当前标准目录

```text
.
├── AGENTS.md
├── README.md
├── build.gradle
├── settings.gradle
├── gradle/
├── src/
├── docs/ai-coding/
├── scripts/
└── .github/
```

生产代码根包：

```text
src/main/java/com/kellen
```

当前 MCP 业务包：

| 目录 | 职责 |
| --- | --- |
| `com/kellen/mcp/controller` | MCP HTTP 接口入口，例如工具目录、工具调用和管理配置接口。 |
| `com/kellen/mcp/config` | MCP 服务私有配置绑定，例如 AI Registry、connector 开关和限流配置。 |
| `com/kellen/mcp/entity` | MCP 工具、connector、凭据索引、审计等数据库实体和枚举。 |
| `com/kellen/mcp/entity/bo` | 新增、修改、工具调用等写入请求对象。 |
| `com/kellen/mcp/entity/dto` | MCP 协议、MCP Server、AgentCard 或 connector 边界上的数据传输对象。 |
| `com/kellen/mcp/entity/query` | 查询请求对象。 |
| `com/kellen/mcp/entity/vo` | 接口响应视图对象。 |
| `com/kellen/mcp/mapper` | MyBatis-Plus Mapper 和 XML 对应接口。 |
| `com/kellen/mcp/service` | Service 接口、工具目录、工具调用、MCP Server 和 AgentCard 注册编排。 |
| `com/kellen/mcp/service/impl` | Service 实现。 |
| `com/kellen/mcp/service/query` | 查询服务入参和复杂读模型。 |
| `com/kellen/mcp/service/results` | Service 层输出结果对象。 |
| `com/kellen/mcp/connector` | 三方系统 connector 适配器；每个 connector 必须守住凭据和 SSRF 边界。 |
| `com/kellen/mcp/credential` | 凭据引用、OAuth token 元数据和敏感字段脱敏逻辑。 |
| `com/kellen/mcp/audit` | 工具调用审计、指标和告警相关逻辑。 |
| `com/kellen/job` | MCP token 刷新、调用补偿或审计归档等定时任务入口。 |
| `com/kellen/bean` | 当前服务本地基础对象；公共基础能力优先回到 `../utils`。 |

## 目录规则

- 新增 MCP 能力优先放在 `com/kellen/mcp` 现有分层下，不新建并行的 `dao`、`domain`、`manager`、`modules` 体系。
- 三方系统 connector、凭据管理、工具状态和调用审计等扩展应先复用现有 service / adapter / strategy / registry 边界。
- 公共工具类、公共配置、公共异常、公共响应、认证上下文和多租户能力优先回到 `../utils`。
- 跨服务接口、DTO 和枚举优先回到 `../rpc-api`。
- `src/main/resources/db` 只放当前 MCP 服务的 SQL；公共基础脚本来自 `../utils`。
- Nacos 本地入口只放 `application.yml`、`application-dev.yml`、`application-test.yml`、`application-prod.yml`。
- AI 规范放 `docs/ai-coding`；不要把规范散落到业务源码目录。

## 分包演进

- 当前按技术分层组织包（controller/service/mapper/entity 等，package-by-layer）。
- 当 connector、credential、audit、registry 等业务域在多个层目录中持续膨胀，且改动总是跨多个层目录联动时，才评估按业务特性分包（package-by-feature）。
- 演进必须有真实维护痛点，不为小规模代码强行切换，并同步 Spring 组件扫描、MyBatis 扫描、测试和文档。

## 禁止事项

- 不把 `../utils`、`../rpc-api`、`../ai`、`../ai-agent`、`../gateway` 等同级仓库复制进当前仓库。
- 不把三方系统 SDK 样例项目、临时下载包或本机测试脚本放进生产源码目录。
- 不把凭据、token、Nacos 密码、Webhook secret 或本机路径放进文档、测试资源和示例配置。
- 不移动已执行 SQL 脚本；新增 DDL 必须追加新文件。

## 检查清单

- 是否保持 `com/kellen/mcp` MCP 服务边界清晰？
- 是否没有复制 `utils` 公共源码或 `rpc-api` 契约源码？
- 是否没有移动或替换已有密钥、Nacos 地址、数据库连接和生产配置？
- 是否为新增 connector、credential、audit、registry 代码补充测试或验证说明？
