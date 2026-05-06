package rotateObject

import de.fabmax.kool.KoolApplication
import de.fabmax.kool.addScene
import de.fabmax.kool.math.deg
import de.fabmax.kool.math.Vec3f
import de.fabmax.kool.modules.ksl.KslPbrShader
import de.fabmax.kool.scene.addColorMesh
import de.fabmax.kool.scene.defaultOrbitCamera
import de.fabmax.kool.modules.ui2.*
import de.fabmax.kool.util.Color
import de.fabmax.kool.util.MdColor
import de.fabmax.kool.scene.geometry.*
import de.fabmax.kool.scene.Scene


fun main() = KoolApplication {
    addScene {
        // Настройка камеры
        defaultOrbitCamera()

        // Создаём куб с разными цветами граней
        val cubeNode = addColorMesh {
            generate {
                // Создаем куб с указанием цветов для каждой вершины
                cube {
                    colored()   // правильный способ для разноцветного куба (или color(Color.RED) если нужен один цвет)
                }
            }
            shader = KslPbrShader {
                color { vertexColor() }
                metallic(0f)
                roughness(0.25f)
            }
        }

        // Освещение
        lighting.singleDirectionalLight {
            setup(Vec3f(-1f, -1f, -1f))
            setColor(Color.WHITE, 5f)
        }


        setupUiScene(Scene.DEFAULT_CLEAR_COLOR)

        addPanelSurface(
            colors = Colors.singleColorLight(MdColor.BLUE_GREY)
        ) {
            modifier
                .size(300.dp, 160.dp)
                .align(AlignmentX.End, AlignmentY.Top)
                .margin(16.dp)
                .background(RoundRectBackground(colors.background, 12.dp))

            Column {
                modifier
                    .alignX(AlignmentX.Center)
                    .margin(16.dp)

                // Заголовок
                Text("Управление вращением") {
                    modifier
                        .margin(bottom = 16.dp)
                        .font(sizes.largeText)          // ← ИСПРАВЛЕНО: Font.poppins не существует
                        .textColor(Color.WHITE)
                }

                // Горизонтальное расположение кнопок
                Row {
                    modifier
                        .alignX(AlignmentX.Center)
                        .margin(bottom = 16.dp)

                    // Кнопка "Влево" (вращение вокруг оси Y)
                    Button("◀ -10°") {
                        modifier
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .margin(end = 8.dp)
                            .font(sizes.largeText)          // ← ИСПРАВЛЕНО
                            .onClick {
                                // Вращение на -10 градусов вокруг вертикальной оси Y
                                cubeNode.transform.rotate((-10f).deg, Vec3f.Y_AXIS)
                            }
                    }

                    // Кнопка "Вправо" (вращение вокруг оси Y)
                    Button("+10° ▶") {
                        modifier
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .margin(start = 8.dp)
                            .font(sizes.largeText)          // ← ИСПРАВЛЕНО
                            .onClick {
                                // Вращение на +10 градусов вокруг вертикальной оси Y
                                cubeNode.transform.rotate(10f.deg, Vec3f.Y_AXIS)
                            }
                    }
                }

                // Дополнительные кнопки для вращения по другим осям
                Row {
                    modifier.alignX(AlignmentX.Center)

                    Button("Вверх ▲ (X)") {
                        modifier
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .margin(end = 4.dp)
                            .font(sizes.largeText)          // ← ИСПРАВЛЕНО
                            .onClick {
                                cubeNode.transform.rotate(10f.deg, Vec3f.X_AXIS)
                            }
                    }

                    Button("Вниз ▼ (X)") {
                        modifier
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .margin(start = 4.dp, end = 4.dp)
                            .font(sizes.largeText)          // ← ИСПРАВЛЕНО
                            .onClick {
                                cubeNode.transform.rotate((-10f).deg, Vec3f.X_AXIS)
                            }
                    }

                    Button("Сброс") {
                        modifier
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .margin(start = 4.dp)
                            .font(sizes.largeText)          // ← ИСПРАВЛЕНО
                            .onClick {
                                // Сброс вращения: устанавливаем идентичную трансформацию
                                cubeNode.transform.setIdentity()
                            }
                    }
                }
            }
        }
    }
}