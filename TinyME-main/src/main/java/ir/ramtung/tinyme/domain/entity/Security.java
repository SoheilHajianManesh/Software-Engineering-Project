package ir.ramtung.tinyme.domain.entity;

import ir.ramtung.tinyme.domain.service.AuctionMatcher;
import ir.ramtung.tinyme.messaging.exception.InvalidRequestException;
import ir.ramtung.tinyme.messaging.request.DeleteOrderRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.domain.service.ContinuousMatcher;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.request.MatchingState;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Builder
public class Security {
    private String isin;
    @Builder.Default
    private int tickSize = 1;
    @Builder.Default
    private int lotSize = 1;
    @Builder.Default
    private OrderBook orderBook = new OrderBook();
    @Builder.Default
    @Setter
    private int lastTradedPrice = 0;
    @Builder.Default
    private MatchingState matchingState = MatchingState.CONTINUOUS;


    private void validateOrderForUpdate(Order order, EnterOrderRq updateOrderRq) throws InvalidRequestException {
        if ((order instanceof IcebergOrder) && updateOrderRq.getPeakSize() == 0)
            throw new InvalidRequestException(Message.INVALID_PEAK_SIZE);
        if (!(order instanceof IcebergOrder) && updateOrderRq.getPeakSize() != 0)
            throw new InvalidRequestException(Message.CANNOT_SPECIFY_PEAK_SIZE_FOR_A_NON_ICEBERG_ORDER);
        if (order.getMinimumExpectedQuantity() != updateOrderRq.getMinimumExecutionQuantity())
            throw new InvalidRequestException(Message.CANT_UPDATE_MIN_QUANTITY);
        if(matchingState==MatchingState.AUCTION && updateOrderRq.getStopPrice()>0){
            throw new InvalidRequestException(Message.CANT_UPDATE_STOP_LIMIT_ORDER_IN_AUCTION_STATE);
        }

    }
    private Order getOrderForUpdate(EnterOrderRq updateOrderRq) throws InvalidRequestException {
        Order order;
        if (updateOrderRq.getStopPrice() > 0)
            order = orderBook.findByOrderIdInInactiveQueue(updateOrderRq.getSide(), updateOrderRq.getOrderId());
        else
            order = orderBook.findByOrderIdInActiveQueue(updateOrderRq.getSide(), updateOrderRq.getOrderId());

        if (order == null)
            throw new InvalidRequestException(Message.ORDER_ID_NOT_FOUND);
        return order;
    }
    private boolean hasDealerEnoughCreditOrSecurity(Order order){
        if(order.getSide()==Side.BUY)
            return order.getBroker().hasEnoughCredit(order.getValue());
        else
            return order.getShareholder().hasEnoughPositionsOn(order.getSecurity(), order.getQuantity());
    }

    private MatchResult handleInactiveOrder(Order order){
        if (order.getSide()==Side.BUY)
            order.getBroker().decreaseCreditBy(order.getValue());
        orderBook.enqueueToInactiveQueue(order);
        return MatchResult.inActiveOrderEnqueued();
    }

    private boolean hasNotEnoughPosition(EnterOrderRq enterOrderRq, Shareholder shareholder){
        return enterOrderRq.getSide() == Side.SELL &&
                !shareholder.hasEnoughPositionsOn(this,
                        orderBook.totalSellQuantityByShareholder(shareholder) + enterOrderRq.getQuantity());
    }

    private MatchResult newOrderInAuctionState(EnterOrderRq enterOrderRq, Broker broker, Shareholder shareholder) throws InvalidRequestException {
        if(enterOrderRq.getStopPrice()>0)
            throw new InvalidRequestException(Message.CANT_ADD_NEW_STOP_LIMIT_ORDER_IN_AUCTION_STATE);
        if (hasNotEnoughPosition(enterOrderRq, shareholder))
            return MatchResult.notEnoughPositions();
        Order order;
        if (enterOrderRq.getPeakSize() == 0)
            order = new Order(enterOrderRq.getOrderId(), this, enterOrderRq.getSide(),
                    enterOrderRq.getQuantity(), enterOrderRq.getPrice(), broker, shareholder, enterOrderRq.getEntryTime(), OrderStatus.NEW, enterOrderRq.getMinimumExecutionQuantity());
        else
            order = new IcebergOrder(enterOrderRq.getOrderId(), this, enterOrderRq.getSide(),
                    enterOrderRq.getQuantity(), enterOrderRq.getPrice(), broker, shareholder,
                    enterOrderRq.getEntryTime(), enterOrderRq.getPeakSize(), OrderStatus.NEW, enterOrderRq.getMinimumExecutionQuantity());

        if (order.getSide() == Side.BUY) {
            if (!order.getBroker().hasEnoughCredit(order.getValue())) {
                return MatchResult.notEnoughCredit();
            }
            order.getBroker().decreaseCreditBy(order.getValue());
        }
        orderBook.enqueueToActiveQueue(order);

        return MatchResult.openingPriceAnnouncement();
    }

