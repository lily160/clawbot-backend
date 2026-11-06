package com.github.wechat.cloudbot.modules.health.medication.infra;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 用药管理模块配置属性（前缀 cloudbot.health.medication）。
 */
@ConfigurationProperties(prefix = "cloudbot.health.medication")
public class MedicationProperties {

    /** 是否启用用药管理模块。 */
    private boolean enabled = true;

    /** 每个用户最多保存多少条用药记录。 */
    private int maxMedicationsPerUser = 30;

    /** OTC 配伍数据文件路径（classpath）。 */
    private String otcDataPath = "health/otc/interactions-sample.json";

    /** 过敏样本数据文件路径（classpath）。 */
    private String allergyDataPath = "health/otc/allergies-sample.json";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getMaxMedicationsPerUser() { return maxMedicationsPerUser; }
    public void setMaxMedicationsPerUser(int maxMedicationsPerUser) { this.maxMedicationsPerUser = maxMedicationsPerUser; }

    public String getOtcDataPath() { return otcDataPath; }
    public void setOtcDataPath(String otcDataPath) { this.otcDataPath = otcDataPath; }

    public String getAllergyDataPath() { return allergyDataPath; }
    public void setAllergyDataPath(String allergyDataPath) { this.allergyDataPath = allergyDataPath; }
}
