# AI 设计模式规范

本规范约束 AI 在 `mcp` 服务中选择、引入和调整设计模式的方式。目标是让 MCP 工具目录、三方系统 connector、凭据引用、OAuth/token 元数据、调用审计和 AI Registry 注册逻辑清晰可扩展。

## 1. 总原则

- 先识别当前语言、框架和模块边界；本项目默认按 Java 17、Spring Boot、MyBatis-Plus、Nacos AI Registry、Gradle 和 `com.kellen:utils` 生态落地。
- 优先沿用当前分层：Controller、BO、Query、VO、Entity、Mapper、Service、ServiceQuery、ServiceResults。
- 设计模式必须服务 MCP 边界：工具可见性、工具调用权限、租户隔离、凭据保护、SSRF 防护、重试熔断、调用审计、MCP Server 发布和 AgentCard 发布。
- 不允许为简单 CRUD 硬套 Factory、Manager、Abstract 层或过深继承。
- 公共工具和跨微服务能力优先回到真实同级 `../utils`，不在 `mcp` 内复制公共源码。

## 2. 标准参考

- GoF 设计模式：Strategy、Adapter、Factory、Template Method、Observer、State、Builder 等。
- SOLID 原则：判断职责拆分、依赖方向和接口稳定性。
- GRASP 原则：判断工具职责应该归属工具目录、connector、credential、audit、registry 还是编排服务。
- Martin Fowler 企业应用模式：Service Layer、Repository/Mapper、DTO、Transaction Script。
- Spring / Nacos 官方惯例：配置绑定、服务发现、MCP Server 发布、AgentCard 发布、生命周期回调和配置分层。

## 3. 本项目推荐模式

### Application Service / Service Layer

- Controller 只处理 HTTP 入参、权限注解和统一响应。
- Service 编排工具目录、connector 选择、凭据解析、调用审计、重试和事务。
- 工具列表和工具调用是不同权限场景，不要混用同一个权限判断。

### Repository / Mapper

- Mapper 只处理数据库访问。
- 查询条件和排序优先放 ServiceQuery。
- 返回转换和枚举说明优先放 ServiceResults。

### Adapter

适用三方系统 connector。

- 外部系统参数、签名、OAuth/token、失败码、分页和重试语义封装在适配器里。
- 上层 Service 只依赖统一 connector 接口。
- connector 不能让请求参数直接决定任意 URL，目标地址必须来自受控配置。

### Strategy

适用不同三方系统、工具动作和鉴权方式。

- 不同系统、不同认证模式或不同工具动作可以用策略隔离。
- 策略必须明确租户、工具权限、凭据引用和数据范围边界。
- 不把所有系统分支堆在一个 Service 方法里。

### Registry / Catalog

适用工具目录、工具元数据、Nacos MCP Server 和 AgentCard。

- 工具目录负责可见工具和元数据，不直接执行三方系统调用。
- MCP Server 注册只发布 MCP 协议入口、工具 JSON Schema 和服务发现引用，不保存三方系统密钥。
- AgentCard 注册只发布可发现入口，不保存三方系统密钥。
- 工具目录、JSON Schema、接口路径、协议或可达地址变化时必须同步评估 `mcp.server.registry.version` 和 AgentCard 版本。

### Observer / Publisher

适用工具调用后需要触发审计、告警、指标或补偿。

- 工具调用成功、失败、超时、熔断和凭据失效可考虑发布事件或观察者。
- 审计失败不能吞掉主调用失败原因，也不能泄露敏感请求体。
- 事件内容必须脱敏，不能包含密钥、完整认证头或敏感业务数据。

### State

适用 connector、OAuth token、调用任务和审计状态。

- 简单状态用枚举即可。
- 有非法迁移、刷新、过期、吊销、重试、补偿等规则时集中状态迁移。
- 状态迁移必须可测试，不能散落在 Controller、定时任务和 connector 中。

### Template Method / Pipeline

适用工具调用固定流程。

- 可按校验工具、校验租户权限、解析凭据、构造请求、调用 connector、脱敏响应、写审计组织流程。
- 任一关键校验失败应失败关闭，不继续调用三方系统。
- 流程稳定后再抽模板或流水线，不为了单一场景提前抽象。

## 4. 谨慎或禁止使用

- 手写 Singleton：Spring Bean 已管理生命周期。
- Service Locator：优先构造器注入。
- 巨型 Manager：不要把工具目录、connector、凭据、审计、AI Registry 全塞进一个类。
- 过深继承：系统差异优先用接口和组合表达。
- 全局静态可变状态：工具调用状态、token 和重试上下文必须可追踪。
- 模式先行重构：没有重复实现、稳定扩展点或维护痛点时不改结构。

## 5. 检查清单

- 是否区分工具目录权限、工具调用权限和管理配置权限？
- 是否复用了 `utils` 公共能力而非复制公共源码？
- 是否没有绕过认证、租户、数据权限、凭据保护和统一响应？
- 新模式是否解决真实 connector 扩展、凭据差异或状态迁移问题？
- 是否存在更简单的函数、枚举、接口或组合方案？
- 是否补充 Controller 请求层、Service 或 connector 相关测试？
