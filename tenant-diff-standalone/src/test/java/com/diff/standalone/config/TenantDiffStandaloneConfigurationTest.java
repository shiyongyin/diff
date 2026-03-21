package com.diff.standalone.config;

import com.diff.standalone.datasource.DiffDataSourceRegistry;
import com.diff.standalone.persistence.mapper.TenantDiffSessionMapper;
import com.diff.standalone.persistence.entity.TenantDiffSessionPo;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import javax.sql.DataSource;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class TenantDiffStandaloneConfigurationTest {

    @Test
    void sqlSessionFactory_usesConfiguredTablePrefixForFrameworkMappers() throws Exception {
        DataSource ds = new EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .generateUniqueName(true)
            .build();

        TenantDiffProperties properties = new TenantDiffProperties();
        properties.setEnabled(true);
        properties.getSchema().setTablePrefix("custom_diff_");

        TenantDiffSchemaInitializer initializer = new TenantDiffSchemaInitializer(ds, properties, false);
        initializer.afterPropertiesSet();

        TenantDiffStandaloneConfiguration configuration = new TenantDiffStandaloneConfiguration();
        SqlSessionFactory sqlSessionFactory = configuration.tenantDiffSqlSessionFactory(
            new DiffDataSourceRegistry(ds),
            properties
        );
        assertNotNull(sqlSessionFactory);
        sqlSessionFactory.getConfiguration().addMapper(TenantDiffSessionMapper.class);

        TenantDiffSessionPo sessionPo = TenantDiffSessionPo.builder()
            .sessionKey("S-001")
            .sourceTenantId(1L)
            .targetTenantId(2L)
            .status("CREATED")
            .createdAt(LocalDateTime.of(2026, 3, 8, 12, 0))
            .version(0)
            .build();

        try (SqlSession sqlSession = sqlSessionFactory.openSession(true)) {
            TenantDiffSessionMapper mapper = sqlSession.getMapper(TenantDiffSessionMapper.class);
            assertEquals(1, mapper.insert(sessionPo));
            assertNotNull(sessionPo.getId());

            TenantDiffSessionPo loaded = mapper.selectById(sessionPo.getId());
            assertNotNull(loaded);
            assertEquals("S-001", loaded.getSessionKey());
            assertEquals("CREATED", loaded.getStatus());
        }

        JdbcTemplate jdbc = new JdbcTemplate(ds);
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM custom_diff_session", Integer.class);
        assertNotNull(count);
        assertEquals(1, count);
    }
}
