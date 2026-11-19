package com.childeshu.githelper;

import cn.hutool.core.util.StrUtil;
import com.intellij.ide.util.PropertiesComponent;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Git 助手配置：批量操作的并行设置，以及多项目搜索分隔符。
 * <p>
 * 配置双写：先写内存（保证保存后立即生效），再写入 IDE 的 PropertiesComponent（重启后依然生效）。
 *
 * @author childe shu
 */
public final class GitHelperSettings {

    /**
     * 配置存储的 PropertiesComponent 名称
     */
    private static final String STORE_NAME = "GitHelper";

    /**
     * 是否启用多进程并行
     */
    private static final String KEY_PARALLEL_ENABLED = "parallel.enabled";

    /**
     * 并行进程数
     */
    private static final String KEY_PARALLEL_THREADS = "parallel.threads";

    /**
     * 多项目搜索的分隔符字符集合
     */
    private static final String KEY_SEARCH_SEPARATORS = "search.separators";

    /**
     * 工作区根目录
     */
    private static final String KEY_WORKSPACE_ROOT = "workspace.root";

    /**
     * 默认分隔符：逗号、顿号、斜杠、竖线；空白字符由代码额外追加，
     * 组合后等价于旧的正则 [,，、/|\s]+
     */
    public static final String DEFAULT_SEARCH_SEPARATORS = ",，、/|";

    /**
     * 默认并行进程数
     */
    public static final int DEFAULT_THREADS = 8;

    /**
     * 并行进程数上限
     */
    public static final int MAX_THREADS = 32;

    /**
     * 内存缓存：是否启用多进程并行（null 表示本次会话尚未设置过，读持久化值）
     */
    private static Boolean cachedEnabled;

    /**
     * 内存缓存：并行进程数
     */
    private static Integer cachedThreads;

    /**
     * 内存缓存：搜索分隔符字符集合
     */
    private static String cachedSeparators;

    /**
     * 内存缓存：工作区根目录（null 表示本次会话尚未设置过，读持久化值）
     */
    private static String cachedWorkspaceRoot;

    private GitHelperSettings() {
    }

    /**
     * 是否启用多进程并行执行
     */
    public static boolean isParallelEnabled() {
        if (Objects.nonNull(cachedEnabled)) {
            return cachedEnabled;
        }
        return Boolean.parseBoolean(getProperty(KEY_PARALLEL_ENABLED, "false"));
    }

    /**
     * 保存是否启用多进程并行执行
     */
    public static void setParallelEnabled(boolean enabled) {
        cachedEnabled = enabled;
        setProperty(KEY_PARALLEL_ENABLED, String.valueOf(enabled));
    }

    /**
     * 并行进程数（未启用时返回 1，表示串行）
     */
    public static int getParallelThreads() {
        if (!isParallelEnabled()) {
            return 1;
        }
        return limit(Objects.requireNonNullElseGet(cachedThreads, () -> parseInt(getProperty(KEY_PARALLEL_THREADS, ""))));
    }

    /**
     * 保存并行进程数
     */
    public static void setParallelThreads(int threads) {
        int value = limit(threads);
        cachedThreads = value;
        setProperty(KEY_PARALLEL_THREADS, String.valueOf(value));
    }

    /**
     * 读取当前搜索分隔符字符集合。
     *
     * @return 分隔符字符集合，未配置时返回默认值
     */
    public static String getSearchSeparators() {
        if (Objects.nonNull(cachedSeparators)) {
            return cachedSeparators;
        }
        return getProperty(KEY_SEARCH_SEPARATORS, DEFAULT_SEARCH_SEPARATORS);
    }

    /**
     * 保存搜索分隔符；留空表示恢复默认值。
     *
     * @param separators 分隔符字符集合
     */
    public static void setSearchSeparators(String separators) {
        String value = StrUtil.isBlank(separators)
                ? DEFAULT_SEARCH_SEPARATORS : separators.trim();
        cachedSeparators = value;
        setProperty(KEY_SEARCH_SEPARATORS, value);
    }

