package com.blamenet.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 进度跟踪器。
 * <p>
 * 设计目标（对应设计要求第 2 条）：
 * <ul>
 *     <li>整个流程的计划在前置阶段一次性确定（31 个模块 + 报告导出），因此进度条总长度稳定；</li>
 *     <li>每个小步骤完成都调用一次 {@link #step(String)}，进度条必定向前推进，绝不回退；</li>
 *     <li>若某个模块的实际步骤数超出预估，总长度会自动增长，百分比仍然单调不减；</li>
 *     <li>事件中携带“当前正在进行的测试内容”，供界面显示在进度条上方。</li>
 * </ul>
 */
public final class ProgressTracker {

    /** 进度监听器。 */
    public interface Listener {
        void onProgress(ProgressEvent event);
    }

    /** 计划项：模块或报告导出阶段。 */
    public record PlanItem(String id, String name, int expectedSteps, boolean module) {

        public static PlanItem module(String id, String name, int expectedSteps) {
            return new PlanItem(id, name, expectedSteps, true);
        }

        public static PlanItem phase(String id, String name, int expectedSteps) {
            return new PlanItem(id, name, expectedSteps, false);
        }
    }

    private final List<PlanItem> plan = new ArrayList<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final long startNanos = System.nanoTime();

    private int planIndex = -1;
    private PlanItem current;
    private int currentStepsDone;
    private int completedSteps;
    private volatile boolean finished;

    /** 设置完整计划。必须在开始进度上报之前调用。 */
    public void plan(List<PlanItem> items) {
        plan.clear();
        plan.addAll(items);
        planIndex = -1;
        current = null;
        currentStepsDone = 0;
        completedSteps = 0;
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    /** 进入计划中的下一个项。 */
    public void beginNext() {
        if (planIndex + 1 < plan.size()) {
            planIndex++;
            current = plan.get(planIndex);
        } else {
            planIndex++;
            current = new PlanItem("--", "附加步骤", 1, false);
        }
        currentStepsDone = 0;
        fire("开始 " + current.name());
    }

    /** 完成一个小步骤，进度条必定推进。 */
    public void step(String detail) {
        completedSteps++;
        currentStepsDone++;
        fire(detail);
    }

    /** 全部流程结束。 */
    public void finish() {
        finished = true;
        fire("全部步骤已完成");
    }

    public int completedSteps() {
        return completedSteps;
    }

    public int totalSteps() {
        return Math.max(completedSteps, completedSteps + remainingExpected());
    }

    public long elapsedMillis() {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    public PlanItem currentItem() {
        return current;
    }

    public String currentModuleId() {
        return current == null ? null : current.id();
    }

    public String currentModuleName() {
        return current == null ? null : current.name();
    }

    private int remainingExpected() {
        int remaining = 0;
        if (current != null) {
            remaining += Math.max(0, current.expectedSteps() - currentStepsDone);
        }
        for (int i = planIndex + 1; i < plan.size(); i++) {
            remaining += Math.max(0, plan.get(i).expectedSteps());
        }
        return remaining;
    }

    private void fire(String detail) {
        int total = totalSteps();
        double percent = total <= 0 ? 0 : Math.min(100.0, completedSteps * 100.0 / total);
        long elapsed = elapsedMillis();
        long remainingMillis = 0L;
        if (completedSteps > 0 && !finished && completedSteps < total) {
            remainingMillis = (long) (elapsed / (double) completedSteps * (total - completedSteps));
        }
        ProgressEvent event = new ProgressEvent(
                completedSteps,
                total,
                percent,
                current == null ? "" : current.name(),
                current == null ? "" : current.id(),
                current == null ? "" : current.name(),
                detail,
                elapsed,
                remainingMillis,
                finished);
        for (Listener listener : listeners) {
            try {
                listener.onProgress(event);
            } catch (RuntimeException ignored) {
                // 单个监听器异常不影响诊断流程
            }
        }
    }
}
