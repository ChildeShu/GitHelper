package com.childeshu.githelper;

import cn.hutool.core.util.StrUtil;
import com.intellij.openapi.progress.ProgressIndicator;
import org.apache.commons.collections4.CollectionUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 核心逻辑：
 * 1. 切换分支：备份本地改动 -> 切目标分支 -> 远程为准强制同步 -> 还原本地改动
 * 2. 新建分支：备份本地改动 -> 切源分支并同步远程 -> 基于源分支新建目标分支 -> 还原本地改动
 * 3. 同步远程：fetch 远程最新 + rebase（本地改动优先保留）
 * 4. 推送远程：本地当前分支已提交内容批量 push
 *
 * @author childe shu
 */
public class GitHelperSwitcher {

    /**
     * 批量操作的最大并行仓库数：并发太多会让磁盘/网络成为瓶颈，取适中值
     */
    private static final int MAX_PARALLEL = 8;

    /**
     * 单条 git 命令超时（秒）：网络异常或凭据弹窗时强制终止，
     * 避免后台任务永久挂起、界面一直停在"加载中..."
     */
    private static final int GIT_TIMEOUT_SECONDS = 45;

    /** 默认远程仓库名 */
    private static final String DEFAULT_REMOTE = "origin";

    /** Git 远程分支引用前缀 */
    private static final String REMOTE_REF_PREFIX = "refs/remotes/";

    /** detached HEAD 的输出，无法确定当前分支 */
    private static final String DETACHED_HEAD = "HEAD";

    /**
     * 等待输出读取线程收尾的秒数
     */
    private static final int OUTPUT_JOIN_SECONDS = 2;

    /**
     * 并行进程数：1 表示串行执行（不启用多进程）
     */
    private final int parallelThreads;

    /**
     * 默认构造：串行执行
     */
    public GitHelperSwitcher() {
        this(1);
    }

    /**
     * 指定并行进程数，1 表示串行
     */
    public GitHelperSwitcher(int parallelThreads) {
        this.parallelThreads = Math.max(1, parallelThreads);
    }

    /**
     * 批量切换到目标分支
     */
    public List<String> switchBranches(@NotNull List<Path> repos, @NotNull String branch,
                                       @NotNull ProgressIndicator indicator) {
        return runBatch(repos, indicator, (repo, backupRoot) ->
                doSwitch(repo, branch, backupRoot.resolve(repo.getFileName().toString())));
    }

    /**
     * 基于源分支批量新建目标分支
     */
    public List<String> createBranches(@NotNull List<Path> repos, @NotNull String source,
                                       @NotNull String target,
                                       @NotNull ProgressIndicator indicator) {
        return runBatch(repos, indicator, (repo, backupRoot) ->
                doCreate(repo, source, target, backupRoot.resolve(repo.getFileName().toString())));
    }

    /**
     * 批量处理公共骨架：按配置串行或并行执行，含进度展示、异常兜底、用户取消、备份目录提示。
     * 各仓库之间互不影响，git 命令在独立工作目录执行，并行安全。
     */
    private List<String> runBatch(@NotNull List<Path> repos, @NotNull ProgressIndicator indicator,
                                  @NotNull RepoWorker worker) {
        Path backupRoot = createBackupRoot();
        List<String> logs = new ArrayList<>();
        if (CollectionUtils.isEmpty(repos)) {
            logs.add("本地原文件备份目录: " + backupRoot);
            return logs;
        }
        if (parallelThreads <= 1) {
            runSerial(repos, indicator, worker, backupRoot, logs);
        } else {
            runParallel(repos, indicator, worker, backupRoot, logs);
        }
        logs.add("本地原文件备份目录: " + backupRoot);
        return logs;
    }

