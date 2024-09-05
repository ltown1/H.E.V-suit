package ltown.hev_suit.client;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
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

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

public class Hev_suitClient implements ClientModInitializer {

    private static final Map<String, SoundEvent> SOUND_EVENTS = new HashMap<>();
    private static final Map<String, Integer> SOUND_DURATIONS = new HashMap<>(); // Duration in milliseconds
    private float lastHealth = 20.0f;

    private long lastSoundTime = 0;
    private long gameStartTime = 0;
    private long lastMorphineTime = 0;
    private long lastBurningTime = 0;
    private long lastLacerationTime = 0;
    private long lastBloodLossTime = 0;
    private long lastDirectionalWarningTime = 0;
    private long lastRadarTime = 0;
    private long lastRadarVoiceLineTime = 0;
    private static final long SOUND_COOLDOWN = 900; // 1 second cooldown
    private static final long MORPHINE_COOLDOWN = 900000; 
    private static final long BURNING_COOLDOWN = 5000; // 5 seconds cooldown
    private static final long BLOOD_LOSS_COOLDOWN = 5000; // 5 seconds cooldown
    private static final long DIRECTIONAL_WARNING_COOLDOWN = 5000; // 5 seconds cooldown
    private static final long RADAR_COOLDOWN = 3000; // 5 seconds cooldown for radar
    private static final long RADAR_VOICE_LINE_COOLDOWN = 10000; // 1 minute 30 seconds cooldown for radar voice lines
    private static final long SOUND_PLAY_DELAY = 600; // 500 milliseconds (half a second) delay after sound

    private static final Queue<String> SOUND_QUEUE = new LinkedList<>();
    private boolean isSoundPlaying = false;
    private long soundEndTime = 0;

    @Override
    public void onInitializeClient() {
        registerSounds();
        registerEventListeners();
        gameStartTime = System.currentTimeMillis();
    }

    private void registerSounds() {
        registerSound("major_laceration", 1200);
        registerSound("minor_laceration", 1000);
        registerSound("major_fracture", 2000);
        registerSound("blood_loss", 1300);
        registerSound("health_critical", 1500);
        registerSound("health_critical2", 1600);
        registerSound("morphine_administered", 1400);
        registerSound("seek_medical", 1700);
        registerSound("near_death", 1900);
        registerSound("morphine_system", 1900);
        registerSound("heat_damage", 1200);
        registerSound("warning", 1000);
        registerSound("bio_reading", 1200);
        registerSound("danger", 1200);
        registerSound("evacuate_area", 1200);
        registerSound("immediately", 1200);
        // Directional sounds
        registerSound("north", 1000);
        registerSound("south", 1000);
        registerSound("east", 1000);
        registerSound("west", 1000);
    }

    private void registerSound(String name, int duration) {
        SoundEvent sound = SoundEvent.of(new Identifier("hev_suit", name));
        Registry.register(Registries.SOUND_EVENT, sound.getId(), sound);
        SOUND_EVENTS.put(name, sound);
        SOUND_DURATIONS.put(name, duration); // Register sound duration
    }

