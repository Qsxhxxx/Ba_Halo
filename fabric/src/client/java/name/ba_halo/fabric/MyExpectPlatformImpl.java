package name.ba_halo.fabric;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

public class MyExpectPlatformImpl {
    public static Path getConfigDirectory() {
        return FabricLoader.getInstance().getConfigDir();
    }
}
