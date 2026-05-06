package PlayerMovement

import de.fabmax.kool.KoolApplication           // KoolApplication - запускает Kool-приложение (окно + цикл рендера)
import de.fabmax.kool.addScene                  // addScene - функция "добавь сцену" в приложение (у тебя она просила отдельный импорт)
import de.fabmax.kool.math.Vec3f                // Vec3f - 3D-вектор (x, y, z), как координаты / направление
import de.fabmax.kool.math.deg                  // deg - превращает число в "градусы" (угол)
import de.fabmax.kool.modules.audio.synth.SampleNode
import de.fabmax.kool.scene.*                   // scene.* - Scene, defaultOrbitCamera, addColorMesh, lighting и т.д.
import de.fabmax.kool.modules.ksl.KslPbrShader  // KslPbrShader - готовый PBR-шейдер (материал)
import de.fabmax.kool.util.Color                // Color - цвет (RGBA)
import de.fabmax.kool.util.Time                 // Time.deltaT - сколько секунд прошло между кадрами
import de.fabmax.kool.pipeline.ClearColorLoad   // ClearColorLoad - режим: "не очищай экран, оставь то что уже нарисовано"
import de.fabmax.kool.modules.ui2.*             // UI2: addPanelSurface, Column, Row, Button, Text, dp, remember, mutableStateOf
import de.fabmax.kool.physics.joints.DistanceJoint
import jdk.jfr.DataAmount
import jdk.jfr.StackTrace

import kotlinx.coroutines.launch                    // запуск корутин
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay

// Flow корутины
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
import kotlinx.coroutines.processNextEventInCurrentThread
import kotlinx.serialization.modules.SerializersModule
import physx.character.PxObstacle


import javax.accessibility.AccessibleValue
import javax.management.ValueExp
import kotlin.math.sqrt

import  java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent

import kotlin.math.abs          // Модуль числа
import kotlin.math.atan2        // Угол по X/Z
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.max          // Выбор большего из двух чисел
import kotlin.math.sqrt

//Реализация
// Движение игрока с помощью назначенных клавиш на клавиатуре
// Свободное перемещение, а не по клеткам
// Поворот игрока по направлению движения
// Тестовый объект для взаимодействия игрока с ним
// follow-camera эффект, чтобы игрок оставался в центре сцены на экране


object DesktopKeyboardState{
    private val pressedKeys = mutableSetOf<Int>()
    //наборы кодов клавиш, которые сейчас зажаты
    private val justPressedKeys = mutableSetOf<Int>()
    // наборы клавиш, которые были нажаты только 1 раз
    private  var isInstalled = false
    // флаг-подсказка, чтобы не устанавливать один и тот же dispatcher дважды

    // метод установки перехватчика клавиатуры
    fun install(){
        if (isInstalled) return

        KeyboardFocusManager.getCurrentKeyboardFocusManager()
            .addKeyEventDispatcher (
                object: KeyEventDispatcher{
                    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
                        when (e.id){
                            KeyEvent.KEY_PRESSED -> {
                                // если клавиша ранее не была зажата, значит, это "новое нажатие"
                                // и можно добавит её в justPressedKeys
                                if (!pressedKeys.contains(e.keyCode)){
                                    justPressedKeys.add(e.keyCode)
                                }
                                pressedKeys.add(e.keyCode)
                            }
                            KeyEvent.KEY_RELEASED -> {
                                // Когда клавишу отпускают - удаляем её из общих наборов клавищ
                                pressedKeys.remove(e.keyCode)
                                justPressedKeys.remove(e.keyCode)
                            }
                        }
                        return false
                        // false - не блокировать дальнейшую обработку
                    }
                }
            )
        isInstalled = true
    }
    fun isDown(keyCode: Int): Boolean {
        // Проверка, зажата ли клавишу прямо сейчас
        return keyCode in pressedKeys
    }
    fun consumeJustPressed(keyCode: Int): Boolean{
        // Один раз поймать новое нажатие
        // Если клавиша есть в justPressedKeys
        // тогда вернём true и сразу удалим её оттуда
        // Так клавиши требующие одиночного взаимодействия, будут работать правильно, а не как пулемёт "тыщ тыщ"

        return if(keyCode in justPressedKeys){
            justPressedKeys.remove(keyCode)
            true
        }else{
            false
        }
    }
}
enum class  QuestState{
    START,
    WAIT_HERB,
    GOOD_END,
    EVIL_END
}

