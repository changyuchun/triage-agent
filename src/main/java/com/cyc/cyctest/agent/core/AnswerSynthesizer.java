package com.cyc.cyctest.agent.core;

import com.cyc.cyctest.agent.core.AgentModels.AgentRunContext;
import com.cyc.cyctest.agent.core.AgentModels.Evidence;
import com.cyc.cyctest.agent.llm.JsonSupport;
import com.cyc.cyctest.agent.llm.LlmClient;
import com.cyc.cyctest.agent.skill.SkillRegistry;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class AnswerSynthesizer {
    private final LlmClient llmClient;
    private final JsonSupport jsonSupport;
    private final SkillRegistry skillRegistry;

    public AnswerSynthesizer(LlmClient llmClient, JsonSupport jsonSupport, SkillRegistry skillRegistry) {
        this.llmClient = llmClient;
        this.jsonSupport = jsonSupport;
        this.skillRegistry = skillRegistry;
    }

    /**
     * 合成最终答案。
     *
     * @param ctx            当前运行上下文（槽位、路由、证据）
     * @param episodicContext L4 情景记忆召回结果（相似历史问题的处理结论），可为空列表
     */
    public String synthesize(AgentRunContext ctx, List<String> episodicContext) {
        if (!llmClient.available()) return templateAnswer(ctx);
        try {
            return llmClient.complete(buildSystem(ctx), buildUser(ctx, episodicContext));
        } catch (Exception e) {
            return templateAnswer(ctx);
        }
    }

    /** 兼容旧调用（无情景记忆）*/
    public String synthesize(AgentRunContext ctx) {
        return synthesize(ctx, List.of());
    }

    /** 流式合成：token 级推送，供 /chat/stream/v2 使用 */
    public Flux<String> synthesizeStream(AgentRunContext ctx, List<String> episodicContext) {
        if (!llmClient.available()) return Flux.just(templateAnswer(ctx));
        try {
            return llmClient.streamTokens(buildSystem(ctx), buildUser(ctx, episodicContext));
        } catch (Exception e) {
            return Flux.just(templateAnswer(ctx));
        }
    }

    private String buildSystem(AgentRunContext ctx) {
        String domainCode = ctx.route() != null ? ctx.route().domainCode() : "";
        String subDomainCode = ctx.route() != null ? ctx.route().subDomainCode() : "";
        String domainSop = skillRegistry.sopFor(domainCode, subDomainCode);
        String sopSection = domainSop.isBlank() ? "" : "\n\n领域诊断 SOP（按此标准回答，不得偏离）：\n" + domainSop;
        return """
                你是基础平台智能答疑 Agent 的答案合成模块。
                严格基于证据回答，不要编造工具没有查到的事实。
                输出结构（必须包含以下五段，每段标题加粗）：
                **核心结论** | **关键事实**（事实后标注 [ref:evidenceId]）| **排查过程** | **建议** | **限制**
                控制在 600 字以内，不要重复证据原文。%s
                """.formatted(sopSection);
    }

    private String buildUser(AgentRunContext ctx, List<String> episodicContext) {
        String episodicSection = "";
        if (episodicContext != null && !episodicContext.isEmpty()) {
            episodicSection = "\n\n历史相似问题经验（情景记忆，供参考）:\n"
                    + episodicContext.stream()
                          .limit(3)
                          .map(e -> "- " + e)
                          .collect(Collectors.joining("\n"));
        }
        return """
                用户问题:
                %s

                领域路由:
                %s

                槽位:
                %s

                证据:
                %s%s
                """.formatted(ctx.userText(), ctx.route(), ctx.slots(),
                        jsonSupport.write(ctx.evidence().evidence()), episodicSection);
    }

    private String templateAnswer(AgentRunContext ctx) {
        String facts = ctx.evidence().evidence().stream()
                .map(e -> "- " + e.title() + ": " + e.content() + " [ref:" + e.evidenceId() + "]")
                .collect(Collectors.joining("\n"));
        boolean hasToolFailure = ctx.evidence().evidence().stream().anyMatch(e -> "tool_error".equals(e.type()));
        String conclusion = buildConclusion(ctx);
        return """
                核心结论
                %s

                关键事实
                %s

                排查过程
                - 已完成对话信息提取，当前槽位：%s
                - 已路由到：%s/%s，处理模式：%s
                - 已执行计划并收集 %d 条证据，证据质量分：%.2f

                建议
                %s

                限制
                %s
                """.formatted(
                conclusion,
                facts.isBlank() ? "- 暂无可用证据。" : facts,
                ctx.slots(),
                ctx.route().domainName(), ctx.route().subDomainName(), ctx.route().handleMode(),
                ctx.evidence().evidence().size(), ctx.evidence().qualityScore(),
                buildSuggestion(ctx),
                hasToolFailure ? "部分工具调用失败，结论为有限回答。" : "当前回答仅基于本次工具结果和知识库证据。"
        );
    }

    private String buildConclusion(AgentRunContext ctx) {
        for (Evidence evidence : ctx.evidence().evidence()) {
            if ("tool".equals(evidence.type()) && evidence.content().contains("FAILED")) {
                return "该对象当前支付状态为失败，工具结果显示可能与风控拒绝或渠道返回有关。";
            }
            if ("tool".equals(evidence.type()) && evidence.content().contains("SUCCESS")) {
                return "该对象当前支付状态为成功，若用户仍反馈失败，需要继续核对交易履约或前端展示链路。";
            }
        }
        if ("knowledge_only".equals(ctx.route().handleMode())) {
            return "这是一个知识解释类问题，下面基于知识库给出说明。";
        }
        return "已完成初步排查，结论需要结合下方证据理解。";
    }

    private String buildSuggestion(AgentRunContext ctx) {
        if ("payment".equals(ctx.route().domainCode())) {
            return "- 若状态失败，优先核对 riskCode、渠道返回码和支付单状态。\n- 若支付成功但交易未推进，继续查询交易/履约链路。";
        }
        return "- 继续核对订单状态、履约状态和相关错误码。\n- 需要具体排查时补充订单号和环境。";
    }
}