    private MatchResult newOrderInContinuousState(EnterOrderRq enterOrderRq, Broker broker, Shareholder shareholder, ContinuousMatcher continuousMatcher){
        if (hasNotEnoughPosition(enterOrderRq, shareholder))
            return MatchResult.notEnoughPositions();
        Order order;
        if (enterOrderRq.getPeakSize() == 0 && enterOrderRq.getStopPrice() == 0)
            order = new Order(enterOrderRq.getOrderId(), this, enterOrderRq.getSide(),
                    enterOrderRq.getQuantity(), enterOrderRq.getPrice(), broker, shareholder, enterOrderRq.getEntryTime(), OrderStatus.NEW, enterOrderRq.getMinimumExecutionQuantity());
        else if(enterOrderRq.getPeakSize() != 0)
            order = new IcebergOrder(enterOrderRq.getOrderId(), this, enterOrderRq.getSide(),
                    enterOrderRq.getQuantity(), enterOrderRq.getPrice(), broker, shareholder,
                    enterOrderRq.getEntryTime(), enterOrderRq.getPeakSize(), OrderStatus.NEW, enterOrderRq.getMinimumExecutionQuantity());
        else {
            order = new StopLimitOrder(enterOrderRq.getOrderId(), this, enterOrderRq.getSide(),
                    enterOrderRq.getQuantity(), enterOrderRq.getPrice(), broker, shareholder,
                    enterOrderRq.getStopPrice(),enterOrderRq.getRequestId());
            if (!hasDealerEnoughCreditOrSecurity(order)) {
                return (order.getSide() == Side.BUY) ? MatchResult.notEnoughCredit() : MatchResult.notEnoughPositions();
            }
            if (!order.canBeActive(lastTradedPrice))
                return handleInactiveOrder(order);
        }
        return continuousMatcher.execute(order);
    }

    public MatchResult newOrder(EnterOrderRq enterOrderRq, Broker broker, Shareholder shareholder, ContinuousMatcher continuousMatcher)  throws InvalidRequestException{
        if(matchingState==MatchingState.AUCTION)
            return newOrderInAuctionState(enterOrderRq, broker, shareholder);
        else
            return newOrderInContinuousState(enterOrderRq, broker, shareholder, continuousMatcher);
    }

    public void deleteOrder(DeleteOrderRq deleteOrderRq) throws InvalidRequestException {
        Order order = orderBook.findOrderInAllQueues(deleteOrderRq.getSide(), deleteOrderRq.getOrderId());
        Order orderIfIsInInactiveQueue = orderBook.findByOrderIdInInactiveQueue(deleteOrderRq.getSide(), deleteOrderRq.getOrderId());
        if(matchingState==MatchingState.AUCTION && orderIfIsInInactiveQueue != null)
            throw new InvalidRequestException(Message.CANT_DELETE_STOP_LIMIT_ORDER_IN_AUCTION_STATE);
        if (order == null)
            throw new InvalidRequestException(Message.ORDER_ID_NOT_FOUND);
        if (order.getSide() == Side.BUY)
            order.getBroker().increaseCreditBy(order.getValue());
        orderBook.removeByOrderFromBothQueues(deleteOrderRq.getSide(), deleteOrderRq.getOrderId());
    }


    private boolean hasEnoughPositionsToUpdate(Order order, EnterOrderRq updateOrderRq) {
        return order.getShareholder().hasEnoughPositionsOn(this,
                orderBook.totalSellQuantityByShareholder(order.getShareholder()) - order.getQuantity() + updateOrderRq.getQuantity());
    }

    private boolean checkPriorityLoss(Order order, EnterOrderRq updateOrderRq) {
        return order.isQuantityIncreased(updateOrderRq.getQuantity())
                || updateOrderRq.getPrice() != order.getPrice()
                || (order instanceof IcebergOrder icebergOrder && icebergOrder.getPeakSize() < updateOrderRq.getPeakSize());
    }

    private void increaseBrokerCredit(Order order) {
        order.getBroker().increaseCreditBy(order.getValue());
    }

    private void decreaseBrokerCredit(Order order) {
        order.getBroker().decreaseCreditBy(order.getValue());
    }

    private void handleNoPriorityLoss(Order order) {
        if (order.getSide() == Side.BUY) {
            order.getBroker().decreaseCreditBy(order.getValue());
        }
    }

