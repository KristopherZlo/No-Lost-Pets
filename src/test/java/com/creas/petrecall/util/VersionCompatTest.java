package com.creas.petrecall.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.server.players.NameAndId;
import org.junit.jupiter.api.Test;

final class VersionCompatTest {
    @Test
    void matchesGameProfileByUuid() {
        UUID uuid = UUID.randomUUID();
        GameProfile player = new GameProfile(uuid, "PlayerOne");
        GameProfile operator = new GameProfile(uuid, "RenamedPlayer");

        assertTrue(VersionCompat.matchesProfileKey(player, operator));
    }

    @Test
    void matchesRecordLikeProfileByUuidEvenAfterRename() {
        UUID uuid = UUID.randomUUID();
        GameProfile player = new GameProfile(uuid, "PlayerOne");

        assertTrue(VersionCompat.matchesProfileKey(player, new NameAndId(uuid, "SomeoneElse")));
        assertFalse(VersionCompat.matchesProfileKey(player, new NameAndId(UUID.randomUUID(), "playerone")));
        assertFalse(VersionCompat.matchesProfileKey(player, new NameAndId(UUID.randomUUID(), "DifferentName")));
    }

    @Test
    void identicalNamesNeverGrantAnotherUuidOperatorAccess() {
        UUID uuid = UUID.randomUUID();
        GameProfile player = new GameProfile(uuid, "Admin");
        assertFalse(VersionCompat.matchesProfileKey(player, new GameProfile(UUID.randomUUID(), "Admin")));
        assertFalse(VersionCompat.matchesProfileKey(player, new ProfileKey(null, "Admin")));
        assertFalse(VersionCompat.matchesProfileKey(player, null));
        assertFalse(VersionCompat.matchesProfileKey(player, "Admin"));
        assertFalse(VersionCompat.matchesProfileKey(player, new AmbiguousKey(uuid, UUID.randomUUID())));
    }

    private record AmbiguousKey(UUID first, UUID second) { }
    private record ProfileKey(UUID id, String name) {
    }
}
