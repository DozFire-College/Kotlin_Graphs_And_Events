package cutScenes

import de.fabmax.kool.KoolApplication           // KoolApplication - запускает Kool-приложение (окно + цикл рендера)
import de.fabmax.kool.addScene                  // addScene - функция "добавь сцену" в приложение (у тебя она просила отдельный импорт)
import de.fabmax.kool.math.Vec3f                // Vec3f - 3D-вектор (x, y, z), как координаты / направление
import de.fabmax.kool.math.deg                  // deg - превращает число в "градусы" (угол)
import de.fabmax.kool.scene.*                   // scene.* - Scene, defaultOrbitCamera, addColorMesh, lighting и т.д.
import de.fabmax.kool.modules.ksl.KslPbrShader  // KslPbrShader - готовый PBR-шейдер (материал)
import de.fabmax.kool.util.Color                // Color - цвет (RGBA)
import de.fabmax.kool.util.Time                 // Time.deltaT - сколько секунд прошло между кадрами
import de.fabmax.kool.pipeline.ClearColorLoad   // ClearColorLoad - режим: "не очищай экран, оставь то что уже нарисовано"
import de.fabmax.kool.modules.ui2.*             // UI2: addPanelSurface, Column, Row, Button, Text, dp, remember, mutableStateOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

// Flow корутины
import kotlinx.coroutines.launch                    // запуск корутин
import kotlinx.coroutines.flow.MutableSharedFlow    // радиостанция событий
import kotlinx.coroutines.flow.SharedFlow           // чтение для подписчиков
import kotlinx.coroutines.flow.MutableStateFlow     // табло состояний
import kotlinx.coroutines.flow.StateFlow            // только для чтения
import kotlinx.coroutines.flow.asSharedFlow         // отдать наружу только SharedFlow
import kotlinx.coroutines.flow.asStateFlow          // отдать только StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.flatMapLatest
import realGaneScene.CmdResetPlayer
import realGaneScene.CmdSwitchActivePlayer
import realGaneScene.ServerMessage
import realGaneScene.initialPlayerState


enum class QuestState {
    START,
    WAIT_HERB,
    GOOD_END,
    EVIL_END
}

// -= Типы объектов игрового мира
enum class WorldObjectType{
    ALCHEMIST,
    HERB_SOURCE,
}

// -= Описание объектов в игрровом мире
data class WorldObjectDef(
    val id: String,
    val type: WorldObjectType,
    val x: Float,
    val z: Float,
    val interactRadius: Float
)

data class NpcMemory(
    val hasMet: Boolean, // -= встретил или нет
    val timesTalked: Int, // -= Сколько раз уже поговорил
    val receivedHerb: Boolean,
    val sawPlayerNearSource: Boolean = false,
    val isStopped: Boolean = false,
    val posX: Float = 3f,
    val posZ: Float = 3f
)

data class PlayerState(
    val playerId: String,
    val posX: Float,
    val posZ: Float,
    val questState: QuestState,
    val inventory: Map<String, Int>, // -= примитивный словарь
    val gold: Int,
    val nearAlchemist: Boolean,
    val nearHerbSource: Boolean,
    val alchemistMemory: NpcMemory,
    val currentAreaId: String?,
    val hintText: String, // -= Подсказка что делать и тп

    val inputLocked: Boolean,       // inputLocked - заблокировано ли управление
    val cutsceneActive: Boolean,    // cutsceneActive - идёт ли катсцена
    val cutsceneText: String        // cutsceneText - текущая строка катсцены
)


// -=-=-= Вспомогательные функции =-=-=-
fun herbCount(player: PlayerState): Int{ //> даёт количество herb
    return player.inventory["herb"] ?: 0
}

fun distance2d(ax: Float, az: Float, bx: Float, bz: Float): Float{
    // sqrt((dx * dx) + (dz * dz))
    val dx = ax - bx
    val dz = az - bz
    return kotlin.math.sqrt(dx*dx + dz*dz)
}

fun initialPlayerState(playerId: String): PlayerState {
    return if(playerId == "Stas"){
        PlayerState(
            "Stas",
            0f,
            0f,
            QuestState.START,
            emptyMap(),
            3,
            false,
            false,
            NpcMemory(
                false,
                0,
                false
            ),
            null,
            "Подойди к одной из локаций",
            false,
            false,
            ""
        )
    }else{
        PlayerState(
            "Oleg",
            0f,
            0f,
            QuestState.START,
            emptyMap(),
            3,
            false,
            false,
            NpcMemory(
                false,
                0,
                false
            ),
            null,
            "Подойди к одной из локаций",
            false,
            false,
            ""
        )
    }
}


