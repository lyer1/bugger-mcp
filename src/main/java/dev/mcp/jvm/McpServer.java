package dev.mcp.jvm;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class McpServer {
    public interface Tool {
        String name();
        String description();
        Map<String,Object> schema();
        Map<String,Object> call(Map<String,Object> args) throws Exception;
    }

    private final List<Tool> tools;
    private final InputStream in;
    private final OutputStream out;

    public McpServer(List<Tool> tools, InputStream in, OutputStream out) {
        this.tools = tools;
        this.in = in;
        this.out = out;
    }

    public void run() {
        loop();
    }

    private void loop() {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                Map<String,Object> req = Json.readObj(line);
                Map<String,Object> resp = handle(req);
                write(resp);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private Map<String,Object> handle(Map<String,Object> req) {
        String idKey = "id";
        Object id = req.get(idKey);
        String method = String.valueOf(req.get("method"));
        Map<String,Object> result;
        try {
            if ("tools/list".equals(method)) {
                List<Map<String,Object>> t = new ArrayList<>();
                for (Tool tool : tools) {
                    Map<String,Object> e = new HashMap<>();
                    e.put("name", tool.name());
                    e.put("description", tool.description());
                    e.put("inputSchema", tool.schema());
                    t.add(e);
                }
                result = Map.of("tools", t);
                return respOk(id, result);
            } else if ("tools/call".equals(method)) {
                Map<String,Object> params = (Map<String,Object>) req.get("params");
                String name = String.valueOf(params.get("name"));
                Map<String,Object> args = (Map<String,Object>) params.getOrDefault("arguments", Collections.emptyMap());
                Optional<Tool> t = tools.stream().filter(x -> x.name().equals(name)).findFirst();
                if (t.isEmpty()) return respErr(id, -32601, "Tool not found");
                Map<String,Object> out = t.get().call(args);
                return respOk(id, Map.of("content", List.of(Map.of("type","text","text", Json.write(out)))));
            } else if ("ping".equals(method)) {
                return respOk(id, Map.of("ok", true));
            } else if ("initialize".equals(method)) {
                return respOk(id, Map.of(
                    "protocolVersion", "2025-06-18",
                    "serverInfo", Map.of("name", "jvm-debugger-mcp", "version", "1.0"),
                    "capabilities", Map.of("tools", Map.of())
                ));
            } else if ("notifications/initialized".equals(method)) {
                return null;
            } else {
                return respErr(id, -32601, "Method not found: " + method);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return respErr(id, -32000, e.getMessage());
        }
    }

    private Map<String,Object> respOk(Object id, Object result) {
        Map<String,Object> m = new HashMap<>();
        m.put("jsonrpc","2.0");
        m.put("id", id);
        m.put("result", result);
        return m;
    }

    private Map<String,Object> respErr(Object id, int code, String msg) {
        Map<String,Object> m = new HashMap<>();
        m.put("jsonrpc","2.0");
        m.put("id", id);
        m.put("error", Map.of("code", code, "message", msg));
        return m;
    }

    private synchronized void write(Map<String,Object> msg) throws Exception {
        if (msg == null) return;
        byte[] body = Json.writeBytes(msg);
        out.write(body);
        out.write('\n');
        out.flush();
    }
}
