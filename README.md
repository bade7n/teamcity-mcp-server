# TeamCity MCP Plugin (Server)

TeamCity 2025.11 server plugin exposing MCP tools via the Java MCP SDK, using an SSE + message transport implemented on top of TeamCity's `javax.servlet` endpoints.

## Why custom transport
The official Java MCP SDK provides Servlet-based transports built on `jakarta.servlet` (Servlet 6). TeamCity server plugins still use `javax.servlet`, so this plugin ships a small, compatible SSE transport that implements the MCP server transport interface while running inside TeamCity's web layer.

## Configure tokens
Set internal property `mcp.tokens` on the TeamCity server (comma-separated).

Example in `internal.properties`:

```
mcp.tokens=token1,token2
```

Auth headers supported:
- `Authorization: Bearer <token>`
- `X-Api-Token: <token>`

## MCP endpoints (HTTP+SSE)
- `GET /mcp/sse`
- `POST /mcp/message?sessionId=...`

The MCP server emits the message endpoint via the SSE `endpoint` event, e.g.:
`/mcp/message?sessionId=...`.

## Tools
- `start_build`
  - Input: `{ "buildTypeId": "MyProject_Build" }`
  - Output: `{ "buildId": 12345, "queueItemId": "678", "state": "queued" }`

- `build_status`
  - Input: `{ "id": "<buildIdOrQueueItemId>" }`
  - Output: `{ "buildId": 12345, "state": "running|finished|queued", "status": "success|failure|unknown" }`

- `build_log`
  - Input: `{ "id": "<buildId>" }`
  - Output: `{ "buildId": "12345", "logUrl": "<server>/downloadBuildLog.html?buildId=12345&plain=true" }`

## Build

```
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -Dmaven.repo.local=/Volumes/app/work/local-mcp/.m2 package
```

Plugin ZIP will be produced at:

```
target/teamcity-mcp-plugin.zip
```
