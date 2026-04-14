package com.creas.petrecall.index;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.UUID;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.Uuids;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
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
            Uuids.CODEC.fieldOf("pet_uuid").forGetter(PetRecord::petUuid),
            Uuids.CODEC.fieldOf("owner_uuid").forGetter(PetRecord::ownerUuid),
            Codec.STRING.fieldOf("entity_type").forGetter(PetRecord::entityTypeId),
            Codec.STRING.fieldOf("dimension").forGetter(PetRecord::dimensionId),
            Codec.LONG.fieldOf("chunk_pos").forGetter(PetRecord::chunkPosLong),
            Codec.DOUBLE.fieldOf("x").forGetter(PetRecord::x),
            Codec.DOUBLE.fieldOf("y").forGetter(PetRecord::y),
            Codec.DOUBLE.fieldOf("z").forGetter(PetRecord::z),
            Codec.BOOL.optionalFieldOf("sitting", false).forGetter(PetRecord::sitting),
            Codec.FLOAT.optionalFieldOf("health", 0.0F).forGetter(PetRecord::health)
    ).apply(instance, PetRecord::new));

    public static PetRecord fromEntity(ServerWorld world, net.minecraft.entity.Entity entity, UUID ownerUuid, boolean sitting, float health) {
        return new PetRecord(
                entity.getUuid(),
                ownerUuid,
                Registries.ENTITY_TYPE.getId(entity.getType()).toString(),
                world.getRegistryKey().getValue().toString(),
                entity.getChunkPos().toLong(),
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
    public RegistryKey<World> dimensionKey() {
        Identifier id = Identifier.tryParse(this.dimensionId);
        if (id == null) {
            return null;
        }
        return RegistryKey.of(RegistryKeys.WORLD, id);
    }
}
