package com.github.wechat.cloudbot.platform.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 工具注册中心：收集全部 {@link AgentTool}，支持运行时动态注册/注销/替换/启停。
 *
 * <p>管理三类工具：
 * <ul>
 *   <li><b>内置工具</b>——Spring {@code @Component} 在构造函数中自动注入</li>
 *   <li><b>插件工具</b>——通过 {@link #register(String, AgentTool)} 动态加入（如 SPI 加载）</li>
 *   <li><b>禁用工具</b>——已注册但被标记为禁用，不出现在 {@link #definitions()} 中</li>
 * </ul>
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    /** 名称 → 工具实例（线程安全） */
    private final ConcurrentHashMap<String, AgentTool> tools = new ConcurrentHashMap<>();

    /** 被禁用的工具名称集合 */
    private final Set<String> disabledTools = ConcurrentHashMap.newKeySet();

    /** 上下文注入器链（注册顺序执行） */
    private final List<ToolContextEnricher> enrichers = new CopyOnWriteArrayList<>();

    /** 注册/注销/启停变更监听器 */
    private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();

    /** 调用统计（可选注入） */
    private ToolUsageTracker usageTracker;

    public ToolRegistry(List<AgentTool> agentTools,
                         List<ToolContextEnricher> enricherBeans) {
        // 收集内置 Spring @Component 工具
        if (agentTools != null) {
            for (AgentTool tool : agentTools) {
                if (tool == null || tool.definition() == null || tool.definition().name() == null) {
                    continue;
                }
                String name = tool.definition().name();
                AgentTool old = tools.put(name, tool);
                if (old != null) {
                    log.warn("内置工具名称冲突 name={}，已覆盖", name);
                } else {
                    log.info("注册内置工具 name={}", name);
                }
            }
        }
        // 收集内置上下文注入器
        if (enricherBeans != null) {
            for (ToolContextEnricher enricher : enricherBeans) {
                if (enricher != null) {
                    enrichers.add(enricher);
                    log.debug("注册上下文注入器 class={}", enricher.getClass().getSimpleName());
                }
            }
        }
    }

    // ========== 查询 ==========

    /**
     * 按名称查找（包含已禁用的工具）。
     *
     * @param name 工具名称
     * @return 包含工具的 Optional，不存在时返回 empty
     */
    public Optional<AgentTool> get(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /**
     * 获取所有已注册的工具（包含已禁用的）。
     *
     * @return 所有工具的不可变集合
     */
    public Collection<AgentTool> all() {
        return List.copyOf(tools.values());
    }

    /**
     * 收集所有<strong>未禁用</strong>工具的 {@link ToolDefinition}，供 LLM 注册。
     *
     * @return 有效的工具定义列表
     */
    public List<ToolDefinition> definitions() {
        return tools.values().stream()
                .filter(t -> !disabledTools.contains(t.definition().name()))
                .map(AgentTool::definition)
                .toList();
    }

    /**
     * 按名称查找且未禁用。
     *
     * @param name 工具名称
     * @return 包含工具的 Optional（禁用或不存在时返回 empty）
     */
    public Optional<AgentTool> getEnabled(String name) {
        if (disabledTools.contains(name)) {
            return Optional.empty();
        }
        return Optional.ofNullable(tools.get(name));
    }

    /**
     * 当前启用的工具名集合（未禁用）。
     *
     * @return 不可变的启用名称集合
     */
    public Set<String> enabledNames() {
        return tools.keySet().stream()
                .filter(name -> !disabledTools.contains(name))
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * 查询工具是否已注册。
     *
     * @param name 工具名称
     * @return true 表示已注册
     */
    public boolean isRegistered(String name) {
        return tools.containsKey(name);
    }

    /**
     * 查询工具是否为启用状态。
     *
     * @param name 工具名称
     * @return true 表示已注册且未禁用
     */
    public boolean isEnabled(String name) {
        return tools.containsKey(name) && !disabledTools.contains(name);
    }

    /**
     * 返回当前注册的工具总数（包含已禁用的）。
     *
     * @return 工具数量
     */
    public int size() {
        return tools.size();
    }

    // ========== 动态注册/注销/替换 ==========

    /**
     * 注册一个新工具。如果名称已存在则覆盖并 warn。
     *
     * @param name 工具名称
     * @param tool 工具实例
     * @return 被替换的旧工具（新注册返回 empty）
     */
    public synchronized Optional<AgentTool> register(String name, AgentTool tool) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tool name must not be blank");
        }
        AgentTool old = tools.put(name, tool);
        if (old != null) {
            log.warn("工具名称冲突 name={}，已覆盖（旧={} 新={}）",
                    name, old.getClass().getSimpleName(), tool.getClass().getSimpleName());
        } else {
            log.info("注册工具 name={}, class={}", name, tool.getClass().getSimpleName());
        }
        notifyChanged();
        return Optional.ofNullable(old);
    }

    /**
     * 注销一个工具。
     *
     * @param name 工具名称
     * @return 被移除的工具（不存在返回 empty）
     */
    public synchronized Optional<AgentTool> unregister(String name) {
        AgentTool removed = tools.remove(name);
        if (removed != null) {
            disabledTools.remove(name);
            log.info("注销工具 name={}, class={}", name, removed.getClass().getSimpleName());
            notifyChanged();
        }
        return Optional.ofNullable(removed);
    }

    /**
     * 原子替换一个已有工具。等价于 register，但要求旧工具必须存在。
     *
     * @param name 工具名称
     * @param tool 新工具实例
     * @return 被替换的旧工具
     * @throws IllegalArgumentException 工具不存在
     */
    public synchronized Optional<AgentTool> replace(String name, AgentTool tool) {
        if (!tools.containsKey(name)) {
            throw new IllegalArgumentException("工具「" + name + "」不存在，无法替换");
        }
        return register(name, tool);
    }

    // ========== 启用/禁用 ==========

    /**
     * 禁用指定工具（不出现在 definitions 中，execute 返回禁用提示）。
     *
     * @param name 工具名称
     * @return true 表示成功禁用；false 表示工具不存在或已禁用
     */
    public boolean disable(String name) {
        if (!tools.containsKey(name)) {
            return false;
        }
        boolean added = disabledTools.add(name);
        if (added) {
            log.info("禁用工具 name={}", name);
            notifyChanged();
        }
        return added;
    }

    /**
     * 启用指定工具。
     *
     * @param name 工具名称
     * @return true 表示成功启用；false 表示工具不存在或已是启用状态
     */
    public boolean enable(String name) {
        boolean removed = disabledTools.remove(name);
        if (removed) {
            log.info("启用工具 name={}", name);
            notifyChanged();
        }
        return removed;
    }

    /**
     * 返回所有已禁用的工具名称。
     *
     * @return 不可变的禁用名称集合
     */
    public Set<String> disabledNames() {
        return Set.copyOf(disabledTools);
    }

    // ========== 执行 ==========

    /**
     * 执行工具调用。
     *
     * <p>如果工具不存在或已被禁用，返回友好的 {@link ToolResult#fail()}，不会抛异常。
     *
     * @param call 工具调用
     * @return 执行结果
     */
    public ToolResult execute(ToolCall call) {
        if (call == null || call.name() == null) {
            return ToolResult.fail("", "unknown", "工具调用为空");
        }
        AgentTool tool = tools.get(call.name());
        if (tool == null) {
            return ToolResult.fail(call.id(), call.name(),
                    "工具「" + call.name() + "」不存在，请检查工具名称或先注册");
        }
        if (disabledTools.contains(call.name())) {
            return ToolResult.fail(call.id(), call.name(),
                    "工具「" + call.name() + "」已被禁用，请联系管理员开启");
        }
        long start = System.nanoTime();
        ToolResult result;
        try {
            result = tool.execute(call);
        } catch (Exception e) {
            log.error("工具执行异常 name={}: {}", call.name(), e.getMessage(), e);
            result = ToolResult.fail(call.id(), call.name(),
                    "工具「" + call.name() + "」执行异常: " + e.getMessage());
        }
        // 统计组件单独 try-catch：即使 usageTracker 抛异常（如 DB 故障），也不影响已判定的工具结果
        if (usageTracker != null) {
            try {
                usageTracker.recordCall(call.name(), result.success(),
                        (System.nanoTime() - start) / 1_000_000);
            } catch (Exception ex) {
                log.warn("记录工具调用统计失败 name={}: {}", call.name(), ex.getMessage());
            }
        }
        return result;
    }

    // ========== 上下文注入器 ==========

    /**
     * 添加上下文注入器。
     *
     * @param enricher 注入器实例
     */
    public void addEnricher(ToolContextEnricher enricher) {
        if (enricher != null) {
            enrichers.add(enricher);
            log.info("注册上下文注入器 class={}", enricher.getClass().getSimpleName());
        }
    }

    /**
     * 获取所有上下文注入器。
     *
     * @return 注入器列表
     */
    public List<ToolContextEnricher> getEnrichers() {
        return enrichers;
    }

    /**
     * 移除指定注入器。
     *
     * @param enricher 注入器实例
     * @return true 表示移除成功
     */
    public boolean removeEnricher(ToolContextEnricher enricher) {
        return enrichers.remove(enricher);
    }

    // ========== 变更监听 ==========

    /**
     * 添加注册/注销/启停变更监听器。
     *
     * @param listener 监听器回调
     */
    public void addChangeListener(Runnable listener) {
        if (listener != null) {
            changeListeners.add(listener);
        }
    }

    /**
     * 移除变更监听器。
     *
     * @param listener 监听器回调
     */
    public void removeChangeListener(Runnable listener) {
        changeListeners.remove(listener);
    }

    private void notifyChanged() {
        for (Runnable listener : changeListeners) {
            try {
                listener.run();
            } catch (Exception e) {
                log.warn("变更监听器执行异常: {}", e.getMessage());
            }
        }
    }

    // ========== 调用统计 ==========

    /**
     * 注入调用统计组件（setter 注入，避免循环依赖）。
     *
     * @param usageTracker 统计组件实例
     */
    @Autowired
    public void setUsageTracker(ToolUsageTracker usageTracker) {
        this.usageTracker = usageTracker;
    }

    /**
     * 获取调用统计组件实例。
     *
     * @return 统计组件，可能为 null（未注入时）
     */
    public ToolUsageTracker usageTracker() {
        return usageTracker;
    }

    /**
     * 获取上下文注入器列表。
     *
     * @return 注入器列表（不可变视图）
     */
    public List<ToolContextEnricher> enrichers() {
        return List.copyOf(enrichers);
    }
}
