package name.ba_halo.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import name.ba_halo.MyExpectPlatform
import name.ba_halo.SerializerWrapper
import name.ba_halo.config.RingStyle.Companion.PULSE
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.pathString
import kotlin.properties.ReadWriteProperty
import kotlin.random.Random
import kotlin.reflect.KProperty

@Serializable(with = Config.Serializer::class)
class Config {
    companion object {
        val fileName = "ba-halo-config.json"
        val filePath = MyExpectPlatform.getConfigDirectory().resolve(fileName)
        val file get() = File(filePath.pathString)
        val json = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }
        private val savePending = AtomicBoolean(false)
        
        fun load(): Config {
            return try {
                val content = if (Files.exists(filePath)) {
                    Files.readString(filePath)
                } else {
                    "{}"
                }
                json.decodeFromString<Config>(content)
            } catch (e: Exception) {
                System.err.println("[BAHalo] Failed to load config, using defaults: ${e.message}")
                Config()
            }
        }
        
        val instance by lazy { load() }
        
        fun save() {
            if (savePending.compareAndSet(false, true)) {
                try {
                    val content = json.encodeToString(Serializer, instance)
                    Files.writeString(
                        filePath,
                        content,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE
                    )
                } catch (e: Exception) {
                    System.err.println("[BAHalo] Failed to save config: ${e.message}")
                } finally {
                    savePending.set(false)
                }
            }
        }
    }

    private val levelConfigCache = ConcurrentHashMap<Int, LevelConfig>()
    
    val levels = Conf(mutableMapOf<Int,LevelConfig>()){
        it.filter { it.key == it.value.level }.toMutableMap()
    }
    
    fun getLevelConf(level:Int): LevelConfig{
        return levelConfigCache.getOrPut(level) {
            levels.confirm()
            levels.get[level]?.let { return@getOrPut it }
            LevelConfig(level).also { levels.get[level] = it }
        }
    }
    
    val haloBrightness = Conf(0.5f,rangeConstraint(0f..1f))
    val ringOpacity = Conf(0.8f,rangeConstraint(0f..1f))
    val spacingCount = Conf(8,rangeConstraint(4..20))
    val mixWhite = Conf(0.3f,rangeConstraint(0f..1f))
    val pulseTail = Conf(0.25f,rangeConstraint(0f..1f))
    val enableHaloDye = Conf(true)
    val enableRings = Conf(true)
    val openConfigKey = Conf(334)
    
    fun clearCache() {
        levelConfigCache.clear()
    }
    
    object Serializer: SerializerWrapper<Config, Serializer.Desc>("Config",Desc()){
        class Desc: Descriptor<Config>() {
            val levels = "levels" from {levels.field}
            val haloBrightness = "haloBrightness" from {haloBrightness.field}
            val ringOpacity = "ringOpacity" from {ringOpacity.field}
            val spacingCount = "spacingCount" from {spacingCount.field}
            val mixWhite = "mixWhite" from {mixWhite.field}
            val pulseTail = "pulseTail" from {pulseTail.field}
            val enableHaloDye = "enableHaloDye" from {enableHaloDye.field}
            val enableRings = "enableRings" from {enableRings.field}
            val openConfigKey = "openConfigKey" from {openConfigKey.field}
        }
        override fun Desc.generate() = Config().also {
            it.levels set levels
            it.haloBrightness set haloBrightness
            it.ringOpacity set ringOpacity
            it.spacingCount set spacingCount
            it.mixWhite set mixWhite
            it.pulseTail set pulseTail
            it.enableHaloDye set enableHaloDye
            it.enableRings set enableRings
            it.openConfigKey set openConfigKey
        }
    }
}

class Conf<T : Any>(
    val defaultValue:T,
    val constraint:(T)-> T? = {it}
): ReadWriteProperty<Any?,T?>{
    @Volatile
    private var _field: T? = null
    
    var field: T?
        set(value) {
            if(value != null) constraint(value).let { _field = it }
        }
        get() = _field
    
    val get: T 
        get() = _field ?: defaultValue
    
    fun confirm() { if(_field == null) _field = defaultValue }
    override fun getValue(thisRef: Any?, property: KProperty<*>) = _field
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T?) {_field = value}
    infix fun set(value:T?) { _field = value }
    infix fun set(item: SerializerWrapper.Descriptor.Item<*,T>) { _field = item.nullable }
}

