package ir.ramtung.tinyme.domain;

import ir.ramtung.tinyme.config.MockedJMSTestConfig;
import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.domain.service.OrderHandler;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.event.*;
import ir.ramtung.tinyme.messaging.request.ChangeMatchingStateRq;
import ir.ramtung.tinyme.messaging.request.DeleteOrderRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.messaging.request.MatchingState;
import ir.ramtung.tinyme.repository.BrokerRepository;
import ir.ramtung.tinyme.repository.SecurityRepository;
import ir.ramtung.tinyme.repository.ShareholderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static ir.ramtung.tinyme.domain.entity.Side.BUY;
import static ir.ramtung.tinyme.domain.entity.Side.SELL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@SpringBootTest
@Import(MockedJMSTestConfig.class)

public class MatchingStateTest {

    @Autowired
    OrderHandler orderHandler;
    @Autowired
    EventPublisher eventPublisher;
    @Autowired
    SecurityRepository securityRepository;
    @Autowired
    BrokerRepository brokerRepository;
    @Autowired
    ShareholderRepository shareholderRepository;
    //    @Autowired
//    AuctionMatcher auctionMatcher;
    private Security security;
    private Security security2;

    private Broker broker;
    private Broker broker2;
    private Shareholder shareholder;
    private List<Order> orders;

    @BeforeEach
    void setupOrderBook() {
        securityRepository.clear();
        brokerRepository.clear();
        shareholderRepository.clear();
        security = Security.builder().isin("ABC").build();
        securityRepository.addSecurity(security);
        security2 = Security.builder().isin("AB").build();
        securityRepository.addSecurity(security2);
        broker = Broker.builder().credit(1_000_000L).brokerId(1).build();
        broker2 = Broker.builder().credit(1_000_000L).brokerId(2).build();
        brokerRepository.addBroker(broker);
        brokerRepository.addBroker(broker2);
        shareholder = Shareholder.builder().build();
        shareholder.incPosition(security, 100_000);
        shareholder.incPosition(security2, 100_000);
        shareholderRepository.addShareholder(shareholder);

        orders = Arrays.asList(
                new Order(1, security, BUY, 304, 15700, broker, shareholder),
                new Order(2, security, BUY, 43, 15500, broker, shareholder),
                new Order(3, security, BUY, 445, 15450, broker, shareholder),
                new Order(4, security, BUY, 526, 15450, broker, shareholder),
                new Order(5, security, BUY, 1000, 15400, broker, shareholder),
                new Order(6, security, Side.SELL, 350, 15800, broker, shareholder),
                new Order(7, security, Side.SELL, 285, 15810, broker, shareholder),
                new Order(8, security, Side.SELL, 800, 15810, broker, shareholder),
                new Order(9, security, Side.SELL, 340, 15820, broker, shareholder),
                new Order(10, security, Side.SELL, 65, 15820, broker, shareholder)
        );
        orders.forEach(order -> security.getOrderBook().enqueueToActiveQueue(order));
    }

