package com.apop.investigation.agent;

import com.apop.investigation.domain.MemoryDesign;
import com.apop.investigation.domain.PolicyGuard;
import com.apop.investigation.domain.PromptCatalog;
import com.apop.investigation.persistence.InvestigationCaseRepository;
import com.apop.investigation.service.SessionWriter;
import com.apop.investigation.tools.ToolGateway;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

@Component
public class InvestigationGraph {

    private final ModelScript model;
    private final PromptCatalog prompts;
    private final ToolGateway tools;
    private final SessionWriter writer;
    private final InvestigationCaseRepository cases;
    private final ObjectMapper mapper;
    private final CompiledGraph<InvestigationState> compiled;

    private static final Map<String, String> ROUTES = routes();

    public InvestigationGraph(
            ModelScript model,
            PromptCatalog prompts,
            ToolGateway tools,
            SessionWriter writer,
            InvestigationCaseRepository cases,
            ObjectMapper mapper) throws GraphStateException {
        this.model = model;
        this.prompts = prompts;
        this.tools = tools;
        this.writer = writer;
        this.cases = cases;
        this.mapper = mapper;
        this.compiled = new StateGraph<>(InvestigationState.SCHEMA, InvestigationState::new)
                .addNode("supervisor", node_async(this::supervisor))
                .addNode("fraud", node_async(state -> specialist(state, "FRAUD", "fraud_tools")))
                .addNode("fraud_tools", node_async(state -> callTool(state, "fraud")))
                .addNode("root_cause", node_async(state -> specialist(state, "ROOT_CAUSE", "root_tools")))
                .addNode("root_tools", node_async(state -> callTool(state, "root_cause")))
                .addNode("settlement", node_async(state -> specialist(state, "SETTLEMENT", "settlement_tools")))
                .addNode("settlement_tools", node_async(state -> callTool(state, "settlement")))
                .addNode("reporting", node_async(state -> specialist(state, "REPORTING", "reporting_tools")))
                .addNode("reporting_tools", node_async(state -> callTool(state, "reporting")))
                .addNode("guard", node_async(this::guard))
                .addNode("report_guard", node_async(this::reportGuard))
                .addEdge(START, "supervisor")
                .addConditionalEdges("supervisor", edge_async(this::next), ROUTES)
                .addConditionalEdges("fraud", edge_async(this::next), ROUTES)
                .addEdge("fraud_tools", "fraud")
                .addConditionalEdges("root_cause", edge_async(this::next), ROUTES)
                .addEdge("root_tools", "root_cause")
                .addConditionalEdges("settlement", edge_async(this::next), ROUTES)
                .addEdge("settlement_tools", "settlement")
                .addConditionalEdges("reporting", edge_async(this::next), ROUTES)
                .addEdge("reporting_tools", "reporting")
                .addEdge("guard", END)
                .addEdge("report_guard", END)
                .compile(CompileConfig.builder().checkpointSaver(new MemorySaver()).build());
    }

    public void run(Map<String, Object> input, UUID sessionId) {
        RunnableConfig config = RunnableConfig.builder().threadId(sessionId.toString()).build();
        for (var ignored : compiled.stream(input, config)) {
            // Nodes persist recommendations, tool calls, and audit rows as they run.
        }
    }

    private Map<String, Object> supervisor(InvestigationState state) {
        String requested = state.text("requestedRole");
        String source = state.text("source");
        String route;
        if ("REPORTING".equals(requested)) {
            route = "reporting";
        } else if ("FRAUD_REVIEW".equals(source)) {
            route = "fraud";
        } else if ("ESCALATED_FAILURE".equals(source)) {
            route = "root_cause";
        } else if ("RECON_BREAK".equals(source)) {
            route = "settlement";
        } else {
            route = "end";
        }
        return Map.of("route", route, "messages", "supervisor:" + route);
    }

    private Map<String, Object> specialist(InvestigationState state, String role, String toolNode) {
        int turn = state.turn() + 1;
        long started = Long.parseLong(state.text("startedAt"));
        if (turn > 8 || System.currentTimeMillis() - started > 60_000) {
            writer.stop(id(state, "sessionId"), id(state, "caseId"), "BUDGET");
            return Map.of("turn", Integer.toString(turn), "route", "end", "activeAgent", role);
        }
        RecommendationModel.ModelTurn next = model.next(new RecommendationModel.ModelRequest(
                role,
                prompts.systemPrompt(role),
                MemoryDesign.untrustedChannel(state.text("caseId"), state.text("paymentId"), state.text("source"), state.toolLog(), List.of()),
                state.toolLog(),
                turn));
        Map<String, Object> update = new HashMap<>();
        update.put("turn", Integer.toString(turn));
        update.put("activeAgent", role);
        if (next.toolName() != null) {
            update.put("pendingTool", next.toolName());
            update.put("pendingArgs", write(next.arguments()));
            update.put("route", toolNode);
            return update;
        }
        if (next.report() != null) {
            update.put("report", next.report());
            update.put("route", "report_guard");
            return update;
        }
        PolicyGuard.Draft draft = next.draft();
        update.put("draftDisposition", draft.disposition());
        update.put("draftConfidence", draft.confidence().toPlainString());
        update.put("draftEvidence", String.join(",", draft.evidenceIds()));
        update.put("draftNarrative", draft.narrative());
        update.put("route", "guard");
        return update;
    }

