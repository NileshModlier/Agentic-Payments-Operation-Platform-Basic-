package com.apop.fraud.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "fraud_rule_hit")
@IdClass(FraudRuleHit.Key.class)
public class FraudRuleHit {

    @Id
    @Column(name = "assessment_id")
    private UUID assessmentId;

    @Id
    @Column(name = "rule_id", length = 64)
    private String ruleId;

    @Column(name = "points", nullable = false)
    private int points;

    protected FraudRuleHit() {
    }

    public static FraudRuleHit create(UUID assessmentId, String ruleId, int points) {
        FraudRuleHit hit = new FraudRuleHit();
        hit.assessmentId = assessmentId;
        hit.ruleId = ruleId;
        hit.points = points;
        return hit;
    }

    public String getRuleId() {
        return ruleId;
    }

    public int getPoints() {
        return points;
    }

    public static final class Key implements Serializable {
        private UUID assessmentId;
        private String ruleId;

        public Key() {
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return Objects.equals(assessmentId, key.assessmentId) && Objects.equals(ruleId, key.ruleId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(assessmentId, ruleId);
        }
    }
}