data class DialogueOption(
    val id: String,
    val text: String
)

data class DialogueView(
    val npcId: String,
    val text: String,
    val options: List<DialogueOption>
)

fun buildAlchemistDialogue(player: PlayerState): DialogueView{
    // Если идёт катсцена - обычный диалог - отключаем
    if (player.cutsceneActive){
        return DialogueView(
            "Алхимик",
            "Сейчас идёт катсцена",
            emptyList()
        )
    }

    if (!player.nearAlchemist){
        return DialogueView(
            "Алхимик",
            "Подойди ближе к алхимику",
            emptyList()
        )
    }

    val herbs = herbCount(player)
    val memory = player.alchemistMemory

    return when(player.questState){
        QuestState.START -> {
            val greeting =
                if (!memory.hasMet) {
                    "Привет, ты кто"
                }else{
                    "Ну что ${player.playerId} я жду?!"
                }
            DialogueView(
                "Алхимик",
                "$greeting\nТащи траву",
                listOf(
                    DialogueOption("accept_help", "Акей"),
                    DialogueOption("threat", "Нит, ты давай")
                )
            )
        }
        QuestState.WAIT_HERB -> {
            if (herbs < 3) {
                DialogueView(
                    "Алхимик",
                    "Мало, мне надо 4 вщто",
                    emptyList()
                )
            }else{
                DialogueView(
                    "Алхимик",
                    "спс",
                    listOf(
                        DialogueOption("give_herb", "Отдать 4 травы")
                    )
                )
            }
        }
        QuestState.GOOD_END -> {
            val text =
                if (memory.receivedHerb){
                    "Ну что, похимичим?!"
                }else{
                    "Ты завершил квест, но память не обновилась, капут"
                }
            DialogueView(
                "Алхимик",
                text,
                emptyList()
            )
        }
        QuestState.EVIL_END -> {
            DialogueView(
                "Алхимик",
                "я с тобой больше не дружу",
                emptyList()
            )
        }
    }
}
sealed interface GameCommand{
    val playerId: String
}
data class CmdMovePlayer(
    override val playerId: String,
    val dx: Float,
    val dz: Float
): GameCommand

data class CmdInteract(
    override val playerId: String
): GameCommand

data class CmdChooseDialogueOption(
    override val playerId: String,
    val optionId: String
): GameCommand


data class CmdResetPlayer(
    override val playerId: String
): GameCommand
sealed interface GameEvent{
    val playerId: String
}

data class EnteredArea(
    override val playerId: String,
    val areaId: String
): GameEvent

data class LeftArea(
    override val playerId: String,
    val areaId: String
): GameEvent

data class InteractedWithNpc(
    override val playerId: String,
    val npcId: String
): GameEvent

data class InteractedWithHerbSource(
    override val playerId: String,
    val sourceId: String
): GameEvent

data class InventoryChanged(
    override val playerId: String,
    val itemId: String,
    val newCount: Int
): GameEvent

data class QuestStateChanged(
    override val playerId: String,
    val newState: QuestState
): GameEvent

data class NpcMemoryChanged(
    override val playerId: String,
    val memory: NpcMemory
): GameEvent

data class ServerMessage(
    override val playerId: String,
    val text: String
): GameEvent
data class CutSceneStarted(
    override val playerId: String,
    val cutsceneId: String
): GameEvent

data class CutSceneStep(
    override val playerId: String,
    val text: String
): GameEvent

data class CutSceneFinished(
    override val playerId: String,
    val cutsceneId: String
): GameEvent

class GameServer {
    val worldObjects = mutableListOf(
        WorldObjectDef(
            "alchemist",
            WorldObjectType.ALCHEMIST,
            -3f,
            0f,
            1.7f
        ),
        WorldObjectDef(
            "herb_source",
            WorldObjectType.HERB_SOURCE,
            3f,
            0f,
            1.7f
        )
    )


    private val _events = MutableSharedFlow<GameEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<GameEvent> = _events.asSharedFlow()

    private val _command = MutableSharedFlow<GameCommand>(extraBufferCapacity = 64)
    val commands: SharedFlow<GameCommand> = _command.asSharedFlow()

    fun trySend(cmd: GameCommand): Boolean = _command.tryEmit(cmd)

    private val _players = MutableStateFlow(
        mapOf(
            "Oleg" to initialPlayerState("Oleg"),
            "Stas" to initialPlayerState("Stas")
        )
    )
    val players: StateFlow<Map<String, PlayerState>> = _players.asStateFlow()


