package ltown.hev_suit.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.Entity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.entity.projectile.FireballEntity;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.math.Box;
import net.minecraft.text.Text;
import java.util.*;

public class Hev_suitClient implements ClientModInitializer {

    private static final Map<String, SoundEvent> SOUND_EVENTS = new HashMap<>();
    private static final Map<String, Integer> SOUND_DURATIONS = new HashMap<>(); // Duration in milliseconds
    private static final Queue<String> SOUND_QUEUE = new LinkedList<>();
    private static final Set<String> HEALTH_SOUNDS = Set.of(
        "major_laceration", "minor_laceration", "major_fracture", 
        "blood_loss", "health_critical", "health_critical2", 
        "morphine_administered", "seek_medical", "near_death"
    );

    private float lastHealth = 20.0f;
    private boolean radarEnabled = true;
    private boolean hevSuitEnabled = true;
    private boolean isSoundPlaying = false;

    private long lastSoundTime = 0;
    private long lastMorphineTime = 0;
    private long lastBurningTime = 0;
    private long lastLacerationTime = 0;
    private long lastBloodLossTime = 0;
    private long lastRadarTime = 0;
    private long lastRadarVoiceLineTime = 0;
    private long soundEndTime = 0;

    private static final long SOUND_COOLDOWN = 900;
    private static final long MORPHINE_COOLDOWN = 900000;
    private static final long BURNING_COOLDOWN = 5000;
    private static final long BLOOD_LOSS_COOLDOWN = 5000;
    private static final long RADAR_COOLDOWN = 3000;
    private static final long RADAR_VOICE_LINE_COOLDOWN = 10000;
    private static final long SOUND_PLAY_DELAY = 600;

    @Override
    public void onInitializeClient() {
        registerSounds();
        registerEventListeners();
        registerToggleCommands();
    }

    private void registerSounds() {
        String[] soundNames = {
            "major_laceration", "minor_laceration", "major_fracture", "blood_loss",
            "health_critical", "health_critical2", "morphine_system", "morphine_administered", "seek_medical",
            "near_death", "heat_damage", "warning", "bio_reading",
            "danger", "evacuate_area", "immediately", "north", "south", "east", "west",
            "voice_on", "voice_off"
        };
        int[] durations = {
            1200, 1000, 2000, 1300, 1500, 1600, 1400, 1700, 1900, 1900,
            1200, 1000, 1200, 1200, 1200, 1200, 1000, 1000, 1000, 1000,
            1500, 1500
        };

        for (int i = 0; i < soundNames.length; i++) {
            registerSound(soundNames[i], durations[i]);
        }
    }

    private void registerSound(String name, int duration) {
        SoundEvent sound = SoundEvent.of(new Identifier("hev_suit", name));
        Registry.register(Registries.SOUND_EVENT, sound.getId(), sound);
        SOUND_EVENTS.put(name, sound);
        SOUND_DURATIONS.put(name, duration);
    }

