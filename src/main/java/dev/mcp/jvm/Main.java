package dev.mcp.jvm;

import java.util.*;

public class Main {
    public static void main(String[] args) {
        JdiDebugger debugger = new JdiDebugger();

        List<McpServer.Tool> tools = new ArrayList<>();

        tools.add(new McpServer.Tool() {
            public String name() { return "jvm_attach"; }
            public String description() { return "Attach to a remote JVM debug port (e.g. host: 'localhost', port: 8787)."; }
            public Map<String, Object> schema() {
                return Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "host", Map.of("type", "string"),
                        "port", Map.of("type", "integer")
                    ),
                    "required", List.of("host", "port")
                );
            }
            public Map<String, Object> call(Map<String, Object> args) throws Exception {
                String host = String.valueOf(args.get("host"));
                int port = ((Number) args.get("port")).intValue();
                return debugger.attach(host, port);
            }
        });

        tools.add(new McpServer.Tool() {
            public String name() { return "jvm_disconnect"; }
            public String description() { return "Disconnect from the attached JVM."; }
            public Map<String, Object> schema() {
                return Map.of("type", "object", "properties", Map.of());
            }
            public Map<String, Object> call(Map<String, Object> args) throws Exception {
                return debugger.disconnect();
            }
        });

        tools.add(new McpServer.Tool() {
            public String name() { return "jvm_resume"; }
            public String description() { return "Resume execution of the JVM."; }
            public Map<String, Object> schema() {
                return Map.of("type", "object", "properties", Map.of());
            }
            public Map<String, Object> call(Map<String, Object> args) throws Exception {
                return debugger.resume();
            }
        });

        tools.add(new McpServer.Tool() {
            public String name() { return "jvm_suspend"; }
            public String description() { return "Suspend execution of the JVM."; }
            public Map<String, Object> schema() {
                return Map.of("type", "object", "properties", Map.of());
            }
            public Map<String, Object> call(Map<String, Object> args) throws Exception {
                return debugger.suspend();
            }
        });

        tools.add(new McpServer.Tool() {
            public String name() { return "jvm_get_threads"; }
            public String description() { return "Get a list of all threads in the attached JVM."; }
            public Map<String, Object> schema() {
                return Map.of("type", "object", "properties", Map.of());
            }
            public Map<String, Object> call(Map<String, Object> args) throws Exception {
                return debugger.getThreads();
            }
        });

        tools.add(new McpServer.Tool() {
            public String name() { return "jvm_get_stack"; }
            public String description() { return "Get the call stack and local variables for a specific suspended thread."; }
            public Map<String, Object> schema() {
                return Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "threadId", Map.of("type", "integer", "description", "The unique ID of the thread.")
                    ),
                    "required", List.of("threadId")
                );
            }
            public Map<String, Object> call(Map<String, Object> args) throws Exception {
                long threadId = ((Number) args.get("threadId")).longValue();
                return debugger.getStack(threadId);
            }
        });

        tools.add(new McpServer.Tool() {
            public String name() { return "jvm_set_breakpoint"; }
            public String description() { return "Set a breakpoint at a specific class and line number."; }
            public Map<String, Object> schema() {
                return Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "className", Map.of("type", "string", "description", "Fully qualified class name (e.g. dev.igor.mcp.McpServer)."),
                        "line", Map.of("type", "integer", "description", "Line number in the source file.")
                    ),
                    "required", List.of("className", "line")
                );
            }
            public Map<String, Object> call(Map<String, Object> args) throws Exception {
                String className = String.valueOf(args.get("className"));
                int line = ((Number) args.get("line")).intValue();
                return debugger.setBreakpoint(className, line);
            }
        });

        tools.add(new McpServer.Tool() {
            public String name() { return "jvm_wait_for_event"; }
            public String description() { return "Wait for a JVM event such as hitting a breakpoint."; }
            public Map<String, Object> schema() {
                return Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "timeoutMs", Map.of("type", "integer", "description", "Timeout in milliseconds.")
                    ),
                    "required", List.of("timeoutMs")
                );
            }
            public Map<String, Object> call(Map<String, Object> args) throws Exception {
                int timeoutMs = ((Number) args.get("timeoutMs")).intValue();
                return debugger.waitForEvent(timeoutMs);
            }
        });

        tools.add(new McpServer.Tool() {
            public String name() { return "jvm_step"; }
            public String description() { return "Step over, into, or out on a specific thread."; }
            public Map<String, Object> schema() {
                return Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "threadId", Map.of("type", "integer"),
                        "type", Map.of("type", "string", "description", "Type of step: 'over', 'into', or 'out'.")
                    ),
                    "required", List.of("threadId", "type")
                );
            }
            public Map<String, Object> call(Map<String, Object> args) throws Exception {
                long threadId = ((Number) args.get("threadId")).longValue();
                String type = String.valueOf(args.get("type"));
                return debugger.step(threadId, type);
            }
        });

        McpServer server = new McpServer(tools, System.in, System.out);
        server.run();
    }
}
