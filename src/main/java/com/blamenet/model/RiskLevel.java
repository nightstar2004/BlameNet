package com.blamenet.model;

/** 风险等级：源值 + 中文展示值。 */
public enum RiskLevel {

    CRITICAL("Critical", "严重"),
    HIGH("High", "高"),
    MEDIUM("Medium", "中"),
    LOW("Low", "低");

    private final String sourceValue;
    private final String chinese;

    RiskLevel(String sourceValue, String chinese) {
        this.sourceValue = sourceValue;
        this.chinese = chinese;
    }

    public String sourceValue() {
        return sourceValue;
    }

    public String chinese() {
        return chinese;
    }

    public static RiskLevel fromSource(String value) {
        if (value != null) {
            for (RiskLevel level : values()) {
                if (level.sourceValue.equalsIgnoreCase(value.trim())) {
                    return level;
                }
            }
        }
        return MEDIUM;
    }

    /** 风险等级的排序权重，数值越大越严重。 */
    public int weight() {
        return switch (this) {
            case CRITICAL -> 4;
            case HIGH -> 3;
            case MEDIUM -> 2;
            case LOW -> 1;
        };
    }
}
