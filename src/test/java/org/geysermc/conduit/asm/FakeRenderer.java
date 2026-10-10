package org.geysermc.conduit.asm;

public class FakeRenderer {
    private static final String TEXTURE = "mod:textures/entity/boar.png";

    public String getTextureLocation(Object renderState) {
        return TEXTURE;
    }
}

class FakeInheritedRenderer extends FakeRenderer {
}

class FakeVariantRenderer extends FakeRenderer {
    private static final String WHITE = "mod:textures/entity/bird_white.png";
    private static final String BLUE = "mod:textures/entity/bird_blue.png";
    private static final String RED = "mod:textures/entity/bird_red.png";

    @Override
    public String getTextureLocation(Object renderState) {
        return switch (renderState.hashCode() % 3) {
            case 0 -> WHITE;
            case 1 -> BLUE;
            default -> RED;
        };
    }
}

class FakeRenderState {
    String texture;
}

class FakeEntity {
    static String modelTexture(String name) {
        return "mod:textures/models/" + name;
    }

    public String getTexture() {
        return modelTexture("boar.png");
    }
}

class FakeStateRendererBase<T extends FakeEntity> {
    public String getTextureLocation(FakeRenderState state) {
        return state.texture;
    }

    public void extractRenderState(T entity, FakeRenderState state) {
        state.texture = entity.getTexture();
    }
}

class FakeStateRenderer extends FakeStateRendererBase<FakeEntity> {
}
