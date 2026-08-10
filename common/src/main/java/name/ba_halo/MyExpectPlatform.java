package name.ba_halo;

import dev.architectury.injectables.annotations.ExpectPlatform;

import java.nio.file.Path;

public class MyExpectPlatform {
    @ExpectPlatform
    public static Path getConfigDirectory() {
        throw new AssertionError();
    }
}
