package com.github.wechat.cloudbot.modules.health.medication.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.wechat.cloudbot.modules.health.common.HealthDisclaimers;
import com.github.wechat.cloudbot.modules.health.medication.infra.MedicationProperties;
import com.github.wechat.cloudbot.modules.task.service.Reminder;
import com.github.wechat.cloudbot.modules.task.service.ReminderService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 用药管理服务：增删改查、OTC 冲突检测（含别名）、过敏风险、提醒注册。
 *
 * <p>持久化走 MySQL（Flyway {@code V7_1__health_medication.sql}），JDBC 风格对齐打卡模块，
 * 便于 H2 单测。疗程到期自动停用记录并清理提醒；提醒时间可自定义（HH:mm）。</p>
 */
@Component
public class MedicationService {

    private static final Logger log = LoggerFactory.getLogger(MedicationService.class);

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    /** 用药提醒内容中用于关联用药记录的标记前缀。 */
    static final String REMINDER_MARKER_PREFIX = "[记录ID:";

    /** 常见剂型后缀，药名归一化时剥除，提高 OTC/过敏匹配命中率。 */
    private static final List<String> DOSAGE_SUFFIXES = List.of(
            "肠溶片", "缓释片", "缓释胶囊", "分散片", "软胶囊", "咀嚼片", "含片",
            "胶囊", "片", "口服液", "注射液", "颗粒", "糖浆", "滴丸", "混悬液", "乳膏", "贴剂");

    private final ObjectMapper objectMapper;
    private final MedicationProperties medicationProperties;
    private final ReminderService reminderService;
    private final JdbcTemplate jdbc;

    /** OTC 配伍索引：药名/别名 -> 涉及该药的交互条目索引。 */
    private final Map<String, List<OtcInteraction>> otcIndex = new LinkedHashMap<>();
    /** 药名 -> 用药记录索引（用于快速 OTC 冲突检测）。 */
    private final Map<String, List<MedicationRecord>> drugIndex = new ConcurrentHashMap<>();
    /** 过敏样本索引：过敏原 -> 过敏条目。 */
    private final Map<String, List<AllergyEntry>> allergyIndex = new LinkedHashMap<>();