    private MatchResult handleInactiveOrderInQueue(Order order, EnterOrderRq updateOrderRq) {
        if (updateOrderRq.getSide() == Side.BUY)
            order.getBroker().decreaseCreditBy(order.getValue());
        orderBook.enqueueToInactiveQueue(order);
        return MatchResult.inActiveOrderEnqueued();
    }


    private MatchResult executeMatching(ContinuousMatcher continuousMatcher, Order order, Order originalOrder, EnterOrderRq updateOrderRq) {
        MatchResult matchResult = continuousMatcher.execute(order);
        if (matchResult.outcome() != MatchingOutcome.EXECUTED) {
            orderBook.enqueueToActiveQueue(originalOrder);
            if (updateOrderRq.getSide() == Side.BUY) {
                originalOrder.getBroker().decreaseCreditBy(originalOrder.getValue());
            }
        }
        return matchResult;
    }

    private MatchResult updateOrderInContinuousState(EnterOrderRq updateOrderRq, ContinuousMatcher continuousMatcher) throws InvalidRequestException {
        Order order = getOrderForUpdate(updateOrderRq);
        validateOrderForUpdate(order, updateOrderRq);

        if (updateOrderRq.getSide() == Side.SELL) {
            if (!hasEnoughPositionsToUpdate(order, updateOrderRq)) {
                return MatchResult.notEnoughPositions();
            }
        }

        boolean losesPriority = checkPriorityLoss(order, updateOrderRq);

        if (updateOrderRq.getSide() == Side.BUY) {
            increaseBrokerCredit(order);
        }

        Order originalOrder = order.snapshot();
        order.updateFromRequest(updateOrderRq);

        if (!losesPriority && updateOrderRq.getStopPrice() == 0) {
            handleNoPriorityLoss(order);
            return MatchResult.executed(null, List.of());
        } else {
            order.markAsUpdating();
        }

        if (updateOrderRq.getStopPrice() > 0) {
            orderBook.removeByOrderIdFromInactiveQueue(updateOrderRq.getSide(), updateOrderRq.getOrderId());
            if (!order.canBeActive(lastTradedPrice))
                return handleInactiveOrderInQueue(order, updateOrderRq);
        } else {
            orderBook.removeByOrderIdFromActiveQueue(updateOrderRq.getSide(), updateOrderRq.getOrderId());
        }

        return executeMatching(continuousMatcher, order, originalOrder, updateOrderRq);
    }

    private boolean hasEnoughCreditToUpdate(Order order,EnterOrderRq updateOrderRq){
        long newValue = (long) updateOrderRq.getQuantity() *updateOrderRq.getPrice();
        long prevValue = order.getValue();
        return order.getBroker().hasEnoughCredit(newValue - prevValue);
    }
    private MatchResult updateOrderInAuctionState(EnterOrderRq updateOrderRq) throws InvalidRequestException{
        Order order = getOrderForUpdate(updateOrderRq);
        validateOrderForUpdate(order, updateOrderRq);
        if (updateOrderRq.getSide() == Side.SELL) {
            if (!hasEnoughPositionsToUpdate(order, updateOrderRq)) {
                return MatchResult.notEnoughPositions();
            }
        }
        else{
            if(!hasEnoughCreditToUpdate(order, updateOrderRq)){
                return MatchResult.notEnoughCredit();
            }
        }

        if (updateOrderRq.getSide() == Side.BUY) {
            increaseBrokerCredit(order);
        }
        orderBook.removeByOrderIdFromActiveQueue(order.getSide(),order.getOrderId());
        order.updateFromRequest(updateOrderRq);
        if (updateOrderRq.getSide() == Side.BUY){
            decreaseBrokerCredit(order);
        }
        orderBook.enqueueToActiveQueue(order);
        return MatchResult.openingPriceAnnouncement();
    }

    public MatchResult updateOrder(EnterOrderRq updateOrderRq, ContinuousMatcher continuousMatcher) throws InvalidRequestException {

        if(matchingState==MatchingState.CONTINUOUS)
            return updateOrderInContinuousState(updateOrderRq, continuousMatcher);
        else
            return updateOrderInAuctionState(updateOrderRq);
    }

    public int calculateTradableQuantity(int openingPrice){
        return orderBook.calculateTradableQuantity(openingPrice);
    }
    public int calculateOpeningPrice(){
        int openingPrice = orderBook.calculateOpeningPrice(lastTradedPrice);
        if(orderBook.calculateTradableQuantity(openingPrice)==0){
            return 0;
        }
        return openingPrice;
    }

    public MatchResult changeMatchingState(MatchingState newState, AuctionMatcher matcher){
        MatchResult matchResult = null;
        if(this.matchingState == MatchingState.AUCTION){
            matchResult = matcher.execute(this);
        }
        this.matchingState = newState;
        return matchResult;
    }


}