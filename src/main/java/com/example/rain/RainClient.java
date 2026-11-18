package com.example.rain;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.LightType;
import net.minecraft.world.World;
import org.joml.Matrix4f;

/**
 * Client side: draws real rain (the vanilla rain texture) in the Nether and the End,
 * with ground splashes and rain sounds. Rain falls from the ceiling of the cave / from high above.
 */
public class RainClient implements ClientModInitializer {

    // Same texture the Overworld uses (so resource packs apply too).
    private static final Identifier RAIN_TEXTURE = new Identifier("textures/environment/rain.png");

    private static final int R = 8;               // horizontal radius in blocks
    private static final int SIZE = R * 2 + 1;
    private static final int UP = 24;             // how far above the player the rain starts
    private static final int DOWN = 12;           // how far below the player the rain reaches
    private static final Matrix4f IDENTITY = new Matrix4f();

    // synced from the server
    private static boolean target = false;
    private static float gradient = 0f;
    private static float prevGradient = 0f;
    private static int ticks = 0;

    // cached columns around the player (rebuilt every tick)
    private static final int[] bottom = new int[SIZE * SIZE];
    private static final int[] top = new int[SIZE * SIZE];
    private static final boolean[] open = new boolean[SIZE * SIZE];
    private static final boolean[] ground = new boolean[SIZE * SIZE];
    private static final float[] bright = new float[SIZE * SIZE];
    private static boolean roofedCenter = false;
    private static boolean columnsValid = false;
    private static int originX = 0;
    private static int originZ = 0;

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(RainMod.RAIN_PACKET, (client, handler, buf, sender) -> {
            boolean raining = buf.readBoolean();
            client.execute(() -> target = raining);
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            target = false;
            gradient = 0f;
            prevGradient = 0f;
            columnsValid = false;
        });

