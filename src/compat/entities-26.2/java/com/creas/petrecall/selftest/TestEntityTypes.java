package com.creas.petrecall.selftest;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.animal.wolf.Wolf;

public final class TestEntityTypes {
    public static final EntityType<Wolf> WOLF = EntityTypes.WOLF;
    public static final EntityType<Pig> PIG = EntityTypes.PIG;

    private TestEntityTypes() {}
}
