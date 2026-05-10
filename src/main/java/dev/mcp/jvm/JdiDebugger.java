package dev.mcp.jvm;

import com.sun.jdi.*;
import com.sun.jdi.connect.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;

import java.util.*;
import java.util.LinkedHashMap;

public class JdiDebugger {
    private VirtualMachine vm;

    public Map<String, Object> attach(String host, int port) throws Exception {
        if (vm != null) {
            try { vm.version(); } catch (Exception e) {
                // Stale connection — clean up and re-attach
                try { vm.dispose(); } catch (Exception ignored) {}
                vm = null;
            }
        }
        if (vm != null) return Map.of("status", "already_attached");
        VirtualMachineManager vmm = Bootstrap.virtualMachineManager();
        AttachingConnector connector = null;
        for (AttachingConnector c : vmm.attachingConnectors()) {
            if ("com.sun.jdi.SocketAttach".equals(c.name())) {
                connector = c;
                break;
            }
        }
        if (connector == null) {
            throw new Exception("SocketAttach connector not found");
        }

        Map<String, Connector.Argument> args = connector.defaultArguments();
        args.get("hostname").setValue(host);
        args.get("port").setValue(String.valueOf(port));

        vm = connector.attach(args);
        return Map.of("status", "attached", "vm_name", vm.name());
    }

    public Map<String, Object> disconnect() {
        if (vm != null) {
            try { vm.dispose(); } catch (Exception ignored) {}
            vm = null;
            return Map.of("status", "disconnected");
        }
        return Map.of("status", "not_attached");
    }

    public Map<String, Object> resume() {
        if (vm != null) {
            vm.resume();
            return Map.of("status", "resumed");
        }
        return Map.of("status", "not_attached");
    }

    public Map<String, Object> suspend() {
        if (vm != null) {
            vm.suspend();
            return Map.of("status", "suspended");
        }
        return Map.of("status", "not_attached");
    }

