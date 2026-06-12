package name.ba_halo.forge

import dev.architectury.registry.client.rendering.forge.BlockEntityRendererRegistryImpl
import name.ba_halo.BeaconHaloRenderer
import name.ba_halo.BlueArchiveHaloClient
import name.ba_halo.config.Config
import name.ba_halo.screen.MainScreen
import net.minecraft.block.entity.BeaconBlockEntity
import net.minecraft.block.entity.BlockEntityType
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.option.KeyBinding
import net.minecraft.text.Text
import net.minecraftforge.client.ConfigScreenHandler
import net.minecraftforge.client.event.RegisterKeyMappingsEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.ModLoadingContext
import net.minecraftforge.fml.common.Mod
import org.lwjgl.glfw.GLFW
import thedarkcolour.kotlinforforge.forge.FORGE_BUS
import thedarkcolour.kotlinforforge.forge.MOD_BUS

@Mod("ba_halo")
object BlueArchiveHaloClientForge {
    private var toggleRingsKey: KeyBinding? = null
    
    init {
        MOD_BUS.register(object : Any() {
            @SubscribeEvent
            fun registerKeyMappings(event: RegisterKeyMappingsEvent) {
                toggleRingsKey = KeyBinding(
                    "key.ba_halo.toggle_rings",
                    GLFW.GLFW_KEY_Q,
                    "key.categories.ba_halo"
                )
                toggleRingsKey?.let { event.register(it) }
            }
        })
        
        FORGE_BUS.register(object : Any() {
            @SubscribeEvent
            fun onClientTick(event: TickEvent.ClientTickEvent) {
                if (event.phase == TickEvent.Phase.END) {
                    val client = MinecraftClient.getInstance()
                    val key = toggleRingsKey ?: return
                    while (key.wasPressed()) {
                        if (client.options.sprintKey.isPressed) {
                            val newValue = !Config.instance.enableRings.get
                            Config.instance.enableRings.set(newValue)
                            client.player?.sendMessage(
                                if (newValue) Text.translatable("message.ba_halo.rings_enabled")
                                else Text.translatable("message.ba_halo.rings_disabled"),
                                true
                            )
                        }
                    }
                }
            }
        })
        
        BlockEntityRendererRegistryImpl.register(
            BlockEntityType.BEACON,
            ::BeaconHaloRenderer
        )
        ModLoadingContext.get().registerExtensionPoint<ConfigScreenHandler.ConfigScreenFactory?>(
            ConfigScreenHandler.ConfigScreenFactory::class.java)
        { ConfigScreenHandler.ConfigScreenFactory { _: MinecraftClient?, parent: Screen? -> MainScreen(parent) } }
    }
}
