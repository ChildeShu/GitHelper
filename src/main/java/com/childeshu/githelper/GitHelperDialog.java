package com.childeshu.githelper;

import com.intellij.icons.AllIcons;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.ui.popup.ComponentPopupBuilder;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.components.fields.ExtendableTextComponent;
import com.intellij.ui.components.fields.ExtendableTextField;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import cn.hutool.core.util.StrUtil;
import org.apache.commons.collections4.CollectionUtils;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.AWTEventListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

/**
 * 主对话框：列出根目录下所有 Git 仓库，支持勾选/全选/取消。
 * 当前分支列按可视区域懒加载，滚动到哪里加载到哪里，避免一次性全部加载卡顿。
 * 点击"当前分支"列弹出分支选择框，分支列表在弹窗内分批动态填充，支持输入筛选。
 *
 * @author childe shu
 */
public class GitHelperDialog extends DialogWrapper {

    /**
     * 分支列加载中的占位文案
     */
    private static final String LOADING_TEXT = "加载中...";

    /** Git 元数据目录名 */
    private static final String GIT_DIR = ".git";

    /** 递归查找 Git 仓库时的最大深度，避免扫描整个磁盘 */
    private static final int MAX_REPO_SCAN_DEPTH = 6;

    /** 递归扫描时跳过的目录名 */
    private static final Set<String> SKIP_SCAN_DIRS = Set.of(
            ".git", ".idea", ".gradle", "build", "target", "node_modules", ".svn");

    /** .git/HEAD 中分支引用的前缀 */
    private static final String HEAD_REF_PREFIX = "ref: refs/heads/";

    /** 分支名未知 / 游离 HEAD 时的显示文案 */
    private static final String UNKNOWN_BRANCH = "(未知)";
    private static final String DETACHED_BRANCH = "(detached)";

    /** 通知组 id，与 plugin.xml 中注册的一致 */
    private static final String NOTIFICATION_GROUP = "GitHelper";

    /** 工具窗口 id，与 plugin.xml 中注册的一致 */
    private static final String TOOL_WINDOW_ID = "Git 助手";

    /** 鼠标移出判定容差：覆盖输入框与弹层之间的间隙及弹层阴影 */
    private static final int HOVER_MARGIN = 12;

    // 表格三列的推荐宽度（勾选列索引：排序与勾选状态读取均基于该列，固定为 0）
    private static final int CHECKBOX_COLUMN_INDEX = 0;
    private static final int CHECKBOX_COLUMN_WIDTH = 30;
    private static final int PROJECT_COLUMN_WIDTH = 300;
    private static final int BRANCH_COLUMN_WIDTH = 120;

    /** 设置弹窗：并行进程数的默认值、范围与步长 */
    private static final int DEFAULT_PARALLEL_THREADS = 8;
    private static final int MIN_PARALLEL_THREADS = 1;
    private static final int MAX_PARALLEL_THREADS = 32;
    private static final int PARALLEL_THREADS_STEP = 1;

    /** 设置弹窗尺寸与网格内边距 */
    private static final int SETTINGS_PANEL_WIDTH = 380;
    private static final int SETTINGS_PANEL_HEIGHT = 235;

    /** 恢复默认分隔符按钮文案 */
    private static final String RESET_SEPARATORS_TEXT = "恢复";
    private static final int SETTINGS_INSET_VERTICAL = 4;
    private static final int SETTINGS_INSET_HORIZONTAL = 6;
    private static final float SETTINGS_TIP_FONT_DELTA = 1.0f;

    private final TextFieldWithBrowseButton rootField = new TextFieldWithBrowseButton();
    private final DefaultTableModel tableModel = new DefaultTableModel(
            new Object[]{"", "项目", "当前分支"}, 0) {
        @Override
        public Class<?> getColumnClass(int c) {
            return c == 0 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            return c == 0;
        }
    };
    private final JTable table = new JTable(tableModel);
    private final JScrollPane tableScroll = new JScrollPane(table);
    private final List<Path> repoPaths = new ArrayList<>();
    private final JPanel mainPanel = new JPanel(new BorderLayout(8, 8));

    /**
     * 项目搜索框：输入过滤表格行，清空恢复全部
     * 搜索项目：平台 SearchTextField（Find in Files 同款），
     * 自带输入框内部右侧的 × 清除图标
     */
    private final SearchTextField searchField = new SearchTextField(false);

    /**
     * 表格行排序/过滤器（用视图行-模型行转换支持搜索过滤）。
     * 勾选的行始终排在最前面，勾选状态变化时自动重排。
     */
    private final TableRowSorter<DefaultTableModel> rowSorter = createRowSorter();

    private final JButton switchBtn = new JButton("切换分支");
    private final JButton createBtn = new JButton("新建分支");
    private final JButton syncBtn = new JButton("同步远程");
    private final JButton pushBtn = new JButton("推送远程");
    private final JButton selectAllBtn = new JButton("全选");
    private final JButton deselectAllBtn = new JButton("取消全选");

    /**
     * 正在加载当前分支的行，防止重复加载
     */
    private final Set<Integer> loadingRows = new HashSet<>();

    /**
     * 扫描代次：刷新时递增，使旧的后台加载结果失效
     */
    private int scanGen = 0;

    private final Project myProject;

    /**
     * 是否嵌入工具窗口：嵌入时主面板常驻，ESC/点外部只关子弹窗，不关主面板
     */
    private final boolean embedded;

    /**
     * 全局 AWT 事件监听（ESC 分层 + 点外部关闭）；随 dialog 释放一并移除
     */
    private AWTEventListener globalListener;

    /**
     * 本工具额外打开的窗口（设置弹窗等）：点击这些窗口不算"点外部"
     */
    private final Set<Window> toolOwnedWindows = new java.util.concurrent.CopyOnWriteArraySet<>();

    /**
     * 本实例子弹窗的唯一窗口标识：兜底清理时精确匹配，
     * 避免按标题前缀扫描 Window.getWindows() 误杀其它项目/IDE 的弹窗
     */
    private final String branchWindowTag = "GitHelperBranchDialog#" + System.identityHashCode(this);

    private SwitchCallback onSwitch;
    private CreateCallback onCreate;
    private SimpleCallback onSync;
    private SimpleCallback onPush;

    /**
     * 同步/推送回调（无需额外参数）
     */
    public interface SimpleCallback {
        void run(List<Path> repos);
    }

    /**
     * 切换分支回调
     */
    public interface SwitchCallback {
        void run(List<Path> repos, String targetBranch);
    }

    /**
     * 新建分支回调
     */
    public interface CreateCallback {
        void run(List<Path> repos, String sourceBranch, String targetBranch);
    }

    public GitHelperDialog(@Nullable Project project, String defaultRoot) {
        this(project, defaultRoot, false);
    }

    /**
     * 所有仍打开的分支子弹窗。用列表而非单引用：单引用被清掉后，
     * 还显示着的分支弹窗会变成"孤立弹窗"（点外部关不掉、ESC 也管不到）。
     */
    private final List<BranchInputDialog> openBranchDialogs = new ArrayList<>();

    /**
     * 分支缓存最多保留的仓库数，避免大工作区下静态缓存无限增长
     */
    private static final int MAX_BRANCH_CACHE_ENTRIES = 32;

    /**
     * 分支列表缓存：key=仓库路径，value=分支列表 + 读取时间戳。
     * 之前每次 new BranchInputDialog 都在 EDT 构造函数里同步跑 git branch -a，
     * 卡住界面导致用户反复点击、堆积多个弹窗。缓存后同仓库再次打开是纯内存操作。
     * 写入时按插入顺序淘汰最早的条目，超出上限自动回收。
     */
    private static final Map<String, CachedBranches> BRANCH_CACHE = new LinkedHashMap<>();

    /**
     * 缓存有效期（毫秒）：超过后仅后台刷新，仍先用缓存瞬时打开
     */
    private static final long BRANCH_CACHE_TTL = 60_000L;

    private record CachedBranches(List<String> branches, long timestamp) {
    }

    private static List<String> getCachedBranches(Path repo) {
        synchronized (BRANCH_CACHE) {
            CachedBranches cache = BRANCH_CACHE.get(repo.toString());
            return Objects.isNull(cache) ? null : new ArrayList<>(cache.branches());
        }
    }

    private static boolean isCacheFresh(Path repo) {
        synchronized (BRANCH_CACHE) {
            CachedBranches cache = BRANCH_CACHE.get(repo.toString());
            return Objects.nonNull(cache)
                    && System.currentTimeMillis() - cache.timestamp() < BRANCH_CACHE_TTL;
        }
    }

    private static void putCachedBranches(Path repo, List<String> branches) {
        synchronized (BRANCH_CACHE) {
            BRANCH_CACHE.put(repo.toString(), new CachedBranches(new ArrayList<>(branches),
                    System.currentTimeMillis()));
            while (BRANCH_CACHE.size() > MAX_BRANCH_CACHE_ENTRIES) {
                BRANCH_CACHE.remove(BRANCH_CACHE.keySet().iterator().next());
            }
        }
    }

    /**
     * 同步/推送后使分支缓存失效，避免分支下拉框继续展示旧列表。
     *
     * @param repos 需要失效缓存的仓库列表
     */
    public void invalidateBranchCache(@NotNull List<Path> repos) {
        if (CollectionUtils.isEmpty(repos)) {
            return;
        }
        synchronized (BRANCH_CACHE) {
            for (Path repo : repos) {
                BRANCH_CACHE.remove(repo.toString());
            }
        }
    }

    private void registerBranchDialog(BranchInputDialog d) {
        synchronized (openBranchDialogs) {
            if (!openBranchDialogs.contains(d)) {
                openBranchDialogs.add(d);
            }
        }
    }

    private void unregisterBranchDialog(BranchInputDialog d) {
        synchronized (openBranchDialogs) {
            openBranchDialogs.remove(d);
        }
    }

    /**
     * 当前显示的分支子弹窗窗口集合
     */
    private List<java.awt.Window> branchWindows() {
        List<java.awt.Window> result = new ArrayList<>();
        synchronized (openBranchDialogs) {
            for (BranchInputDialog d : openBranchDialogs) {
                java.awt.Window w = d.getWindow();
                if (Objects.nonNull(w) && w.isShowing()) {
                    result.add(w);
                }
            }
        }
        return result;
    }