    public Map<String, Object> getThreads() {
        if (vm == null) return Map.of("status", "not_attached");
        List<Map<String, Object>> list = new ArrayList<>();
        for (ThreadReference t : vm.allThreads()) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", t.uniqueID());
            map.put("name", t.name());
            map.put("status", t.status());
            map.put("isSuspended", t.isSuspended());
            list.add(map);
        }
        return Map.of("threads", list);
    }

    public Map<String, Object> getStack(long threadId) throws Exception {
        if (vm == null) return Map.of("status", "not_attached");
        ThreadReference thread = null;
        for (ThreadReference t : vm.allThreads()) {
            if (t.uniqueID() == threadId) {
                thread = t;
                break;
            }
        }
        if (thread == null) return Map.of("error", "Thread not found");
        if (!thread.isSuspended()) return Map.of("error", "Thread is not suspended");

        List<Map<String, Object>> list = new ArrayList<>();
        for (StackFrame f : thread.frames()) {
            Map<String, Object> map = new HashMap<>();
            Location loc = f.location();
            map.put("class", loc.declaringType().name());
            map.put("method", loc.method().name());
            map.put("line", loc.lineNumber());

            Map<String, String> vars = new HashMap<>();
            try {
                for (LocalVariable v : f.visibleVariables()) {
                    Value val = f.getValue(v);
                    if (val == null) vars.put(v.name(), "null");
                    else vars.put(v.name(), val.toString());
                }
            } catch (AbsentInformationException e) {
                vars.put("error", "Local variable information not available");
            }
            map.put("locals", vars);
            list.add(map);
        }
        return Map.of("frames", list);
    }

    public Map<String, Object> setBreakpoint(String className, int line) throws Exception {
        if (vm == null) return Map.of("error", "Not attached");
        List<ReferenceType> classes = vm.classesByName(className);
        if (classes.isEmpty()) {
            // Wait for class prepare event
            ClassPrepareRequest cpr = vm.eventRequestManager().createClassPrepareRequest();
            cpr.addClassFilter(className);
            cpr.enable();
            return Map.of("status", "deferred_class_not_loaded_yet");
        }

        ReferenceType refType = classes.get(0);
        List<Location> locations = refType.locationsOfLine(line);
        if (locations.isEmpty()) {
            return Map.of("error", "No location found at line " + line);
        }

        BreakpointRequest bp = vm.eventRequestManager().createBreakpointRequest(locations.get(0));
        bp.enable();
        return Map.of("status", "breakpoint_set");
    }

    public Map<String, Object> waitForEvent(int timeoutMs) throws Exception {
        if (vm == null) return Map.of("error", "Not attached");
        EventQueue queue = vm.eventQueue();
        EventSet eventSet = queue.remove(timeoutMs);
        if (eventSet == null) {
            return Map.of("event", "timeout");
        }

        Map<String, Object> result = new HashMap<>();
        for (Event event : eventSet) {
            if (event instanceof BreakpointEvent) {
                BreakpointEvent bp = (BreakpointEvent) event;
                result.put("event", "breakpoint");
                result.put("thread", bp.thread().uniqueID());
                result.put("class", bp.location().declaringType().name());
                result.put("line", bp.location().lineNumber());
                break;
            } else if (event instanceof ClassPrepareEvent) {
                result.put("event", "class_prepare");
                result.put("class", ((ClassPrepareEvent)event).referenceType().name());
            } else if (event instanceof VMDeathEvent || event instanceof VMDisconnectEvent) {
                result.put("event", "disconnected");
                vm = null;
                break;
            } else if (event instanceof StepEvent) {
                StepEvent sp = (StepEvent) event;
                result.put("event", "step");
                result.put("thread", sp.thread().uniqueID());
                result.put("class", sp.location().declaringType().name());
                result.put("line", sp.location().lineNumber());
                break;
            }
        }
        if (!result.containsKey("event")) {
            result.put("event", eventSet.iterator().next().getClass().getSimpleName());
        }

        // Wait, eventSet requires resuming, unless we suspend all on breakpoint.
        // We assume suspend_all policy for breakpoints by default.
        return result;
    }

    public Map<String, Object> step(long threadId, String type) throws Exception {
        if (vm == null) return Map.of("status", "not_attached");
        ThreadReference thread = null;
        for (ThreadReference t : vm.allThreads()) {
            if (t.uniqueID() == threadId) {
                thread = t;
                break;
            }
        }
        if (thread == null) return Map.of("error", "Thread not found");
        if (!thread.isSuspended()) return Map.of("error", "Thread is not suspended");

        // clear existing step requests for thread
        List<StepRequest> currentReqs = new ArrayList<>();
        for (StepRequest req : vm.eventRequestManager().stepRequests()) {
            if (req.thread().equals(thread)) {
                currentReqs.add(req);
            }
        }
        vm.eventRequestManager().deleteEventRequests(currentReqs);

        int depth = StepRequest.STEP_OVER;
        if ("into".equalsIgnoreCase(type)) depth = StepRequest.STEP_INTO;
        else if ("out".equalsIgnoreCase(type)) depth = StepRequest.STEP_OUT;

        StepRequest req = vm.eventRequestManager().createStepRequest(thread, StepRequest.STEP_LINE, depth);
        req.addCountFilter(1);
        req.enable();
        vm.resume();
        return Map.of("status", "stepping");
    }

    public Map<String, Object> inspectVariable(long threadId, int frameIndex, String variableName, int maxDepth) throws Exception {
        if (vm == null) return Map.of("error", "Not attached");
        ThreadReference thread = null;
        for (ThreadReference t : vm.allThreads()) {
            if (t.uniqueID() == threadId) {
                thread = t;
                break;
            }
        }
        if (thread == null) return Map.of("error", "Thread not found");
        if (!thread.isSuspended()) return Map.of("error", "Thread is not suspended");

        List<StackFrame> frames = thread.frames();
        if (frameIndex < 0 || frameIndex >= frames.size()) {
            return Map.of("error", "Frame index out of range (0-" + (frames.size() - 1) + ")");
        }

        StackFrame frame = frames.get(frameIndex);
        LocalVariable var;
        try {
            var = frame.visibleVariableByName(variableName);
        } catch (AbsentInformationException e) {
            return Map.of("error", "Local variable information not available for this frame");
        }
        if (var == null) {
            return Map.of("error", "Variable '" + variableName + "' not found in frame");
        }

        Value val = frame.getValue(var);
        Object expanded = expandValue(val, maxDepth, 0);
        Map<String, Object> result = new HashMap<>();
        result.put("variable", variableName);
        result.put("type", var.typeName());
        result.put("value", expanded);
        return result;
    }

    private Object expandValue(Value val, int maxDepth, int currentDepth) {
        if (val == null) return null;

        if (val instanceof StringReference) {
            return ((StringReference) val).value();
        }
        if (val instanceof BooleanValue) {
            return ((BooleanValue) val).value();
        }
        if (val instanceof ByteValue) {
            return ((ByteValue) val).value();
        }
        if (val instanceof CharValue) {
            return String.valueOf(((CharValue) val).value());
        }
        if (val instanceof ShortValue) {
            return ((ShortValue) val).value();
        }
        if (val instanceof IntegerValue) {
            return ((IntegerValue) val).value();
        }
        if (val instanceof LongValue) {
            return ((LongValue) val).value();
        }
        if (val instanceof FloatValue) {
            return ((FloatValue) val).value();
        }
        if (val instanceof DoubleValue) {
            return ((DoubleValue) val).value();
        }

        if (val instanceof ArrayReference) {
            ArrayReference arr = (ArrayReference) val;
            List<Object> items = new ArrayList<>();
            int len = Math.min(arr.length(), 100); // cap at 100 elements
            for (int i = 0; i < len; i++) {
                if (currentDepth < maxDepth) {
                    items.add(expandValue(arr.getValue(i), maxDepth, currentDepth + 1));
                } else {
                    Value elem = arr.getValue(i);
                    items.add(elem == null ? null : elem.toString());
                }
            }
            if (arr.length() > 100) items.add("... (" + arr.length() + " total elements)");
            return items;
        }

        if (val instanceof ObjectReference) {
            ObjectReference obj = (ObjectReference) val;

            // For common wrapper types and enums, use toString via invokeMethod
            String typeName = obj.referenceType().name();
            if (typeName.startsWith("java.lang.") || typeName.equals("java.math.BigDecimal") || typeName.equals("java.math.BigInteger")) {
                try {
                    return invokeToString(obj);
                } catch (Exception e) {
                    return obj.toString();
                }
            }

            // For enums, get the name
            if (obj.referenceType() instanceof com.sun.jdi.ClassType) {
                com.sun.jdi.ClassType ct = (com.sun.jdi.ClassType) obj.referenceType();
                if (ct.superclass() != null && ct.superclass().name().equals("java.lang.Enum")) {
                    try {
                        Field nameField = ct.fieldByName("name");
                        if (nameField != null) {
                            Value nameVal = obj.getValue(nameField);
                            if (nameVal instanceof StringReference) {
                                return ((StringReference) nameVal).value();
                            }
                        }
                    } catch (Exception e) {
                        // fall through
                    }
                    return obj.toString();
                }
            }

            if (currentDepth >= maxDepth) {
                return obj.referenceType().name() + " (id=" + obj.uniqueID() + ")";
            }

            // Expand object fields
            Map<String, Object> fieldMap = new LinkedHashMap<>();
            fieldMap.put("__type__", obj.referenceType().name());
            ReferenceType refType = obj.referenceType();
            List<Field> fields = refType.allFields();
            for (Field f : fields) {
                if (f.isStatic()) continue; // skip static fields
                try {
                    Value fieldVal = obj.getValue(f);
                    fieldMap.put(f.name(), expandValue(fieldVal, maxDepth, currentDepth + 1));
                } catch (Exception e) {
                    fieldMap.put(f.name(), "<error: " + e.getMessage() + ">");
                }
            }
            return fieldMap;
        }

        return val.toString();
    }

    private String invokeToString(ObjectReference obj) throws Exception {
        List<Method> methods = obj.referenceType().methodsByName("toString", "()Ljava/lang/String;");
        if (methods.isEmpty()) return obj.toString();
        // Find a thread we can use for invocation
        ThreadReference invThread = null;
        for (ThreadReference t : vm.allThreads()) {
            if (t.isSuspended() && t.frameCount() > 0) {
                invThread = t;
                break;
            }
        }
        if (invThread == null) return obj.toString();
        Value result = obj.invokeMethod(invThread, methods.get(0), Collections.emptyList(), ObjectReference.INVOKE_SINGLE_THREADED);
        if (result instanceof StringReference) {
            return ((StringReference) result).value();
        }
        return obj.toString();
    }

    public boolean isAttached() {
        return vm != null;
    }
}