    public MedicationService(MedicationProperties medicationProperties,
                             ReminderService reminderService,
                             DataSource dataSource) {
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.medicationProperties = medicationProperties;
        this.reminderService = reminderService;
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @PostConstruct
    public void init() {
        loadOtcData();
        loadAllergyData();
        ensureTable();
        rebuildDrugIndex();
    }

    /** 建表（Flyway 已建，此处幂等兜底；单元测试中手动触发）。 */
    public void ensureTable() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS health_medication (
                    id VARCHAR(64) NOT NULL PRIMARY KEY,
                    wechat_user_id VARCHAR(128) NOT NULL,
                    drug_name VARCHAR(128) NOT NULL,
                    dosage VARCHAR(64) NOT NULL,
                    frequency VARCHAR(32) NOT NULL,
                    course_days INT NOT NULL DEFAULT 0,
                    notes VARCHAR(255) DEFAULT NULL,
                    reminder_times VARCHAR(128) DEFAULT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    enabled TINYINT(1) NOT NULL DEFAULT 1
                )
                """);
        try {
            jdbc.execute("CREATE INDEX idx_med_user_enabled ON health_medication(wechat_user_id, enabled)");
        } catch (Exception ignored) {
            // index may already exist on restart
        }
    }

    // ==================== OTC 冲突检测 ====================

    void loadOtcData() {
        otcIndex.clear();
        String path = medicationProperties.getOtcDataPath();
        try (InputStream is = new ClassPathResource(path).getInputStream()) {
            List<OtcRaw> rawList = objectMapper.readValue(is, new TypeReference<>() {});
            for (OtcRaw raw : rawList) {
                OtcInteraction interaction = new OtcInteraction(
                        raw.a(), raw.b(), raw.risk(), raw.level(), raw.note(),
                        raw.aliasesA(), raw.aliasesB());
                for (String key : indexKeysFor(raw.a(), raw.aliasesA())) {
                    otcIndex.computeIfAbsent(key, k -> new ArrayList<>()).add(interaction);
                }
                for (String key : indexKeysFor(raw.b(), raw.aliasesB())) {
                    otcIndex.computeIfAbsent(key, k -> new ArrayList<>()).add(interaction);
                }
            }
            int size = otcIndex.values().stream().mapToInt(List::size).sum();
            log.info("已加载 OTC 配伍数据 {} 条(含别名索引), path={}", size, path);
        } catch (IOException e) {
            log.warn("加载 OTC 数据失败: {}", e.getMessage());
        }
    }

    /** 生成某侧药品的索引键（主名 + 别名，均归一化去重）。 */
    private static List<String> indexKeysFor(String name, List<String> aliases) {
        List<String> keys = new ArrayList<>();
        String norm = normalizeDrugName(name);
        if (!norm.isEmpty()) {
            keys.add(norm);
        }
        if (aliases != null) {
            for (String alias : aliases) {
                String na = normalizeDrugName(alias);
                if (!na.isEmpty() && !keys.contains(na)) {
                    keys.add(na);
                }
            }
        }
        return keys;
    }

    /**
     * 检测两种药品间是否存在已知冲突（支持别名与剂型后缀归一化）。
     */
    public Optional<OtcInteraction> checkInteraction(String drugNameA, String drugNameB) {
        if (drugNameA == null || drugNameB == null || drugNameA.isBlank() || drugNameB.isBlank()) {
            return Optional.empty();
        }
        String normA = normalizeDrugName(drugNameA);
        String normB = normalizeDrugName(drugNameB);
        if (normA.equals(normB) || normA.isEmpty() || normB.isEmpty()) {
            return Optional.empty();
        }
        for (OtcInteraction otc : candidatesFor(normA, normB)) {
            if ((sideMatches(otc, true, normA) && sideMatches(otc, false, normB))
                    || (sideMatches(otc, true, normB) && sideMatches(otc, false, normA))) {
                return Optional.of(otc);
            }
        }
        return Optional.empty();
    }

    /** 判断某药是否命中交互条目的某一侧（主名或别名，均做归一化）。 */
    private static boolean sideMatches(OtcInteraction otc, boolean sideA, String drugName) {
        List<String> names = sideA ? namesOf(otc.a(), otc.aliasesA()) : namesOf(otc.b(), otc.aliasesB());
        return names.stream().anyMatch(n -> drugNameMatches(normalizeDrugName(n), drugName));
    }

    private static List<String> namesOf(String name, List<String> aliases) {
        List<String> names = new ArrayList<>();
        if (name != null && !name.isBlank()) {
            names.add(name);
        }
        if (aliases != null) {
            names.addAll(aliases);
        }
        return names;
    }

    /**
     * 收集可能与 (normA, normB) 相关的 OTC 条目，避免全量遍历。
     */
    private List<OtcInteraction> candidatesFor(String normA, String normB) {
        List<OtcInteraction> candidates = new ArrayList<>();
        collectCandidates(normA, candidates);
        collectCandidates(normB, candidates);
        return candidates;
    }

    private void collectCandidates(String normName, List<OtcInteraction> candidates) {
        List<OtcInteraction> direct = otcIndex.get(normName);
        if (direct != null) {
            candidates.addAll(direct);
            return;
        }
        // 精确名未命中时，用包含匹配兜底：药名包含索引 key，或索引 key 包含药名。
        for (String key : otcIndex.keySet()) {
            if (key.contains(normName) || normName.contains(key)) {
                candidates.addAll(otcIndex.get(key));
            }
        }
    }

    /**
     * 判断两个归一化后的药名是否视为同一种药（精确相等或包含关系）。
     */
    private static boolean drugNameMatches(String otcName, String drugName) {
        // 空药名直接不匹配，避免 otcName.contains("") 恒真导致的误报冲突
        if (otcName == null || drugName == null || otcName.isBlank() || drugName.isBlank()) {
            return false;
        }
        if (otcName.equals(drugName)) {
            return true;
        }
        // 短名（如「阿司匹林」）被长名（如「阿司匹林肠溶片」）包含，或反之
        return otcName.contains(drugName) || drugName.contains(otcName);
    }

    /** 药名归一化：去空白 + 剥剂型后缀。 */
    private static String normalizeDrugName(String name) {
        if (name == null) {
            return "";
        }
        String s = name.replaceAll("\\s+", "").trim();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String suffix : DOSAGE_SUFFIXES) {
                if (s.length() > suffix.length() && s.endsWith(suffix)) {
                    s = s.substring(0, s.length() - suffix.length());
                    changed = true;
                }
            }
        }
        return s;
    }

    /**
     * 重建药名→用药记录的倒排索引。
     */
    private void rebuildDrugIndex() {
        drugIndex.clear();
        List<MedicationRecord> all = jdbc.query(
                "SELECT * FROM health_medication WHERE enabled = 1",
                (rs, row) -> map(rs));
        for (MedicationRecord r : all) {
            String key = normalizeDrugName(r.drugName());
            drugIndex.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }
    }

    /**
     * 检测新药品与已记录药品之间的冲突。
     * 命中结果去重，并按 danger > warn > info 排序。
     */
    public List<OtcInteraction> checkInteractionsWithExisting(String userId, String newDrugName) {
        if (newDrugName == null || newDrugName.isBlank()) {
            return List.of();
        }
        String normName = normalizeDrugName(newDrugName);

        // 从 OTC 索引中找到新药的所有交互对象（精确名 + 包含兜底）
        List<OtcInteraction> interactions = new ArrayList<>();
        collectCandidates(normName, interactions);
        if (interactions.isEmpty()) {
            return List.of();
        }

        List<MedicationRecord> existing = list(userId);
        if (existing.isEmpty()) {
            return List.of();
        }
        List<String> existingNames = existing.stream()
                .map(r -> normalizeDrugName(r.drugName()))
                .toList();

        List<OtcInteraction> conflicts = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (OtcInteraction otc : interactions) {
            boolean newOnSideA = sideMatches(otc, true, normName);
            boolean newOnSideB = sideMatches(otc, false, normName);
            if (newOnSideA == newOnSideB) {
                continue;
            }
            List<String> partnerNames = newOnSideA
                    ? namesOf(otc.b(), otc.aliasesB())
                    : namesOf(otc.a(), otc.aliasesA());
            boolean hit = partnerNames.stream()
                    .map(MedicationService::normalizeDrugName)
                    .anyMatch(p -> existingNames.stream().anyMatch(e -> drugNameMatches(p, e)));
            if (hit && seen.add(otc.hashCode())) {
                conflicts.add(otc);
            }
        }
        conflicts.sort(Comparator.comparingInt(MedicationService::severityRank));
        return conflicts;
    }

    /** 风险级别排序：danger(0) > warn(1) > info(2)。 */
    private static int severityRank(OtcInteraction otc) {
        return switch (otc.level() == null ? "" : otc.level()) {
            case "danger" -> 0;
            case "warn" -> 1;
            default -> 2;
        };
    }

    // ==================== 过敏检测 ====================

    void loadAllergyData() {
        allergyIndex.clear();
        String path = medicationProperties.getAllergyDataPath();
        try (InputStream is = new ClassPathResource(path).getInputStream()) {
            List<AllergyRaw> rawList = objectMapper.readValue(is, new TypeReference<>() {});
            for (AllergyRaw raw : rawList) {
                AllergyEntry entry = new AllergyEntry(raw.allergen(), raw.drugs(), raw.risk(), raw.level(), raw.note());
                allergyIndex.computeIfAbsent(normalizeDrugName(raw.allergen()), k -> new ArrayList<>()).add(entry);
            }
            int size = allergyIndex.values().stream().mapToInt(List::size).sum();
            log.info("已加载过敏样本 {} 条, path={}", size, path);
        } catch (IOException e) {
            log.warn("加载过敏样本失败: {}", e.getMessage());
        }
    }

    /**
     * 检测药品是否命中档案过敏史。
     *
     * @param profileAllergies 健康档案中的过敏史（如 ["青霉素", "海鲜"]）
     * @param drugName         待录药品名称
     * @return 命中的过敏风险提示（按数据文件顺序，同一条目去重）
     */
    public List<AllergyWarning> checkAllergies(List<String> profileAllergies, String drugName) {
        if (profileAllergies == null || profileAllergies.isEmpty()
                || drugName == null || drugName.isBlank()) {
            return List.of();
        }
        String normDrug = normalizeDrugName(drugName);
        List<AllergyWarning> warnings = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (String allergy : profileAllergies) {
            if (allergy == null || allergy.isBlank()) {
                continue;
            }
            String normAllergy = normalizeDrugName(allergy);
            for (AllergyEntry entry : allergyCandidates(normAllergy)) {
                String normAllergen = normalizeDrugName(entry.allergen());
                if (!drugNameMatches(normAllergen, normAllergy)) {
                    continue;
                }
                for (String drug : entry.drugs()) {
                    if (drugNameMatches(normalizeDrugName(drug), normDrug)) {
                        if (seen.add(entry.hashCode())) {
                            warnings.add(new AllergyWarning(
                                    entry.allergen(), drugName, entry.risk(), entry.level(), entry.note()));
                        }
                        break;
                    }
                }
            }
        }
        return warnings;
    }

    /**
     * 收集可能命中某过敏原的过敏条目（精确名 + 包含兜底，风格对齐 OTC 索引）。
     */
    private List<AllergyEntry> allergyCandidates(String normAllergy) {
        List<AllergyEntry> direct = allergyIndex.get(normAllergy);
        if (direct != null) {
            return direct;
        }
        List<AllergyEntry> candidates = new ArrayList<>();
        for (String key : allergyIndex.keySet()) {
            if (key.contains(normAllergy) || normAllergy.contains(key)) {
                candidates.addAll(allergyIndex.get(key));
            }
        }
        return candidates;
    }

    // ==================== 用药记录管理 ====================

    /**
     * 添加用药记录（默认按频次内置时间提醒），同时注册周期性提醒。
     */
    public MedicationRecord add(String userId, String drugName, String dosage,
                                String frequency, int courseDays, String notes) {
        return add(userId, drugName, dosage, frequency, courseDays, notes, List.of());
    }

    /**
     * 添加用药记录，支持自定义每日提醒时间（需与频次对应的提醒数量一致，如每日两次传 2 个时间）。
     *
     * @throws IllegalArgumentException 时间格式非法时
     */
    public MedicationRecord add(String userId, String drugName, String dosage,
                                String frequency, int courseDays, String notes,
                                List<String> reminderTimes) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        if (drugName == null || drugName.isBlank()) {
            throw new IllegalArgumentException("药品名称不能为空");
        }
        List<String> times = normalizeTimes(reminderTimes);
        validateReminderTimeCount(frequency, times);
        int max = medicationProperties.getMaxMedicationsPerUser();
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM health_medication WHERE wechat_user_id = ? AND enabled = 1",
                Integer.class, userId);
        if (count != null && count >= max) {
            throw new IllegalStateException("用药记录数量已达上限 " + max + " 条");
        }

        MedicationRecord record = new MedicationRecord(
                UUID.randomUUID().toString().replace("-", ""),
                userId, drugName, dosage, frequency, courseDays,
                notes, times, LocalDateTime.now(), true);
        jdbc.update("""
                INSERT INTO health_medication
                    (id, wechat_user_id, drug_name, dosage, frequency, course_days, notes, reminder_times, created_at, enabled)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW(), 1)
                """,
                record.id(), userId, drugName, dosage, frequency, courseDays,
                notes, joinTimes(times));
        rebuildDrugIndex();
        registerReminder(userId, record.id(), drugName, dosage, frequency, courseDays, times);
        return record;
    }

    /**
     * 列出某用户所有启用中的用药记录（顺带把已到期记录自动停用）。
     */
    public List<MedicationRecord> list(String userId) {
        expireDueRecords(userId);
        return jdbc.query(
                "SELECT * FROM health_medication WHERE wechat_user_id = ? AND enabled = 1 ORDER BY created_at DESC",
                (rs, row) -> map(rs), userId);
    }

    /**
     * 自动停用已到期（疗程结束）的用药记录，并清理其关联提醒。
     *
     * @return 本次停用的记录
     */
    public List<MedicationRecord> expireDueRecords(String userId) {
        List<MedicationRecord> all = jdbc.query(
                "SELECT * FROM health_medication WHERE wechat_user_id = ? AND enabled = 1",
                (rs, row) -> map(rs), userId);
        List<MedicationRecord> expired = new ArrayList<>();
        for (MedicationRecord r : all) {
            if (isExpired(r)) {
                jdbc.update("UPDATE health_medication SET enabled = 0 WHERE id = ?", r.id());
                removeRemindersForRecord(userId, r.id());
                expired.add(r.disable());
            }
        }
        if (!expired.isEmpty()) {
            rebuildDrugIndex();
            log.info("已自动停用 userId={} 的 {} 条到期用药记录", userId, expired.size());
        }
        return expired;
    }

    /**
     * 定时扫描所有用户的到期用药记录并自动停用（疗程到期后不再推送提醒）。
     * <p>修复：原先 {@code expireDueRecords} 只在 {@code list()}/{@code getStats()} 被调用时触发，
     * 疗程到期后注册的每日提醒仍会持续推送。
     */
    @Scheduled(cron = "0 17,47 * * * *")
    public void sweepExpiredMedications() {
        try {
            List<String> userIds = jdbc.queryForList(
                    "SELECT DISTINCT wechat_user_id FROM health_medication WHERE enabled = 1",
                    String.class);
            int total = 0;
            for (String userId : userIds) {
                if (userId == null || userId.isBlank()) {
                    continue;
                }
                total += expireDueRecords(userId).size();
            }
            if (total > 0) {
                log.info("定时扫描停用到期用药记录，共停用 {} 条", total);
            }
        } catch (Exception e) {
            log.warn("定时扫描到期用药记录失败: {}", e.getMessage());
        }
    }

    private static boolean isExpired(MedicationRecord r) {
        return r.courseDays() > 0 && expiryDate(r).isBefore(LocalDate.now());
    }

    /** 疗程到期日 = 创建日期 + 疗程天数。 */
    private static LocalDate expiryDate(MedicationRecord r) {
        return r.createdAt().toLocalDate().plusDays(r.courseDays());
    }

    /**
     * 删除（禁用）用药记录，同步清理关联提醒。
     */
    public boolean delete(String userId, String recordId) {
        int rows = jdbc.update(
                "UPDATE health_medication SET enabled = 0 WHERE wechat_user_id = ? AND id = ? AND enabled = 1",
                userId, recordId);
        if (rows > 0) {
            rebuildDrugIndex();
            removeRemindersForRecord(userId, recordId);
            return true;
        }
        return false;
    }

    /**
     * 更新用药记录（剂量/频次/疗程/备注），更新后重新注册提醒。
     */
    public MedicationRecord update(String userId, String recordId, String dosage, String frequency,
                                   int courseDays, String notes) {
        return update(userId, recordId, dosage, frequency, courseDays, notes, null);
    }

    /**
     * 更新用药记录，可一并更新自定义提醒时间；reminderTimes 为 null 表示保持原值。
     */
    public MedicationRecord update(String userId, String recordId, String dosage, String frequency,
                                   int courseDays, String notes, List<String> reminderTimes) {
        List<MedicationRecord> existing = jdbc.query(
                "SELECT * FROM health_medication WHERE wechat_user_id = ? AND id = ? AND enabled = 1",
                (rs, row) -> map(rs), userId, recordId);
        if (existing.isEmpty()) {
            throw new IllegalArgumentException("未找到该用药记录");
        }
        MedicationRecord old = existing.get(0);
        List<String> times = reminderTimes == null ? old.reminderTimes() : normalizeTimes(reminderTimes);
        validateReminderTimeCount(frequency != null ? frequency : old.frequency(), times);
        MedicationRecord updated = new MedicationRecord(
                old.id(), old.userId(), old.drugName(),
                dosage != null ? dosage : old.dosage(),
                frequency != null ? frequency : old.frequency(),
                courseDays >= 0 ? courseDays : old.courseDays(),
                notes != null ? notes : old.notes(),
                times, old.createdAt(), true);
        jdbc.update("""
                UPDATE health_medication
                SET drug_name = ?, dosage = ?, frequency = ?, course_days = ?, notes = ?, reminder_times = ?
                WHERE id = ?
                """,
                updated.drugName(), updated.dosage(), updated.frequency(), updated.courseDays(),
                updated.notes(), joinTimes(times), updated.id());
        rebuildDrugIndex();
        removeRemindersForRecord(userId, updated.id());
        registerReminder(userId, updated.id(), updated.drugName(), updated.dosage(),
                updated.frequency(), updated.courseDays(), times);
        return updated;
    }

    // ==================== 用药统计 ====================

    /**
     * 用药统计信息。
     *
     * @param totalCount            启用中的总记录数
     * @param activeCount           启用中的记录数（与 totalCount 一致）
     * @param frequencyDistribution 频次分布（频次 → 数量）
     * @param nearCompletion        疗程中的记录
     * @param todayExpiring         今日到期的记录
     */
    public record MedicationStats(int totalCount, int activeCount,
                                  Map<String, Long> frequencyDistribution,
                                  List<String> nearCompletion,
                                  List<String> todayExpiring) {}

    /**
     * 获取某用户的用药统计信息。
     */
    public MedicationStats getStats(String userId) {
        List<MedicationRecord> records = list(userId);
        if (records.isEmpty()) {
            return new MedicationStats(0, 0, Map.of(), List.of(), List.of());
        }
        Map<String, Long> freqDist = records.stream()
                .collect(Collectors.groupingBy(MedicationRecord::frequency, Collectors.counting()));
        List<String> nearCompletion = records.stream()
                .filter(r -> r.courseDays() > 0)
                .map(r -> r.drugName() + "（疗程 " + r.courseDays() + " 天）")
                .toList();
        List<String> todayExpiring = records.stream()
                .filter(r -> r.courseDays() > 0 && expiryDate(r).isEqual(LocalDate.now()))
                .map(r -> r.drugName() + "（今日到期）")
                .toList();
        return new MedicationStats(records.size(), records.size(), freqDist, nearCompletion, todayExpiring);
    }

    // ==================== 提醒注册 ====================

    /**
     * 频次解析结果：一条用药提醒的时段标签和 cron 表达式。
     *
     * @param label 时段描述，如"早"、"中"、"晚"、""（单次）
     * @param cron  cron 表达式
     */
    record CronSchedule(String label, String cron) {}

    /**
     * 将中文频次解析为一条或多条 cron 调度。
     * 返回 List 以支持每日多次分别注册提醒。
     */
    static List<CronSchedule> parseFrequency(String frequency) {
        if (frequency == null || frequency.isBlank()) {
            return List.of();
        }
        String f = frequency.replaceAll("\\s+", "").trim();
        return switch (f) {
            case "每日一次", "一天一次", "每天一次", "qd", "每日" ->
                    List.of(new CronSchedule("", "0 0 8 * * ?"));
            case "每日两次", "每日二次", "一天两次", "每天两次", "bid" ->
                    List.of(new CronSchedule("早", "0 0 8 * * ?"), new CronSchedule("晚", "0 0 20 * * ?"));
            case "每日三次", "一天三次", "每天三次", "tid" ->
                    List.of(new CronSchedule("早", "0 0 8 * * ?"), new CronSchedule("中", "0 0 13 * * ?"), new CronSchedule("晚", "0 0 18 * * ?"));
            case "隔日一次", "qod" ->
                    List.of(new CronSchedule("", "0 0 8 */2 * ?"));
            case "每周一次", "qw", "每周" ->
                    List.of(new CronSchedule("", "0 0 8 ? * 1"));
            case "每晚一次", "睡前" ->
                    List.of(new CronSchedule("", "0 0 21 * * ?"));
            default -> {
                log.warn("未识别的频次: {}", frequency);
                yield List.of(new CronSchedule("", "0 0 8 * * ?"));
            }
        };
    }

    /**
     * 删除指定用药记录关联的全部定时提醒（按提醒内容中内嵌的记录 ID 标记匹配）。
     */
    void removeRemindersForRecord(String userId, String recordId) {
        if (recordId == null || recordId.isBlank()) {
            return;
        }
        String marker = reminderMarker(recordId);
        try {
            List<Reminder> reminders = reminderService.list(userId);
            for (Reminder r : reminders) {
                if (r.content().contains(marker)) {
                    reminderService.delete(userId, r.id());
                }
            }
        } catch (Exception e) {
            log.warn("清理用药提醒失败 userId={}, recordId={}: {}", userId, recordId, e.getMessage());
        }
    }

    private static String reminderMarker(String recordId) {
        if (recordId == null || recordId.isBlank()) {
            return "";
        }
        return REMINDER_MARKER_PREFIX + recordId + "]";
    }

    /**
     * 注册用药提醒。
     *
     * @return 成功注册的提醒条数
     * @throws IllegalStateException 提醒数量达上限等领域限制时向上抛出（用药记录已入库但提醒未注册，
     *                               由调用方/工具层如实告知用户，避免「已设置提醒」的虚假成功）
     */
    int registerReminder(String userId, String recordId, String drugName, String dosage,
                         String frequency, int courseDays, List<String> reminderTimes) {
        List<CronSchedule> schedules = parseFrequency(frequency);
        if (schedules.isEmpty()) {
            log.warn("无法解析频次，跳过提醒注册: frequency={}", frequency);
            return 0;
        }
        int registered = 0;
        for (int i = 0; i < schedules.size(); i++) {
            CronSchedule s = schedules.get(i);
            String label = s.label().isBlank() ? "" : "（" + s.label() + "）";
            String content = "⏰ 用药提醒：" + drugName + "（" + dosage + "）" + label
                    + reminderMarker(recordId);
            if (courseDays > 0) {
                content += "，共 " + courseDays + " 天";
            }
            content += "。吃完回复「已吃药」" + HealthDisclaimers.MEDICATION_DISCLAIMER;

            LocalTime custom = customTime(reminderTimes, i);
            String cron = custom != null ? dailyCron(custom) : s.cron();
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime triggerTime = custom != null
                    ? now.withHour(custom.getHour()).withMinute(custom.getMinute()).withSecond(0).withNano(0)
                    : now.withHour(hourOf(s.cron())).withMinute(0).withSecond(0).withNano(0);
            if (triggerTime.isBefore(now)) {
                triggerTime = triggerTime.plusDays(1);
            }
            try {
                reminderService.add(userId, content, triggerTime, 0, cron);
                registered++;
            } catch (IllegalStateException e) {
                // 领域限制（如提醒数量已达上限）：用药记录已入库，把失败如实上抛
                throw new IllegalStateException("用药已记录，但提醒设置失败：" + e.getMessage(), e);
            } catch (Exception e) {
                log.warn("注册第 {} 条用药提醒失败 userId={}, drugName={}: {}", i + 1, userId, drugName, e.getMessage());
            }
        }
        log.info("已为 userId={} 注册 {} 条用药提醒: {}", userId, registered, drugName);
        return registered;
    }

    /** 自定义提醒时间数量需与频次对应的提醒条数一致，否则多余的提醒会静默回退默认时间，形成混合时间表。 */
    private static void validateReminderTimeCount(String frequency, List<String> times) {
        if (times == null || times.isEmpty()) {
            return;
        }
        List<CronSchedule> schedules = parseFrequency(frequency);
        if (!schedules.isEmpty() && times.size() != schedules.size()) {
            throw new IllegalArgumentException(
                    "自定义提醒时间数量与频次不匹配：" + frequency + " 需要 " + schedules.size()
                            + " 个时间点，实际传入 " + times.size() + " 个");
        }
    }

    private static LocalTime customTime(List<String> reminderTimes, int index) {
        if (reminderTimes == null || index >= reminderTimes.size()) {
            return null;
        }
        String t = reminderTimes.get(index);
        return (t == null || t.isBlank()) ? null : LocalTime.parse(t.trim(), TIME_FORMATTER);
    }

    private static int hourOf(String cron) {
        return Integer.parseInt(cron.trim().split("\\s+")[2]);
    }

    private static String dailyCron(LocalTime time) {
        return "0 " + time.getMinute() + " " + time.getHour() + " * * ?";
    }

    /** 校验并归一化自定义提醒时间（HH:mm）。 */
    private static List<String> normalizeTimes(List<String> reminderTimes) {
        if (reminderTimes == null || reminderTimes.isEmpty()) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String t : reminderTimes) {
            if (t == null || t.isBlank()) {
                continue;
            }
            String clean = t.trim();
            try {
                LocalTime.parse(clean, TIME_FORMATTER);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("提醒时间格式应为 HH:mm，例如 08:00、20:00");
            }
            normalized.add(clean);
        }
        return List.copyOf(normalized);
    }

    private static String joinTimes(List<String> times) {
        return times == null || times.isEmpty() ? null : String.join(",", times);
    }

    // ==================== 行映射 ====================

    private static MedicationRecord map(ResultSet rs) throws SQLException {
        String timesRaw = rs.getString("reminder_times");
        List<String> times = timesRaw == null || timesRaw.isBlank()
                ? List.of()
                : List.of(timesRaw.split(","));
        return new MedicationRecord(
                rs.getString("id"),
                rs.getString("wechat_user_id"),
                rs.getString("drug_name"),
                rs.getString("dosage"),
                rs.getString("frequency"),
                rs.getInt("course_days"),
                rs.getString("notes"),
                times,
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getInt("enabled") == 1);
    }

    // ==================== 内部类型 ====================

    /**
     * OTC 配伍交互信息（含两侧别名，提升品牌名命中率）。
     */
    public record OtcInteraction(String a, String b, String risk, String level, String note,
                                 List<String> aliasesA, List<String> aliasesB) {
        public OtcInteraction {
            aliasesA = aliasesA == null ? List.of() : List.copyOf(aliasesA);
            aliasesB = aliasesB == null ? List.of() : List.copyOf(aliasesB);
        }

        public String displayText() {
            return "⚠️ " + a + " × " + b + "："
                    + risk
                    + (note != null && !note.isBlank() ? "（" + note + "）" : "")
                    + "\n" + HealthDisclaimers.MEDICATION_DISCLAIMER;
        }
    }

    /**
     * 过敏风险提示（命中档案过敏史时返回）。
     */
    public record AllergyWarning(String allergen, String drug, String risk, String level, String note) {
        public String displayText() {
            return "⚠️ 过敏风险：档案过敏史【" + allergen + "】× " + drug + "：" + risk
                    + (note != null && !note.isBlank() ? "（" + note + "）" : "")
                    + "\n" + HealthDisclaimers.MEDICATION_DISCLAIMER;
        }
    }

    /**
     * 过敏样本条目（allergen 为过敏原，drugs 为相关药品列表）。
     */
    record AllergyEntry(String allergen, List<String> drugs, String risk, String level, String note) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OtcRaw(String a, String b, String risk, String level, String note,
                          List<String> aliasesA, List<String> aliasesB) {}

    private record AllergyRaw(String allergen, List<String> drugs, String risk, String level, String note) {}
}
