package com.wildmare.wmorder.gui;

import com.wildmare.wmorder.config.*;
import com.wildmare.wmorder.database.model.*;
import com.wildmare.wmorder.database.repository.HistoryRepository;
import com.wildmare.wmorder.gui.input.ChatInputManager;
import com.wildmare.wmorder.gui.session.*;
import com.wildmare.wmorder.item.*;
import com.wildmare.wmorder.order.model.*;
import com.wildmare.wmorder.order.service.*;
import com.wildmare.wmorder.order.transaction.IdempotencyKeys;
import com.wildmare.wmorder.permission.*;
import com.wildmare.wmorder.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class MenuManager {
    private final JavaPlugin plugin;private final ConfigManager configs;private final Messages messages;private final GuiItemFactory items;
    private final GuiSessionRegistry sessions;private final ChatInputManager inputs;private final OrderQueryService queries;private final OrderService orderService;
    private final FulfillmentService fulfillment;private final CollectionService collection;private final ItemSerializer serializer;private final InventoryItemService inventoryItems;
    private final LimitService limits;private final PriceCalculator prices;private final MoneyMath money;
    private final Map<UUID,Long> lastRefresh=new ConcurrentHashMap<>();private final SlidingWindowRateLimiter<UUID> searchLimiter;

    public MenuManager(JavaPlugin plugin,ConfigManager configs,Messages messages,GuiItemFactory items,GuiSessionRegistry sessions,
                       ChatInputManager inputs,OrderQueryService queries,OrderService orderService,FulfillmentService fulfillment,
                       CollectionService collection,ItemSerializer serializer,InventoryItemService inventoryItems,
                       LimitService limits,PriceCalculator prices,MoneyMath money){
        this.plugin=plugin;this.configs=configs;this.messages=messages;this.items=items;this.sessions=sessions;this.inputs=inputs;this.queries=queries;
        this.orderService=orderService;this.fulfillment=fulfillment;this.collection=collection;this.serializer=serializer;this.inventoryItems=inventoryItems;
        this.limits=limits;this.prices=prices;this.money=money;this.searchLimiter=new SlidingWindowRateLimiter<>(configs.settings().performance().searchesPerMinute(),60000);
    }

    public void openBrowser(Player player){openBrowser(player,OrderQuery.browser(0,configs.settings().performance().browserPageSize()),MenuType.BROWSER);}
    public void openBrowser(Player player,OrderQuery query,MenuType type){
        queries.query(query).thenAccept(page->MainThread.run(plugin,()->renderBrowser(player,query,page,type))).exceptionally(error->{MainThread.run(plugin,()->failure(player,"query_failure",root(error).getMessage()));return null;});
    }

    private void renderBrowser(Player player,OrderQuery query,OrderPage page,MenuType type){
        if(!player.isOnline())return;MenuSession session=sessions.create(player,type);session.query(query);Inventory inv=createInventory(session,type==MenuType.MY_ORDERS?"my-orders":"browser",Map.of("page",page.page()+1,"order",""));
        List<Integer> slots=orderSlots(inv.getSize());for(int i=0;i<page.entries().size()&&i<slots.size();i++){OrderSummary order=page.entries().get(i);int slot=slots.get(i);inv.setItem(slot,orderIcon(order));session.action(slot,new GuiAction(GuiAction.Type.DETAILS,order.id(),0,null));}
        if(page.page()>0){inv.setItem(45,items.button("previous",Map.of("page",page.page())));session.action(45,GuiAction.simple(GuiAction.Type.PREVIOUS));}
        inv.setItem(46,toggleIcon(query.fulfillableOnly()));session.action(46,GuiAction.simple(GuiAction.Type.TOGGLE_FULFILLABLE));
        inv.setItem(47,categoryIcon(query.category()));session.action(47,GuiAction.simple(GuiAction.Type.CYCLE_CATEGORY));
        inv.setItem(48,sortIcon(query.sort()));session.action(48,GuiAction.simple(GuiAction.Type.CYCLE_SORT));
if(type == MenuType.MY_ORDERS){
    inv.setItem(49,items.button("create-order",Map.of()));
    session.action(49,GuiAction.simple(GuiAction.Type.CREATE_OPEN));
}else{
    inv.setItem(49,items.button("my-orders",Map.of()));
    session.action(49,GuiAction.simple(GuiAction.Type.MY_ORDERS));
}
        inv.setItem(50,items.button("search",Map.of()));session.action(50,GuiAction.simple(GuiAction.Type.SEARCH));
        inv.setItem(51,items.button("collection",Map.of()));session.action(51,GuiAction.simple(GuiAction.Type.COLLECTION));
        inv.setItem(52,items.button("refresh",Map.of()));session.action(52,GuiAction.simple(GuiAction.Type.REFRESH));
        if(page.hasNext()){inv.setItem(53,items.button("next",Map.of("page",page.page()+2)));session.action(53,GuiAction.simple(GuiAction.Type.NEXT));}
        items.fill(inv);player.openInventory(inv);sound(player,"sounds.open",Sound.UI_BUTTON_CLICK);
    }

    public void openMyOrders(Player player){OrderQuery query=new OrderQuery("",null,null,player.getUniqueId(),Set.of(OrderStatus.ACTIVE,OrderStatus.PARTIALLY_FILLED,OrderStatus.FILLED,OrderStatus.ADMIN_FROZEN),OrderSort.NEWEST,false,0,configs.settings().performance().browserPageSize());openBrowser(player,query,MenuType.MY_ORDERS);}

    public void openHistory(Player player,int page){
        int size=configs.settings().performance().browserPageSize();queries.history(player.getUniqueId(),page,size).thenAccept(lines->MainThread.run(plugin,()->{
            if(!player.isOnline())return;MenuSession session=sessions.create(player,MenuType.HISTORY);Inventory inv=createInventory(session,"history",Map.of("page",page+1,"order",""));
            for(int i=0;i<lines.size()&&i<45;i++){HistoryRepository.HistoryLine line=lines.get(i);ItemStack icon=new ItemStack(Material.PAPER);ItemMeta meta=icon.getItemMeta();meta.displayName(messages.renderRaw("<gold>"+line.eventType(),Map.of()));meta.lore(List.of(messages.renderRaw("<gray>Order: <white>"+OrderService.shortId(line.orderId()),Map.of()),messages.renderRaw("<gray>Quantity: <white>"+line.quantity(),Map.of()),messages.renderRaw("<gray>Amount: <white>"+money.normalize(line.amount()),Map.of()),messages.renderRaw("<dark_gray>"+line.createdAt(),Map.of())));icon.setItemMeta(meta);inv.setItem(i,icon);session.action(i,new GuiAction(GuiAction.Type.DETAILS,line.orderId(),0,null));}
            if(page>0){inv.setItem(45,items.button("previous",Map.of("page",page)));session.action(45,new GuiAction(GuiAction.Type.PREVIOUS,null,page-1,"history"));}
            inv.setItem(49,items.button("my-orders",Map.of()));session.action(49,GuiAction.simple(GuiAction.Type.MY_ORDERS));if(lines.size()==size){inv.setItem(53,items.button("next",Map.of("page",page+2)));session.action(53,new GuiAction(GuiAction.Type.NEXT,null,page+1,"history"));}
            items.fill(inv);player.openInventory(inv);
        })).exceptionally(error->{MainThread.run(plugin,()->failure(player,"history_failure",root(error).getMessage()));return null;});
    }

    public void openCollection(Player player){
        queries.readyDeliveries(player.getUniqueId(),45).thenAccept(entries->MainThread.run(plugin,()->{
            if(!player.isOnline())return;MenuSession session=sessions.create(player,MenuType.COLLECTION);Inventory inv=createInventory(session,"collection",Map.of("page",1,"order",""));
            for(int i=0;i<entries.size()&&i<45;i++){DeliveryEntry entry=entries.get(i);ItemStack icon;
                if(entry.isItem()){try{icon=serializer.deserialize(entry.itemBlob());icon.setAmount((int)Math.min(icon.getMaxStackSize(),Math.max(1,entry.quantity())));}catch(RuntimeException e){icon=new ItemStack(Material.BARRIER);}}
                else icon=new ItemStack(Material.GOLD_INGOT);
                ItemMeta meta=icon.getItemMeta();List<Component> lore=new ArrayList<>(Optional.ofNullable(meta.lore()).orElse(List.of()));lore.add(Component.empty());lore.add(messages.renderRaw("<gray>Type: <white>"+entry.type(),Map.of()));if(entry.isItem())lore.add(messages.renderRaw("<gray>Quantity: <white>"+entry.quantity(),Map.of()));if(entry.isMoney())lore.add(messages.renderRaw("<gray>Amount: <green>"+entry.amount(),Map.of()));meta.lore(lore);icon.setItemMeta(meta);inv.setItem(i,icon);}
            inv.setItem(49,items.button(entries.isEmpty()?"empty":"collection",Map.of()));if(!entries.isEmpty())session.action(49,GuiAction.simple(GuiAction.Type.COLLECT_ALL));items.fill(inv);player.openInventory(inv);
        })).exceptionally(error->{MainThread.run(plugin,()->failure(player,"collection_failure",root(error).getMessage()));return null;});
    }

    public void openCreate(Player player){
    LimitProfile profile=limits.resolve(player);
    CreateState state=new CreateState(
            null,
            1,
            null,
            profile.duration(),
            null,
            UUID.randomUUID()
    );
    openCreate(player,state);
}

private void openCreate(Player player,CreateState state){
    MenuSession session=sessions.create(player,MenuType.CREATE);
    session.createState(state);

    Inventory inv=createInventory(
            session,
            "create",
            Map.of("page",1,"order","")
    );

    String itemName=state.hasItem()
            ? state.item().getType().name()
            : "Not selected";

    inv.setItem(11,items.button(
            "create-item",
            Map.of("item",itemName)
    ));
    session.action(11,GuiAction.simple(GuiAction.Type.CREATE_ITEM));

    inv.setItem(13,items.button(
            "create-amount",
            Map.of("amount",state.quantity())
    ));
    session.action(13,GuiAction.simple(GuiAction.Type.CREATE_AMOUNT));

    inv.setItem(15,items.button(
            "create-price",
            Map.of("price",state.price()==null?"Not set":state.price())
    ));
    session.action(15,GuiAction.simple(GuiAction.Type.CREATE_PRICE));

    inv.setItem(20,items.button("create-decline",Map.of()));
    session.action(20,GuiAction.simple(GuiAction.Type.CREATE_DECLINE));

    inv.setItem(24,items.button("confirm",Map.of()));

    if(state.hasItem() && state.price()!=null){
        session.action(24,GuiAction.simple(GuiAction.Type.CREATE_CONFIRM));
    }

    items.fill(inv);
    player.openInventory(inv);
}

private void openCreateItemBrowser(Player player,CreateState state,int page){
    List<Material> materials=Arrays.stream(Material.values())
            .filter(Material::isItem)
            .filter(material -> !material.isAir())
            .sorted(Comparator.comparing(Material::name))
            .toList();

    int pageSize=45;
    int maxPage=Math.max(0,(materials.size()-1)/pageSize);
    page=Math.max(0,Math.min(page,maxPage));

    MenuSession session=sessions.create(player,MenuType.CREATE_ITEM_BROWSER);
    session.createState(state);

    Inventory inv=createInventory(
            session,
            "create-item-browser",
            Map.of("page",page+1,"order","")
    );

    int start=page*pageSize;
    int end=Math.min(start+pageSize,materials.size());

    for(int index=start;index<end;index++){
        Material material=materials.get(index);
        int slot=index-start;

        ItemStack icon=new ItemStack(material);
        ItemMeta meta=icon.getItemMeta();

        meta.displayName(messages.renderRaw(
                "<white>"+material.name(),
                Map.of()
        ));

        icon.setItemMeta(meta);

        inv.setItem(slot,icon);
        session.action(
                slot,
                new GuiAction(
                        GuiAction.Type.CREATE_ITEM_SELECT,
                        null,
                        0,
                        material.name()
                )
        );
    }

    if(page>0){
        inv.setItem(
                45,
                items.button("previous",Map.of("page",page))
        );
        session.action(
                45,
                new GuiAction(
                        GuiAction.Type.CREATE_ITEM_PREVIOUS,
                        null,
                        page-1,
                        null
                )
        );
    }

    if(page<maxPage){
        inv.setItem(
                53,
                items.button("next",Map.of("page",page+2))
        );
        session.action(
                53,
                new GuiAction(
                        GuiAction.Type.CREATE_ITEM_NEXT,
                        null,
                        page+1,
                        null
                )
        );
    }

    items.fill(inv);
    player.openInventory(inv);
}
    
    public void openDetails(Player player,UUID orderId){queries.find(orderId).thenAccept(optional->MainThread.run(plugin,()->{
        if(optional.isEmpty()){failure(player,"order_not_found","Order not found");return;}BuyOrder order=optional.get();MenuSession session=sessions.create(player,MenuType.DETAILS);Inventory inv=createInventory(session,"details",Map.of("order",OrderService.shortId(order.id()),"page",1));
        ItemStack icon=serializer.deserialize(order.itemBlob());icon.setAmount(1);appendLore(icon,orderLore(order));inv.setItem(13,icon);
        if(order.acceptsFulfillment(Instant.now())&&!order.buyerUuid().equals(player.getUniqueId())){put(inv,session,20,"sell-one",Map.of(),new GuiAction(GuiAction.Type.SELL_ONE,order.id(),1,null));put(inv,session,22,"sell-stack",Map.of(),new GuiAction(GuiAction.Type.SELL_STACK,order.id(),icon.getMaxStackSize(),null));put(inv,session,24,"sell-all",Map.of(),new GuiAction(GuiAction.Type.SELL_ALL,order.id(),Long.MAX_VALUE,null));}
        if(order.buyerUuid().equals(player.getUniqueId())&&order.status().cancellable()){inv.setItem(26,items.button("cancel",Map.of()));session.action(26,new GuiAction(GuiAction.Type.CANCEL_ORDER,order.id(),0,null));}
        inv.setItem(18,items.button("previous",Map.of("page",1)));session.action(18,GuiAction.simple(GuiAction.Type.BACK));items.fill(inv);player.openInventory(inv);
    })).exceptionally(error->{MainThread.run(plugin,()->failure(player,"query_failure",root(error).getMessage()));return null;});}

    public void handle(Player player,MenuSession session,GuiAction action){
        if(session.busy())return;
        switch(action.type()){
            case PREVIOUS -> {if("history".equals(action.value()))openHistory(player,(int)action.amount());else openBrowser(player,session.query().withPage(Math.max(0,session.query().page()-1)),session.type());}
            case NEXT -> {if("history".equals(action.value()))openHistory(player,(int)action.amount());else openBrowser(player,session.query().withPage(session.query().page()+1),session.type());}
            case REFRESH -> refresh(player,session);
            case SEARCH -> requestSearch(player,session.query(),session.type());
            case MY_ORDERS -> openMyOrders(player);
            case CREATE_OPEN -> openCreate(player);
            case CREATE_DECLINE -> openMyOrders(player);
            case COLLECTION -> openCollection(player);
            case HISTORY -> openHistory(player,0);
            case DETAILS -> openDetails(player,action.orderId());
            case TOGGLE_FULFILLABLE -> openBrowser(player,copy(session.query(),null,null,null,null,null,null,!session.query().fulfillableOnly(),0),session.type());
            case CYCLE_SORT -> openBrowser(player,copy(session.query(),null,null,null,null,null,nextSort(session.query().sort()),null,0),session.type());
            case CYCLE_CATEGORY -> {
                OrderQuery current=session.query();
                OrderQuery updated=new OrderQuery(current.search(),nextCategory(current.category()),current.material(),current.buyerUuid(),current.statuses(),current.sort(),current.fulfillableOnly(),0,current.pageSize());
                openBrowser(player,updated,session.type());
            }
            case CREATE_QUANTITY -> adjustQuantity(player,session.createState(),action.amount());
            case CREATE_PRICE -> requestPrice(player,session.createState());
            case CREATE_CONFIRM -> confirmCreate(player,session.createState());
            case CREATE_EXECUTE -> executeCreate(player,session);
            case SELL_ONE,SELL_STACK,SELL_ALL -> confirmFulfillment(player,action.orderId(),action.amount());
            case FULFILL_EXECUTE -> executeFulfillment(player,session,action);
            case CANCEL_ORDER -> confirmCancel(player,action.orderId());
            case CANCEL_EXECUTE -> executeCancel(player,session,action.orderId());
            case COLLECT_ALL -> executeCollection(player,session);
            case BACK -> openBrowser(player);
        }
    }

    private void refresh(Player player,MenuSession session){long now=System.currentTimeMillis(),previous=lastRefresh.getOrDefault(player.getUniqueId(),0L),cooldown=configs.settings().performance().guiRefreshCooldownMillis();if(now-previous<cooldown&&!player.hasPermission("wmorder.bypass.cooldown")){failure(player,"cooldown",Long.toString((cooldown-(now-previous)+999)/1000));return;}lastRefresh.put(player.getUniqueId(),now);queries.invalidate();if(session.type()==MenuType.COLLECTION)openCollection(player);else if(session.type()==MenuType.HISTORY)openHistory(player,0);else openBrowser(player,session.query(),session.type());}
    private void requestSearch(Player player,OrderQuery query,MenuType type){if(!searchLimiter.tryAcquire(player.getUniqueId())){messages.send(player,"search-rate-limited");return;}messages.send(player,"input-search");inputs.request(player,text->{if(text.equalsIgnoreCase("cancel")){messages.send(player,"input-cancelled");openBrowser(player,query,type);}else openBrowser(player,query.withSearch(text),type);});}
    private void requestPrice(Player player,CreateState state){messages.send(player,"input-price");inputs.request(player,text->{if(text.equalsIgnoreCase("cancel")){messages.send(player,"input-cancelled");openCreate(player,state);return;}try{BigDecimal price=money.normalize(new BigDecimal(text.replace(",","")));if(price.signum()<=0)throw new NumberFormatException();state.price(price);openCreate(player,state);}catch(NumberFormatException|ArithmeticException e){messages.send(player,"invalid-number");openCreate(player,state);}});}
    private void adjustQuantity(Player player,CreateState state,long delta){LimitProfile profile=limits.resolve(player);long max=profile.maxQuantityPerOrder();long updated;try{updated=Math.addExact(state.quantity(),delta);}catch(ArithmeticException e){updated=delta>0?max:1;}state.quantity(Math.max(1,Math.min(max,updated)));openCreate(player,state);}

    private void confirmCreate(Player player,CreateState state){if(state.price()==null){messages.send(player,"invalid-number");return;}MenuSession session=sessions.create(player,MenuType.CONFIRM_CREATE);session.createState(state);Inventory inv=createInventory(session,"confirm",Map.of("page",1,"order",""));ItemStack icon=state.item();icon.setAmount(1);BigDecimal gross=money.multiply(state.price(),state.quantity());MoneyBreakdown breakdown=prices.creation(gross,limits.resolve(player));appendLore(icon,List.of("<gray>Quantity: <white>"+state.quantity(),"<gray>Price each: <white>"+state.price(),"<gray>Order value: <white>"+gross,"<gray>Fees: <yellow>"+breakdown.totalFee(),"<gray>Deposit: <green>"+breakdown.netOrDeposit()));inv.setItem(13,icon);inv.setItem(11,items.button("cancel",Map.of()));session.action(11,GuiAction.simple(GuiAction.Type.BACK));inv.setItem(15,items.button("confirm",Map.of()));session.action(15,GuiAction.simple(GuiAction.Type.CREATE_EXECUTE));items.fill(inv);player.openInventory(inv);}
    private void executeCreate(Player player,MenuSession session){if(!session.begin())return;CreateState state=session.createState();OrderDraft draft=new OrderDraft(state.item(),state.quantity(),state.price(),state.duration(),state.category(),IdempotencyKeys.creation(player.getUniqueId(),state.idempotencySession()));orderService.create(player,draft).whenComplete((result,error)->MainThread.run(plugin,()->{session.finish();if(error!=null){failure(player,"transaction_failure",root(error).getMessage());openCreate(player,state);return;}if(result.success()){sound(player,"sounds.success",Sound.ENTITY_EXPERIENCE_ORB_PICKUP);openMyOrders(player);}else{failure(player,result.code(),result.detail());openCreate(player,state);}}));}

    private void confirmFulfillment(Player player,UUID orderId,long requested){queries.find(orderId).thenAccept(optional->MainThread.run(plugin,()->{if(optional.isEmpty()){failure(player,"order_not_found","not found");return;}BuyOrder order=optional.get();ItemStack template=serializer.deserialize(order.itemBlob());long max=Math.min(Math.min(requested,order.remainingQuantity()),configs.settings().orders().maximumItemsPerTransaction());long available=inventoryItems.count(player.getInventory(),template,max);if(available<=0){messages.send(player,"no-matching-items");return;}MoneyBreakdown payout=prices.sellerPayout(order.pricePerItem().multiply(BigDecimal.valueOf(available)),limits.resolve(player));MenuSession session=sessions.create(player,MenuType.CONFIRM_FULFILL);Inventory inv=createInventory(session,"confirm",Map.of("page",1,"order",OrderService.shortId(orderId)));template.setAmount(1);appendLore(template,List.of("<gray>Sell quantity: <white>"+available,"<gray>Gross payout: <white>"+payout.gross(),"<gray>Tax: <yellow>"+payout.totalFee(),"<gray>Final payout: <green>"+payout.netOrDeposit(),"<gray>Expires: <white>"+DurationParser.compact(Duration.between(Instant.now(),order.expiresAt()))));inv.setItem(13,template);inv.setItem(11,items.button("cancel",Map.of()));session.action(11,new GuiAction(GuiAction.Type.DETAILS,orderId,0,null));inv.setItem(15,items.button("confirm",Map.of()));session.action(15,new GuiAction(GuiAction.Type.FULFILL_EXECUTE,orderId,available,UUID.randomUUID().toString()));items.fill(inv);player.openInventory(inv);}));}
    private void executeFulfillment(Player player,MenuSession session,GuiAction action){if(!session.begin())return;UUID txSession=UUID.fromString(action.value());fulfillment.fulfill(player,action.orderId(),action.amount(),txSession).whenComplete((result,error)->MainThread.run(plugin,()->{session.finish();if(error!=null){failure(player,"transaction_failure",root(error).getMessage());openDetails(player,action.orderId());return;}if(result.success()){messages.send(player,"sale-complete",Map.of("quantity",result.value().quantity(),"item",result.value().itemDisplayName(),"amount",result.value().net()));sound(player,"sounds.success",Sound.ENTITY_EXPERIENCE_ORB_PICKUP);}else failure(player,result.code(),result.detail());openDetails(player,action.orderId());}));}

    private void confirmCancel(Player player,UUID orderId){MenuSession session=sessions.create(player,MenuType.CONFIRM_CANCEL);Inventory inv=createInventory(session,"confirm",Map.of("page",1,"order",OrderService.shortId(orderId)));inv.setItem(11,items.button("cancel",Map.of()));session.action(11,new GuiAction(GuiAction.Type.DETAILS,orderId,0,null));inv.setItem(15,items.button("confirm",Map.of()));session.action(15,new GuiAction(GuiAction.Type.CANCEL_EXECUTE,orderId,0,null));items.fill(inv);player.openInventory(inv);}
    private void executeCancel(Player player,MenuSession session,UUID orderId){if(!session.begin())return;orderService.cancel(player,orderId,false,"Cancelled by buyer").whenComplete((result,error)->MainThread.run(plugin,()->{session.finish();if(error!=null)failure(player,"transaction_failure",root(error).getMessage());else if(!result.success())failure(player,result.code(),result.detail());else messages.send(player,"order-cancelled",Map.of("order",OrderService.shortId(orderId)));openMyOrders(player);}));}
    private void executeCollection(Player player,MenuSession session){if(!session.begin())return;collection.collect(player).whenComplete((result,error)->MainThread.run(plugin,()->{session.finish();if(error!=null)failure(player,"collection_failure",root(error).getMessage());else if(!result.success()){if(result.code().equals("empty"))messages.send(player,"collection-empty");else failure(player,result.code(),result.detail());}else{messages.send(player,"collection-result",Map.of("items",result.value().items(),"money",result.value().money()));if(result.value().partial())messages.send(player,"collection-partial");sound(player,"sounds.success",Sound.ENTITY_EXPERIENCE_ORB_PICKUP);}openCollection(player);}));}

    private Inventory createInventory(MenuSession session,String menu,Map<String,?> placeholders){int configured=configs.guiConfig().getInt("menus."+menu+".size",54);int size=Math.max(9,Math.min(54,((configured+8)/9)*9));String raw=configs.guiConfig().getString("menus."+menu+".title","<dark_gray>WMOrder");WMInventoryHolder holder=new WMInventoryHolder(session.id());Inventory inv=Bukkit.createInventory(holder,size,messages.renderRaw(raw,placeholders));holder.inventory(inv);session.inventory(inv);return inv;}
    private List<Integer> orderSlots(int size){List<Integer> configured=configs.guiConfig().getIntegerList("menus.browser.order-slots");if(!configured.isEmpty())return configured.stream().filter(i->i>=0&&i<size).toList();List<Integer> slots=new ArrayList<>();for(int i=0;i<Math.min(45,size);i++)slots.add(i);return slots;}
    private ItemStack orderIcon(OrderSummary order){ItemStack icon;try{icon=serializer.deserialize(order.itemBlob());}catch(RuntimeException e){icon=new ItemStack(Material.BARRIER);}icon.setAmount(1);appendLore(icon,List.of("","<gray>Buyer: <white>"+order.buyerName(),"<gray>Price each: <green>"+order.pricePerItem(),"<gray>Remaining: <white>"+order.remainingQuantity(),"<gray>Remaining value: <white>"+order.remainingValue(),"<gray>Status: "+(order.status()==OrderStatus.PARTIALLY_FILLED?"<yellow>":"<green>")+order.status(),"<gray>Expires in: <white>"+DurationParser.compact(Duration.between(Instant.now(),order.expiresAt())),"<dark_gray>ID: "+order.id()));return icon;}
    private List<String> orderLore(BuyOrder order){return List.of("","<gray>Buyer: <white>"+order.buyerName(),"<gray>Requested: <white>"+order.requestedQuantity(),"<gray>Fulfilled: <white>"+order.fulfilledQuantity(),"<gray>Remaining: <white>"+order.remainingQuantity(),"<gray>Price each: <green>"+order.pricePerItem(),"<gray>Reserved balance: <white>"+order.remainingReservedBalance(),"<gray>Status: <white>"+order.status(),"<gray>Expires: <white>"+order.expiresAt(),"<dark_gray>Version: "+order.version());}
    private void appendLore(ItemStack item,List<String> lines){ItemMeta meta=item.getItemMeta();List<Component> lore=new ArrayList<>(Optional.ofNullable(meta.lore()).orElse(List.of()));for(String line:lines)lore.add(messages.renderRaw(line,Map.of()));meta.lore(lore);item.setItemMeta(meta);}
    private void put(Inventory inv,MenuSession session,int slot,String button,Map<String,?> placeholders,GuiAction action){if(slot>=0&&slot<inv.getSize()){inv.setItem(slot,items.button(button,placeholders));session.action(slot,action);}}
    private ItemStack toggleIcon(boolean enabled){ItemStack icon=new ItemStack(enabled?Material.LIME_DYE:Material.GRAY_DYE);ItemMeta meta=icon.getItemMeta();meta.displayName(messages.renderRaw(enabled?"<green>Fulfillable only":"<gray>All active orders",Map.of()));icon.setItemMeta(meta);return icon;}
    private ItemStack sortIcon(OrderSort sort){ItemStack icon=new ItemStack(Material.HOPPER);ItemMeta meta=icon.getItemMeta();meta.displayName(messages.renderRaw("<aqua>Sort: <white>"+sort,Map.of()));icon.setItemMeta(meta);return icon;}
    private ItemStack categoryIcon(String category){CategoryRegistry.Category found=configs.categories().find(category).orElse(null);ItemStack icon=new ItemStack(found==null?Material.CHEST:found.icon());ItemMeta meta=icon.getItemMeta();meta.displayName(found==null?messages.renderRaw("<yellow>Category: <white>All",Map.of()):found.name());icon.setItemMeta(meta);return icon;}
    private OrderSort nextSort(OrderSort current){OrderSort[] all=OrderSort.values();return all[(current.ordinal()+1)%all.length];}
    private String nextCategory(String current){List<String> ids=configs.categories().all().stream().map(CategoryRegistry.Category::id).toList();if(ids.isEmpty())return null;if(current==null)return ids.get(0);int index=ids.indexOf(current);return index<0||index+1>=ids.size()?null:ids.get(index+1);}
    private OrderQuery copy(OrderQuery q,String search,String category,String material,UUID buyer,Set<OrderStatus> statuses,OrderSort sort,Boolean fulfillable,Integer page){return new OrderQuery(search==null?q.search():search,category==null?q.category():category,material==null?q.material():material,buyer==null?q.buyerUuid():buyer,statuses==null?q.statuses():statuses,sort==null?q.sort():sort,fulfillable==null?q.fulfillableOnly():fulfillable,page==null?q.page():page,q.pageSize());}
    private void sound(Player player,String path,Sound fallback){String configured=configs.guiConfig().getString(path,fallback.name());try{player.playSound(player.getLocation(),Sound.valueOf(configured.toUpperCase(Locale.ROOT)),0.7f,1.0f);}catch(IllegalArgumentException ignored){player.playSound(player.getLocation(),fallback,0.7f,1.0f);}}
    private void failure(Player player,String code,String detail){if(code.equals("no_items")){messages.send(player,"no-matching-items");return;}if(code.equals("cooldown")){messages.send(player,"cooldown",Map.of("seconds",detail));return;}if(code.equals("order_not_found")){messages.send(player,"invalid-order",Map.of("order","?"));return;}messages.send(player,"admin-action-failed",Map.of("reason",detail==null||detail.isBlank()?code:detail));sound(player,"sounds.error",Sound.ENTITY_VILLAGER_NO);}
    public void closeAll(){for(Player player:Bukkit.getOnlinePlayers())if(player.getOpenInventory().getTopInventory().getHolder() instanceof WMInventoryHolder)player.closeInventory();sessions.clear();inputs.clear();}
    private static Throwable root(Throwable t){Throwable v=t;while(v instanceof java.util.concurrent.CompletionException&&v.getCause()!=null)v=v.getCause();return v;}
}
