package com.diff.standalone.config;


import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.DynamicTableNameInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.diff.core.apply.PlanBuilder;
import com.diff.core.engine.TenantDiffEngine;
import com.diff.core.spi.apply.BusinessApplySupport;

import com.diff.standalone.datasource.DiffDataSourceAutoConfiguration;
import com.diff.standalone.apply.*;
import com.diff.standalone.model.StandaloneTenantModelBuilder;
import com.diff.standalone.persistence.TenantDiffTableNames;
import com.diff.standalone.snapshot.StandaloneSnapshotBuilder;
import com.diff.standalone.persistence.mapper.TenantDiffApplyRecordMapper;
import com.diff.standalone.persistence.mapper.TenantDiffResultMapper;
import com.diff.standalone.persistence.mapper.TenantDiffSessionMapper;
import com.diff.standalone.persistence.mapper.TenantDiffSnapshotMapper;
import com.diff.standalone.plugin.StandaloneBusinessTypePlugin;
import com.diff.standalone.plugin.StandalonePluginRegistry;
import com.diff.standalone.datasource.DiffDataSourceRegistry;
import com.diff.standalone.persistence.mapper.TenantDiffDecisionRecordMapper;
import com.diff.standalone.service.DecisionRecordService;
import com.diff.standalone.service.TenantDiffStandaloneApplyService;
import com.diff.standalone.service.TenantDiffStandaloneRollbackService;
import com.diff.standalone.service.TenantDiffStandaloneService;
import com.diff.standalone.service.impl.DecisionRecordServiceImpl;
import com.diff.standalone.service.impl.TenantDiffStandaloneApplyServiceImpl;
import com.diff.standalone.service.impl.TenantDiffStandaloneRollbackServiceImpl;
import com.diff.standalone.service.impl.TenantDiffStandaloneServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.lang.Nullable;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * Standalone tenant-diff 模块装配配置（MyBatis-Plus + Spring MVC，无 DAP 依赖）。
 *
 * <p>
 * <b>设计动机：</b>通过 {@code @ConditionalOnProperty(enabled=true)} 实现 opt-in 设计——只有显式开启时
 * 才装配该模块的 Bean，避免在未使用 tenant-diff 的项目中引入额外依赖和连接池，降低误用风险。
 * </p>
 *
 * <p>
 * 该配置负责将"对比引擎、计划构建器、业务插件、Apply/回滚编排、持久化 Mapper"等组件以 Spring Bean 形式组装起来，
 * 使得 standalone 模式可以通过 REST API 独立运行。
 * </p>
 *
 * <p>
 * <b>MapperScan 隔离：</b>框架 Mapper 绑定到独立的 {@code tenantDiffSqlSessionFactory}，
 * 避免与宿主应用的 MyBatis 配置互相干扰。
 * </p>
 *
 * <p>
 * 启用方式：设置 {@code tenant-diff.standalone.enabled=true}。
 * </p>
 *
 * @author tenant-diff
 * @since 2026-01-20
 */
