package io.scent.settlement;

import io.scent.settlement.SettlementModels.*;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface SettlementMapper {
    @Select("SELECT * FROM payment WHERE order_id=#{id}")
    Payment payment(long id);

    @Insert("INSERT INTO payment(order_id,app_id,seller_id) VALUES(#{id},#{app},#{seller})")
    void issue(@Param("id") long id, @Param("app") String app, @Param("seller") String seller);

    @Select("SELECT id FROM mall_order WHERE order_no=#{number}")
    Long orderId(String number);

    @Update("UPDATE payment SET status='SUCCESS',trade_no=#{trade},amount_cents=#{cents},success_at=#{at},needs_review=#{late} WHERE order_id=#{id} AND status<>'SUCCESS'")
    int paid(@Param("id") long id, @Param("trade") String trade, @Param("cents") long cents, @Param("at") Instant at, @Param("late") boolean late);

    @Update("UPDATE mall_order SET status=1,paid_at=#{at} WHERE id=#{id} AND status=0")
    int markOrderPaid(@Param("id") long id, @Param("at") Instant at);

    @Update("UPDATE payment SET status='CLOSED' WHERE order_id=#{id} AND status='PENDING'")
    int closePayment(long id);

    @Update("UPDATE payment SET checked_at=UTC_TIMESTAMP(6) WHERE order_id=#{id}")
    void checked(long id);

    @Update("UPDATE payment SET needs_review=FALSE WHERE order_id=#{id} AND status='SUCCESS'")
    void resolved(long id);

    @Select("SELECT COUNT(*) FROM payment WHERE order_id=#{id} AND status<>'CLOSED'")
    int paymentBlocksClose(long id);

    @Select("SELECT COUNT(*) FROM after_sale WHERE order_id=#{id} AND state<>'REJECTED'")
    int blocksFulfillment(long id);

    @Select("SELECT a.*,o.order_no,o.status AS order_status,o.total_cents FROM after_sale a JOIN mall_order o ON o.id=a.order_id WHERE a.order_id=#{id}")
    AfterSale saleForOrder(long id);

    @Select("SELECT a.*,o.order_no,o.status AS order_status,o.total_cents FROM after_sale a JOIN mall_order o ON o.id=a.order_id WHERE a.id=#{id}")
    AfterSale sale(long id);

    @Insert("INSERT INTO after_sale(order_id,reason,state) VALUES(#{orderId},#{reason},#{state})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void apply(AfterSale sale);

    @Update("UPDATE after_sale SET state=#{state},review_note=#{note},reviewed_by=#{actor},reviewed_at=UTC_TIMESTAMP(6) WHERE id=#{id} AND state='REQUESTED'")
    int decision(@Param("id") long id, @Param("state") String state, @Param("note") String note, @Param("actor") long actor);

    @Update("UPDATE after_sale SET return_carrier=#{carrier},return_tracking_no=#{tracking} WHERE id=#{id} AND state='RETURN_REQUIRED' AND received_at IS NULL")
    int returning(@Param("id") long id, @Param("carrier") String carrier, @Param("tracking") String tracking);

    @Update("UPDATE after_sale SET state='REFUND_PENDING',received_at=UTC_TIMESTAMP(6) WHERE id=#{id} AND state='RETURN_REQUIRED' AND return_tracking_no IS NOT NULL")
    int received(long id);

    @Update("UPDATE after_sale SET state='REFUNDED' WHERE id=#{id} AND state='REFUND_PENDING'")
    int refunded(long id);

    @Update("UPDATE after_sale SET restocked=TRUE WHERE id=#{id} AND state='REFUNDED' AND restocked=FALSE")
    int restocked(long id);

    @Select("SELECT a.*,o.order_no,o.status AS order_status,o.total_cents FROM after_sale a JOIN mall_order o ON o.id=a.order_id WHERE (#{state} IS NULL OR a.state=#{state}) ORDER BY a.id DESC LIMIT #{size} OFFSET #{offset}")
    List<AfterSale> sales(@Param("state") String state, @Param("offset") int offset, @Param("size") int size);

    @Select("SELECT COUNT(*) FROM after_sale WHERE (#{state} IS NULL OR state=#{state})")
    long saleCount(String state);

    @Select("SELECT * FROM refund WHERE after_sale_id=#{id}")
    Refund refund(long id);

    @Insert("INSERT INTO refund(after_sale_id,request_no,amount_cents) VALUES(#{id},#{request},#{cents})")
    void refundIntent(@Param("id") long id, @Param("request") String request, @Param("cents") long cents);

    @Update("UPDATE refund SET status='SUCCESS',success_at=#{at},last_error_code=NULL WHERE after_sale_id=#{id} AND status<>'SUCCESS'")
    int refundSuccess(@Param("id") long id, @Param("at") Instant at);

    @Update("UPDATE refund SET status=#{status},last_error_code=#{code},updated_at=UTC_TIMESTAMP(6) WHERE after_sale_id=#{id} AND status<>'SUCCESS'")
    void refundPending(@Param("id") long id, @Param("status") String status, @Param("code") String code);

    @Select("SELECT after_sale_id FROM refund WHERE status='PENDING' ORDER BY updated_at,after_sale_id LIMIT 10")
    List<Long> pendingRefunds();

    @Select("SELECT p.order_id FROM payment p JOIN mall_order o ON o.id=p.order_id WHERE p.status='PENDING' AND o.status=0 ORDER BY p.checked_at,p.order_id LIMIT 5")
    List<Long> pendingPayments();

    @Select("SELECT r.*,a.display_name FROM review r JOIN order_item i ON i.id=r.order_item_id JOIN mall_order o ON o.id=i.order_id JOIN account a ON a.id=o.account_id WHERE i.order_id=#{id} ORDER BY r.id")
    List<Review> orderReviews(long id);

    @Select("SELECT r.*,a.display_name FROM review r JOIN order_item i ON i.id=r.order_item_id JOIN mall_order o ON o.id=i.order_id JOIN account a ON a.id=o.account_id WHERE i.product_id=#{id} ORDER BY r.id DESC LIMIT #{size} OFFSET #{offset}")
    List<Review> productReviews(@Param("id") long id, @Param("offset") int offset, @Param("size") int size);

    @Select("SELECT COUNT(*) FROM review r JOIN order_item i ON i.id=r.order_item_id WHERE i.product_id=#{id}")
    long reviewCount(long id);

    @Insert("INSERT INTO review(order_item_id,stars,content) VALUES(#{item},#{stars},#{content})")
    void review(@Param("item") long item, @Param("stars") int stars, @Param("content") String content);

    @Select("SELECT DATE(CONVERT_TZ(success_at,'+00:00','+08:00')) AS date,SUM(amount_cents) AS cents,COUNT(*) AS count FROM payment WHERE status='SUCCESS' AND success_at>=#{from} AND success_at<#{until} GROUP BY date ORDER BY date")
    List<Total> paidDays(@Param("from") Instant from, @Param("until") Instant until);

    @Select("SELECT DATE(CONVERT_TZ(success_at,'+00:00','+08:00')) AS date,SUM(amount_cents) AS cents,COUNT(*) AS count FROM refund WHERE status='SUCCESS' AND success_at>=#{from} AND success_at<#{until} GROUP BY date ORDER BY date")
    List<Total> refundDays(@Param("from") Instant from, @Param("until") Instant until);
}
