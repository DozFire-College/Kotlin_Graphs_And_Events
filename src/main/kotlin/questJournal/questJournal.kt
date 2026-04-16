package questJournal

import de.fabmax.kool.KoolApplication           // KoolApplication - запускает Kool-приложение (окно + цикл рендера)
import de.fabmax.kool.addScene                  // addScene - функция "добавь сцену" в приложение (у тебя она просила отдельный импорт)
import de.fabmax.kool.math.FLT_EPSILON
import de.fabmax.kool.math.Vec3f                // Vec3f - 3D-вектор (x, y, z), как координаты / направление
import de.fabmax.kool.math.deg                  // deg - превращает число в "градусы" (угол)
import de.fabmax.kool.scene.*                   // scene.* - Scene, defaultOrbitCamera, addColorMesh, lighting и т.д.
import de.fabmax.kool.modules.ksl.KslPbrShader  // KslPbrShader - готовый PBR-шейдер (материал)
import de.fabmax.kool.util.Color                // Color - цвет (RGBA)
import de.fabmax.kool.util.Time                 // Time.deltaT - сколько секунд прошло между кадрами
import de.fabmax.kool.pipeline.ClearColorLoad   // ClearColorLoad - режим: "не очищай экран, оставь то что уже нарисовано"
import de.fabmax.kool.modules.ui2.*             // UI2: addPanelSurface, Column, Row, Button, Text, dp, remember, mutableStateOf
import de.fabmax.kool.util.DynamicStruct
import de.fabmax.kool.modules.ui2.UiModifier.*
import de.fabmax.kool.physics.geometry.PlaneGeometry
import de.fabmax.kool.physics.vehicle.Vehicle
import de.fabmax.kool.util.checkIsReleased

import kotlinx.coroutines.launch  // запускает корутину
import kotlinx.coroutines.Job     // контроллер запущенной корутины
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive // проверка жива ли ещё корутина - полезно для циклов
import kotlinx.coroutines.delay

// Flow корутины
import kotlinx.coroutines.launch // запуск корутины
import kotlinx.coroutines.flow.MutableSharedFlow // табло состояний
import kotlinx.coroutines.flow.SharedFlow // Только чтение для подписчиков
import kotlinx.coroutines.flow.MutableStateFlow // Радиостанция событий
import kotlinx.coroutines.flow.StateFlow // Только для чтения стостояний
import kotlinx.coroutines.flow.asSharedFlow // Отдать наружу только SharedFlow
import kotlinx.coroutines.flow.asStateFlow // Отдать только StateFlow
import kotlinx.coroutines.flow.collect // слушать поток

import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.flatMapLatest

// импорты Serialization
import kotlinx.serialization.Serializable // Анотация, что можно сохранять
import kotlinx.serialization.json.Json // Формат файла Json


import java.io.File
import javax.print.DocFlavor

// questjournal 2.0 - список активных квестов, цели, маркеры, подсказки
// QuestSystem будет обрабатывать информацию о нынешнем квесте активного игрока
// и на UI выводить активную информацию

// Маркеры и типы квестов

enum class  QuestStatus{
    ACTIVE,
    COMPLETED,
    FAILED
}

enum class QuestMarker{
    NEW,
    PINED,
    COMPLETED,
    NONE
}
// Подготовка журнала квестов - то что будет отрисовывать UI
data class QuestJournalEntry(
    val questId: String,
    val title: String,
    val status: QuestStatus,
    val objectiveText: String, // подсказка что делать дальше
    val marker: QuestMarker,
    val markerHint: String
)
sealed interface GameEvent{
    val playerId: String
}
data class QuestJournalUpdated(
    override val playerId: String
): GameEvent

data class QuestCompleted(
    override val playerId: String,
    val questId: String,
    val questTitle: String
) : GameEvent
//------------------ События, что будут влиять на UI и другие системы -------------//

sealed interface GameCommand{
    val playerId: String
}


// Игрок открыл квест - поменять маркер NEW
data class CmdOpenQuest(
    override val playerId: String,
    val questId: String
): GameCommand