    /**
     * 统一注册全局 ESC / 点外部监听。
     * <p>
     * 独立弹窗与工具窗口嵌入面板共用本入口（由 GitHelperAction 工厂调用），
     * 避免只给其中一个入口装监听导致另一入口 ESC/点外部全部失效。
     * 监听挂在 dialog 自己的 Disposable 上，销毁时自动移除。
     */
    public void installGlobalHandlers() {
        if (Objects.nonNull(globalListener)) {
            return;
        }
        AWTEventListener listener = event -> {
            if (event instanceof KeyEvent ke) {
                // ESC 只在焦点属于本工具（或有本工具的弹层）时分发：
                // 否则用户在编辑器里按 ESC 关补全/查找框会顺带关掉 GitHelper 的一层。
                if (ke.getID() == KeyEvent.KEY_PRESSED
                        && ke.getKeyCode() == KeyEvent.VK_ESCAPE
                        && isEscapeRelevant()) {
                    diag("main global ESC");
                    handleEscape();
                }
                return;
            }
            if (!(event instanceof MouseEvent me)) {
                return;
            }
            // 鼠标移出「输入框 + 下拉弹层」整块区域时自动收起弹层，无需点击。
            // 只在鼠标曾进入过该区域后才生效，避免用户全程用键盘输入时被误关。
            if (me.getID() == MouseEvent.MOUSE_MOVED) {
                handlePopupHoverOut(me);
                return;
            }
            if (me.getID() != MouseEvent.MOUSE_PRESSED) {
                return;
            }
            // 嵌入面板常驻：没有本工具的弹层时，IDE 里的点击与本工具无关
            if (embedded && CollectionUtils.isEmpty(branchWindows())
                    && !hasVisibleBranchPopup()) {
                return;
            }
            // 下拉弹层可见时，点击弹层与输入框之外的任何位置都只收起弹层，
            // 不连带关闭子弹窗/主弹窗（老实现被 isInsideRelatedWindow 提前拦住）。
            if (hasVisibleBranchPopup() && !isInsideBranchPopup(me)
                    && !isInsideBranchEditor(me)) {
                diag("outsideClick: close branch popup");
                hideBranchPopups();
                return;
            }
            if (isInsideRelatedWindow(me)) {
                return;
            }
            diag("main outsideClick close");
            onOutsideClick();
        };
        globalListener = listener;
        Toolkit.getDefaultToolkit().addAWTEventListener(
                listener, AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK
                        | AWTEvent.KEY_EVENT_MASK);
        Disposer.register(getDisposable(), this::removeGlobalHandlers);
    }

    /**
     * 移除全局监听（install/dispose 双保险，可重复调用）
     */
    private void removeGlobalHandlers() {
        if (Objects.nonNull(globalListener)) {
            Toolkit.getDefaultToolkit().removeAWTEventListener(globalListener);
            globalListener = null;
        }
    }

    /**
     * 鼠标是否曾进入过「输入框 + 弹层」区域。
     * 只有进入过再移出才收起，避免弹层刚展开就被同一次鼠标移动误收。
     */
    private boolean popupHoverInside = false;

