package com.github.wechat.cloudbot.platform.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ToolRegistry：内置工具收集、动态注册/注销/替换、启用/禁用、执行、变更通知。
 */
@ExtendWith(MockitoExtension.class)
class ToolRegistryTest {

    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry(
                List.of(new StubTool("web_search"), new StubTool("fun_joke"), new StubTool("calc")),
                List.of());
        registry.disable("fun_joke");
    }

    @Test
    void testConstructorSkipsNullTool() {
        var tools = new ArrayList<AgentTool>();
        tools.add(tool("good"));
        tools.add(null);
        tools.add(tool("ok"));
        var registry = new ToolRegistry(tools, List.of());
        assertEquals(2, registry.size());
        assertTrue(registry.isRegistered("good"));
        assertTrue(registry.isRegistered("ok"));
    }

    @Test
    void testConstructorWarnsOnDuplicateName() {
        var registry = new ToolRegistry(List.of(tool("dup"), tool("dup")), List.of());
        assertTrue(registry.isRegistered("dup"));
        assertEquals(1, registry.size(), "同名工具应覆盖");
    }

    @Test
    void testConstructorCollectsEnrichers() {
        var enricher = new TestEnricher();
        var registry = new ToolRegistry(List.of(), List.of(enricher));
        assertEquals(1, registry.getEnrichers().size());
    }

    @Test
    void testEmptyRegistry() {
        var registry = new ToolRegistry(List.of(), List.of());
        assertEquals(0, registry.size());
        assertTrue(registry.definitions().isEmpty());
        assertTrue(registry.all().isEmpty());
    }

    @Test
    void testGet() {
        var registry = new ToolRegistry(List.of(tool("find_me")), List.of());
        assertTrue(registry.get("find_me").isPresent());
        assertTrue(registry.get("not_found").isEmpty());
    }

    @Test
    void testDefinitionsOnlyReturnsEnabled() {
        var registry = new ToolRegistry(List.of(tool("a"), tool("b")), List.of());
        assertEquals(2, registry.definitions().size());
        registry.disable("a");
        assertEquals(1, registry.definitions().size());
        assertEquals("b", registry.definitions().get(0).name());
    }

    @Test
    void disableRemovesFromDefinitionsAndRejectsExecute() {
        assertTrue(registry.disable("calc"));
        assertFalse(registry.isEnabled("calc"));
        assertTrue(registry.definitions().stream().noneMatch(d -> "calc".equals(d.name())));

        ToolCall call = new ToolCall("1", "calc", "{}", null);
        ToolResult result = registry.execute(call);
        assertFalse(result.success());
        assertTrue(result.content().contains("已被禁用"));
    }

    @Test
    void enableRestoresToolForLlm() {
        registry.disable("web_search");
        assertFalse(registry.isEnabled("web_search"));
        assertTrue(registry.enable("web_search"));
        assertTrue(registry.isEnabled("web_search"));
        assertTrue(registry.definitions().stream().anyMatch(d -> "web_search".equals(d.name())));
    }

    @Test
    void registerAndUnregister() {
        registry.register("custom_tool", new StubTool("custom_tool"));
        assertTrue(registry.isEnabled("custom_tool"));
        assertTrue(registry.unregister("custom_tool").isPresent());
        assertFalse(registry.isRegistered("custom_tool"));
        ToolResult result = registry.execute(new ToolCall("1", "custom_tool", "{}", null));
        assertFalse(result.success());
    }

    @Test
    void testRegisterThrowsOnBlankName() {
        var registry = new ToolRegistry(List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> registry.register("", tool("x")));
        assertThrows(IllegalArgumentException.class, () -> registry.register(null, tool("x")));
    }

    @Test
    void testUnregister() {
        var registry = new ToolRegistry(List.of(tool("gone")), List.of());
        var removed = registry.unregister("gone");
        assertTrue(removed.isPresent());
        assertFalse(registry.isRegistered("gone"));
    }

    @Test
    void testUnregisterNonExistent() {
        var registry = new ToolRegistry(List.of(), List.of());
        assertTrue(registry.unregister("nothing").isEmpty());
    }

    @Test
    void testReplace() {
        var registry = new ToolRegistry(List.of(tool("old")), List.of());
        var old = registry.replace("old", tool("new"));
        assertTrue(old.isPresent());
        assertEquals("old", old.get().definition().name());
        assertEquals("test tool: new", registry.get("old").get().definition().description());
    }

    @Test
    void testReplaceNonExistentThrows() {
        var registry = new ToolRegistry(List.of(), List.of());
        assertThrows(IllegalArgumentException.class,
                () -> registry.replace("not_there", tool("x")));
    }

    @Test
    void testDisable() {
        var registry = new ToolRegistry(List.of(tool("target")), List.of());
        assertTrue(registry.disable("target"));
        assertFalse(registry.disable("target"), "重复禁用应返回 false");
        assertTrue(registry.disabledNames().contains("target"));
    }

    @Test
    void testDisableNonExistent() {
        var registry = new ToolRegistry(List.of(), List.of());
        assertFalse(registry.disable("phanton"));
    }

    @Test
    void testEnable() {
        var registry = new ToolRegistry(List.of(tool("silent")), List.of());
        registry.disable("silent");
        assertTrue(registry.enable("silent"));
        assertFalse(registry.enable("silent"), "重复启用应返回 false");
        assertFalse(registry.disabledNames().contains("silent"));
    }

    @Test
    void testEnableNonExistent() {
        var registry = new ToolRegistry(List.of(), List.of());
        assertFalse(registry.enable("phanton"));
    }

    @Test
    void testDisabledNames() {
        var registry = new ToolRegistry(List.of(tool("a"), tool("b"), tool("c")), List.of());
        registry.disable("a");
        registry.disable("c");
        var disabled = registry.disabledNames();
        assertEquals(2, disabled.size());
        assertTrue(disabled.contains("a"));
        assertTrue(disabled.contains("c"));
    }

    @Test
    void testExecute() {
        var registry = new ToolRegistry(List.of(tool("hello")), List.of());
        var result = registry.execute(new ToolCall("1", "hello", "{}"));
        assertTrue(result.success());
        assertEquals("executed hello", result.content());
    }

    @Test
    void enabledNamesExcludeDisabled() {
        Set<String> all = registry.enabledNames();
        assertFalse(all.contains("fun_joke"));
        assertTrue(all.contains("web_search"));
    }

    @Test
    void testExecuteNullCall() {
        var registry = new ToolRegistry(List.of(), List.of());
        var result = registry.execute(null);
        assertFalse(result.success());
    }

    @Test
    void testChangeListenerFiredOnRegister() {
        var registry = new ToolRegistry(List.of(), List.of());
        var fired = new AtomicBoolean(false);
        registry.addChangeListener(() -> fired.set(true));

        registry.register("x", tool("x"));
        assertTrue(fired.get());
    }

    @Test
    void testChangeListenerFiredOnUnregister() {
        var registry = new ToolRegistry(List.of(tool("x")), List.of());
        var fired = new AtomicBoolean(false);
        registry.addChangeListener(() -> fired.set(true));

        registry.unregister("x");
        assertTrue(fired.get());
    }

    @Test
    void testChangeListenerFiredOnDisable() {
        var registry = new ToolRegistry(List.of(tool("y")), List.of());
        var fired = new AtomicBoolean(false);
        registry.addChangeListener(() -> fired.set(true));

        registry.disable("y");
        assertTrue(fired.get());
    }

    @Test
    void testChangeListenerFiredOnEnable() {
        var registry = new ToolRegistry(List.of(tool("z")), List.of());
        registry.disable("z");
        var fired = new AtomicBoolean(false);
        registry.addChangeListener(() -> fired.set(true));

        registry.enable("z");
        assertTrue(fired.get());
    }

    @Test
    void testRemoveChangeListener() {
        var registry = new ToolRegistry(List.of(), List.of());
        var fired = new AtomicBoolean(false);
        Runnable listener = () -> fired.set(true);
        registry.addChangeListener(listener);
        registry.removeChangeListener(listener);

        registry.register("x", tool("x"));
        assertFalse(fired.get(), "移除后不应再触发");
    }

    @Test
    void testAddEnricher() {
        var registry = new ToolRegistry(List.of(), List.of());
        var e = new TestEnricher();
        registry.addEnricher(e);
        assertEquals(1, registry.getEnrichers().size());
        assertSame(e, registry.getEnrichers().get(0));
    }

    @Test
    void testRemoveEnricher() {
        var registry = new ToolRegistry(List.of(), List.of());
        var e = new TestEnricher();
        registry.addEnricher(e);
        assertTrue(registry.removeEnricher(e));
        assertFalse(registry.removeEnricher(e), "再次移除应返回 false");
    }

    @Test
    void testAllReturnsAll() {
        var registry = new ToolRegistry(List.of(tool("a"), tool("b")), List.of());
        assertEquals(2, registry.all().size());
    }

    private static AgentTool tool(String name) {
        return new StubTool(name);
    }

    private static class StubTool implements AgentTool {
        private final String name;

        private StubTool(String name) {
            this.name = name;
        }

        @Override
        public ToolDefinition definition() {
            return new ToolDefinition(name, "test tool: " + name, "{}");
        }

        @Override
        public ToolResult execute(ToolCall call) {
            return ToolResult.ok(call.id(), name, "executed " + name);
        }
    }

    private static class TestEnricher implements ToolContextEnricher {
        @Override
        public boolean supports(String toolName) {
            return true;
        }

        @Override
        public String enrich(String argumentsJson, String userId, String userContent) {
            return argumentsJson;
        }
    }
}
