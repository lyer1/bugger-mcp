# JVM Debugger MCP Server

An MCP (Model Context Protocol) server for remote JVM debugging. This tool bridges AI assistants (like Copilot CLI, Claude Code, Cursor, etc.) and Java's standard JDI (Java Debug Interface), allowing you to interactively debug running Java applications using natural language.

Whether you're debugging a local Java app or a remote application server (like JBoss EAP 8), this server attaches to standard JDWP socket ports (e.g., `8787` or `5005`) and exposes powerful debugging tools natively to the LLM.

## Features

- **Standard Java Debugging**: Uses the official `com.sun.jdi` API to connect and interact.
- **Remote Attach**: Connect to any JVM started with standard JDWP socket transport arguments.
- **Interactive Inspection**: Get a list of threads, suspend/resume execution, and inspect call stacks and local variables.
- **Breakpoints and Stepping**: Set breakpoints on classes and lines, wait for execution events, and step over, into, or out of functions.

## Installation and Build

### Prerequisites
- JDK 17+ (or compatible) installed
- Maven (`mvn`) installed

### Building from Source

This project uses Maven to build a fat jar (including dependencies like Jackson for JSON handling) for easy execution.

```bash
git clone <repository_url>
cd jvm-debugger-mcp
mvn clean package
```

The executable fat jar will be located at `target/jvm-debugger-mcp-1.0-SNAPSHOT.jar`.

## Usage with Copilot CLI (and other MCP Clients)

This server uses the standard MCP input/output mechanism over `stdio`. It can be integrated into any MCP-compliant client.

### Using with GitHub Copilot CLI

While the exact configuration for the GitHub Copilot CLI is actively evolving, integrating an MCP server generally involves pointing the CLI to the executable command.

If Copilot CLI supports MCP configuration via a configuration file (like Claude Code or Cursor), you add a configuration snippet like the following to your MCP settings file:

```json
{
  "mcpServers": {
    "jvm-debugger": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/your/jvm-debugger-mcp/target/jvm-debugger-mcp-1.0-SNAPSHOT.jar"]
    }
  }
}
```

*Note: Replace `/absolute/path/to/your/jvm-debugger-mcp` with the actual path to the cloned repository.*

### Debugging a Java Application

1. **Start your Java application with debugging enabled.** For example, using JBoss or just a standard JAR:
   ```bash
   java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:8787 -jar myapp.jar
   ```

2. **Open your AI CLI/Assistant** (e.g. Copilot CLI).
3. **Ask the assistant to attach:**
   > "Attach the JVM debugger to localhost on port 8787."
4. **Interact and Debug:**
   > "List the active threads."
   > "Suspend the JVM."
   > "Get the stack trace and local variables for thread ID 1."
   > "Set a breakpoint in com.example.MyController at line 42."
   > "Resume the JVM and wait for a breakpoint event."
   > "Step over."

## Available MCP Tools

| Tool Name | Description | Required Arguments |
|---|---|---|
| `jvm_attach` | Attach to a remote JVM debug port. | `host` (string), `port` (integer) |
| `jvm_disconnect` | Disconnect from the currently attached JVM. | - |
| `jvm_suspend` | Suspend all threads in the JVM. | - |
| `jvm_resume` | Resume execution of all suspended threads. | - |
| `jvm_get_threads` | Get a list of all threads and their statuses. | - |
| `jvm_get_stack` | Get the call stack and local variables for a suspended thread. | `threadId` (integer) |
| `jvm_set_breakpoint` | Set a breakpoint at a specific class and line number. | `className` (string), `line` (integer) |
| `jvm_wait_for_event` | Wait for a JVM event (like hitting a breakpoint or class prepare). | `timeoutMs` (integer) |
| `jvm_step` | Step over, into, or out on a specific thread. | `threadId` (integer), `type` ('over', 'into', 'out') |

## Technical Architecture

The architecture consists of three core components:

1. **McpServer Core (`McpServer.java` & `Json.java`)**
   - Handles the Model Context Protocol (MCP) JSON-RPC specification.
   - Reads incoming JSON messages asynchronously from `System.in` by scanning for `\r\n\r\n` headers and standard `Content-Length`.
   - Routes `tools/list` requests to enumerate capabilities.
   - Routes `tools/call` requests to the registered Java tools, passing parsed JSON arguments and responding with formatted results over `System.out`.

2. **JDI Wrapper (`JdiDebugger.java`)**
   - Wraps the official `com.sun.jdi` and `com.sun.jdi.connect` APIs.
   - **Attach:** Uses the `com.sun.jdi.SocketAttach` connector to establish a TCP connection to the target JVM.
   - **Inspection:** Iterates over `ThreadReference` objects to extract thread states and ID numbers. If a thread is suspended, iterates over `StackFrame` to extract visible `LocalVariable`s and their corresponding values. (Handles `AbsentInformationException` gracefully if code was not compiled with the `-g` flag).
   - **Execution Control:** Uses `EventRequestManager` to create `BreakpointRequest`, `ClassPrepareRequest` (for deferred breakpoints on unloaded classes), and `StepRequest`. It consumes the `EventQueue` to wait for and report events back to the client.

3. **Tool Registry (`Main.java`)**
   - Serves as the entry point (`public static void main`).
   - Instantiates the `JdiDebugger`.
   - Defines a list of `McpServer.Tool` anonymous classes. Each class specifies its `name`, `description`, `schema` (JSON Schema for arguments), and a `call` method that bridges directly into the `JdiDebugger` methods.
