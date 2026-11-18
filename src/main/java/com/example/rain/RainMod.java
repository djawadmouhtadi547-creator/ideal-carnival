package com.example.rain;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.command.argument.TimeArgumentType;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.random.Random;

/**
 * Server side: the weather of the Nether and the End mirrors the Overworld's weather
 * (natural cycle + /weather command). The state is sent to every player.
 */
public class RainMod implements ModInitializer {

    public static final String MOD_ID = "netherendrain";
    public static final Identifier RAIN_PACKET = new Identifier(MOD_ID, "rain_state");

    private static final int CLEAR = 0;
    private static final int RAIN = 1;
    private static final int THUNDER = 2;

    private static boolean lastRaining = false;
    private static int counter = 0;

    @Override
    public void onInitialize() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            boolean raining = isOverworldRaining(server);
            counter++;
            if (raining != lastRaining || counter % 10 == 0) {
                lastRaining = raining;
                for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                    send(player, raining);
                }
            }
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                send(handler.player, isOverworldRaining(server)));

        // /weather works from ANY dimension (Nether / End too) and always controls the shared weather.
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(CommandManager.literal("weather")
                    .requires(source -> source.hasPermissionLevel(2))
                    .then(CommandManager.literal("clear")
                            .executes(ctx -> setWeather(ctx.getSource(), -1, CLEAR))
                            .then(CommandManager.argument("duration", TimeArgumentType.time(1))
                                    .executes(ctx -> setWeather(ctx.getSource(),
                                            IntegerArgumentType.getInteger(ctx, "duration"), CLEAR))))
                    .then(CommandManager.literal("rain")
                            .executes(ctx -> setWeather(ctx.getSource(), -1, RAIN))
                            .then(CommandManager.argument("duration", TimeArgumentType.time(1))
                                    .executes(ctx -> setWeather(ctx.getSource(),
                                            IntegerArgumentType.getInteger(ctx, "duration"), RAIN))))
                    .then(CommandManager.literal("thunder")
                            .executes(ctx -> setWeather(ctx.getSource(), -1, THUNDER))
                            .then(CommandManager.argument("duration", TimeArgumentType.time(1))
                                    .executes(ctx -> setWeather(ctx.getSource(),
                                            IntegerArgumentType.getInteger(ctx, "duration"), THUNDER)))));
        });
    }

    private static int setWeather(ServerCommandSource source, int duration, int mode) {
        ServerWorld overworld = source.getServer().getOverworld();
        Random random = overworld.getRandom();
        String key;
        if (mode == CLEAR) {
            int d = duration == -1 ? random.nextBetween(12000, 180000) : duration;
            overworld.setWeather(d, 0, false, false);
            key = "commands.weather.set.clear";
        } else if (mode == RAIN) {
            int d = duration == -1 ? random.nextBetween(12000, 24000) : duration;
            overworld.setWeather(0, d, true, false);
            key = "commands.weather.set.rain";
        } else {
            int d = duration == -1 ? random.nextBetween(3600, 15600) : duration;
            overworld.setWeather(0, d, true, true);
            key = "commands.weather.set.thunder";
        }
        source.sendFeedback(() -> Text.translatable(key), true);
        return duration;
    }

    private static boolean isOverworldRaining(MinecraftServer server) {
        ServerWorld overworld = server.getOverworld();
        return overworld != null && overworld.getLevelProperties().isRaining();
    }

    private static void send(ServerPlayerEntity player, boolean raining) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBoolean(raining);
        ServerPlayNetworking.send(player, RAIN_PACKET, buf);
    }
}
