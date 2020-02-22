package org.javacs.kt

import org.javacs.kt.classpath.defaultClassPathResolver
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.FileSystems
import java.nio.file.Path
import java.util.stream.Collectors

class CompilerClassPath(private val config: CompilerConfiguration) : Closeable {
    private val workspaceRoots = mutableSetOf<Path>()
    private val javaSourcePath = mutableSetOf<Path>()
    private val classPath = mutableSetOf<Path>()
    private val buildScriptClassPath = mutableSetOf<Path>()
    var compiler = Compiler(javaSourcePath, classPath, buildScriptClassPath)
        private set

    init {
        compiler.updateConfiguration(config)
    }

    private fun refresh(
        updateClassPath: Boolean = true,
        updateBuildScriptClassPath: Boolean = true,
        updateJavaSourcePath: Boolean = false
    ): Boolean {
        // TODO: Fetch class path and build script class path concurrently (and asynchronously)
        val resolver = defaultClassPathResolver(workspaceRoots)
        var refreshCompiler = updateJavaSourcePath

        if (updateClassPath) {
            val newClassPath = resolver.classpathOrEmpty
            if (newClassPath != classPath) {
                syncPath(classPath, newClassPath, "class path")
                refreshCompiler = true
            }
        }

        if (updateBuildScriptClassPath) {
            LOG.info("Update build script path")
            val newBuildScriptClassPath = resolver.buildScriptClasspathOrEmpty
            if (newBuildScriptClassPath != buildScriptClassPath) {
                syncPath(buildScriptClassPath, newBuildScriptClassPath, "class path")
                refreshCompiler = true
            }
        }

        if (refreshCompiler) {
            LOG.info("Reinstantiating compiler")
            compiler.close()
            compiler = Compiler(javaSourcePath, classPath, buildScriptClassPath)
            updateCompilerConfiguration()
        }

        return refreshCompiler
    }

    private fun syncPath(dest: MutableSet<Path>, new: Set<Path>, name: String) {
        val added = new - dest
        val removed = dest - new

        logAdded(added, name)
        logRemoved(removed, name)

        dest.removeAll(removed)
        dest.addAll(added)
    }

    fun updateCompilerConfiguration() {
        compiler.updateConfiguration(config)
    }

    fun addWorkspaceRoot(root: Path): Boolean {
        LOG.info("Searching for dependencies and Java sources in workspace root {}", root)

        workspaceRoots.add(root)
        javaSourcePath.addAll(findJavaSourceFiles(root))

        return refresh(updateJavaSourcePath = true)
    }

    fun removeWorkspaceRoot(root: Path): Boolean {
        LOG.info("Removing dependencies and Java source path from workspace root {}", root)

        workspaceRoots.remove(root)
        javaSourcePath.removeAll(findJavaSourceFiles(root))

        return refresh(updateJavaSourcePath = true)
    }

    fun createdOnDisk(file: Path): Boolean {
        if (isJavaSource(file)) {
            javaSourcePath.add(file)
        }
        return changedOnDisk(file)
    }

    fun deletedOnDisk(file: Path): Boolean {
        if (isJavaSource(file)) {
            javaSourcePath.remove(file)
        }
        return changedOnDisk(file)
    }

    fun changedOnDisk(file: Path): Boolean {
        val buildScript = isBuildScript(file)
        val javaSource = isJavaSource(file)
        if (buildScript || javaSource) {
            return refresh(updateClassPath = buildScript, updateBuildScriptClassPath = false, updateJavaSourcePath = javaSource)
        } else {
            return false
        }
    }

    private fun isJavaSource(file: Path): Boolean = file.fileName.toString().endsWith(".java")

    private fun isBuildScript(file: Path): Boolean = file.fileName.toString().let { it == "pom.xml" || it == "build.gradle" || it == "build.gradle.kts" }

    override fun close() {
        compiler.close()
    }
}

// TODO: Cut off branches that are excluded in the walker directly
private fun findJavaSourceFiles(root: Path): Set<Path> {
    val sourceMatcher = FileSystems.getDefault().getPathMatcher("glob:*.java")
    val exclusions = SourceExclusions(root)
    return Files.walk(root)
        .filter { exclusions.isPathIncluded(it) && sourceMatcher.matches(it.fileName) }
        .collect(Collectors.toSet())
}

private fun logAdded(sources: Collection<Path>, name: String) {
    when {
        sources.isEmpty() -> return
        sources.size > 5 -> LOG.info("Adding {} files to {}", sources.size, name)
        else -> LOG.info("Adding {} to {}", sources, name)
    }
}

private fun logRemoved(sources: Collection<Path>, name: String) {
    when {
        sources.isEmpty() -> return
        sources.size > 5 -> LOG.info("Removing {} files from {}", sources.size, name)
        else -> LOG.info("Removing {} from {}", sources, name)
    }
}