data class CmdPinQuest(
    override val playerId: String,
    val questId: String
): GameCommand
data class CmdAddQuest(
    override val playerId: String,
    val questId: String
): GameCommand
data class CmdProgressQuest(
    override val playerId: String,
    val questId: String
): GameCommand
data class CmdSwitchPlayer(
    override val playerId: String,
    val newPlayerId: String
): GameCommand
// -----------------Серверные данные квеста -------------------------//
data class  QuestStateOnServer(
    val questId: String,
    val title: String,
    val step: Int,
    val status: QuestStatus,
    val isNew: Boolean,
    val isPinned: Boolean
)
class QuestSystem{
    // Здесь прописываем текст целей квеста по шагам для каждого квеста
    private fun objectiveFor(questId: String, step: Int): String{
        return when (questId){
            "q_alchemist" -> when (step){
                0 -> "Поговорить с алхимиком"
                1 -> "Собери траву"
                2 -> "Принеси траву"
                else -> "Квест завершён"
            }
            "q_guard" -> when(step){
                0 -> "Поговорить со стражем этой двери"
                1 -> "Заплатить 10 золота"
                else -> "Проход открыт"
            }
            else -> "Неизвестный квест"
        }
    }
    // Подсказки куда идти - в будущем используем для карты и компаса
    private fun markerHintFor(questId: String, step: Int): String{
        return when (questId){
            "q_alchemist" -> when (step){
                0 -> "Идти к NPC: Алхимик"
                1 -> "Собрать herb x2"
                2 -> "Вернись к NPC"
                else -> "Готово"
            }
            "q_guard" -> when(step){
                0 -> "Идти к NPC: Страж"
                1 -> "Найти чем расплотиться со стражником"
                else -> "Готово"
            }
            else -> ""
        }
    }

    fun toJournalEntry(quest: QuestStateOnServer): QuestJournalEntry{
        val objective = objectiveFor(quest.questId, quest.step)
        val hint = markerHintFor(quest.questId, quest.step)

        val marker = when{
            quest.status == QuestStatus.COMPLETED -> QuestMarker.COMPLETED
            quest.isPinned -> QuestMarker.PINED
            quest.isNew -> QuestMarker.NEW
            else -> QuestMarker.NONE
        }
        return QuestJournalEntry(
            quest.questId,
            quest.title,
            quest.status,
            objective,
            marker,
            hint

        )
    }

}
class GameServer{
    private val _events = MutableSharedFlow<GameEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<GameEvent> = _events.asSharedFlow()

    private  val _commands = MutableSharedFlow<GameCommand>(extraBufferCapacity = 64)
    val commands: SharedFlow<GameCommand> = _commands.asSharedFlow()

    fun trySend(cmd: GameCommand): Boolean{
        // tryEmit - попытка быстро положить команду в поток
        return _commands.tryEmit(cmd)
    }
    // состояние квестов для каждого игрока

    private val _questByPlayer = MutableStateFlow<Map<String, List<QuestStateOnServer>>>(
        mapOf(
            "Oleg" to listOf(
                QuestStateOnServer("q_alchemist", "Алхимик и трава", 0, QuestStatus.ACTIVE, true, true),
                QuestStateOnServer("q_guard", "Тебе сюды нельзя", 0, QuestStatus.ACTIVE, true, false)
            ),
            "Stas" to listOf(
                QuestStateOnServer("q_alchemist", "Алхимик и трава", 0, QuestStatus.ACTIVE, true, true),
                QuestStateOnServer("q_guard", "Тебе сюды нельзя", 0, QuestStatus.ACTIVE, true, false)
            )
        )
    )
    val questByPlayer: StateFlow<Map<String, List<QuestStateOnServer>>> = _questByPlayer.asStateFlow()
    fun start(scope: kotlinx.coroutines.CoroutineScope){
        scope.launch {
            commands.collect{ cmd ->
                process(cmd)
            }
        }
    }
    private suspend fun process(cmd: GameCommand){
        when (cmd){
            is CmdOpenQuest -> openQuest(cmd.playerId, cmd.questId)
            is CmdPinQuest -> pinQuest(cmd.playerId, cmd.questId)
            is CmdAddQuest -> addQuest(cmd.playerId, cmd.questId)
            is CmdProgressQuest -> progressQuest(cmd.playerId, cmd.questId)
            is CmdSwitchPlayer -> {}
        }
    }
    private fun getPlayerQuests(playerId: String) : List<QuestStateOnServer>{
        return _questByPlayer.value[playerId] ?: emptyList()
    }
    private fun setPlayerQuests(playerId: String, quests : List<QuestStateOnServer>){
       val oldMap = _questByPlayer.value.toMutableMap()
        oldMap[playerId] = quests
        _questByPlayer.value = oldMap.toMap()
    }
    private suspend fun openQuest(playerId: String, questId: String){
        val quests = getPlayerQuests(playerId).toMutableList()

        for(i in quests.indices){
            val q = quests[i]
            if (q.questId == questId){
                quests[i] = q.copy(isNew = false)
            }
        }
        setPlayerQuests(playerId, quests)

        _events.emit(QuestJournalUpdated(playerId))
    }
    private  suspend fun pinQuest (playerId: String, questId: String){
        val quests = getPlayerQuests(playerId).toMutableList()

        for(i in quests.indices){
            val q = quests[i]
            if (q.questId == questId){
                quests[i] = q.copy(isPinned = (q.questId == questId))
            }
        }
        setPlayerQuests(playerId, quests)

        _events.emit(QuestJournalUpdated(playerId))
    }
    private suspend fun addQuest(playerId: String, questId: String) {
        val quests = getPlayerQuests(playerId).toMutableList()
        val existingQuestIndex = quests.indexOfFirst { it.questId == questId }

        if (existingQuestIndex >= 0) {
            val q = quests[existingQuestIndex]
            quests[existingQuestIndex] = q.copy(
                isNew = true,
                isPinned = false,
                step = 0,
                status = QuestStatus.ACTIVE
            )
        } else {
            val title = when(questId) {
                "q_alchemist" -> "Алхимик и трава"
                "q_guard" -> "Тебе сюды нельзя"
                else -> "Новый квест"
            }
            quests.add(
                QuestStateOnServer(
                    questId,
                    title,
                    0,
                    QuestStatus.ACTIVE,
                    true,
                    false
                )
            )
        }

        setPlayerQuests(playerId, quests)
        _events.emit(QuestJournalUpdated(playerId))
    }

