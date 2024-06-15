package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.exception.InvalidRequestException;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.TradeDTO;
import ir.ramtung.tinyme.messaging.event.*;
import ir.ramtung.tinyme.messaging.request.*;
import ir.ramtung.tinyme.repository.BrokerRepository;
import ir.ramtung.tinyme.repository.SecurityRepository;
import ir.ramtung.tinyme.repository.ShareholderRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class OrderHandler {
    SecurityRepository securityRepository;
    BrokerRepository brokerRepository;
    ShareholderRepository shareholderRepository;
    EventPublisher eventPublisher;
    ContinuousMatcher continuousMatcher;
    AuctionMatcher auctionMatcher;

    public OrderHandler(SecurityRepository securityRepository, BrokerRepository brokerRepository, ShareholderRepository shareholderRepository, EventPublisher eventPublisher, ContinuousMatcher continuousMatcher, AuctionMatcher auctionMatcher) {
        this.securityRepository = securityRepository;
        this.brokerRepository = brokerRepository;
        this.shareholderRepository = shareholderRepository;
        this.eventPublisher = eventPublisher;
        this.continuousMatcher = continuousMatcher;
        this.auctionMatcher = auctionMatcher;
    }

    private void publishOpeningPrice(String securityIsin){
        int openingPrice = securityRepository.findSecurityByIsin(securityIsin).calculateOpeningPrice();
        int tradableQuantity = securityRepository.findSecurityByIsin(securityIsin).calculateTradableQuantity(openingPrice);
        eventPublisher.publish(new OpeningPriceEvent(LocalDateTime.now(),securityIsin,openingPrice,tradableQuantity));
    }

    private void publishEvent(MatchResult matchResult, EnterOrderRq enterOrderRq){
        if (matchResult.outcome() == MatchingOutcome.NOT_ENOUGH_CREDIT) {
            eventPublisher.publish(new OrderRejectedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId(), List.of(Message.BUYER_HAS_NOT_ENOUGH_CREDIT)));
            return;
        }
        if (matchResult.outcome() == MatchingOutcome.NOT_ENOUGH_POSITIONS) {
            eventPublisher.publish(new OrderRejectedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId(), List.of(Message.SELLER_HAS_NOT_ENOUGH_POSITIONS)));
            return;
        }
        if (matchResult.outcome() == MatchingOutcome.MINIMUM_QUANTITY_INSUFFICIENT) {
            eventPublisher.publish(new OrderRejectedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId(), List.of(Message.MINIMUM_QUANTITY_INSUFFICIENT)));
            return;
        }
        if (enterOrderRq.getRequestType() == OrderEntryType.NEW_ORDER)
            eventPublisher.publish(new OrderAcceptedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId()));
        else
            eventPublisher.publish(new OrderUpdatedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId()));
        if(matchResult.outcome() != MatchingOutcome.INACTIVE_ORDER_ENQUEUED && enterOrderRq.getStopPrice()>0){
            eventPublisher.publish(new OrderActivatedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId()));
        }
        if(matchResult.outcome()==MatchingOutcome.OPENING_PRICE_ANNOUNCEMENT)
            publishOpeningPrice(enterOrderRq.getSecurityIsin());
        if (!matchResult.trades().isEmpty()) {
            eventPublisher.publish(new OrderExecutedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId(), matchResult.trades().stream().map(TradeDTO::new).collect(Collectors.toList())));
        }
    }

    private void publishEventForActivatedOrder(MatchResult matchResult, Order order){
        if(matchResult.outcome() != MatchingOutcome.INACTIVE_ORDER_ENQUEUED && order.getStopPrice()>0){
            eventPublisher.publish(new OrderActivatedEvent(((StopLimitOrder)order).getRequestId(), order.getOrderId()));
        }
        if (!matchResult.trades().isEmpty()) {
            eventPublisher.publish(new OrderExecutedEvent(((StopLimitOrder)order).getRequestId(), order.getOrderId(), matchResult.trades().stream().map(TradeDTO::new).collect(Collectors.toList())));
        }
    }

    private void publishEventForChangeState(MatchResult matchResult,ChangeMatchingStateRq changeMatchingStateRq){
        eventPublisher.publish(new SecurityStateChangedEvent(LocalDateTime.now(),changeMatchingStateRq.getSecurityIsin(),changeMatchingStateRq.getTargetState()));

        if (matchResult != null){
            for(Trade trade: matchResult.trades()){
                eventPublisher.publish(new TradeEvent(LocalDateTime.now(),changeMatchingStateRq.getSecurityIsin(),trade.getPrice(), trade.getQuantity(), trade.getBuy().getOrderId(),trade.getSell().getOrderId()));
            }
        }
    }
    private void executePossibleInactiveOrders(Security security,Side side) {
        while (true) {
            Order orderToBeExecute = security.getOrderBook().dequeueFromInactiveQueue(side, security.getLastTradedPrice());
            if (orderToBeExecute == null)
                break;
            if (orderToBeExecute.getSide() == Side.BUY) {
                orderToBeExecute.getBroker().increaseCreditBy(orderToBeExecute.getValue());
            }
            MatchResult matchResult = continuousMatcher.execute(orderToBeExecute);
            publishEventForActivatedOrder(matchResult, orderToBeExecute);
        }
    }

    private void activatePossibleInactiveOrders(Security security){
        while(true){
            Order sellOrderToBeExecute = security.getOrderBook().dequeueFromInactiveQueue(Side.SELL, security.getLastTradedPrice());
            Order buyOrderToBeExecute = security.getOrderBook().dequeueFromInactiveQueue(Side.BUY, security.getLastTradedPrice());
            if(sellOrderToBeExecute==null && buyOrderToBeExecute==null)
                break;
            if(sellOrderToBeExecute!=null){
                security.getOrderBook().enqueueToActiveQueue(sellOrderToBeExecute);
                eventPublisher.publish(new OrderActivatedEvent(((StopLimitOrder)sellOrderToBeExecute).getRequestId(), sellOrderToBeExecute.getOrderId()));
            }
            if (buyOrderToBeExecute!=null){
                security.getOrderBook().enqueueToActiveQueue(buyOrderToBeExecute);
                eventPublisher.publish(new OrderActivatedEvent(((StopLimitOrder)buyOrderToBeExecute).getRequestId(), buyOrderToBeExecute.getOrderId()));
            }
        }
    }

    public void handleChangeMatchingStateRq(ChangeMatchingStateRq changeMatchingStateRq){
        Security security = securityRepository.findSecurityByIsin(changeMatchingStateRq.getSecurityIsin());
        MatchResult matchResult = security.changeMatchingState(changeMatchingStateRq.getTargetState(), auctionMatcher);
        publishEventForChangeState(matchResult,changeMatchingStateRq);

        if(security.getMatchingState()==MatchingState.CONTINUOUS){
            executePossibleInactiveOrders(security, Side.BUY);
            executePossibleInactiveOrders(security, Side.SELL);
        }
        else
            activatePossibleInactiveOrders(security);
    }
    public void handleEnterOrder(EnterOrderRq enterOrderRq) {
        try {
            validateEnterOrderRq(enterOrderRq);

            Security security = securityRepository.findSecurityByIsin(enterOrderRq.getSecurityIsin());
            Broker broker = brokerRepository.findBrokerById(enterOrderRq.getBrokerId());
            Shareholder shareholder = shareholderRepository.findShareholderById(enterOrderRq.getShareholderId());

            MatchResult matchResult;

            if (enterOrderRq.getRequestType() == OrderEntryType.NEW_ORDER)
                matchResult = security.newOrder(enterOrderRq, broker, shareholder, continuousMatcher);
            else
                matchResult = security.updateOrder(enterOrderRq, continuousMatcher);

            publishEvent(matchResult, enterOrderRq);

            if(securityRepository.findSecurityByIsin(enterOrderRq.getSecurityIsin()).getMatchingState().equals(MatchingState.CONTINUOUS)) {
                executePossibleInactiveOrders(security,enterOrderRq.getSide());
            }

        }
        catch (InvalidRequestException ex) {
            eventPublisher.publish(new OrderRejectedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId(), ex.getReasons()));
        }
    }

    public void handleDeleteOrder(DeleteOrderRq deleteOrderRq) {
        try {
            validateDeleteOrderRq(deleteOrderRq);
            Security security = securityRepository.findSecurityByIsin(deleteOrderRq.getSecurityIsin());
            security.deleteOrder(deleteOrderRq);
            eventPublisher.publish(new OrderDeletedEvent(deleteOrderRq.getRequestId(), deleteOrderRq.getOrderId()));
            if(security.getMatchingState()==MatchingState.AUCTION)
                publishOpeningPrice(deleteOrderRq.getSecurityIsin());
        } catch (InvalidRequestException ex) {
            eventPublisher.publish(new OrderRejectedEvent(deleteOrderRq.getRequestId(), deleteOrderRq.getOrderId(), ex.getReasons()));
        }
    }

    private void validateEnterOrderRq(EnterOrderRq enterOrderRq) throws InvalidRequestException {
        List<String> errors = new LinkedList<>();
        if (enterOrderRq.getOrderId() <= 0)
            errors.add(Message.INVALID_ORDER_ID);
        if (enterOrderRq.getQuantity() <= 0)
            errors.add(Message.ORDER_QUANTITY_NOT_POSITIVE);
        if (enterOrderRq.getPrice() <= 0)
            errors.add(Message.ORDER_PRICE_NOT_POSITIVE);
        Security security = securityRepository.findSecurityByIsin(enterOrderRq.getSecurityIsin());
        if (security == null)
            errors.add(Message.UNKNOWN_SECURITY_ISIN);
        else {
            if (enterOrderRq.getQuantity() % security.getLotSize() != 0)
                errors.add(Message.QUANTITY_NOT_MULTIPLE_OF_LOT_SIZE);
            if (enterOrderRq.getPrice() % security.getTickSize() != 0)
                errors.add(Message.PRICE_NOT_MULTIPLE_OF_TICK_SIZE);
        }
        if (brokerRepository.findBrokerById(enterOrderRq.getBrokerId()) == null)
            errors.add(Message.UNKNOWN_BROKER_ID);
        if (shareholderRepository.findShareholderById(enterOrderRq.getShareholderId()) == null)
            errors.add(Message.UNKNOWN_SHAREHOLDER_ID);
        if (enterOrderRq.getPeakSize() < 0 || enterOrderRq.getPeakSize() >= enterOrderRq.getQuantity())
            errors.add(Message.INVALID_PEAK_SIZE);
        if(enterOrderRq.getMinimumExecutionQuantity()<0 || enterOrderRq.getMinimumExecutionQuantity()>enterOrderRq.getQuantity())
            errors.add(Message.INVALID_MINIMUM_QUANTITY);
        if(enterOrderRq.getStopPrice()<0)
            errors.add(Message.INVALID_STOP_PRICE);
        if(enterOrderRq.getStopPrice()>0 && enterOrderRq.getMinimumExecutionQuantity()>0)
            errors.add(Message.STOP_LIMIT_ORDER_CAN_NOT_HAVE_MIN_EXEC_QUANTITY);
        if(enterOrderRq.getStopPrice()>0 && enterOrderRq.getPeakSize()>0)
            errors.add(Message.AN_ORDER_CAN_NOT_BE_BOTH_ICEBERG_AND_STOP_LIMIT);
        if(security!=null && security.getMatchingState().equals(MatchingState.AUCTION)){
            if(enterOrderRq.getMinimumExecutionQuantity()>0 && enterOrderRq.getRequestType()==OrderEntryType.NEW_ORDER)
                errors.add(Message.CANT_HAVE_MINIMUM_EXEC_QUANTITY_IN_AUCTION_STATE);
        }
        if (!errors.isEmpty())
            throw new InvalidRequestException(errors);
    }

    private void validateDeleteOrderRq(DeleteOrderRq deleteOrderRq) throws InvalidRequestException {
        List<String> errors = new LinkedList<>();
        if (deleteOrderRq.getOrderId() <= 0)
            errors.add(Message.INVALID_ORDER_ID);
        if (securityRepository.findSecurityByIsin(deleteOrderRq.getSecurityIsin()) == null)
            errors.add(Message.UNKNOWN_SECURITY_ISIN);
        if (!errors.isEmpty())
            throw new InvalidRequestException(errors);
    }

}