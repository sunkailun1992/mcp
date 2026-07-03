package com.kellen;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import com.kellen.mcp.config.McpAgentRegistryProperties;
import com.kellen.mcp.config.McpOAuthResourceServerProperties;
import com.kellen.mcp.config.McpServerRegistryProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.bind.annotation.RestController;

/**
 * MCP 服务启动类。
 *
 * <p>注册三方系统连接器、MCP 工具目录、Nacos 配置、AI Registry MCP Server /
 * AgentCard、异步任务、Dubbo、事务和 MyBatis Mapper 扫描。</p>
 */
@EnableCaching
@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class})
@RestController
@EnableDiscoveryClient
	@EnableConfigurationProperties({
			McpAgentRegistryProperties.class,
			McpOAuthResourceServerProperties.class,
			McpServerRegistryProperties.class
	})
@EnableDubbo
@EnableAsync
@EnableScheduling
@EnableTransactionManagement
@MapperScan("com.kellen.*.mapper")
public class ApiApplication {

	/**
	 * JVM 进程入口，启动 MCP 服务。
	 *
	 * @param args 命令行参数
	 */
	public static void main(String[] args) {
		SpringApplication.run(ApiApplication.class, args);
	}
}