    private suspend fun  progressQuest(playerId: String, questId: String){
        val quests = getPlayerQuests(playerId).toMutableList()
        var completedQuestTitle: String? = null


        for(i in quests.indices){
            val q = quests[i]
            if (q.questId == questId){
                val newStep = q.step + 1

                val completed = when(q.questId){
                    "q_alchemist" -> newStep >= 3
                    "q_guard" -> newStep >= 2
                    else -> false
                }
                val newStatus = if (completed) QuestStatus.COMPLETED else QuestStatus.ACTIVE

                if (completed && q.status != QuestStatus.COMPLETED) {
                    completedQuestTitle = q.title
                }

                quests[i] = q.copy(isNew = false, step = newStep, status = newStatus)
            }
        }
        setPlayerQuests(playerId, quests)

        _events.emit(QuestJournalUpdated(playerId))
    }
}
class HudState{
    val activePlayerIdFlow = MutableStateFlow("Oleg")
    val activePlayerIdUi = mutableStateOf("Oleg")

    val questEntries = mutableStateOf<List<QuestJournalEntry>>(emptyList())
    val selectedQuestId = mutableStateOf<String?>(null)

    val log = mutableStateOf<List<String>>(emptyList())

    val victoryVisible = mutableStateOf(false)
    val victoryMessage = mutableStateOf("")
}

fun hudLog(hud: HudState, text: String){
    hud.log.value = (hud.log.value + text). takeLast(20)
}

