package com.apop.investigation.agent;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

@Component
public class ModelScript implements RecommendationModel {

    private final DeterministicRecommendationModel deterministic;
    private final AtomicReference<RecommendationModel> override = new AtomicReference<>();

    public ModelScript(DeterministicRecommendationModel deterministic) {
        this.deterministic = deterministic;
    }

    public void use(RecommendationModel model) {
        override.set(model);
    }

    public void reset() {
        override.set(null);
    }

    @Override
    public ModelTurn next(ModelRequest request) {
        RecommendationModel model = override.get();
        return (model == null ? deterministic : model).next(request);
    }
}
