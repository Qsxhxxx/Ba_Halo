package name.ba_halo.screen

import name.ba_halo.config.LevelConfig
import name.ba_halo.config.RingStyle
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.client.gui.widget.ClickableWidget
import net.minecraft.client.gui.widget.ElementListWidget
import net.minecraft.client.gui.widget.SliderWidget
import net.minecraft.text.Text
import kotlin.math.roundToInt


class LevelConfigScreen(parent: Screen,val levelConf: LevelConfig): MyScreen(
    Text.of("信标等级${levelConf.level} 环数${levelConf.size}"),parent) {
    override fun init() {
        val listWidget = object: ElementListWidget<WidgetEntry>(client,width,height - 67, 32, height - 35, 40){
            init {
                centerListVertically = false
                setRenderBackground(false)
            }
            public override fun addEntry(entry: WidgetEntry) = super.addEntry(entry)
            override fun getRowWidth() = 370
            override fun getScrollbarPositionX() = width/2 + 180
        }


        levelConf.rings.confirm()
        val left = width/2 - 160

        levelConf.rings.get.forEach {
            val typeButton = ButtonWidget.builder(Text.of(it.style.get.text)){ button ->
                it.style.field = it.style.get.next
                button.message = Text.of(it.style.get.text)
                button tooltip it.style.get.description
            }.position(left,0).size(55,20).build() tooltip it.style.get.description

            val clockwiseButton = ButtonWidget.builder(Text.of(if(it.clockwise.get) "顺" else "逆")){ button ->
                it.clockwise.field = !it.clockwise.get
                button.message = Text.of(if(it.clockwise.get) "顺" else "逆")
            }.position(left + 58,0).size(25,20).build() tooltip "光环旋转方向"

            val radius = slider(it.radius,10f..600f){Text.of("半${it.radius.get.toInt()}")}.apply {
                x = left + 85
                y = 0
                width = 45
            }

            val heightSlider = slider(it.height,60f..500f){Text.of("高${it.height.get.toInt()}")}.apply {
                x = left + 132
                y = 0
                width = 45
            }

            val widthSlider = slider(it.width,0f..5f){Text.of("宽${String.format("%.1f",it.width.get)}")}.apply {
                x = left + 179
                y = 0
                width = 45
            }

            val speed = slider(it.speed,0f..8f){Text.of("速${String.format("%.1f",it.speed.get)}")}.apply {
                x = left + 226
                y = 0
                width = 45
            }

            listWidget.addEntry(WidgetEntry(mutableListOf(
                typeButton,clockwiseButton,radius,heightSlider,widthSlider,speed
            )))
        }
        addDrawableChild(listWidget)
        addDrawableChild(previewButton.also {
            it.width = 150
            it.setPosition(left,height-32)
        })
        addDrawableChild(done.also {
            it.width = 150
            it.setPosition(left + 160,height - 32)
        })
        super.init()
    }

    class WidgetEntry(val widgets: MutableList<ClickableWidget>): ElementListWidget.Entry<WidgetEntry>() {
        private val widgetBaseY = widgets.associateWith { it.y }
        
        override fun render(
            context: DrawContext,
            index: Int,
            y: Int,
            x: Int,
            entryWidth: Int,
            entryHeight: Int,
            mouseX: Int,
            mouseY: Int,
            hovered: Boolean,
            tickDelta: Float
        ) {
            this.widgets.forEach { widget ->
                widget.y = y + (widgetBaseY[widget] ?: 0)
                widget.render(context, mouseX, mouseY, tickDelta)
            }
        }
        override fun children() = this.widgets
        override fun selectableChildren() = this.widgets
    }
}