    /**
     * 串行执行：逐个仓库处理
     */
    private void runSerial(@NotNull List<Path> repos, @NotNull ProgressIndicator indicator,
                           @NotNull RepoWorker worker, @NotNull Path backupRoot,
                           @NotNull List<String> logs) {
        int total = repos.size();
        int idx = 0;
        for (Path repo : repos) {
            idx++;
            indicator.setText("(" + idx + "/" + total + ") " + repo.getFileName());
            indicator.setFraction((double) idx / total);
            logs.add(runOne(repo, backupRoot, worker));
            if (indicator.isCanceled()) {
                logs.add("用户取消，已处理 " + idx + "/" + total);
                return;
            }
        }
    }

    /**
     * 并行执行：多仓库同时处理，最多 parallelThreads 个并发
     */
    private void runParallel(@NotNull List<Path> repos, @NotNull ProgressIndicator indicator,
                             @NotNull RepoWorker worker, @NotNull Path backupRoot,
                             @NotNull List<String> logs) {
        List<Path> repoList = new ArrayList<>(repos);
        int total = repoList.size();
        int parallel = Math.min(total, Math.min(parallelThreads, MAX_PARALLEL));
        ExecutorService pool = Executors.newFixedThreadPool(parallel);
        try {
            List<Future<String>> futures = new ArrayList<>(total);
            for (Path repo : repoList) {
                futures.add(pool.submit(() -> runOne(repo, backupRoot, worker)));
            }
            AtomicInteger completed = new AtomicInteger();
            for (int i = 0; i < futures.size(); i++) {
                Future<String> future = futures.get(i);
                String log;
                try {
                    log = future.get();
                } catch (Exception ex) {
                    log = "[" + repoList.get(i).getFileName() + "] 失败: " + ex.getMessage();
                }
                logs.add(log);
                int done = completed.incrementAndGet();
                indicator.setText("(" + done + "/" + total + ") 已完成");
                if (indicator.isCanceled()) {
                    pool.shutdownNow();
                    logs.add("用户取消，已处理 " + done + "/" + total);
                    return;
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 单仓库处理：异常兜底为失败日志
     */
    private String runOne(@NotNull Path repo, @NotNull Path backupRoot, @NotNull RepoWorker worker) {
        try {
            return "[" + repo.getFileName() + "] " + worker.run(repo, backupRoot);
        } catch (Exception ex) {
            String msg = ex.getMessage();
            return "[" + repo.getFileName() + "] 失败: " + (StrUtil.isBlank(msg) ? ex.toString() : msg);
        }
    }

    /**
     * 批量同步远程：fetch 全部分支引用，并用 autostash 将当前分支
     * rebase 到远程同名分支，保留未提交改动。
     */
    public List<String> syncFromRemote(@NotNull List<Path> repos, @NotNull ProgressIndicator indicator) {
        return runBatch(repos, indicator, (repo, backupRoot) -> {
            String branch = currentBranch(repo);
            String name = Objects.isNull(repo.getFileName()) ? "" : repo.getFileName().toString();
            indicator.setText("同步 " + name + "：fetch origin");
            int fetchRc = execGitQuiet(repo, "fetch", DEFAULT_REMOTE, "--prune");
            if (fetchRc != 0) {
                throw new RuntimeException("同步 " + branch + " 失败：fetch origin 失败，"
                        + "请检查远程地址、网络或权限");
            }
            String remoteBranch = DEFAULT_REMOTE + "/" + branch;
            String remoteRef = REMOTE_REF_PREFIX + remoteBranch;
            boolean remoteExists = execGitQuiet(repo, "show-ref", "--verify", "--quiet",
                    remoteRef) == 0;
            if (!remoteExists) {
                return "已同步远程引用，当前分支 " + branch + " 没有对应的 origin 分支";
            }
            indicator.setText("同步 " + name + "：rebase " + remoteBranch);
            int rc = execGitQuiet(repo, "rebase", "--autostash", remoteBranch);
            if (rc != 0) {
                execGitQuiet(repo, "rebase", "--abort");
                throw new RuntimeException("同步 " + branch + " 失败：rebase 冲突，已中止，"
                        + "请手动处理");
            }
            return "已同步当前分支 " + branch + "，并更新远程引用";
        });
    }

    /**
     * 批量推送：将本地当前分支已提交的内容推送到远程同名分支，
     * 新分支会通过 --set-upstream 自动建立远程分支与跟踪关系。
     */
    public List<String> pushToRemote(@NotNull List<Path> repos, @NotNull ProgressIndicator indicator) {
        return runBatch(repos, indicator, (repo, backupRoot) -> {
            String branch = currentBranch(repo);
            String name = Objects.isNull(repo.getFileName()) ? "" : repo.getFileName().toString();
            indicator.setText("推送 " + name + "：" + branch);
            int rc = execGitQuiet(repo, "push", "--set-upstream", DEFAULT_REMOTE, branch);
            if (rc != 0) {
                throw new RuntimeException("推送 " + branch + " 失败：远程拒绝或网络/权限异常，"
                        + "请先同步远程并确认已提交代码");
            }
            if (hasUncommittedChanges(repo)) {
                return "已推送 " + branch + "；工作区仍有未提交改动，未包含在本次推送中";
            }
            return "已推送 " + branch;
        });
    }

    /**
     * 是否存在未提交的已跟踪文件改动，未跟踪文件不参与 push，故不计入。
     *
     * @param repo 仓库路径
     * @return 存在未提交改动返回 true
     */
    private boolean hasUncommittedChanges(@NotNull Path repo) {
        return StrUtil.isNotBlank(execGitCapture(repo, "status", "--porcelain",
                "--untracked-files=no"));
    }

    /**
     * 读取仓库当前分支名
     */
    private String currentBranch(@NotNull Path repo) {
        String out = execGitCapture(repo, "rev-parse", "--abbrev-ref", "HEAD").trim();
        if (StrUtil.isBlank(out) || DETACHED_HEAD.equals(out)) {
            throw new RuntimeException("无法识别当前分支（detached HEAD 不支持同步/推送）");
        }
        return out;
    }

    private Path createBackupRoot() {
        String stamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path backupRoot = Path.of(System.getProperty("user.home"), ".git-helper-backup", stamp);
        try {
            Files.createDirectories(backupRoot);
        } catch (IOException e) {
            throw new RuntimeException("无法创建备份目录: " + e.getMessage(), e);
        }
        return backupRoot;
    }

    private String doSwitch(@NotNull Path repo, @NotNull String branch,
                            @NotNull Path backupRepo) throws IOException {
        return doSwitchOrCreate(repo, branch, null, backupRepo);
    }

    private String doCreate(@NotNull Path repo, @NotNull String source,
                            @NotNull String target, @NotNull Path backupRepo) throws IOException {
        return doSwitchOrCreate(repo, source, target, backupRepo);
    }

    /**
     * 统一处理：备份 -> 强制同步源分支 -> （可选）新建目标分支 -> 还原本地改动。
     * target 为空表示仅切换到 source 分支，否则基于 source 新建 target 分支。
     */
    private String doSwitchOrCreate(@NotNull Path repo, @NotNull String source,
                                    @Nullable String target, @NotNull Path backupRepo)
            throws IOException {
        long t0 = System.currentTimeMillis();
        String name = Objects.isNull(repo.getFileName())
                ? "" : repo.getFileName().toString();
        boolean hasBackup = backupLocalChanges(repo, backupRepo);
        GitHelperDialog.diag("switch backup done " + (System.currentTimeMillis() - t0)
                + "ms hasBackup=" + hasBackup + " repo=" + name);
        forceSyncBranch(repo, source);
        GitHelperDialog.diag("switch sync done " + (System.currentTimeMillis() - t0)
                + "ms repo=" + name);
        String msg;
        if (Objects.isNull(target)) {
            msg = "已同步到 " + localBranchName(source);
        } else {
            // 目标分支是本地新分支：基于当前 HEAD（已同步到 source）创建
            createBranch(repo, target);
            msg = "已基于 " + localBranchName(source) + " 新建 " + target;
        }
        String result = finishWithBackup(repo, backupRepo, hasBackup, msg);
        GitHelperDialog.diag("switch all done " + (System.currentTimeMillis() - t0)
                + "ms repo=" + name);
        return result;
    }

    /**
     * 新建本地分支并切换过去（目标分支已存在则直接切换）
     */
    private void createBranch(@NotNull Path repo, @NotNull String target) {
        boolean exists = execGitQuiet(repo, "show-ref", "--verify", "--quiet",
                "refs/heads/" + target) == 0;
        if (exists) {
            execGit(repo, "checkout", target);
            return;
        }
        execGit(repo, "checkout", "-b", target);
    }

    /**
     * 切换到指定分支（用于切换/新建前的"源分支对齐"）：
     * - 本地已存在该分支：直接 checkout（保留本地进度，不强制同步远程）。
     * 这样基于"本地创建、尚未推送远程"的分支新建分支时不会因远程缺失而报错。
     * - 本地不存在该分支：从远程拉取后 checkout -b，再硬重置到远程为准。
     */
    private void forceSyncBranch(@NotNull Path repo, @NotNull String branch) {
        String name = localBranchName(branch);
        boolean localExists = execGitQuiet(repo, "show-ref", "--verify", "--quiet",
                "refs/heads/" + name) == 0;
        // 丢弃已跟踪文件的工作区/暂存区改动（备份已在调用前完成）。
        // 不再执行 clean -fd：未跟踪文件本来就不参与 checkout，删了又要从备份全量拷回，
        // 在 IDEA 项目里 build/target/.idea 等目录可达数万文件，是切换耗时几十秒的主因。
        execGit(repo, "reset", "--hard", "HEAD");
        if (localExists) {
            // 本地已存在：直接切换过去即可，无需触碰远程
            execGit(repo, "checkout", name);
            return;
        }
        // 本地不存在：从远程拉取并强制同步
        // 显式 fetch 目标分支，失败立即抛错（不再静默吞掉），让用户看到真实原因
        int frc = execGitQuiet(repo, "fetch", "origin", name);
        if (frc != 0) {
            throw new RuntimeException("获取远程分支 " + name + " 失败（远程可能不存在该分支，或网络/权限异常），跳过");
        }
        // 起点用 FETCH_HEAD：与分支名无关，避免含 "/" 或中文的分支名被解析为路径
        // （ambiguous argument）。
        execGit(repo, "checkout", "-b", name, "FETCH_HEAD");
        execGit(repo, "reset", "--hard", "FETCH_HEAD");
    }

    /**
     * 取本地分支名：兼容用户直接选择远程分支（origin/xxx）的情况
     */
    private String localBranchName(@NotNull String branch) {
        String name = branch.trim();
        if (name.startsWith("origin/")) {
            return name.substring("origin/".length());
        }
        return name;
    }

    /**
     * 有备份则覆盖回本地修改文件，并返回带后缀的提示信息
     */
    private String finishWithBackup(@NotNull Path repo, @NotNull Path backupRepo,
                                    boolean hasBackup, @NotNull String baseMsg) throws IOException {
        if (hasBackup) {
            copyRecursive(backupRepo, repo);
            return baseMsg + "，并已覆盖本地修改文件";
        }
        return baseMsg;
    }

    private boolean backupLocalChanges(@NotNull Path repo, @NotNull Path backupRepo)
            throws IOException {
        boolean hasBackup = false;
        // --untracked-files=no：只备份已跟踪文件的改动。
        // 旧实现对 "?? build/" 这类未跟踪目录整目录 Files.walk 全量拷贝到 home 备份区，
        // 切完再 copyRecursive 全量拷回，两次大 IO 但净效果等于什么都没做。
        // 未跟踪文件不会被 reset/checkout 影响，无需备份。
        for (String line : execGitCapture(repo, "status", "--porcelain",
                "--untracked-files=no").lines().toList()) {
            if (line.length() < 4) {
                continue;
            }
            String status = line.substring(0, 2);
            // 已删除的文件不备份
            if (status.contains("D")) {
                continue;
            }
            String file = line.substring(3).trim();
            Path src = repo.resolve(file);
            if (Files.exists(src)) {
                Path dst = backupRepo.resolve(file);
                Files.createDirectories(dst.getParent());
                copyRecursive(src, dst);
                hasBackup = true;
            }
        }
        return hasBackup;
    }

    private void copyRecursive(@NotNull Path src, @NotNull Path dst) throws IOException {
        if (Files.isDirectory(src)) {
            try (Stream<Path> walk = Files.walk(src)) {
                for (Path p : walk.toList()) {
                    Path target = dst.resolve(src.relativize(p).toString());
                    if (Files.isDirectory(p)) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        } else {
            Files.createDirectories(dst.getParent());
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private int execGitQuiet(@NotNull Path dir, String... args) {
        return runGit(dir, args).code;
    }

    /**
     * 执行 git 命令，失败时抛出异常（携带 git 输出，便于定位问题）
     */
    private void execGit(@NotNull Path dir, String... args) {
        GitResult result = runGit(dir, args);
        if (result.code() != 0) {
            throw new RuntimeException("git " + String.join(" ", args) + " 执行失败: "
                    + firstLine(result.out()));
        }
    }

    /**
     * 取输出首行（非空时），用于错误提示
     */
    private String firstLine(@NotNull String out) {
        for (String line : out.split("\n")) {
            String t = line.trim();
            if (StrUtil.isNotBlank(t)) {
                return t;
            }
        }
        return "无输出";
    }

    /**
     * 在指定仓库执行 git 命令并返回合并后的输出（供 UI 层复用，避免重复实现）
     */
    public static String execGitForOutput(@NotNull Path dir, String... args) {
        return runGit(dir, args).out;
    }

    /**
     * 构建 git 进程：命令 + 工作目录 + 合并错误流
     */
    private static ProcessBuilder newGitProcess(@NotNull Path dir, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        Collections.addAll(cmd, args);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir.toFile());
        pb.redirectErrorStream(true);
        // 禁止 git / 凭据管理器弹交互式输入框：后台线程里没人能应答，
        // 进程会永久挂起，表现为切换分支一直"加载中..."
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        pb.environment().put("GCM_INTERACTIVE", "Never");
        return pb;
    }

    private String execGitCapture(@NotNull Path dir, String... args) {
        return runGit(dir, args).out;
    }

    /**
     * 启动 git 进程并读取所有输出，返回退出码与输出内容。
     * <p>
     * 旧实现直接 r.lines() 阻塞到进程 EOF，再调用无超时的 waitFor()；
     * git 一旦等待网络/凭据就永久卡住，UI 回调永远不执行。
     * 现在输出由独立线程读取，主线程带超时等待，超时强制杀进程并返回失败。
     * 同时显式按 UTF-8 解码：Windows 默认 GBK 会把中文分支名读成乱码。
     */
    private static GitResult runGit(@NotNull Path dir, String... args) {
        try {
            Process process = newGitProcess(dir, args).start();
            CompletableFuture<String> reader = CompletableFuture.supplyAsync(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(
                        process.getInputStream(), StandardCharsets.UTF_8))) {
                    return r.lines().collect(Collectors.joining("\n"));
                } catch (IOException e) {
                    return "";
                }
            });
            if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                String msg = "git 命令超时(" + GIT_TIMEOUT_SECONDS + "s): git "
                        + String.join(" ", args);
                GitHelperDialog.diag(msg);
                return new GitResult(-1, msg);
            }
            String out;
            try {
                out = reader.get(OUTPUT_JOIN_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                out = "";
            }
            return new GitResult(process.exitValue(), out);
        } catch (IOException e) {
            return new GitResult(-1, StrUtil.isBlank(e.getMessage()) ? "" : e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new GitResult(-1, "已中断");
        }
    }

    /**
     * git 执行结果：退出码与合并错误流后的标准输出
     */
    private record GitResult(int code, String out) {
    }

    /**
     * 单仓库处理任务
     */
    @FunctionalInterface
    private interface RepoWorker {
        String run(Path repo, Path backupRoot) throws Exception;
    }
}
