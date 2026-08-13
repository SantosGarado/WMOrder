package com.wildmare.wmorder.gui.input;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class ChatInputManager implements Listener {

    private record SignInput(
            Location location,
            Consumer<String> callback
    ) {}

    private final Plugin plugin;

    private final Map<UUID, Consumer<String>> inputs =
            new ConcurrentHashMap<>();

    private final Map<UUID, SignInput> signInputs =
            new ConcurrentHashMap<>();

    private final PlainTextComponentSerializer plain =
            PlainTextComponentSerializer.plainText();

    public ChatInputManager(Plugin plugin) {
        this.plugin = plugin;
    }

    public void request(
            Player player,
            Consumer<String> callback
    ) {
        cancel(player.getUniqueId());

        inputs.put(
                player.getUniqueId(),
                callback
        );

        player.closeInventory();
    }

    public void requestSign(
            Player player,
            String initialText,
            Consumer<String> callback
    ) {
        UUID uuid = player.getUniqueId();

        cancel(uuid);
        player.closeInventory();

        Bukkit.getScheduler().runTask(plugin, () -> {

            if (!player.isOnline()) {
                return;
            }

            Location location = findSignLocation(player);

            if (location == null) {
                callback.accept("");
                return;
            }

            Block block = location.getBlock();

            block.setType(
                    Material.OAK_SIGN,
                    false
            );

            if (!(block.getState() instanceof Sign sign)) {

                block.setType(
                        Material.AIR,
                        false
                );

                callback.accept("");
                return;
            }

            sign.getSide(Side.FRONT).line(
                    0,
                    Component.text(
                            initialText == null
                                    ? ""
                                    : initialText
                    )
            );

            sign.update(true, false);

            SignInput request =
                    new SignInput(
                            location.clone(),
                            callback
                    );

            signInputs.put(
                    uuid,
                    request
            );

            player.openSign(
                    sign,
                    Side.FRONT
            );
        });
    }

    public boolean active(UUID player) {
        return inputs.containsKey(player)
                || signInputs.containsKey(player);
    }

    public void cancel(UUID player) {

        inputs.remove(player);

        SignInput sign =
                signInputs.remove(player);

        if (sign != null) {
            restore(sign.location());
        }
    }

    public void clear() {

        for (SignInput input : signInputs.values()) {
            restore(input.location());
        }

        inputs.clear();
        signInputs.clear();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void chat(AsyncChatEvent event) {

        Consumer<String> callback =
                inputs.remove(
                        event.getPlayer()
                                .getUniqueId()
                );

        if (callback == null) {
            return;
        }

        event.setCancelled(true);

        String text =
                plain.serialize(
                        event.message()
                ).trim();

        Bukkit.getScheduler().runTask(
                plugin,
                () -> callback.accept(text)
        );
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void sign(SignChangeEvent event) {

        UUID uuid =
                event.getPlayer()
                        .getUniqueId();

        SignInput request =
                signInputs.get(uuid);

        if (request == null) {
            return;
        }

        if (!event.getBlock()
                .getLocation()
                .equals(request.location())) {
            return;
        }

        if (event.getSide() != Side.FRONT) {
            return;
        }

        signInputs.remove(uuid);

        event.setCancelled(true);

        String text =
                event.lines()
                        .stream()
                        .map(plain::serialize)
                        .map(String::trim)
                        .filter(line -> !line.isEmpty())
                        .findFirst()
                        .orElse("");

        Bukkit.getScheduler().runTask(
                plugin,
                () -> {
                    restore(request.location());
                    request.callback().accept(text);
                }
        );
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void open(InventoryOpenEvent event) {

        if (active(
                event.getPlayer()
                        .getUniqueId()
        )) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void quit(PlayerQuitEvent event) {

        UUID uuid =
                event.getPlayer()
                        .getUniqueId();

        inputs.remove(uuid);

        SignInput sign =
                signInputs.remove(uuid);

        if (sign != null) {
            restore(sign.location());
        }
    }

    private Location findSignLocation(Player player) {

        Location base =
                player.getLocation()
                        .getBlock()
                        .getLocation();

        int minY =
                player.getWorld()
                        .getMinHeight() + 1;

        int maxY =
                player.getWorld()
                        .getMaxHeight() - 2;

        for (int dy = 2; dy <= 6; dy++) {

            int y =
                    base.getBlockY() + dy;

            if (y < minY || y > maxY) {
                continue;
            }

            for (int dx = -2; dx <= 2; dx++) {

                for (int dz = -2; dz <= 2; dz++) {

                    Location location =
                            new Location(
                                    player.getWorld(),
                                    base.getBlockX() + dx,
                                    y,
                                    base.getBlockZ() + dz
                            );

                    if (location
                            .getBlock()
                            .getType()
                            .isAir()) {

                        return location;
                    }
                }
            }
        }

        return null;
    }

    private void restore(Location location) {

        if (location
                .getBlock()
                .getType()
                == Material.OAK_SIGN) {

            location
                    .getBlock()
                    .setType(
                            Material.AIR,
                            false
                    );
        }
    }
}