@AutoConfiguration(after = DiffDataSourceAutoConfiguration.class)
@ConditionalOnProperty(prefix = "tenant-diff.standalone", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(TenantDiffProperties.class)
@ComponentScan(basePackages = {"com.diff.standalone.web.controller", "com.diff.standalone.web.handler"})
@MapperScan(
    basePackages = "com.diff.standalone.persistence.mapper",
    sqlSessionFactoryRef = "tenantDiffSqlSessionFactory"
)
public class TenantDiffStandaloneConfiguration {

    /**
     * 框架专属的 SqlSessionFactory，与宿主应用的 MyBatis 配置完全隔离。
     *
     * <p>使用 {@link DiffDataSourceRegistry} 的 primary DataSource，
     * 避免直接注入 {@code DataSource} 导致与宿主 SqlSessionFactory 竞争。
     * 同时为框架 5 张元数据表安装动态表名映射，使 {@code table-prefix} 配置既能影响建表脚本，
     * 也能影响运行时 Mapper 访问。</p>
     *
     * @param dataSourceRegistry 框架数据源注册表，提供 primary DataSource
     * @param properties tenant-diff 外部化配置，包含 schema.table-prefix
     * @return tenant-diff 专用的 SqlSessionFactory
     * @throws Exception SqlSessionFactory 初始化失败时抛出
     */
    @Bean("tenantDiffSqlSessionFactory")
    @ConditionalOnMissingBean(name = "tenantDiffSqlSessionFactory")
    public SqlSessionFactory tenantDiffSqlSessionFactory(
            DiffDataSourceRegistry dataSourceRegistry,
            TenantDiffProperties properties) throws Exception {
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSourceRegistry.getDataSource(null));
        factory.setTypeAliasesPackage("com.diff.standalone.persistence.entity");

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        factory.setConfiguration(configuration);

        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new DynamicTableNameInnerInterceptor(
            (sql, tableName) -> TenantDiffTableNames.resolvePhysicalTableName(
                tableName,
                properties.getSchema().getTablePrefix()
            )));
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        factory.setPlugins(interceptor);

        return factory.getObject();
    }

    @Bean
    @ConditionalOnMissingBean
    public TenantDiffEngine tenantDiffEngine() {
        return new TenantDiffEngine();
    }

    @Bean
    @ConditionalOnMissingBean
    public PlanBuilder planBuilder() {
        return new PlanBuilder();
    }

    @Bean
    public StandalonePluginRegistry standalonePluginRegistry(List<StandaloneBusinessTypePlugin> plugins) {
        return new StandalonePluginRegistry(plugins);
    }

    @Bean
    public StandaloneTenantModelBuilder standaloneTenantModelBuilder(StandalonePluginRegistry registry) {
        return new StandaloneTenantModelBuilder(registry);
    }

    @Bean
    public BusinessApplySupportRegistry standaloneApplySupportRegistry(List<BusinessApplySupport> supports) {
        return new BusinessApplySupportRegistry(supports);
    }

    @Bean
    public StandaloneApplyExecutor standaloneApplyExecutor(
        TenantDiffSessionMapper sessionMapper,
        TenantDiffResultMapper resultMapper,
        BusinessApplySupportRegistry supportRegistry,
        ObjectMapper objectMapper,
        DiffDataSourceRegistry dataSourceRegistry
    ) {
        return new SessionBasedApplyExecutor(sessionMapper, resultMapper, supportRegistry, objectMapper, dataSourceRegistry);
    }

    @Bean
    public StandaloneBusinessDiffApplyExecutor standaloneBusinessDiffApplyExecutor(
        BusinessApplySupportRegistry supportRegistry,
        DiffDataSourceRegistry dataSourceRegistry
    ) {
        return new InMemoryApplyExecutor(supportRegistry, dataSourceRegistry);
    }

    @Bean
    public StandaloneSnapshotBuilder standaloneSnapshotBuilder(StandaloneTenantModelBuilder modelBuilder, ObjectMapper objectMapper) {
        return new StandaloneSnapshotBuilder(modelBuilder, objectMapper);
    }

    @Bean
    public TenantDiffStandaloneService tenantDiffStandaloneService(
        TenantDiffSessionMapper sessionMapper,
        TenantDiffResultMapper resultMapper,
        StandaloneTenantModelBuilder modelBuilder,
        TenantDiffEngine diffEngine,
        ObjectMapper objectMapper,
        TransactionTemplate transactionTemplate,
        TenantDiffProperties properties
    ) {
        return new TenantDiffStandaloneServiceImpl(
            sessionMapper,
            resultMapper,
            modelBuilder,
            diffEngine,
            objectMapper,
            transactionTemplate,
            properties
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public DecisionRecordService decisionRecordService(TenantDiffDecisionRecordMapper decisionRecordMapper) {
        return new DecisionRecordServiceImpl(decisionRecordMapper);
    }

    @Bean
    public TenantDiffStandaloneApplyService tenantDiffStandaloneApplyService(
        TenantDiffApplyRecordMapper applyRecordMapper,
        TenantDiffSnapshotMapper snapshotMapper,
        TenantDiffSessionMapper sessionMapper,
        TenantDiffResultMapper resultMapper,
        StandaloneSnapshotBuilder snapshotBuilder,
        StandaloneApplyExecutor applyExecutor,
        PlanBuilder planBuilder,
        ObjectMapper objectMapper,
        @org.springframework.beans.factory.annotation.Value("${tenant-diff.apply.preview-action-limit:5000}")
        int previewActionLimit,
        @Nullable DecisionRecordService decisionRecordService
    ) {
        return new TenantDiffStandaloneApplyServiceImpl(
            applyRecordMapper,
            snapshotMapper,
            sessionMapper,
            resultMapper,
            snapshotBuilder,
            applyExecutor,
            planBuilder,
            objectMapper,
            previewActionLimit,
            decisionRecordService
        );
    }

    @Bean
    public TenantDiffStandaloneRollbackService tenantDiffStandaloneRollbackService(
        TenantDiffApplyRecordMapper applyRecordMapper,
        TenantDiffSnapshotMapper snapshotMapper,
        TenantDiffSessionMapper sessionMapper,
        StandaloneTenantModelBuilder modelBuilder,
        TenantDiffEngine diffEngine,
        PlanBuilder planBuilder,
        StandaloneBusinessDiffApplyExecutor diffApplyExecutor,
        ObjectMapper objectMapper,
        StandalonePluginRegistry pluginRegistry
    ) {
        return new TenantDiffStandaloneRollbackServiceImpl(
            applyRecordMapper,
            snapshotMapper,
            sessionMapper,
            modelBuilder,
            diffEngine,
            planBuilder,
            diffApplyExecutor,
            objectMapper,
            pluginRegistry
        );
    }
}
