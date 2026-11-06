package com.github.wechat.cloudbot.modules.health.common;

/**
 * 医院平台预约入口或提交回执。
 * <p>仅在真实号源/真实医院入口已确认时返回，不代表支付或实名环节已完成。</p>
 */
public record BookingReceipt(
        String hospitalName,
        String department,
        String visitTimeText,
        String orderNo,
        String bookingLink) {

    public BookingReceipt {
        if (hospitalName == null || hospitalName.isBlank()) {
            hospitalName = "未指定医院";
        }
        if (department == null || department.isBlank()) {
            department = "未指定科室";
        }
        if (visitTimeText == null || visitTimeText.isBlank()) {
            visitTimeText = "待确认";
        }
        if (orderNo == null || orderNo.isBlank()) {
            orderNo = "未生成";
        }
    }
}
