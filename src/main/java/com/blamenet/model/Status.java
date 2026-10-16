package com.blamenet.model;

/** 模块状态：源值 + 中文展示值。JSON 中始终保存源值（Pass/Warn/Fail/Manual/Info）。 */
public enum Status {

    PASS("Pass", "通过"),
    WARN("Warn", "警告"),
    FAIL("Fail", "失败"),
    MANUAL("Manual", "手动"),
    INFO("Info", "信息");

    private final String sourceValue;
    private final String chinese;

    Status(String sourceValue, String chinese) {
        this.sourceValue = sourceValue;
        this.chinese = chinese;
    }

    /** JSON 中的源值。 */
    public String sourceValue() {
        return sourceValue;
    }

    /** 展示层中文值。 */
    public String chinese() {
        return chinese;
    }

    public static Status fromSource(String value) {
        if (value != null) {
            for (Status status : values()) {
                if (status.sourceValue.equalsIgnoreCase(value.trim())) {
                    return status;
                }
            }
        }
        return INFO;
    }

    /** 展示层中文 → 源值，用于用户回填。 */
    public static Status fromChinese(String value) {
        if (value != null) {
            for (Status status : values()) {
                if (status.chinese.equals(value.trim())) {
                    return status;
                }
            }
        }
        return fromSource(value);
    }

    /** 是否为需要关注的状态（警告/失败）。 */
    public boolean isProblem() {
        return this == WARN || this == FAIL;
    }
}
