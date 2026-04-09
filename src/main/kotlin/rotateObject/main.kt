package rotateObject

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



enum class QuestState {
    START,
    WAIT_HERB,
    GOOD_END,
    EVIL_END
}

enum class Facing {
    LEFT,
    RIGHT,
    FORWARD,
    BACK
}

// -= Типы объектов игрового мира
enum class WorldObjectType{
    ALCHEMIST,
    HERB_SOURCE,
}

data class GridPos(
    val x: Int,
    val z: Int
)



// -= Описание объектов в игрровом мире
data class WorldObjectDef(
    val id: String,
    val type: WorldObjectType,
    val x: Int,
    val z: Int,
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
    val gridX: Int,
    val gridZ: Int,
    val questState: QuestState,
    val inventory: Map<String, Int>, // -= примитивный словарь
    val gold: Int,

    val alchemistMemory: NpcMemory,
    val currentAreaId: String?,
    val hintText: String, // -= Подсказка что делать и тп

    val facing: Facing,
    val rotationY: Float = 0f
)


// -=-=-= Вспомогательные функции =-=-=-
fun herbCount(player: PlayerState): Int{ //> даёт количество herb
    return player.inventory["herb"] ?: 0
}

fun facingToYawDeg(facing: Facing): Float{
    // Превращает направление в угол поворота по оси Y
    //> Нужно для визуального отображения поворота куба

    return when (facing){
        Facing.FORWARD -> 0f
        Facing.RIGHT -> 90f
        Facing.BACK -> 180f
        Facing.LEFT -> 270f
    }
}

