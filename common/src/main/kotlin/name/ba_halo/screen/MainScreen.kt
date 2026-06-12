package name.ba_halo.screen

import name.ba_halo.config.Config
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.client.gui.widget.GridWidget
import net.minecraft.client.gui.widget.SimplePositioningWidget
import net.minecraft.text.Text



class MainScreen(parent: Screen?): MyScreen(Text.of("光环设置"),parent) {
    override fun close() {
        client?.setScreen(parent)
        Config.save()
    }
    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)
    }
    val chooseLevel = ButtonWidget.builder(Text.of("分等级设置")){
        client?.setScreen(LevelChooseScreen(this))
    }.build() tooltip "不同等级的信标的特定配置"
    val enableHaloDye = ButtonWidget.builder(Text.of(if(conf.enableHaloDye.get) "光环染色:开" else "光环染色:关")){ button ->
        conf.enableHaloDye.field = !conf.enableHaloDye.get
        button.message = Text.of(if(conf.enableHaloDye.get) "光环染色:开" else "光环染色:关")
    }.build() tooltip "开启后光环会根据信标上方的染色玻璃染色，有概率无效"
    val haloBrightness = slider(conf.haloBrightness,0f..1f) { Text.of("光环亮度") }
    val pulseTail = slider(conf.pulseTail,0.1f..1f) { Text.of("脉冲拖尾长度") }
    val ringOpacity = slider(conf.ringOpacity,0f..1f) { Text.of("全局环透明度") } tooltip "所有光环模式的全局透明度设置"
    val spacingCount = slider(conf.spacingCount,4..20) { Text.of("间隔数量:${conf.spacingCount.get}") }



    override fun init() {
        val gridWidget = GridWidget()
        gridWidget.mainPositioner.marginX(5).marginBottom(4).alignHorizontalCenter()
        val adder = gridWidget.createAdder(2)
        listOf(chooseLevel,enableHaloDye,haloBrightness,pulseTail,ringOpacity,spacingCount)
            .forEach { adder.add(it) }
        adder.add(previewButton,2,adder.copyPositioner().marginTop(6))
        adder.add(done,2, adder.copyPositioner().marginTop(6))
        gridWidget.refreshPositions()
        SimplePositioningWidget.setPos(gridWidget, 0, height / 6 - 12,width,height, 0.5f, 0.0f)
        gridWidget.forEachChild(::addDrawableChild)

        super.init()
    }
}
