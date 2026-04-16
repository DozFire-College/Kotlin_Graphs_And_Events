package Examen

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.io.File

@Serializable
data class FileInfo(val filename: String, val lines: Int)

fun countLines(filePath: String): Int = File(filePath).useLines { it.count() }

fun main() {
    val filePath = "C:\\Users\\user\\IdeaProjects\\Kotlin_Graphs_And_Events\\src\\main\\kotlin\\playerGridMovement\\main.kt"
    val lineCount = countLines(filePath)

    val result = Json.encodeToString(FileInfo(filePath, lineCount))
    println(result)
}