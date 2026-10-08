package com.creas.petrecall.index;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

public record PetRecord(
        UUID petUuid,
        UUID ownerUuid,
        String entityTypeId,
        String dimensionId,
        long chunkPosLong,
        double x,
        double y,
        double z,
        boolean sitting,
        float health
) {
    public static final Codec<PetRecord> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUIDUtil.AUTHLIB_CODEC.fieldOf("pet_uuid").forGetter(PetRecord::petUuid),
            UUIDUtil.AUTHLIB_CODEC.fieldOf("owner_uuid").forGetter(PetRecord::ownerUuid),
            Codec.STRING.fieldOf("entity_type").forGetter(PetRecord::entityTypeId),
            Codec.STRING.fieldOf("dimension").forGetter(PetRecord::dimensionId),
            Codec.LONG.fieldOf("chunk_pos").forGetter(PetRecord::chunkPosLong),
            Codec.DOUBLE.fieldOf("x").forGetter(PetRecord::x),
            Codec.DOUBLE.fieldOf("y").forGetter(PetRecord::y),
            Codec.DOUBLE.fieldOf("z").forGetter(PetRecord::z),
            Codec.BOOL.optionalFieldOf("sitting", false).forGetter(PetRecord::sitting),
            Codec.FLOAT.optionalFieldOf("health", 0.0F).forGetter(PetRecord::health)
    ).apply(instance, PetRecord::new));

    public static PetRecord fromEntity(ServerLevel world, net.minecraft.world.entity.Entity entity, UUID ownerUuid, boolean sitting, float health) {
        return new PetRecord(
                entity.getUUID(),
                ownerUuid,
                BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
                world.dimension().identifier().toString(),
                entity.chunkPosition().toLong(),
                entity.getX(),
                entity.getY(),
                entity.getZ(),
                sitting,
                health
        );
    }

    public ChunkPos chunkPos() {
        return new ChunkPos(this.chunkPosLong);
    }

    @Nullable
    public ResourceKey<Level> dimensionKey() {
        Identifier id = Identifier.tryParse(this.dimensionId);
        if (id == null) {
            return null;
        }
        return ResourceKey.create(Registries.DIMENSION, id);
    }
}
