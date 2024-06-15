package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.*;
import org.springframework.stereotype.Service;

import java.util.LinkedList;

@Service
public class AuctionMatcher{
    public LinkedList<Trade> match(Order newOrder, int openingPrice)  {
        OrderBook orderBook = newOrder.getSecurity().getOrderBook();
        LinkedList<Trade> trades = new LinkedList<>();
        while (!orderBook.getSellQueue().isEmpty() && newOrder.getQuantity() > 0) {
            Order matchingOrder = orderBook.getSellQueue().getFirst();
            if (matchingOrder.getPrice()>openingPrice)
                break;

            Trade trade = new Trade(newOrder.getSecurity(), openingPrice, Math.min(newOrder.getQuantity(), matchingOrder.getQuantity()), newOrder, matchingOrder);
            if (newOrder.getSide() == Side.BUY)
                newOrder.getBroker().increaseCreditBy((long) trade.getQuantity() * (newOrder.getPrice() - openingPrice));

            trade.increaseSellersCredit();
            trades.add(trade);

            if (newOrder.getQuantity() >= matchingOrder.getQuantity()) {
                newOrder.decreaseQuantity(matchingOrder.getQuantity());
                orderBook.removeFirst(Side.SELL);
                if (matchingOrder instanceof IcebergOrder icebergOrder) {
                    icebergOrder.decreaseQuantity(matchingOrder.getQuantity());
                    icebergOrder.replenish();
                    if (icebergOrder.getQuantity() > 0)
                        orderBook.enqueueToActiveQueue(icebergOrder);
                }
            }
            else {
                matchingOrder.decreaseQuantity(newOrder.getQuantity());
                orderBook.removeFirst(Side.BUY);
                if (newOrder instanceof IcebergOrder icebergOrder) {
                    icebergOrder.decreaseQuantity(newOrder.getQuantity());
                    icebergOrder.replenish();
                    if (icebergOrder.getQuantity() > 0)
                        orderBook.enqueueToActiveQueue(icebergOrder);
                }
                else
                    newOrder.makeQuantityZero();
            }
        }
        return trades;
    }

    public LinkedList<Trade> executeForOneOrder(Order order, int openingPrice) {
        LinkedList<Trade> trades = match(order, openingPrice);
        if(!trades.isEmpty()){
            order.getSecurity().setLastTradedPrice(trades.getLast().getPrice());
        }
        for (Trade trade : trades) {
            trade.getBuy().getShareholder().incPosition(trade.getSecurity(), trade.getQuantity());
            trade.getSell().getShareholder().decPosition(trade.getSecurity(), trade.getQuantity());
        }
        return trades;
    }
    public MatchResult execute(Security security) {
        LinkedList<Trade> allTrades = new LinkedList<>();
        int openingPrice = security.calculateOpeningPrice();
        while(!security.getOrderBook().getBuyQueue().isEmpty()){
            Order buyOrder = security.getOrderBook().getBuyQueue().getFirst();
            if(buyOrder.getPrice()>=openingPrice){
                LinkedList<Trade> trades = executeForOneOrder(buyOrder, openingPrice);
                if(trades.isEmpty())
                    break;
                else
                    allTrades.addAll(trades);
            }
            else
                break;
        }
        return MatchResult.auctionMatchCompleted(allTrades);
    }
}
