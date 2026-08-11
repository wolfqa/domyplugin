package com.example;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.util.*;

public class ExampleMod implements ModInitializer {

    private static final File configFile = new File("config/domy_homes.json");
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private static Map<String, Map<String, HomeData>> homesDatabase = new HashMap<>();
    private static final List<TeleportTask> activeTeleports = new ArrayList<>();

    @Override
    public void onInitialize() {
        loadHomes();

        ServerTickEvents.END_SERVER_TICK.register(this::tickTeleports);

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            
            // --- KOMENDA /SETHOME ---
            dispatcher.register(CommandManager.literal("sethome")
                .executes(context -> {
                    sendError(context.getSource().getPlayer(), "Nie podano nazwy! Użycie: /sethome <nazwa_domu>");
                    return 1;
                })
                .then(CommandManager.argument("name", StringArgumentType.word())
                    .executes(context -> {
                        ServerPlayerEntity player = context.getSource().getPlayer();
                        if (player == null) return 1;
                        
                        String homeName = StringArgumentType.getString(context, "name").toLowerCase();
                        String uuid = player.getUuid().toString();

                        Map<String, HomeData> playerHomes = homesDatabase.computeIfAbsent(uuid, k -> new HashMap<>());

                        if (playerHomes.containsKey(homeName)) {
                            sendError(player, "Masz już dom o takiej nazwie! Wymyśl inną.");
                            return 1;
                        }

                        if (playerHomes.size() >= 5) {
                            sendError(player, "Osiągnąłeś maksymalny limit domów (5)!");
                            return 1;
                        }

                        HomeData data = new HomeData(
                            player.getWorld().getRegistryKey().getValue().toString(),
                            player.getX(), player.getY(), player.getZ(),
                            player.getYaw(), player.getPitch()
                        );
                        
                        playerHomes.put(homeName, data);
                        saveHomes();

                        player.sendMessage(Text.literal("Ustawiono dom na: ")
                                .formatted(Formatting.GREEN)
                                .append(Text.literal(String.format("X: %.0f, Y: %.0f, Z: %.0f", data.x, data.y, data.z))
                                .formatted(Formatting.GRAY)), false);
                        return 1;
                    })
                )
            );

            // --- KOMENDA /HOME ---
            dispatcher.register(CommandManager.literal("home")
                .executes(context -> {
                    ServerPlayerEntity player = context.getSource().getPlayer();
                    if (player == null) return 1;

                    String uuid = player.getUuid().toString();
                    Map<String, HomeData> playerHomes = homesDatabase.getOrDefault(uuid, new HashMap<>());

                    if (playerHomes.isEmpty()) {
                        sendError(player, "Nie masz ustawionego żadnego domu! Użyj /sethome <nazwa>");
                        return 1;
                    }

                    if (playerHomes.size() > 1) {
                        sendError(player, "Masz więcej niż jeden dom. Musisz podać nazwę: /home <nazwa>");
                        return 1;
                    }

                    String onlyHomeName = playerHomes.keySet().iterator().next();
                    startTeleportSequence(player, onlyHomeName, playerHomes.get(onlyHomeName));
                    return 1;
                })
                .then(CommandManager.argument("name", StringArgumentType.word())
                    .executes(context -> {
                        ServerPlayerEntity player = context.getSource().getPlayer();
                        if (player == null) return 1;

                        String homeName = StringArgumentType.getString(context, "name").toLowerCase();
                        String uuid = player.getUuid().toString();
                        Map<String, HomeData> playerHomes = homesDatabase.getOrDefault(uuid, new HashMap<>());

                        if (!playerHomes.containsKey(homeName)) {
                            sendError(player, "Dom o nazwie '" + homeName + "' nie istnieje!");
                            return 1;
                        }

                        startTeleportSequence(player, homeName, playerHomes.get(homeName));
                        return 1;
                    })
                )
            );
        });
    }

    // --- SYSTEM UNIWERSALNYCH ERRORÓW ---
    public static void sendError(ServerPlayerEntity player, String message) {
        if (player == null || player.getServer() == null) return;
        player.sendMessage(Text.literal("⚠ " + message).formatted(Formatting.RED), false);
        player.getServer().getCommandManager().executeWithPrefix(player.getCommandSource().withSilent(), 
            "playsound minecraft:block.note_block.bass player " + player.getName().getString());
    }

    // --- TELEPORTACJA I ODLICZANIE ---
    private void startTeleportSequence(ServerPlayerEntity player, String homeName, HomeData homeData) {
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, 80, 0, false, false, false));
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 80, 9, false, false, false));
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.JUMP_BOOST, 80, 128, false, false, false));

        long endTime = System.currentTimeMillis() + 3000;
        activeTeleports.add(new TeleportTask(player, homeName, homeData, endTime));
    }

    private void tickTeleports(MinecraftServer server) {
        Iterator<TeleportTask> iterator = activeTeleports.iterator();
        while (iterator.hasNext()) {
            TeleportTask task = iterator.next();
            long remainingMs = task.endTime - System.currentTimeMillis();

            if (remainingMs <= 0) {
                ServerWorld targetWorld = server.getWorld(RegistryKey.of(RegistryKeys.WORLD, Identifier.of(task.home.dimension)));
                if (targetWorld != null) {
                    task.player.teleport(targetWorld, task.home.x, task.home.y, task.home.z, task.home.yaw, task.home.pitch);
                }

                task.player.removeStatusEffect(StatusEffects.BLINDNESS);
                task.player.removeStatusEffect(StatusEffects.SLOWNESS);
                task.player.removeStatusEffect(StatusEffects.JUMP_BOOST);

                task.player.sendMessage(Text.literal("Wrócono do domu " + task.homeName).formatted(Formatting.YELLOW), false);
                sendTitleViaCommand(task.player, "WRÓCONO DO DOMU", "yellow", task.homeName, "gray", 10, 60, 20);

                iterator.remove();
            } else {
                double seconds = remainingMs / 1000.0;
                String timeStr = String.format(Locale.US, "%.3f", seconds);
                sendTitleViaCommand(task.player, "WRACANIE DO DOMU...", "yellow", "poczekaj " + timeStr + "s", "gray", 0, 5, 0);
            }
        }
    }

    private void sendTitleViaCommand(ServerPlayerEntity player, String title, String titleColor, String subtitle, String subColor, int in, int stay, int out) {
        if (player.getServer() == null) return;
        ServerCommandSource source = player.getCommandSource().withSilent();
        CommandManager cmd = player.getServer().getCommandManager();
        String name = player.getName().getString();
        
        cmd.executeWithPrefix(source, "title " + name + " times " + in + " " + stay + " " + out);
        cmd.executeWithPrefix(source, "title " + name + " subtitle {\"text\":\"" + subtitle + "\",\"color\":\"" + subColor + "\"}");
        cmd.executeWithPrefix(source, "title " + name + " title {\"text\":\"" + title + "\",\"color\":\"" + titleColor + "\"}");
    }

    private static class TeleportTask {
        ServerPlayerEntity player;
        String homeName;
        HomeData home;
        long endTime;

        TeleportTask(ServerPlayerEntity p, String name, HomeData data, long endTime) {
            this.player = p;
            this.homeName = name;
            this.home = data;
            this.endTime = endTime;
        }
    }

    private static class HomeData {
        String dimension;
        double x, y, z;
        float yaw, pitch;

        HomeData(String dimension, double x, double y, double z, float yaw, float pitch) {
            this.dimension = dimension;
            this.x = x; this.y = y; this.z = z;
            this.yaw = yaw; this.pitch = pitch;
        }
    }

    private void loadHomes() {
        if (!configFile.exists()) {
            configFile.getParentFile().mkdirs();
            return;
        }
        try (FileReader reader = new FileReader(configFile)) {
            Type type = new TypeToken<Map<String, Map<String, HomeData>>>() {}.getType();
            homesDatabase = gson.fromJson(reader, type);
            if (homesDatabase == null) homesDatabase = new HashMap<>();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void saveHomes() {
        try (FileWriter writer = new FileWriter(configFile)) {
            gson.toJson(homesDatabase, writer);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
