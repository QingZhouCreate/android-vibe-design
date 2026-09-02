package com.aeibi.design.data.projects

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 用 pending 目录整体替换工作区：先删旧目录，再原子移动。项目创建、模板初始化
 * 与版本恢复共用同一安全模式。移动失败时保底重建空工作区，数据仍在 git 历史
 * 与 pending 目录中，可再次恢复。
 */
internal fun replaceWorkspaceDirectory(projectDir: File, pendingWorkspace: File) {
    val workspace = File(projectDir, WORKSPACE_DIR)
    if (!workspace.deleteRecursively() && workspace.exists()) {
        throw IOException("Could not replace workspace: ${workspace.path}")
    }
    try {
        try {
            Files.move(pendingWorkspace.toPath(), workspace.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(pendingWorkspace.toPath(), workspace.toPath())
        }
    } catch (error: Exception) {
        workspace.mkdirs()
        throw error
    }
}
