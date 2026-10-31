package cl.lilith;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Crea a Lilith como un ServerPlayer real (sin cliente) usando NMS. */
public final class LilithBody {

    public static final String NAME = "Lilith";
    public static final UUID ID =
            UUID.nameUUIDFromBytes(("OfflinePlayer:" + NAME).getBytes(StandardCharsets.UTF_8));

    private LilithBody() {}

    public static ServerPlayer spawn(Location loc) {
        MinecraftServer server = ((CraftServer) Bukkit.getServer()).getServer();
        ServerLevel level = ((CraftWorld) loc.getWorld()).getHandle();
        GameProfile profile = new GameProfile(ID, NAME);

        ServerPlayer player = new ServerPlayer(server, level, profile, ClientInformation.createDefault());

        // Conexion falsa: un canal en memoria, sin red.
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);

        boolean first = !Bukkit.getOfflinePlayer(ID).hasPlayedBefore();
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        if (first) {
            player.getBukkitEntity().teleport(loc);
        }
        return player;
    }

    /** El ServerPlayer actual de Lilith (cambia al reaparecer), o null si no esta conectada. */
    public static ServerPlayer handle() {
        Player pl = Bukkit.getPlayer(ID);
        return pl == null ? null : ((CraftPlayer) pl).getHandle();
    }
}