    @Test
    void cant_create_stop_limit_order_in_auction() {
        orderHandler.handleChangeMatchingStateRq(new ChangeMatchingStateRq(security.getIsin(),MatchingState.AUCTION));

        orderHandler.handleEnterOrder( EnterOrderRq.createNewOrderRq(1,"ABC",11,
                LocalDateTime.now(), BUY,1,1, broker.getBrokerId(), shareholder.getShareholderId(),0,0,100));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 11, List.of(Message.CANT_ADD_NEW_STOP_LIMIT_ORDER_IN_AUCTION_STATE)));

    }

    @Test
    void cant_create_minimum_execution_order_in_auction() {
        orderHandler.handleChangeMatchingStateRq(new ChangeMatchingStateRq(security.getIsin(),MatchingState.AUCTION));
        orderHandler.handleEnterOrder( EnterOrderRq.createNewOrderRq(1,security.getIsin(),11,
                LocalDateTime.now(), BUY,100,100, broker.getBrokerId(), shareholder.getShareholderId(),0,10,0));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 11, List.of(Message.CANT_HAVE_MINIMUM_EXEC_QUANTITY_IN_AUCTION_STATE)));
    }

    @Test
    void new_sell_order_in_auction_state_with_not_enough_positions(){
        orderHandler.handleChangeMatchingStateRq(new ChangeMatchingStateRq(security.getIsin(),MatchingState.AUCTION));
        orderHandler.handleEnterOrder( EnterOrderRq.createNewOrderRq(1,security.getIsin(),11,
                LocalDateTime.now(), SELL,100_001,100, broker.getBrokerId(), shareholder.getShareholderId(),0));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 11, List.of(Message.SELLER_HAS_NOT_ENOUGH_POSITIONS)));
    }

    @Test
    void new_buy_order_in_auction_state_with_not_enough_credit(){
        orderHandler.handleChangeMatchingStateRq(new ChangeMatchingStateRq(security.getIsin(),MatchingState.AUCTION));
        orderHandler.handleEnterOrder( EnterOrderRq.createNewOrderRq(1,security.getIsin(),11,
                LocalDateTime.now(), BUY,1000,1001, broker.getBrokerId(), shareholder.getShareholderId(),0));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 11, List.of(Message.BUYER_HAS_NOT_ENOUGH_CREDIT)));
    }


    @Test
    void check_buyer_credit_on_order_acceptance(){
        orderHandler.handleChangeMatchingStateRq(new ChangeMatchingStateRq(security.getIsin(),MatchingState.AUCTION));
        orderHandler.handleEnterOrder( EnterOrderRq.createNewOrderRq(1,security.getIsin(),11,
                LocalDateTime.now(), BUY,1000,999, broker.getBrokerId(), shareholder.getShareholderId(),0));
        assertThat(broker.getCredit()).isEqualTo(1000);
    }


    @Test
    void get_correct_opening_price() {
        orders = Arrays.asList(
                new Order(1, security2, BUY, 304, 15700, broker, shareholder),
                new Order(2, security2, BUY, 600, 15500, broker, shareholder),
                new Order(3, security2, Side.SELL, 350, 15300, broker2, shareholder),
                new Order(4, security2, Side.SELL, 350, 15460, broker2, shareholder)

        );
        orders.forEach(order -> security2.getOrderBook().enqueueToActiveQueue(order));

        broker.increaseCreditBy(10_000_000);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(0,"AB",12, LocalDateTime.now(), SELL,4,15700,broker2.getBrokerId(),shareholder.getShareholderId(),0,0,0));
        assertThat(security2.getLastTradedPrice()).isEqualTo(15700);

        orderHandler.handleChangeMatchingStateRq(new ChangeMatchingStateRq(security2.getIsin(),MatchingState.AUCTION));

        verify(eventPublisher, times(1)).publish(argThat(event ->
                event instanceof SecurityStateChangedEvent &&
                        "AB".equals(((SecurityStateChangedEvent) event).getSecurityIsin()) &&
                        MatchingState.AUCTION.equals(((SecurityStateChangedEvent) event).getState())
        ));


        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1,"AB",13, LocalDateTime.now(), SELL,100,15400,broker2.getBrokerId(),shareholder.getShareholderId(),0,0,0));

        verify(eventPublisher, times(1)).publish(argThat(event ->
                event instanceof OpeningPriceEvent &&
                        "AB".equals(((OpeningPriceEvent) event).getSecurityIsin()) &&
                        15500 == ((OpeningPriceEvent) event).getOpeningPrice() &&
                        800 == ((OpeningPriceEvent) event).getTradableQuantity()
        ));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2,"AB",5, LocalDateTime.now(),BUY,600,15600,broker.getBrokerId(),shareholder.getShareholderId(),0,0,0));

        verify(eventPublisher, times(1)).publish(argThat(event ->
                event instanceof OpeningPriceEvent &&
                        "AB".equals(((OpeningPriceEvent) event).getSecurityIsin()) &&
                        15600 == ((OpeningPriceEvent) event).getOpeningPrice() &&
                        800 == ((OpeningPriceEvent) event).getTradableQuantity()
        ));
    }


    @Test
    void update_stop_price_order_in_auction_mode() {
        orders = Arrays.asList(
                new Order(1, security2, BUY, 304, 15700, broker, shareholder),
                new Order(2, security2, BUY, 600, 15500, broker, shareholder),
                new Order(3, security2, Side.SELL, 350, 15300, broker2, shareholder),
                new Order(4, security2, Side.SELL, 350, 15460, broker2, shareholder)
        );
        orders.forEach(order -> security2.getOrderBook().enqueueToActiveQueue(order));

        broker.increaseCreditBy(10_000_000);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(0,"AB",12, LocalDateTime.now(), SELL,4,15700,broker2.getBrokerId(),shareholder.getShareholderId(),0,0,0));
        assertThat(security2.getLastTradedPrice()).isEqualTo(15700);

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(5,security2.getIsin(),5, LocalDateTime.now(), SELL,350,16000,broker2.getBrokerId(),shareholder.getShareholderId(),0,0,15650));

        orderHandler.handleChangeMatchingStateRq(new ChangeMatchingStateRq(security2.getIsin(),MatchingState.AUCTION));

        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(5,security2.getIsin(),5, LocalDateTime.now(), SELL,350,16000,broker2.getBrokerId(),shareholder.getShareholderId(),0,0,15700));

        verify(eventPublisher).publish(new OrderRejectedEvent(5, 5, List.of(Message.CANT_UPDATE_STOP_LIMIT_ORDER_IN_AUCTION_STATE)));
    }

    @Test
    void update_min_exec_quantity_in_auction_state(){
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(0,security.getIsin(),11, LocalDateTime.now(), SELL,504,15700,broker2.getBrokerId(),shareholder.getShareholderId(),0,304,0));
        verify(eventPublisher).publish(new OrderAcceptedEvent(0, 11));
        verify(eventPublisher).publish(any(OrderExecutedEvent.class));
        orderHandler.handleChangeMatchingStateRq(new ChangeMatchingStateRq(security.getIsin(),MatchingState.AUCTION));
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1,security.getIsin(),11, LocalDateTime.now(), SELL,200,15700,broker2.getBrokerId(),shareholder.getShareholderId(),0,100,0));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 11, List.of(Message.CANT_UPDATE_MIN_QUANTITY)));
    }

    @Test
    void update_order_which_seller_has_not_enough_positions(){
        orderHandler.handleChangeMatchingStateRq(new ChangeMatchingStateRq(security.getIsin(),MatchingState.AUCTION));
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(0,security.getIsin(),6, LocalDateTime.now(), SELL,999_700,15700,broker.getBrokerId(),shareholder.getShareholderId(), 0, 0, 0));
        verify(eventPublisher).publish(new OrderRejectedEvent(0, 6, List.of(Message.SELLER_HAS_NOT_ENOUGH_POSITIONS)));
    }

    @Test
    void update_order_which_buyer_has_not_enough_credit(){
        orderHandler.handleChangeMatchingStateRq(new ChangeMatchingStateRq(security.getIsin(),MatchingState.AUCTION));
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(0,security.getIsin(),1, LocalDateTime.now(), BUY,368,15700,broker.getBrokerId(),shareholder.getShareholderId(), 0, 0, 0));
        verify(eventPublisher).publish(new OrderRejectedEvent(0, 1, List.of(Message.BUYER_HAS_NOT_ENOUGH_CREDIT)));
    }

    @Test
    void  update_buy_order_and_check_place_in_queue_and_opening_price(){
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(0,security.getIsin(),12, LocalDateTime.now(), SELL,4,15700,broker2.getBrokerId(),shareholder.getShareholderId(),0,0,0));
        assertThat(security.getLastTradedPrice()).isEqualTo(15700);
        orderHandler.handleChangeMatchingStateRq(new ChangeMatchingStateRq(security.getIsin(),MatchingState.AUCTION));
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(0,security.getIsin(),5, LocalDateTime.now(), BUY,1000,15800,broker.getBrokerId(),shareholder.getShareholderId(), 0, 0, 0));
        assertThat(security.getOrderBook().getBuyQueue().getFirst().getOrderId()).isEqualTo(5);
        verify(eventPublisher, times(1)).publish(argThat(event ->
                event instanceof OpeningPriceEvent &&
                        security.getIsin().equals(((OpeningPriceEvent) event).getSecurityIsin()) &&
                        15800==(((OpeningPriceEvent) event).getOpeningPrice()) &&
                        350==(((OpeningPriceEvent) event).getTradableQuantity())
        ));
        verify(eventPublisher, never()).publish(any(TradeEvent.class));
    }

    @Test
    void update_sell_order_check_place_in_queue_and_opening_price(){
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(0,security.getIsin(),12, LocalDateTime.now(), SELL,4,15700,broker2.getBrokerId(),shareholder.getShareholderId(),0,0,0));
        orderHandler.handleChangeMatchingStateRq(new ChangeMatchingStateRq(security.getIsin(),MatchingState.AUCTION));
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1,security.getIsin(),6, LocalDateTime.now(), SELL,400,15500,broker.getBrokerId(),shareholder.getShareholderId(), 0, 0, 0));
        verify(eventPublisher, times(1)).publish(argThat(event ->
                event instanceof OpeningPriceEvent &&
                        security.getIsin().equals(((OpeningPriceEvent) event).getSecurityIsin()) &&
                        15500==(((OpeningPriceEvent) event).getOpeningPrice()) &&
                        343==(((OpeningPriceEvent) event).getTradableQuantity())
        ));
        verify(eventPublisher, never()).publish(any(TradeEvent.class));
        assertThat(security.getOrderBook().getBuyQueue().size()).isEqualTo(5);
        assertThat(security.getOrderBook().getSellQueue().size()).isEqualTo(5);
        assertThat(security.getOrderBook().getSellQueue().getFirst().getOrderId()).isEqualTo(6);
    }
    @Test
    void changing_state_from_auction_to_continues_without_trade_possible() {
        orders = Arrays.asList(
                new Order(1, security2, BUY, 304, 15100, broker, shareholder),
                new Order(2, security2, BUY, 600, 15200, broker, shareholder),
                new Order(3, security2, Side.SELL, 350, 15300, broker2, shareholder),
                new Order(4, security2, Side.SELL, 350, 15460, broker2, shareholder)
        );
        orderHandler.handleChangeMatchingStateRq(new ChangeMatchingStateRq(security2.getIsin(),MatchingState.AUCTION));
        verify(eventPublisher, times(1)).publish(argThat(event ->
                event instanceof SecurityStateChangedEvent &&
                        "AB".equals(((SecurityStateChangedEvent) event).getSecurityIsin()) &&
                        MatchingState.AUCTION.equals(((SecurityStateChangedEvent) event).getState())
        ));

        orderHandler.handleChangeMatchingStateRq(new ChangeMatchingStateRq(security2.getIsin(),MatchingState.CONTINUOUS));

        verify(eventPublisher, times(1)).publish(argThat(event ->
                event instanceof SecurityStateChangedEvent &&
                        "AB".equals(((SecurityStateChangedEvent) event).getSecurityIsin()) &&
                        MatchingState.CONTINUOUS.equals(((SecurityStateChangedEvent) event).getState())
        ));

        verifyNoMoreInteractions(eventPublisher);

    }
    @Test
    void change_state_from_auction_to_auction_witch_cause_some_trades_but_no_stop_limit_order() {
        orders = Arrays.asList(
                new Order(1, security2, BUY, 304, 15700, broker, shareholder),
                new Order(2, security2, BUY, 600, 15500, broker, shareholder),
                new Order(3, security2, Side.SELL, 350, 15300, broker2, shareholder),
                new Order(4, security2, Side.SELL, 350, 15460, broker2, shareholder)

        );
        orders.forEach(order -> security2.getOrderBook().enqueueToActiveQueue(order));

        orders = Arrays.asList(
                new StopLimitOrder(11, security2, BUY, 200, 15200, broker, shareholder, 15760),
                new StopLimitOrder(12, security2, BUY, 300, 16200, broker, shareholder, 15710),
                new StopLimitOrder(13, security2, BUY, 1000, 15400, broker, shareholder,15720),
                new StopLimitOrder(14, security2, BUY, 700, 15800, broker, shareholder,15710),
                new StopLimitOrder(15, security2,SELL, 600, 15810, broker2, shareholder,15400),
                new StopLimitOrder(16, security2, SELL, 500, 15810, broker2, shareholder,15401)
        );
        orders.forEach(order -> security2.getOrderBook().enqueueToInactiveQueue(order));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(0,"AB",12, LocalDateTime.now(), SELL,4,15700,broker2.getBrokerId(),shareholder.getShareholderId(),0,0,0));
        assertThat(security2.getLastTradedPrice()).isEqualTo(15700);
        orderHandler.handleChangeMatchingStateRq(new ChangeMatchingStateRq(security2.getIsin(),MatchingState.AUCTION));
        orderHandler.handleChangeMatchingStateRq(new ChangeMatchingStateRq(security2.getIsin(),MatchingState.AUCTION));
        verify(eventPublisher, times(2)).publish(argThat(event ->
                event instanceof SecurityStateChangedEvent &&
                        "AB".equals(((SecurityStateChangedEvent) event).getSecurityIsin()) &&
                        MatchingState.AUCTION.equals(((SecurityStateChangedEvent) event).getState())
        ));

        verify(eventPublisher, times(1)).publish(argThat(event ->
                event instanceof TradeEvent &&
                        "AB".equals(((TradeEvent) event).getSecurityIsin()) &&
                        15500 == ((TradeEvent) event).getPrice() &&
                        300 == ((TradeEvent) event).getQuantity()&&
                        1==((TradeEvent) event).getBuyId()&&
                        3==((TradeEvent) event).getSellId()
        ));
        verify(eventPublisher, times(1)).publish(argThat(event ->
                event instanceof TradeEvent &&
                        "AB".equals(((TradeEvent) event).getSecurityIsin()) &&
                        15500 == ((TradeEvent) event).getPrice() &&
                        50 == ((TradeEvent) event).getQuantity()&&
                        2==((TradeEvent) event).getBuyId()&&
                        3==((TradeEvent) event).getSellId()
        ));

        verify(eventPublisher, times(1)).publish(argThat(event ->
                event instanceof TradeEvent &&
                        "AB".equals(((TradeEvent) event).getSecurityIsin()) &&
                        15500 == ((TradeEvent) event).getPrice() &&
                        350 == ((TradeEvent) event).getQuantity()&&
                        2==((TradeEvent) event).getBuyId()&&
                        4==((TradeEvent) event).getSellId()
        ));

        assertThat(broker.getCredit()).isEqualTo(1_000_000 + 300*200);
        assertThat(broker2.getCredit()).isEqualTo(1_000_000 + 700*15500 + 4*15700);

        verify(eventPublisher, never()).publish(any(OrderActivatedEvent.class));
    }

    @Test
    void change_state_from_auction_to_auction_which_cause_stop_limit_order_activation() {
        orders = Arrays.asList(
                new Order(1, security2, BUY, 304, 15700, broker, shareholder),
                new Order(2, security2, BUY, 600, 15500, broker, shareholder),
                new Order(3, security2, Side.SELL, 350, 15300, broker2, shareholder),
                new Order(4, security2, Side.SELL, 350, 15460, broker2, shareholder)
        );
        orders.forEach(order -> security2.getOrderBook().enqueueToActiveQueue(order));

        orders = Arrays.asList(
                new StopLimitOrder(11, security2, BUY, 200, 15200, broker2, shareholder, 15760, 1000),
                new StopLimitOrder(12, security2, BUY, 300, 16200, broker2, shareholder, 15710, 1001),
                new StopLimitOrder(13, security2, BUY, 1000, 15400, broker2, shareholder,15720, 1002),
                new StopLimitOrder(14, security2, BUY, 700, 15800, broker2, shareholder,15710, 1003),
                new StopLimitOrder(15, security2,SELL, 600, 15810, broker, shareholder,15510, 1004),
                new StopLimitOrder(16, security2, SELL, 500, 15810, broker, shareholder,15540, 1005)
        );
        orders.forEach(order -> security2.getOrderBook().enqueueToInactiveQueue(order));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(0,"AB",12, LocalDateTime.now(), SELL,4,15700,broker2.getBrokerId(),shareholder.getShareholderId(),0,0,0));
        assertThat(security2.getLastTradedPrice()).isEqualTo(15700);

        orderHandler.handleChangeMatchingStateRq(new ChangeMatchingStateRq(security2.getIsin(),MatchingState.AUCTION));
        orderHandler.handleChangeMatchingStateRq(new ChangeMatchingStateRq(security2.getIsin(),MatchingState.AUCTION));

        verify(eventPublisher).publish(new OrderActivatedEvent(1004, 15));
        verify(eventPublisher).publish(new OrderActivatedEvent(1005, 16));
        assertThat(security2.getOrderBook().getSellQueue().size()).isEqualTo(2);
    }

    @Test
    void changing_state_from_continues_to_auction() {
        orderHandler.handleChangeMatchingStateRq(new ChangeMatchingStateRq(security2.getIsin(),MatchingState.AUCTION));
        verify(eventPublisher, times(1)).publish(argThat(event ->
                event instanceof SecurityStateChangedEvent &&
                        "AB".equals(((SecurityStateChangedEvent) event).getSecurityIsin()) &&
                        MatchingState.AUCTION.equals(((SecurityStateChangedEvent) event).getState())
        ));
    }


    @Test
    void changing_state_from_continues_to_continues() {
        orderHandler.handleChangeMatchingStateRq(new ChangeMatchingStateRq(security2.getIsin(),MatchingState.CONTINUOUS));
        verify(eventPublisher, times(1)).publish(argThat(event ->
                event instanceof SecurityStateChangedEvent &&
                        "AB".equals(((SecurityStateChangedEvent) event).getSecurityIsin()) &&
                        MatchingState.CONTINUOUS.equals(((SecurityStateChangedEvent) event).getState())
        ));
    }

    @Test
    void change_state_from_auction_to_continuous_which_cause_stop_limit_order_activation_and_execution() {
        orders = Arrays.asList(
                new Order(1, security2, BUY, 304, 15700, broker, shareholder),
                new Order(2, security2, BUY, 600, 15500, broker, shareholder),
                new Order(3, security2, Side.SELL, 350, 15300, broker2, shareholder),
                new Order(4, security2, Side.SELL, 350, 15460, broker2, shareholder)
        );
        orders.forEach(order -> security2.getOrderBook().enqueueToActiveQueue(order));

        orders = Arrays.asList(
                new StopLimitOrder(11, security2, BUY, 200, 15200, broker, shareholder, 15760, 1000),
                new StopLimitOrder(12, security2, BUY, 300, 16200, broker, shareholder, 15710, 1001),
                new StopLimitOrder(13, security2, BUY, 1000, 15400, broker, shareholder,15720, 1002),
                new StopLimitOrder(14, security2, BUY, 700, 15800, broker, shareholder,15710, 1003),
                new StopLimitOrder(15, security2, SELL, 200, 15500, broker2, shareholder,15510, 1004),
                new StopLimitOrder(16, security2, SELL, 500, 15810, broker2, shareholder,15540, 1005)
        );
        orders.forEach(order -> security2.getOrderBook().enqueueToInactiveQueue(order));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(0,"AB",12, LocalDateTime.now(), SELL,4,15700,broker2.getBrokerId(),shareholder.getShareholderId(),0,0,0));
        assertThat(security2.getLastTradedPrice()).isEqualTo(15700);

        orderHandler.handleChangeMatchingStateRq(new ChangeMatchingStateRq(security2.getIsin(),MatchingState.AUCTION));
        orderHandler.handleChangeMatchingStateRq(new ChangeMatchingStateRq(security2.getIsin(),MatchingState.CONTINUOUS));

        verify(eventPublisher).publish(new OrderActivatedEvent(1004, 15));
        verify(eventPublisher).publish(new OrderActivatedEvent(1005, 16));
        verify(eventPublisher, times(1)).publish(argThat(event ->
                event instanceof OrderExecutedEvent &&
                        1004==((OrderExecutedEvent) event).getRequestId() &&
                        15==((OrderExecutedEvent) event).getOrderId()
        ));
        assertThat(security2.getOrderBook().getSellQueue().size()).isEqualTo(1);
        assertThat(broker2.getCredit()).isEqualTo(1_000_000 + 700*15500 + 4*15700 + 200*15500);
    }

    @Test
    void delete_order_for_a_stop_price(){
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(0,security.getIsin(),17, LocalDateTime.now(), SELL,4,15700,broker2.getBrokerId(),shareholder.getShareholderId(),0,0,0));
        verify(eventPublisher).publish(new OrderAcceptedEvent(0, 17));
        verify(eventPublisher, times(1)).publish(any(OrderExecutedEvent.class));
        orders = Arrays.asList(
                new StopLimitOrder(11, security, BUY, 200, 15200, broker, shareholder, 15760, 1000),
                new StopLimitOrder(12, security, BUY, 300, 16200, broker, shareholder, 15710, 1001),
                new StopLimitOrder(13, security, BUY, 1000, 15400, broker, shareholder,15720, 1002),
                new StopLimitOrder(14, security, BUY, 700, 15800, broker, shareholder,15710, 1003),
                new StopLimitOrder(15, security, SELL, 200, 15500, broker2, shareholder,15510, 1004),
                new StopLimitOrder(16, security, SELL, 500, 15810, broker2, shareholder,15540, 1005)
        );
        orders.forEach(order -> security.getOrderBook().enqueueToInactiveQueue(order));
        orderHandler.handleChangeMatchingStateRq(new ChangeMatchingStateRq(security.getIsin(),MatchingState.AUCTION));
        verify(eventPublisher, times(1)).publish(argThat(event ->
                event instanceof SecurityStateChangedEvent &&
                        security.getIsin().equals(((SecurityStateChangedEvent) event).getSecurityIsin()) &&
                        MatchingState.AUCTION.equals(((SecurityStateChangedEvent) event).getState())
        ));
        orderHandler.handleDeleteOrder(new DeleteOrderRq(1, security.getIsin(), BUY, 11));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 11, List.of(Message.CANT_DELETE_STOP_LIMIT_ORDER_IN_AUCTION_STATE)));
        verifyNoMoreInteractions(eventPublisher);
    }

    @Test
    void delete_order_publish_opening_price(){
        orders = Arrays.asList(
                new Order(1, security2, BUY, 304, 15700, broker, shareholder),
                new Order(2, security2, BUY, 600, 15500, broker, shareholder),
                new Order(3, security2, Side.SELL, 350, 15300, broker2, shareholder),
                new Order(4, security2, Side.SELL, 350, 15460, broker2, shareholder)

        );
        orders.forEach(order -> security2.getOrderBook().enqueueToActiveQueue(order));

        broker.increaseCreditBy(10_000_000);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(0,"AB",12, LocalDateTime.now(), SELL,4,15700,broker2.getBrokerId(),shareholder.getShareholderId(),0,0,0));
        verify(eventPublisher).publish(new OrderAcceptedEvent(0, 12));
        verify(eventPublisher, times(1)).publish(any(OrderExecutedEvent.class));
        assertThat(security2.getLastTradedPrice()).isEqualTo(15700);

        orderHandler.handleChangeMatchingStateRq(new ChangeMatchingStateRq(security2.getIsin(),MatchingState.AUCTION));

        orderHandler.handleDeleteOrder(new DeleteOrderRq(1, security2.getIsin(), BUY, 2));

        verify(eventPublisher, times(1)).publish(argThat(event ->
                event instanceof SecurityStateChangedEvent &&
                        security2.getIsin().equals(((SecurityStateChangedEvent) event).getSecurityIsin()) &&
                        MatchingState.AUCTION.equals(((SecurityStateChangedEvent) event).getState())
        ));

        verify(eventPublisher).publish(new OrderDeletedEvent(1,2));




        verify(eventPublisher, times(1)).publish(argThat(event ->
                event instanceof OpeningPriceEvent &&
                        security2.getIsin().equals(((OpeningPriceEvent) event).getSecurityIsin()) &&
                        15700 == ((OpeningPriceEvent) event).getOpeningPrice() &&
                        300 == ((OpeningPriceEvent) event).getTradableQuantity()
        ));
        verifyNoMoreInteractions(eventPublisher);


    }

}