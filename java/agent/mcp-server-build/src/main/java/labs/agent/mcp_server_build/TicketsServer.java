package labs.agent.mcp_server_build;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** tickets 工单 MCP 服务端：Spring AI 的 MCP 服务端 starter 扫描 @McpTool，自动生成工具清单和 JSON Schema。 */
@SpringBootApplication
public class TicketsServer {
    public static void main(String[] args) {
        SpringApplication.run(TicketsServer.class, args);
    }
}
