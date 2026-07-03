# AGENTS.md

本文件是 `mcp` 服务的 AI 编码入口。AI 修改本项目代码前，必须先阅读本文件，再按任务风险阅读 `README.md` 和 `docs/ai-coding` 下的规范。

## 项目定位

- 项目名称：`mcp`
- 项目类型：MCP 三方系统接入服务 / AI 工具接入中心
- 技术栈：Java 17、Spring Boot、Gradle、MyBatis-Plus、Nacos、Nacos AI Registry、Dubbo、`com.kellen:utils`、`com.kellen:rpc-api`
- 同级依赖：`../rpc-api` 提供跨服务 RPC 接口和 DTO 契约；`../utils` 提供公共响应、认证上下文、租户、多数据源和基础工具；`../gateway` 负责路由；`../ai` 和 `../ai-agent` 负责 AI 编排和模型执行；`../admin-web` 负责后台配置页面
- 核心风险：未复用 `user` 统一鉴权、工具越权调用、租户隔离失效、三方系统出站凭据泄露、AI 工具幻觉调用、SSRF、批量数据外发、调用审计缺失、Nacos AI Registry MCP Server / AgentCard 地址不可达

## 修改前阅读顺序

任何代码修改前必须先阅读：

1. `README.md`：确认当前 MCP 服务职责、接口范围、Nacos dataId、MCP Server / AgentCard 自动发布和验证命令。
2. `docs/ai-coding/README.md`：确认 AI 编码入口和阅读顺序。
3. `docs/ai-coding/AI_CODING_GUIDE.md`：确认执行步骤、注释规则、测试和安全要求。
4. `docs/ai-coding/AI_DIRECTORY_STRUCTURE_GUIDE.md`：确认 Java 微服务目录、测试、资源、文档和跨项目边界。
5. `docs/ai-coding/AI_COMMENT_STYLE_GUIDE.md`：确认注释规范、自解释优先、禁止注释掉死代码和排版要求。
6. `docs/ai-coding/AI_DESIGN_PATTERN_GUIDE.md`：确认 connector、adapter、strategy、registry、audit 等 MCP 设计模式边界。
7. `docs/ai-coding/BRANCHING_SPEC.md`：确认分支命名、短分支生命周期、release/hotfix、tag 和清理规则。
8. `docs/ai-coding/ENVIRONMENT_CONFIG_SPEC.md`：确认环境、Nacos namespace、Java profile 和前端/小程序边界。
9. `docs/ai-coding/VERSIONING_SPEC.md`：确认 `group = 'com'`、`version = '1.0.0'`、补丁递增和消费者同步规则。
10. `docs/ai-coding/RPC_API_CODING_SPEC.md`：涉及 Dubbo RPC provider、consumer、接口或 DTO 时必须阅读。
11. `docs/ai-coding/TESTING_SPEC.md`：确认 SpringBootTest、真实 HTTP 集成测试、三方系统 stub 和安全测试边界。
12. `docs/ai-coding/PROJECT_CODING_SPEC.md`：确认微服务分层、RESTful、权限、多租户、数据权限和 DDL 规范。
13. `docs/ai-coding/AI_ENGINEERING_GUARDRAILS.md`：确认风险分级、Definition of Done 和交付门禁。
14. `docs/ai-coding/SECURITY_CODING_SPEC.md`：涉及接口、权限、三方凭据、工具调用、数据隔离、脱敏、SSRF、上传下载、SQL 或测试安全时必须阅读。
15. `docs/ai-coding/UTILS_PUBLIC_SPEC.md`：涉及公共规范、错误码、数据库、乐观锁或 `utils` 能力时阅读。
16. `docs/ai-coding/NACOS_CONFIG_SPEC.md`：修改 Nacos 配置中心、AI Registry、共享 dataId 或 `application.yml` import 前必读。

## 项目边界

- `mcp` 负责 MCP 工具目录、三方系统连接器、工具调用编排、调用审计、限流、重试、Nacos MCP Server 发布和 AgentCard 发布。
- `mcp` 的入站认证、JWT、三方客户端身份、OpenAPI HMAC 签名和 nonce 防重放必须统一复用 `../user`，不得在本服务重复实现。
- `mcp` 不负责模型执行；模型执行继续在 `../ai-agent`，AI 编排和 AgentCard 消费继续在 `../ai`。
- `mcp` 不直接把三方系统密钥返回给 AI、前端或调用方；只暴露受控工具和稳定结构化结果。
- 新增 connector 时必须明确系统边界、鉴权方式、租户映射、权限码、数据范围、审计字段、超时、重试和降级策略。
- 新增业务表必须按 `docs/ai-coding/PROJECT_CODING_SPEC.md` 补齐公共治理字段；`version` 只做乐观锁，业务版本使用 `*_version` 命名。
- 公共响应、认证上下文、多租户、错误码和工具能力优先复用 `../utils`。
- 跨服务 Dubbo RPC 调用优先复用 `../rpc-api` 中的接口和 DTO，不在本服务复制契约。
- 新增本服务 OpenAPI 入口、调整服务前缀，或新增同级 Java 微服务需要接入网关时，必须同步检查 `../gateway` 的 Nacos `gateway-spring.yaml`；需要聚合到 Swagger UI 的服务要补业务路由和 `springdoc.swagger-ui.urls`，并验证对应网关文档路径与 `/swagger-ui/index.html`。
- 多个 MCP 功能默认继续放在当前 `mcp` 模块内，按工具、connector、credential、audit、job 分层；只有独立部署、安全隔离、资源瓶颈或生命周期明显不同才拆新服务。