    /**
     * 读取用户保存的工作区根目录。
     *
     * @return 已保存的根目录，未配置时返回空字符串
     */
    public static String getWorkspaceRoot() {
        if (Objects.nonNull(cachedWorkspaceRoot)) {
            return cachedWorkspaceRoot;
        }
        return getProperty(KEY_WORKSPACE_ROOT, "");
    }

    /**
     * 保存工作区根目录；留空表示恢复默认（当前项目目录）。
     *
     * @param root 工作区根目录
     */
    public static void setWorkspaceRoot(String root) {
        String value = StrUtil.isBlank(root) ? "" : root.trim();
        cachedWorkspaceRoot = value;
        setProperty(KEY_WORKSPACE_ROOT, value);
    }

    /**
     * 构造用于 String.split 的分隔符正则。
     * <p>
     * 配置的每个字符都用 {@link Pattern#quote} 包成 \Q...\E 按字面匹配，
     * 再用 | 连成多选分支，末尾追加 \s+ 保证空白始终能分隔关键字。
     * 相比手工拼正则字符类，这种方式对任意用户配置都安全：
     * 点号、星号、问号、括号、方括号、花括号、脱字符、美元符、竖线、
     * 反斜杠、与号以及双与号（字符类交集语法）等全部按普通字符处理，
     * 无需维护转义白名单，也不会产生非法正则。
     *
     * @return 分隔符正则
     */
    public static String buildSearchSeparatorRegex() {
        String chars = getSearchSeparators();
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < chars.length(); i++) {
            if (!regex.isEmpty()) {
                regex.append('|');
            }
            regex.append(Pattern.quote(String.valueOf(chars.charAt(i))));
        }
        if (!regex.isEmpty()) {
            regex.append('|');
        }
        regex.append("\\s+");
        return regex.toString();
    }

    /**
     * 限制进程数范围
     */
    private static int limit(int threads) {
        return Math.max(1, Math.min(threads, MAX_THREADS));
    }

    /**
     * 生成实际存储键。
     * <p>
     * 不能用 getValue(STORE_NAME, key)：该两参重载是 (name, defaultValue)，
     * 会把 STORE_NAME 当成属性名、把 key 当成默认值；三参 setValue 同理。
     * 旧实现导致所有配置都写进名为 "GitHelper" 的同一个属性并互相覆盖，
     * 界面上甚至会读出 "parallel.threads" 这种键名字符串。
     * 现在每个配置项用 "GitHelper.<key>" 作为唯一属性名。
     *
     * @param key 配置项短名
     * @return 实际存储键
     */
    private static String storageKey(String key) {
        return STORE_NAME + "." + key;
    }

    /**
     * 读取配置项：不存在或为空白时返回默认值。
     *
     * @param key          配置项短名
     * @param defaultValue 默认值
     * @return 配置值
     */
    private static String getProperty(String key, String defaultValue) {
        String value = PropertiesComponent.getInstance()
                .getValue(storageKey(key), defaultValue);
        return StrUtil.isBlank(value) ? defaultValue : value;
    }

    /**
     * 写入配置项。
     *
     * @param key   配置项短名
     * @param value 配置值
     */
    private static void setProperty(String key, String value) {
        PropertiesComponent.getInstance().setValue(storageKey(key), value);
    }

    /**
     * 解析整数，失败时返回默认进程数
     */
    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_THREADS;
        }
    }

    /**
     * 当前生效的并行进程数，供切换器直接使用
     */
    public static int effectiveThreads() {
        return getParallelThreads();
    }

    /**
     * 配置摘要，用于日志提示：实际并行数不超过仓库数
     *
     * @param repoCount 本次操作的仓库数量
     */
    public static String describe(int repoCount) {
        int threads = Math.min(repoCount, effectiveThreads());
        if (threads <= 1) {
            return "串行执行";
        }
        return "并行执行（" + threads + " 进程）";
    }
}
