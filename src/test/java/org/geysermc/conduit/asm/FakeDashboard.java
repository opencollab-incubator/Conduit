package org.geysermc.conduit.asm;

class FakeWidget {
    float x;
    float y;
    boolean visible;
}

class FakePanel {
    FakeWidget find(String name) {
        return new FakeWidget();
    }
}

class FakeInput {
    float time;
    float speed;
    int mode;
    boolean paused;
}

class FakeDashboard {
    private static final String[] DIALS = {"dial0", "dial1", "dial2"};

    private final FakeWidget needle;
    private final FakeWidget label;
    private final FakeWidget[] lights;
    private final FakeWidget[] badges;
    private final FakeWidget[] dials;

    FakeDashboard(FakePanel panel) {
        this.needle = panel.find("needle");
        this.label = panel.find("label");
        this.lights = new FakeWidget[4];
        for (int i = 0; i < this.lights.length; i++) {
            this.lights[i] = panel.find("light" + i);
        }
        this.badges = new FakeWidget[] {panel.find("badge0"), panel.find("badge1")};
        this.dials = new FakeWidget[DIALS.length];
        for (int i = 0; i < DIALS.length; i++) {
            this.dials[i] = panel.find(DIALS[i]);
        }
    }

    void update(FakeInput input) {
        this.needle.x = input.speed * 2f;
        for (int i = 0; i < this.lights.length; i++) {
            this.lights[i].x = (float) Math.cos(input.time + i) * input.speed;
        }
        for (FakeWidget badge : this.badges) {
            badge.visible = input.paused;
        }

        if (input.paused) {
            this.label.x = 1f;
        } else {
            this.label.x = this.needle.x;
        }

        switch (input.mode) {
            case 0 -> this.needle.y = 0.5f;
            case 1 -> this.needle.y = 1f;
            default -> {
            }
        }

        this.toggle(input);
        if (input.speed < 0.1f) {
            return;
        }
        this.label.y = 2f;
    }

    private void toggle(FakeInput input) {
        this.label.visible = input.mode != 2;
    }

    String icon(FakeInput input) {
        return "icons/mode_" + input.mode + (input.paused ? "_paused" : "") + ".png";
    }

    float halved(FakeInput input) {
        return half(input.speed);
    }

    static float half(float value) {
        return value / 2f;
    }

    static double widths(double a, long b) {
        double c = a * b;
        return c + c;
    }
}

class FakeSource {
    int getMode() {
        return 3;
    }
}

class FakePausableSource extends FakeSource {
    boolean isPaused() {
        return true;
    }
}

class FakeReader {
    void read(FakeSource source, FakeInput input) {
        input.paused = source instanceof FakePausableSource pausable && pausable.isPaused();
        input.mode = source.getMode();
    }
}
