package direct.kabu.alertkun.monitoring

import java.io.File

/** Supports daily logs and the retained legacy files; never deletes the user's observations. */
internal fun observationTestLines(directory: File): List<String> = directory.listFiles().orEmpty()
    .filter { it.isFile && it.name.startsWith("monitoring-observation") &&
        (it.name.endsWith(".log") || it.name.matches(Regex("monitoring-observation\\.log\\.\\d+"))) }
    .flatMap { it.readLines() }.sortedBy { it.substringBefore(' ') }