    fun start(scope: kotlinx.coroutines.CoroutineScope) {
        scope.launch {
            commands.collect { cmd ->
                processCommand(cmd)
            }
        }
    }

    private fun setPlayerState(playerId: String, data: PlayerState) {
        val map = _players.value.toMutableMap()
        map[playerId] = data
        _players.value = map.toMap()
    }

    fun getPlayerState(playerId: String): PlayerState {
        return _players.value[playerId] ?: initialPlayerState(playerId)
    }

    private fun updatePlayer(playerId: String, change: (PlayerState) -> PlayerState) {
        val oldMap = _players.value
        val oldPlayer = oldMap[playerId] ?: return

        val newPlayer = change(oldPlayer)

        val newMap = oldMap.toMutableMap()
        newMap[playerId] = newPlayer
        _players.value = newMap.toMap()
    }
    // текущая катсцена  этого игрока
    private val cutsceneJobs = mutableMapOf<String, Job>()

    private  var serverScope: kotlinx.coroutines.CoroutineScope? = null

    private fun nearestObject(player: PlayerState): WorldObjectDef? {
        val candidates = worldObjects.filter { obj ->
            distance2d(player.posX, player.posZ, obj.x, obj.z) <= obj.interactRadius
        }

        return candidates.minByOrNull { obj ->  //> minBy = берёт ближайший объект по расстоянию до игрока | OrNull - если нет таких объектов - вернуть null
            distance2d(player.posX, player.posZ, obj.x, obj.z) <= obj.interactRadius
        }
    }

    private suspend fun refreshPlayerArea(playerId: String) {
        val player = getPlayerState(playerId)
        val nearest = nearestObject(player)

        val oldAreaId = player.currentAreaId
        val newAreaId = nearest?.id

        if (oldAreaId == newAreaId) {
            val newHint =
                when (newAreaId) {
                    "alchemist" -> "Подойди и нажми по алхимику"
                    "herb_source" -> "Собери траву"
                    "chest" -> "Открой сундук"
                    else -> "Подойди к одной из локаций"
                }
            updatePlayer(playerId) { p -> p.copy(hintText = newHint) }
            return
        }

        if (oldAreaId != null) {
            _events.emit(
                LeftArea(
                    playerId,
                    oldAreaId
                )
            ) //> emit - "Сообщи всем подписчикам, что произошло событие LeftArea"
        }

        if (newAreaId != null) {
            _events.emit(EnteredArea(playerId, newAreaId))

            if (newAreaId == "herb_source") {
                updatePlayer(playerId) { p ->
                    val memor = p.alchemistMemory
                    if (!memor.sawPlayerNearSource) {
                        p.copy(alchemistMemory = memor.copy(sawPlayerNearSource = true))
                    } else p
                }
            }

        }

        val newHint =
            when (newAreaId) {
                "alchemist" -> "Подойди и нажми по алхимику"
                "herb_source" -> "Собери траву"
                "chest" -> "Открой сундук"
                else -> "Подойди к одной из локаций"
            }
        updatePlayer(playerId) { p -> p.copy(hintText = newHint, currentAreaId = newAreaId) }
    }

