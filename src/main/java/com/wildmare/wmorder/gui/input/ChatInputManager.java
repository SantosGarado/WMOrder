package com.wildmare.wmorder.gui.input;

import io.papermc.paper.event.packet.UncheckedSignChangeEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.math.Position;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class ChatInputManager implements Listener {

    private record SignInput(Location location, Consumer<String> callback) {}

    private final Plugin plugin;
    private final Map<UUID,Consumer<String>> inputs=new ConcurrentHashMap<>();
    private final Map<UUID,SignInput> signInputs=new ConcurrentHashMap<>();
    private final PlainTextComponentSerializer plain=PlainTextComponentSerializer.plainText();

    public ChatInputManager(Plugin plugin){
        this.plugin=plugin;
    }

    public void request(Player player,Consumer<String> callback){
        cancel(player.getUniqueId());
        inputs.put(player.getUniqueId(),callback);
        player.closeInventory();
    }

    public void requestSign(Player player,String initialText,Consumer<String> callback){
        cancel(player.getUniqueId());
        player.closeInventory();

        Location location=player.getLocation().getBlock().getLocation();

        int y=Math.max(
                player.getWorld().getMinHeight()+1,
                Math.min(player.getWorld().getMaxHeight()-2,location.getBlockY()-4)
        );

        location.setY(y);

        BlockData signData=Material.OAK_SIGN.createBlockData();
        BlockState blockState=signData.createBlockState();

        if(!(blockState instanceof Sign sign)){
            callback.accept("");
            return;
        }

        sign.getSide(Side.FRONT).line(
                0,
                Component.text(initialText==null?"":initialText)
        );

        player.sendBlockChange(location,signData);
        player.sendBlockUpdate(location,sign);

        signInputs.put(
                player.getUniqueId(),
                new SignInput(location.clone(),callback)
        );

        player.openVirtualSign(
                Position.block(location),
                Side.FRONT
        );
    }

    public boolean active(UUID player){
        return inputs.containsKey(player) || signInputs.containsKey(player);
    }

    public void cancel(UUID player){
        inputs.remove(player);

        SignInput sign=signInputs.remove(player);

        if(sign!=null){
            Player online=Bukkit.getPlayer(player);

            if(online!=null){
                restore(online,sign.location());
            }
        }
    }

    public void clear(){
        for(Map.Entry<UUID,SignInput> entry:signInputs.entrySet()){
            Player player=Bukkit.getPlayer(entry.getKey());

            if(player!=null){
                restore(player,entry.getValue().location());
            }
        }

        inputs.clear();
        signInputs.clear();
    }

    @EventHandler(priority=EventPriority.HIGHEST)
    public void chat(AsyncChatEvent event){
        Consumer<String> callback=inputs.remove(
                event.getPlayer().getUniqueId()
        );

        if(callback==null)return;

        event.setCancelled(true);

        String text=plain.serialize(event.message()).trim();

        Bukkit.getScheduler().runTask(
                plugin,
                ()->callback.accept(text)
        );
    }

    @EventHandler(priority=EventPriority.HIGHEST)
    public void sign(UncheckedSignChangeEvent event){
        UUID uuid=event.getPlayer().getUniqueId();

        SignInput request=signInputs.get(uuid);

        if(request==null)return;

        if(!event.getEditedBlockPosition().equals(
                Position.block(request.location())
        )){
            return;
        }

        signInputs.remove(uuid);
        event.setCancelled(true);

        String text=event.lines().stream()
                .map(plain::serialize)
                .map(String::trim)
                .filter(line->!line.isEmpty())
                .findFirst()
                .orElse("");

        Bukkit.getScheduler().runTask(plugin,()->{
            restore(event.getPlayer(),request.location());
            request.callback().accept(text);
        });
    }

    @EventHandler(
            priority=EventPriority.HIGHEST,
            ignoreCancelled=true
    )
    public void open(InventoryOpenEvent event){
        if(active(event.getPlayer().getUniqueId())){
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void quit(PlayerQuitEvent event){
        inputs.remove(event.getPlayer().getUniqueId());
        signInputs.remove(event.getPlayer().getUniqueId());
    }

    private void restore(Player player,Location location){
        player.sendBlockChange(
                location,
                location.getBlock().getBlockData()
        );

        BlockState state=location.getBlock().getState();

        if(state instanceof TileState tile){
            player.sendBlockUpdate(location,tile);
        }
    }
}
