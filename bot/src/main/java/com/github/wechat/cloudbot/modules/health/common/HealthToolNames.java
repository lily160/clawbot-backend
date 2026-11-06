package com.github.wechat.cloudbot.modules.health.common;

/**
 * 健康模块全局工具名，防止多人协作时重名。
 */
public final class HealthToolNames {

    public static final String PROFILE_UPSERT = "health_profile_upsert";
    public static final String PROFILE_GET = "health_profile_get";
    public static final String PROFILE_REMIND = "health_profile_remind";
    public static final String PROFILE_LIST = "health_profile_list";
    public static final String TRIAGE_DEPARTMENT = "health_triage_department";
    public static final String BOOKING_RECOMMEND = "health_booking_recommend";
    public static final String BOOKING_CONFIRM = "health_booking_confirm";
    public static final String BOOKING_FOLLOWUP = "health_booking_followup";
    public static final String BOOKING_LIST = "health_booking_list";
    public static final String MED_ADD = "health_med_add";
    public static final String MED_LIST = "health_med_list";
    public static final String MED_DELETE = "health_med_delete";
    public static final String MED_UPDATE = "health_med_update";
    public static final String MED_STATS = "health_med_stats";
    public static final String MED_OTC_CHECK = "health_med_otc_check";
    public static final String MED_TAKE = "health_med_take";
    public static final String DAILY_REMINDER = "health_daily_reminder";
    public static final String CHECKIN_ADD = "health_checkin_add";
    public static final String CHECKIN_TODAY = "health_checkin_today";
    public static final String REMINDER_LIST = "health_reminder_list";
    public static final String REMINDER_CANCEL = "health_reminder_cancel";
    public static final String CHECKUP_PLAN = "health_checkup_plan";
    public static final String REPORT_PARSE = "health_report_parse";

    public static final String[] ALL = {
            PROFILE_UPSERT,
            PROFILE_GET,
            PROFILE_REMIND,
            PROFILE_LIST,
            TRIAGE_DEPARTMENT,
            BOOKING_RECOMMEND,
            BOOKING_CONFIRM,
            BOOKING_FOLLOWUP,
            BOOKING_LIST,
            MED_ADD,
            MED_LIST,
            MED_DELETE,
            MED_UPDATE,
            MED_STATS,
            MED_OTC_CHECK,
            MED_TAKE,
            DAILY_REMINDER,
            CHECKIN_ADD,
            CHECKIN_TODAY,
            REMINDER_LIST,
            REMINDER_CANCEL,
            CHECKUP_PLAN,
            REPORT_PARSE
    };

    private HealthToolNames() {
    }
}
