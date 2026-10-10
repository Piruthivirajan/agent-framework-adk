# Agentic Framework (take-home assignment)

Java 21, Spring Boot, Google ADK for Java, MongoDB Atlas (local: mongodb/mongodb-atlas-local).
Full plan: docs/PLAN.md. Progress log: docs/PROGRESS.md (update it after every task).

## Non-negotiables
- Requirements and the 4 test scenarios in docs/PLAN.md are fixed. Do not change stack or scope without asking me.
- One database only: MongoDB. No second store.
- Agents are AgentPlugin beans, given to the coordinator as AgentTool (not sub-agents).
- MCP credentials: never logged, never returned by the API, never sent to the model.
- Do not guess ADK or MongoDB API method names. Check the pinned version's sources/javadoc first.
- Secrets live in .env (gitignored). Never commit keys.

## Commands
- DB: docker compose up -d
- Build and test: ./mvnw verify
- Run: ./mvnw spring-boot:run

## How we work
- Plan first, then small steps. Run the build/tests after each step.
- I must defend every line in a technical interview. After each step, explain what you built and why.
