package name.ba_halo

import name.ba_halo.BeaconHaloRenderer.Companion.ArgbFloat.Companion.white
import name.ba_halo.BlueArchiveHaloClient.texture
import name.ba_halo.config.Config
import name.ba_halo.config.LevelConfig
import name.ba_halo.config.RingConfig
import name.ba_halo.config.RingStyle
import name.ba_halo.config.RingStyle.Companion.FLICKER
import name.ba_halo.config.RingStyle.Companion.EXPAND
import name.ba_halo.config.RingStyle.Companion.DOUBLE_PULSE
import name.ba_halo.config.RingStyle.Companion.FLAT
import name.ba_halo.config.RingStyle.Companion.INNER_RING
import name.ba_halo.config.RingStyle.Companion.OUTER_RING
import name.ba_halo.config.RingStyle.Companion.PULSE
import name.ba_halo.config.RingStyle.Companion.SPACING
import name.ba_halo.config.RingStyle.Companion.STATIC
import net.minecraft.block.Block
import net.minecraft.block.Blocks
import net.minecraft.block.GlassBlock
import net.minecraft.block.PaneBlock
import net.minecraft.block.StainedGlassBlock
import net.minecraft.block.StainedGlassPaneBlock
import net.minecraft.block.TintedGlassBlock
import net.minecraft.block.entity.BeaconBlockEntity
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.*
import net.minecraft.client.render.block.entity.BeaconBlockEntityRenderer
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.util.math.RotationAxis
import net.minecraft.util.math.Vec3d
import net.minecraft.util.math.random.LocalRandom
import org.joml.Quaternionf
import org.joml.Vector3f
import org.joml.Vector4f
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*

class BeaconHaloRenderer(ctx: BlockEntityRendererFactory.Context?) : BeaconBlockEntityRenderer(ctx) {

    private data class CachedRingData(
        val angleCount: Int,
        val radius: Float,
        val thickness: Float,
        val height: Float,
        val style: Int,
        val configHash: Int
    )

    private data class RenderCacheKey(
        val entityPos: Long,
        val ringIndex: Int
    )