    private suspend fun processCommand(cmd: GameCommand) {
        when (cmd) {
            is CmdMovePlayer -> {
                val player = getPlayerState(cmd.playerId)
                if (player.cutsceneActive){
                    _events.emit(ServerMessage(cmd.playerId, "Управление заблокировано. Идёт катсцена"))
                    return
                }
                updatePlayer(cmd.playerId) { p ->
                    p.copy(posX = p.posX + cmd.dx, posZ = p.posZ + cmd.dz)
                }
                refreshPlayerArea(cmd.playerId)
            }

            is CmdInteract -> {
                val player = getPlayerState(cmd.playerId) //!
                val obj = nearestObject(player)
                val dist = distance2d(player.posX, player.posZ, obj.x, obj.z)
                val herb = herbCount(player)

                if (obj == null) {
                    _events.emit(ServerMessage(cmd.playerId, "Рядом нет объектов для взаимодействия"))
                    return
                }
                if (dist > obj.interactRadius) {
                    _events.emit(ServerMessage(cmd.playerId, "ты куда ушёл?"))
                    return
                }

                when (obj.type) {
                    WorldObjectType.ALCHEMIST -> {
                        val oldMemory = player.alchemistMemory
                        val newMemory = oldMemory.copy(
                            hasMet = true,
                            timesTalked = oldMemory.timesTalked + 1
                        )

                        if (herb < 3 && newMemory.sawPlayerNearSource) {
                            DialogueView(
                                "Алхимик",
                                "а я тебя видел на herb source",
                                emptyList()
                            )
                        }


                        updatePlayer(cmd.playerId) { p ->
                            p.copy(alchemistMemory = newMemory)
                        }

                        _events.emit(InteractedWithNpc(cmd.playerId, obj.id))
                        _events.emit(NpcMemoryChanged(cmd.playerId, newMemory))
                    }

                    WorldObjectType.HERB_SOURCE -> {
                        if (player.questState != QuestState.WAIT_HERB) {
                            _events.emit(ServerMessage(cmd.playerId, "Трава тебе не надо щас, сначала квест"))
                            return
                        }

                        val oldCount = herbCount(player)
                        val newCount = oldCount + 1
                        val newInventory = player.inventory + ("herb" to newCount)

                        updatePlayer(cmd.playerId) { p ->
                            p.copy(inventory = newInventory)
                        }

                        _events.emit(InteractedWithHerbSource(cmd.playerId, obj.id))
                        _events.emit(InventoryChanged(cmd.playerId, "herb", newCount))

                    }


                }
            }

            is CmdChooseDialogueOption -> {
                val player = getPlayerState(cmd.playerId)

                if (player.currentAreaId != "alchemist") {
                    _events.emit(ServerMessage(cmd.playerId, "Сначала подойди к алхимику"))
                    return
                }

                when (cmd.optionId) {
                    "accept_help" -> {
                        if (player.questState != QuestState.START) {
                            _events.emit(ServerMessage(cmd.playerId, "Путь помощи можно выбрать ток в начале квеста"))
                            return
                        }

                        updatePlayer(cmd.playerId) { p ->
                            p.copy(questState = QuestState.WAIT_HERB)
                        }

                        _events.emit(QuestStateChanged(cmd.playerId, QuestState.WAIT_HERB))
                        _events.emit(ServerMessage(cmd.playerId, "Алхимик попросил собрать 3 травы"))
                    }

                    "give_herb" -> {
                        if (player.questState != QuestState.WAIT_HERB) {
                            _events.emit(ServerMessage(cmd.playerId, "Сейчас нельзя сдать траву"))
                            return
                        }

                        val herbs = herbCount(player)


                        if (herbs < 3) {
                            _events.emit(ServerMessage(cmd.playerId, "Недостаточно травы. Нужно 3, у тебя $herbs"))
                            return
                        }

                        val newCount = herbs - 3
                        val newInventory = if (newCount <= 0) {
                            player.inventory - "herb"
                        } else {
                            player.inventory + ("herb" to newCount)
                        }

                        val newMemory = player.alchemistMemory.copy(
                            receivedHerb = true
                        )

                        updatePlayer(cmd.playerId) { p ->
                            p.copy(
                                inventory = newInventory,
                                gold = p.gold + 5,
                                questState = QuestState.GOOD_END,
                                alchemistMemory = newMemory
                            )
                        }
                    }

                    else -> {
                        _events.emit(ServerMessage(cmd.playerId, "Неизвестный формат диалога"))
                    }
                }
            }


                is CmdResetPlayer -> {
                    updatePlayer(cmd.playerId) {_ -> initialPlayerState(cmd.playerId) }
                    _events.emit(ServerMessage(cmd.playerId, "Игрок сброшен к начальному состоянию"))
                }


            }

        }
        private fun startRewardCutscene(playerId: String){
            val scope = serverScope ?: return

            if (cutsceneJobs[playerId]?.isActive == true){
                scope.launch {
                    _events.emit(ServerMessage(playerId, "катсцена уже идёт"))
                }
                return
            }
            val job = scope.launch {
                updatePlayer(playerId){ p ->
                    p.copy(
                        inputLocked = true,
                        cutsceneActive = true,
                        cutsceneText = "Алхимик варит траву"
                    )
            }

                _events.emit(CutSceneStarted(playerId, "alchemists_reward"))
                _events.emit(CutSceneStep(playerId, "Алхимик варит траву"))

                delay(timeMillis = 1200)

                val p1 = getPlayerState(playerId)
                val herbs = herbCount(p1)
                val newCount = herbs - 3

                val newInventory =
                    if (newCount <= 0) p1.inventory - "herb" else p1.inventory + ("herb" to newCount)

                val newMemory = p1.alchemistMemory.copy(
                    receivedHerb = true
                )
        }
    }
}