fun lerp(current: Float, target: Float, t: Float): Float{
    // Линейная интерполяция
    //> Простыми словами - нужна для плавного перемещения current в сторону target
    //>> Формула = current + (target - current) * t
    return current + (target - current) * t
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
            0,
            0,
            QuestState.START,
            emptyMap(),
            0,
            NpcMemory(
                false,
                0,
                false
            ),
            null,
            "Подойди к одной из локаций",
            Facing.FORWARD,
            rotationY = 0f
        )
    }else{
        PlayerState(
            "Oleg",
            0,
            0,
            QuestState.START,
            emptyMap(),
            0,
            NpcMemory(
                false,
                0,
                false
            ),
            null,
            "Подойди к одной из локаций",
            Facing.FORWARD,
            rotationY = 0f
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
data class CmdStepMove(
    override val playerId: String,
    val stepX: Int,
    val stepZ: Int
): GameCommand

data class CmdRotatePlayer(
    override val playerId: String,
    val dAngle: Float
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

data class PlayerMoved(
    override val playerId: String,
    val newGridX: Int,
    val newGridZ: Int
): GameEvent

data class MovedBlocked(
    override val playerId: String,
    val blockedX: Int,
    val blockedZ: Int
): GameEvent

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

class GameServer{
    // размер карты, игрок может ходить только в её пределах

    private val minX = -5
    private val maxX = 5
    private val minZ = -4
    private val maxZ = 4

    // Подготовка клеток на которые нельзя зайти (занятые)
    private val blockedCells = setOf(
        GridPos(-1, 1),
        GridPos(0, 1),
        GridPos(1, 1),
        GridPos(1, 0),
        GridPos(-2, 0) ////////////////
    )
    // Имитация маленькой стены для проверки запрета хольбы при упоре в неё

    val worldObjects = mutableListOf(
        WorldObjectDef(
            "alchemist",
            WorldObjectType.ALCHEMIST,
            -3,
            0,
            1.7f
        ),
        WorldObjectDef(
            "herb_source",
            WorldObjectType.HERB_SOURCE,
            3,
            0,
            1.7f
        )

    )

    private  val _events = MutableSharedFlow<GameEvent>(extraBufferCapacity = 64)
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


    fun start(scope: kotlinx.coroutines.CoroutineScope){
        scope.launch {
            commands.collect{cmd ->
                processCommand(cmd)
            }
        }
    }
    private fun setPlayerState(playerId: String, data: PlayerState){
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

    private fun isCellInsideMap(x: Int, z: Int): Boolean{
        // Находится ли клетка для перемещения в допустимой карте
        return x in minX..maxX && z in minZ..maxZ
        // x in minX..maxX - "x входит в диапазон от minX до maxX"
    }

    private fun isCellBlocked(x: Int, z: Int): Boolean{
        // проверка запрещена ли клетка для входа в неё
        return GridPos(x, z) in blockedCells
    }

    // cutsceneJobs[playerId] - текущая катсцена этого игрока
    private val cutsceneJobs = mutableMapOf<String, Job>()

    private var serverScope: kotlinx.coroutines.CoroutineScope? = null

    private fun nearestObject(player: PlayerState): WorldObjectDef? {
        val px = player.gridX.toFloat()
        val pz = player.gridZ.toFloat()

        val candidates = worldObjects.filter { obj ->
            distance2d(player.gridX.toFloat(), player.gridZ.toFloat(), obj.x.toFloat(), obj.z.toFloat()) <= obj.interactRadius
        }


        return candidates.minByOrNull { obj ->  //> minBy = берёт ближайший объект по расстоянию до игрока | OrNull - если нет таких объектов - вернуть null
            distance2d(player.gridX.toFloat(), player.gridZ.toFloat(), obj.x.toFloat(), obj.z.toFloat())
        }
    }

    private suspend fun refreshPlayerArea(playerId: String){
        val player = getPlayerState(playerId)
        val nearest = nearestObject(player)

        val oldAreaId = player.currentAreaId
        val newAreaId = nearest?.id

        if (oldAreaId == newAreaId) {
            val newHint =
                when (newAreaId) {
                    "alchemist" -> "Подойди и нажми по алхимику"
                    "herb_source" -> "Собери траву"
                    else -> "Подойди к одной из локаций"
                }
            updatePlayer(playerId) {p -> p.copy(hintText = newHint)}
            return
        }

        if(oldAreaId != null) {
            _events.emit(LeftArea(playerId, oldAreaId)) //> emit - "Сообщи всем подписчикам, что произошло событие LeftArea"
        }

        if (newAreaId != null) {
            _events.emit(EnteredArea(playerId, newAreaId))

            if (newAreaId == "herb_source") {
                updatePlayer(playerId) { p ->
                    val memor = p.alchemistMemory
                    if (!memor.sawPlayerNearSource) {
                        p.copy(alchemistMemory = memor.copy(sawPlayerNearSource = true))
                    }else p
                }
            }

        }

        val newHint =
            when (newAreaId) {
                "alchemist" -> "Подойди и нажми по алхимику"
                "herb_source" -> "Собери траву"
                else -> "Подойди к одной из локаций"
            }
        updatePlayer(playerId) {p -> p.copy(hintText = newHint, currentAreaId = newAreaId)}
    }
    private suspend fun processCommand(cmd: GameCommand) {
        when (cmd) {
            is CmdStepMove -> {
                val player = getPlayerState(cmd.playerId)
                val targetX = player.gridX + cmd.stepX
                val targetZ = player.gridZ + cmd.stepZ

                val newFacing =
                    when {
                        cmd.stepX < 0 -> Facing.LEFT
                        cmd.stepX > 0 -> Facing.RIGHT
                        cmd.stepZ < 0 -> Facing.FORWARD
                        else -> Facing.BACK
                    }

                val midX = player.gridX + cmd.stepX.coerceIn(-1, 1)
                val midZ = player.gridZ + cmd.stepZ.coerceIn(-1, 1)

                if (isCellBlocked(midX, midZ) || isCellBlocked(targetX, targetZ)) {
                    _events.emit(ServerMessage(cmd.playerId, "Путь заблокирован стеной"))
                    _events.emit(MovedBlocked(cmd.playerId, targetX, targetZ))
                    updatePlayer(cmd.playerId){ p -> p.copy(facing = newFacing) }
                    return
                }

                updatePlayer(cmd.playerId){ p ->
                    p.copy(
                        gridX = targetX,
                        gridZ = targetZ,
                        facing = newFacing
                    )
                }
            }
            is CmdRotatePlayer -> {
                updatePlayer(cmd.playerId) { p ->
                    p.copy(rotationY = p.rotationY + cmd.dAngle)
                }
            }
            is CmdInteract -> {
                val player = getPlayerState(cmd.playerId)
                val obj = nearestObject(player)

                if (obj == null){
                    _events.emit(ServerMessage(cmd.playerId, "Рядом нет объектов для взаимодействия"))
                    return
                }

                when (obj.type){
                    WorldObjectType.ALCHEMIST -> {
                        val  oldMemory = player.alchemistMemory
                        val newMemory = oldMemory.copy(
                            hasMet = true,
                            timesTalked = oldMemory.timesTalked + 1
                        )

                        updatePlayer(cmd.playerId) { p ->
                            p.copy(alchemistMemory = newMemory)
                        }

                        _events.emit(InteractedWithNpc(cmd.playerId, obj.id))
                        _events.emit(NpcMemoryChanged(cmd.playerId, newMemory))
                    }

                    WorldObjectType.HERB_SOURCE -> {
                        if (player.questState != QuestState.WAIT_HERB){
                            _events.emit(ServerMessage(cmd.playerId, "Трава тебе сейчас не нужна - сначала возьми квест"))
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

                if (player.currentAreaId != "alchemist"){
                    _events.emit(ServerMessage(cmd.playerId, "Сначала подойди к алхимику"))
                    return
                }

                when(cmd.optionId){
                    "accept_help" -> {
                        if(player.questState != QuestState.START){
                            _events.emit(ServerMessage(cmd.playerId, "Путь помощи можно выбрать ток в начале квеста"))
                            return
                        }

                        updatePlayer(cmd.playerId){ p ->
                            p.copy(questState = QuestState.WAIT_HERB)
                        }

                        _events.emit(QuestStateChanged(cmd.playerId, QuestState.WAIT_HERB))
                        _events.emit(ServerMessage(cmd.playerId, "Алхимик попросил собрать 3 травы"))
                    }
                    "give_herb" ->{
                        if (player.questState != QuestState.WAIT_HERB){
                            _events.emit(ServerMessage(cmd.playerId, "Сейчас нельзя сдать траву"))
                        }

                        val herbs = herbCount(player)

                        if (herbs > 3){
                            _events.emit(ServerMessage(cmd.playerId, "Недостаточно травы"))
                            return
                        }

                        val newCount = herbs - 3
                        val newInventory = if(newCount <= 0) player.inventory - "herb" else player.inventory + ("herb" to newCount)

                        val newMemory = player.alchemistMemory.copy(
                            receivedHerb = true
                        )

                        updatePlayer(cmd.playerId){ p ->
                            p.copy(
                                inventory = newInventory,
                                gold = p.gold + 5,
                                questState = QuestState.GOOD_END,
                                alchemistMemory = newMemory
                            )
                        }

                        _events.emit(InventoryChanged(cmd.playerId, "herb", newCount))
                        _events.emit(NpcMemoryChanged(cmd.playerId, newMemory))
                        _events.emit(QuestStateChanged(cmd.playerId, QuestState.GOOD_END))
                        _events.emit(ServerMessage(cmd.playerId, "Алхимик получил траву и выдал тебе золото"))
                    }
                    else -> {
                        _events.emit(ServerMessage(cmd.playerId,"Неизвестный формат диалога"))
                    }
                }
            }


            is CmdResetPlayer -> {
                updatePlayer(cmd.playerId) {_ -> initialPlayerState(cmd.playerId)}
                _events.emit(ServerMessage(cmd.playerId, "Игрок сброшен к начальному состоянию"))
            }
        }
    }
}
class HudState{
    val activePlayerIdFlow = MutableStateFlow("Oleg")
    val activePlayerIdUi = mutableStateOf("Oleg")
    val playerSnapShot = mutableStateOf(initialPlayerState("Oleg"))
    val log = mutableStateOf<List<String>>(emptyList())
}

fun hudLog(hud: HudState, line: String){
    hud.log.value = (hud.log.value + line).takeLast(20)
}

fun formatInventory(player: PlayerState): String{
    return if (player.inventory.isEmpty()){
        "Inventory: (пусто)"
    }else{
        "Inventory:" + player.inventory.entries.joinToString { "${it.key}: ${it.value}" }
    }
}

fun currentObjective(player: PlayerState): String{
    val herbs = herbCount(player)

    return when(player.questState){
        QuestState.START -> "подойди к алхимику и начни разговор"
        QuestState.WAIT_HERB -> {
            if (herbs < 3) "Собери 3 травы. сейчас $herbs /3"
            else "Вернись к алхимику и отдай 3 травы"
        }
        QuestState.GOOD_END -> "Квест завершён хорошо"
        QuestState.EVIL_END -> "Квест завершён плохо"
    }
}


fun currentZoneText(player: PlayerState): String{
    return when(player.currentAreaId){
        "alchemist" -> "зона: алхимик"
        "herb_source" -> "Зона источника травы"
        "chest" -> "Зона: Фундук"
        else -> "Без зоны :("
    }
}

fun formatMemory(memory: NpcMemory): String{
    return "Встретился:${memory.hasMet} | Сколько раз поговорил: ${memory.timesTalked} | отдал траву: ${memory.receivedHerb}"
}

fun eventToText(e: GameEvent): String{
    return when(e){
        is PlayerMoved -> "PlayerMoved (${e.newGridX}, ${e.newGridZ})"
        is MovedBlocked -> "MovedBlocked (${e.blockedX}, ${e.blockedZ})"
        is EnteredArea -> "EnteredArea ${e.areaId}"
        is LeftArea -> "LeftArea ${e.areaId}"
        is InteractedWithNpc -> "InteractedWithNpc ${e.npcId}"
        is InteractedWithHerbSource -> "InteractedWithHerbSource ${e.sourceId}"
        is InventoryChanged -> "InventoryChanged ${e.itemId} -> ${e.newCount}"
        is QuestStateChanged -> "QuestStateChanged ${e.newState}"
        is NpcMemoryChanged -> "NpcMemoryChanged Встретился = ${e.memory.hasMet}, Сколько раз поговорил = ${e.memory.timesTalked}, Отдал траву = ${e.memory.receivedHerb}"
        is ServerMessage -> "Server: ${e.text}"
    }
}

fun main() = KoolApplication {
    val hud = HudState()
    val server = GameServer()

    addScene {
        defaultOrbitCamera()

        // строим из мелких кубиков
        for (x in -5..5) {
            for (z in -4..4) {
                addColorMesh {
                    generate { cube { colored() } }

                    shader = KslPbrShader {
                        color { vertexColor() }
                        metallic(0f)
                        roughness(0.25f)
                    }
                }.transform.translate(x.toFloat(), -1.2f, z.toFloat())
                // сдвигаем плитку (кубы - пол) в мире
                // y = -1.2f опускаем пол ниж игрока
            }
        }
        val wallCells = listOf(
            GridPos(-1, 1),
            GridPos(0, 1),
            GridPos(1, 1),
            GridPos(1, 0),
            GridPos(-2, 0) ///////

        )

        // Создание стены кубиками
        for (cell in wallCells) {
            addColorMesh {
                generate { cube { colored() } }

                shader = KslPbrShader {
                    color { vertexColor() }
                    metallic(0f)
                    roughness(0.25f)
                }
            }.transform.translate(cell.x.toFloat(), -1.2f, cell.z.toFloat())
        }
        val playerNode = addColorMesh {
            generate {
                cube {
                    colored()
                }
            }
            shader = KslPbrShader {
                color { vertexColor() }
                metallic(0f)
                roughness(0.25f)
            }
        }

        val alchemistNode = addColorMesh {
            generate {
                cube {
                    colored()
                }
            }
            shader = KslPbrShader {
                color { vertexColor() }
                metallic(0f)
                roughness(0.25f)
            }
        }

        alchemistNode.transform.translate(-3f, 0f, 0f)

        val herbNode = addColorMesh {
            generate {
                cube {
                    colored()
                }
            }
            shader = KslPbrShader {
                color { vertexColor() }
                metallic(0f)
                roughness(0.25f)
            }
        }
        herbNode.transform.translate(3f, 0f, 0f)

        lighting.singleDirectionalLight {
            setup(Vec3f(-1f, -1f, 1f))
            setColor(Color.YELLOW, 5f)
        }

        server.start(coroutineScope)

        var renderX = 0f
        var renderZ = 0f
        var lastAppliedX = 0f
        var lastAppliedZ = 0f

        var lastAppliedYaw = 0f
        // yaw - какой поворот уже был применён к playerNode

        playerNode.onUpdate {
            val activeId = hud.activePlayerIdFlow.value
            val player = server.getPlayerState(activeId)

            val targetX = player.gridX.toFloat()
            val targetZ = player.gridZ.toFloat()


            // Плавность перемещения
            //> Чем больше коэфициент тем быстрее куб переходит на новую клетку
            val speed = Time.deltaT * 8
            val t = if (speed > 1f) 1f else speed

            renderX = lerp(renderX, targetX, t)
            renderZ = lerp(renderZ, targetZ, t)

            val dx = renderX - lastAppliedX
            val dz = renderZ - lastAppliedZ

            playerNode.transform.translate(dx, 0f, dz)
            lastAppliedX = renderX
            lastAppliedZ = renderZ

            // Поворачиваем игрока по направлению
            val targetYaw = facingToYawDeg(player.facing)
            val yawDelta = targetYaw - lastAppliedYaw

            playerNode.transform.rotate(yawDelta.deg, Vec3f.Y_AXIS)

            lastAppliedYaw = targetYaw
        }
    }
    addScene {
        setupUiScene(ClearColorLoad)

        hud.activePlayerIdFlow
            .flatMapLatest { pid ->
                server.players.map { map ->
                    map[pid] ?: initialPlayerState(pid)
                }
            }
            .onEach { player ->
                hud.playerSnapShot.value = player
            }
            .launchIn(coroutineScope)
        hud.activePlayerIdFlow
            .flatMapLatest { pid ->
                server.events.filter { it.playerId == pid }
            }
            .map { event ->
                eventToText(event)
            }
            .onEach { line ->
                hudLog(hud, "[${hud.activePlayerIdFlow.value}] $line")
            }
            .launchIn(coroutineScope)
        addPanelSurface {
            modifier
                .align(AlignmentX.Start, AlignmentY.Top)
                .margin(16.dp)
                .background(RoundRectBackground(Color(0f, 0f, 0f, 0.6f), (12.dp)))
                .padding(12.dp)
            Column {
                val player = hud.playerSnapShot.use()
                val dialogue = buildAlchemistDialogue(player)

                Text("Игрок: ${hud.activePlayerIdUi.use()}") {
                    modifier.margin(bottom = sizes.gap)
                }

                Text("Позиция x=${"%.1f".format(player.gridX)} z=${"%.1f".format(player.gridZ)}") {}
                Text("Смотрит: ${player.facing}") { modifier.margin(bottom = sizes.smallGap) }
                Text("Quest State: ${player.questState}") {
                    modifier.font(sizes.smallText)
                }
                Text(currentObjective(player)) {
                    modifier.font(sizes.smallText)
                }
                Text(formatInventory(player)) {
                    modifier.font(sizes.smallText).margin(bottom = sizes.smallGap)
                }
                Text("Gold: ${player.gold}") {
                    modifier.font(sizes.smallText)
                }
                Text("Hint: ${player.hintText}") {
                    modifier.font(sizes.smallText)
                }
                Text("Npc Memory: ${formatMemory(player.alchemistMemory)}") {
                    modifier.font(sizes.smallText).margin(bottom = sizes.smallGap)
                }

                Row {
                    Button("Сменить игрока") {
                        modifier.margin(end = 8.dp).onClick {
                            val newId = if (hud.activePlayerIdFlow.value == "Oleg") "Stas" else "Oleg"

                            hud.activePlayerIdUi.value = newId
                            hud.activePlayerIdFlow.value = newId
                        }
                    }

                    Button("Сбросить игрока") {
                        modifier.onClick {
                            server.trySend(CmdResetPlayer(player.playerId))
                        }
                    }
                }
                Text("Движение в мире:") { modifier.margin(top = sizes.gap) }

                Row {
                    Button("Лево") {
                        modifier.margin(end = 8.dp).onClick {
                            server.trySend(CmdStepMove(player.playerId, stepX = -1, stepZ = 0))
                        }
                    }
                    Button("Право") {
                        modifier.margin(end = 8.dp).onClick {
                            server.trySend(CmdStepMove(player.playerId, stepX = 1, stepZ = 0))
                        }
                    }
                    Button("Вперёд") {
                        modifier.margin(end = 8.dp).onClick {
                            server.trySend(CmdStepMove(player.playerId, stepX = 0, stepZ = -1))
                        }
                    }
                    Button("Назад") {
                        modifier.margin(end = 8.dp).onClick {
                            server.trySend(CmdStepMove(player.playerId, stepX = 0, stepZ = 1))
                        }
                    }
                    Button("Двойной вперёд ") {
                        modifier.margin(end = 8.dp).onClick {
                            server.trySend(CmdStepMove(player.playerId, stepX = 0, stepZ = -2))
                        }
                    }
                    Button("Поворот") {
                        modifier.margin(end = 8.dp).onClick {
                            val p = hud.playerSnapShot.value
                            server.trySend(CmdRotatePlayer(p.playerId, dAngle = 10f))
                        }
                    }

                    Text("Взаимодействия") { modifier.margin(top = sizes.gap) }
                    Row {
                        Button("Потрогать ближайшего") {
                            modifier.margin(end = 8.dp).onClick {
                                server.trySend(CmdInteract(player.playerId))
                            }
                        }
                    }

                    Text(dialogue.npcId) { modifier.margin(top = sizes.gap) }

                    Text(dialogue.text) { modifier.margin(bottom = sizes.smallGap) }

                    if (dialogue.options.isEmpty()) {
                        Text("Нет доступных вариантов ответа") {
                            modifier.margin(top = sizes.gap).font(sizes.smallText).margin(bottom = sizes.gap)
                        }
                    } else {
                        Row {
                            for (option in dialogue.options) {
                                Button(option.text) {
                                    server.trySend(
                                        CmdChooseDialogueOption(player.playerId, option.id)
                                    )
                                }
                            }
                        }
                    }
                    Text("Лог: ") { modifier.margin(top = sizes.gap, bottom = sizes.gap) }

                    for (line in hud.log.use()) {
                        Text(line) { modifier.font(sizes.smallText) }
                    }


                }
            }
        }
    }
}
