package questDependence

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


enum class QuestStatus{
    LOCKED,
    ACTIVE,
    COMPLETE
}

enum class QuestMarker{
    NEW,
    PINNED,
    COMPLETED,
    LOCKED,
    NONE
}
enum class QuestBranch{
    NONE,
    HELP,
    THREAD,
    PASS,
    PAY
}

data class QuestStateOnServer(
    val questId: String,
    val title: String,
    val status: QuestStatus,
    val step: Int,
    val branch: QuestBranch,
    val progressCurrent: Int,
    val progressTarget: Int,
    val isNew: Boolean,
    val isPinned: Boolean,
    val unlockRequiredQuestId: String?
)
data class QuestJournalEntry(
    val questId: String,
    val title: String,
    val status: QuestStatus,
    val objectiveText: String, // подсказка что делать дальше
    val progressText: String,
    val progressBar: String,
    val marker: QuestMarker,
    val markerHint: String,
    val branchText: String,
    val lockedReason: String
)
sealed interface GameEvent{
    val playerId: String
}
data class QuestBranchChosen(
    override val playerId: String,
    val questId: String,
    val branch: QuestBranch
): GameEvent
data class ItemCollected(
    override val playerId: String,
    val itemId: String,
    val countAdded: Int
): GameEvent
data class GoldTurnedIn(
    override val playerId: String,
    val questId: String,
    val amount: Int
    ): GameEvent
data class  QuestCompleted(
    override val playerId: String,
    val questId: String
): GameEvent
data class QuestUnlocked(
    override val playerId: String,
    val questId: String
): GameEvent
data class QuestJournalUpdated(
    override val playerId: String
): GameEvent



data class QuestOpened(
    override val playerId: String,
    val questId: String
): GameEvent
data class QuestPinned(
    override val playerId: String,
    val questId: String
): GameEvent
data class  ServerMessage(
    override val playerId: String,
    val text: String
): GameEvent

sealed interface GameCommand{
    val playerId: String
}


// Игрок открыл квест - поменять маркер NEW
data class CmdOpenQuest(
    override val playerId: String,
    val questId: String
): GameCommand
data class CmdChooseBranch(
    override val playerId: String,
    val questId: String,
    val branch: QuestBranch
): GameCommand
data class CmdCollectItem(
    override val playerId: String,
    val itemId: String,
    val countAdded: Int
): GameCommand

