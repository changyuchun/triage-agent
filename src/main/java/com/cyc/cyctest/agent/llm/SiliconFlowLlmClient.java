package com.cyc.cyctest.agent.llm;

import com.cyc.cyctest.agent.config.AgentProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

import static com.cyc.cyctest.agent.core.AgentModels.hasText;

@Component
@ConditionalOnProperty(name = "agent.llm.provider", havingValue = "siliconflow", matchIfMissing = true)
public class SiliconFlowLlmClient implements LlmClient {
    private final AgentProperties properties;
    private final RestClient restClient;

    public SiliconFlowLlmClient(AgentProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        this.restClient = builder.build();
    }

    @Override
    public boolean available() {
        return properties.siliconflow().enabled() && hasText(properties.siliconflow().apiKey());
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        if (!available()) {
            throw new IllegalStateException("SiliconFlow API is disabled or api key is empty");
        }
        ChatCompletionRequest request = new ChatCompletionRequest(
                properties.siliconflow().model(),
                List.of(new Message("system", systemPrompt), new Message("user", userPrompt)),
                properties.siliconflow().temperature()
        );

        ChatCompletionResponse response = restClient.post()
                .uri(properties.siliconflow().baseUrl())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.siliconflow().apiKey())
                .body(request)
                .retrieve()
                .body(ChatCompletionResponse.class);

        if (response == null || response.choices() == null || response.choices().isEmpty()
                || response.choices().getFirst().message() == null) {
            throw new IllegalStateException("empty llm response");
        }
        return response.choices().getFirst().message().content();
    }
}
