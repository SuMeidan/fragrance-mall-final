/**
 * 严肃声明：
 * 开源版本请务必保留此注释头信息，若删除我方将保留所有法律责任追究！
 * 本软件已申请软件著作权，受国家版权局知识产权以及国家计算机软件著作权保护！
 * 可正常分享和学习源码，不得用于违法犯罪活动，违者必究！
 * Copyright (c) 2019-2021 十三 all rights reserved.
 * 版权所有，侵权必究！
 */
package io.scent.order;

/** State codes/labels adopted from newbee-mall-api 859e28ab, NewBeeMallOrderStatusEnum.
 * SCENT removes mutable enum setters and validates every transition in OrderService. */
public enum OrderState {
    PENDING(0, "待支付"), PAID(1, "已支付"), PACKED(2, "配货完成"),
    SHIPPED(3, "出库成功"), COMPLETED(4, "交易成功"),
    BUYER_CLOSED(-1, "手动关闭"), EXPIRED(-2, "超时关闭"), ADMIN_CLOSED(-3, "商家关闭");

    private final int code;
    private final String label;
    OrderState(int code, String label) { this.code = code; this.label = label; }
    public int code() { return code; }

    public static String label(int code) {
        for (OrderState state : values()) { if (state.code == code) { return state.label; } }
        return "未知状态";
    }

    public static boolean valid(int code) {
        for (OrderState state : values()) { if (state.code == code) { return true; } }
        return false;
    }
}
