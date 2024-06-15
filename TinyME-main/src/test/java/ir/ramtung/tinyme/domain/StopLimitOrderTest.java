package ir.ramtung.tinyme.domain;

import ir.ramtung.tinyme.config.MockedJMSTestConfig;
import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.domain.service.OrderHandler;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.event.*;
import ir.ramtung.tinyme.messaging.request.DeleteOrderRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
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
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
@Import(MockedJMSTestConfig.class)
class StopLimitOrderTest {
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
    void validate_stop_order_with_value_smaller_than_zero() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, security.getIsin(), 11, LocalDateTime.now(), Side.BUY, 500, 585, broker.getBrokerId(), shareholder.getShareholderId(), 0, 0,-10));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 11, List.of(Message.INVALID_STOP_PRICE)));
    }
    @Test
    void validate_stop_order_with_minimum_execution() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, security.getIsin(), 11, LocalDateTime.now(), Side.BUY, 500, 585, broker.getBrokerId(), shareholder.getShareholderId(), 0, 10,100));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 11, List.of(Message.STOP_LIMIT_ORDER_CAN_NOT_HAVE_MIN_EXEC_QUANTITY)));
    }
    @Test
    void validate_stop_order_with_peak_size() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, security.getIsin(), 11, LocalDateTime.now(), Side.BUY, 500, 585, broker.getBrokerId(), shareholder.getShareholderId(), 50, 0,100));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 11, List.of(Message.AN_ORDER_CAN_NOT_BE_BOTH_ICEBERG_AND_STOP_LIMIT)));
    }

    @Test
    void new_stop_order_which_has_not_enough_credit(){
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, security.getIsin(), 11, LocalDateTime.now(), Side.BUY, 100, 15000, broker.getBrokerId(), shareholder.getShareholderId(), 0, 0, 15000));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 11, List.of(Message.BUYER_HAS_NOT_ENOUGH_CREDIT)));
    }

    @Test
    void new_stop_order_which_has_not_enough_position(){
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, security.getIsin(), 12, LocalDateTime.now(), SELL, 100001, 15000, broker.getBrokerId(), shareholder.getShareholderId(), 0, 0, 15000));
        verify(eventPublisher).publish(new OrderRejectedEvent(2, 12, List.of(Message.SELLER_HAS_NOT_ENOUGH_POSITIONS)));
    }
    @Test
    void new_stop_order_which_go_in_inactive_queue(){
        broker2.increaseCreditBy(10_000_000L);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, security.getIsin(), 11, LocalDateTime.now(), Side.BUY, 10, 15800, broker2.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, security.getIsin(), 12, LocalDateTime.now(), Side.BUY, 100, 15000, broker2.getBrokerId(), shareholder.getShareholderId(), 0, 0, 16000));
        verify(eventPublisher).publish(new OrderAcceptedEvent(2, 12));
        verify(eventPublisher, never()).publish(any (OrderActivatedEvent.class));
        assertThat(broker2.getCredit()).isEqualTo(11_000_000 - (10*15800) - (100*15000));
    }

    @Test
    void new_buy_stop_order_activated(){
        broker.increaseCreditBy(10_000_000L);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, security.getIsin(), 11, LocalDateTime.now(), Side.BUY, 10, 15800, broker.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, security.getIsin(), 12, LocalDateTime.now(), Side.BUY, 100, 15800, broker.getBrokerId(), shareholder.getShareholderId(), 0, 0, 15500));
        verify(eventPublisher).publish(new OrderAcceptedEvent(2, 12));
        verify(eventPublisher).publish(new OrderActivatedEvent(2, 12));
        verify(eventPublisher, times(2)).publish(any(OrderExecutedEvent.class));
    }

    @Test
    void new_sell_stop_order_activated(){
        broker.increaseCreditBy(10_000_000L);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, security.getIsin(), 11, LocalDateTime.now(), Side.SELL, 10, 15650, broker.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, security.getIsin(), 12, LocalDateTime.now(), Side.SELL, 100, 15700, broker.getBrokerId(), shareholder.getShareholderId(), 0, 0, 15750));
        verify(eventPublisher).publish(new OrderAcceptedEvent(2, 12));
        verify(eventPublisher).publish(new OrderActivatedEvent(2, 12));
        verify(eventPublisher, times(2)).publish(any(OrderExecutedEvent.class));
    }

    @Test
    void add_new_order_that_active_2_order_in_inactive_queue(){
        orders = Arrays.asList(
                new Order(1, security2, BUY, 304, 15700, broker, shareholder),
                new Order(2, security2, BUY, 43, 15500, broker, shareholder),
                new Order(3, security2, BUY, 445, 15450, broker, shareholder),
                new Order(4, security2, BUY, 526, 15450, broker, shareholder),
                new Order(5, security2, BUY, 1000, 15400, broker, shareholder),
                new Order(6, security2, Side.SELL, 350, 15300, broker2, shareholder),
                new Order(7, security2, Side.SELL, 350, 15460, broker2, shareholder)

        );
        orders.forEach(order -> security2.getOrderBook().enqueueToActiveQueue(order));

        broker.increaseCreditBy(10_000_000);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(0,"AB",12, LocalDateTime.now(), BUY,350,15500,broker.getBrokerId(),shareholder.getShareholderId(),0,0,0));
        assertThat(security2.getLastTradedPrice()).isEqualTo(15300);
        orders = Arrays.asList(
                new StopLimitOrder(13, security2, BUY, 200, 15200, broker, shareholder, 15400, 1000),
                new StopLimitOrder(14, security2, BUY, 300, 16200, broker, shareholder, 15450, 1001),
                new StopLimitOrder(15, security2, BUY, 1000, 15400, broker, shareholder,15470, 1002),
                new StopLimitOrder(16, security2, BUY, 700, 15800, broker, shareholder,15700, 1003)
        );
        orders.forEach(order -> security2.getOrderBook().enqueueToInactiveQueue(order));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1,"AB",17, LocalDateTime.now(), BUY,100,15500,broker.getBrokerId(),shareholder.getShareholderId(),0,0,0));
        assertThat(security2.getLastTradedPrice()).isEqualTo(15460);
        assertThat(security2.getOrderBook().getInactiveBuyQueue().get(0).getOrderId()).isEqualTo(15);
        assertThat(security2.getOrderBook().getInactiveBuyQueue().get(1).getOrderId()).isEqualTo(16);
        verify(eventPublisher).publish(new OrderActivatedEvent(1000, 13));
        verify(eventPublisher).publish(new OrderActivatedEvent(1001, 14));
        verify(eventPublisher, times(3)).publish(any(OrderExecutedEvent.class));
        assertThat(broker2.getCredit()).isEqualTo(1_000_000 + 350*15300 + 100*15460 + 250*15460);
        assertThat(broker.getCredit()).isEqualTo(11_000_000 - 350*15300 - 100*15460 + 16200*300 - 250*15460 - 50*16200);
    }

    @Test
    void add_new_sell_order_that_active_2_order_in_inactive_queue(){
        orders = Arrays.asList(
                new Order(1, security2, BUY, 304, 15700, broker, shareholder),
                new Order(2, security2, BUY, 600, 15500, broker, shareholder),
                new Order(3, security2, Side.SELL, 350, 15300, broker2, shareholder),
                new Order(4, security2, Side.SELL, 350, 15460, broker2, shareholder)

        );
        orders.forEach(order -> security2.getOrderBook().enqueueToActiveQueue(order));

        broker.increaseCreditBy(10_000_000);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(0,"AB",12, LocalDateTime.now(), SELL,304,15700,broker2.getBrokerId(),shareholder.getShareholderId(),0,0,0));
        assertThat(security2.getLastTradedPrice()).isEqualTo(15700);
        orders = Arrays.asList(
                new StopLimitOrder(13, security2, SELL, 200, 15200, broker2, shareholder, 15200, 1000),
                new StopLimitOrder(14, security2, SELL, 100, 15450, broker2, shareholder, 15600, 1001),
                new StopLimitOrder(15, security2, SELL, 1000, 15400, broker2, shareholder,15470, 1002),
                new StopLimitOrder(16, security2, SELL, 700, 15800, broker2, shareholder,15650, 1003)
        );
        orders.forEach(order -> security2.getOrderBook().enqueueToInactiveQueue(order));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1,"AB",17, LocalDateTime.now(), SELL,100,15400,broker2.getBrokerId(),shareholder.getShareholderId(),0,0,0));
        assertThat(security2.getLastTradedPrice()).isEqualTo(15500);
        assertThat(security2.getOrderBook().getInactiveSellQueue().get(0).getOrderId()).isEqualTo(15);
        assertThat(security2.getOrderBook().getInactiveSellQueue().get(1).getOrderId()).isEqualTo(13);
        verify(eventPublisher).publish(new OrderActivatedEvent(1003, 16));
        verify(eventPublisher).publish(new OrderActivatedEvent(1001, 14));
        verify(eventPublisher, times(3)).publish(any(OrderExecutedEvent.class));
        assertThat(broker2.getCredit()).isEqualTo(1_000_000 + 304*15700 + 100*15500 + 100*15500);
        assertThat(broker.getCredit()).isEqualTo(11_000_000);
    }

    @Test
    void reject_update_an_active_order_stop_price(){
        broker.increaseCreditBy(10000000);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(0,"ABC",11, LocalDateTime.now(), BUY,100,15800,broker.getBrokerId(),shareholder.getShareholderId(),0,0,0));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1,"ABC",11, LocalDateTime.now(), BUY,500,15800,broker.getBrokerId(),shareholder.getShareholderId(),0,0,15750));
        verify(eventPublisher).publish(new OrderAcceptedEvent(1, 11));
        verify(eventPublisher).publish(new OrderActivatedEvent(1, 11));
        assertThat(security.getOrderBook().getBuyQueue().get(0).getOrderId()).isEqualTo(11);
        assertThat(security.getLastTradedPrice()).isEqualTo(15800);
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(2,"ABC",11, LocalDateTime.now(), BUY,500, 15800,broker.getBrokerId(),shareholder.getShareholderId(),0,0,15900));
        verify(eventPublisher).publish(new OrderRejectedEvent(2, 11 , List.of(Message.ORDER_ID_NOT_FOUND)));
    }

    @Test
    void update_an_inactive_order_stop_price_which_stays_inactive(){
        broker2.increaseCreditBy(10_000_000L);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1,"ABC",11, LocalDateTime.now(), BUY,350,15800,broker2.getBrokerId(),shareholder.getShareholderId(),0,0,0));
        orders = Arrays.asList(
                new StopLimitOrder(11, security, BUY, 200, 15200, broker2, shareholder, 15900),
                new StopLimitOrder(12, security, BUY, 300, 16200, broker2, shareholder, 15910),
                new StopLimitOrder(13, security, BUY, 1000, 15400, broker2, shareholder,15920),
                new StopLimitOrder(14, security, BUY, 700, 15800, broker2, shareholder,15910),
                new StopLimitOrder(15, security,SELL, 600, 15810, broker, shareholder,15740),
                new StopLimitOrder(16, security, SELL, 500, 15810, broker, shareholder,15700)
        );
        orders.forEach(order -> security.getOrderBook().enqueueToInactiveQueue(order));
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(2,"ABC",12, LocalDateTime.now(), BUY,300, 16820,broker2.getBrokerId(),shareholder.getShareholderId(),0,0,15930));
        verify(eventPublisher).publish(new OrderUpdatedEvent(2,12));
        verify(eventPublisher, never()).publish(any (OrderActivatedEvent.class));
        assertThat(security.getOrderBook().getInactiveBuyQueue().get(3).getOrderId()).isEqualTo(12);

        assertThat(broker2.getCredit()).isEqualTo(11_000_000 - 350*15800 - 300*620);
    }

    @Test
    void update_an_inactive_order_stop_price_to_become_active(){
        broker2.increaseCreditBy(10_000_000L);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1,"ABC",11, LocalDateTime.now(), BUY,350,15800,broker2.getBrokerId(),shareholder.getShareholderId(),0,0,0));
        orders = Arrays.asList(
                new StopLimitOrder(11, security, BUY, 200, 15200, broker2, shareholder, 15900),
                new StopLimitOrder(12, security, BUY, 300, 16200, broker2, shareholder, 15910),
                new StopLimitOrder(13, security, BUY, 1000, 15400, broker2, shareholder,15920),
                new StopLimitOrder(14, security, BUY, 700, 15800, broker2, shareholder,15910),
                new StopLimitOrder(15, security,SELL, 600, 15810, broker, shareholder,15740),
                new StopLimitOrder(16, security, SELL, 500, 15810, broker, shareholder,15700)
        );
        orders.forEach(order -> security.getOrderBook().enqueueToInactiveQueue(order));
        security.getOrderBook().enqueueToActiveQueue(new Order(17, security, BUY, 500, 16000, broker2, shareholder));
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(2,"ABC",15, LocalDateTime.now(), SELL,600, 15810,broker.getBrokerId(),shareholder.getShareholderId(),0,0,15820));
        verify(eventPublisher).publish(new OrderUpdatedEvent(2, 15));
        verify(eventPublisher).publish(new OrderActivatedEvent(2, 15));
        assertThat(security.getLastTradedPrice()).isEqualTo(16000);
        assertThat(security.getOrderBook().getSellQueue().get(2).getOrderId()).isEqualTo(15);
        assertThat(broker2.getCredit()).isEqualTo(11_000_000 - 350 *15800);
        assertThat(broker.getCredit()).isEqualTo(1_000_000 + 350*15800 + 500 *16000);
    }

    @Test
    void delete_an_inactive_stop_limit_buy_order(){
        broker2.increaseCreditBy(10_000_000L);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1,"ABC",11, LocalDateTime.now(), BUY,350,15800,broker2.getBrokerId(),shareholder.getShareholderId(),0,0,0));
        orders = Arrays.asList(
                new StopLimitOrder(11, security, BUY, 200, 15200, broker2, shareholder, 15900),
                new StopLimitOrder(12, security, BUY, 300, 16200, broker2, shareholder, 15910),
                new StopLimitOrder(13, security, BUY, 1000, 15400, broker2, shareholder,15920),
                new StopLimitOrder(14, security, BUY, 700, 15800, broker2, shareholder,15910),
                new StopLimitOrder(15, security,SELL, 600, 15810, broker, shareholder,15740),
                new StopLimitOrder(16, security, SELL, 500, 15810, broker, shareholder,15700)
        );
        orders.forEach(order -> security.getOrderBook().enqueueToInactiveQueue(order));
        orderHandler.handleDeleteOrder(new DeleteOrderRq(1, security.getIsin(), BUY, 11));
        assertThat(security.getOrderBook().getInactiveBuyQueue().size()).isEqualTo(3);
        verify(eventPublisher).publish(new OrderDeletedEvent(1, 11));
        verify(eventPublisher, never()).publish(any(OrderActivatedEvent.class));
        assertThat(broker2.getCredit()).isEqualTo(11_000_000 - 350*15800 + 200*15200);
    }


    @Test
    void delete_an_inactive_stop_limit_sell_order(){
        broker2.increaseCreditBy(10_000_000L);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(0,"ABC",11, LocalDateTime.now(), BUY,350,15800,broker2.getBrokerId(),shareholder.getShareholderId(),0,0,0));
        orders = Arrays.asList(
                new StopLimitOrder(11, security, BUY, 200, 15200, broker2, shareholder, 15900),
                new StopLimitOrder(12, security, BUY, 300, 16200, broker2, shareholder, 15910),
                new StopLimitOrder(13, security, BUY, 1000, 15400, broker2, shareholder,15920),
                new StopLimitOrder(14, security, BUY, 700, 15800, broker2, shareholder,15910),
                new StopLimitOrder(15, security,SELL, 600, 15810, broker, shareholder,15740),
                new StopLimitOrder(16, security, SELL, 500, 15810, broker, shareholder,15700)
        );
        orders.forEach(order -> security.getOrderBook().enqueueToInactiveQueue(order));
        long beforeDelete= broker.getCredit();
        orderHandler.handleDeleteOrder(new DeleteOrderRq(1, security.getIsin(), SELL, 15));
        assertThat(security.getOrderBook().getInactiveSellQueue().size()).isEqualTo(1);
        verify(eventPublisher).publish(new OrderDeletedEvent(1, 15));
        verify(eventPublisher, never()).publish(any(OrderActivatedEvent.class));
        assertThat(broker.getCredit()).isEqualTo(beforeDelete);
    }



    @Test
    void test_update_active_order_to_activate_another_stop_limit_order() {
        broker2.increaseCreditBy(100_000_000L);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1,"ABC",11, LocalDateTime.now(), BUY,10,15800,broker2.getBrokerId(),shareholder.getShareholderId(),0,0,0));
        List<Order> orders = Arrays.asList(
                new StopLimitOrder(11, security, BUY, 200, 15200, broker2, shareholder, 15900, 1001),
                new StopLimitOrder(12, security, BUY, 300, 16200, broker2, shareholder, 15910, 1002),
                new StopLimitOrder(13, security, BUY, 1000, 15400, broker2, shareholder,15920, 1003),
                new StopLimitOrder(14, security, BUY, 700, 15800, broker2, shareholder,15910, 1004),
                new StopLimitOrder(15, security, SELL, 600, 15810, broker, shareholder,15740, 1005),
                new StopLimitOrder(16, security, SELL, 500, 15810, broker, shareholder,15700, 1006)
        );

        orders.forEach(order -> security.getOrderBook().enqueueToInactiveQueue(order));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2,"ABC",11, LocalDateTime.now(), BUY,500,16000,broker2.getBrokerId(),shareholder.getShareholderId(),0,0,0));

        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(3,"ABC",15, LocalDateTime.now(), SELL,294, 15700,broker.getBrokerId(),shareholder.getShareholderId(),0,0,15810));


        verify(eventPublisher).publish(new OrderUpdatedEvent(3, 15));
        verify(eventPublisher).publish(new OrderActivatedEvent(3, 15));
        verify(eventPublisher).publish(new OrderActivatedEvent(1006, 16));
        assertThat(security.getLastTradedPrice()).isEqualTo(15700);
        assertThat(security.getOrderBook().getSellQueue().get(2).getOrderId()).isEqualTo(16);
        assertThat(broker2.getCredit()).isEqualTo(101_000_000 - 10 *15800-340*15800-160*15810);
        assertThat(broker.getCredit()).isEqualTo(1_000_000 +  10 *15800+ 340*15800+160*15810 + 294*15700 );
    }





}
