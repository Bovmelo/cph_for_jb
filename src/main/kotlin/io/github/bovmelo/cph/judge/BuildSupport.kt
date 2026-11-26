package io.github.bovmelo.cph.judge

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Comparator

internal fun applyPreparations(plan: ExecutionPlan, buildDirectory: Path) {
    val normalizedBuildDirectory = buildDirectory.toAbsolutePath().normalize()
    plan.preparations.forEach { preparation ->
        val target = preparation.target.toAbsolutePath().normalize()
        require(target.startsWith(normalizedBuildDirectory)) { "A preparation target escaped the build directory." }
        Files.createDirectories(target.parent)
        when (preparation) {
            is FilePreparation.Copy -> Files.copy(
                preparation.source,
                target,
                StandardCopyOption.REPLACE_EXISTING,
            )

            is FilePreparation.Write -> Files.writeString(target, preparation.content, StandardCharsets.UTF_8)
        }
    }
}

/**
 * The command that runs a program this build just produced, or null when the plan runs an interpreter or a
 * runtime that was already on the machine. Only a new executable has a first launch still ahead of it.
 */
internal fun ExecutionPlan.builtExecutable(buildDirectory: Path): CommandSpec? {
    if (compile == null) {
        return null
    }
    val executable = runCatching { Path.of(run.executable) }.getOrNull()?.takeIf(Path::isAbsolute) ?: return null
    return run.takeIf { executable.normalize().startsWith(buildDirectory.toAbsolutePath().normalize()) }
}

internal fun deleteTree(root: Path) {
    runCatching {
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
