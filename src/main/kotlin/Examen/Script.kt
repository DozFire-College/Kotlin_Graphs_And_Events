package Examen

import  kotlinx.serialization.json.Json
import java.io.File

fun countLines(filePath: String): Int = File(filePath).useLines { it.count()}

fun main(){
    val filePath = "C:\\Users\\user\\IdeaProjects\\Kotlin_Graphs_And_Events\\src\\main\\kotlin\\playerGridMovement\\main.kt"
    val lineCount = countLines(filePath)

    val result = Json.encodeToString(mapOf("filename" to filePath, "lines" to lineCount))
    println(result)
}
