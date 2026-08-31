package io.github.bovmelo.cph.persistence

import io.github.bovmelo.cph.model.CphJson
import io.github.bovmelo.cph.model.Problem
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

class ProblemStore {
    fun exists(sourcePath: Path): Boolean = Files.isRegularFile(metadataPath(sourcePath))

    fun load(sourcePath: Path): Problem? {
        val metadataPath = metadataPath(sourcePath)
        if (!Files.isRegularFile(metadataPath)) {
            return null
        }
        return runCatching {
            CphJson.format.decodeFromString<Problem>(Files.readString(metadataPath, StandardCharsets.UTF_8))
        }.getOrNull()
    }

    fun save(problem: Problem) {
        val sourcePath = Path.of(problem.sourcePath).toAbsolutePath().normalize()
        val metadataPath = metadataPath(sourcePath)
        Files.createDirectories(metadataPath.parent)

        val temporaryPath = Files.createTempFile(metadataPath.parent, ".cph-", ".tmp")
        try {
            val json = CphJson.format.encodeToString(problem) + System.lineSeparator()
            Files.writeString(temporaryPath, json, StandardCharsets.UTF_8)
            try {
                Files.move(
                    temporaryPath,
                    metadataPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporaryPath, metadataPath, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporaryPath)
        }
    }

    fun delete(sourcePath: Path): Boolean = Files.deleteIfExists(metadataPath(sourcePath))

    fun metadataPath(sourcePath: Path): Path {
        val normalizedPath = sourcePath.toAbsolutePath().normalize()
        val digest = MessageDigest.getInstance("MD5")
            .digest(normalizedPath.toString().toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
        val metadataName = ".${normalizedPath.fileName}_${digest}.prob"
        return normalizedPath.parent.resolve(".cph").resolve(metadataName)
    }
}
