package name.ba_halo

import net.minecraft.block.entity.BeaconBlockEntity
import net.minecraft.client.render.GameRenderer
import net.minecraft.client.render.RenderLayer.MultiPhase
import net.minecraft.client.render.RenderLayer.MultiPhaseParameters
import net.minecraft.client.render.RenderPhase
import net.minecraft.client.render.VertexFormat
import net.minecraft.client.render.VertexFormat.DrawMode
import net.minecraft.client.render.VertexFormats
import net.minecraft.util.Identifier
import java.util.*


object BlueArchiveHaloClient {
	val id = "ba-halo"
    var shrinker:((BeaconBlockEntity) -> Int)? = null
	val texture = Identifier(id,"textures/pure_white.png")
	fun MultiPhase.modifyMultiPhase (
		name: String?,
		vertexFormat: VertexFormat?,
		drawMode: DrawMode?,
		expectedBufferSize: Int,
		hasCrumbling: Boolean,
		translucent: Boolean,
		phases: MultiPhaseParameters
	) {
		if (name != "beacon_beam") return
		if (phases.texture.id.get() != texture) return
		affectedOutline = Optional.empty()
		this.phases = MultiPhaseParameters.Builder()
			.cull(RenderPhase.ENABLE_CULLING)
			.lightmap(RenderPhase.DISABLE_LIGHTMAP)
			.program(RenderPhase.ShaderProgram { GameRenderer.getRenderTypeBeaconBeamProgram() })
			.texture(phases.texture)
			.transparency(RenderPhase.TRANSLUCENT_TRANSPARENCY)
			.build(false)
		this.vertexFormat = VertexFormats.POSITION_COLOR
		this.drawMode = DrawMode.TRIANGLE_STRIP
		beginAction = Runnable { this.phases.phases.forEach(RenderPhase::startDrawing) }
		endAction = Runnable { this.phases.phases.forEach(RenderPhase::endDrawing) }
	}
}
