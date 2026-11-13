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

## 测试稳定性

- 测试结果只能由输入决定，不依赖真实时间、线程调度、连接复用、端口、执行顺序或本机遗留状态。需要时间的逻辑注入 `Clock`；等待异步结果用有总时限的条件等待，不用固定 `Thread.sleep` 赌时序。
- 本地 HTTP 桩服务每个测试独占随机端口，并在 `@AfterEach` 停止。同一测试对同一被测实例请求多次时，桩响应设 `Connection: close`，不依赖 JDK `HttpServer` 的 keep-alive 连接回收时序。更复杂的桩可评估 MockWebServer 或 WireMock，新增依赖按依赖变更处理。
- 连接池、事件循环、静态单例、系统属性等进程级共享状态，不能成为测试之间的隐含依赖；用到时写明隔离方式，不依赖测试类或测试方法的执行顺序。
- 新增或修改涉及网络、并发、超时、流式的测试，合并前本机连续三轮运行该测试类全部通过：`./gradlew test --rerun --tests '<测试类全名>'`。
- 偶发失败按缺陷处理：先查明原因再修，不用自动重试、放宽断言、`@Disabled` 或删除测试换取通过。确需临时禁用时，在注解里写明原因与恢复条件，并在交付说明中列出。
- 本机 macOS 通过不代表 CI 的 Linux 通过；只在一个平台验证过的结论要写明平台。

## 必跑命令

```bash
./gradlew clean test
```
