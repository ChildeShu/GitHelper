package com.childeshu.githelper;

import cn.hutool.core.util.StrUtil;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import org.apache.commons.collections4.CollectionUtils;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/**
 * 入口 Action：在 Tools 菜单、主工具栏、导航栏提供 "Git 助手"。
 *
 * @author childe shu
 */
public class GitHelperAction extends AnAction {

    /** 没有项目上下文且未保存根目录时的兜底路径 */
    private static final String DEFAULT_ROOT = "D:/coding/idea";

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        openDialog(e.getProject());
    }

    /**
     * 打开 Git 助手弹窗（供 Action 与左侧工具窗口复用）
     */
    public static void openDialog(Project project) {
        // 全局 ESC / 点外部监听由 GitHelperDialog.installGlobalHandlers() 统一注册：
        // 独立弹窗与工具窗口嵌入面板共用一条路径，避免只修了其中一个入口。
        createDialog(project).show();
    }

    /**
     * 构建已配置回调的 Git 助手对话框
     */
    public static GitHelperDialog createDialog(Project project) {
        GitHelperDialog dialog = new GitHelperDialog(project, resolveDefaultRoot(project));
        return getGitHelperDialog(project, dialog);
    }

    /**
     * 根目录优先级：用户保存过的目录 → 当前项目目录 → 兜底目录。
     *
     * @param project 当前项目，可为空
     * @return 工作区根目录
     */
    private static String resolveDefaultRoot(Project project) {
        String saved = GitHelperSettings.getWorkspaceRoot();
        if (StrUtil.isNotBlank(saved)) {
            return saved;
        }
        return Objects.nonNull(project) && Objects.nonNull(project.getBasePath())
                ? project.getBasePath()
                : DEFAULT_ROOT;
    }

    /**
     * 按当前配置创建切换器（并行进程数为 1 表示串行）
     */
    private static GitHelperSwitcher newSwitcher() {
        return new GitHelperSwitcher(GitHelperSettings.effectiveThreads());
    }

    /**
     * 创建嵌入左侧工具窗口的 Git 助手面板（面板常驻，不随操作关闭）
     */
    public static GitHelperDialog createEmbeddedDialog(Project project) {
        GitHelperDialog dialog = new GitHelperDialog(project, resolveDefaultRoot(project), true);
        return getGitHelperDialog(project, dialog);
    }

    @NotNull
    private static GitHelperDialog getGitHelperDialog(Project project, GitHelperDialog dialog) {
        dialog.setOnSwitch((repos, branch) ->
                runTask(project, dialog, "切换到 " + branch, repos, indicator ->
                        newSwitcher().switchBranches(repos, branch, indicator)));
        dialog.setOnCreate((repos, source, target) ->
                runTask(project, dialog, "新建分支 " + target, repos, indicator ->
                        newSwitcher().createBranches(repos, source, target, indicator)));
        dialog.setOnSync(repos ->
                runTask(project, dialog, "同步远程", repos, indicator ->
                        newSwitcher().syncFromRemote(repos, indicator)));
        dialog.setOnPush(repos ->
                runTask(project, dialog, "推送远程", repos, indicator ->
                        newSwitcher().pushToRemote(repos, indicator)));
        // 两个入口（Tools 菜单弹窗 / 左侧工具窗口面板）都经过这里，监听只装一次
        dialog.installGlobalHandlers();
        return dialog;
    }

    private static void runTask(Project project, GitHelperDialog dialog, String title,
                                List<java.nio.file.Path> repos, Job job) {
        ProgressManager.getInstance().run(new Task.Modal(project, title, true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                // 提示当前执行模式，便于确认是否启用了多进程（实际并行数不超过仓库数）
                indicator.setText(GitHelperSettings.describe(repos.size()));
                List<String> logs;
                try {
                    logs = job.execute(indicator);
                } catch (Exception ex) {
                    String detail = StrUtil.isBlank(ex.getMessage())
                            ? ex.toString() : ex.getMessage();
                    logs = List.of("操作失败: " + detail);
                }
                List<String> result = logs;
                // 统一回 EDT 刷新界面并提示结果，避免用户误以为按钮无效
                ApplicationManager.getApplication().invokeLater(() -> {
                    dialog.invalidateBranchCache(repos);
                    dialog.refreshBranches(repos);
                    if (CollectionUtils.isNotEmpty(result)) {
                        GitHelperAction.notify(project, String.join("\n", result));
                    }
                });
            }
        });
    }

    private static void notify(Project project, String message) {
        NotificationGroupManager.getInstance()
                .getNotificationGroup("GitHelper")
                .createNotification("Git 助手", message, NotificationType.INFORMATION)
                .notify(project);
    }

    @FunctionalInterface
    private interface Job {
        List<String> execute(ProgressIndicator indicator);
    }
}
