package io.snyk.plugin

import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.*
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import io.snyk.plugin.services.SnykTaskQueueService
import io.snyk.plugin.snykcode.core.AnalysisData
import io.snyk.plugin.snykcode.core.RunUtils
import io.snyk.plugin.snykcode.core.SnykCodeUtils
import io.snyk.plugin.ui.toolwindow.SnykToolWindowPanel
import java.util.function.Predicate

class SnykBulkFileListener() : BulkFileListener {

    // proceed added and changed files
    override fun after(events: MutableList<out VFileEvent>) {
//        cleanCaches(
//            events, listOf(
//                VFileContentChangeEvent::class.java,
//                VFileMoveEvent::class.java,
//                VFileCopyEvent::class.java,
//                VFileCreateEvent::class.java
//            )
//        )
    }

    // proceed removed files
    override fun before(events: MutableList<out VFileEvent>) {
        cleanCaches(
            events, listOf(
//                VFileEvent::class.java
                VFileDeleteEvent::class.java,
                VFileContentChangeEvent::class.java,
                VFileMoveEvent::class.java,
                VFileCopyEvent::class.java
            )
        )
    }

    private fun cleanCaches(events: MutableList<out VFileEvent>, classesOfEventsToFilter: Collection<Class<*>>) {
        for (project in ProjectUtil.getOpenProjects()) {
            if (project.isDisposed) continue

            // clean CLI cached results
            val changedBuildFiles = getAffectedVirtualFiles(
                events,
                fileFilter = Predicate { supportedBuildFiles.contains(it.name) },
                classesOfEventsToFilter = classesOfEventsToFilter
            )
            if (changedBuildFiles.isNotEmpty()) {
                val toolWindowPanel = project.service<SnykToolWindowPanel>()
                toolWindowPanel.currentCliResults = null
            }

            val virtualFilesAffected = getAffectedVirtualFiles(
                events,
                fileFilter = Predicate { true },
                classesOfEventsToFilter = classesOfEventsToFilter
            )

            // if SnykCode analysis is running then re-run it (with updated files)
            val manager = PsiManager.getInstance(project)
            val supportedFileChanged = virtualFilesAffected
                .mapNotNull { manager.findFile(it) }
                .any { SnykCodeUtils.instance.isSupportedFileFormat(it) }
            val isSnykCodeRunning = AnalysisData.instance.isUpdateAnalysisInProgress(project)

            if (supportedFileChanged && isSnykCodeRunning) {
                RunUtils.instance.rescanInBackgroundCancellableDelayed(project, 0, false, false)
            }

            // remove changed files from SnykCode cache
            val allCachedFiles = AnalysisData.instance.getAllCachedFiles(project)
            val filesToRemoveFromCache = allCachedFiles
                .filter { cachedPsiFile ->
                    val vFile = (cachedPsiFile as PsiFile).virtualFile
                    vFile in virtualFilesAffected ||
                        // Directory containing cached file is deleted/moved
                        virtualFilesAffected.any { it.isDirectory && vFile.path.startsWith(it.path) }
                }

            if (filesToRemoveFromCache.isNotEmpty())
                project.service<SnykTaskQueueService>().scheduleRunnable("SnykCode is updating caches...") {
                    if (filesToRemoveFromCache.size > 10) {
                        // bulk files change event (like `git checkout`) - better to drop cache and perform full rescan later
                        AnalysisData.instance.removeProjectFromCaches(project)
                    } else {
                        AnalysisData.instance.removeFilesFromCache(filesToRemoveFromCache)
                        // update Bundle files on server
/*
                        RunUtils.instance.runInBackground(
                            project,
                            "Updating cache on server (for ${filesToRemoveFromCache.size} locally changed files)..."
                        ) { progress ->
                            AnalysisData.instance.updateCachedResultsForFiles(
                                project,
                                Collections.emptyList(),
                                filesToRemoveFromCache,
                                progress);
                        }
*/
                    }
                }
        }
    }

    private fun getAffectedVirtualFiles(
        events: List<VFileEvent>,
        fileFilter: Predicate<VirtualFile>,
        classesOfEventsToFilter: Collection<Class<*>>
    ): Set<VirtualFile> {
        return events.asSequence()
//            .filter(getUpdateModeFilter())
            .filter { event -> instanceOf(event, classesOfEventsToFilter) }
            .mapNotNull(VFileEvent::getFile)
            .filter(fileFilter::test)
            .toSet()
    }

    private fun instanceOf(obj: Any, classes: Collection<Class<*>>): Boolean {
        for (c in classes) {
            if (c.isInstance(obj)) return true
        }
        return false
    }

    companion object {
        // see https://github.com/snyk/snyk/blob/master/src/lib/detect.ts#L10
        private val supportedBuildFiles = listOf(
            "yarn.lock",
            "package-lock.json",
            "package.json",
            "Gemfile",
            "Gemfile.lock",
            "pom.xml",
            "build.gradle",
            "build.gradle.kts",
            "build.sbt",
            "Pipfile",
            "requirements.txt",
            "Gopkg.lock",
            "go.mod",
            "vendor.json",
            "project.assets.json",
            "project.assets.json",
            "packages.config",
            "paket.dependencies",
            "composer.lock",
            "Podfile",
            "Podfile.lock",
            "pyproject.toml",
            "poetry.lock"
        )
    }
}
