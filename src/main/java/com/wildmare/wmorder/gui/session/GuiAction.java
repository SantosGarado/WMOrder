package com.wildmare.wmorder.gui.session;

import java.util.UUID;

public record GuiAction(Type type,UUID orderId,long amount,String value) {
    public enum Type { PREVIOUS,NEXT,REFRESH,SEARCH,MY_ORDERS,CREATE_OPEN,CREATE_ITEM,CREATE_AMOUNT,CREATE_DECLINE,COLLECTION,HISTORY,DETAILS,TOGGLE_FULFILLABLE,CYCLE_SORT,CYCLE_CATEGORY,
        CREATE_QUANTITY,CREATE_PRICE,CREATE_CONFIRM,CREATE_EXECUTE,BACK,SELL_ONE,SELL_STACK,SELL_ALL,FULFILL_EXECUTE,CANCEL_ORDER,CANCEL_EXECUTE,COLLECT_ALL }
    public static GuiAction simple(Type type){return new GuiAction(type,null,0,null);}
}