data class CmdTurnInGold (
    override val playerId: String,
    val amount: Int,
    val questId: String
): GameCommand
data class CmdGiveGoldDebug(
    override val playerId: String,
    val amount: Int
): GameCommand
data class CmdFinishQuest(
    override val playerId: String,
    val questId: String,
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

data class PlayerData(
    val playerId: String,
    val gold: Int,
    val inventory: Map<String, Int>
)

class QuestSystem {
    // Здесь прописываем текст целей квеста по шагам для каждого квеста
     fun objectiveFor(q: QuestStateOnServer): String {

        if (q.status == QuestStatus.LOCKED){
            return "Квест пока недоступен"
        }
        if (q.questId == "q_alchemist"){
            return  when(q.step){
            0 -> "Поговори с алхимиком"
            1 ->{
                when(q.branch){
                    QuestBranch.NONE -> "Выбери путь: Help или Threat"
                    QuestBranch.HELP -> "Собери траву ${q.progressCurrent} / ${q.progressTarget}"
                    QuestBranch.THREAD -> "Собери золото ${q.progressCurrent} / ${q.progressTarget}"
                    else -> ""
                }
            }
            2 -> "Вернись к алхимику и заверши квест"
                else -> "квест завершён"
            }
        }
        if (q.questId == "q_guard"){
            return when(q.step){
                0 -> "Поговори со стражником"
                1 -> "Заплати стражнику золото: ${q.progressCurrent} / ${q.progressTarget}"
                2 -> "Сдай квест у стражника"
                else -> "Квест завершён"
            }
        }
        if (q.questId == "q_yura"){
            return when(q.step){
                0 -> "Поговори с Юрой и узнай по поводу автомата"
                1 -> {
                    when (q.branch) {
                        QuestBranch.NONE -> "Выбери как ты хочешь закрыть Котлин: Help или Threat"
                        QuestBranch.PASS -> "Напиши контрольную и сдай Юре"
                        QuestBranch.PAY -> "Заплати золотом: ${q.progressCurrent} / ${q.progressTarget}"
                        else -> ""
                    }
                }
                2 -> "Сдай квест у Юры"
                 else -> "Квест завершён"
            }
        }
        return "Неизвестный квест"
        }


    // Подсказки куда идти - в будущем используем для карты и компаса
     fun markerHintFor(q: QuestStateOnServer): String {
        if (q.status == QuestStatus.LOCKED){
            return "Сначала разблокируй квест"
        }
        if (q.questId == "q_alchemist"){
            return when (q.step){
                0 -> "NPC: Алхимик"
                1 -> {
                    when(q.branch){
                        QuestBranch.NONE -> "Выбери вариант диалога"
                        QuestBranch.HELP -> "Собери траву"
                        QuestBranch.THREAD -> "Найди золото"
                        else -> ""
                    }
                }
                2 -> "NPC: Алхимик"
                else -> "Готово"
            }
        }
        if (q.questId == "q_guard"){
            return when(q.step){
                0 -> "NPC: Стражник"
                1 -> "Найди золото для оплаты"
                2 -> "NPC: Стражник"
                else -> "Готово"
            }
        }
        if (q.questId == "q_yura"){
            return when (q.step){
                0 -> "NPC: Юра"
                1 -> {
                    when(q.branch){
                        QuestBranch.NONE -> "Выбери вариант диалога"
                        QuestBranch.PASS -> "Сделай контрольную"
                        QuestBranch.PAY -> "Найди золото"
                        else -> ""
                    }
                }
                2 -> "NPC: Юра"
                else -> "Готово"
            }
        }
        return ""
    }
    fun branchTextFor(branch: QuestBranch): String{
        return when(branch){
            QuestBranch.NONE -> "Путь не выбран"
            QuestBranch.HELP -> "Путь помощи"
            QuestBranch.THREAD -> "Путь угрозы"
            QuestBranch.PASS -> "Путь ботаника"
            QuestBranch.PAY -> "Путь взяточника"
        }
    }

    fun lockedReasonFor(q: QuestStateOnServer): String{
        if (q.status != QuestStatus.LOCKED) return ""

        return if(q.unlockRequiredQuestId == null){
            "Причина блокировки неизвестна"
        }else{
            "Нужно завершить квест ${q.unlockRequiredQuestId}"
        }
    }
    fun markerFor(q: QuestStateOnServer): QuestMarker{
        return  when{
            q.status == QuestStatus.LOCKED -> QuestMarker.LOCKED
            q.status == QuestStatus.COMPLETE -> QuestMarker.COMPLETED
            q.isPinned -> QuestMarker.PINNED
            q.isNew -> QuestMarker.NEW
            else -> QuestMarker.NONE
        }
    }
    fun progressBarText(current: Int, target: Int, blocks: Int = 10): String{
        if (target <= 0) return ""

        val ratio = current.toFloat() / target.toFloat()
        //ratio - отношение прогресса к цели

        val filled = (ratio * blocks).toInt().coerceIn(0, blocks)
        //coerceIn - ограничивает от 0 до ... blocks (10) числа

        val empty = blocks - filled

        val bar =  "█".repeat(filled) + "▒".repeat(empty)
        val percent = (ratio * 100).toInt().coerceIn(0, 100)
        return  "$bar $percent%"
    }
    fun toJournalEntry(q: QuestStateOnServer): QuestJournalEntry{
        val progressText = if(q.progressTarget > 0) "${q.progressCurrent} / ${q.progressTarget}" else ""

        val progressBar = if(q.progressTarget > 0) progressBarText(q.progressCurrent, q.progressTarget) else ""

        return QuestJournalEntry(
            q.questId,
            q.title,
            q.status,
            objectiveFor(q),
            progressText,
            progressBar,
            markerFor(q),
            markerHintFor(q),
            branchTextFor(q.branch),
            lockedReasonFor(q)
        )
    }
    fun applyEvent(
        quests: List<QuestStateOnServer>,
        event: GameEvent
    ): List<QuestStateOnServer>{
        val copy = quests.toMutableList()

        for (i in copy.indices){
            val q = copy[i]

            if (q.status == QuestStatus.LOCKED) continue
            if (q.status == QuestStatus.COMPLETE) continue

            if (q.questId == "q_alchemist"){
                copy[i] = updateAlchemist(q, event)
            }
            if (q.questId == "q_guard"){
                copy[i] = updateGuard(q, event)
            }
            if (q.questId == "q_yura"){
                copy[i] = updateYura(q, event)
            }
        }
        return copy.toList()
    }

    private fun updateAlchemist(q: QuestStateOnServer, event: GameEvent): QuestStateOnServer {
        if (q.step == 0 && event is QuestBranchChosen && event.questId == q.questId) {
            return when (event.branch) {
                QuestBranch.HELP -> q.copy(
                    step = 1,
                    branch = QuestBranch.HELP,
                    progressCurrent = 0,
                    progressTarget = 3,
                    isNew = false
                )

                QuestBranch.THREAD -> q.copy(
                    step = 1,
                    branch = QuestBranch.THREAD,
                    progressCurrent = 0,
                    progressTarget = 10,
                    isNew = false
                )

                QuestBranch.NONE -> q
                else -> {q}
            }

        }
        if (q.step == 1 && q.branch == QuestBranch.HELP && event is ItemCollected && event.itemId == "Herb") {
            val newCurrent = (q.progressCurrent + event.countAdded).coerceAtMost(q.progressTarget)
            val update = q.copy(progressCurrent = newCurrent, isNew = false)

            if (newCurrent >= q.progressTarget) {
                return update.copy(step = 2, progressCurrent = 0, progressTarget = 0)
            }
            return update
        }
        if (q.step == 1 && q.branch == QuestBranch.THREAD && event is GoldTurnedIn && event.questId == q.questId) {
            val newCurrent = (q.progressCurrent + event.amount).coerceAtMost(q.progressTarget)
            val update = q.copy(progressCurrent = newCurrent, isNew = false)

            if (newCurrent >= q.progressTarget) {
                return update.copy(step = 2, progressCurrent = 0, progressTarget = 0)
            }
            return update
        }
        return q
    }
    fun updateGuard(q: QuestStateOnServer, event: GameEvent): QuestStateOnServer{
        val base = if (q.step == 0){
            q.copy(step = 1, progressCurrent = 0, progressTarget = 5, isNew = false)
        }else q

        if (base.step == 1 && event is GoldTurnedIn && event.questId == base.questId){
            val newCurrent = (base.progressCurrent + event.amount).coerceAtMost(base.progressTarget)
            val updated = base.copy(progressCurrent = newCurrent, isNew = false)

            if (newCurrent >= base.progressTarget){
                return  updated.copy(step = 2, progressCurrent = 0, progressTarget = 0)
            }
            return updated
        }
        return base
    }
    fun updateYura(q: QuestStateOnServer, event: GameEvent): QuestStateOnServer{
        if (q.step == 0 && event is QuestBranchChosen && event.questId == q.questId) {
        return when (event.branch) {
            QuestBranch.PASS -> q.copy(
                step = 1,
                branch = QuestBranch.HELP,
                progressCurrent = 0,
                progressTarget = 1,
                isNew = false
            )

            QuestBranch.PAY -> q.copy(
                step = 1,
                branch = QuestBranch.THREAD,
                progressCurrent = 0,
                progressTarget = 100000,
                isNew = false
            )

            QuestBranch.NONE -> q
            else -> {q}
        }
    }
    if (q.step == 1 && q.branch == QuestBranch.HELP && event is ItemCollected && event.itemId == "Kontrol_Work") {
        val newCurrent = (q.progressCurrent + event.countAdded).coerceAtMost(q.progressTarget)
        val update = q.copy(progressCurrent = newCurrent, isNew = false)

        if (newCurrent >= q.progressTarget) {
            return update.copy(step = 2, progressCurrent = 0, progressTarget = 0)
        }
        return update
    }
    if (q.step == 1 && q.branch == QuestBranch.THREAD && event is GoldTurnedIn && event.questId == q.questId) {
        val newCurrent = (q.progressCurrent + event.amount).coerceAtMost(q.progressTarget)
        val update = q.copy(progressCurrent = newCurrent, isNew = false)

        if (newCurrent >= q.progressTarget) {
            return update.copy(step = 2, progressCurrent = 0, progressTarget = 0)
        }
        return update
    }
    return q
}
}
class  GameServer{
    private val _events = MutableSharedFlow<GameEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<GameEvent> = _events.asSharedFlow()

    private val _commands = MutableSharedFlow<GameCommand>(extraBufferCapacity = 64)
    val commands: SharedFlow<GameCommand> = _commands.asSharedFlow()

    fun trySend(cmd: GameCommand): Boolean = _commands.tryEmit(cmd)

    private val _players = MutableStateFlow(
        mapOf(
            "Oleg" to PlayerData("Oleg", 0, emptyMap()),
            "Stas" to PlayerData("Stas", 0, emptyMap())
        )
    )
    val players: StateFlow<Map<String, PlayerData>> = _players.asStateFlow()

    private val _questsByPlayer = MutableStateFlow(
        mapOf(
            "Oleg" to initialQuestList(),
            "Stas" to initialQuestList()
        )
    )
    val questsByPlayer: StateFlow<Map<String, List<QuestStateOnServer>>> = _questsByPlayer.asStateFlow()

    fun start(scope: kotlinx.coroutines.CoroutineScope, questSystem: QuestSystem){
        scope.launch{
            commands.collect{
                cmd -> processCommand(cmd, questSystem)
            }
        }
    }
   private  fun setPlayerData(playerId: String, data: PlayerData){
        val map = _players.value.toMutableMap()
        map[playerId] = data
        _players.value = map.toMap()
    }
   private fun getPlayerData(playerId: String): PlayerData{
        return  _players.value[playerId] ?: PlayerData(playerId, 0, emptyMap())
    }
    private fun setQuests(playerId: String, quests: List<QuestStateOnServer>){
        val map  = _questsByPlayer.value.toMutableMap()
        map[playerId] = quests
        _questsByPlayer.value = map.toMap()
    }
    private fun getQuests(playerId: String): List<QuestStateOnServer>{
        return _questsByPlayer.value[playerId] ?: emptyList()
    }
    private suspend fun processCommand(cmd: GameCommand, questSystem: QuestSystem) {
        when (cmd) {
            is CmdOpenQuest -> {
                val list = getQuests(cmd.playerId).toMutableList()

                for (i in list.indices) {
                    if (list[i].questId == cmd.questId) {
                        list[i] = list[i].copy(isNew = false)
                    }
                }
                setQuests(cmd.playerId, list)
                _events.emit(QuestJournalUpdated(cmd.playerId))
            }

            is CmdPinQuest -> {
                val list = getQuests(cmd.playerId).toMutableList()

                for (i in list.indices) {
                    if (list[i].questId == cmd.questId) {
                        list[i] = list[i].copy(isPinned = false)
                    }
                }
                setQuests(cmd.playerId, list)
                _events.emit(QuestJournalUpdated(cmd.playerId))
            }

            is CmdChooseBranch -> {
                val quests = getQuests(cmd.playerId)
                val target = quests.find { it.questId == cmd.questId }

                if (target == null) {
                    _events.emit(ServerMessage(cmd.playerId, "Квест ${cmd.questId} не найден"))
                    return
                }
                val ev = QuestBranchChosen(cmd.playerId, cmd.questId, cmd.branch)
                _events.emit(ev)

                val updated = questSystem.applyEvent(quests, ev)
                setQuests(cmd.playerId, updated)

                _events.emit(QuestJournalUpdated(cmd.playerId))
            }

            is CmdGiveGoldDebug -> {
                val player = getPlayerData(cmd.playerId)
                setPlayerData(cmd.playerId, player.copy(gold = player.gold + cmd.amount))
                _events.emit(ServerMessage(cmd.playerId, "Выдано золото +${cmd.amount}"))
            }

            is CmdTurnInGold -> {
                val player = getPlayerData(cmd.playerId)

                if (player.gold < cmd.amount) {
                    _events.emit(ServerMessage(cmd.playerId, "Недостаточно богат, нужно ${cmd.amount}"))
                    return
                }
                setPlayerData(cmd.playerId, player.copy(gold = player.gold - cmd.amount))

                val ev = GoldTurnedIn(cmd.playerId, cmd.questId, cmd.amount)
                _events.emit(ev)

                val updated = questSystem.applyEvent(getQuests(cmd.playerId), ev)
                setQuests(cmd.playerId, updated)

                _events.emit(QuestJournalUpdated(cmd.playerId))
            }

            is CmdFinishQuest -> {
                finishQuest(cmd.playerId, cmd.questId)
            }

            else -> {}
        }
    }
        private suspend fun finishQuest(playerId: String, questId: String) {
            val list = getQuests(playerId).toMutableList()

            val index = list.indexOfFirst { it.questId == questId }

            if (index == -1) {
                _events.emit(ServerMessage(playerId, "Квест $questId не найден"))
                return
            }

            val q = list[index]

            if (q.status != QuestStatus.ACTIVE) {
                _events.emit(ServerMessage(playerId, "Нельзя завершить $questId статус: ${q.status}"))
            }
            if (q.step != 2) {
                _events.emit(ServerMessage(playerId, "Нельзя завершить $questId - сначала дойди до этапа 2"))
            }
            list[index] = q.copy(
                status = QuestStatus.COMPLETE,
                step = 3,
                isNew = false
            )

            setQuests(playerId, list)
            _events.emit(QuestCompleted(playerId, questId))

            unlockDependentQuests(playerId, questId)

            _events.emit(QuestJournalUpdated(playerId))
        }
    private  suspend fun  unlockDependentQuests(playerId: String, completedQuestId: String){
        val list = getQuests(playerId).toMutableList()
        var changed = false

        for(i in list.indices){
            val q = list[i]

            if(q.status == QuestStatus.LOCKED && q.unlockRequiredQuestId == completedQuestId){
                list[i] = q.copy(
                    status = QuestStatus.ACTIVE,
                    isNew = true
                )
                changed = true

                _events.emit(QuestUnlocked(playerId, q.questId))
            }
        }
        if (changed){
            setQuests(playerId, list)
        }
    }
    }


fun initialQuestList(): List<QuestStateOnServer>{
    return  listOf(
        QuestStateOnServer(
            "q_alchemist",
            "Помочь Хайзенбергу",
            QuestStatus.ACTIVE,
            0,
            QuestBranch.NONE,
            0,
            0,
            true,
            false,
            null
        ),
        QuestStateOnServer(
            "q_guard",
            "Подкупить стражника",
            QuestStatus.LOCKED,
            0,
            QuestBranch.NONE,
            0,
            0,
            false,
            false,
            "q_alchemist"
        ),
        QuestStateOnServer(
            "q_yura",
            "Сдать экзамен у Юры",
            QuestStatus.LOCKED,
            0,
            QuestBranch.NONE,
            0,
            0,
            false,
            false,
            "q_guard"
        )
    )
}

class  HudState{
    val activePlayerIdFlow = MutableStateFlow("Oleg")
    val activePlayerIdUi = mutableStateOf("Oleg")

    val gold = mutableStateOf(0)
    val inventoryText = mutableStateOf("Inventory(empty)")

    val questEntries = mutableStateOf<List<QuestJournalEntry>>(emptyList())
    val selectedQuests = MutableStateFlow<String?>(null)

    val log = mutableStateOf<List<String>>(emptyList())


}
fun hudLog( hud: HudState, text: String){
    hud.log.value = (hud.log.value + text).takeLast(25)
}
fun markerSymbol(marker: QuestMarker): String{
    return  when(marker){
        QuestMarker.NEW -> "🆕"
        QuestMarker.PINNED -> "📌"
        QuestMarker.COMPLETED -> "✅"
        QuestMarker.LOCKED -> "🔒"
        QuestMarker.NONE -> ""
    }
}
fun journalSortRank(entry: QuestJournalEntry): Int{
    return when{
        entry.marker == QuestMarker.PINNED -> 0
        entry.marker == QuestMarker.NEW -> 1
        entry.status == QuestStatus.ACTIVE -> 2
        entry.status == QuestStatus.LOCKED -> 3
        entry.status == QuestStatus.COMPLETE -> 4
        else -> 5
    }
}
fun eventToText(event: GameEvent): String{
    return when(event){
        is QuestBranchChosen -> "QuestBranchChosen ${event.questId} -> ${event.branch}"
        is ItemCollected -> "ItemCollected ${event.itemId} x ${event.countAdded}"
        is GoldTurnedIn -> "GoldTurnedIn ${event.questId} -${event.amount}"
        is QuestCompleted -> "QuestCompleted ${event.questId}"
        is QuestUnlocked -> "QuestUncloked ${event.questId}"
        is QuestJournalUpdated -> "QuestJournalUpdated ${event.playerId}"
        is ServerMessage -> "Server ${event.text}"
        else -> ""
    }
}