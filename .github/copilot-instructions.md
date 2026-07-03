# GitHub Copilot Instructions

This repository is the `mcp` Java/Spring Boot third-party system and MCP tool integration service. Before suggesting or changing code, read `AGENTS.md` and `docs/ai-coding/README.md`.

Follow these project rules:

- Follow `docs/ai-coding/AI_DIRECTORY_STRUCTURE_GUIDE.md` before adding, moving, or deleting directories.
- Keep Java code under `src/main/java/com/kellen`; tests belong under `src/test/java/com/kellen`.
- Do not nest sibling repositories such as `utils`, `user`, `gateway`, `admin-web`, `ai`, or `ai-agent` inside this repository.
- Keep Dubbo RPC interfaces and DTOs in sibling `rpc-api`; this service only implements provider code or calls published contracts.
- Do not change existing secrets, RabbitMQ addresses, Nacos addresses, database URLs, or production configuration values. Report file paths and line numbers only.
- Third-party credentials, OAuth tokens, MCP tool permissions, tenant isolation, connector allowlists, and tool-call audit semantics must be enforced by backend services.
