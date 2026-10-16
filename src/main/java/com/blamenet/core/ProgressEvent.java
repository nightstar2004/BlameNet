package com.blamenet.core;

/**
 * 进度事件。
 * <p>
 * 对应设计要求第 2 条：测试界面最下方显示实时进度条，进度条上方显示当前正在进行的测试内容，
 * 每个小步骤完成后进度条都必须推进。事件中的 stepDetail 即“当前正在进行的测试内容”。
 */
public record ProgressEvent(int completedSteps,
                            int totalSteps,
                            double percent,
                            String phaseName,
                            String moduleId,
                            String moduleName,
                            String stepDetail,
                            long elapsedMillis,
                            long remainingMillis,
                            boolean finished) {

    /** 进度条上方显示的当前测试内容。 */
    public String currentContent() {
        StringBuilder sb = new StringBuilder();
        if (moduleId != null && !moduleId.isBlank()) {
            sb.append("模块 ").append(moduleId);
            if (moduleName != null && !moduleName.isBlank()) {
                sb.append(' ').append(moduleName);
            }
        } else if (phaseName != null && !phaseName.isBlank()) {
            sb.append(phaseName);
        }
        if (stepDetail != null && !stepDetail.isBlank()) {
            sb.append(" — ").append(stepDetail);
        }
        return sb.toString();
    }
}