    companion object {
        private val renderCache = ConcurrentHashMap<RenderCacheKey, CachedRingData>()
        private val cachedSkyBlueSegments = ConcurrentHashMap<RenderCacheKey, Array<Pair<Double, Double>>>()
        private val lastRenderLevels = ConcurrentHashMap<Long, Int>()
        private val lastRenderTimes = ConcurrentHashMap<Long, Long>()
        private val cachedSegmentHeights = ConcurrentHashMap<Long, IntArray>()
        private val cachedRandoms = ConcurrentHashMap<Long, LocalRandom>()
        private const val CACHE_CLEANUP_INTERVAL = 200
        private const val MAX_CACHE_SIZE = 300
        private const val MAX_ENTITY_CACHE_SIZE = 30
        private val lastCacheCleanup = AtomicLong(0L)

        private val cachedAngleCounts = ConcurrentHashMap<Pair<Float, Float>, Int>()

        inline fun MatrixStack.stack(block: () -> Unit) {
            push()
            block()
            pop()
        }

        val BeaconBlockEntity.levelShrink: Int get() {
            BlueArchiveHaloClient.shrinker?.let { return it(this) }
            var shrink = 0
            val world = world ?: return 0
            val pos = pos ?: return 0
            val level = level
            while (shrink < level) {
                val blockState = world.getBlockState(pos.add(0, shrink + 1, 0))
                val block = blockState.block
                // 透明玻璃和玻璃板减少等级，遮光玻璃不减少等级（直接阻止渲染）
                val shrinkLevel = isClearGlassBlock(block) || blockState.isOf(Blocks.GLASS) || blockState.isOf(Blocks.GLASS_PANE)
                if (shrinkLevel) shrink++ else break
            }
            return shrink
        }

        // 检测信标上方是否有遮光玻璃（阻止光柱和光环渲染）
        val BeaconBlockEntity.hasTintedGlass: Boolean get() {
            val world = world ?: return false
            val pos = pos ?: return false
            val level = level
            for (i in 0 until level) {
                val blockState = world.getBlockState(pos.add(0, i + 1, 0))
                if (isTintedGlassBlock(blockState.block)) return true
            }
            return false
        }

        // 检测信标上方是否有白色染色玻璃方块（检测光柱经过的所有方块）
        val BeaconBlockEntity.hasWhiteStainedGlassBlock: Boolean get() {
            val world = world ?: return false
            val pos = pos ?: return false
            // 检测光柱经过的所有方块（从信标上方第一格到世界高度限制）
            val worldHeight = world.height
            for (y in 1..worldHeight) {
                val blockState = world.getBlockState(pos.add(0, y, 0))
                val block = blockState.block
                // 检测是否为白色染色玻璃或白色染色玻璃板
                if (block == Blocks.WHITE_STAINED_GLASS || block == Blocks.WHITE_STAINED_GLASS_PANE) {
                    return true
                }
                // 如果遇到不透明方块（非空气、非玻璃类），停止检测
                if (!blockState.isAir && !isAnyGlassBlock(block) && !blockState.isOf(Blocks.GLASS) && !blockState.isOf(Blocks.GLASS_PANE)) {
                    break
                }
            }
            return false
        }

        // 检测信标上方是否有任何玻璃（检测光柱经过的所有方块）
        val BeaconBlockEntity.hasAnyGlass: Boolean get() {
            val world = world ?: return false
            val pos = pos ?: return false
            // 检测光柱经过的所有方块（从信标上方第一格到世界高度限制）
            val worldHeight = world.height
            for (y in 1..worldHeight) {
                val blockState = world.getBlockState(pos.add(0, y, 0))
                val block = blockState.block
                // 检测是否为任何玻璃
                if (isAnyGlassBlock(block) || blockState.isOf(Blocks.GLASS) || blockState.isOf(Blocks.GLASS_PANE)) {
                    return true
                }
                // 如果遇到不透明方块（非空气、非玻璃类），停止检测
                if (!blockState.isAir && !isAnyGlassBlock(block) && !blockState.isOf(Blocks.GLASS) && !blockState.isOf(Blocks.GLASS_PANE)) {
                    break
                }
            }
            return false
        }

        // 使用位置作为缓存键，不包含等级，确保缓存一致性
        fun seed(entity: BeaconBlockEntity): Long = entity.pos.asLong()

        // 检测方块是否为遮光玻璃（阻止光柱渲染）
        fun isTintedGlassBlock(block: Block): Boolean {
            return block is TintedGlassBlock
        }

        // 检测方块是否为染色玻璃或染色玻璃板（包括白色染色玻璃）
        // 兼容各种mod的染色玻璃：检测是否为StainedGlassBlock或StainedGlassPaneBlock
        fun isStainedGlassBlock(block: Block): Boolean {
            return block is StainedGlassBlock || block is StainedGlassPaneBlock
        }

        // 检测方块是否为透明玻璃或透明玻璃板（非染色玻璃）
        fun isClearGlassBlock(block: Block): Boolean {
            return (block is GlassBlock && block !is StainedGlassBlock && block !is TintedGlassBlock) ||
                   (block is PaneBlock && block !is StainedGlassPaneBlock)
        }

        // 检测方块是否为任何类型的玻璃或玻璃板（用于检测是否有玻璃）
        fun isAnyGlassBlock(block: Block): Boolean {
            return block is GlassBlock || block is PaneBlock || 
                   block is StainedGlassBlock || block is StainedGlassPaneBlock ||
                   block is TintedGlassBlock
        }

        class ArgbFloat(val a: Float, val r: Float, val g: Float, val b: Float) {
            constructor(arr: FloatArray) : this(
                1f,
                if (arr.isNotEmpty()) arr[0] else 1f,
                if (arr.size > 1) arr[1] else 1f,
                if (arr.size > 2) arr[2] else 1f
            )
            constructor(color: Int) : this(
                ((color shr 24) and 0xFF) / 255f,
                ((color shr 16) and 0xFF) / 255f,
                ((color shr 8) and 0xFF) / 255f,
                (color and 0xFF) / 255f
            )

            companion object {
                val white = ArgbFloat(1f, 1f, 1f, 1f)
            }

            fun toInt(): Int {
                val alpha = (a * 255).toInt().coerceIn(0, 255) shl 24
                val red = (r * 255).toInt().coerceIn(0, 255) shl 16
                val green = (g * 255).toInt().coerceIn(0, 255) shl 8
                val blue = (b * 255).toInt().coerceIn(0, 255)
                return alpha or red or green or blue
            }

            operator fun times(other: ArgbFloat) = ArgbFloat(a * other.a, r * other.r, g * other.g, b * other.b)

            fun alpha(alpha: Float) = ArgbFloat(alpha.coerceIn(0f, 1f), r, g, b)

            fun mix(other: ArgbFloat, rate: Float): ArgbFloat {
                if (rate <= 0) return this
                if (rate >= 1) return other
                val thisRate = 1 - rate
                return ArgbFloat(
                    a * thisRate + other.a * rate,
                    r * thisRate + other.r * rate,
                    g * thisRate + other.g * rate,
                    b * thisRate + other.b * rate
                )
            }
        }

        class AngleInfo(val angle: Double, val color: Int) {
            private val sin = sin(angle).toFloat()
            private val cos = cos(angle).toFloat()

            fun vertex(consumer: VertexConsumer, pose: MatrixStack.Entry, radius: Float, y: Float = 0f) {
                val pos = Vector4f(cos * radius, y, sin * radius, 1f)
                pos.mul(pose.positionMatrix)
                
                val alpha = (color shr 24 and 0xFF) / 255f
                val red = (color shr 16 and 0xFF) / 255f
                val green = (color shr 8 and 0xFF) / 255f
                val blue = (color and 0xFF) / 255f

                consumer.vertex(
                    pos.x(), pos.y(), pos.z(),
                    red, green, blue, alpha,
                    0f, 0f,
                    OverlayTexture.DEFAULT_UV,
                    15728880,
                    0f, 1f, 0f
                )
            }
        }

        private fun calculateAngleCountCached(radius: Float, height: Float, entity: BeaconBlockEntity): Int {
            // 简化缓存键，减少计算频率
            val key = Pair(radius, height)
            return cachedAngleCounts.getOrPut(key) {
                // 只在缓存未命中时计算距离
                val cameraPos = MinecraftClient.getInstance().gameRenderer.camera.pos
                val entityPos = entity.pos.toCenterPos()
                val distance = entityPos.add(0.0, height.toDouble(), 0.0).distanceTo(cameraPos)
                calculateAngleCountInternal(radius, height, distance)
            }
        }

        private fun calculateAngleCountInternal(radius: Float, height: Float, distance: Double): Int {
            // 优化分段数计算，减少计算量
            val baseSegments = ((height + radius) * radius * 8 / distance).toInt() // 减少系数，降低分段数
            val minSegments = 240 // 减少最小分段数
            // 减少最大分段数，提高性能
            return max(minSegments, min(1200, baseSegments))
        }

        private fun cleanupCache(currentTime: Long) {
            val lastCleanup = lastCacheCleanup.get()
            if (currentTime - lastCleanup > CACHE_CLEANUP_INTERVAL) {
                if (!lastCacheCleanup.compareAndSet(lastCleanup, currentTime)) {
                    return
                }
                
                if (renderCache.size > MAX_CACHE_SIZE) {
                    val keysToRemove = mutableListOf<RenderCacheKey>()
                    renderCache.forEach { (key, _) ->
                        if (currentTime - lastRenderTimes.getOrDefault(key.entityPos, 0) > 50) {
                            keysToRemove.add(key)
                        }
                    }
                    keysToRemove.forEach { renderCache.remove(it) }
                }
                
                if (cachedSkyBlueSegments.size > MAX_CACHE_SIZE) {
                    val keysToRemove = mutableListOf<RenderCacheKey>()
                    cachedSkyBlueSegments.forEach { (key, _) ->
                        if (currentTime - lastRenderTimes.getOrDefault(key.entityPos, 0) > 50) {
                            keysToRemove.add(key)
                        }
                    }
                    keysToRemove.forEach { cachedSkyBlueSegments.remove(it) }
                }
                
                if (lastRenderLevels.size > MAX_ENTITY_CACHE_SIZE) {
                    val keysToRemove = mutableListOf<Long>()
                    lastRenderLevels.forEach { (entityPos, _) ->
                        if (currentTime - lastRenderTimes.getOrDefault(entityPos, 0) > 500) {
                            keysToRemove.add(entityPos)
                        }
                    }
                    keysToRemove.forEach { 
                        lastRenderLevels.remove(it)
                        lastRenderTimes.remove(it)
                        cachedSegmentHeights.remove(it)
                        cachedRandoms.remove(it)
                    }
                }
                
                if (cachedAngleCounts.size > 100) {
                    cachedAngleCounts.clear()
                }
            }
        }

        fun renderHaloStyle(
            matrices: MatrixStack, consumerProvider: VertexConsumerProvider,
            radius: Float, thickness: Float, @Suppress("UNUSED_PARAMETER") originalHeight: Float, segmentCount: Int,
            colorBy0to1: (Double) -> ArgbFloat
        ) {
            if (thickness <= 0f) return
            // 使用不受雾影响的渲染层
            val consumer = consumerProvider.getBuffer(RenderLayer.getBeaconBeam(texture, true))
            
            matrices.stack {
                val pose = matrices.peek()
                
                val angleInfos = arrayOfNulls<Triple<AngleInfo?, Float, Float>?>(segmentCount)
                
                for (i in 0 until segmentCount) {
                    val angle = 2 * PI * i / segmentCount
                    val color = colorBy0to1(angle / (2 * PI))
                    if (color.a < 0.05f) {
                        angleInfos[i] = null
                    } else {
                        val actualThickness = thickness * color.a
                        val radiusInner = radius - actualThickness / 2
                        val radiusOuter = radius + actualThickness / 2
                        angleInfos[i] = Triple(AngleInfo(angle, color.toInt()), radiusInner, radiusOuter)
                    }
                }

                for (i in 0 until angleInfos.size - 1) {
                    val preData = angleInfos[i] ?: continue
                    val postData = angleInfos[i + 1] ?: continue
                    
                    val pre = preData.first ?: continue
                    val post = postData.first ?: continue
                    val preRadiusInner = preData.second
                    val preRadiusOuter = preData.third
                    val postRadiusInner = postData.second
                    val postRadiusOuter = postData.third
                    
                    val preColor = colorBy0to1(pre.angle / (2 * PI))
                    val postColor = colorBy0to1(post.angle / (2 * PI))
                    val preDynamicHeight = thickness * preColor.a * 0.7f
                    val postDynamicHeight = thickness * postColor.a * 0.7f
                    val avgDynamicHeight = (preDynamicHeight + postDynamicHeight) / 2
                    
                    segment(consumer, pose, pre, post, preRadiusInner, preRadiusOuter, 0f, 0f)
                    segment(consumer, pose, pre, post, preRadiusOuter, postRadiusOuter, 0f, avgDynamicHeight)
                    segment(consumer, pose, pre, post, postRadiusOuter, postRadiusInner, avgDynamicHeight, 0f)
                }
                
                if (angleInfos.isNotEmpty()) {
                    val lastData = angleInfos[angleInfos.size - 1]
                    val firstData = angleInfos[0]
                    if (lastData != null && firstData != null) {
                        val last = lastData.first ?: return@stack
                        val first = firstData.first ?: return@stack
                        val lastRadiusInner = lastData.second
                        val lastRadiusOuter = lastData.third
                        val firstRadiusInner = firstData.second
                        val firstRadiusOuter = firstData.third
                        
                        val lastColor = colorBy0to1(last.angle / (2 * PI))
                        val firstColor = colorBy0to1(first.angle / (2 * PI))
                        val lastDynamicHeight = thickness * lastColor.a * 0.7f
                        val firstDynamicHeight = thickness * firstColor.a * 0.7f
                        val avgDynamicHeight = (lastDynamicHeight + firstDynamicHeight) / 2
                        
                        segment(consumer, pose, last, first, lastRadiusInner, lastRadiusOuter, 0f, 0f)
                        segment(consumer, pose, last, first, lastRadiusOuter, firstRadiusOuter, 0f, avgDynamicHeight)
                        segment(consumer, pose, last, first, firstRadiusOuter, firstRadiusInner, avgDynamicHeight, 0f)
                    }
                }
            }
        }

        fun renderHaloStyleSpaced(
            matrices: MatrixStack, consumerProvider: VertexConsumerProvider,
            length: Double, count: Int, radius: Float, thickness: Float, originalHeight: Float, step: Double,
            colorBy0to1: (Double) -> ArgbFloat, offset: Vector3f? = null, rotation: Quaternionf? = null
        ) {
            if (thickness <= 0f || length <= 0 || count <= 0) return
            val consumer = consumerProvider.getBuffer(RenderLayer.getBeaconBeam(texture, true))
            
            matrices.stack {
                offset?.let { matrices.translate(it.x.toDouble(), it.y.toDouble(), it.z.toDouble()) }
                rotation?.let { matrices.multiply(it) }
                
                val pose = matrices.peek()
                val segmentCount = (360 / step).toInt()
                
                val segmentLength = length
                val gapLength = segmentLength / 4.0
                
                val totalLength = segmentLength + gapLength
                val angleInfos = arrayOfNulls<Triple<AngleInfo?, Float, Float>?>(segmentCount)
                
                for (i in 0 until segmentCount) {
                    val angle = 2 * PI * i / segmentCount
                    val angleInDegrees = Math.toDegrees(angle)
                    val positionInCycle = angleInDegrees % totalLength
                    
                    val baseColor = colorBy0to1(angle / (2 * PI))
                    
                    val adjustedColor: ArgbFloat = if (positionInCycle < segmentLength) {
                        val segmentProgress = positionInCycle / segmentLength
                        val alphaMultiplier = when {
                            segmentProgress < 0.25f -> segmentProgress.toFloat() * 4f
                            segmentProgress > 0.75f -> (1f - segmentProgress.toFloat()) * 4f
                            else -> 1f
                        }
                        baseColor.alpha(baseColor.a * alphaMultiplier)
                    } else {
                        ArgbFloat(0f, baseColor.r, baseColor.g, baseColor.b)
                    }
                    
                    if (adjustedColor.a < 0.05f) {
                        angleInfos[i] = null
                    } else {
                        val actualThickness = thickness * adjustedColor.a
                        val radiusInner = radius - actualThickness / 2
                        val radiusOuter = radius + actualThickness / 2
                        angleInfos[i] = Triple(AngleInfo(angle, adjustedColor.toInt()), radiusInner, radiusOuter)
                    }
                }
                
                for (i in 0 until angleInfos.size - 1) {
                    val preData = angleInfos[i] ?: continue
                    val postData = angleInfos[i + 1] ?: continue
                    
                    val pre = preData.first ?: continue
                    val post = postData.first ?: continue
                    val preRadiusInner = preData.second
                    val preRadiusOuter = preData.third
                    val postRadiusInner = postData.second
                    val postRadiusOuter = postData.third
                    
                    val preColor = colorBy0to1(pre.angle / (2 * PI))
                    val postColor = colorBy0to1(post.angle / (2 * PI))
                    val preDynamicHeight = thickness * preColor.a * 0.7f
                    val postDynamicHeight = thickness * postColor.a * 0.7f
                    val avgDynamicHeight = (preDynamicHeight + postDynamicHeight) / 2
                    
                    segment(consumer, pose, pre, post, preRadiusInner, preRadiusOuter, 0f, 0f)
                    segment(consumer, pose, pre, post, preRadiusOuter, postRadiusOuter, 0f, avgDynamicHeight)
                    segment(consumer, pose, pre, post, postRadiusOuter, postRadiusInner, avgDynamicHeight, 0f)
                }
                
                if (angleInfos.isNotEmpty()) {
                    val lastData = angleInfos[angleInfos.size - 1]
                    val firstData = angleInfos[0]
                    if (lastData != null && firstData != null) {
                        val last = lastData.first ?: return@stack
                        val first = firstData.first ?: return@stack
                        val lastRadiusInner = lastData.second
                        val lastRadiusOuter = lastData.third
                        val firstRadiusInner = firstData.second
                        val firstRadiusOuter = firstData.third
                        
                        val lastColor = colorBy0to1(last.angle / (2 * PI))
                        val firstColor = colorBy0to1(first.angle / (2 * PI))
                        val lastDynamicHeight = thickness * lastColor.a * 0.7f
                        val firstDynamicHeight = thickness * firstColor.a * 0.7f
                        val avgDynamicHeight = (lastDynamicHeight + firstDynamicHeight) / 2
                        
                        segment(consumer, pose, last, first, lastRadiusInner, lastRadiusOuter, 0f, 0f)
                        segment(consumer, pose, last, first, lastRadiusOuter, firstRadiusOuter, 0f, avgDynamicHeight)
                        segment(consumer, pose, last, first, firstRadiusOuter, firstRadiusInner, avgDynamicHeight, 0f)
                    }
                }
            }
        }

        private fun segment(
            consumer: VertexConsumer, pose: MatrixStack.Entry,
            pre: AngleInfo, post: AngleInfo,
            radius: Float, radius1: Float, y: Float, y1: Float
        ) {
            pre.vertex(consumer, pose, radius, y)
            pre.vertex(consumer, pose, radius1, y1)
            post.vertex(consumer, pose, radius1, y1)
            post.vertex(consumer, pose, radius, y)
        }
        
        private fun lerp(a: Double, b: Double, t: Double): Double {
            return a + (b - a) * t
        }
        
        private fun lerp(from: ArgbFloat, to: ArgbFloat, amount: Double): ArgbFloat {
            return ArgbFloat(
                lerp(from.a.toDouble(), to.a.toDouble(), amount).toFloat().coerceIn(0f, 1f),
                lerp(from.r.toDouble(), to.r.toDouble(), amount).toFloat().coerceIn(0f, 1f),
                lerp(from.g.toDouble(), to.g.toDouble(), amount).toFloat().coerceIn(0f, 1f),
                lerp(from.b.toDouble(), to.b.toDouble(), amount).toFloat().coerceIn(0f, 1f)
            )
        }
        
        fun renderHorizontalCircleRing(
            matrices: MatrixStack, consumerProvider: VertexConsumerProvider,
            radius: Float, thickness: Float, height: Float, segmentCount: Int,
            colorBy0to1: (Double) -> ArgbFloat
        ) {
            renderHaloStyle(matrices, consumerProvider, radius, thickness, height, segmentCount, colorBy0to1)
        }
    }

