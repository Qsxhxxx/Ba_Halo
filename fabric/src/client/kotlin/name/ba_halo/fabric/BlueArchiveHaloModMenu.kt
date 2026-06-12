package name.ba_halo.fabric

import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi
import name.ba_halo.screen.MainScreen

object BlueArchiveHaloModMenu : ModMenuApi {
    override fun getModConfigScreenFactory() = ConfigScreenFactory(::MainScreen)
}
