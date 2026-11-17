package com.example.rain;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;

public class RainClient implements ClientModInitializer {

    private static final int DROPS_PER_TICK = 45;
    private static final double RADIUS = 16.0;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_WORLD_TICK.register(this::tick);
    }

    private void tick(ClientWorld world) {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) return;

        var dim = world.getRegistryKey();
        if (dim != World.NETHER && dim != World.END) return;

        Random r = world.getRandom();
        BlockPos.Mutable pos = new BlockPos.Mutable();

        for (int i = 0; i < DROPS_PER_TICK; i++) {
            double x = player.getX() + (r.nextDouble() - 0.5) * 2 * RADIUS;
            double z = player.getZ() + (r.nextDouble() - 0.5) * 2 * RADIUS;
            double y = player.getY() + 3 + r.nextDouble() * 8;

            pos.set(x, y, z);
            if (!world.getBlockState(pos).isAir()) continue;

            world.addParticle(ParticleTypes.FALLING_WATER, x, y, z, 0, -0.4, 0);
        }

        if (world.getTime() % 12 == 0) {
            world.playSound(player.getX(), player.getY() + 2, player.getZ(),
                    SoundEvents.WEATHER_RAIN, SoundCategory.WEATHER,
                    0.35f, 0.9f + r.nextFloat() * 0.2f, false);
        }
    }
}