    override fun render(
        entity: BeaconBlockEntity, tickDelta: Float, matrices: MatrixStack,
        vertexConsumers: VertexConsumerProvider, light: Int, overlay: Int
    ) {
        if (!Config.instance.enableRings.get) {
            super.render(entity, tickDelta, matrices, vertexConsumers, light, overlay)
            return
        }
        
        val world = entity.world ?: return
        val gameTime = world.time + tickDelta
        
        val beaconLevel = entity.level
        val levelShrink = entity.levelShrink
        val renderLevel = entity.level - levelShrink
        
        val entityKey = seed(entity)
        
        val segments = entity.beamSegments
        if (segments.isEmpty()) {
            super.render(entity, tickDelta, matrices, vertexConsumers, light, overlay)
            return
        }
        
        lastRenderTimes[entityKey] = gameTime.toLong()
        
        val lastLevel = lastRenderLevels.getOrPut(entityKey) { renderLevel }
        
        if (lastLevel != renderLevel) {
            val renderCacheIterator = renderCache.keys.iterator()
            while (renderCacheIterator.hasNext()) {
                if (renderCacheIterator.next().entityPos == entityKey) {
                    renderCacheIterator.remove()
                }
            }
            
            val skyBlueIterator = cachedSkyBlueSegments.keys.iterator()
            while (skyBlueIterator.hasNext()) {
                if (skyBlueIterator.next().entityPos == entityKey) {
                    skyBlueIterator.remove()
                }
            }
            
            cachedSegmentHeights.remove(entityKey)
            cachedRandoms.remove(entityKey)
            
            lastRenderLevels[entityKey] = renderLevel
        }
        
        if (renderLevel <= 0) {
            super.render(entity, tickDelta, matrices, vertexConsumers, light, overlay)
            return
        }
        
        if (entity.hasTintedGlass) {
            super.render(entity, tickDelta, matrices, vertexConsumers, light, overlay)
            return
        }
        
        val conf = Config.instance.getLevelConf(renderLevel)
        val enableHaloDye = Config.instance.enableHaloDye.get
        
        val hasWhiteStainedGlass = entity.hasWhiteStainedGlassBlock
        val hasAnyGlass = entity.hasAnyGlass
        
        fun calculateGlobalBrightness(value: Float): Float {
            return when {
                value <= 0f -> 0f
                value <= 0.5f -> value * 2f
                else -> 1f + (value - 0.5f) * 6f
            }
        }
        
        val haloBrightness = Config.instance.haloBrightness.get
        val globalBrightness = calculateGlobalBrightness(haloBrightness)
        if (globalBrightness <= 0f) {
            super.render(entity, tickDelta, matrices, vertexConsumers, light, overlay)
            return
        }
        
        val skyBlueWhite = ArgbFloat(1.0f, 0.71f, 0.87f, 0.93f)
        val skyBlueWhiteLight = ArgbFloat(0.8f, 1f, 1f, 1f)
        
        val entitySeed = seed(entity)
        val rand = cachedRandoms.getOrPut(entitySeed) { LocalRandom(entitySeed) }
        
        val cachedHeights = cachedSegmentHeights[entitySeed]
        val segmentHeights = if (cachedHeights != null && cachedHeights.size == segments.size) {
            cachedHeights
        } else {
            val heights = IntArray(segments.size)
            var totalHeight = 0
            segments.forEachIndexed { index, segment ->
                totalHeight += segment.height
                heights[index] = totalHeight
            }
            cachedSegmentHeights[entitySeed] = heights
            heights
        }
        
        fun getBeamColor(index: Int, isSmallRing: Boolean = false): ArgbFloat {
            // 当未启用染色时，使用 skyBlueWhite
            if (!enableHaloDye) return if (isSmallRing) skyBlueWhiteLight else skyBlueWhite

            // 当没有放置任何玻璃时， 使用 skyBlueWhite
            if (!hasAnyGlass) return if (isSmallRing) skyBlueWhiteLight else skyBlueWhite

            // 安全检查：确保 segments 不为空且索引在有效范围内
            if (segments.isEmpty() || index < 0) {
                return if (isSmallRing) skyBlueWhiteLight else skyBlueWhite
            }

            // 获取光柱颜色 - 使用安全索引访问（避免越界崩溃）
            val safeIndex = index.coerceIn(0, segments.size - 1)
            val colorArray = segments[safeIndex].color
            val beamColor = ArgbFloat(colorArray)
            
            // 检测光柱颜色是否为白色（允许一定误差）
            val isBeamWhite = beamColor.r > 0.95f && beamColor.g > 0.95f && beamColor.b > 0.95f
            
            // 如果光柱颜色是白色，且没有白色染色玻璃方块，则使用 skyBlueWhite
            if (isBeamWhite && !hasWhiteStainedGlass) {
                return if (isSmallRing) skyBlueWhiteLight else skyBlueWhite
            }
            
            // 有染色玻璃时，使用光柱颜色
            // 添加20%白色混合和10%明度，使染色看起来更淡
            val colorWithWhite = beamColor.mix(white, 0.2f)
            val brightenedColor = ArgbFloat(
                colorWithWhite.a,
                min(1f, colorWithWhite.r * 1.1f),
                min(1f, colorWithWhite.g * 1.1f),
                min(1f, colorWithWhite.b * 1.1f)
            )
            
            return if (isSmallRing) {
                ArgbFloat(1f, brightenedColor.r * 0.9f, brightenedColor.g * 0.95f, brightenedColor.b * 0.95f)
            } else brightenedColor
        }
        
        fun calculateRotation(cycleTicks: Int, speed: Float = 1f): Float {
            // 当速度为0时，环静止
            if (cycleTicks == 0 || speed == 0f) return 0f
            val abs = cycleTicks.absoluteValue
            val effectiveSpeed = if (speed < 0f) 1f else speed
            val adjustedTime = world.time * effectiveSpeed + tickDelta * effectiveSpeed
            val mod = adjustedTime % abs
            val rad = mod / abs * 2 * PI
            return (if (cycleTicks > 0) rad else 2 * PI - rad).toFloat()
        }

        fun renderRing(ringConf: RingConfig, colorIndex: Int) {
            val baseColor = getBeamColor(colorIndex)
            val globalOpacity = Config.instance.ringOpacity.get
            val effectiveBaseOpacity = 1.0f
            val radius = ringConf.radius.get
            val height = ringConf.height.get
            val width = ringConf.width.get
            val cycleTicks = ringConf.rotateCycle.get
            val style = ringConf.style.get
            val speed = ringConf.speed.get
            val clockwise = ringConf.clockwise.get
            
            val effectiveCycleTicks = if (clockwise) cycleTicks else -cycleTicks
            val rotation = calculateRotation(effectiveCycleTicks, speed)
            
            // 使用固定的高度偏移，避免环上下抖动
            val heightOffset = ((entitySeed + colorIndex) % 100) / 100.0
            
            // 使用entityKey作为缓存键，确保缓存一致性
            val cacheKey = RenderCacheKey(entityKey, colorIndex)
            val cachedData = renderCache.getOrPut(cacheKey) {
                // 计算距离
                val cameraPos = MinecraftClient.getInstance().gameRenderer.camera.pos
                val entityPos = entity.pos.toCenterPos()
                val distance = entityPos.add(0.0, height.toDouble(), 0.0).distanceTo(cameraPos)
                CachedRingData(
                    calculateAngleCountInternal(radius, height, distance),
                    radius,
                    width,
                    height,
                    style.value,
                    ringConf.hashCode()
                )
            }
            val angleCount = cachedData.angleCount
            
            // 缓存 skyBlueSegments 计算
            val skyBlueSegments = cachedSkyBlueSegments.getOrPut(cacheKey) {
                val ringRand = LocalRandom(entitySeed + colorIndex)
                val segmentCount = ringRand.nextInt(5) + 1
                Array(segmentCount) {
                    val start = ringRand.nextDouble()
                    val length = 0.05 + ringRand.nextDouble() * 0.15
                    Pair(start, length)
                }
            }
            
            fun getSkyBlueSegmentPosition(angle0to1: Double): Double? {
                for ((start, length) in skyBlueSegments) {
                    val wrappedAngle = (angle0to1 - start + 1) % 1
                    if (wrappedAngle < length) {
                        return wrappedAngle / length
                    }
                }
                return null
            }
            
            when (style) {
                PULSE -> {
                    val pulseTail = Config.instance.pulseTail.get
                    val b = 1f / pulseTail
                    val colorBy0to1: (Double) -> ArgbFloat = { angle0to1 ->
                        val adjustedAngle = if (!clockwise) 1 - angle0to1 else angle0to1
                        val pulseMultiplier = 1f - b * adjustedAngle.toFloat()
                        // 只在alpha通道应用不透明度系数，保持RGB通道不变
                        val baseOpacityMultiplier = max(0.0f, pulseMultiplier)
                        if (adjustedAngle < 0.1) {
                            val headPos = adjustedAngle / 0.1
                            val overlayOpacity = 0.8f * (1 - headPos.toFloat())
                            val mixedR = baseColor.r * (1 - overlayOpacity) + skyBlueWhite.r * overlayOpacity
                            val mixedG = baseColor.g * (1 - overlayOpacity) + skyBlueWhite.g * overlayOpacity
                            val mixedB = baseColor.b * (1 - overlayOpacity) + skyBlueWhite.b * overlayOpacity
                            ArgbFloat(
                                baseColor.a * globalOpacity * effectiveBaseOpacity * baseOpacityMultiplier,
                                mixedR * globalBrightness,
                                mixedG * globalBrightness,
                                mixedB * globalBrightness
                            )
                        } else {
                            ArgbFloat(
                                baseColor.a * globalOpacity * effectiveBaseOpacity * baseOpacityMultiplier,
                                baseColor.r * globalBrightness,
                                baseColor.g * globalBrightness,
                                baseColor.b * globalBrightness
                            )
                        }
                    }
                    
                    matrices.stack {
                        matrices.translate(0.5, heightOffset + height, 0.5)
                        matrices.multiply(RotationAxis.POSITIVE_Y.rotation(rotation))
                        renderHaloStyle(matrices, vertexConsumers, radius, width, height, angleCount, colorBy0to1)
                    }
                }
                SPACING -> {
                    val colorBy0to1: (Double) -> ArgbFloat = { angle0to1 ->
                        val segmentPos = getSkyBlueSegmentPosition(angle0to1)
                        if (segmentPos != null) {
                            val overlayOpacity = when {
                                segmentPos < 0.5f -> segmentPos.toFloat() * 1.2f
                                else -> 0.6f - (segmentPos.toFloat() - 0.5f) * 1.2f
                            }
                            val mixedR = baseColor.r * (1 - overlayOpacity) + skyBlueWhite.r * overlayOpacity
                            val mixedG = baseColor.g * (1 - overlayOpacity) + skyBlueWhite.g * overlayOpacity
                            val mixedB = baseColor.b * (1 - overlayOpacity) + skyBlueWhite.b * overlayOpacity
                            ArgbFloat(
                                globalOpacity * effectiveBaseOpacity,
                                mixedR * globalBrightness,
                                mixedG * globalBrightness,
                                mixedB * globalBrightness
                            )
                        } else {
                            ArgbFloat(
                                globalOpacity * effectiveBaseOpacity,
                                baseColor.r * globalBrightness,
                                baseColor.g * globalBrightness,
                                baseColor.b * globalBrightness
                            )
                        }
                    }
                    
                    matrices.stack {
                        matrices.translate(0.5, heightOffset + height, 0.5)
                        matrices.multiply(RotationAxis.POSITIVE_Y.rotation(rotation))
                        renderHaloStyleSpaced(
                            matrices, vertexConsumers,
                            180.0 / Config.instance.spacingCount.get,
                            Config.instance.spacingCount.get,
                            radius, width, height,
                            360.0 / angleCount,
                            colorBy0to1
                        )
                    }
                }
                FLAT -> {
                    val colorBy0to1: (Double) -> ArgbFloat = { angle0to1 ->
                        val segmentPos = getSkyBlueSegmentPosition(angle0to1)
                        if (segmentPos != null) {
                            val overlayOpacity = when {
                                segmentPos < 0.5f -> segmentPos.toFloat() * 1.2f
                                else -> 0.6f - (segmentPos.toFloat() - 0.5f) * 1.2f
                            }
                            val mixedR = baseColor.r * (1 - overlayOpacity) + skyBlueWhite.r * overlayOpacity
                            val mixedG = baseColor.g * (1 - overlayOpacity) + skyBlueWhite.g * overlayOpacity
                            val mixedB = baseColor.b * (1 - overlayOpacity) + skyBlueWhite.b * overlayOpacity
                            ArgbFloat(
                                baseColor.a * globalOpacity * effectiveBaseOpacity,
                                mixedR * globalBrightness,
                                mixedG * globalBrightness,
                                mixedB * globalBrightness
                            )
                        } else {
                            ArgbFloat(
                                baseColor.a * globalOpacity * effectiveBaseOpacity,
                                baseColor.r * globalBrightness,
                                baseColor.g * globalBrightness,
                                baseColor.b * globalBrightness
                            )
                        }
                    }
                    
                    matrices.stack {
                        matrices.translate(0.5, heightOffset + height, 0.5)
                        matrices.multiply(RotationAxis.POSITIVE_Y.rotation(rotation))
                        renderHaloStyle(matrices, vertexConsumers, radius, width, height, angleCount, colorBy0to1)
                    }
                }
                STATIC -> {
                    val colorBy0to1: (Double) -> ArgbFloat = { angle0to1 ->
                        val segmentPos = getSkyBlueSegmentPosition(angle0to1)
                        if (segmentPos != null) {
                            val overlayOpacity = when {
                                segmentPos < 0.5f -> segmentPos.toFloat() * 1.2f
                                else -> 0.6f - (segmentPos.toFloat() - 0.5f) * 1.2f
                            }
                            val mixedR = baseColor.r * (1 - overlayOpacity) + skyBlueWhite.r * overlayOpacity
                            val mixedG = baseColor.g * (1 - overlayOpacity) + skyBlueWhite.g * overlayOpacity
                            val mixedB = baseColor.b * (1 - overlayOpacity) + skyBlueWhite.b * overlayOpacity
                            ArgbFloat(
                                baseColor.a * globalOpacity * effectiveBaseOpacity,
                                mixedR * globalBrightness,
                                mixedG * globalBrightness,
                                mixedB * globalBrightness
                            )
                        } else {
                            ArgbFloat(
                                baseColor.a * globalOpacity * effectiveBaseOpacity,
                                baseColor.r * globalBrightness,
                                baseColor.g * globalBrightness,
                                baseColor.b * globalBrightness
                            )
                        }
                    }
                    
                    matrices.stack {
                        matrices.translate(0.5, heightOffset + height, 0.5)
                        matrices.multiply(RotationAxis.POSITIVE_Y.rotation(rotation))
                        renderHaloStyle(matrices, vertexConsumers, radius, width, height, angleCount, colorBy0to1)
                    }
                }
                FLICKER -> {
                    val flickerSpeed = ringConf.speed.get
                    val cycleLength = 160f
                    val effectiveSpeed = if (flickerSpeed <= 0) 1f else flickerSpeed
                    val progress = (gameTime * effectiveSpeed) % cycleLength / cycleLength
                    val blinkMultiplier = when {
                        progress < 0.5f -> progress * 2f
                        else -> (1 - progress) * 2f
                    }
                    
                    // 闪烁系数为0时取消渲染
                    if (blinkMultiplier <= 0.05f) return
                    
                    val colorBy0to1: (Double) -> ArgbFloat = { angle0to1 ->
                        val segmentPos = getSkyBlueSegmentPosition(angle0to1)
                        // 使用平滑的colorAlpha计算，避免在0.5附近突变
                        val colorAlpha = blinkMultiplier
                        if (segmentPos != null) {
                            val overlayOpacity = when {
                                segmentPos < 0.5f -> segmentPos.toFloat() * 1.2f
                                else -> 0.6f - (segmentPos.toFloat() - 0.5f) * 1.2f
                            }
                            val mixedR = baseColor.r * (1 - overlayOpacity) + skyBlueWhite.r * overlayOpacity
                            val mixedG = baseColor.g * (1 - overlayOpacity) + skyBlueWhite.g * overlayOpacity
                            val mixedB = baseColor.b * (1 - overlayOpacity) + skyBlueWhite.b * overlayOpacity
                            // 只在alpha通道应用不透明度系数，保持RGB通道不变
                            ArgbFloat(
                                baseColor.a * globalOpacity * effectiveBaseOpacity * colorAlpha,
                                mixedR * globalBrightness,
                                mixedG * globalBrightness,
                                mixedB * globalBrightness
                            )
                        } else {
                            // 只在alpha通道应用不透明度系数，保持RGB通道不变
                            ArgbFloat(
                                baseColor.a * globalOpacity * effectiveBaseOpacity * colorAlpha,
                                baseColor.r * globalBrightness,
                                baseColor.g * globalBrightness,
                                baseColor.b * globalBrightness
                            )
                        }
                    }
                    
                    // 当不透明度变化为0时，宽度减小到为0
                    val currentWidth = width * blinkMultiplier
                    if (currentWidth < 0.1f) return
                    
                    matrices.stack {
                        matrices.translate(0.5, heightOffset + height, 0.5)
                        matrices.multiply(RotationAxis.POSITIVE_Y.rotation(0f))
                        renderHaloStyle(matrices, vertexConsumers, radius, currentWidth, height, angleCount, colorBy0to1)
                    }
                }
                EXPAND -> {
                    val expandSpeed = ringConf.speed.get
                    if (expandSpeed <= 0) return
                    val cycleLength = 200f
                    val progress = (gameTime * expandSpeed) % cycleLength / cycleLength
                    
                    for (i in 0 until 5) {
                        val ringProgress = (progress + i * 0.2f) % 1f
                        
                        // 根据clockwise决定扩散方向：
                        // 顺时针：半径从0扩散到设置值，宽度固定
                        // 逆时针：半径从设置值收缩到0，宽度从0增长到设置宽度
                        val currentRadius: Float
                        val currentWidth: Float
                        val widthOpacityMultiplier: Float
                        
                        if (clockwise) {
                            currentRadius = radius * ringProgress
                            currentWidth = width
                            widthOpacityMultiplier = 1f - ringProgress
                        } else {
                            currentRadius = radius * (1f - ringProgress)
                            currentWidth = width * ringProgress
                            widthOpacityMultiplier = ringProgress
                        }
                        
                        if (currentRadius < 1f || currentWidth < 0.1f) continue
                        
                        val currentAngleCount = calculateAngleCountCached(currentRadius, height, entity)
                        
                        // 为每个扩散环单独计算 skyBlueSegments
                        val expandCacheKey = RenderCacheKey(entityKey, colorIndex * 10 + i)
                        val skyBlueSegments = cachedSkyBlueSegments.getOrPut(expandCacheKey) {
                            val ringRand = LocalRandom(entitySeed + colorIndex * 10 + i)
                            val segmentCount = ringRand.nextInt(5) + 1
                            Array(segmentCount) {
                                val start = ringRand.nextDouble()
                                val length = 0.05 + ringRand.nextDouble() * 0.15
                                Pair(start, length)
                            }
                        }
                        
                        fun getSkyBlueSegmentPosition(angle0to1: Double): Double? {
                            for ((start, length) in skyBlueSegments) {
                                val wrappedAngle = (angle0to1 - start + 1) % 1
                                if (wrappedAngle < length) {
                                    return wrappedAngle / length
                                }
                            }
                            return null
                        }
                        
                        val currentColor: (Double) -> ArgbFloat = { angle0to1 ->
                            val segmentPos = getSkyBlueSegmentPosition(angle0to1)
                            if (segmentPos != null) {
                                val overlayOpacity = when {
                                    segmentPos < 0.5f -> 0.1f + segmentPos.toFloat() * 1.0f
                                    else -> 0.6f - (segmentPos.toFloat() - 0.5f) * 1.0f
                                }
                                val mixedR = baseColor.r * (1 - overlayOpacity) + skyBlueWhite.r * overlayOpacity
                                val mixedG = baseColor.g * (1 - overlayOpacity) + skyBlueWhite.g * overlayOpacity
                                val mixedB = baseColor.b * (1 - overlayOpacity) + skyBlueWhite.b * overlayOpacity
                                ArgbFloat(
                                    baseColor.a * effectiveBaseOpacity * widthOpacityMultiplier,
                                    mixedR * globalBrightness,
                                    mixedG * globalBrightness,
                                    mixedB * globalBrightness
                                )
                            } else {
                                ArgbFloat(
                                    baseColor.a * effectiveBaseOpacity * widthOpacityMultiplier,
                                    baseColor.r * globalBrightness,
                                    baseColor.g * globalBrightness,
                                    baseColor.b * globalBrightness
                                )
                            }
                        }
                        
                        matrices.stack {
                            matrices.translate(0.5, heightOffset + height, 0.5)
                            matrices.multiply(RotationAxis.POSITIVE_Y.rotation(rotation))
                            renderHaloStyle(matrices, vertexConsumers, currentRadius, currentWidth, height, currentAngleCount, currentColor)
                        }
                    }
                }
                OUTER_RING -> {
                    matrices.stack {
                        matrices.translate(0.5, height.toDouble(), 0.5)
                        matrices.multiply(RotationAxis.POSITIVE_Y.rotation(rotation))
                        
                        val mainColorBy0to1: (Double) -> ArgbFloat = { angle0to1 ->
                            val segmentPos = getSkyBlueSegmentPosition(angle0to1)
                            if (segmentPos != null) {
                                val overlayOpacity = when {
                                    segmentPos < 0.5f -> segmentPos.toFloat() * 1.2f
                                    else -> 0.6f - (segmentPos.toFloat() - 0.5f) * 1.2f
                                }
                                val mixedR = baseColor.r * (1 - overlayOpacity) + skyBlueWhite.r * overlayOpacity
                                val mixedG = baseColor.g * (1 - overlayOpacity) + skyBlueWhite.g * overlayOpacity
                                val mixedB = baseColor.b * (1 - overlayOpacity) + skyBlueWhite.b * overlayOpacity
                                ArgbFloat(
                                    baseColor.a * effectiveBaseOpacity,
                                    mixedR * globalBrightness,
                                    mixedG * globalBrightness,
                                    mixedB * globalBrightness
                                )
                            } else {
                                ArgbFloat(
                                    baseColor.a * effectiveBaseOpacity,
                                    baseColor.r * globalBrightness,
                                    baseColor.g * globalBrightness,
                                    baseColor.b * globalBrightness
                                )
                            }
                        }
                        
                        renderHaloStyle(matrices, vertexConsumers, radius, width, height, angleCount, mainColorBy0to1)
                        
                        // 使用固定种子，确保小环位置稳定
                        // 将renderLevel加入种子，当信标等级变化时小环位置重新计算
                        val outerRand = java.util.Random(entitySeed + colorIndex * 1000L + renderLevel * 100000L)
                        var smallRingCount = outerRand.nextInt(4) + 3
                        
                        val threshold = ((0.5f - 0.2f) / 2f) + 0.2f
                        val relativeSmallRadius = radius * 0.2f
                        if (relativeSmallRadius > radius * threshold && outerRand.nextFloat() < 0.5f && smallRingCount < 6) {
                            smallRingCount++
                        }
                        
                        // 记录已生成的小环圆心和半径，用于检测重叠
                        data class SmallRingInfo(val centerX: Double, val centerZ: Double, val smallRadius: Float)
                        val existingRings = mutableListOf<SmallRingInfo>()
                        
                        for (i in 0 until smallRingCount) {
                            val baseAngle = (outerRand.nextFloat() * 2 * PI).toFloat()
                            val distRange = radius * (1f / 6f + outerRand.nextFloat() * (1f / 3f - 1f / 6f))
                            val sideProb = outerRand.nextFloat()
                            val centerDist = if (sideProb < 0.7f) {
                                distRange
                            } else {
                                -distRange
                            }
                            val smallRadius = radius * (1f / 5f + outerRand.nextFloat() * (2f / 5f - 1f / 5f))
                            
                            val centerX = (radius + centerDist) * cos(baseAngle)
                            val centerZ = (radius + centerDist) * sin(baseAngle)
                            
                            // 检查新小环的圆心是否在已存在的小环内
                            var overlaps = false
                            for (existing in existingRings) {
                                val dx = centerX.toDouble() - existing.centerX
                                val dz = centerZ.toDouble() - existing.centerZ
                                val distance = sqrt(dx * dx + dz * dz)
                                // 如果圆心距离小于已存在小环的半径，则重叠
                                if (distance < existing.smallRadius) {
                                    overlaps = true
                                    break
                                }
                            }
                            if (overlaps) continue
                            
                            // 记录这个小环
                            existingRings.add(SmallRingInfo(centerX.toDouble(), centerZ.toDouble(), smallRadius))
                            
                            // 小环自转速度为0.05，转向使用固定种子
                            val smallRingClockwise = outerRand.nextBoolean()
                            val smallRingCycleTicks = if (smallRingClockwise) 200 else -200
                            val smallRingRotation = calculateRotation(smallRingCycleTicks, 0.05f)
                            
                            // 为小环生成独立的skyBlueSegments
                            // 将renderLevel加入缓存键，确保等级变化时重新生成
                            val smallRingCacheKey = RenderCacheKey(entityKey, colorIndex * 100 + existingRings.size - 1 + renderLevel * 10000)
                            val smallSkyBlueSegments = cachedSkyBlueSegments.getOrPut(smallRingCacheKey) {
                                val smallRand = LocalRandom(entitySeed + colorIndex * 100 + existingRings.size - 1 + renderLevel * 10000)
                                val segmentCount = smallRand.nextInt(5) + 1
                                Array(segmentCount) {
                                    val start = smallRand.nextDouble()
                                    val length = 0.05 + smallRand.nextDouble() * 0.15
                                    Pair(start, length)
                                }
                            }
                            
                            fun getSmallSkyBlueSegmentPosition(angle0to1: Double): Double? {
                                for ((start, length) in smallSkyBlueSegments) {
                                    val wrappedAngle = (angle0to1 - start + 1) % 1
                                    if (wrappedAngle < length) {
                                        return wrappedAngle / length
                                    }
                                }
                                return null
                            }
                            
                            matrices.stack {
                                matrices.translate(centerX.toDouble(), 0.0, centerZ.toDouble())
                                matrices.multiply(RotationAxis.POSITIVE_Y.rotation(smallRingRotation))
                                
                                val smallAngleCount = calculateAngleCountCached(smallRadius, height, entity)
                                val smallAdjustedWidth = width * 0.7f
                                
                                // 小环染色渲染逻辑与大环一样
                                val smallColorBy0to1: (Double) -> ArgbFloat = { angle0to1 ->
                                    val segmentPos = getSmallSkyBlueSegmentPosition(angle0to1)
                                    if (segmentPos != null) {
                                        val overlayOpacity = when {
                                            segmentPos < 0.5f -> segmentPos.toFloat() * 1.2f
                                            else -> 0.6f - (segmentPos.toFloat() - 0.5f) * 1.2f
                                        }
                                        val mixedR = baseColor.r * (1 - overlayOpacity) + skyBlueWhite.r * overlayOpacity
                                        val mixedG = baseColor.g * (1 - overlayOpacity) + skyBlueWhite.g * overlayOpacity
                                        val mixedB = baseColor.b * (1 - overlayOpacity) + skyBlueWhite.b * overlayOpacity
                                        ArgbFloat(
                                            baseColor.a * effectiveBaseOpacity,
                                            mixedR * globalBrightness,
                                            mixedG * globalBrightness,
                                            mixedB * globalBrightness
                                        )
                                    } else {
                                        ArgbFloat(
                                            baseColor.a * effectiveBaseOpacity,
                                            baseColor.r * globalBrightness,
                                            baseColor.g * globalBrightness,
                                            baseColor.b * globalBrightness
                                        )
                                    }
                                }
                                
                                renderHaloStyle(matrices, vertexConsumers, smallRadius, smallAdjustedWidth, height, smallAngleCount, smallColorBy0to1)
                            }
                        }
                        
                        // 同心环必须与某个小环圆心重合
                        if (relativeSmallRadius > radius * threshold && outerRand.nextFloat() < 0.5f && existingRings.isNotEmpty()) {
                            // 随机选择一个小环的圆心作为同心环的圆心
                            val selectedRing = existingRings[outerRand.nextInt(existingRings.size)]
                            val concentricRadius = radius * 0.2f * 4f / 5f
                            val concentricAngleCount = calculateAngleCountCached(concentricRadius, height, entity)
                            val concentricWidth = width * 0.7f
                            
                            // 同心小环自转，使用固定种子
                            val concentricClockwise = outerRand.nextBoolean()
                            val concentricCycleTicks = if (concentricClockwise) 200 else -200
                            val concentricRotation = calculateRotation(concentricCycleTicks, 0.05f)
                            
                            // 为同心小环生成独立的skyBlueSegments
                            // 将renderLevel加入缓存键，确保等级变化时重新生成
                            val concentricCacheKey = RenderCacheKey(entityKey, colorIndex * 100 + 99 + renderLevel * 10000)
                            val concentricSkyBlueSegments = cachedSkyBlueSegments.getOrPut(concentricCacheKey) {
                                val concentricRand = LocalRandom(entitySeed + colorIndex * 100 + 99 + renderLevel * 10000)
                                val segmentCount = concentricRand.nextInt(5) + 1
                                Array(segmentCount) {
                                    val start = concentricRand.nextDouble()
                                    val length = 0.05 + concentricRand.nextDouble() * 0.15
                                    Pair(start, length)
                                }
                            }
                            
                            fun getConcentricSkyBlueSegmentPosition(angle0to1: Double): Double? {
                                for ((start, length) in concentricSkyBlueSegments) {
                                    val wrappedAngle = (angle0to1 - start + 1) % 1
                                    if (wrappedAngle < length) {
                                        return wrappedAngle / length
                                    }
                                }
                                return null
                            }
                            
                            matrices.stack {
                                matrices.translate(selectedRing.centerX.toDouble(), 0.0, selectedRing.centerZ.toDouble())
                                matrices.multiply(RotationAxis.POSITIVE_Y.rotation(concentricRotation))
                                
                                // 同心环染色渲染逻辑与大环一样
                                val concentricColorBy0to1: (Double) -> ArgbFloat = { angle0to1 ->
                                    val segmentPos = getConcentricSkyBlueSegmentPosition(angle0to1)
                                    if (segmentPos != null) {
                                        val overlayOpacity = when {
                                            segmentPos < 0.5f -> segmentPos.toFloat() * 1.2f
                                            else -> 0.6f - (segmentPos.toFloat() - 0.5f) * 1.2f
                                        }
                                        val mixedR = baseColor.r * (1 - overlayOpacity) + skyBlueWhite.r * overlayOpacity
                                        val mixedG = baseColor.g * (1 - overlayOpacity) + skyBlueWhite.g * overlayOpacity
                                        val mixedB = baseColor.b * (1 - overlayOpacity) + skyBlueWhite.b * overlayOpacity
                                        ArgbFloat(
                                            baseColor.a * effectiveBaseOpacity,
                                            mixedR * globalBrightness,
                                            mixedG * globalBrightness,
                                            mixedB * globalBrightness
                                        )
                                    } else {
                                        ArgbFloat(
                                            baseColor.a * effectiveBaseOpacity,
                                            baseColor.r * globalBrightness,
                                            baseColor.g * globalBrightness,
                                            baseColor.b * globalBrightness
                                        )
                                    }
                                }
                                
                                renderHaloStyle(matrices, vertexConsumers, concentricRadius, concentricWidth, height, concentricAngleCount, concentricColorBy0to1)
                            }
                        }
                    }
                }
                INNER_RING -> {
                    matrices.stack {
                        matrices.translate(0.5, height.toDouble(), 0.5)
                        matrices.multiply(RotationAxis.POSITIVE_Y.rotation(rotation))
                        
                        // 使用固定种子，确保小环位置稳定
                        // 将renderLevel加入种子，当信标等级变化时小环位置重新计算
                        val innerRand = java.util.Random(entitySeed + colorIndex * 2000L + renderLevel * 200000L)
                        val innerRingCount = innerRand.nextInt(4) + 1
                        
                        // 记录已生成的小环圆心和半径，用于检测重叠
                        data class InnerSmallRingInfo(val centerX: Double, val centerZ: Double, val smallRadius: Float)
                        val existingInnerRings = mutableListOf<InnerSmallRingInfo>()
                        
                        var ringsCreated = 0
                        var attempts = 0
                        val maxAttempts = innerRingCount * 3
                        
                        while (ringsCreated < innerRingCount && attempts < maxAttempts) {
                            attempts++
                            
                            val centerDist = radius * (1f / 6f + innerRand.nextFloat() * (1f / 3f - 1f / 6f))
                            val baseAngle = innerRand.nextFloat() * 2 * PI
                            val smallRadius = radius * (1f / 16f + innerRand.nextFloat() * (1f / 4f - 1f / 16f))
                            
                            val centerX = centerDist * cos(baseAngle)
                            val centerZ = centerDist * sin(baseAngle)
                            
                            // 检查新小环的圆心是否在已存在的小环内
                            var overlaps = false
                            for (existing in existingInnerRings) {
                                val dx = centerX.toDouble() - existing.centerX
                                val dz = centerZ.toDouble() - existing.centerZ
                                val distance = sqrt(dx * dx + dz * dz)
                                // 如果圆心距离小于已存在小环的半径，则重叠
                                if (distance < existing.smallRadius) {
                                    overlaps = true
                                    break
                                }
                            }
                            
                            if (overlaps) continue
                            
                            // 记录这个小环
                            existingInnerRings.add(InnerSmallRingInfo(centerX.toDouble(), centerZ.toDouble(), smallRadius))
                            
                            // 小环自转速度为0.05，转向使用固定种子
                            val smallRingClockwise = innerRand.nextBoolean()
                            val smallRingCycleTicks = if (smallRingClockwise) 200 else -200
                            val smallRingRotation = calculateRotation(smallRingCycleTicks, 0.05f)
                            
                            // 为小环生成独立的skyBlueSegments
                            // 将renderLevel加入缓存键，确保等级变化时重新生成
                            val smallRingCacheKey = RenderCacheKey(entityKey, colorIndex * 200 + ringsCreated + renderLevel * 20000)
                            val smallSkyBlueSegments = cachedSkyBlueSegments.getOrPut(smallRingCacheKey) {
                                val smallRand = LocalRandom(entitySeed + colorIndex * 200 + ringsCreated + renderLevel * 20000)
                                val segmentCount = smallRand.nextInt(5) + 1
                                Array(segmentCount) {
                                    val start = smallRand.nextDouble()
                                    val length = 0.05 + smallRand.nextDouble() * 0.15
                                    Pair(start, length)
                                }
                            }
                            
                            fun getSmallSkyBlueSegmentPosition(angle0to1: Double): Double? {
                                for ((start, length) in smallSkyBlueSegments) {
                                    val wrappedAngle = (angle0to1 - start + 1) % 1
                                    if (wrappedAngle < length) {
                                        return wrappedAngle / length
                                    }
                                }
                                return null
                            }
                            
                            matrices.stack {
                                matrices.translate(centerX.toDouble(), 0.0, centerZ.toDouble())
                                matrices.multiply(RotationAxis.POSITIVE_Y.rotation(smallRingRotation))
                                
                                val smallAngleCount = calculateAngleCountCached(smallRadius, height, entity)
                                val smallAdjustedWidth = width * 0.6f
                                
                                // 小环染色渲染逻辑与大环一样
                                val smallColorBy0to1: (Double) -> ArgbFloat = { angle0to1 ->
                                    val segmentPos = getSmallSkyBlueSegmentPosition(angle0to1)
                                    if (segmentPos != null) {
                                        val overlayOpacity = when {
                                            segmentPos < 0.5f -> segmentPos.toFloat() * 1.2f
                                            else -> 0.6f - (segmentPos.toFloat() - 0.5f) * 1.2f
                                        }
                                        val mixedR = baseColor.r * (1 - overlayOpacity) + skyBlueWhite.r * overlayOpacity
                                        val mixedG = baseColor.g * (1 - overlayOpacity) + skyBlueWhite.g * overlayOpacity
                                        val mixedB = baseColor.b * (1 - overlayOpacity) + skyBlueWhite.b * overlayOpacity
                                        ArgbFloat(
                                            baseColor.a * effectiveBaseOpacity,
                                            mixedR * globalBrightness,
                                            mixedG * globalBrightness,
                                            mixedB * globalBrightness
                                        )
                                    } else {
                                        ArgbFloat(
                                            baseColor.a * effectiveBaseOpacity,
                                            baseColor.r * globalBrightness,
                                            baseColor.g * globalBrightness,
                                            baseColor.b * globalBrightness
                                        )
                                    }
                                }
                                
                                renderHaloStyle(matrices, vertexConsumers, smallRadius, smallAdjustedWidth, height, smallAngleCount, smallColorBy0to1)
                            }
                            
                            ringsCreated++
                        }
                    }
                }
                DOUBLE_PULSE -> {
                    val innerRadius = radius - radius / 15f
                    val pulseTail = Config.instance.pulseTail.get
                    val b = 1f / pulseTail
                    
                    fun renderSingleRing(currentRadius: Float, direction: Int) {
                        val effectiveDirection = if (clockwise) direction else -direction
                        val currentRotation = calculateRotation(cycleTicks * effectiveDirection, speed)
                        val currentAngleCount = calculateAngleCountCached(currentRadius, height, entity)
                        
                        val currentColor: (Double) -> ArgbFloat = { angle0to1 ->
                            val adjustedAngle = if (effectiveDirection > 0) angle0to1 else 1 - angle0to1
                            val pulseMultiplier = 1f - b * adjustedAngle.toFloat()
                            // 只在alpha通道应用不透明度系数，保持RGB通道不变
                            val baseOpacityMultiplier = max(0.0f, pulseMultiplier)
                            if (adjustedAngle < 0.1) {
                                val headPos = adjustedAngle / 0.1
                                val overlayOpacity = 0.8f * (1 - headPos.toFloat())
                                val mixedR = baseColor.r * (1 - overlayOpacity) + skyBlueWhite.r * overlayOpacity
                                val mixedG = baseColor.g * (1 - overlayOpacity) + skyBlueWhite.g * overlayOpacity
                                val mixedB = baseColor.b * (1 - overlayOpacity) + skyBlueWhite.b * overlayOpacity
                                ArgbFloat(
                                    baseColor.a * effectiveBaseOpacity * baseOpacityMultiplier,
                                    mixedR * globalBrightness,
                                    mixedG * globalBrightness,
                                    mixedB * globalBrightness
                                )
                            } else {
                                ArgbFloat(
                                    baseColor.a * effectiveBaseOpacity * baseOpacityMultiplier,
                                    baseColor.r * globalBrightness,
                                    baseColor.g * globalBrightness,
                                    baseColor.b * globalBrightness
                                )
                            }
                        }
                        
                        matrices.stack {
                            matrices.translate(0.5, height.toDouble(), 0.5)
                            matrices.multiply(RotationAxis.POSITIVE_Y.rotation(currentRotation))
                            renderHaloStyle(matrices, vertexConsumers, currentRadius, width, height, currentAngleCount, currentColor)
                        }
                    }
                    
                    renderSingleRing(radius, 1)
                    renderSingleRing(innerRadius, -1)
                }
                else -> return
            }
        }

        conf.rings.get.forEachIndexed { index, ringConf ->
            renderRing(ringConf, index)
        }
        
        cleanupCache(gameTime.toLong())

        super.render(entity, tickDelta, matrices, vertexConsumers, light, overlay)
    }

    override fun getRenderDistance() = Int.MAX_VALUE
    override fun isInRenderDistance(beaconBlockEntity: BeaconBlockEntity?, vec3d: Vec3d?): Boolean {
        return beaconBlockEntity?.isRemoved == false
    }
}
