package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.*;
import org.springframework.stereotype.Service;

import java.util.LinkedList;
import java.util.ListIterator;

@Service
public class ContinuousMatcher {
    public MatchResult match(Order newOrder)  {
        OrderBook orderBook = newOrder.getSecurity().getOrderBook();
        LinkedList<Trade> trades = new LinkedList<>();
        int saveInitNewOrderQuantity = newOrder.getQuantity();
        while (orderBook.hasOrderOfType(newOrder.getSide().opposite()) && newOrder.getQuantity() > 0) {
            Order matchingOrder = orderBook.matchWithFirst(newOrder);
            if (matchingOrder == null)
                break;

            Trade trade = new Trade(newOrder.getSecurity(), matchingOrder.getPrice(), Math.min(newOrder.getQuantity(), matchingOrder.getQuantity()), newOrder, matchingOrder);
            if (newOrder.getSide() == Side.BUY) {
                if (trade.buyerHasEnoughCredit())
                    trade.decreaseBuyersCredit();
                else {
                    rollbackTrades(newOrder, trades);
                    return MatchResult.notEnoughCredit();
                }
            }
            trade.increaseSellersCredit();
            trades.add(trade);

            if (newOrder.getQuantity() >= matchingOrder.getQuantity()) {
                newOrder.decreaseQuantity(matchingOrder.getQuantity());
                orderBook.removeFirst(matchingOrder.getSide());
                if (matchingOrder instanceof IcebergOrder icebergOrder) {
                    icebergOrder.decreaseQuantity(matchingOrder.getQuantity());
                    icebergOrder.replenish();
                    if (icebergOrder.getQuantity() > 0)
                        orderBook.enqueueToActiveQueue(icebergOrder);
                }
            } else {
                matchingOrder.decreaseQuantity(newOrder.getQuantity());
                newOrder.makeQuantityZero();
            }
        }
        if((saveInitNewOrderQuantity - newOrder.getQuantity() < newOrder.getMinimumExpectedQuantity()) && newOrder.getStatus()!=OrderStatus.UPDATING){
            rollbackTrades(newOrder, trades);
            return MatchResult.minimumQuantityInsufficient();
        }
        return MatchResult.executed(newOrder, trades);
    }

    private void rollbackTradesForBuyOrder(Order newOrder, LinkedList<Trade> trades) {
        newOrder.getBroker().increaseCreditBy(trades.stream().mapToLong(Trade::getTradedValue).sum());
        trades.forEach(trade -> trade.getSell().getBroker().decreaseCreditBy(trade.getTradedValue()));

        ListIterator<Trade> it = trades.listIterator(trades.size());
        while (it.hasPrevious()) {
            newOrder.getSecurity().getOrderBook().restoreOrder(it.previous().getSell());
        }
    }
    private void rollbackTradesForSellOrder(Order newOrder, LinkedList<Trade> trades) {
        newOrder.getBroker().decreaseCreditBy(trades.stream().mapToLong(Trade::getTradedValue).sum());
        trades.forEach(trade -> trade.getBuy().getBroker().increaseCreditBy(trade.getTradedValue()));

        ListIterator<Trade> it = trades.listIterator(trades.size());
        while (it.hasPrevious()) {
            newOrder.getSecurity().getOrderBook().restoreOrder(it.previous().getBuy());
        }
    }
    private void rollbackTrades(Order newOrder, LinkedList<Trade> trades) {
        if(newOrder.getSide()==Side.SELL){
            rollbackTradesForSellOrder(newOrder, trades);
        }
        else if(newOrder.getSide()==Side.BUY){
            rollbackTradesForBuyOrder(newOrder, trades);
        }
    }

    public MatchResult execute(Order order) {
        MatchResult result = match(order);
        if (result.outcome() == MatchingOutcome.NOT_ENOUGH_CREDIT || result.outcome() == MatchingOutcome.MINIMUM_QUANTITY_INSUFFICIENT)
            return result;

        if (result.remainder().getQuantity() > 0) {
            if (order.getSide() == Side.BUY) {
                if (!order.getBroker().hasEnoughCredit(order.getValue())) {
                    rollbackTrades(order, result.trades());
                    return MatchResult.notEnoughCredit();
                }
                order.getBroker().decreaseCreditBy(order.getValue());
            }
            order.getSecurity().getOrderBook().enqueueToActiveQueue(result.remainder());
        }

        if(!result.trades().isEmpty()){
            order.getSecurity().setLastTradedPrice(result.trades().getLast().getPrice());
        }

        for (Trade trade : result.trades()) {
            trade.getBuy().getShareholder().incPosition(trade.getSecurity(), trade.getQuantity());
            trade.getSell().getShareholder().decPosition(trade.getSecurity(), trade.getQuantity());
        }
        return result;
    }

}