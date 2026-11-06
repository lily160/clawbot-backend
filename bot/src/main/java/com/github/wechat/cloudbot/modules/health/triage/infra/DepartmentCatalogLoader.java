package com.github.wechat.cloudbot.modules.health.triage.infra;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Collections;
import java.util.List;

/**
 * 启动时加载 classpath:health/rag/departments.json 分科目录，缓存到内存供 TriageService 使用。
 */
@Component
public class DepartmentCatalogLoader {

    private static final Logger log = LoggerFactory.getLogger(DepartmentCatalogLoader.class);

    private static final String CATALOG_PATH = "health/rag/departments.json";

    private final ObjectMapper objectMapper;

    private List<DepartmentEntry> catalog = Collections.emptyList();

    public DepartmentCatalogLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void load() {
        try (InputStream is = new ClassPathResource(CATALOG_PATH).getInputStream()) {
            catalog = objectMapper.readValue(is, new TypeReference<List<DepartmentEntry>>() {});
            log.info("✅ 分科目录加载完成，共 {} 个科室", catalog.size());
        } catch (Exception e) {
            log.error("❌ 分科目录加载失败 path={}: {}", CATALOG_PATH, e.getMessage(), e);
            catalog = Collections.emptyList();
        }
    }

    /**
     * 获取已加载的分科目录（不可变）。
     *
     * @return 分科目录列表，加载失败时返回空列表
     */
    public List<DepartmentEntry> getCatalog() {
        return catalog;
    }
}