fun <T: Comparable<T>> rangeConstraint(range: ClosedRange<T>):(T)->T = {
    if(it < range.start) range.start
    else if(it > range.endInclusive) range.endInclusive
    else it
}
fun <T: Comparable<T>> rangeConstraint(range:()-> ClosedRange<T>):(T)->T = {
    val range = range()
    if(it < range.start) range.start
    else if(it > range.endInclusive) range.endInclusive
    else it
}

@Serializable(with = LevelConfig.Serializer::class)
class LevelConfig(val level:Int){
    companion object {
        fun ringCount(level:Int) = if(level <= 0) 0 else
            when(level){
                1-> 1
                2-> 2
                3-> 4
                4-> 6
                5-> 8
                6,7 -> 7
                8,9,10 -> 8
                11,12,13,14,15 -> 9
                else -> 10
            }
        
        private val defaultConfigs: Map<Int, List<DefaultRingConfig>> = mapOf(
            1 to listOf(
                DefaultRingConfig(0, 600f, 600f, 1.7154244f, 5, null, 300.81082f, 0.12003801f)
            ),
            2 to listOf(
                DefaultRingConfig(0, 600f, 184.6517f, 0.8588999f, 7, true, 110.54054f, 0.39051944f),
                DefaultRingConfig(1, 600f, 203.26985f, 1.5576435f, 8, null, 162.91597f, 3.0051732f)
            ),
            3 to listOf(
                DefaultRingConfig(0, 600f, 10f, null, 1, true, 107.567566f, null),
                DefaultRingConfig(1, 600f, 46.345543f, 1.3547825f, 4, null, 152.16216f, 3.6362965f),
                DefaultRingConfig(2, 600f, 128.7973f, null, 0, false, 208.64865f, 5f),
                DefaultRingConfig(3, 600f, 296.36053f, null, 6, null, 303.78378f, 0.14257812f)
            ),
            4 to listOf(
                DefaultRingConfig(0, 600f, 72.94289f, 1.0392208f, 7, false, 104.5946f, 0.16216215f),
                DefaultRingConfig(1, 600f, 51.665012f, 0.7461993f, 8, false, 140.27026f, 8f),
                DefaultRingConfig(2, 600f, 120.81809f, 1.3998628f, 4, null, 184.86487f, 1.2739865f),
                DefaultRingConfig(3, 600f, 131.45703f, 1.3547825f, 4, false, 190.6854f, 1.6706926f),
                DefaultRingConfig(4, 600f, 251.14507f, null, 1, false, 238.29012f, 0.8412162f),
                DefaultRingConfig(5, 600f, 394.7707f, null, 6, null, 262.09247f, 0.119932435f)
            ),
            5 to listOf(
                DefaultRingConfig(0, 600f, 10f, 0.135135f, 2, null, 92.702705f, null),
                DefaultRingConfig(1, 600f, 33.91892f, 0.6081081f, 2, null, 140.27026f, null),
                DefaultRingConfig(2, 600f, 113.64865f, 0.8108108f, 5, true, 140.27026f, 0.4864865f),
                DefaultRingConfig(3, 600f, 85.74324f, null, 8, null, 170f, 4.216216f),
                DefaultRingConfig(4, 600f, 169.45946f, 2.3310812f, 0, false, 223.51352f, 4.5405407f),
                DefaultRingConfig(5, 600f, 177.43243f, 2.3310812f, 1, true, 223.51352f, 0.4864865f),
                DefaultRingConfig(6, 600f, 332.9054f, 2.7027028f, null, null, 291.8919f, 5.027027f),
                DefaultRingConfig(7, 600f, 600f, 2.837838f, 6, false, 354.3243f, 0.114054054f)
            )
        )
        
        fun getDefaultRingConfig(level: Int, index: Int): DefaultRingConfig? {
            return defaultConfigs[level]?.getOrNull(index)
        }
    }
    val size:Int get() = ringCount(level)
    val maxRadius get() = 600f
    fun maxRadius(index:Int) = maxRadius

    @Volatile
    private var _rings: MutableList<RingConfig>? = null
    
    val rings = Conf(MutableList(size){ RingConfig(it,maxRadius(it), level) }){
        it.forEach { it.maxRadius = maxRadius(it.ringIndex) }
        if(it.size != size || (it.filterIndexed { index,it-> it.ringIndex != index }.isNotEmpty()))
            MutableList(size){ index ->
                it.firstOrNull { it.ringIndex == index } ?: RingConfig(index,maxRadius(index), level)
            }
        else it
    }
    