fun markerSymbol(m: QuestMarker): String{
    return when(m){
        QuestMarker.NEW -> "!"
        QuestMarker.PINED -> "->"
        QuestMarker.COMPLETED -> "✔"
        QuestMarker.NONE -> "о"
    }
}
fun sortQuestEntries(entries: List<QuestJournalEntry>): List<QuestJournalEntry> {
    return entries.sortedWith(compareBy(
        { if (it.marker == QuestMarker.PINED) 0 else 1 },
        { if (it.marker == QuestMarker.NEW) 0 else 1 },
        { if (it.status == QuestStatus.ACTIVE) 0 else 1 },
        { if (it.status == QuestStatus.COMPLETED) 0 else 1 }
    ))
}
fun main() = KoolApplication {
    val hud = HudState()
    val server = GameServer()
    val quests = QuestSystem()

    addScene {

        defaultOrbitCamera()

        addColorMesh {
            generate { cube { colored() } }

            shader = KslPbrShader {
                color { vertexColor() }
                metallic(0.7f)
                roughness(0.4f)
            }
            onUpdate {
                transform.rotate(45f.deg * Time.deltaT, Vec3f.X_AXIS)
            }
        }
        lighting.singleDirectionalLight {
            setup(Vec3f(-1f, -1f, -1f))
            setColor(Color.WHITE, 5f)
        }
        server.start(coroutineScope)
    }
    addScene {
        setupUiScene(ClearColorLoad)

        // Подписка на события квестов
        coroutineScope.launch {
            server.questByPlayer.collect { map ->
                val pid = hud.activePlayerIdFlow.value
                val serverList = map[pid] ?: emptyList()

                val entries = serverList.map { quests.toJournalEntry(it) }

                hud.questEntries.value = entries

                if (hud.selectedQuestId.value == null) {
                    val pinned = entries.firstOrNull { it.marker == QuestMarker.PINED }
                    if (pinned != null) hud.selectedQuestId.value = pinned.questId
                }
            }
        }
        coroutineScope.launch {
            server.events
                .filter { it is QuestCompleted && it.playerId == hud.activePlayerIdFlow.value }
                .collect { event ->
                    event as QuestCompleted
                    hud.victoryMessage.value = "ПОБЕДА!\nКвест завершён: ${event.questTitle}"
                    hud.victoryVisible.value = true

                    // Автоматически скрыть через 3 секунды
                    delay(3000)
                    hud.victoryVisible.value = false
                }
        }
        hud.activePlayerIdFlow
            .flatMapLatest { pid ->
                server.events.filter { it.playerId == pid }
            }
            .map { e -> "[${e.playerId}] ${e::class.simpleName}" }
            .onEach { line -> hudLog(hud, line) }
            .launchIn(coroutineScope)

        addPanelSurface {
            modifier
                .align(AlignmentX.Start, AlignmentY.Top)
                .margin(16.dp)
                .background(RoundRectBackground(Color(0f, 0f, 0f, 0.6f), 14.dp))
                .padding(12.dp)
            Column {
                Text("Player: ${hud.activePlayerIdUi.use()}") {}
                modifier.margin(bottom = sizes.gap)

                Row {
                    Button("Switch Player") {
                        modifier.margin(end = 8.dp).onClick {
                            val newId = if (hud.activePlayerIdFlow.value == "Oleg") "Stas" else "Oleg"

                            hud.activePlayerIdFlow.value = newId
                            hud.activePlayerIdUi.value = newId

                            hud.selectedQuestId.value = null
                        }
                    }
                }
                Button("Add Quest") {
                    modifier.margin(start = 8.dp).onClick {
                        server.trySend(CmdAddQuest(hud.activePlayerIdUi.value, "q_alchemist"))
                        hudLog(hud, "Added q_alchemist for ${hud.activePlayerIdUi.value}")
                    }
                }


                Text("Активные квесты:") { modifier.margin(top = sizes.gap) }

                val entries = hud.questEntries.use()
                val selectedId = hud.selectedQuestId.use()

                val sortedEntries = sortQuestEntries(entries)

                for (q in entries) {
                    val symbol = markerSymbol(q.marker)

                    val line = "$symbol ${q.title}"
                    Button(line) {
                        modifier.margin(bottom = sizes.smallGap).onClick {
                            hud.selectedQuestId.value = q.questId
                            // Если квест открыт отправить серверу команду что он уже не новый
                            server.trySend(CmdOpenQuest(hud.activePlayerIdUi.value, q.questId))
                        }
                    }
                    Text(" - ${q.objectiveText}") {
                        modifier.font(sizes.smallText).margin(bottom = sizes.smallGap)
                    }

                    if (selectedId == q.questId) {
                        Text("marker: ${q.markerHint}") {
                            modifier.font(sizes.smallText).margin(sizes.gap)
                        }
                        Row {
                            Button("Pin") {
                                modifier.margin(end = 8.dp).onClick {
                                    server.trySend(CmdPinQuest(hud.activePlayerIdUi.value, q.questId))
                                }
                            }
                            Button("Progress") {
                                modifier.margin(end = 8.dp).onClick {
                                    server.trySend(CmdProgressQuest(hud.activePlayerIdUi.value, q.questId))
                                }
                            }

                        }
                    }
                }
            }
            Text("Log:"){ modifier.margin(top = sizes.gap)}
            for (line in hud.log.use()){
                Text(line){ modifier.font(sizes.smallText)}
            }
        }
        if (hud.victoryVisible.use()) {
            addPanelSurface {
                modifier
                    .align(AlignmentX.Center, AlignmentY.Center)
                    .size(400.dp, 200.dp)
                    .background(RoundRectBackground(Color(0.1f, 0.1f, 0.1f, 0.95f), 24.dp))
                    .padding(24.dp)

                Column {
                    modifier.align(AlignmentX.Center, AlignmentY.Center)

                    Text("🎉") {
                        modifier
                            .align(AlignmentX.Center)
                            .font(sizes.largeText)
                            .margin(bottom = 16.dp)
                    }

                    Text(hud.victoryMessage.value) {
                        modifier
                            .align(AlignmentX.Center)
                            .font(sizes.normalText)
                            .textAlign(AlignmentX.Center)
                            .margin(bottom = 24.dp)
                    }

                    Button("Продолжить") {
                        modifier
                            .align(AlignmentX.Center)
                            .onClick {
                                hud.victoryVisible.value = false
                            }
                    }
                }
            }
        }
    }
}