enum class WorldObjectType{
    ALCHEMIST,
    HERB_SOURCE,
    CHEST,
    DOOR
}
data class  WorldObjectDef(
    val id: String,
    val type: WorldObjectType,
    val worldX: Float,
    val worldZ: Float,
    val interactRadius: Float
)
data class ObstacleDef(
    val centerX: Float,
    val centerZ: Float,
    val halfSize: Float
)
data class  NpcMemory(
    val hasMet: Boolean,
    val timesTalked: Int,
    val receivedHerb: Boolean
)
data class  PlayerState(
    val playerId: String,
    val worldX: Float,
    val worldZ: Float,

    val yawDeg: Float,

    val moveSpeed: Float,

    val questState: QuestState,
    val inventory: Map<String, Int>,
    val gold: Int,

    val alchemistsMemory: NpcMemory,
    val chestLooted: Boolean,
    val doorOpened: Boolean,

    val currentFocusId: String?,
    val hintText: String,

    val pinnedQuestEnabled: Boolean,
    val pinnedTargetId: String?
)

fun herbCount(player: PlayerState): Int{
    return player.inventory["herb"] ?: 0
}

fun herb(current: Float, target: Float, t: Float): Float{
    return current + (target - current) * t
}

fun distance2d(ax: Float, az: Float, bx: Float, bz: Float): Float{
    val dx = ax - bx
    val dz = az - bz
    return sqrt (dx * dx + dz * dz)
}

//Нормализация 2D вектора
//Зачем нужно: если игрок нажал W + D одновременно, то диагональное движение не должно становиться быстрее прямого
// берём длину вектора и делим на неё x и Z
fun normalizeOrZero(x: Float, z: Float): Pair<Float, Float>{
    val len = sqrt(x * x + z * z)
    return  if (len <= 0.0001f){
        0f to 0f
    } else {
        (x / len) to (z / len)
    }
}
fun computeYawDegDirection(dirX: Float, dirZ: Float): Float{
    // Получение угла Yaw из вектора движения
    // atan2(y, x) или atan2(x, z) - вернёт угол направления
    // В привычном мире
    // -Z - вперёд +Z - назад
    // +X - вправо -X - влево
    // Поэтому используем atan2(dirX, -dirZ)
    // Из этой логики будет:
    // Направление в градусах
    // (0, -1) -> 0 градусов
    // (1, 0) -> 90 градусов
    // (0, 1) -> 180 градусов
    // (-1, 0) -> -90 градусов(или 270)

    val raw = Math.toDegrees(atan2(dirX.toDouble(), (-dirZ.toDouble()))).toFloat()

    return  if(raw < 0f) raw + 360f else raw
}
fun initialPlayerState(playerId: String): PlayerState {
    return if(playerId == "Stas"){
        PlayerState(
            "Stas",
            0f,
            0f,
            0f,
            3.2f,
            QuestState.START,
            emptyMap(),
            2,
            NpcMemory(
                false,
                0,
                false
            ),
            false,
            false,
            null,
            "Подойди к одной из локаций",
            true,
            "alchemist"
        )
    }else{
        PlayerState(
            "Oleg",
            0f,
            0f,
            0f,
            3.2f,
            QuestState.START,
            emptyMap(),
            2,
            NpcMemory(
                false,
                0,
                false
            ),
            false,
            false,
            null,
            "Подойди к одной из локаций",
            true,
            "alchemist"
        )
    }
}
fun computePinnedTargetId(player: PlayerState): String?{
    // Высчитывает, какая игровая цель сейчас активна
    if (!player.pinnedQuestEnabled) return null

    val herbs = herbCount(player)

    return when(player.questState){
        QuestState.START -> "alchemist"

        QuestState.WAIT_HERB -> {
            if (herbs < 3) "herb_source" else "alchemist"
        }

        QuestState.GOOD_END -> {
            if (!player.chestLooted) "reward_chest"
            else if (!player.doorOpened) "door"
            else null
        }

        QuestState.EVIL_END -> null
    }
}
data class DialogueOption(
    val id: String,
    val text: String
)

