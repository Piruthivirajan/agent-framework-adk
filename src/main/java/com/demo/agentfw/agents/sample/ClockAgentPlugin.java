package com.demo.agentfw.agents.sample;

import com.demo.agentfw.core.AgentContext;
import com.demo.agentfw.core.AgentPlugin;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.tools.Annotations;
import com.google.adk.tools.FunctionTool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A sample third agent, here to show that the framework is pluggable: this one class is the whole
 * change. It is off unless {@code agentfw.sample-agent.enabled=true}; nothing else is edited, the
 * coordinator learns about it from {@link #name()} and {@link #description()}.
 *
 * <p>It answers "what time or date is it" from the server clock. Its answer is shown to the user as
 * that agent's text, labelled as not verified against the documents.
 */
@Component
@ConditionalOnProperty(prefix = "agentfw.sample-agent", name = "enabled", havingValue = "true")
public class ClockAgentPlugin implements AgentPlugin {

    public static final String NAME = "clock_agent";
    static final String METHOD = "currentTime";

    private static final String INSTRUCTION = """
            You tell the current date and time. Always call the currentTime tool, with the time zone the user \
            asked for (an IANA name such as Asia/Kolkata or Europe/London), or UTC when none was named. Answer \
            in one sentence using only what the tool returned. Never guess the time.""";

    private final Clock clock;

    public ClockAgentPlugin() {
        this(Clock.systemUTC());
    }

    ClockAgentPlugin(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Tells the current date and time in a time zone. Use it only when the user asks what time or "
                + "date it is now, never for questions about documents, files or other data.";
    }

    @Override
    public BaseAgent build(AgentContext context) {
        return LlmAgent.builder()
                .name(NAME)
                .description(description())
                .model(context.model())
                .instruction(INSTRUCTION)
                .generateContentConfig(context.generateContentConfig())
                .tools(FunctionTool.create(this, METHOD))
                .build();
    }

    @Annotations.Schema(name = METHOD, description = "Returns the current date and time in the given time zone.")
    public Map<String, Object> currentTime(
            @Annotations.Schema(name = "zone", description = "IANA time zone name such as Asia/Kolkata, "
                    + "or UTC") String zone) {
        ZoneId id;
        try {
            id = zone == null || zone.isBlank() ? ZoneId.of("UTC") : ZoneId.of(zone.strip());
        } catch (DateTimeException e) {
            id = ZoneId.of("UTC");
        }
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(id));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("zone", id.getId());
        out.put("dateTime", now.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm:ss")));
        return out;
    }
}
