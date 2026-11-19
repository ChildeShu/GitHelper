package com.childeshu.githelper;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

/**
 * New UI 左侧工具条上的 "Git 助手" 工具窗口。
 * 面板直接嵌入工具窗口显示（不做"隐藏面板再弹窗"的拦截，
 * 2024.1 平台无激活前可否决 API，那种写法会先闪一下空面板）。
 * Tools 菜单入口仍是独立弹窗。
 *
 * @author childe shu
 */
public class GitHelperToolWindowFactory implements ToolWindowFactory {

    @Override
    public boolean shouldBeAvailable(@NotNull Project project) {
        return true;
    }

    /**
     * 把 Git 助手面板嵌入工具窗口（只含主面板，无 OK/Cancel），关闭内容时释放资源
     */
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        try {
            GitHelperDialog dialog = GitHelperAction.createEmbeddedDialog(project);
            Content content = ContentFactory.getInstance()
                    .createContent(dialog.getRootPanel(), "", false);
            content.setDisposer(dialog::release);
            toolWindow.getContentManager().addContent(content);
        } catch (Throwable t) {
            // 面板创建失败时把异常直接弹出来，避免只剩 "Nothing to show" 无从排查
            NotificationGroupManager.getInstance()
                    .getNotificationGroup("GitHelper")
                    .createNotification("Git 助手面板加载失败",
                            String.valueOf(t), NotificationType.ERROR)
                    .notify(project);
        }
    }
}
