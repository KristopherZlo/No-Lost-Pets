package com.creas.petrecall.selftest;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.animal.wolf.Wolf;

public final class TestEntityTypes {
    public static final EntityType<Wolf> WOLF = EntityType.WOLF;
    public static final EntityType<Pig> PIG = EntityType.PIG;

    private TestEntityTypes() {}
}
