package ir.ramtung.tinyme.messaging;

public class Message {
    public static final String INVALID_ORDER_ID = "Invalid order ID";
    public static final String ORDER_QUANTITY_NOT_POSITIVE = "Order quantity is not-positive";
    public static final String ORDER_PRICE_NOT_POSITIVE = "Order price is not-positive";
    public static final String UNKNOWN_SECURITY_ISIN = "Unknown security ISIN";
    public static final String ORDER_ID_NOT_FOUND = "Order ID not found in the order book";
    public static final String INVALID_PEAK_SIZE = "Iceberg order peak size is out of range";
    public static final String CANNOT_SPECIFY_PEAK_SIZE_FOR_A_NON_ICEBERG_ORDER = "Cannot specify peak size for a non-iceberg order";
    public static final String UNKNOWN_BROKER_ID = "Unknown broker ID";
    public static final String UNKNOWN_SHAREHOLDER_ID = "Unknown shareholder ID";
    public static final String BUYER_HAS_NOT_ENOUGH_CREDIT = "Buyer has not enough credit";
    public static final String QUANTITY_NOT_MULTIPLE_OF_LOT_SIZE = "Quantity is not a multiple of security lot size";
    public static final String PRICE_NOT_MULTIPLE_OF_TICK_SIZE = "Price is not a multiple of security tick size";
    public static final String SELLER_HAS_NOT_ENOUGH_POSITIONS = "Seller has not enough positions";
    public static final String INVALID_MINIMUM_QUANTITY = "Minimum quantity is out of range";
    public static final String MINIMUM_QUANTITY_INSUFFICIENT = "Minimum quantity is insufficient";
    public static final String CANT_UPDATE_MIN_QUANTITY = "Minimum quantity can not be changed";
    public static final String INVALID_STOP_PRICE = "Stop price can not be negative";
    public static final String STOP_LIMIT_ORDER_CAN_NOT_HAVE_MIN_EXEC_QUANTITY = "Stop limit order can not have minimum execution quantity";
    public static final String AN_ORDER_CAN_NOT_BE_BOTH_ICEBERG_AND_STOP_LIMIT = "An order can not be iceberg and stop limit at same time";
    public static final String CANT_ADD_NEW_STOP_LIMIT_ORDER_IN_AUCTION_STATE = "Can't add new stop limit order in auction state";
    public static final String CANT_HAVE_MINIMUM_EXEC_QUANTITY_IN_AUCTION_STATE = "Can't add new order with minimum execution quantity in auction state";
    public static final String CANT_UPDATE_STOP_LIMIT_ORDER_IN_AUCTION_STATE = "Can't update stop limit order in auction state";
    public static final String CANT_DELETE_STOP_LIMIT_ORDER_IN_AUCTION_STATE = "Can't delete stop limit order in auction state";
}