## AI Registry 边界

- `mcp` 作为 MCP Server 生产端，默认 Server 名称为 `mcp`，通过 `mcp.server.registry.*` 自动发布到 Nacos MCP 管理。
- `mcp.server.registry.enabled=false` 时不发布 MCP Server；本地编译和单元测试默认关闭。
- MCP Server 默认使用 `mcp-streamable` + `REF` endpoint，引用 Nacos 服务发现中的 `mcp` 服务；Java 微服务不要配置成 Stdio。
- MCP Server 的工具清单、JSON Schema、协议路径和版本属于代码契约；启停、版本号、endpoint 引用和可达地址放 Nacos 配置中心。
- `mcp` 作为工具接入 AgentCard 生产端，默认 Agent 名称为 `mcp-tool-agent`。
- `mcp.agent.registry.enabled=false` 时不连接 Nacos AI Registry，便于本地编译和测试。
- AgentCard 地址必须是 `ai` / `ai-agent` 所在网络可达地址；容器部署应显式配置 `mcp.agent.registry.advertise-host` 和必要的端口。
- AgentCard 的接口路径、技能、描述或可达地址变化时递增 `mcp.agent.registry.card-version`。
- Nacos AI Registry 只保存工具发现元数据，不保存三方系统真实凭据、OAuth token 或业务密钥。

## AI 工程门禁

- 工具调用、`user` 鉴权复用、出站 connector 凭据、批量同步、导入导出、Webhook、跨租户查询、MCP Server 发布和 AgentCard 发布默认中高风险。
- 新增或修改功能前，必须按 `AI_AUTOMATION_WORKFLOW.md` 整理需求说明、验收标准和开发手册。
- 完成后必须按 `docs/ai-coding/AI_ENGINEERING_GUARDRAILS.md` 做风险分级、Definition of Done、测试证据、安全检查、风险和回滚说明。
- 涉及当前用户上下文、租户、数据权限、`user` 签名校验复用、出站三方凭据、Webhook、SSRF 或批量外发时，必须有针对性测试或清楚说明未验证项。
- 测试分层按 `docs/ai-coding/TESTING_SPEC.md` 执行；核心工具调用不能只靠 mock 或纯对象断言，必须补 Spring Boot 级别测试或三方系统 stub 测试。

## 多智能体协作规则

- 子智能体可以并行分析 Controller、Service、connector、credential、audit、DDL、admin-web 调用、gateway 路由和 Nacos 配置。
- 不允许多个 worker 同时修改同一核心 connector、凭据解析逻辑、权限规则、审计表或 DDL 脚本。
- MCP 服务全新或空业务库首次启动前，必须先在目标业务库手动执行 `../utils/src/main/resources/db/common-infra-schema.sql`；Seata AT 会在 `DataSource` 初始化时先检查 `undo_log`，不能依赖应用首次启动自动创建该表。
- 最终工具权限、凭据边界、租户隔离、AI Registry 注册和测试结论必须由主智能体统一判断。

## 验证命令

按风险选择验证：

```bash
./gradlew clean compileJava -x test
./gradlew test
bash scripts/check-secrets.sh
```

涉及 `rpc-api` 契约、三方系统 connector、MCP Server、AgentCard、权限或数据权限时，还需要说明契约编译、接口验证、数据库验证、Nacos/网关路由验证、AI Registry 读写验证或依赖外部环境的未验证项。

## 禁止事项

- 禁止信任 AI、前端或三方系统传入的当前用户、租户、权限、工具 allowlist 和凭据字段。
- 禁止在 MCP 内重复实现入站三方鉴权；外部系统调用 MCP/Kellen 的身份、签名和 nonce 校验必须走 `user`。
- 禁止把出站三方系统 API key、OAuth token、refresh token、Webhook secret、签名密钥、数据库密码或 Nacos 密码写入仓库、README 示例或测试数据。
- 禁止把三方系统原始响应、敏感字段、完整认证头、完整 URL 查询串、患者/用户隐私数据直接写入日志或返回给 AI。
- 禁止让工具参数直接决定服务端访问的任意 URL；所有 connector 目标必须来自受控配置，防止 SSRF。
- 禁止写死测试用户、测试租户、三方系统地址、Nacos 地址或本机路径。
- 禁止 AI 触碰真实密钥/凭证（疑似密钥只能告警，由项目负责人处理）；配置中心结构性调整（dataId 拆分/合并、import 顺序、`${}` 引用、Nacos 接入地址、namespace/group）允许 AI 自主完成，但必须保值不改值，不得擅自变更生产业务配置的实际取值。
- 禁止在当前服务复制 `utils` 公共工具源码或 `rpc-api` 契约源码；业务 RPC 契约缺失时回到真实 `../rpc-api` 实现，公共能力缺失时先评估是否应回到 `../utils` 实现。
