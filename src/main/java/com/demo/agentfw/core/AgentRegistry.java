package com.demo.agentfw.core;

import com.google.adk.tools.AgentTool;
import com.google.adk.tools.BaseTool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Every {@link AgentPlugin} bean, validated, and the means to turn them into coordinator tools.
 * Agent-as-tool, not sub-agent transfer: the plugin's result comes back to the coordinator's turn,
 * which is what allows other tools to be tried later.
 */
@Component
public class AgentRegistry {

    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final List<AgentPlugin> plugins;

    public AgentRegistry(List<AgentPlugin> plugins) {
        Set<String> seen = new HashSet<>();
        for (AgentPlugin p : plugins) {
            if (p.name() == null || !NAME.matcher(p.name()).matches()) {
                throw new IllegalStateException("Invalid agent plugin name '" + p.name() + "' in "
                        + p.getClass().getName() + ": use letters, digits and underscores");
            }
            if (p.description() == null || p.description().isBlank()) {
                throw new IllegalStateException("Agent plugin '" + p.name() + "' has no description, "
                        + "so the coordinator could not know when to use it");
            }
            if (!seen.add(p.name())) {
                throw new IllegalStateException("Two agent plugins are named '" + p.name() + "'");
            }
        }
        List<AgentPlugin> sorted = new ArrayList<>(plugins);
        sorted.sort(Comparator.comparing(AgentPlugin::name));   // deterministic prompt and tool order
        this.plugins = List.copyOf(sorted);
    }

    public List<AgentPlugin> plugins() {
        return plugins;
    }

    /** One {@link AgentTool} per plugin, each wrapping a freshly built agent. */
    public List<BaseTool> agentTools(AgentContext context) {
        List<BaseTool> tools = new ArrayList<>();
        for (AgentPlugin plugin : plugins) {
            tools.add(AgentTool.create(plugin.build(context), plugin.finalAnswer()));
        }
        return tools;
    }
}
