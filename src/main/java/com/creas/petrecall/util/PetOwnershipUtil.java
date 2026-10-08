package com.creas.petrecall.util;

import java.util.List;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.TraceableEntity;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.TagValueOutput;
import org.jetbrains.annotations.Nullable;

public final class PetOwnershipUtil {
    private static final List<String> OWNER_UUID_KEYS = List.of(
            "Owner",
            "OwnerUUID",
            "OwnerUuid",
            "owner",
            "ownerUUID",
            "ownerUuid",
            "owner_uuid"
    );
    private static final List<String> SITTING_KEYS = List.of(
            "Sitting",
            "OrderedToSit",
            "IsSitting",
            "isSitting",
            "is_sitting"
    );

    private PetOwnershipUtil() {
    }

    @Nullable
    public static OwnedPetData getOwnedPetData(Entity entity) {
        if (entity instanceof TamableAnimal tameable) {
            if (!tameable.isTame()) {
                return null;
            }
            UUID ownerUuid = getOwnerUuid(tameable.getOwnerReference());
            if (ownerUuid == null) {
                return null;
            }
            float health = tameable.getHealth();
            return new OwnedPetData(ownerUuid, tameable.isOrderedToSit(), health);
        }

        // Tamed mounts (horse/donkey/mule/llama/camel/etc.) do not have companion follow-to-owner behavior.
        if (entity instanceof AbstractHorse) {
            return null;
        }

        if (!(entity instanceof LivingEntity living) || entity instanceof Player) {
            return null;
        }

        UUID ownerUuid = null;
        if (entity instanceof TraceableEntity ownable) {
            ownerUuid = getOwnerUuid(ownable.getOwner());
        }
        CompoundTag entityNbt = null;
        if (ownerUuid == null) {
            entityNbt = writeEntityNbt(entity);
            ownerUuid = findOwnerUuidInNbt(entityNbt);
        }
        if (ownerUuid == null) {
            return null;
        }

        if (entityNbt == null) {
            entityNbt = writeEntityNbt(entity);
        }
        if (!hasCompanionFollowSignalsInNbt(entityNbt)) {
            return null;
        }

        boolean sitting = isSittingFromNbt(entityNbt);
        return new OwnedPetData(ownerUuid, sitting, living.getHealth());
    }

    @Nullable
    private static UUID getOwnerUuid(@Nullable Entity owner) {
        return owner == null ? null : owner.getUUID();
    }

    @Nullable
    private static UUID findOwnerUuidInNbt(@Nullable CompoundTag nbt) {
        if (nbt == null) {
            return null;
        }

        for (String key : OWNER_UUID_KEYS) {
            UUID uuid = tryReadUuid(nbt, key);
            if (uuid != null) {
                return uuid;
            }
        }

        UUID uuidFromLongPair = tryReadUuidFromLongPair(nbt, "OwnerUUIDMost", "OwnerUUIDLeast");
        if (uuidFromLongPair != null) {
            return uuidFromLongPair;
        }
        uuidFromLongPair = tryReadUuidFromLongPair(nbt, "OwnerMost", "OwnerLeast");
        if (uuidFromLongPair != null) {
            return uuidFromLongPair;
        }
        uuidFromLongPair = tryReadUuidFromLongPair(nbt, "ownerMost", "ownerLeast");
        if (uuidFromLongPair != null) {
            return uuidFromLongPair;
        }

        return null;
    }

    private static boolean isSittingFromNbt(@Nullable CompoundTag nbt) {
        if (nbt == null) {
            return false;
        }

        for (String key : SITTING_KEYS) {
            OptionalBoolean sitting = tryReadBoolean(nbt, key);
            if (sitting.present()) {
                return sitting.value();
            }
        }

        OptionalByte command = tryReadByte(nbt, "Command");
        if (command.present()) {
            return command.value() == 1;
        }

        return false;
    }

    private static boolean hasCompanionFollowSignalsInNbt(@Nullable CompoundTag nbt) {
        if (nbt == null) {
            return false;
        }

        for (String key : SITTING_KEYS) {
            if (tryReadBoolean(nbt, key).present()) {
                return true;
            }
        }

        return tryReadByte(nbt, "Command").present();
    }

    @Nullable
    private static UUID getOwnerUuid(@Nullable EntityReference<LivingEntity> ownerReference) {
        if (ownerReference == null) {
            return null;
        }
        return ownerReference.getUUID();
    }

    @Nullable
    private static CompoundTag writeEntityNbt(Entity entity) {
        TagValueOutput view = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, entity.registryAccess());
        if (!entity.save(view)) {
            throw new IllegalStateException("Cannot inspect ownership of entity " + entity.getUUID());
        }
        return view.buildResult();
    }

    @Nullable
    private static UUID tryReadUuid(CompoundTag nbt, String key) {
        var intArray = nbt.getIntArray(key);
        if (intArray.isPresent()) {
            int[] value = intArray.get();
            if (value.length == 4) {
                return UUIDUtil.uuidFromIntArray(value);
            }
        }

        var stringValue = nbt.getString(key);
        if (stringValue.isPresent()) {
            try {
                return UUID.fromString(stringValue.get());
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed UUID strings.
            }
        }

        return null;
    }

    @Nullable
    private static UUID tryReadUuidFromLongPair(CompoundTag nbt, String mostKey, String leastKey) {
        var most = nbt.getLong(mostKey);
        var least = nbt.getLong(leastKey);
        if (most.isEmpty() || least.isEmpty()) {
            return null;
        }
        return new UUID(most.get(), least.get());
    }

    private static OptionalBoolean tryReadBoolean(CompoundTag nbt, String key) {
        var value = nbt.getBoolean(key);
        if (value.isEmpty()) {
            return OptionalBoolean.empty();
        }
        return OptionalBoolean.of(value.get());
    }

    private static OptionalByte tryReadByte(CompoundTag nbt, String key) {
        var value = nbt.getByte(key);
        if (value.isEmpty()) {
            return OptionalByte.empty();
        }
        return OptionalByte.of(value.get());
    }

    private record OptionalBoolean(boolean present, boolean value) {
        private static OptionalBoolean empty() {
            return new OptionalBoolean(false, false);
        }

        private static OptionalBoolean of(boolean value) {
            return new OptionalBoolean(true, value);
        }
    }

    private record OptionalByte(boolean present, byte value) {
        private static OptionalByte empty() {
            return new OptionalByte(false, (byte) 0);
        }

        private static OptionalByte of(byte value) {
            return new OptionalByte(true, value);
        }
    }

    public record OwnedPetData(UUID ownerUuid, boolean sitting, float health) {
    }
}