    /**
     * 是否有正在显示的平台下拉弹层。
     *
     * @return 有则返回 true
     */
    private boolean hasVisibleBranchPopup() {
        synchronized (openBranchDialogs) {
            for (BranchInputDialog dialog : openBranchDialogs) {
                if (dialog.isBranchPopupVisible()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 鼠标事件是否落在某个分支输入框（含右侧 × / 下拉箭头扩展区）内。
     *
     * @param e 鼠标事件
     * @return 落在输入框内返回 true
     */
    private boolean isInsideBranchEditor(MouseEvent e) {
        synchronized (openBranchDialogs) {
            for (BranchInputDialog dialog : openBranchDialogs) {
                if (dialog.isInsideBranchEditor(e)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 鼠标移出「输入框 + 下拉弹层」整块区域时收起弹层。
     * <p>
     * 只收起（隐藏弹层窗口、保留实例），不 cancel/dispose，更不会关闭
     * 分支子弹窗或主弹窗。仅响应真实 MOUSE_MOVED，用户全程键盘输入时
     * 鼠标静止不会触发，避免误收起。
     *
     * @param e 鼠标移动事件
     */
    private void handlePopupHoverOut(MouseEvent e) {
        if (!hasVisibleBranchPopup()) {
            popupHoverInside = false;
            return;
        }
        List<BranchInputDialog> snapshot;
        synchronized (openBranchDialogs) {
            snapshot = new ArrayList<>(openBranchDialogs);
        }
        boolean inside = false;
        for (BranchInputDialog dialog : snapshot) {
            if (dialog.isBranchPopupVisible() && dialog.isInsideBranchHoverArea(e)) {
                inside = true;
                break;
            }
        }
        if (inside) {
            popupHoverInside = true;
            return;
        }
        // 鼠标从未进入过区域（例如刚用键盘展开），不收起，避免"点了没反应"
        if (!popupHoverInside) {
            return;
        }
        popupHoverInside = false;
        diag("hover out: collapse branch popup");
        hideBranchPopups();
    }

    /**
     * 鼠标事件是否落在某个平台下拉弹层内部。
     * 平台弹层可能是轻量组件或独立窗口，故同时按组件层级与屏幕矩形判断。
     *
     * @param e 鼠标事件
     * @return 落在弹层内返回 true
     */
    private boolean isInsideBranchPopup(MouseEvent e) {
        synchronized (openBranchDialogs) {
            for (BranchInputDialog dialog : openBranchDialogs) {
                if (dialog.isInsideBranchPopup(e)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 收起所有分支下拉弹层；只隐藏列表，不关闭任何窗口。
     *
     * @return 是否关掉了至少一个弹层
     */
    private boolean hideBranchPopups() {
        List<BranchInputDialog> snapshot;
        synchronized (openBranchDialogs) {
            snapshot = new ArrayList<>(openBranchDialogs);
        }
        boolean any = false;
        for (BranchInputDialog dialog : snapshot) {
            if (dialog.isBranchPopupVisible()) {
                dialog.hideBranchPopup();
                any = true;
            }
        }
        return any;
    }

    /**
     * 嵌入模式下按 ESC 收起工具窗口面板。
     */
    private void hideEmbeddedToolWindow() {
        if (Objects.isNull(myProject)) {
            return;
        }
        ToolWindow toolWindow = ToolWindowManager.getInstance(myProject)
                .getToolWindow(TOOL_WINDOW_ID);
        if (Objects.nonNull(toolWindow) && toolWindow.isVisible()) {
            toolWindow.hide();
        }
    }

    /**
     * 是否存在阻塞交互的模态对话框（设置弹窗等）。
     * 存在时 ESC 应交给该对话框处理，不该抢先关掉 GitHelper。
     *
     * @return 存在则返回 true
     */
    private boolean isModalDialogBlocking() {
        for (Window window : Window.getWindows()) {
            if (!window.isShowing()) {
                continue;
            }
            if (window instanceof Dialog dialog && dialog.isModal()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 本次 ESC 是否需要交给本工具处理。
     * <p>
     * 老实现要求焦点落在主弹窗窗口内，但非模态弹窗在子弹窗关闭后焦点会回到
     * IDE 主框架，导致"按 ESC 关不掉窗口"。现在只要本工具有可见层就响应。
     *
     * @return 需要本工具处理则返回 true
     */
    private boolean isEscapeRelevant() {
        if (isModalDialogBlocking()) {
            return false;
        }
        if (hasVisibleBranchPopup() || CollectionUtils.isNotEmpty(branchWindows())) {
            return true;
        }
        return embedded ? mainPanel.isShowing() : isShowing();
    }

    /**
     * 点外部：独立弹窗关全部；嵌入面板只关子弹窗，主面板常驻
     */
    private void onOutsideClick() {
        // 下拉弹层开着时点外部只收起弹层，不连带关闭子弹窗/主弹窗
        if (hideBranchPopups()) {
            diag("outsideClick: close branch popup");
            return;
        }
        if (embedded) {
            closeBranchDialogs();
            return;
        }
        closeAll();
    }

    /**
     * 判断一次鼠标点击是否落在"本工具相关窗口"内：主弹窗、嵌入面板、所有分支子弹窗、
     * 它们的子窗口（原生 ComboBox 下拉弹层会超出子弹窗边界）、以及本工具/平台
     * 打开的模态对话框（设置、目录选择、进度弹窗）。
     * WindowFocusListener 对非模态主弹窗不可靠（子弹窗关闭后主弹窗不会再收到失焦），
     * 因此改用全局 MOUSE_PRESSED 判定点外部关闭。
     */
    public boolean isInsideRelatedWindow(MouseEvent e) {
        Window mainWin = getWindow();
        List<Window> subs = branchWindows();

        // 1) 事件源组件所属窗口（轻量弹层会归属到宿主窗口）
        Window srcWin = SwingUtilities.getWindowAncestor(e.getComponent());
        if (Objects.nonNull(srcWin) && (srcWin == mainWin || subs.contains(srcWin)
                || toolOwnedWindows.contains(srcWin) || ownedBy(srcWin, mainWin))) {
            return true;
        }
        Point pt;
        try {
            pt = e.getLocationOnScreen();
        } catch (IllegalComponentStateException ex) {
            // 组件已不可显示，拿不到坐标时保守不关，避免误关
            return true;
        }
        // 2) 平台下拉弹层（内容组件层级 + 屏幕矩形）
        if (isInsideBranchPopup(e)) {
            return true;
        }
        // 3) 主弹窗 / 嵌入面板 / 任一子弹窗 / 本工具额外窗口矩形
        if (inBounds(mainWin, pt) || inEmbeddedPanel(pt)) {
            return true;
        }
        for (Window sub : subs) {
            if (inBounds(sub, pt)) {
                return true;
            }
        }
        for (Window owned : toolOwnedWindows) {
            if (inBounds(owned, pt)) {
                return true;
            }
        }
        // 4) owner 链属于主弹窗/子弹窗的可见窗口；
        //    以及当前覆盖点击位置的模态对话框（用户正在与之交互，不该突然关主弹窗）
        Window[] roots = new Window[subs.size() + 1];
        roots[0] = mainWin;
        for (int i = 0; i < subs.size(); i++) {
            roots[i + 1] = subs.get(i);
        }
        for (Window w : Window.getWindows()) {
            if (!w.isShowing() || !inBounds(w, pt) || w == mainWin || subs.contains(w)) {
                continue;
            }
            if (ownedBy(w, roots)) {
                return true;
            }
            if (w instanceof Dialog dlg && dlg.isModal()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 嵌入模式下主面板自身的屏幕矩形（点自己的面板不算外部）
     */
    private boolean inEmbeddedPanel(Point pt) {
        if (!embedded || !mainPanel.isShowing()) {
            return false;
        }
        try {
            return new Rectangle(mainPanel.getLocationOnScreen(), mainPanel.getSize()).contains(pt);
        } catch (IllegalComponentStateException ex) {
            return false;
        }
    }

    private static boolean inBounds(Window w, Point pt) {
        return Objects.nonNull(w) && w.isShowing() && w.getBounds().contains(pt);
    }

    private static boolean ownedBy(Window w, Window... roots) {
        if (Objects.isNull(w)) {
            return false;
        }
        Window owner = w.getOwner();
        while (Objects.nonNull(owner)) {
            for (Window r : roots) {
                if (Objects.nonNull(r) && owner == r) {
                    return true;
                }
            }
            owner = owner.getOwner();
        }
        return false;
    }

    /**
     * ESC 分层关闭：下拉弹层 → 分支子弹窗 → 主弹窗/工具窗口，每次只关一层。
     * 嵌入模式下没有"主弹窗"这一层，面板常驻。
     */
    public void handleEscape() {
        // 第 1 层：分支下拉弹层（只收起列表，子弹窗保留）
        if (hideBranchPopups()) {
            diag("esc layer1: close branch popup");
            return;
        }
        // 第 2 层：分支子弹窗（只关子弹窗，主弹窗保留）
        if (closeBranchDialogs()) {
            diag("esc layer2: close branch dialog");
            return;
        }
        // 第 3 层：嵌入面板收起工具窗口；独立弹窗直接关闭
        if (embedded) {
            diag("esc layer3: hide tool window");
            hideEmbeddedToolWindow();
            return;
        }
        diag("esc layer3: close main dialog");
        doCancelAction();
    }

    /**
     * 点外部：关闭所有层（主弹窗 + 所有分支子弹窗），保证不残留孤立弹窗。
     * doCancelAction 内部已先清子弹窗，这里不再重复扫描。
     */
    public void closeAll() {
        doCancelAction();
    }

    /**
     * 关闭所有分支子弹窗；返回是否确实关了至少一个。
     * 除列表中的实例外，还按本实例唯一窗口标识兜底扫描，防止引用丢失造成孤立弹窗；
     * 不再按标题前缀匹配，避免误杀其它项目窗口或 IDE 自身的同名弹窗。
     */
    private boolean closeBranchDialogs() {
        List<BranchInputDialog> copy;
        synchronized (openBranchDialogs) {
            openBranchDialogs.removeIf(d -> !d.isShowing());
            copy = new ArrayList<>(openBranchDialogs);
        }
        boolean any = false;
        for (BranchInputDialog d : copy) {
            if (d.isShowing()) {
                any = true;
                diag("close branch dialog: " + d.getTitle());
                d.doCancelAction();
            }
        }
        // BranchInputDialog 用 super(project, true, MODELESS)，平台把 owner 设为 IDE 主窗口，
        // 因此不能按 owner 过滤；showFocused() 给窗口打了本实例唯一标识，按标识精确匹配。
        for (Window w : Window.getWindows()) {
            if (w.isShowing() && branchWindowTag.equals(w.getName())) {
                any = true;
                diag("close orphan branch window: " + w);
                w.setVisible(false);
                w.dispose();
            }
        }
        synchronized (openBranchDialogs) {
            openBranchDialogs.removeIf(d -> !d.isShowing());
        }
        return any;
    }

    /**
     * 主弹窗确认：保存当前根目录文本，随后按平台默认流程关闭。
     */
    @Override
    protected void doOKAction() {
        String root = rootField.getText().trim();
        if (StrUtil.isNotBlank(root)) {
            GitHelperSettings.setWorkspaceRoot(root);
        }
        super.doOKAction();
    }

    /**
     * 主弹窗关闭统一入口（取消按钮 / 右上角 × / 第 3 层 ESC / 点外部）。
     * 关主弹窗前先清掉所有分支子弹窗，避免残留；
     * 嵌入模式没有窗口 peer，只清子弹窗，不调用 super（不关闭常驻面板）。
     */
    @Override
    public void doCancelAction() {
        if (!embedded && !isShowing()) {
            return;
        }
        diag("main doCancelAction");
        closeBranchDialogs();
        if (!embedded) {
            super.doCancelAction();
        }
    }

    /**
     * 禁用平台自带的 ESC 快捷键。
     * 平台把 ESC 注册为 WHEN_IN_FOCUSED_WINDOW（DialogWrapper.registerKeyboardShortcuts），
     * 与主弹窗的全局 ESC 监听会竞态：有时一次把两层都关掉、有时又完全不响应。
     * 返回 null 让平台忽略 ESC，改由 GitHelperAction 的全局监听统一分层分发。
     */
    @Override
    protected @Nullable java.awt.event.ActionListener createCancelAction() {
        return null;
    }

    /**
     * @param embedded true 时用于嵌入工具窗口：跳过 DialogWrapper.init()，
     *                 不创建对话框窗口 peer（嵌入模式下 peer 不完整会导致
     *                 createTitlePane 递归时 NPE、面板内容无法加入工具窗口）
     */
    public GitHelperDialog(@Nullable Project project, String defaultRoot, boolean embedded) {
        // 非模态（不传 parentComponent，见 BranchInputDialog 同款平台坑注释）：
        // 点外部关闭由 GitHelperAction 的全局 MOUSE_PRESSED 监听负责，非模态下才能收到外部点击。
        // 嵌入工具窗口模式（embedded=true）不创建窗口 peer，此参数无实际影响。
        super(project, true, DialogWrapper.IdeModalityType.MODELESS);
        this.myProject = project;
        this.embedded = embedded;
        setTitle("Git 助手");
        setSize(640, 480);
        rootField.setText(defaultRoot);
        // 中文描述文案按平台 DialogTitleCapitalization 检查会误报，抑制具体检查项
        //noinspection DialogTitleCapitalization
        String folderDesc = "包含所有 Git 子项目的目录";
        rootField.addBrowseFolderListener("选择工作区根目录",
                folderDesc, project,
                FileChooserDescriptorFactory.createSingleFolderDescriptor());
        // 平台选择完成后会触发 browse 按钮的 ActionListener，此时立即保存并扫描
        rootField.addActionListener(e -> applyRoot(rootField.getText()));

        JButton refreshBtn = new JButton("刷新");
        refreshBtn.addActionListener(e -> applyRoot(rootField.getText()));

        selectAllBtn.addActionListener(e -> setAllSelected(true));
        deselectAllBtn.addActionListener(e -> setAllSelected(false));

        switchBtn.addActionListener(e -> showSwitchDialog());
        createBtn.addActionListener(e -> showCreateDialog());
        syncBtn.addActionListener(e -> runIfSelected(() -> onSync.run(getSelectedRepos())));
        pushBtn.addActionListener(e -> runIfSelected(() -> onPush.run(getSelectedRepos())));
        JButton settingsBtn = new JButton("设置");
        settingsBtn.addActionListener(e -> showSettingsDialog());

        // 三行统一用 GridBagLayout，右边缘严格对齐（右侧按钮列上下一条线）
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(0, 8, 0, 0);
        gbc.anchor = GridBagConstraints.WEST;

        JPanel topRow = new JPanel(new GridBagLayout());
        gbc.gridx = 0;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.insets = new Insets(0, 0, 0, 8);
        topRow.add(new JLabel("工作区根目录:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(0, 0, 0, 8);
        topRow.add(rootField, gbc);
        gbc.gridx = 2;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.insets = new Insets(0, 0, 0, 0);
        topRow.add(refreshBtn, gbc);

        JPanel searchRow = new JPanel(new GridBagLayout());
        gbc.gridx = 0;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.insets = new Insets(0, 0, 0, 8);
        searchRow.add(new JLabel("搜索项目:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(0, 0, 0, 8);
        // 搜索框：SearchTextField 自带内部右侧 × 清除图标（Find in Files 同款），
        // 清除图标触发 setText("") → 文档监听 → applyProjectFilter 恢复全部项目
        searchRow.add(searchField, gbc);
        // 同步/推送放在搜索框右侧，右边缘与上方"刷新"对齐
        gbc.gridx = 2;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.insets = new Insets(0, 0, 0, 0);
        searchRow.add(syncBtn, gbc);
        gbc.gridx = 3;
        searchRow.add(pushBtn, gbc);
        searchField.getTextEditor().getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                applyProjectFilter();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                applyProjectFilter();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                applyProjectFilter();
            }
        });

        JPanel north = new JPanel(new GridLayout(2, 1, 0, 4));
        north.add(topRow);
        north.add(searchRow);

        // 底部按钮行同样用 GridBagLayout，右侧按钮右边缘与上方严格对齐（无尾随间隙）
        JPanel btnRow = new JPanel(new GridBagLayout());
        gbc.gridx = 0;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.insets = new Insets(0, 0, 0, 8);
        btnRow.add(selectAllBtn, gbc);
        gbc.gridx = 1;
        btnRow.add(deselectAllBtn, gbc);
        gbc.gridx = 2;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        btnRow.add(new JPanel(), gbc);
        gbc.gridx = 3;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.insets = new Insets(0, 0, 0, 8);
        btnRow.add(switchBtn, gbc);
        gbc.gridx = 4;
        gbc.insets = new Insets(0, 0, 0, 8);
        btnRow.add(createBtn, gbc);
        gbc.gridx = 5;
        gbc.insets = new Insets(0, 0, 0, 0);
        btnRow.add(settingsBtn, gbc);

        mainPanel.add(north, BorderLayout.NORTH);
        mainPanel.add(tableScroll, BorderLayout.CENTER);
        mainPanel.add(btnRow, BorderLayout.SOUTH);

        // 搜索过滤走视图行，需要视图行-模型行转换
        table.setRowSorter(rowSorter);
        // 未选择项目时按钮置灰
        switchBtn.setEnabled(false);
        createBtn.setEnabled(false);
        syncBtn.setEnabled(false);
        pushBtn.setEnabled(false);
        selectAllBtn.setEnabled(false);
        deselectAllBtn.setEnabled(false);
        tableModel.addTableModelListener(e -> updateActionButtons());

        // 滚动/视口变化时，懒加载进入可视区域的行
        tableScroll.getViewport().addChangeListener(e -> loadVisibleBranches());

        // 点击"当前分支"列：弹出分支选择框
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int row = table.rowAtPoint(e.getPoint());
                int col = table.columnAtPoint(e.getPoint());
                if (row >= 0 && col == 2) {
                    switchSingleRepo(row);
                }
            }
        });

        if (!embedded) {
            init();
        }
        scanAsync(defaultRoot);
    }

    public void setOnSwitch(SwitchCallback r) {
        this.onSwitch = r;
    }

    public void setOnCreate(CreateCallback r) {
        this.onCreate = r;
    }

    public void setOnSync(SimpleCallback r) {
        this.onSync = r;
    }

    public void setOnPush(SimpleCallback r) {
        this.onPush = r;
    }

    public List<Path> getSelectedRepos() {
        List<Path> sel = new ArrayList<>();
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            Boolean b = (Boolean) tableModel.getValueAt(i, 0);
            if (Boolean.TRUE.equals(b)) {
                sel.add(repoPaths.get(i));
            }
        }
        return sel;
    }

    /**
     * 获取内容面板（供工具窗口嵌入使用，只含主面板，不含 OK/Cancel 按钮行）
     */
    public JComponent getRootPanel() {
        return mainPanel;
    }

    /**
     * 打开设置对话框：配置批量操作的并行开关、进程数与搜索分隔符
     */
    private void showSettingsDialog() {
        SettingsDialog dialog = new SettingsDialog(myProject);
        // 设置弹窗属于本工具：在里面点击不算"点外部"，否则会误关主弹窗
        Window win = dialog.getWindow();
        if (Objects.nonNull(win)) {
            toolOwnedWindows.add(win);
        }
        try {
            try {
                if (dialog.showAndGet()) {
                    // 分隔符改动后立即按新规则重新过滤，无需重新打开面板
                    applyProjectFilter();
                    notifyInfo("配置成功");
                }
            } catch (Exception ex) {
                diag("showSettingsDialog failed=" + ex.getMessage());
                notifyInfo("配置失败");
            }
        } finally {
            if (Objects.nonNull(win)) {
                toolOwnedWindows.remove(win);
            }
        }
    }

    /**
     * 创建底部按钮行：确认 / 取消，按平台约定返回动作数组。
     *
     * @return 按钮动作数组
     */
    @Override
    protected Action @NotNull [] createActions() {
        getOKAction().putValue(Action.NAME, "确认");
        getCancelAction().putValue(Action.NAME, "取消");
        return new Action[]{getOKAction(), getCancelAction()};
    }

    /**
     * 销毁时移除全局监听并清理所有分支子弹窗，避免监听泄漏与孤立窗口残留。
     */
    @Override
    protected void dispose() {
        removeGlobalHandlers();
        closeBranchDialogs();
        super.dispose();
    }

    /**
     * 释放对话框资源，供工具窗口 Content 的 Disposer 回调使用。
     * 嵌入模式下可能尚未初始化对话框 peer，故忽略释放异常。
     */
    public void release() {
        try {
            dispose();
        } catch (Exception ignored) {
            // 未初始化 peer 时 release 会抛异常，静默忽略
        }
    }

    /**
     * 应用用户填写/选择的根目录：保存配置并立即重新扫描。
     *
     * @param root 根目录文本
     */
    private void applyRoot(String root) {
        String trimmed = StrUtil.isBlank(root) ? "" : root.trim();
        rootField.setText(trimmed);
        if (StrUtil.isBlank(trimmed)) {
            return;
        }
        GitHelperSettings.setWorkspaceRoot(trimmed);
        scanAsync(trimmed);
    }

    /**
     * 扫描工作区根目录下的 Git 仓库并重建表格，当前分支列仅填入占位文案，
     * 真正的分支名按可视区域懒加载。
     *
     * @param rootStr 工作区根目录路径
     */
    private void scanAsync(String rootStr) {
        scanGen++;
        tableModel.setRowCount(0);
        repoPaths.clear();
        loadingRows.clear();
        Path root;
        try {
            root = Path.of(rootStr);
        } catch (RuntimeException ex) {
            notifyInfo("根目录路径无效: " + rootStr);
            return;
        }
        if (!Files.isDirectory(root)) {
            notifyInfo("根目录不存在或不是目录: " + rootStr);
            return;
        }
        List<Path> found = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                        throws IOException {
                    if (dir.equals(root)) {
                        if (Files.exists(dir.resolve(GIT_DIR))) {
                            found.add(dir);
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }
                    if (dir.getNameCount() - root.getNameCount() > MAX_REPO_SCAN_DEPTH) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    String name = Objects.isNull(dir.getFileName())
                            ? "" : dir.getFileName().toString();
                    if (SKIP_SCAN_DIRS.contains(name)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (Files.exists(dir.resolve(GIT_DIR))) {
                        found.add(dir);
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException ex) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ex) {
            diag("scanAsync walk failed=" + ex.getMessage());
        }
        found.stream().sorted().forEach(path -> {
            repoPaths.add(path);
            tableModel.addRow(new Object[]{
                    false, path.getFileName().toString(), LOADING_TEXT});
        });
        if (repoPaths.isEmpty()) {
            notifyInfo("未在 " + root + " 下发现 Git 仓库（最多扫描 "
                    + MAX_REPO_SCAN_DEPTH + " 层）");
        }
        adjustColumnWidths();
        updateActionButtons();
        applyProjectFilter();
        loadVisibleBranches();
    }

    /**
     * 按搜索框内容过滤表格行，支持多关键字（任一命中即显示）。
     */
    private void applyProjectFilter() {
        String text = searchField.getText().trim();
        if (StrUtil.isBlank(text)) {
            rowSorter.setRowFilter(null);
        } else {
            String[] keywords = text.split(GitHelperSettings.buildSearchSeparatorRegex());
            List<String> effective = new ArrayList<>();
            for (String keyword : keywords) {
                String trimmed = keyword.trim();
                if (StrUtil.isNotBlank(trimmed)) {
                    effective.add(trimmed.toLowerCase());
                }
            }
            rowSorter.setRowFilter(new RowFilter<>() {
                @Override
                public boolean include(
                        RowFilter.Entry<? extends DefaultTableModel, ? extends Integer> entry) {
                    if (CollectionUtils.isEmpty(effective)) {
                        return true;
                    }
                    Object value = entry.getValue(1);
                    if (Objects.isNull(value)) {
                        return false;
                    }
                    String name = value.toString().toLowerCase();
                    for (String keyword : effective) {
                        if (name.contains(keyword)) {
                            return true;
                        }
                    }
                    return false;
                }
            });
        }
        loadVisibleBranches();
    }

    /**
     * 根据勾选状态与表格内容刷新各操作按钮的可用性。
     */
    private void updateActionButtons() {
        boolean anySelected = false;
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            if (Boolean.TRUE.equals(tableModel.getValueAt(i, 0))) {
                anySelected = true;
                break;
            }
        }
        boolean anyRow = tableModel.getRowCount() > 0;
        switchBtn.setEnabled(anySelected);
        createBtn.setEnabled(anySelected);
        syncBtn.setEnabled(anySelected);
        pushBtn.setEnabled(anySelected);
        selectAllBtn.setEnabled(anyRow);
        deselectAllBtn.setEnabled(anyRow);
    }

    /**
     * 仅在有勾选仓库时执行动作。
     *
     * @param action 待执行动作
     */
    private void runIfSelected(Runnable action) {
        if (CollectionUtils.isNotEmpty(getSelectedRepos())) {
            action.run();
        }
    }

    /**
     * 创建表格排序器：勾选的行排在最前面，未勾选行保持原相对顺序。
     *
     * @return 排序器
     */
    private TableRowSorter<DefaultTableModel> createRowSorter() {
        List<RowSorter.SortKey> checkedFirst =
                List.of(new RowSorter.SortKey(CHECKBOX_COLUMN_INDEX, SortOrder.ASCENDING));
        // 排序规则固定为"已勾选优先"，故重写排序入口时仍在内部应用该规则
        TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(tableModel) {
            @Override
            public void toggleSortOrder(int column) {
                super.setSortKeys(checkedFirst);
            }

            @Override
            public void setSortKeys(@Nullable List<? extends SortKey> sortKeys) {
                super.setSortKeys(checkedFirst);
            }
        };
        sorter.setComparator(CHECKBOX_COLUMN_INDEX, (a, b) -> Boolean.compare(
                Boolean.TRUE.equals(b), Boolean.TRUE.equals(a)));
        sorter.setSortKeys(checkedFirst);
        return sorter;
    }

    /**
     * 懒加载当前滚动可视区内的分支名，避免一次性加载全部仓库造成卡顿。
     */
    private void loadVisibleBranches() {
        Rectangle viewRect = tableScroll.getViewport().getViewRect();
        if (viewRect.isEmpty() || tableModel.getRowCount() == 0) {
            return;
        }
        int firstView = table.rowAtPoint(new Point(0, viewRect.y));
        int lastView = table.rowAtPoint(new Point(0, viewRect.y + viewRect.height - 1));
        if (firstView < 0) {
            firstView = 0;
        }
        if (lastView < 0) {
            lastView = table.getRowCount() - 1;
        }
        for (int view = firstView; view <= lastView; view++) {
            int row = table.convertRowIndexToModel(view);
            Object value = tableModel.getValueAt(row, 2);
            if (loadingRows.contains(row) || !LOADING_TEXT.equals(value)) {
                continue;
            }
            loadingRows.add(row);
            loadRowBranch(row);
        }
    }

    /**
     * 后台读取单个仓库的当前分支并回填表格行。
     * 无论扫描代次是否失配，都先清除加载态，避免单元格永久停在"加载中..."。
     *
     * @param row 表格模型行号
     */
    private void loadRowBranch(int row) {
        int gen = scanGen;
        Path repo = repoPaths.get(row);
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            String branch = currentBranch(repo);
            ApplicationManager.getApplication().invokeLater(() -> {
                loadingRows.remove(row);
                if (gen != scanGen || row >= tableModel.getRowCount()) {
                    return;
                }
                tableModel.setValueAt(branch, row, 2);
            }, ModalityState.any());
        });
    }

    /**
     * 批量操作完成后刷新指定仓库的当前分支显示。
     *
     * @param repos 需要刷新的仓库路径
     */
    public void refreshBranches(@NotNull List<Path> repos) {
        if (CollectionUtils.isEmpty(repos)) {
            return;
        }
        for (int row = 0; row < repoPaths.size(); row++) {
            if (repos.contains(repoPaths.get(row))) {
                loadRowBranch(row);
            }
        }
    }

    /**
     * 打开分支选择弹窗。场景一致时复用已有弹窗，场景不同则先关闭旧弹窗再新建，
     * 避免"新建分支"复用到只有分支选择框的切换弹窗。
     *
     * @param title         弹窗标题
     * @param repo          目标仓库
     * @param withName      是否还需要输入新分支名
     * @param defaultBranch 输入为空时回退使用的分支
     * @param onConfirm     确认回调，参数为基础分支与新分支名
     */
    private void openBranchDialog(String title, Path repo, boolean withName,
                                  String defaultBranch, BiConsumer<String, String> onConfirm) {
        List<BranchInputDialog> snapshot;
        synchronized (openBranchDialogs) {
            openBranchDialogs.removeIf(dialog -> !dialog.isShowing());
            snapshot = new ArrayList<>(openBranchDialogs);
        }
        for (BranchInputDialog existing : snapshot) {
            if (!existing.isShowing()) {
                continue;
            }
            if (existing.matches(title, repo, withName)) {
                Window window = existing.getWindow();
                if (Objects.nonNull(window)) {
                    window.toFront();
                    window.requestFocus();
                }
                diag("branch dialog already open -> reuse");
                return;
            }
            diag("branch dialog replaced: " + existing.getTitle() + " -> " + title);
            existing.doCancelAction();
        }
        BranchInputDialog dialog = new BranchInputDialog(title, repo, withName, defaultBranch);
        dialog.setOnConfirm(onConfirm);
        dialog.showFocused();
    }

    /**
     * 点击表格"当前分支"单元格：为单个仓库切换分支。
     *
     * @param viewRow 表格视图行号
     */
    private void switchSingleRepo(int viewRow) {
        int row = table.convertRowIndexToModel(viewRow);
        Path repo = repoPaths.get(row);
        String repoName = repo.getFileName().toString();
        openBranchDialog("切换到分支 - " + repoName, repo, false,
                tableBranchAt(row), (target, ignore) -> {
                    if (StrUtil.isBlank(target)) {
                        return;
                    }
                    tableModel.setValueAt(LOADING_TEXT, row, 2);
                    loadingRows.add(row);
                    ApplicationManager.getApplication().executeOnPooledThread(() -> {
                        List<String> logs = new ArrayList<>();
                        String failure = null;
                        try {
                            logs = new GitHelperSwitcher().switchBranches(
                                    List.of(repo), target, new EmptyProgressIndicator());
                        } catch (Exception ex) {
                            failure = StrUtil.isBlank(ex.getMessage())
                                    ? ex.toString() : ex.getMessage();
                        }
                        String branch = currentBranch(repo);
                        diag("switchSingleRepo done branch=" + branch + " failure=" + failure);
                        final String result = branch;
                        final String error = failure;
                        final List<String> finalLogs = logs;
                        ApplicationManager.getApplication().invokeLater(() -> {
                            // 无条件清理加载态：后台异常时回调仍会执行，不会永久停在"加载中..."
                            loadingRows.remove(row);
                            if (row < tableModel.getRowCount()) {
                                tableModel.setValueAt(result, row, 2);
                            }
                            if (StrUtil.isNotBlank(error)) {
                                notifyInfo("切换分支失败: " + error);
                            } else if (finalLogs.stream().anyMatch(log -> log.contains("失败"))) {
                                notifyInfo(String.join("\n", finalLogs));
                            }
                        }, ModalityState.any());
                    });
                });
    }

    /**
     * 底部"切换分支"按钮：对勾选的仓库批量切换。
     */
    private void showSwitchDialog() {
        List<Path> selected = getSelectedRepos();
        if (CollectionUtils.isEmpty(selected)) {
            return;
        }
        openBranchDialog("切换到分支", selected.get(0), false,
                firstSelectedBranch(), (target, ignore) -> {
                    if (StrUtil.isBlank(target)) {
                        return;
                    }
                    if (Objects.nonNull(onSwitch)) {
                        onSwitch.run(selected, target);
                    }
                });
    }

    /**
     * 底部"新建分支"按钮：基于所选分支为勾选仓库批量新建分支。
     */
    private void showCreateDialog() {
        List<Path> selected = getSelectedRepos();
        if (CollectionUtils.isEmpty(selected)) {
            return;
        }
        openBranchDialog("新建分支", selected.get(0), true,
                firstSelectedBranch(), (source, target) -> {
                    if (StrUtil.isBlank(source) || StrUtil.isBlank(target)) {
                        return;
                    }
                    if (Objects.nonNull(onCreate)) {
                        onCreate.run(selected, source, target);
                    }
                });
    }

    /**
     * 读取表格指定行的当前分支文本。
     *
     * @param row 表格模型行号
     * @return 分支名；加载中或越界时返回空串
     */
    private String tableBranchAt(int row) {
        if (row < 0 || row >= tableModel.getRowCount()) {
            return "";
        }
        Object value = tableModel.getValueAt(row, 2);
        return Objects.isNull(value) || LOADING_TEXT.equals(value) ? "" : value.toString();
    }

    /**
     * 取第一个勾选行的当前分支，作为分支弹窗的默认值。
     *
     * @return 分支名；无勾选时返回空串
     */
    private String firstSelectedBranch() {
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            if (Boolean.TRUE.equals(tableModel.getValueAt(i, 0))) {
                return tableBranchAt(i);
            }
        }
        return "";
    }

    /**
     * 发送信息级通知，级别固定为 INFORMATION。
     *
     * @param message 通知内容
     */
    private void notifyInfo(String message) {
        if (Objects.isNull(myProject)) {
            return;
        }
        NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification("Git 助手", message, NotificationType.INFORMATION)
                .notify(myProject);
    }

    /** 设置表格三列的推荐宽度 */
    private void adjustColumnWidths() {
        if (table.getColumnModel().getColumnCount() > 0) {
            table.getColumnModel().getColumn(0).setPreferredWidth(CHECKBOX_COLUMN_WIDTH);
            table.getColumnModel().getColumn(1).setPreferredWidth(PROJECT_COLUMN_WIDTH);
            table.getColumnModel().getColumn(2).setPreferredWidth(BRANCH_COLUMN_WIDTH);
        }
    }

    /**
     * 全选 / 取消全选当前可见行。
     *
     * @param selected 是否勾选
     */
    private void setAllSelected(boolean selected) {
        for (int view = 0; view < table.getRowCount(); view++) {
            int row = table.convertRowIndexToModel(view);
            tableModel.setValueAt(selected, row, 0);
        }
    }

    /**
     * 读取仓库当前分支：优先直接解析 .git/HEAD，失败时回退到 git rev-parse。
     *
     * @param repo 仓库路径
     * @return 当前分支名
     */
    private String currentBranch(Path repo) {
        Path head = repo.resolve(GIT_DIR).resolve("HEAD");
        if (Files.isRegularFile(head)) {
            try (BufferedReader reader = Files.newBufferedReader(head)) {
                String line = reader.readLine();
                if (StrUtil.isNotBlank(line) && line.startsWith(HEAD_REF_PREFIX)) {
                    return line.substring(HEAD_REF_PREFIX.length()).trim();
                }
                if (StrUtil.isNotBlank(line)) {
                    return DETACHED_BRANCH;
                }
            } catch (IOException ex) {
                diag("currentBranch head read failed=" + ex.getMessage());
            }
        }
        String out = GitHelperSwitcher.execGitForOutput(
                repo, "rev-parse", "--abbrev-ref", "HEAD").trim();
        return StrUtil.isBlank(out) ? UNKNOWN_BRANCH : out;
    }

    /**
     * 设置对话框：并行开关与进程数，以及多项目搜索分隔符。
     */
    private static class SettingsDialog extends DialogWrapper {
        private final JCheckBox parallelEnabled = new JCheckBox("开启多进程并行执行");
        private final JSpinner parallelThreads = new JSpinner(new SpinnerNumberModel(
                DEFAULT_PARALLEL_THREADS, MIN_PARALLEL_THREADS,
                MAX_PARALLEL_THREADS, PARALLEL_THREADS_STEP));

        /** 多项目搜索时用于拆分关键字的分隔符字符集合 */
        private final JTextField searchSeparators = new JTextField();

        /** 恢复按钮：把分隔符输入框还原成代码里的默认值 */
        private final JButton resetSeparators = new JButton(RESET_SEPARATORS_TEXT);

        /** 当前并行模式实时预览：切换开关时立刻刷新，不用保存后才知道 */
        private final JLabel currentModeLabel = new JLabel();

        SettingsDialog(@Nullable Project project) {
            super(project, true);
            setTitle("Git 助手设置");
            getOKAction().putValue(Action.NAME, "确定");
            getCancelAction().putValue(Action.NAME, "取消");
            parallelEnabled.setSelected(GitHelperSettings.isParallelEnabled());
            int threads = GitHelperSettings.getParallelThreads() > MIN_PARALLEL_THREADS
                    ? GitHelperSettings.getParallelThreads() : DEFAULT_PARALLEL_THREADS;
            parallelThreads.setValue(threads);
            parallelThreads.setEnabled(parallelEnabled.isSelected());
            searchSeparators.setText(GitHelperSettings.getSearchSeparators());
            resetSeparators.addActionListener(event ->
                    searchSeparators.setText(GitHelperSettings.DEFAULT_SEARCH_SEPARATORS));
            parallelEnabled.addActionListener(event -> {
                parallelThreads.setEnabled(parallelEnabled.isSelected());
                refreshCurrentMode();
            });
            refreshCurrentMode();
            init();
        }

        /**
         * 刷新"当前模式"预览文案，随开关勾选状态实时变化。
         */
        private void refreshCurrentMode() {
            currentModeLabel.setText(parallelEnabled.isSelected()
                    ? "保存后：" + GitHelperSettings.describe((Integer) parallelThreads.getValue())
                    : "保存后：串行执行（未开启并行）");
        }

        @Nullable
        @Override
        protected JComponent createCenterPanel() {
            JPanel panel = new JPanel(new GridBagLayout());
            GridBagConstraints gc = new GridBagConstraints();
            gc.gridx = 0;
            gc.gridy = 0;
            gc.gridwidth = 1;
            gc.fill = GridBagConstraints.HORIZONTAL;
            gc.weightx = 1.0;
            gc.anchor = GridBagConstraints.WEST;
            panel.add(buildParallelSection(), gc);

            gc.gridy = 1;
            panel.add(buildSeparatorSection(), gc);

            panel.setPreferredSize(new Dimension(SETTINGS_PANEL_WIDTH, SETTINGS_PANEL_HEIGHT));
            return panel;
        }

        /**
         * 分区一：并行执行（开关、进程数、说明、实时模式预览）。
         *
         * @return 带分区标题的子面板
         */
        private JComponent buildParallelSection() {
            JPanel section = new JPanel(new GridBagLayout());
            section.setBorder(BorderFactory.createTitledBorder("批量执行"));
            GridBagConstraints gc = new GridBagConstraints();
            gc.insets = JBUI.insets(SETTINGS_INSET_VERTICAL, SETTINGS_INSET_HORIZONTAL,
                    SETTINGS_INSET_VERTICAL, SETTINGS_INSET_HORIZONTAL);
            gc.gridx = 0;
            gc.gridy = 0;
            gc.gridwidth = 2;
            gc.anchor = GridBagConstraints.WEST;
            section.add(parallelEnabled, gc);

            gc.gridy = 1;
            gc.gridwidth = 1;
            gc.fill = GridBagConstraints.NONE;
            gc.weightx = 0.0;
            section.add(new JLabel("并行进程数:"), gc);
            gc.gridx = 1;
            gc.anchor = GridBagConstraints.WEST;
            section.add(parallelThreads, gc);

            gc.gridx = 0;
            gc.gridy = 2;
            gc.gridwidth = 2;
            section.add(tipLabel("关闭时逐个仓库串行执行；开启后同时处理多个仓库"), gc);

            gc.gridy = 3;
            currentModeLabel.setFont(tipFont());
            section.add(currentModeLabel, gc);
            return section;
        }

        /**
         * 分区二：搜索分隔符（输入框 + 恢复默认 + 说明）。
         *
         * @return 带分区标题的子面板
         */
        private JComponent buildSeparatorSection() {
            JPanel section = new JPanel(new GridBagLayout());
            section.setBorder(BorderFactory.createTitledBorder("搜索分隔符"));
            GridBagConstraints gc = new GridBagConstraints();
            gc.insets = JBUI.insets(SETTINGS_INSET_VERTICAL, SETTINGS_INSET_HORIZONTAL,
                    SETTINGS_INSET_VERTICAL, SETTINGS_INSET_HORIZONTAL);
            gc.gridx = 0;
            gc.gridy = 0;
            gc.fill = GridBagConstraints.NONE;
            gc.weightx = 0.0;
            gc.anchor = GridBagConstraints.WEST;
            section.add(new JLabel("分隔符字符:"), gc);

            gc.gridx = 1;
            gc.fill = GridBagConstraints.HORIZONTAL;
            gc.weightx = 1.0;
            section.add(searchSeparators, gc);

            gc.gridx = 2;
            gc.fill = GridBagConstraints.NONE;
            gc.weightx = 0.0;
            resetSeparators.setFocusable(false);
            section.add(resetSeparators, gc);

            gc.gridx = 0;
            gc.gridy = 1;
            gc.gridwidth = 3;
            section.add(tipLabel("按字符拆分搜索关键字；特殊字符按字面处理，空白始终有效"), gc);
            return section;
        }

        /**
         * 生成灰色小号说明文字。
         *
         * @param text 说明内容
         * @return 缩小字号后的标签
         */
        private JLabel tipLabel(String text) {
            JLabel label = new JLabel(text);
            label.setFont(tipFont());
            return label;
        }

        /**
         * 说明文字字体：比标准字号小一档。
         *
         * @return 缩小后的字体
         */
        private Font tipFont() {
            Font base = UIManager.getFont("Label.font");
            if (Objects.isNull(base)) {
                base = currentModeLabel.getFont();
            }
            return base.deriveFont(base.getSize2D() - SETTINGS_TIP_FONT_DELTA);
        }

        @Override
        protected void doOKAction() {
            GitHelperSettings.setParallelEnabled(parallelEnabled.isSelected());
            GitHelperSettings.setParallelThreads((Integer) parallelThreads.getValue());
            GitHelperSettings.setSearchSeparators(searchSeparators.getText());
            super.doOKAction();
        }
    }

    /**
     * 分支选择弹窗：输入框右侧带 × 清空与下拉箭头，展开平台原生下拉弹层。
     * <p>
     * 列表数据由本类管理、外观由 JBPopupFactory 提供，不使用 BasicComboPopup：
     * 原生弹层的 viewport 在过滤行数变化后不会重算（粘贴可显示、逐字删除
     * 只剩一行或大片空白），尺寸/视口 hack 无法根治。现在弹层尺寸恒定，
     * 过滤只改数据，因此首次点开、逐字删除、清空恢复全量表现一致。
     */
    private class BranchInputDialog extends DialogWrapper {
        /** 下拉列表固定显示行数与行高，尺寸不随过滤结果变化 */
        private static final int LIST_VISIBLE_ROWS = 12;
        private static final int LIST_CELL_HEIGHT = 22;
        private static final int LIST_WIDTH = 520;

        /** 列表区域额外留白与面板尺寸 */
        private static final int LIST_EXTRA_HEIGHT = 6;
        private static final int PANEL_EXTRA_WIDTH = 80;
        private static final int PANEL_HEIGHT = 60;
        private static final int PANEL_HEIGHT_WITH_NAME = 96;

        /** 输入过滤去抖时长（毫秒） */
        private static final int FILTER_DEBOUNCE_MS = 150;

        /**
         * 输入框左侧内边距（像素）：避免第一个字符被焦点边框压住。
         * 用于 createCenterPanel 中给 branchEditor 追加 EmptyBorder。
         */
        private static final int EDITOR_LEFT_INSET = 8;

        /** .git 中存放分支引用的子目录 */
        private static final String GIT_REFS_DIR = "refs";
        private static final String GIT_LOCAL_REFS = "heads";
        private static final String GIT_REMOTE_REFS = "remotes";
        private static final String GIT_PACKED_REFS = "packed-refs";
        private static final String REF_PREFIX_HEADS = "refs/heads/";
        private static final String REF_PREFIX_REMOTES = "refs/remotes/";

        /** packed-refs 中被剥离的 tag 引用前缀 */
        private static final String REF_PREFIX_TAGS = "refs/tags/";

        /** packed-refs 中 peeled 行的前缀 */
        private static final String PEELED_LINE_PREFIX = "^";

        /** packed-refs 每行字段分隔符（空格或制表符） */
        private static final String PACKED_REF_SEPARATOR = "\\s+";

        /** packed-refs 中引用路径所在字段下标 */
        private static final int PACKED_REF_PATH_INDEX = 1;

        /** 分支列表项可能携带的别名分隔符 */
        private static final String BRANCH_ALIAS_SEPARATOR = " | ";

        /**
         * 图标扩展（× 清空 / 下拉箭头）不显示气泡：
         * Extension.create 的第二个参数会被平台自动显示，故传空串。
         * 列表项的截断提示是另一回事，由列表 renderer 单独提供。
         */
        private static final String NO_TOOLTIP = "";

        /** 输入框右侧扩展图标（× 清空 + 下拉箭头）占用的宽度 */
        private static final int EXTENSION_AREA_WIDTH = 48;

        private final DefaultListModel<String> branchListModel = new DefaultListModel<>();

        /** 分支列表：tooltip 由 renderer 按是否超宽决定，见 installListHandlers */
        private final JList<String> branchList = new JList<>(branchListModel);

        private final JScrollPane branchScroll = new JScrollPane(branchList);
        private final JTextField nameField = new JTextField();
        private final boolean withName;
        private final Path repo;
        private final List<String> loadedBranches = new ArrayList<>();

        /** 打开弹窗时的当前分支：只作灰色占位提示，不是输入框真实文本 */
        private final String currentBranchHint;

        /**
         * 输入框：内部右侧带 × 清空与下拉箭头。
         * 默认分支以灰色斜体占位提示绘制，且只有这一处绘制来源：
         * 老实现同时用 getEmptyText() 和 paintComponent，两遍叠加导致文字发虚。
         * 不用 getEmptyText() 是因为输入框获得焦点时平台会隐藏它。
         */
        private final ExtendableTextField branchEditor = new ExtendableTextField() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                if (StrUtil.isNotBlank(getText()) || StrUtil.isBlank(currentBranchHint)) {
                    return;
                }
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                            RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g2.setColor(UIUtil.getContextHelpForeground());
                    g2.setFont(g2.getFont().deriveFont(Font.ITALIC));
                    FontMetrics fm = g2.getFontMetrics();
                    Insets insets = getInsets();
                    int y = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
                    g2.drawString(currentBranchHint, insets.left, y);
                } finally {
                    g2.dispose();
                }
            }
        };

        /**
         * 输入框内部右侧的下拉箭头扩展：与 Find in Files 搜索框的下拉图标一致，
         * 由平台绘制，不再使用自绘按钮或 BasicArrowButton。
         */
        private final ExtendableTextComponent.Extension arrowExtension =
                ExtendableTextComponent.Extension.create(
                        AllIcons.General.ArrowDown, NO_TOOLTIP,
                        this::toggleBranchPopup);

        /** 平台原生弹层：外观、边框、阴影、滚轮由 IDEA 提供，列表内容由本类管理 */
        private JBPopup branchPopup;

        /**
         * 弹层是否真正 show() 过。
         * 老实现只看 branchPopup != null，创建后立刻被判定为"已显示"，
         * showBranchPopup() 随即 return，弹层永远停在已创建未显示状态 ——
         * 这就是"下拉框点了没反应"的根因（日志中完全没有 showBranchPopup）。
         */
        private boolean popupShown = false;

        /** 弹层内容组件，用于判断点击是否落在弹层内部 */
        private JComponent popupContent;

        private final ExtendableTextComponent.Extension clearExtension =
                ExtendableTextComponent.Extension.create(
                        AllIcons.Actions.Close, NO_TOOLTIP, this::clearBranchEditor);

        private boolean branchesRequested = false;
        private BiConsumer<String, String> onConfirm;
        private String selectedBranch = null;
        private boolean ignoreDocEvents = false;
        private Timer filterDebounce = null;

        /**
         * 构造分支选择弹窗。使用三参构造器才能生效 MODELESS，
         * 传 parentComponent 会被平台静默回落为 APPLICATION_MODAL。
         *
         * @param title         弹窗标题
         * @param repo          目标仓库
         * @param withName      是否需要输入新分支名
         * @param defaultBranch 预填到输入框的当前分支
         */
        BranchInputDialog(String title, Path repo, boolean withName, String defaultBranch) {
            super(myProject, true, DialogWrapper.IdeModalityType.MODELESS);
            this.repo = repo;
            this.withName = withName;
            setTitle(title);
            getOKAction().putValue(Action.NAME, "确定");
            getCancelAction().putValue(Action.NAME, "取消");
            this.currentBranchHint = Objects.isNull(defaultBranch) ? "" : defaultBranch;

            // 输入框保持为空，默认分支由 paintComponent 画成灰色占位提示；
            // getBranch() 在文本为空时回退到 currentBranchHint。
            branchEditor.getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent e) {
                    onBranchTextChanged();
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    onBranchTextChanged();
                }

                @Override
                public void changedUpdate(DocumentEvent e) {
                    onBranchTextChanged();
                }
            });
            installListHandlers();
            updateEditorExtras();
            ensureBranchesLoaded();
            init();
            updateOkState();
            diag("init window=" + getWindow());
        }

        private static void diag(String msg) {
            GitHelperDialog.diag(msg);
        }

        /** 输入框文本变化：刷新 × 图标并安排过滤任务 */
        private void onBranchTextChanged() {
            updateEditorExtras();
            scheduleBranchRefresh();
        }

        /**
         * 列表交互：左键点选回填、右键复制。
         */
        private void installListHandlers() {
            branchList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            branchList.setFixedCellHeight(LIST_CELL_HEIGHT);
            branchList.setVisibleRowCount(LIST_VISIBLE_ROWS);
            branchList.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    int index = branchList.locationToIndex(e.getPoint());
                    Rectangle cell = index >= 0
                            ? branchList.getCellBounds(index, index) : null;
                    if (Objects.isNull(cell) || !cell.contains(e.getPoint())
                            || index >= branchListModel.getSize()) {
                        return;
                    }
                    String name = branchListModel.getElementAt(index);
                    if (SwingUtilities.isRightMouseButton(e)) {
                        copyBranchName(name);
                    } else if (SwingUtilities.isLeftMouseButton(e)) {
                        chooseSelectedBranch(name);
                    }
                }
            });
            // 右键被定义为「把刚才复制的分支名粘贴进来」，
            // 禁掉平台默认编辑菜单，避免一个右键同时弹菜单又填文本。
            branchEditor.setComponentPopupMenu(null);
            branchEditor.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (SwingUtilities.isRightMouseButton(e)) {
                        pasteBranchFromClipboard();
                        return;
                    }
                    // 右侧扩展图标区（× 清空 / 下拉箭头）由各自的 Extension 处理。
                    // 若这里也展开弹层，点 × 会出现"刚清空又弹出列表"的冲突表现。
                    if (e.getX() >= branchEditor.getWidth() - EXTENSION_AREA_WIDTH) {
                        return;
                    }
                    showBranchPopup();
                }
            });
        }

        /**
         * 右键输入框：把系统剪贴板里的分支名粘贴进来并展开下拉列表。
         * 与列表项的右键复制配套使用；剪贴板为空或无文本时不做任何改动。
         */
        private void pasteBranchFromClipboard() {
            String text = readClipboardText();
            if (StrUtil.isBlank(text)) {
                diag("pasteBranchFromClipboard empty");
                return;
            }
            diag("pasteBranchFromClipboard text=[" + text + "]");
            selectedBranch = null;
            cancelFilterDebounce();
            ignoreDocEvents = true;
            try {
                branchEditor.setText(text);
                branchEditor.setCaretPosition(text.length());
                updateEditorExtras();
            } finally {
                ignoreDocEvents = false;
            }
            updateBranchList(true);
            showBranchPopup();
            updateOkState();
            branchEditor.requestFocusInWindow();
        }

        /**
         * 读取系统剪贴板文本。
         *
         * @return 剪贴板文本；不可用或异常时返回空串
         */
        private String readClipboardText() {
            try {
                java.awt.datatransfer.Clipboard clipboard =
                        java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
                java.awt.datatransfer.Transferable contents = clipboard.getContents(null);
                if (Objects.isNull(contents) || !contents.isDataFlavorSupported(
                        java.awt.datatransfer.DataFlavor.stringFlavor)) {
                    return "";
                }
                Object data = contents.getTransferData(
                        java.awt.datatransfer.DataFlavor.stringFlavor);
                return data.toString().trim();
            } catch (Exception ex) {
                diag("readClipboardText failed=" + ex.getMessage());
                return "";
            }
        }

        /**
         * 右键复制完整分支名到系统剪贴板。
         *
         * @param name 列表项文本
         */
        private void copyBranchName(String name) {
            String clipped = name;
            int separatorIndex = clipped.indexOf(BRANCH_ALIAS_SEPARATOR);
            if (separatorIndex > 0) {
                clipped = clipped.substring(0, separatorIndex);
            }
            java.awt.datatransfer.StringSelection selection =
                    new java.awt.datatransfer.StringSelection(clipped.trim());
            java.awt.Toolkit.getDefaultToolkit()
                    .getSystemClipboard().setContents(selection, null);
            diag("right-click copy branch=" + clipped.trim());
        }

        /**
         * 刷新输入框右侧扩展。ExtendableTextField 的扩展从右往左排列，
         * 先 add 的更靠右；期望左 × 清空、右 ⌄ 展开（与 Find in Files 一致）。
         * <p>
         * 图标扩展和输入框不提供 tooltip，避免重复气泡；
         * 只有列表项在被截断时才提示（见 installListHandlers 的 renderer）。
         * × 清空始终显示：默认提示态点它会清空文本回到提示，交互稳定。
         */
        private void updateEditorExtras() {
            List<ExtendableTextComponent.Extension> extensions = new ArrayList<>();
            extensions.add(arrowExtension);
            extensions.add(clearExtension);
            branchEditor.setExtensions(extensions);
        }

        /**
         * 点击 × 清空：清空文本，输入框重新显示当前分支占位提示，
         * 列表恢复全量并展开，方便重新筛选。
         */
        private void clearBranchEditor() {
            selectedBranch = null;
            cancelFilterDebounce();
            ignoreDocEvents = true;
            try {
                branchEditor.setText("");
                updateEditorExtras();
            } finally {
                ignoreDocEvents = false;
            }
            updateBranchList(false);
            showBranchPopup();
            branchEditor.requestFocusInWindow();
            updateOkState();
        }

        /** 取消待执行的过滤任务 */
        private void cancelFilterDebounce() {
            if (Objects.nonNull(filterDebounce)) {
                filterDebounce.cancel();
                filterDebounce = null;
            }
        }

        /**
         * 输入停顿后过滤；同时展开下拉弹层，符合下拉框的输入联想习惯。
         * 程序化回填（ignoreDocEvents）不触发过滤，也不自动展开。
         */
        private void scheduleBranchRefresh() {
            if (ignoreDocEvents) {
                return;
            }
            diag("scheduleBranchRefresh text=[" + branchEditor.getText() + "]");
            selectedBranch = null;
            updateOkState();
            cancelFilterDebounce();
            filterDebounce = new Timer("branch-filter", true);
            filterDebounce.schedule(new java.util.TimerTask() {
                @Override
                public void run() {
                    SwingUtilities.invokeLater(() -> {
                        if (ignoreDocEvents || isImeComposing() || !isShowing()) {
                            return;
                        }
                        updateBranchList(StrUtil.isNotBlank(branchEditor.getText()));
                        showBranchPopup();
                    });
                }
            }, FILTER_DEBOUNCE_MS);
        }

        /**
         * 中文输入法组合期间不过滤，避免打断拼音串。
         *
         * @return 是否正在组合输入
         */
        private boolean isImeComposing() {
            java.awt.im.InputMethodRequests requests = branchEditor.getInputMethodRequests();
            if (Objects.isNull(requests)) {
                return false;
            }
            try {
                return requests.getCommittedTextLength()
                        < branchEditor.getDocument().getLength();
            } catch (Exception ignored) {
                return false;
            }
        }

        /**
         * 取用户选定的分支：优先已点选项，其次输入框中与列表精确匹配的文本，
         * 最后回退到打开弹窗时的当前分支。
         *
         * @return 分支名
         */
        String getBranch() {
            if (StrUtil.isNotBlank(selectedBranch)) {
                return selectedBranch.trim();
            }
            String text = branchEditor.getText().trim();
            if (StrUtil.isBlank(text)) {
                return currentBranchHint.trim();
            }
            synchronized (loadedBranches) {
                for (String branch : loadedBranches) {
                    if (branch.equals(text)) {
                        return text;
                    }
                }
            }
            return currentBranchHint.trim();
        }

        /**
         * 取新分支名输入框内容。
         *
         * @return 新分支名
         */
        String getNewBranchName() {
            return nameField.getText().trim();
        }

        /**
         * 设置确认回调。
         *
         * @param callback 回调，参数为基础分支与新分支名
         */
        void setOnConfirm(BiConsumer<String, String> callback) {
            onConfirm = callback;
        }

        /**
         * 判断已打开的弹窗是否与本次请求属于同一场景，供防重入复用判断。
         *
         * @param dialogTitle 弹窗标题
         * @param targetRepo  目标仓库
         * @param needName    是否需要输入新分支名
         * @return 场景一致返回 true
         */
        boolean matches(String dialogTitle, Path targetRepo, boolean needName) {
            return getTitle().equals(dialogTitle)
                    && repo.equals(targetRepo) && withName == needName;
        }

        /** 非模态显示并请求焦点；窗口名用于关闭时精确识别本实例弹窗 */
        void showFocused() {
            registerBranchDialog(this);
            show();
            Window window = getWindow();
            if (Objects.nonNull(window)) {
                window.setName(branchWindowTag);
            }
            SwingUtilities.invokeLater(() -> {
                Window current = getWindow();
                if (Objects.nonNull(current)) {
                    current.requestFocus();
                }
            });
        }

        /**
         * ESC 统一由主弹窗的全局监听分层分发，此处禁用平台自带取消动作。
         *
         * @return null
         */
        @Nullable
        @Override
        protected java.awt.event.ActionListener createCancelAction() {
            return null;
        }

        /**
         * 下拉弹层是否正在显示。
         *
         * @return 正在显示返回 true
         */
        boolean isBranchPopupVisible() {
            return Objects.nonNull(branchPopup) && !branchPopup.isDisposed()
                    && popupShown;
        }

        /**
         * 判断鼠标是否落在指定组件的屏幕矩形内。
         *
         * @param comp   目标组件
         * @param e      鼠标事件
         * @param margin 矩形外扩容差，单位像素
         * @return 落在矩形内返回 true
         */
        private boolean isInsideBounds(JComponent comp, MouseEvent e, int margin) {
            if (Objects.isNull(comp) || !comp.isShowing()) {
                return false;
            }
            try {
                Point pt = e.getLocationOnScreen();
                Rectangle bounds = new Rectangle(
                        comp.getLocationOnScreen(), comp.getSize());
                if (margin > 0) {
                    bounds.grow(margin, margin);
                }
                return bounds.contains(pt);
            } catch (IllegalComponentStateException ex) {
                // 组件刚被隐藏，拿不到坐标时保守认为仍在区域内
                return true;
            }
        }

        /**
         * 鼠标事件是否落在分支输入框（含右侧扩展图标区）内。
         *
         * @param e 鼠标事件
         * @return 落在输入框内返回 true
         */
        boolean isInsideBranchEditor(MouseEvent e) {
            return SwingUtilities.isDescendingFrom(e.getComponent(), branchEditor)
                    || isInsideBounds(branchEditor, e, 0);
        }

        /**
         * 鼠标是否仍在「输入框 + 下拉弹层」合并区域内（带容差）。
         * 弹层紧贴输入框下方，两矩形各外扩一点，避免鼠标经过两者间隙时
         * 被误判为移出而立刻收起，导致无法用鼠标选择列表项。
         *
         * @param e 鼠标移动事件
         * @return 仍在区域内返回 true
         */
        boolean isInsideBranchHoverArea(MouseEvent e) {
            if (isInsideBounds(branchEditor, e, HOVER_MARGIN)) {
                return true;
            }
            return isBranchPopupVisible()
                    && isInsideBounds(popupContent, e, HOVER_MARGIN);
        }

        /**
         * 鼠标事件是否落在平台弹层内部。
         *
         * @param e 鼠标事件
         * @return 落在弹层内返回 true
         */
        boolean isInsideBranchPopup(MouseEvent e) {
            if (!isBranchPopupVisible() || Objects.isNull(popupContent)) {
                return false;
            }
            return SwingUtilities.isDescendingFrom(e.getComponent(), popupContent)
                    || isInsideBounds(popupContent, e, 0);
        }

        /** 点击箭头 / 输入框：展开或收起下拉弹层 */
        private void toggleBranchPopup() {
            if (isBranchPopupVisible()) {
                hideBranchPopup();
            } else {
                showBranchPopup();
            }
        }

        /**
         * 创建平台弹层：宽度取输入框实际宽度，使下拉列表左右边缘与输入框对齐。
         * 关闭行为统一由主弹窗的全局监听分层处理，故禁用平台自带的
         * 点外部/ESC 取消，避免同一次操作收掉两层。
         */
        private void initBranchPopup() {
            if (isBranchPopupVisible()) {
                return;
            }
            int width = Math.max(branchEditor.getWidth(), LIST_WIDTH);
            Dimension size = new Dimension(
                    width, LIST_VISIBLE_ROWS * LIST_CELL_HEIGHT + LIST_EXTRA_HEIGHT);
            branchScroll.setPreferredSize(size);
            branchScroll.setMinimumSize(size);
            branchScroll.setMaximumSize(size);
            branchScroll.setHorizontalScrollBarPolicy(
                    ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            branchScroll.setVerticalScrollBarPolicy(
                    ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
            JPanel content = new JPanel(new BorderLayout());
            content.add(branchScroll, BorderLayout.CENTER);
            content.setPreferredSize(size);
            content.revalidate();
            popupContent = content;

            ComponentPopupBuilder builder = JBPopupFactory.getInstance()
                    .createComponentPopupBuilder(content, null)
                    .setRequestFocus(false)
                    .setCancelOnClickOutside(false)
                    .setCancelKeyEnabled(false)
                    .setResizable(false);
            branchPopup = builder.createPopup();
            popupShown = false;
        }

        /**
         * 展开弹层并定位到输入框正下方。
         * 关键：先判「已显示」，再创建，最后 show() 并置 popupShown，
         * 不能像旧实现那样创建完立刻用可见性判断把自己 return 掉。
         */
        private void showBranchPopup() {
            if (isBranchPopupVisible() || !isShowing() || !branchEditor.isShowing()) {
                return;
            }
            // 上一次收起已销毁实例，这里重建；不影响任何 DialogWrapper 窗口
            initBranchPopup();
            if (Objects.isNull(branchPopup) || branchPopup.isDisposed()) {
                return;
            }
            branchPopup.show(new RelativePoint(
                    branchEditor, new Point(0, branchEditor.getHeight())));
            popupShown = true;
            // JBPopup.show() 之后 content 尚未完成布局，首次展开会是一片空白；
            // 显式 revalidate/repaint 让 JList 立即绘制已加载的分支数据。
            refreshPopupContent();
            diag("showBranchPopup model=" + branchListModel.getSize()
                    + " size=" + Math.max(branchEditor.getWidth(), LIST_WIDTH) + "x"
                    + (LIST_VISIBLE_ROWS * LIST_CELL_HEIGHT + LIST_EXTRA_HEIGHT));
        }

        /** 刷新弹层内容，避免数据异步到达后停留在空白 */
        private void refreshPopupContent() {
            if (Objects.nonNull(popupContent)) {
                popupContent.revalidate();
                popupContent.repaint();
                branchList.repaint();
            }
        }

        /**
         * 收起下拉弹层：只销毁弹层列表对象，
         * 绝不 cancel/dispose 任何 DialogWrapper（子弹窗、主弹窗保持原样）。
         * 平台弹层在当前主题下是轻量组件，隐藏窗口复用不可靠，故直接销毁，
         * 下次展开重建一个即可，语义仍是"收起"而非"关闭窗口"。
         */
        void hideBranchPopup() {
            if (Objects.isNull(branchPopup)) {
                return;
            }
            disposeBranchPopup();
            diag("collapseBranchPopup (popup only, windows kept)");
        }

        /** 销毁弹层实例（仅弹层，不涉及任何窗口） */
        private void disposeBranchPopup() {
            if (Objects.nonNull(branchPopup) && !branchPopup.isDisposed()) {
                branchPopup.cancel();
            }
            branchPopup = null;
            popupContent = null;
            popupShown = false;
        }

        /** 释放时注销弹窗、销毁弹层、取消去抖任务 */
        @Override
        protected void dispose() {
            unregisterBranchDialog(this);
            cancelFilterDebounce();
            disposeBranchPopup();
            super.dispose();
        }

        /**
         * 构建弹窗内容：原生外观输入框（内部右侧带下拉箭头图标），
         * 新建分支时额外显示名称输入框；分支列表放在平台下拉弹层里。
         *
         * @return 内容面板
         */
        @Nullable
        @Override
        protected JComponent createCenterPanel() {
            // 平台输入框默认左边距过小，第一个字符会被焦点边框压住；
            // 用 compound border 保留原边框并补左侧内边距。
            // 占位提示 paintComponent 按 getInsets().left 绘制，会自动同步对齐。
            javax.swing.border.Border outer = branchEditor.getBorder();
            javax.swing.border.Border inset = BorderFactory.createEmptyBorder(
                    0, EDITOR_LEFT_INSET, 0, 0);
            branchEditor.setBorder(Objects.isNull(outer) ? inset
                    : BorderFactory.createCompoundBorder(outer, inset));

            JPanel panel = new JPanel(new GridBagLayout());
            GridBagConstraints gc = new GridBagConstraints();
            gc.insets = new Insets(6, 6, 6, 6);

            gc.gridy = 0;
            gc.gridx = 0;
            gc.anchor = GridBagConstraints.WEST;
            panel.add(new JLabel(withName ? "基础分支:" : "分支:"), gc);
            gc.gridx = 1;
            gc.weightx = 1.0;
            gc.fill = GridBagConstraints.HORIZONTAL;
            panel.add(branchEditor, gc);

            if (withName) {
                gc.gridy = 1;
                gc.gridx = 0;
                gc.weightx = 0.0;
                gc.fill = GridBagConstraints.NONE;
                panel.add(new JLabel("新分支名:"), gc);
                gc.gridx = 1;
                gc.weightx = 1.0;
                gc.fill = GridBagConstraints.HORIZONTAL;
                panel.add(nameField, gc);
            }
            int height = withName ? PANEL_HEIGHT_WITH_NAME : PANEL_HEIGHT;
            panel.setPreferredSize(new Dimension(LIST_WIDTH + PANEL_EXTRA_WIDTH, height));
            return panel;
        }

        /** 初始化：新分支名变化时刷新确认按钮状态 */
        @Override
        protected void init() {
            super.init();
            nameField.getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent e) {
                    updateOkState();
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    updateOkState();
                }

                @Override
                public void changedUpdate(DocumentEvent e) {
                    updateOkState();
                }
            });
            setOKActionEnabled(false);
        }

        /** 刷新确认按钮：必须有基础分支；新建场景还必须有新分支名 */
        private void updateOkState() {
            boolean branchOk = StrUtil.isNotBlank(getBranch());
            boolean nameOk = !withName || StrUtil.isNotBlank(getNewBranchName());
            setOKActionEnabled(branchOk && nameOk);
        }

        /**
         * 分支列表加载：缓存命中时纯内存填充；未命中时直接读 .git/refs
         * 与 packed-refs（纯文件、毫秒级），在 EDT 上同步填充后即可显示，
         * 不再把首次显示寄托于后台 git 进程 —— 那会造成"首次打开十几秒空白"
         * （日志证据：showBranchPopup model=0，1.5 秒后才 applyBranches）。
         * fetch 只用于后台补齐远程新建分支，不参与首次显示。
         */
        private void ensureBranchesLoaded() {
            if (branchesRequested) {
                return;
            }
            branchesRequested = true;
            List<String> cached = getCachedBranches(repo);
            if (Objects.nonNull(cached) && CollectionUtils.isNotEmpty(cached)) {
                applyBranches(cached);
                if (!isCacheFresh(repo)) {
                    refreshBranchesAsync();
                }
                return;
            }
            List<String> branches = readBranchesFromGitDir(repo);
            if (CollectionUtils.isEmpty(branches)) {
                branches = readAllBranches();
            }
            if (CollectionUtils.isEmpty(branches)) {
                branches = new ArrayList<>();
                branches.add("master");
            }
            putCachedBranches(repo, branches);
            applyBranches(branches);
            refreshBranchesAsync();
        }

        /**
         * 从 .git 目录直接读取本地与远程分支引用，不启动 git 进程。
         *
         * @param repo 仓库路径
         * @return 分支名列表，去重并保持顺序
         */
        private List<String> readBranchesFromGitDir(Path repo) {
            Set<String> all = new LinkedHashSet<>();
            Path gitDir = repo.resolve(GIT_DIR);
            if (!Files.isDirectory(gitDir)) {
                return new ArrayList<>();
            }
            Path refs = gitDir.resolve(GIT_REFS_DIR);
            collectRefs(refs.resolve(GIT_LOCAL_REFS), all);
            collectRefs(refs.resolve(GIT_REMOTE_REFS), all);
            readPackedRefs(gitDir.resolve(GIT_PACKED_REFS), all);
            return new ArrayList<>(all);
        }

        /**
         * 递归收集引用目录下的分支名。
         *
         * @param dir 引用目录
         * @param out 结果集合
         */
        private void collectRefs(Path dir, Set<String> out) {
            if (!Files.isDirectory(dir)) {
                return;
            }
            try (Stream<Path> paths = Files.walk(dir)) {
                paths.filter(Files::isRegularFile).forEach(path -> {
                    String rel = dir.relativize(path).toString()
                            .replace(java.io.File.separatorChar, '/');
                    if (StrUtil.isNotBlank(rel)) {
                        out.add(rel);
                    }
                });
            } catch (IOException ex) {
                diag("collectRefs failed=" + ex.getMessage());
            }
        }

        /**
         * 读取 packed-refs 中的 heads/remotes 引用（松散 refs 里没有的部分）。
         *
         * @param packed packed-refs 文件
         * @param out    结果集合
         */
        private void readPackedRefs(Path packed, Set<String> out) {
            if (!Files.isRegularFile(packed)) {
                return;
            }
            try {
                for (String line : Files.readAllLines(packed)) {
                    String trimmed = line.trim();
                    if (StrUtil.isBlank(trimmed) || trimmed.startsWith("#")
                            || trimmed.startsWith(PEELED_LINE_PREFIX)) {
                        continue;
                    }
                    String[] parts = trimmed.split(PACKED_REF_SEPARATOR);
                    if (parts.length <= PACKED_REF_PATH_INDEX) {
                        continue;
                    }
                    String ref = parts[PACKED_REF_PATH_INDEX];
                    if (ref.startsWith(REF_PREFIX_HEADS)) {
                        out.add(ref.substring(REF_PREFIX_HEADS.length()));
                    } else if (ref.startsWith(REF_PREFIX_REMOTES)) {
                        out.add(ref.substring(REF_PREFIX_REMOTES.length()));
                    } else if (!ref.startsWith(REF_PREFIX_TAGS)) {
                        out.add(ref);
                    }
                }
            } catch (IOException ex) {
                diag("readPackedRefs failed=" + ex.getMessage());
            }
        }

        /**
         * 用给定分支列表替换已加载数据并重建列表。
         *
         * @param branches 分支列表
         */
        private void applyBranches(List<String> branches) {
            diag("applyBranches in size=" + branches.size());
            synchronized (loadedBranches) {
                loadedBranches.clear();
                loadedBranches.addAll(branches);
            }
            updateBranchList(false);
        }

        /**
         * 按关键字筛选分支，去重且保持原顺序。
         *
         * @param snapshot 分支快照
         * @param keyword  小写关键字，空串表示不过滤
         * @return 期望显示的分支列表
         */
        private List<String> buildExpectedList(List<String> snapshot, String keyword) {
            LinkedHashSet<String> expected = new LinkedHashSet<>();
            // 当前分支放最前面
            String current = Objects.isNull(currentBranchHint) ? "" : currentBranchHint.trim();
            if (StrUtil.isNotBlank(current) && snapshot.contains(current)
                    && (StrUtil.isBlank(keyword) || current.toLowerCase().contains(keyword))) {
                expected.add(current);
            }
            for (String branch : snapshot) {
                if (branch.equals(current)) {
                    continue;
                }
                if (StrUtil.isBlank(keyword) || branch.toLowerCase().contains(keyword)) {
                    expected.add(branch);
                }
            }
            return new ArrayList<>(expected);
        }

        /**
         * 按关键字过滤并重建列表；只改数据，弹层尺寸恒定，
         * 不存在原生 ComboBox 的 viewport 定格问题。
         *
         * @param filterByInput 是否按输入框内容过滤
         */
        private void updateBranchList(boolean filterByInput) {
            List<String> snapshot;
            synchronized (loadedBranches) {
                snapshot = new ArrayList<>(loadedBranches);
            }
            String keyword = (filterByInput && Objects.isNull(selectedBranch))
                    ? branchEditor.getText().trim().toLowerCase() : "";
            List<String> expected = buildExpectedList(snapshot, keyword);
            branchListModel.clear();
            for (String branch : expected) {
                branchListModel.addElement(branch);
            }
            branchList.clearSelection();
            diag("updateBranchList keyword=[" + keyword + "] shown=" + expected.size()
                    + " loaded=" + snapshot.size());
            // 分支数据可能异步到达；弹层已展开时刷新内容，避免停留在空白
            if (isBranchPopupVisible() && Objects.nonNull(popupContent)) {
                popupContent.revalidate();
                popupContent.repaint();
                branchList.repaint();
            }
        }

        /**
         * 左键点选列表项：唯一回填路径，回填后收起弹层并恢复全量列表。
         *
         * @param selected 选中的分支名
         */
        private void chooseSelectedBranch(String selected) {
            if (Objects.isNull(selected)) {
                return;
            }
            diag("chooseSelectedBranch selected=" + selected);
            selectedBranch = selected;
            cancelFilterDebounce();
            hideBranchPopup();
            ignoreDocEvents = true;
            try {
                branchEditor.setText(selected);
                branchEditor.setCaretPosition(selected.length());
            } finally {
                ignoreDocEvents = false;
            }
            updateEditorExtras();
            updateBranchList(false);
            updateOkState();
            branchEditor.requestFocusInWindow();
        }

        /** 确认：回调上层执行切换/新建，随后关闭本弹窗 */
        @Override
        protected void doOKAction() {
            String branch = getBranch();
            diag("doOKAction branch=" + branch);
            try {
                if (Objects.nonNull(onConfirm)) {
                    onConfirm.accept(branch, getNewBranchName());
                }
            } catch (Exception ignored) {
                // 回调异常不打断弹窗关闭流程
            } finally {
                hideBranchPopup();
                unregisterBranchDialog(this);
                if (isShowing()) {
                    close(OK_EXIT_CODE);
                }
            }
        }

        /** 后台 fetch 后刷新列表，远程新建分支才会出现在 origin/xxx 中 */
        private void refreshBranchesAsync() {
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                execGitQuiet("fetch", "origin", "--prune");
                List<String> latest = readAllBranches();
                diag("refreshBranchesAsync fetched size=" + latest.size());
                if (CollectionUtils.isEmpty(latest)) {
                    return;
                }
                putCachedBranches(repo, latest);
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!isShowing()) {
                        return;
                    }
                    synchronized (loadedBranches) {
                        loadedBranches.clear();
                        loadedBranches.addAll(latest);
                    }
                    updateBranchList(StrUtil.isNotBlank(branchEditor.getText()));
                }, ModalityState.any());
            });
        }

        /**
         * 读取仓库全部分支（本地 + 远程），去重并保持顺序。
         *
         * @return 分支名列表
         */
        private List<String> readAllBranches() {
            Set<String> all = new LinkedHashSet<>();
            String out = GitHelperSwitcher.execGitForOutput(repo, "branch", "-a");
            for (String raw : out.split("\\n")) {
                String name = raw.trim();
                if (name.startsWith("* ") || name.startsWith("+ ")) {
                    name = name.substring(2);
                }
                if (StrUtil.isBlank(name) || name.contains("->")) {
                    continue;
                }
                if (name.startsWith("remotes/")) {
                    name = name.substring("remotes/".length());
                }
                all.add(name);
            }
            return new ArrayList<>(all);
        }

        /**
         * 静默执行 git 命令，忽略输出与错误。
         *
         * @param args git 参数
         */
        private void execGitQuiet(String... args) {
            GitHelperSwitcher.execGitForOutput(repo, args);
        }
    }

    /**
     * 工具窗口嵌入模式下，内容面板只返回主面板，不含平台默认的 OK/Cancel 行。
     *
     * @return 主面板
     */
    @Nullable
    @Override
    protected JComponent createCenterPanel() {
        return mainPanel;
    }

    /**
     * 诊断日志入口（当前为空实现，不再打印任何日志）。
     *
     * @param msg 诊断信息
     */
    @SuppressWarnings("unused")
    public static void diag(String msg) {
        // 日志开关已关闭，保留入口以兼容既有调用点
    }
}
