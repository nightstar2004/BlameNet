package com.blamenet.model;

/** 测试整体状态。 */
public enum TestStatus {

    COMPLETED("Completed", "已完成"),
    ABORTED("Aborted", "已中止"),
    FAILED("Failed", "异常结束");

    private final String sourceValue;
    private final String chinese;

    TestStatus(String sourceValue, String chinese) {
        this.sourceValue = sourceValue;
        this.chinese = chinese;
    }

    public String sourceValue() {
        return sourceValue;
    }

    public String chinese() {
        return chinese;
    }

    public static TestStatus fromSource(String value) {
        if (value != null) {
            for (TestStatus status : values()) {
                if (status.sourceValue.equalsIgnoreCase(value.trim())) {
                    return status;
                }
            }
        }
        return COMPLETED;
    }
}
