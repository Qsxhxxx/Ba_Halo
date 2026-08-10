package name.ba_halo.fabric

import dev.architectury.registry.client.rendering.fabric.BlockEntityRendererRegistryImpl
import name.ba_halo.BeaconHaloRenderer
import name.ba_halo.BlueArchiveHaloClient
import name.ba_halo.config.Config
import name.ba_halo.screen.MainScreen
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.block.entity.BeaconBlockEntity
import net.minecraft.block.entity.BlockEntityType
import net.minecraft.client.MinecraftClient
import net.minecraft.client.option.KeyBinding
import net.minecraft.text.Text
import org.lwjgl.glfw.GLFW

object BlueArchiveHaloClientFabric : ClientModInitializer{
    private lateinit var openConfigKey: KeyBinding
    private lateinit var toggleRingsKey: KeyBinding
    
    override fun onInitializeClient() {
        BlockEntityRendererRegistryImpl.register(
            BlockEntityType.BEACON,
            ::BeaconHaloRenderer
        )
        
        openConfigKey = KeyBindingHelper.registerKeyBinding(
            KeyBinding(
                "key.ba_halo.open_config",
                GLFW.GLFW_KEY_KP_ADD,
                "key.categories.ba_halo"
            )
        )
        
        toggleRingsKey = KeyBindingHelper.registerKeyBinding(
            KeyBinding(
                "key.ba_halo.toggle_rings",
                GLFW.GLFW_KEY_Q,
                "key.categories.ba_halo"
            )
        )
        
        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { client ->
            while (openConfigKey.wasPressed()) {
                client.setScreen(MainScreen(client.currentScreen))
            }
            
            while (toggleRingsKey.wasPressed()) {
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
        })
        
        val shrinkers = FabricLoader.getInstance().getEntrypoints("ba_halo_beacon_level_shrinker",Function1::class.java)
        shrinkers.firstOrNull()?.let { BlueArchiveHaloClient.shrinker = it as ((BeaconBlockEntity) -> Int) }
    }
}