        ClientTickEvents.END_WORLD_TICK.register(RainClient::tick);
        WorldRenderEvents.AFTER_TRANSLUCENT.register(RainClient::render);
    }

    private static boolean isRainDimension(ClientWorld world) {
        if (world == null) return false;
        var key = world.getRegistryKey();
        return key.equals(World.NETHER) || key.equals(World.END);
    }

    // ------------------------------------------------------------------ tick

    private static void tick(ClientWorld world) {
        ticks++;

        // smooth fade in / out, same speed as vanilla (about 5 seconds)
        prevGradient = gradient;
        float t = target ? 1f : 0f;
        if (gradient < t) gradient = Math.min(t, gradient + 0.01f);
        else if (gradient > t) gradient = Math.max(t, gradient - 0.01f);

        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null || !isRainDimension(world) || gradient <= 0f) {
            columnsValid = false;
            return;
        }

        rebuildColumns(world, player);
        effects(world, player);
    }

    private static void rebuildColumns(ClientWorld world, ClientPlayerEntity player) {
        originX = MathHelper.floor(player.getX());
        originZ = MathHelper.floor(player.getZ());
        int py = MathHelper.floor(player.getEyeY());

        int minY = world.getBottomY();
        int maxY = world.getTopY();
        int lowLimit = Math.max(py - DOWN, minY);
        int highLimit = Math.min(py + UP, maxY - 1);

        BlockPos.Mutable m = new BlockPos.Mutable();

        for (int dz = -R; dz <= R; dz++) {
            for (int dx = -R; dx <= R; dx++) {
                int idx = (dz + R) * SIZE + (dx + R);
                int x = originX + dx;
                int z = originZ + dz;

                open[idx] = false;
                ground[idx] = false;

                m.set(x, py, z);
                if (!world.getBlockState(m).isAir()) continue; // inside a wall at eye level

                int b = lowLimit;
                boolean hasGround = false;
                for (int y = py - 1; y >= lowLimit; y--) {
                    m.setY(y);
                    if (!world.getBlockState(m).isAir()) {
                        b = y + 1;
                        hasGround = true;
                        break;
                    }
                }

                int tp = highLimit;
                boolean roof = false;
                for (int y = py + 1; y <= highLimit; y++) {
                    m.setY(y);
                    if (!world.getBlockState(m).isAir()) {
                        tp = y;
                        roof = true;
                        break;
                    }
                }

                if (tp <= b) continue;

                m.set(x, py, z);
                int light = world.getLightLevel(LightType.BLOCK, m);

                bottom[idx] = b;
                top[idx] = tp;
                ground[idx] = hasGround;
                bright[idx] = 0.35f + 0.65f * (light / 15f);
                open[idx] = true;

                if (dx == 0 && dz == 0) roofedCenter = roof;
            }
        }
        columnsValid = true;
    }

    private static void effects(ClientWorld world, ClientPlayerEntity player) {
        Random r = world.getRandom();
        float g = gradient;

        // splashes on the ground
        int count = (int) (24f * g * g);
        if (count == 0 && r.nextFloat() < 24f * g * g) count = 1;
        for (int i = 0; i < count; i++) {
            int dx = r.nextInt(SIZE) - R;
            int dz = r.nextInt(SIZE) - R;
            int idx = (dz + R) * SIZE + (dx + R);
            if (!open[idx] || !ground[idx]) continue;
            world.addParticle(ParticleTypes.RAIN,
                    originX + dx + r.nextDouble(), bottom[idx] + 0.1, originZ + dz + r.nextDouble(),
                    0, 0, 0);
        }

        if (g < 0.1f) return;

        // rain hitting the ground around you
        if (ticks % 8 == 0) {
            for (int tries = 0; tries < 6; tries++) {
                int dx = r.nextInt(SIZE) - R;
                int dz = r.nextInt(SIZE) - R;
                int idx = (dz + R) * SIZE + (dx + R);
                if (!open[idx] || !ground[idx]) continue;
                world.playSound(originX + dx + 0.5, bottom[idx], originZ + dz + 0.5,
                        SoundEvents.WEATHER_RAIN, SoundCategory.WEATHER,
                        0.2f * g, 1.0f, false);
                break;
            }
        }

        // rain on the ceiling above you
        if (roofedCenter && ticks % 10 == 0) {
            world.playSound(player.getX(), player.getY() + 1.0, player.getZ(),
                    SoundEvents.WEATHER_RAIN_ABOVE, SoundCategory.WEATHER,
                    0.1f * g, 0.5f, false);
        }
    }

    // ---------------------------------------------------------------- render

    private static boolean isIdentity(Matrix4f m) {
        float e = 1.0E-4f;
        return Math.abs(m.m00() - 1f) < e && Math.abs(m.m11() - 1f) < e && Math.abs(m.m22() - 1f) < e
                && Math.abs(m.m01()) < e && Math.abs(m.m02()) < e
                && Math.abs(m.m10()) < e && Math.abs(m.m12()) < e
                && Math.abs(m.m20()) < e && Math.abs(m.m21()) < e
                && Math.abs(m.m30()) < e && Math.abs(m.m31()) < e && Math.abs(m.m32()) < e;
    }

    private static void render(WorldRenderContext context) {
        ClientWorld world = context.world();
        if (!isRainDimension(world) || !columnsValid) return;

        float tickDelta = context.tickDelta();
        float g = MathHelper.lerp(tickDelta, prevGradient, gradient);
        if (g <= 0.01f) return;

        Vec3d cam = context.camera().getPos();

        // If the model-view matrix is already the camera rotation (vanilla weather stage), use camera-relative
        // coordinates only. Otherwise (identity), apply the camera matrix to the vertices ourselves.
        Matrix4f pos = isIdentity(RenderSystem.getModelViewMatrix())
                ? context.matrixStack().peek().getPositionMatrix()
                : IDENTITY;

        RenderSystem.setShader(GameRenderer::getPositionTexColorProgram);
        RenderSystem.setShaderTexture(0, RAIN_TEXTURE);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE_COLOR);

        for (int dz = -R; dz <= R; dz++) {
            for (int dx = -R; dx <= R; dx++) {
                int idx = (dz + R) * SIZE + (dx + R);
                if (!open[idx]) continue;

                int r = bottom[idx];
                int s = top[idx];
                int x = originX + dx;
                int z = originZ + dz;

                double vx = x + 0.5 - cam.x;
                double vz = z + 0.5 - cam.z;
                double len = Math.sqrt(vx * vx + vz * vz);
                if (len < 0.01) continue;

                float ox = (float) (-vz / len * 0.5);
                float oz = (float) (vx / len * 0.5);

                float rel = Math.min((float) len / (R + 2), 1f);
                float alpha = ((1f - rel * rel) * 0.5f + 0.5f) * g;
                float br = bright[idx];

                int u = (ticks + x * x * 3121 + x * 45238971 + z * z * 418711 + z * 13761) & 31;
                float rnd = (((x * x * 3121 + x * 45238971) ^ (z * z * 418711 + z * 13761)) & 255) / 255f;
                float h = -((float) u + tickDelta) / 32f * (3f + rnd);

                float cx = (float) (x + 0.5 - cam.x);
                float cz = (float) (z + 0.5 - cam.z);
                float yb = (float) (r - cam.y);
                float yt = (float) (s - cam.y);

                float v0 = r * 0.25f + h;
                float v1 = s * 0.25f + h;

                buffer.vertex(pos, cx - ox, yt, cz - oz).texture(0f, v0).color(br, br, br, alpha).next();
                buffer.vertex(pos, cx + ox, yt, cz + oz).texture(1f, v0).color(br, br, br, alpha).next();
                buffer.vertex(pos, cx + ox, yb, cz + oz).texture(1f, v1).color(br, br, br, alpha).next();
                buffer.vertex(pos, cx - ox, yb, cz - oz).texture(0f, v1).color(br, br, br, alpha).next();
            }
        }

        tessellator.draw();

        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }
}