    fun getRings(): MutableList<RingConfig> {
        return _rings ?: synchronized(this) {
            _rings ?: rings.get.also { _rings = it }
        }
    }

    object Serializer: SerializerWrapper<LevelConfig, Serializer.Desc>("LevelConfig",Desc()){
        class Desc: Descriptor<LevelConfig>(){
            val rings = "rings" from {rings.field}
            val level = "level" from {level}
        }
        override fun Desc.generate() = LevelConfig(level.orElse(0)).also {
            it.rings set rings
        }
    }
}

data class DefaultRingConfig(
    val index: Int,
    val maxRadius: Float,
    val r: Float,
    val width: Float?,
    val style: Int?,
    val clockwise: Boolean?,
    val height: Float,
    val speed: Float?
)

@Serializable(with = RingConfig.Serializer::class)
class RingConfig(val ringIndex:Int, var maxRadius: Float, private val level: Int = 0){

    private val default: DefaultRingConfig? get() = LevelConfig.getDefaultRingConfig(level, ringIndex)

    val radius = Conf(default?.r ?: if(ringIndex%2 == 0) 95 + ringIndex*50f else 55 + ringIndex*50f,
        rangeConstraint{10f..1000f})

    val rotateCycle = Conf(Random(ringIndex).nextInt(300,400),
        rangeConstraint(-10000..10000))

    val width = Conf(default?.width ?: 2.0f, rangeConstraint(0f..5f))

    val style = Conf(default?.style?.let { RingStyle(it) } ?: PULSE){ it.takeIf { it.isValid } }

    val clockwise = Conf(default?.clockwise ?: true)

    val height = Conf(default?.height ?: 125f, rangeConstraint(60f..800f))

    val speed = Conf(default?.speed ?: 1.00f, rangeConstraint(0f..8f))

    object Serializer: SerializerWrapper<RingConfig, Serializer.Desc>("RingConfig",Desc()){
        class Desc: Descriptor<RingConfig>() {
            val index = "index" from {ringIndex}
            val maxRadius = "maxRadius" from {maxRadius}
            val radius = "r" from {radius.field}
            val rotateCycle = "cycle" from {rotateCycle.field}
            val width = "width" from {width.field}
            val style = "style" from {style.field?.value}
            val clockwise = "clockwise" from {clockwise.field}
            val height = "height" from {height.field}
            val speed = "speed" from {speed.field}
        }
        override fun Desc.generate() = RingConfig(index.orElse(0), maxRadius.orElse(1000f)).also {
            it.radius set radius
            it.rotateCycle set rotateCycle
            it.width set width
            it.style set style.nullable?.let { style -> RingStyle(style) }
            it.clockwise set clockwise
            it.height set height
            it.speed set speed
        }
    }
}

@JvmInline
value class RingStyle(val value:Int){
    companion object {
        val PULSE = RingStyle(0)
        val SPACING = RingStyle(1)
        val FLAT = RingStyle(2)
        val STATIC = RingStyle(3)
        val FLICKER = RingStyle(4)
        val EXPAND = RingStyle(5)
        val OUTER_RING = RingStyle(6)
        val INNER_RING = RingStyle(7)
        val DOUBLE_PULSE = RingStyle(8)
    }
    val isValid get() = value in 0..8
    val next get() = RingStyle((value+1) % 9)
    val text get() = when(this){
        PULSE -> "脉冲"
        SPACING -> "间隔"
        FLAT -> "平凡"
        STATIC -> "不透明"
        FLICKER -> "闪烁"
        EXPAND -> "扩散"
        OUTER_RING -> "外环"
        INNER_RING -> "内环"
        DOUBLE_PULSE -> "双重脉冲"
        else -> "未知"
    }
    val description get() = when(this){
        PULSE -> "脉冲旋转效果。高亮部分由不透明度控制。脉冲最尖端不透明度为1"
        SPACING -> "像虚线一样，间隔亮灭"
        FLAT -> "只有半透明底色，无其他效果"
        STATIC -> "只有不透明底色，无其他效果"
        FLICKER -> "光环无基础旋转，透明度周期变化"
        EXPAND -> "无基础旋转，5个光环半径从0逐渐增大到设置半径"
        OUTER_RING -> "大环边缘随机取点，生成不会旋转的小环，大环转动"
        INNER_RING -> "在大环内侧随机取点，生成不会旋转的小环，大环转动"
        DOUBLE_PULSE -> "有脉冲属性，转向相反"
        else -> "未知效果"
    }
}