    private void registerEventListeners() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> resetTracking());
    }

    private void resetTracking() {
        lastHealth = 20.0f;
        lastSoundTime = 0;
        gameStartTime = System.currentTimeMillis();
        lastMorphineTime = 0;
        lastBurningTime = 0;
        lastLacerationTime = 0;
        lastBloodLossTime = 0;
        lastDirectionalWarningTime = 0;
        lastRadarTime = 0;
        lastRadarVoiceLineTime = 0;
        isSoundPlaying = false;
        soundEndTime = 0;
    }

    private void onClientTick(MinecraftClient client) {
        PlayerEntity player = client.player;
        if (player == null) return;

        float currentHealth = player.getHealth();
        long currentTime = System.currentTimeMillis();

        // Detect hostile mobs nearby and queue directional sounds
        detectHostileMobsNearby(client);

        // Check if the player is dead
        if (currentHealth <= 0) {
            // Clear the sound queue
            SOUND_QUEUE.clear();
            isSoundPlaying = false;
            soundEndTime = 0;
            return;
        }

        // Check for burning damage
        if (player.isOnFire() && currentTime - lastBurningTime >= BURNING_COOLDOWN) {
            queueSound("heat_damage");
            lastBurningTime = currentTime;
        }

        // Check for fall damage
        if (player.fallDistance > 3.0 && player.getHealth() < lastHealth) {
            queueSound("major_fracture");
            player.fallDistance = 0; // Reset fall distance after damage
        }

        // Check for damage
        if (currentHealth < lastHealth) {
            float damage = lastHealth - currentHealth;
            handleDamage(client, damage, player.getRecentDamageSource());
        }

        if (currentHealth <= 1.0 && this.lastHealth > 1.0) {
            this.queueSound("near_death");
        } else if (currentHealth <= 3.0 && this.lastHealth > 3.0) {
            this.queueSound("seek_medical");
        } else if (currentHealth <= 17.0 && this.lastHealth > 17.0) {
            this.queueSound("health_critical2");
        } else if (currentHealth > 17.0 && this.lastHealth <= 17.0) {

        }
        // Check for morphine administration
        if (currentTime - lastMorphineTime >= MORPHINE_COOLDOWN && currentHealth < 20) {
            queueSound("morphine_system");
            queueSound("morphine_administered");
            lastMorphineTime = currentTime;
        }

        // Update last health value
        lastHealth = currentHealth;

        // Process sound queue
        processSoundQueue(client, currentTime);
    }

    private void detectHostileMobsNearby(MinecraftClient client) {
        PlayerEntity player = client.player;
        if (player == null || client.world == null) return;

        long currentTime = System.currentTimeMillis();

        // Ensure the radar cooldown has passed
        if (currentTime - lastRadarTime < RADAR_COOLDOWN) return;

        // Create a bounding box around the player to check for nearby entities
        Box detectionBox = new Box(
                player.getX() - 10, player.getY() - 5, player.getZ() - 10,
                player.getX() + 10, player.getY() + 5, player.getZ() + 10
        );

        List<HostileEntity> detectedMobs = client.world.getEntitiesByClass(HostileEntity.class, detectionBox, e -> true);

        if (detectedMobs.size() > 3) {
            // Play voice lines if more than 5 mobs are detected
            if (currentTime - lastRadarVoiceLineTime >= RADAR_VOICE_LINE_COOLDOWN) {

                queueSound("evacuate_area");
                queueSound("immediately");
                lastRadarVoiceLineTime = currentTime;
            }
        } else if (detectedMobs.size() > 0) {
            // Detect direction of first hostile mob
            for (Entity entity : detectedMobs) {
                double deltaX = entity.getX() - player.getX();
                double deltaZ = entity.getZ() - player.getZ();

                // Determine direction relative to the player
                String direction = getDirection(deltaX, deltaZ);

                if (direction != null) {
                    queueSound("warning");

                    queueSound(direction);
                    lastRadarTime = currentTime;
                    break; // Only queue one set of sounds per detection
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
        long currentTime = System.currentTimeMillis();

        // Check for projectile damage or damage from hostile mobs
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
                        lastLacerationTime = currentTime;
                    } else {
                        queueSound("minor_laceration");
                        lastLacerationTime = currentTime;
                    }
                }
            }
        }
    }

    private void queueSound(String soundName) {
        SOUND_QUEUE.add(soundName);
    }

    private void processSoundQueue(MinecraftClient client, long currentTime) {
        if (!SOUND_QUEUE.isEmpty()) {
            if (!isSoundPlaying || (currentTime - soundEndTime >= SOUND_PLAY_DELAY)) {
                if (currentTime - lastSoundTime >= SOUND_COOLDOWN) {
                    String soundName = SOUND_QUEUE.poll();
                    SoundEvent sound = SOUND_EVENTS.get(soundName);
                    if (sound != null) {
                        client.getSoundManager().play(PositionedSoundInstance.master(sound, 1.0F));
                        lastSoundTime = currentTime;
                        isSoundPlaying = true;
                        soundEndTime = currentTime + SOUND_DURATIONS.get(soundName) + SOUND_PLAY_DELAY; // Calculate end time of current sound
                    }
                }
            }
        } else {
            // Reset sound playing status if queue is empty
            isSoundPlaying = false;
        }
    }

    private boolean currentTimeExceedsBloodLossCooldown(long currentTime) {
        return currentTime - lastBloodLossTime >= BLOOD_LOSS_COOLDOWN;
    }

    private boolean currentTimeExceedsLacerationCooldown(long currentTime) {
        return currentTime - lastLacerationTime >= BLOOD_LOSS_COOLDOWN;
    }
}

