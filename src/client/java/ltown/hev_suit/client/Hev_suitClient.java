package ltown.hev_suit.client;

import com.mojang.brigadier.arguments.FloatArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.Entity;
import net.minecraft.entity.TntEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.CreeperEntity;
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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

public class Hev_suitClient implements ClientModInitializer {

    private static final Logger LOGGER = LogManager.getLogger("Hev_suitClient");

    private static final Map<String, SoundEvent> SOUND_EVENTS = new HashMap<>();
    private static final Map<String, Integer> SOUND_DURATIONS = new HashMap<>(); // Duration in milliseconds
    private static final PriorityQueue<PrioritizedSound> SOUND_QUEUE = new PriorityQueue<>();

    private float lastHealth = 20.0f;
    private boolean radarEnabled = true;
    private boolean hevSuitEnabled = true;
    private boolean isSoundPlaying = false;
    private boolean wasPoisoned = false;


    private long lastMorphineTime = 0;
    private long lastBurningTime = 0;
    private long lastLacerationTime = 0;
    private long lastBloodLossTime = 0;
    private long lastRadarTime = 0;
    private long lastRadarVoiceLineTime = 0;
    private long soundEndTime = 0;


    private static final long MORPHINE_COOLDOWN = 90000;
    private static final long BURNING_COOLDOWN = 5000;
    private static final long BLOOD_LOSS_COOLDOWN = 5000;
    private static final long RADAR_COOLDOWN = 3000;
    private static final long RADAR_VOICE_LINE_COOLDOWN = 10000;

    private static float volume = 1.0f;

    public enum SoundPriority {
        CRITICAL,
        HIGH,
        MEDIUM,
        LOW
    }

    private static class PrioritizedSound implements Comparable<PrioritizedSound> {
        String name;
        SoundPriority priority;
        long timestamp;

        PrioritizedSound(String name, SoundPriority priority) {
            this.name = name;
            this.priority = priority;
            this.timestamp = System.currentTimeMillis();
        }

        @Override
        public int compareTo(PrioritizedSound other) {
            int priorityCompare = other.priority.compareTo(this.priority);
            if (priorityCompare != 0) {
                return priorityCompare;
            }
            return Long.compare(this.timestamp, other.timestamp);
        }
    }

    @Override
    public void onInitializeClient() {
        registerSounds();
        registerEventListeners();
        registerToggleCommands();
    }

    public static float getVolume() {
        return volume;
    }

    public static void setVolume(float newVolume) {
        volume = Math.max(0, Math.min(2, newVolume));
    }

    private void registerSounds() {
        String[] soundNames = {
            "major_laceration", "minor_laceration", "major_fracture", "blood_loss",
            "health_critical", "health_critical2", "morphine_system", "morphine_administered", "seek_medical",
            "near_death", "heat_damage", "warning", "bio_reading",
            "danger", "evacuate_area", "immediately", "north", "south", "east", "west",
            "voice_on", "voice_off", "shock_damage", "internal_bleeding", "minor_fracture","chemical"
        };
        int[] durations = {
            1200, 1000, 2000, 1300, 1500, 1600, 1400, 1700, 1900, 1900,
            1200, 1000, 1200, 1200, 1200, 1200, 1000, 1000, 1000, 1000,
            1500, 1500, 1500, 1000, 1200, 1600
        };
    
        for (int i = 0; i < soundNames.length; i++) {
            registerSound(soundNames[i], durations[i]);
        }
    }

