package ltown.hev_suit.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.entity.projectile.FireballEntity;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public class Hev_suitClient implements ClientModInitializer {

    private static final Map<String, SoundEvent> SOUND_EVENTS = new HashMap<>();
    private float lastHealth = 20.0f;

    private long lastSoundTime = 0;
    private long gameStartTime = 0;
    private long lastMorphineTime = 0;
    private static final long SOUND_COOLDOWN = 1000; // 1 second cooldown
    private static final long MORPHINE_COOLDOWN = 1800000; // 30 minutes in milliseconds
    private static final Random RANDOM = new Random();

    @Override
    public void onInitializeClient() {
        registerSounds();
        registerEventListeners();
        gameStartTime = System.currentTimeMillis();
    }

    private void registerSounds() {
        registerSound("minor_laceration");
        registerSound("major_laceration");
        registerSound("minor_fracture");
        registerSound("major_fracture");
        registerSound("blood_loss");
        registerSound("seek_medical");
        registerSound("health_critical");
        registerSound("health_critical2");
        registerSound("morphine_administered");
        registerSound("oxygen_low");
        registerSound("seek_medical");
        // Add more sounds as needed
    }

    private void registerSound(String name) {
        SoundEvent sound = SoundEvent.of(new Identifier("hevsoundmod", name));
        Registry.register(Registries.SOUND_EVENT, sound.getId(), sound);
        SOUND_EVENTS.put(name, sound);
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
    }

    private void onClientTick(MinecraftClient client) {
        PlayerEntity player = client.player;
        if (player == null) return;

        float currentHealth = player.getHealth();

        long currentTime = System.currentTimeMillis();

        // Check for damage
        if (currentHealth < lastHealth) {
            float damage = lastHealth - currentHealth;
            handleDamage(client, damage, player.getRecentDamageSource());
        }

        // Check for very low health
        if (currentHealth <= 1) {
            playSound(client, "near_death");
        } else if (currentHealth <= 2) {
            playSound(client, "seek_medical");
        } else if (currentHealth <= 5) {
            playSound(client, "health_critical");
        } else if (currentHealth <= 10) {
            playSound(client, "health_dropping2");
        }

        // Check for morphine administration
        if (currentTime - lastMorphineTime >= MORPHINE_COOLDOWN && currentHealth < 20) {
            playSound(client, "morphine_administered");
            lastMorphineTime = currentTime;
        }


    }

    private void handleDamage(MinecraftClient client, float damage, DamageSource damageSource) {
        if (damage >= 8) {
            playSound(client, "major_fracture");
        } else if (damage >= 5) {
            playSound(client, "major_laceration");
        } else if (damage >= 3) {
            playSound(client, "minor_fracture");
        } else if (damage >= 1) {
            playSound(client, "minor_laceration");
        }

        // Check for projectile damage
        if (damageSource != null && damageSource.getSource() != null) {
            if (damageSource.getSource() instanceof ArrowEntity || damageSource.getSource() instanceof FireballEntity) {
                playSound(client, "blood_loss");
            }
        }
    }

    private void playSound(MinecraftClient client, String soundName) {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastSoundTime < SOUND_COOLDOWN) return;

        SoundEvent sound = SOUND_EVENTS.get(soundName);
        if (sound != null) {
            client.getSoundManager().play(PositionedSoundInstance.master(sound, 1.0F, 1.0F));
            lastSoundTime = currentTime;
        }
    }
}
