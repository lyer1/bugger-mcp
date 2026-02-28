package dev.mcp.jvm;

import com.sun.jdi.*;
import com.sun.jdi.connect.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;

import java.util.*;

public class JdiDebugger {
    private VirtualMachine vm;

    public Map<String, Object> attach(String host, int port) throws Exception {
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
            vm.dispose();
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

    public boolean isAttached() {
        return vm != null;
    }
}