    private void registerSound(String name, int duration) {
        try {
            SoundEvent sound = SoundEvent.of(new Identifier("hev_suit", name));
            Registry.register(Registries.SOUND_EVENT, sound.getId(), sound);
            SOUND_EVENTS.put(name, sound);
            SOUND_DURATIONS.put(name, duration);
        } catch (Exception e) {
            LOGGER.error("Failed to register sound: " + name, e);
        }
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
                    queueSound(hevSuitEnabled ? "voice_on" : "voice_off", SoundPriority.HIGH);
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

            dispatcher.register(ClientCommandManager.literal("hevpitch")
                .then(ClientCommandManager.argument("pitch", FloatArgumentType.floatArg(0, 2))
                    .executes(context -> {
                        float newVolume = FloatArgumentType.getFloat(context, "pitch");
                        setVolume(newVolume);
                        context.getSource().sendFeedback(Text.literal("HEV Suit pitch set to " + (int)(newVolume * 100) + "%"));
                        return 1;
                    })
                )
            );
        });
    }

    private void resetTracking() {
        lastHealth = 20.0f;
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
        try {
            if (!hevSuitEnabled) return;

            PlayerEntity player = client.player;
            if (player == null) return;

            float currentHealth = player.getHealth();
            long currentTime = System.currentTimeMillis();

            detectHostileMobsNearby(client);

            if (currentHealth <= 0) {
                SOUND_QUEUE.clear();
                isSoundPlaying = false;
                wasPoisoned = false;
                soundEndTime = 0;
                return;
            }

            if (player.isOnFire() && currentTime - lastBurningTime >= BURNING_COOLDOWN) {
                queueSound("heat_damage", SoundPriority.MEDIUM);
                lastBurningTime = currentTime;
            }

            if (currentHealth < lastHealth) {
                float damage = lastHealth - currentHealth;
                handleDamage(client, damage, player.getRecentDamageSource());
            }

            if (currentHealth <= 3.0 && this.lastHealth > 3.0) {
                this.queueSound("near_death", SoundPriority.CRITICAL);
            } 
            else if (currentHealth <= 5.0 && this.lastHealth > 5.0) {
                this.queueSound("health_critical", SoundPriority.CRITICAL);
            }
            else if (currentHealth <= 10.0 && this.lastHealth > 10.0) {
                this.queueSound("seek_medical", SoundPriority.CRITICAL);
            } else if (currentHealth <= 17.0 && this.lastHealth > 17.0) {
                this.queueSound("health_critical2", SoundPriority.CRITICAL);
            }

            if (currentTime - lastMorphineTime >= MORPHINE_COOLDOWN && currentHealth < 20) {
                queueSound("morphine_administered", SoundPriority.MEDIUM);
                lastMorphineTime = currentTime;
            }

            lastHealth = currentHealth;

            processSoundQueueOverride(client, currentTime);
        } catch (Exception e) {
            LOGGER.error("Error in HEV suit client tick", e);
        }
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
                queueSound("evacuate_area", SoundPriority.HIGH);
                queueSound("immediately", SoundPriority.HIGH);
                lastRadarVoiceLineTime = currentTime;
            }
        } else if (detectedMobs.size() > 0) {
            for (Entity entity : detectedMobs) {
                double deltaX = entity.getX() - player.getX();
                double deltaZ = entity.getZ() - player.getZ();

                String direction = getDirection(deltaX, deltaZ);

                if (direction != null) {
                    queueSound("warning", SoundPriority.LOW);
                    queueSound(direction, SoundPriority.LOW);
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
    
        if (damageSource != null) {
            if (damageSource.getName().equals("fall")) {
                if (damage >= 6) {
                    queueSound("major_fracture", SoundPriority.HIGH);
                } else if (damage >= 3) {
                    queueSound("minor_fracture", SoundPriority.HIGH);
                }
            }
            if (damageSource.getSource() instanceof TntEntity || damageSource.getSource() instanceof CreeperEntity) {
                queueSound("internal_bleeding", SoundPriority.HIGH);
            } else if (damageSource.getName().equals("explosion")) {
                queueSound("internal_bleeding", SoundPriority.HIGH);
            }
            if (damageSource.getName().equals("lightningBolt")) {
                queueSound("shock_damage", SoundPriority.HIGH);
            }
            if (client.player.hasStatusEffect(StatusEffects.POISON)) {
                if (!wasPoisoned) {
                    queueSound("chemical", SoundPriority.HIGH);
                    wasPoisoned = true;
                }
            } else {
                wasPoisoned = false;
            }

            Entity damageEntity = damageSource.getSource();
            if (damageEntity instanceof ArrowEntity || damageEntity instanceof FireballEntity) {
                if (currentTime - lastBloodLossTime >= BLOOD_LOSS_COOLDOWN) {
                    queueSound("blood_loss", SoundPriority.LOW);
                    lastBloodLossTime = currentTime;
                }
            } else if (damageEntity instanceof HostileEntity) {
                if (currentTime - lastLacerationTime >= BLOOD_LOSS_COOLDOWN) {
                    if (damage >= 5) {
                        queueSound("major_laceration", SoundPriority.HIGH);
                    } else {
                        queueSound("minor_laceration", SoundPriority.LOW);
                    }
                    lastLacerationTime = currentTime;
                }
            }
        }
    }
  
    private PrioritizedSound currentPlayingSound = null;
    private Queue<PrioritizedSound> highPriorityQueue = new LinkedList<>();
    private Queue<PrioritizedSound> lowPriorityQueue = new LinkedList<>();

    private void processSoundQueueOverride(MinecraftClient client, long currentTime) {
        if (!highPriorityQueue.isEmpty() || !lowPriorityQueue.isEmpty()) {
            if (!isSoundPlaying || currentTime >= soundEndTime) {
                playNextSound(client, currentTime);
            } else {
                // Check if we need to interrupt the current sound
                PrioritizedSound nextSound = highPriorityQueue.peek();
                if (nextSound != null && currentPlayingSound != null &&
                    (nextSound.priority == SoundPriority.CRITICAL || nextSound.priority == SoundPriority.HIGH) &&
                    (currentPlayingSound.priority == SoundPriority.MEDIUM || currentPlayingSound.priority == SoundPriority.LOW)) {
                    playNextSound(client, currentTime);
                }
            }
        } else {
            isSoundPlaying = false;
            currentPlayingSound = null;
        }
    }

    private void playNextSound(MinecraftClient client, long currentTime) {
        PrioritizedSound nextSound = highPriorityQueue.poll();
        if (nextSound == null) {
            nextSound = lowPriorityQueue.poll();
        }

        if (nextSound != null) {
            String soundName = nextSound.name;
            SoundEvent sound = SOUND_EVENTS.get(soundName);
            if (sound != null) {
                try {
                    if (isSoundPlaying) {
                        client.getSoundManager().stopAll(); // Stop current sound if interrupting
                    }
                    client.getSoundManager().play(PositionedSoundInstance.master(sound, volume));
                    isSoundPlaying = true;
                    currentPlayingSound = nextSound;
                    soundEndTime = currentTime + SOUND_DURATIONS.get(soundName);
                } catch (Exception e) {
                    LOGGER.error("Error playing sound: " + soundName, e);
                }
            } else {
                LOGGER.warn("Sound not found: " + soundName);
            }
        }
    }

    private void queueSound(String soundName, SoundPriority priority) {
        PrioritizedSound newSound = new PrioritizedSound(soundName, priority);
        if (priority == SoundPriority.CRITICAL || priority == SoundPriority.HIGH) {
            highPriorityQueue.offer(newSound);
        } else {
            lowPriorityQueue.offer(newSound);
        }
    }
}