    private void registerEventListeners() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> resetTracking());
    }

    private void registerToggleCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("hevtoggle")
                .executes(context -> {
                    hevSuitEnabled = !hevSuitEnabled;
                    String status = hevSuitEnabled ? "Activated" : "Deactivated";
                    context.getSource().sendFeedback(Text.literal("Voice System " + status));
                    queueSoundOverride(hevSuitEnabled ? "voice_on" : "voice_off");
                    return 1;
                })
            );

            dispatcher.register(ClientCommandManager.literal("hevtoggleradar")
                .executes(context -> {
                    radarEnabled = !radarEnabled;
                    String status = radarEnabled ? "Activated" : "Deactivated";
                    context.getSource().sendFeedback(Text.literal("HEV Radar " + status));
                    return 1;
                })
            );
        });
    }
    private void resetTracking() {
        lastHealth = 20.0f;
        lastSoundTime = 0;
 
        lastMorphineTime = 0;
        lastBurningTime = 0;
        lastLacerationTime = 0;
        lastBloodLossTime = 0;
    
        lastRadarTime = 0;
        lastRadarVoiceLineTime = 0;
        isSoundPlaying = false;
        soundEndTime = 0;
    }

    private void onClientTick(MinecraftClient client) {
        if (!hevSuitEnabled) return;

        PlayerEntity player = client.player;
        if (player == null) return;

        float currentHealth = player.getHealth();
        long currentTime = System.currentTimeMillis();

        detectHostileMobsNearby(client);

        if (currentHealth <= 0) {
            SOUND_QUEUE.clear();
            isSoundPlaying = false;
            soundEndTime = 0;
            return;
        }

        if (player.isOnFire() && currentTime - lastBurningTime >= BURNING_COOLDOWN) {
            queueSound("heat_damage");
            lastBurningTime = currentTime;
        }

        if (player.fallDistance > 3.0 && player.getHealth() < lastHealth) {
            queueSound("major_fracture");
            player.fallDistance = 0;
        }

        if (currentHealth < lastHealth) {
            float damage = lastHealth - currentHealth;
            handleDamage(client, damage, player.getRecentDamageSource());
        }

        if (currentHealth <= 3.0 && this.lastHealth > 3.0) {
            this.queueSound("near_death");
        } 
        else if (currentHealth <= 5.0 && this.lastHealth > 5.0) {
            this.queueSound("health_critical");
        }
        else if (currentHealth <= 10.0 && this.lastHealth > 10.0) {
            this.queueSound("seek_medical");
        } else if (currentHealth <= 17.0 && this.lastHealth > 17.0) {
            this.queueSound("health_critical2");
        }

        if (currentTime - lastMorphineTime >= MORPHINE_COOLDOWN && currentHealth < 20) {
     
            queueSound("morphine_administered");
            lastMorphineTime = currentTime;
        }

        lastHealth = currentHealth;

        processSoundQueueOverride(client, currentTime);
    }

    private void detectHostileMobsNearby(MinecraftClient client) {
        if (!hevSuitEnabled || !radarEnabled) return;
        PlayerEntity player = client.player;
        if (player == null || client.world == null) return;

        long currentTime = System.currentTimeMillis();

        if (currentTime - lastRadarTime < RADAR_COOLDOWN) return;

        Box detectionBox = new Box(
                player.getX() - 10, player.getY() - 5, player.getZ() - 10,
                player.getX() + 10, player.getY() + 5, player.getZ() + 10
        );

        List<HostileEntity> detectedMobs = client.world.getEntitiesByClass(HostileEntity.class, detectionBox, e -> true);

        if (detectedMobs.size() > 3) {
            if (currentTime - lastRadarVoiceLineTime >= RADAR_VOICE_LINE_COOLDOWN) {
                queueSound("evacuate_area");
                queueSound("immediately");
                lastRadarVoiceLineTime = currentTime;
            }
        } else if (detectedMobs.size() > 0) {
            for (Entity entity : detectedMobs) {
                double deltaX = entity.getX() - player.getX();
                double deltaZ = entity.getZ() - player.getZ();

                String direction = getDirection(deltaX, deltaZ);

                if (direction != null) {
                    queueSound("warning");
                    queueSound(direction);
                    lastRadarTime = currentTime;
                    break;
                }
            }
        }
    }

    private String getDirection(double deltaX, double deltaZ) {
        double angle = Math.toDegrees(Math.atan2(deltaZ, deltaX)) + 180;
        if (angle >= 315 || angle < 45) {
            return "west";
        } else if (angle >= 45 && angle < 135) {
            return "north";
        } else if (angle >= 135 && angle < 225) {
            return "east";
        } else if (angle >= 225 && angle < 315) {
            return "south";
        }
        return null;
    }

    private void handleDamage(MinecraftClient client, float damage, DamageSource damageSource) {
        if (!hevSuitEnabled) return;

        long currentTime = System.currentTimeMillis();

        if (damageSource != null && damageSource.getSource() != null) {
            Entity damageEntity = damageSource.getSource();
            if (damageEntity instanceof ArrowEntity || damageEntity instanceof FireballEntity) {
                if (currentTime - lastBloodLossTime >= BLOOD_LOSS_COOLDOWN) {
                    queueSound("blood_loss");
                    lastBloodLossTime = currentTime;
                }
            } else if (damageEntity instanceof HostileEntity) {
                if (currentTime - lastLacerationTime >= BLOOD_LOSS_COOLDOWN) {
                    if (damage >= 5) {
                        queueSound("major_laceration");
                    } else {
                        queueSound("minor_laceration");
                    }
                    lastLacerationTime = currentTime;
                }
            }
        }
    }

    private void queueSound(String soundName) {
        SOUND_QUEUE.add(soundName);
    }

    private void queueSoundOverride(String soundName) {
        SOUND_QUEUE.add(soundName);
        processSoundQueueOverride(MinecraftClient.getInstance(), System.currentTimeMillis());
    }

    private void processSoundQueueOverride(MinecraftClient client, long currentTime) {
        if (!SOUND_QUEUE.isEmpty()) {
            if (!isSoundPlaying || (currentTime - soundEndTime >= SOUND_PLAY_DELAY)) {
                if (currentTime - lastSoundTime >= SOUND_COOLDOWN) {
                    String soundName = getNextPrioritizedSound();
                    SoundEvent sound = SOUND_EVENTS.get(soundName);
                    if (sound != null) {
                        client.getSoundManager().play(PositionedSoundInstance.master(sound, 1.0F));
                        lastSoundTime = currentTime;
                        isSoundPlaying = true;
                        soundEndTime = currentTime + SOUND_DURATIONS.get(soundName) + SOUND_PLAY_DELAY;
                    }
                }
            }
        } else {
            isSoundPlaying = false;
        }
    }
    
    private String getNextPrioritizedSound() {
      
        
        for (String sound : SOUND_QUEUE) {
            if (HEALTH_SOUNDS.contains(sound)) {
                SOUND_QUEUE.remove(sound);
                return sound;
            }
        }
        
        return SOUND_QUEUE.poll();
    }
}