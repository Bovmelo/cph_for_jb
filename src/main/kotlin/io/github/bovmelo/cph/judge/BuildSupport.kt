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

internal fun deleteTree(root: Path) {
    runCatching {
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