data class DialogueView(
    val npcId: String,
    val text: String,
    val option: List<DialogueOption>
)
fun buildAlchemistDialogue(player: PlayerState):  DialogueView {
    val herbs = herbCount(player)
    val memory = player.alchemistsMemory

    return when(player.questState){
        QuestState.START -> {
            val greeting =
                if (!memory.hasMet){
                    "О привет"
                }else{
                    "снова ты... я тебя знаю, ты ${player.playerId}"
                }
            DialogueView(
                "Алхимик",
                "$greeting \n Хочешь помочь - принеси травку",
                listOf(
                    DialogueOption("accept_help", "Я принесу траву"),
                    DialogueOption("threat", "травы не будет, гони товар")
                )
            )
        }

        QuestState.WAIT_HERB ->{
            if (herbs < 3){
                DialogueView(
                    "Алхимик",
                    "Недостаточно, надо $herbs/4 травы",
                    emptyList()
                )
            }else{
                DialogueView(
                    "Алхимик",
                    "найс, прет как белый, давай сюда",
                    listOf(
                        DialogueOption("give_herb", "Отдать 4 травы")
                    )
                )
            }
        }

        QuestState.GOOD_END -> {
            val text =
                if (memory.receivedHerb){
                    "Спасибо спасибо"
                }else{
                    "Ты завершил квест, но нпс все забыл..."
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
                "ты проиграл бетмен",
                emptyList()
            )
        }
    }
}
sealed interface GameCommand{
    val playerId: String
}
data class  CmdMoveAxis(
    override val playerId: String,
    val axisX: Float,
    val axisZ: Float,
    val deltaSec: Float
): GameCommand

data class CmdInteract(
    override val playerId: String
): GameCommand
data class CmdChooseDialogueOption(
    override val playerId: String,
    val option: String
): GameCommand
data class  CmdResetPlayer(
    override val playerId: String
): GameCommand
data class  CmdTogglePinnedQuest(
    override val playerId: String
): GameCommand

sealed interface GameEvent{
    val playerId: String
}

data class  PlayerMoved(
    override val playerId: String,
    val newWorldX: Float,
    val newWorldZ: Float
): GameEvent
data class  MovementBlocked(
    override val playerId: String,
    val blockedWorldX: Float,
    val blockedWorldZ: Float,
): GameEvent

data class FocusChanged(
    override val playerId: String,
    val newFocusId: String?
): GameEvent

data class  PinnedTargetChanged(
    override val playerId: String,
    val newTargetId: String
): GameEvent

data class InteractedWithNpc(
    override val playerId: String,
    val npcId: String
): GameEvent

data class InteractedWithChest(
    override val playerId: String,
    val chestId: String
): GameEvent

data class InteractedWithHerbSource(
    override val playerId: String,
    val sourceId: String
): GameEvent
data class QuestStateChanged(
    override val playerId: String,
    val newState: QuestState
): GameEvent

data class  InteractedWithDoor(
    override val playerId: String,
    val newState: QuestState
): GameEvent

data class  NpcMemoryChanged(
    override val playerId: String,
    val memory: NpcMemory
): GameEvent

data class  ServerMessage(
    override val playerId: String,
    val text: String
): GameEvent

class GameServer{
    // координаты препятствий в мире
    private val staticObstacles = listOf(
        ObstacleDef(-1f, 1f, 0.45f),
        ObstacleDef(0f, 1f, 0.45f),
        ObstacleDef(1f, 1f, 0.45f),
        ObstacleDef(1f, 0f, 0.45f)
    )

    private val doorObstacle = ObstacleDef(0f, -3f, 0.45f)

    //Объекты в мире

    val worldObjects = listOf(
        WorldObjectDef(
            "alchemist",
            WorldObjectType.ALCHEMIST,
            -3f,
            0f,
            1.4f
        ),
        WorldObjectDef(
            "herb_source",
            WorldObjectType.HERB_SOURCE,
            3f,
            0f,
            1.4f
            ),
        WorldObjectDef(
            "reward_chest",
            WorldObjectType.CHEST,
            0f,
            3f,
            1.4f
        ),
        WorldObjectDef(
            "door",
            WorldObjectType.DOOR,
            0f,
            -3f,
            1.4f
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

    private  fun updatePlayer(playerId: String, change: (PlayerState) -> PlayerState){
        val oldMap = _players.value
        val oldPlayer = oldMap[playerId] ?: return

        val newPlayer = change(oldPlayer)

        val newMap = oldMap.toMutableMap()
        newMap[playerId] = newPlayer
        _players.value = newMap.toMap()
    }
    fun isPointInsideObstacle(x: Float, z: Float, obstacle: ObstacleDef, playerRadius: Float): Boolean{
        // проверка, сталкивается ли точка игрока с квадратной стеной препятствия
        // Идея - если позиция игрока слишком близко к центру  препятствия (значит он зашёл в препятствие)
        return  abs(x - obstacle.centerX) <= (obstacle.halfSize + playerRadius) &&
                abs(z - obstacle.centerZ) <= (obstacle.halfSize + playerRadius )


    }
}