    private Map<String, Object> callTool(InvestigationState state, String back) {
        var caseRow = cases.findById(id(state, "caseId")).orElseThrow();
        var result = tools.execute(caseRow, id(state, "sessionId"), state.text("activeAgent"), state.text("pendingTool"), readArgs(state.text("pendingArgs")));
        return Map.of(
                "route", back,
                "pendingTool", "",
                "toolLog", result.logLine(),
                "evidence", result.evidenceList());
    }

    private Map<String, Object> guard(InvestigationState state) {
        String role = state.text("activeAgent");
        PolicyGuard.Draft draft = new PolicyGuard.Draft(
                state.text("draftDisposition"),
                new BigDecimal(state.text("draftConfidence")),
                split(state.text("draftEvidence")),
                state.text("draftNarrative"));
        PolicyGuard.GuardResult result = PolicyGuard.recommendation(role, draft, state.evidence(), state.text("paymentId"));
        UUID sessionId = id(state, "sessionId");
        UUID caseId = id(state, "caseId");
        if (!result.accepted()) {
            writer.fail(sessionId, caseId, role, result.check(), mapper.valueToTree(state.toolLog()).toString(), state.text("priorStatus"));
        } else {
            writer.completeRecommendation(sessionId, caseId, role, draft, mapper.valueToTree(state.toolLog()).toString());
        }
        return Map.of("route", "end", "sessionStatus", result.accepted() ? "COMPLETED" : "FAILED");
    }

    private Map<String, Object> reportGuard(InvestigationState state) {
        PolicyGuard.GuardResult result = PolicyGuard.report(state.text("report"), state.evidence());
        UUID sessionId = id(state, "sessionId");
        UUID caseId = id(state, "caseId");
        if (!result.accepted()) {
            writer.fail(sessionId, caseId, "REPORTING", result.check(), mapper.valueToTree(state.toolLog()).toString(), state.text("priorStatus"));
        } else {
            writer.completeReport(sessionId, caseId, state.text("report"), mapper.valueToTree(state.toolLog()).toString(), state.text("priorStatus"));
        }
        return Map.of("route", "end");
    }

    private String next(InvestigationState state) {
        return switch (state.text("route")) {
            case "fraud", "fraud_tools", "root_cause", "root_tools", "settlement", "settlement_tools",
                    "reporting", "reporting_tools", "guard", "report_guard" -> state.text("route");
            default -> END;
        };
    }

    private Map<String, String> readArgs(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception exception) {
            return Map.of();
        }
    }

    private String write(Map<String, String> arguments) {
        try {
            return mapper.writeValueAsString(arguments == null ? Map.of() : arguments);
        } catch (Exception exception) {
            return "{}";
        }
    }

    private static Map<String, String> routes() {
        Map<String, String> map = new HashMap<>();
        for (String name : List.of(
                "fraud", "fraud_tools", "root_cause", "root_tools", "settlement", "settlement_tools",
                "reporting", "reporting_tools", "guard", "report_guard")) {
            map.put(name, name);
        }
        map.put(END, END);
        return Map.copyOf(map);
    }

    private static UUID id(InvestigationState state, String key) {
        return UUID.fromString(state.text(key));
    }

    private static List<String> split(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return List.of(value.split(","));
    }

    public static final class InvestigationState extends AgentState {
        public static final Map<String, Channel<?>> SCHEMA = schema();

        public InvestigationState(Map<String, Object> initData) {
            super(initData);
        }

        public String text(String key) {
            return this.<Object>value(key).map(Object::toString).orElse("");
        }

        public int turn() {
            String raw = text("turn");
            return raw.isBlank() ? 0 : Integer.parseInt(raw);
        }

        public List<String> toolLog() {
            return this.<List<String>>value("toolLog").orElse(List.of());
        }

        public java.util.Set<String> evidence() {
            java.util.Set<String> ids = new java.util.LinkedHashSet<>();
            for (String chunk : this.<List<String>>value("evidence").orElse(List.of())) {
                if (chunk == null || chunk.isBlank()) {
                    continue;
                }
                for (String id : chunk.split(",")) {
                    if (!id.isBlank()) {
                        ids.add(id);
                    }
                }
            }
            return ids;
        }

        private static Map<String, Channel<?>> schema() {
            Map<String, Channel<?>> map = new HashMap<>();
            map.put("messages", Channels.appender(ArrayList::new));
            map.put("toolLog", Channels.appender(ArrayList::new));
            map.put("evidence", Channels.appender(ArrayList::new));
            for (String key : List.of(
                    "caseId", "sessionId", "paymentId", "source", "requestedRole", "route", "activeAgent",
                    "turn", "pendingTool", "pendingArgs", "draftDisposition", "draftConfidence",
                    "draftEvidence", "draftNarrative", "report", "sessionStatus", "startedAt", "priorStatus")) {
                map.put(key, Channels.base(() -> ""));
            }
            return Map.copyOf(map);
        }
    }
}
