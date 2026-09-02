package com.aeibi.design.data.versions

import com.aeibi.design.data.projects.ProjectRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 版本快照的应用级入口：校验项目状态、串行化存储操作，供 UI 与未来的
 * Agent 构建循环共同使用。独立于聊天流与任何 Agent 框架（issue #36 原则）。
 *
 * git 后端的固有保障：提交/检出都是崩溃安全的落盘序列，恢复以新记录写入
 * 历史；项目删除时 `versions.git` 随项目目录一起消失，无需额外级联清理。
 */
class VersionSnapshotService(
    private val storage: VersionStorage,
    private val projectRepository: ProjectRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    // UI 与构建循环可能并发触发快照，git 索引不支持并发写；串行化最简单可靠。
    private val mutex = Mutex()

    suspend fun snapshots(projectId: String): List<VersionSnapshot> = withContext(ioDispatcher) {
        if (!isInitialized(projectId)) {
            emptyList()
        } else {
            mutex.withLock { storage.listVersions(projectId) }
        }
    }

    suspend fun createSnapshot(projectId: String, label: String, trigger: VersionTrigger = VersionTrigger.MANUAL) =
        withContext(ioDispatcher) {
            checkInitialized(projectId)
            mutex.withLock { storage.snapshot(projectId, label, trigger) }
        }

    suspend fun restore(projectId: String, snapshotId: String, label: String) = withContext(ioDispatcher) {
        checkInitialized(projectId)
        mutex.withLock {
            // 恢复前保护未提交改动：先落一条 PRE_RESTORE 快照再恢复，否则检出会直接
            // 覆盖未提交内容。该记录保留在历史里，未提交改动随时可通过恢复找回。
            if (storage.hasUncommittedChanges(projectId)) {
                storage.snapshot(projectId, PRE_RESTORE_LABEL, VersionTrigger.PRE_RESTORE)
            }
            storage.restore(projectId, snapshotId, label)
        }
    }

    /**
     * 项目初始化完成后补一条 INIT 快照；未初始化或已有版本时为 no-op。
     * 初始化流程的关键路径不应被快照失败拖垮，调用方按 best-effort 处理。
     */
    suspend fun ensureInitialSnapshot(projectId: String) {
        withContext(ioDispatcher) {
            if (!isInitialized(projectId)) return@withContext
            mutex.withLock {
                if (storage.listVersions(projectId).isEmpty()) {
                    storage.snapshot(projectId, INITIAL_LABEL, VersionTrigger.INIT)
                }
            }
        }
    }

    /**
     * Agent 构建循环的挂钩点：每轮构建动作前调用（trigger=AUTO_BUILD）。
     *
     * 当前调用点尚未接线：真实构建循环在上游 PR #37（沙箱文件工具）/#38（Koog
     * Agent 会话）合入后于 PR-D 接线，届时构建执行器在每轮「执行」前调用本方法。
     * 这是恢复保护链路目前唯一缺失的一环，接口已就绪并有测试覆盖。
     */
    suspend fun snapshotBeforeBuildRound(projectId: String) =
        createSnapshot(projectId, BUILD_ROUND_LABEL, VersionTrigger.AUTO_BUILD)

    private suspend fun isInitialized(projectId: String): Boolean =
        projectRepository.getProject(projectId)?.isInitialized == true

    private suspend fun checkInitialized(projectId: String) {
        check(isInitialized(projectId)) { "项目不存在或尚未初始化: $projectId" }
    }

    private companion object {
        // 提交信息属于用户数据，暂以中文为默认文案；如需跟随应用语言再上提到调用方。
        const val INITIAL_LABEL = "初始版本"
        const val BUILD_ROUND_LABEL = "构建前快照"
        const val PRE_RESTORE_LABEL = "恢复前快照"
    }
}
