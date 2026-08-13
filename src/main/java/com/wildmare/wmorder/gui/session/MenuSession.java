package com.wildmare.wmorder.gui.session;

import com.wildmare.wmorder.order.model.OrderQuery;
import org.bukkit.inventory.Inventory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MenuSession {
    private final UUID id;private final UUID owner;private final MenuType type;private final Instant createdAt=Instant.now();private final Map<Integer,GuiAction> actions=new HashMap<>();private final AtomicBoolean busy=new AtomicBoolean();
    private OrderQuery query; private CreateState createState; private Inventory inventory; private String itemSearch=""; private String itemFilter="ALL";
    public MenuSession(UUID id,UUID owner,MenuType type){this.id=id;this.owner=owner;this.type=type;}
    public UUID id(){return id;}public UUID owner(){return owner;}public MenuType type(){return type;}public Instant createdAt(){return createdAt;}
    public Map<Integer,GuiAction> actions(){return actions;}public void action(int slot,GuiAction action){actions.put(slot,action);}public GuiAction action(int slot){return actions.get(slot);}
    public boolean begin(){return busy.compareAndSet(false,true);}public void finish(){busy.set(false);}public boolean busy(){return busy.get();}
    public OrderQuery query(){return query;}
public void query(OrderQuery query){this.query=query;}

public CreateState createState(){return createState;}
public void createState(CreateState state){this.createState=state;}

public Inventory inventory(){return inventory;}
public void inventory(Inventory inventory){this.inventory=inventory;}

public String itemSearch(){return itemSearch;}
public void itemSearch(String itemSearch){
    this.itemSearch=itemSearch==null?"":itemSearch;
}

public String itemFilter(){return itemFilter;}
public void itemFilter(String itemFilter){
    this.itemFilter=itemFilter==null?"ALL":itemFilter;
}
    public boolean stale(){return createdAt.isBefore(Instant.now().minusSeconds(300));}
}
