# 测试分层规范

## 定位

`mcp` 是 MCP 工具目录、三方系统 connector、凭据引用、工具调用、调用审计和 AI Registry 注册相关业务服务。核心业务测试必须覆盖真实 Spring Boot 业务链路，不能只靠纯对象断言。

## 主流分层

- 单元测试：DTO、枚举、小工具、小函数，使用 JUnit 5 + AssertJ。
- Slice 测试：局部 Controller/Mapper 可以使用 `@WebMvcTest`、`@MybatisTest`，只作为补充。
- Service 集成测试：核心业务用 `@SpringBootTest` 注入真实 Spring Bean，验证事务、AOP、权限上下文、多租户、Mapper、工具权限和审计边界。
- HTTP 集成测试：核心对外接口用 `@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)`，通过 `TestRestTemplate` 或 `WebTestClient` 发真实 HTTP 请求。
- Connector 测试：三方系统调用默认使用 stub、fake、mock server 或测试环境，不允许普通测试默认连接生产三方系统。
- 跨服务测试：Dubbo、Gateway、Nacos、AI Registry、Redis 等链路放到独立 integration profile 或测试环境。

## assertThat 规则

`assertThat` 是断言工具，可以继续使用。判断测试是否充分，看被断言的数据是否来自真实业务链路，而不是看是否用了 `assertThat`。

## 业务测试要求

- 工具目录、工具调用、connector 开关、凭据引用、租户隔离、权限校验、SSRF 防护和调用审计必须有 Spring Boot 级别测试或明确的集成验证说明。
- Controller 测试优先走真实 HTTP 请求，验证路由、参数校验、权限、统一响应和异常处理。
- Service 测试必须验证真实 Bean、真实 Mapper、真实事务和真实租户上下文。
- MCP Server / AgentCard 注册测试不得默认依赖真实 Nacos；本地单测默认关闭 `mcp.server.registry.enabled` 和 `mcp.agent.registry.enabled`，需要验证 AI Registry 时使用显式集成测试开关。
- 不允许只用 mock 或纯 Java 对象测试替代核心业务集成测试。

## 测试数据

- 使用 `test` profile 和测试库。
- 测试数据可以落库，但必须可重复、可识别、可清理。
- 测试前置数据必须由 SQL、fixture、builder、stub server 或测试接口显式准备，不依赖本机临时数据刚好存在。
- 三方系统凭据测试只能使用占位符、mock secret 或测试环境专用凭据，不能写入真实密钥。

## 必跑命令

```bash
./gradlew clean test
```
